// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.web.FakeWebPort
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.testing.button
import com.wangzan101.mika.core.testing.field
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.PageDialog
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 一屏:登录、两个关注、删除、一个搜索框和「提交订单」;记下端口收到的动作。 */
private class Shop : ScreenPort by FakeScreen() {
    val calls = mutableListOf<String>()
    private var reads = 0
    private val shown = listOf(
        button("@e1", "登录", y = 100), button("@e2", "关注", y = 300), button("@e3", "关注", y = 400),
        button("@e4", "删除", y = 500), field("@e5", "搜索商品", y = 700).copy(placeholder = "搜索商品"),
        button("@e6", "提交订单", y = 900),
    )
    override suspend fun snapshot() = ScreenSnapshot("s${++reads}", "com.shop", shown, 1080, 2400)
    override suspend fun peek() = ScreenSnapshot("peek", "com.shop", shown, 1080, 2400)
    override suspend fun tapRef(ref: String, snapshotId: String) = ActionResult.Ok(changed = true).also { calls += "tap $ref" }
    override suspend fun doubleTapRef(ref: String, snapshotId: String) = ActionResult.Ok(changed = true).also { calls += "double $ref" }
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = ActionResult.Ok(changed = true).also { calls += "type $ref $text" }
}

/** 按描述指目标:先在此刻那一屏里换成编号,之后判定、审批、执行和按编号的一样;对不上就不做。 */
class DescribedActionsTest {
    private val sink = object : RunSink { override fun event(e: com.wangzan101.mika.core.record.RunEvent) {} }

    private fun executor(tools: com.wangzan101.mika.core.tool.ToolDispatcher) =
        ActionExecutor(tools, OperationGuards(), ConfirmationGate(), gate = {}, recorder = ActionRecorder(sink))

    private suspend fun run(e: ActionExecutor, tool: String, args: String): ActionOutcome = e.run(Part.ToolCall("c", tool, args), Origin.Model(1))

    private fun content(o: ActionOutcome): JsonObject = (o as ActionOutcome.Ran).content

    @Test fun uniqueDescriptionActsOnThatElement() = runTest {
        val screen = Shop()
        val e = executor(toolDispatcher(screen))
        val o = run(e, "phone_tap", """{"element":"登录"}""")
        assertEquals("$o", JsonPrimitive(true), content(o)["ok"])
        assertEquals(listOf("tap @e1"), screen.calls)
        run(e, "phone_type_text", """{"element":"搜索","text":"耳机"}""")
        assertEquals("tap @e1|type @e5 耳机", screen.calls.joinToString("|"))
    }

    @Test fun ambiguousDescriptionListsCandidatesAndDoesNothing() = runTest {
        val screen = Shop()
        val o = run(executor(toolDispatcher(screen)), "phone_tap", """{"element":"关注"}""")
        val c = content(o)
        assertEquals(JsonPrimitive("element_ambiguous"), c["error"])
        val message = (c["message"] as JsonPrimitive).content
        assertTrue(message, "2 elements" in message && "@e2" in message && "@e3" in message && "near 「" in message && "at [" in message)
        assertTrue(screen.calls.isEmpty())
    }

    @Test fun missingDescriptionSuggestsObserving() = runTest {
        val screen = Shop()
        val c = content(run(executor(toolDispatcher(screen)), "phone_tap", """{"element":"购物车"}"""))
        assertEquals(JsonPrimitive("element_not_found"), c["error"])
        assertTrue("phone_observe" in (c["message"] as JsonPrimitive).content)
        assertTrue(screen.calls.isEmpty())
    }

    @Test fun describedTargetGoesThroughTheSameApproval() = runTest {
        val screen = Shop()
        val o = run(executor(toolDispatcher(screen)), "phone_tap", """{"element":"提交订单"}""")
        o as ActionOutcome.Refused
        assertEquals("confirmation_required", o.code)
        assertEquals(IrrevAction.order, o.hit?.action)
        assertTrue(screen.calls.isEmpty())
        // Nonpayment deletion uses the same Ask gate, even when resolved by description.
        val deletion = run(executor(toolDispatcher(screen)), "phone_tap", """{"element":"删除"}""") as ActionOutcome.Refused
        assertEquals("confirmation_required", deletion.code)
        assertEquals(IrrevAction.delete, deletion.hit?.action)
        assertTrue(screen.calls.isEmpty())
    }

    @Test fun descriptionWithARefIsRejected() = runTest {
        val screen = Shop()
        val c = content(run(executor(toolDispatcher(screen)), "phone_tap", """{"element":"登录","ref":"@e1","snapshotId":"s1"}"""))
        assertEquals(JsonPrimitive("invalid_arguments"), c["error"])
        assertTrue(screen.calls.isEmpty())
    }

    @Test fun partialMatchSaysWhatItResolvedTo() = runTest {
        val screen = Shop()
        val c = content(run(executor(toolDispatcher(screen)), "phone_tap", """{"element":"登"}"""))
        assertEquals(JsonPrimitive(true), c["ok"])
        assertEquals(JsonPrimitive("登"), c["resolvedFrom"])
        assertEquals(JsonPrimitive("登录"), c["matched"])
        val exact = content(run(executor(toolDispatcher(Shop())), "phone_tap", """{"element":"登录"}"""))
        assertTrue("resolvedFrom" !in exact)
    }

    @Test fun roleWithoutElementIsRejected() = runTest {
        val screen = Shop()
        val c = content(run(executor(toolDispatcher(screen)), "phone_tap", """{"role":"button","x":1,"y":1}"""))
        assertEquals(JsonPrimitive("invalid_arguments"), c["error"])
        assertTrue(screen.calls.isEmpty())
    }

    @Test fun describedTapWithAnOpenPageDialogIsRefused() = runTest {
        val web = FakeWebPort().apply { dialog = PageDialog(PageDialog.ALERT, "已保存") }
        val c = content(run(executor(toolDispatcher(FakeScreen(), web = web)), "web_tap", """{"element":"搜索"}"""))
        assertEquals(JsonPrimitive("dialog_open"), c["error"])
        assertTrue(web.calls.none { it.startsWith("tap") })
    }

    @Test fun acceptingAnEmptyLeavePageDialogIsNotGated() = runTest {
        val web = FakeWebPort().apply { dialog = PageDialog(PageDialog.BEFORE_UNLOAD, "") }
        val o = run(executor(toolDispatcher(FakeScreen(), web = web)), "web_interact", """{"action":"dialog","answer":"accept"}""")
        assertTrue("$o", o is ActionOutcome.Ran)
        assertTrue(web.calls.toString(), "dialog true -" in web.calls)
    }

    @Test fun doubleTap() = runTest {
        val screen = Shop()
        run(executor(toolDispatcher(screen)), "phone_tap", """{"element":"登录","clicks":2}""")
        assertEquals(listOf("double @e1"), screen.calls)
    }

    @Test fun webDescriptionAndDoubleTapUseTheSameShape() = runTest {
        val web = FakeWebPort()
        val e = executor(toolDispatcher(FakeScreen(), web = web))
        run(e, "web_tap", """{"element":"搜索","role":"button","clicks":2}""")
        assertTrue(web.calls.toString(), web.calls.any { it.startsWith("doubleTapRef @e1 ") })
    }

    @Test fun mirroredVerbsKeepTheSameArguments() {
        val specs = toolDispatcher(FakeScreen(), web = FakeWebPort()).definitions().associateBy { it.name }
        fun props(name: String) = (Json.parseToJsonElement(specs.getValue(name).parametersJsonSchema) as JsonObject)["properties"] as JsonObject
        for (verb in listOf("tap", "type_text")) {
            val phone = props("phone_$verb"); val web = props("web_$verb")
            assertEquals(verb, phone.keys, web.keys)
            assertTrue(verb, "element" in phone.keys && "role" in phone.keys)
            assertEquals(verb, phone.mapValues { (it.value as JsonObject)["type"] }, web.mapValues { (it.value as JsonObject)["type"] })
        }
        val actions = (props("web_interact")["action"] as JsonObject)["enum"].toString()
        assertTrue(actions, "\"dialog\"" in actions)
    }

    @Test fun destructivePageDialogIsShownButCannotBeAcceptedWithoutApproval() = runTest {
        val web = FakeWebPort().apply { dialog = PageDialog(PageDialog.CONFIRM, "确定删除这条记录吗?") }
        val tools = toolDispatcher(FakeScreen(), web = web)
        val seen = tools.execute(Part.ToolCall("o", "web_observe", "{}")).content
        assertEquals(JsonPrimitive("确定删除这条记录吗?"), (seen["dialog"] as JsonObject)["message"])
        val e = executor(tools)
        val accept = run(e, "web_interact", """{"action":"dialog","answer":"accept"}""")
        assertEquals("confirmation_required", (accept as ActionOutcome.Refused).code)
        assertTrue(web.calls.toString(), "dialog true -" !in web.calls)
        assertTrue(web.dialog != null)
    }
}
