package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.guard.GuardVerdict
import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionOutcome
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.action.execution
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ScriptWebPort
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.cap.web.FakeWebPort
import com.wangzan101.mika.core.cap.web.ScriptRun
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.WEB_SCRIPT_TOOL
import com.wangzan101.mika.core.assembly.toolDispatcher

class ScriptStepsTest {
    private val events = mutableListOf<RunEvent>()
    private val sink = object : RunSink { override fun event(e: RunEvent) { events += e } }
    private var gates = 0

    private fun shop() = FakeWebPort().apply {
        elements = listOf(
            UiElement("@e1", "button", "搜索", 540, 120, listOf(0, 0, 999, 100), "#go", true, false, false),
            UiElement("@e2", "button", "提交订单", 540, 600, listOf(0, 200, 999, 300), "#order", true, false, false),
        )
    }

    private suspend fun onWeb(web: FakeWebPort) = toolDispatcher(FakeScreen(), web = ScriptWebPort(web)).also {
        it.execute(Part.ToolCall("n", "web_navigate", """{"url":"https://shop.example.com/"}"""))
    }

    /** 页面脚本的子步:经执行入口,来源为 Script;守卫结论攒下来供断言。 */
    private class Steps(val executor: ActionExecutor, val step: Int) {
        val verdicts = mutableListOf<GuardVerdict>()
        suspend fun run(call: Part.ToolCall) = executor.run(call, Origin.Script(step)).also { o ->
            verdicts += o.before
            if (o is ActionOutcome.Ran) verdicts += o.after
        }.execution()
    }

    private fun steps(d: ToolDispatcher, guards: OperationGuards = OperationGuards()) =
        Steps(ActionExecutor(d, guards, com.wangzan101.mika.core.gate.ConfirmationGate(), gate = { gates++ }, recorder = ActionRecorder(sink)), step = 4)

    @Test fun ordinaryActionRunsAndIsRecordedAsScriptStep() = runTest {
        val web = shop()
        val d = onWeb(web)
        val s = steps(d)
        val snap = s.run(Part.ToolCall("c1.1", "web_list_elements", "{}")).content
        val sid = snap["snapshotId"]!!.jsonPrimitive.content
        val tap = s.run(Part.ToolCall("c1.2", "web_tap", """{"ref":"@e1","snapshotId":"$sid"}""")).content
        assertEquals(JsonPrimitive(true), tap["ok"])
        assertEquals(1, gates) // 只有写操作过锁屏关卡
        assertTrue(web.calls.any { it.startsWith("tapRef @e1") })
        val results = events.filterIsInstance<RunEvent.ToolResult>()
        assertEquals(listOf("script", "script"), results.map { it.source })
        assertEquals(listOf("c1.1", "c1.2"), results.map { it.callId })
        assertEquals(listOf(4, 4), results.map { it.step })
    }

    @Test fun irreversibleActionIsNotRun() = runTest {
        val web = shop()
        val d = onWeb(web)
        val s = steps(d)
        val sid = s.run(Part.ToolCall("c1.1", "web_list_elements", "{}")).content["snapshotId"]!!.jsonPrimitive.content
        val r = s.run(Part.ToolCall("c1.2", "web_tap", """{"ref":"@e2","snapshotId":"$sid"}""")).content
        assertEquals("confirmation_required", r["error"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), r["executed"])
        assertFalse(web.calls.any { it.startsWith("tapRef") })
        assertEquals(1, gates) // 先过关卡再按此刻的屏幕判
        assertEquals("required", events.filterIsInstance<RunEvent.Confirmation>().single().decision)
        assertEquals(1, events.filterIsInstance<RunEvent.ToolResult>().size) // 没执行的不记成工具结果
    }

    @Test fun startedActionFinishesAndIsRecordedEvenIfCancelled() = runTest {
        val web = shop()
        val slow = object : com.wangzan101.mika.core.cap.web.WebPort by web {
            override suspend fun tapRef(ref: String, snapshotId: String): com.wangzan101.mika.core.uistate.ActionResult {
                kotlinx.coroutines.delay(5_000); return web.tapRef(ref, snapshotId)
            }
        }
        val d = toolDispatcher(FakeScreen(), web = slow)
        d.execute(Part.ToolCall("n", "web_navigate", """{"url":"https://shop.example.com/"}"""))
        val s = steps(d)
        val sid = s.run(Part.ToolCall("c1.1", "web_list_elements", "{}")).content["snapshotId"]!!.jsonPrimitive.content
        val job = launch { s.run(Part.ToolCall("c1.2", "web_tap", """{"ref":"@e1","snapshotId":"$sid"}""")) }
        kotlinx.coroutines.delay(1_000)
        job.cancel() // 脚本超时、任务取消:动作已经开始
        job.join()
        assertTrue(web.calls.any { it.startsWith("tapRef @e1") })
        assertEquals(listOf("c1.1", "c1.2"), events.filterIsInstance<RunEvent.ToolResult>().map { it.callId })
    }

    @Test fun repeatedScriptActionsAreNotStoppedByTheirSignature() = runTest {
        val d = onWeb(shop())
        val g = OperationGuards()
        val s = steps(d, g)
        // 同一个动作反复执行不因签名相同而叫停
        var last: JsonObject? = null
        repeat(14) { last = s.run(Part.ToolCall("c1.$it", "web_tap", """{"x":500,"y":50}""")).content }
        assertTrue(last!!["error"]?.jsonPrimitive?.content != "guard_stopped")
        assertTrue(s.verdicts.none { it.code == "repeating_action" })
    }

    @Test fun loopRunsScriptActionsThroughTheGate() = runTest {
        val web = shop()
        val port = ScriptWebPort(web) { b ->
            b.call("snapshot", JsonObject(emptyMap()))
            b.call("tap", buildJsonObject { put("ref", "@e1") })
            b.call("snapshot", JsonObject(emptyMap()))
            b.call("tap", buildJsonObject { put("ref", "@e2") })
            ScriptRun.Done("\"unreachable\"")
        }
        val model = FakeModel(
            ModelReply(listOf(Part.ToolCall("a", "web_navigate", """{"url":"https://shop.example.com/"}""")), StopReason.ToolUse, null),
            ModelReply(listOf(Part.ToolCall("b", WEB_SCRIPT_TOOL, """{"code":"await mika.tap('@e2')"}""")), StopReason.ToolUse, null),
            com.wangzan101.mika.core.testing.answerReply("ok"),
        )
        AgentLoop(model, toolDispatcher(FakeScreen(), web = port), "BASE", sink = sink).run("搜一下再发")
        assertEquals(1, web.calls.count { it.startsWith("tapRef") })
        val scripted = events.filterIsInstance<RunEvent.ToolResult>().filter { it.source == "script" }
        assertEquals(listOf("b.1", "b.2", "b.3"), scripted.map { it.callId })
        assertTrue(events.any { it is RunEvent.Confirmation })
        val result = model.requests[2].last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().single()
        val o = Json.parseToJsonElement(result.content).jsonObject
        assertEquals("confirmation_required", o["error"]!!.jsonPrimitive.content)
        // 整个脚本调用本身也照常记一条
        assertTrue(events.filterIsInstance<RunEvent.ToolResult>().any { it.tool == WEB_SCRIPT_TOOL && it.callId == "b" && it.source == "model" })
    }
}
