package com.wangzan101.mika.core.replay

import com.wangzan101.mika.core.record.RunEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.TOOLS

class RouteRecorderTest {
    private val goal = "在备忘录里写今天天气不错"
    private val home = Anchors("com.notes", listOf("备忘录", "全部笔记"))
    private val editor = Anchors("com.notes", listOf("新笔记", "完成"))
    private val newBtn = Selector("button", "新建", "n:id/new", listOf(800, 900, 999, 999), 0)
    private val field = Selector("edit", "", "n:id/body", listOf(0, 120, 999, 900), 0)

    private fun target(step: Int, tool: String, args: String, sel: Selector? = null, before: Anchors = home, after: Anchors = editor, effective: Boolean = true,
                       callId: String = "c$step", protected: Boolean = false) =
        RunEvent.ActionTarget(step.toLong(), step, tool, args, sel, before, after, effective, callId, protected)

    private fun result(step: Int, tool: String, json: String, callId: String = "c$step") =
        RunEvent.ToolResult(step.toLong(), step, tool, json, true, null, callId = callId)

    /** 一次核查通过的成功运行;[middle] 插在动作之间。 */
    private fun run(vararg middle: RunEvent, typed: String = "今天天气不错", typedResult: String = """{"ok":true,"typed":6,"secret":false,"verification":"match"}""",
                    goal: String = this.goal) = listOf(
        RunEvent.TaskStarted(0, goal, "m"),
        RunEvent.Criteria(1, listOf("写了"), emptyList()),
        target(1, "phone_launch_app", """{"package":"com.notes"}""", before = Anchors(null, emptyList()), after = home),
        result(1, "phone_launch_app", """{"ok":true}"""),
        RunEvent.ToolResult(2, 2, "phone_observe", """{"ok":true,"currentApp":"com.notes"}""", null, 1),
        target(3, "phone_tap", """{"ref":"@e2","snapshotId":"s1"}""", newBtn),
        result(3, "phone_tap", """{"ok":true,"changed":true}"""),
        *middle,
        target(4, "phone_type_text", """{"ref":"@e9","snapshotId":"s2","text":"$typed"}""", field, before = editor, after = Anchors("com.notes", listOf("保存"))),
        result(4, "phone_type_text", typedResult),
        // 另一次调用的结果(同一步号)不能拿来配对
        result(4, "phone_type_text", """{"ok":true,"secret":false}""", callId = "other"),
        RunEvent.Verifier(5, "pass", "{}"),
        RunEvent.TaskEnded(6, "succeeded", "写好了"),
    )

    @Test fun recordsOnlyEffectiveWrites() {
        val events = run(
            target(3, "phone_tap", """{"x":10,"y":10}""", effective = false),
            result(3, "phone_tap", """{"ok":true,"changed":false}"""),
            target(3, "phone_scroll", """{"direction":"down"}""", effective = false),
        )
        val r = RouteRecorder.record(goal, events, nowMs = 99, tools = TOOLS)!!
        assertEquals(listOf("phone_launch_app", "phone_tap", "phone_type_text"), r.steps.map { it.tool })
        assertEquals(listOf("com.notes"), r.packages)
        assertEquals(99, r.lastUsedMs)
        // 编号是一次读屏内的,路线里只留选择器
        val tap = Json.parseToJsonElement(r.steps[1].argsJson).jsonObject
        assertFalse("ref" in tap); assertFalse("snapshotId" in tap)
        assertEquals(newBtn, r.steps[1].target)
    }

    @Test fun runsThatUsedTheBrowserAreNotRecorded() {
        // 网页任务:用过 web_navigate,之后在网页上点的是共用的 tap,也不记
        val events = run(
            RunEvent.ModelReply(3, 3, "", "web_navigate", "{}", null, null, null),
            result(3, "web_navigate", """{"ok":true,"surface":"web"}"""),
        )
        assertNull(RouteRecorder.record(goal, events, 0, TOOLS))
    }

    @Test fun notVerifiedPassMeansNoRoute() {
        val failed = run().map { if (it is RunEvent.Verifier) RunEvent.Verifier(5, "cannot_verify", "{}") else it }
        assertNull(RouteRecorder.record(goal, failed, 1, TOOLS))
        assertNull(RouteRecorder.record(goal, run().filter { it !is RunEvent.Verifier }, 1, TOOLS))
        val ended = run().map { if (it is RunEvent.TaskEnded) RunEvent.TaskEnded(6, "failed", "x") else it }
        assertNull(RouteRecorder.record(goal, ended, 1, TOOLS))
    }

    @Test fun typedTextInGoalBecomesSlot() {
        val r = RouteRecorder.record(goal, run(), 1, TOOLS)!!
        assertEquals("在备忘录里写{1}", r.template)
        assertEquals(1, r.slots)
        val typed = Json.parseToJsonElement(r.steps[2].argsJson).jsonObject
        assertEquals("{1}", typed["text"]!!.jsonPrimitive.content)
        assertFalse(r.steps.any { "今天天气不错" in it.argsJson })
    }

    @Test fun typedTextNotASlotMeansNoRoute() {
        // 不在原话里、太短、在原话里出现不止一次、被更长的槽吞掉:都不记
        assertNull(RouteRecorder.record(goal, run(typed = "日记"), 1, TOOLS))
        assertNull(RouteRecorder.record("写个a", run(typed = "a", goal = "写个a"), 1, TOOLS))
        val twice = "写今天天气不错,今天天气不错"
        assertNull(RouteRecorder.record(twice, run(goal = twice), 1, TOOLS))
        val swallowed = run(target(3, "phone_type_text", """{"text":"天气"}""", field, callId = "s"), result(3, "phone_type_text", """{"ok":true,"secret":false}""", callId = "s"))
        assertNull(RouteRecorder.record(goal, swallowed, 1, TOOLS))
    }

    @Test fun targetNameInGoalBecomesSlot() {
        val memo = Selector("button", "备忘录", null, listOf(0, 0, 100, 100), 0, uniqueAtRecord = true)
        val events = run(target(3, "phone_tap", """{"ref":"@e1","snapshotId":"s1","target":"备忘录图标"}""", memo, callId = "m"),
            result(3, "phone_tap", """{"ok":true,"changed":true}""", callId = "m"))
        val r = RouteRecorder.record(goal, events, 1, TOOLS)!!
        assertEquals("在{1}里写{2}", r.template)
        assertEquals(2, r.slots)
        val tap = r.steps.first { it.target?.role == "button" && it.target.resourceId == null }
        assertEquals(memo.copy(name = "{1}"), tap.target)
        // 模型写的 target 描述不存
        assertFalse("target" in Json.parseToJsonElement(tap.argsJson).jsonObject)
        // 一个字的名字碰巧在原话里:没法可靠绑槽,不记
        val one = Selector("button", "写", null, listOf(0, 0, 1, 1), 0)
        assertNull(RouteRecorder.record(goal, run(target(3, "phone_tap", "{}", one, callId = "w"), result(3, "phone_tap", """{"ok":true,"changed":true}""", callId = "w")), 1, TOOLS))
    }

    @Test fun targetNameContainingSlotValueIsDropped() {
        val sel = Selector("button", "发送今天天气不错", "n:id/x", listOf(0, 0, 1, 1), 0, uniqueAtRecord = true)
        val events = run().toMutableList().apply {
            add(indexOfFirst { it is RunEvent.Verifier }, target(5, "phone_tap", "{}", sel, callId = "x"))
        }
        val t = RouteRecorder.record(goal, events, 1, TOOLS)!!.steps.last().target!!
        assertEquals("", t.name); assertFalse(t.uniqueAtRecord); assertEquals("n:id/x", t.resourceId)
    }

    @Test fun tooManyStepsMeansNoRoute() {
        val many = (1..MAX_ROUTE_STEPS).map { target(3, "phone_back", "{}", callId = "b$it") }.toTypedArray()
        assertNull(RouteRecorder.record(goal, run(*many), 1, TOOLS))
    }

    @Test fun takeoverOrInputOrConfirmationMeansNoRoute() {
        val spoilers = listOf(
            RunEvent.Takeover(3, "login", "skipped", 10, 0),
            RunEvent.State(3, "waiting_input", "哪个?"),
            RunEvent.State(3, "waiting_takeover", "请登录"),
            RunEvent.Confirmation(3, "send", "x", null, null, "declined"),
            RunEvent.Barrier(3, "payment"),
            RunEvent.ToolResult(3, 3, "request_confirmation", """{"ok":false,"error":"payment_requires_takeover"}""", null, null),
            RunEvent.ModelReply(3, 3, "", "web_navigate", "{}", null, null, null),
            target(3, "web_interact", "{}"),
            // 用过沙箱的做法:路线只记屏幕操作
            RunEvent.ToolResult(3, 3, "run_command", """{"ok":true,"exitCode":0}""", null, null),
            RunEvent.ModelReply(3, 3, "", "share_file", "{}", null, null, null),
        )
        assertNotNull(RouteRecorder.record(goal, run(), 1, TOOLS))
        for (e in spoilers) assertNull(e.toString(), RouteRecorder.record(goal, run(e), 1, TOOLS))
    }

    @Test fun passwordInputMeansNoRoute() {
        assertNull(RouteRecorder.record(goal, run(typedResult = """{"ok":true,"typed":6,"secret":true}"""), 1, TOOLS))
        // 端口没说不是密码框:按是处理
        assertNull(RouteRecorder.record(goal, run(typedResult = """{"ok":true,"typed":6}"""), 1, TOOLS))
        assertNull(RouteRecorder.record(goal, run(typed = "***"), 1, TOOLS))
        val shielded = RunEvent.ToolResult(3, 3, "phone_observe", """{"ok":true,"currentApp":"com.notes","protected":true}""", null, null)
        assertNull(RouteRecorder.record(goal, run(shielded), 1, TOOLS))
        assertNull(RouteRecorder.record(goal, run(target(3, "phone_back", "{}", protected = true, callId = "p")), 1, TOOLS))
        // 密码框核对按调用配对:配不上就不记
        val unpaired = run().map { if (it is RunEvent.ActionTarget && it.tool == "phone_type_text") it.copy(callId = "nobody") else it }
        assertNull(RouteRecorder.record(goal, unpaired, 1, TOOLS))
    }

    @Test fun anchorsExcludeNumbersAndTarget() {
        val noisy = Anchors("com.notes", listOf("备忘录", "3条新消息", "新建", "今天天气不错的日子", "一二三四五六七八九十一二三"))
        val events = run().map {
            if (it is RunEvent.ActionTarget && it.tool == "phone_tap") it.copy(before = noisy, after = Anchors("com.notes", listOf("今天天气不错", "新笔记"))) else it
        }
        val tap = RouteRecorder.record(goal, events, 1, TOOLS)!!.steps[1]
        // 数字、目标自己、含输入值的字都不当锚点
        assertEquals(listOf("备忘录"), tap.before.texts)
        assertEquals(listOf("新笔记"), tap.after.texts)
        assertTrue(RouteAnchors.stable("全部笔记"))
        for (s in listOf("12:30", "¥25", "2026-10-23", "", "一二三四五六七八九十一二三")) assertFalse(s, RouteAnchors.stable(s))
    }

    @Test fun noEffectiveStepMeansNoRoute() {
        val events = run().map { if (it is RunEvent.ActionTarget) it.copy(effective = false) else it }
        assertNull(RouteRecorder.record(goal, events, 1, TOOLS))
    }

    @Test fun incompleteBatchStepMeansNoRoute() {
        val gap = RunEvent.ActionTarget(3, 3, "phone_back", "{}", null, Anchors(null, emptyList()), Anchors(null, emptyList()), true, "c3.2",
            source = "batch", incomplete = true)
        assertNull(RouteRecorder.record(goal, run(gap), 1, TOOLS))
        // 批里取到了目标的子步和单步一样记
        val sub = RunEvent.ActionTarget(3, 3, "phone_back", "{}", null, editor, editor, true, "c3.2", source = "batch")
        assertEquals(listOf("phone_launch_app", "phone_tap", "phone_back", "phone_type_text"), RouteRecorder.record(goal, run(sub, result(3, "phone_back", """{"ok":true,"changed":true}""", "c3.2")), 1, TOOLS)!!.steps.map { it.tool })
    }
}
