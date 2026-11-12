package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.testing.field
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.Verification
import com.wangzan101.mika.core.vision.JvmTestImageOps
import com.wangzan101.mika.core.vision.LocScreen
import com.wangzan101.mika.core.vision.VisionService
import com.wangzan101.mika.core.vision.uiEl
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 按描述指输入框,而树读不出这一屏:改看图找这个框,之后和传 @vN 一样核对、判不可逆、粘贴核对。 */
class DescribedBySightTest {
    private val sink = object : RunSink { override fun event(e: RunEvent) {} }

    /** 一屏(默认树里什么都没有,即树不能用);记下端口收到的打字。 */
    private class Blind(inner: LocScreen = LocScreen()) : ScreenPort by inner {
        val calls = mutableListOf<String>()
        override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult { calls += "type $ref $text"; return ActionResult.Ok(changed = true) }
        override suspend fun typeAt(x: Int, y: Int, text: String): ActionResult {
            calls += "typeAt $x,$y $text"; return ActionResult.Ok(changed = true, verification = Verification.MATCH)
        }
        override suspend fun pressKey(keyCode: Int): ActionResult { calls += "key $keyCode"; return ActionResult.Ok() }
    }

    private fun model(vararg replies: String) = FakeModel(
        *replies.map { ModelReply(listOf(Part.Text(it)), StopReason.EndTurn, null) }.toTypedArray(), groundingVerified = false)

    private fun executor(screen: ScreenPort, vision: FakeModel) = ActionExecutor(
        toolDispatcher(screen, vision = VisionService(vision, JvmTestImageOps)), OperationGuards(), ConfirmationGate(), gate = {},
        recorder = ActionRecorder(sink))

    private suspend fun type(e: ActionExecutor, args: String) = e.run(Part.ToolCall("c", "phone_type_text", args), Origin.Model(1))

    private fun content(o: ActionOutcome): JsonObject = (o as ActionOutcome.Ran).content
    private fun JsonObject.s(k: String) = (this[k] as JsonPrimitive).content

    @Test fun blindScreenFindsTheBoxBySightAndTypesThere() = runTest {
        val screen = Blind()
        val vision = model("""{"bbox_2d":[0,880,800,940],"seen":"输入框"}""", "是")
        val c = content(type(executor(screen, vision), """{"element":"底部的消息输入框","text":"明天见"}"""))
        assertEquals(c.toString(), "true", c.s("ok"))
        // 0–999 的框中心换成像素,点一下再粘贴
        assertEquals(listOf("typeAt 432,2183 明天见"), screen.calls)
        assertEquals("true", c.s("foundBySight"))
        assertEquals("底部的消息输入框", c.s("resolvedFrom"))
        assertEquals("输入框", c.s("seen"))
        // 只靠看图、没有佐证:照样标出来
        assertEquals("true", c.s("unverifiedPoint"))
        assertEquals(2, vision.requests.size)
    }

    @Test fun notSeenIsReportedAndNothingIsTyped() = runTest {
        val screen = Blind()
        val c = content(type(executor(screen, model("null")), """{"element":"底部的消息输入框","text":"明天见"}"""))
        assertEquals("element_not_found", c.s("error"))
        assertTrue(c.s("message"), "not_found" in c.s("message") && Feedback["described.not_seen.retry"] in c.s("message"))
        assertTrue(screen.calls.isEmpty())
    }

    @Test fun boxFoundOnASendButtonIsUsedWithoutAsking() = runTest {
        // 判过不可逆:是发送,不花钱,不先问
        val screen = Blind()
        val vision = model("""{"bbox_2d":[800,880,999,940],"seen":"发送"}""", "是")
        val o = type(executor(screen, vision), """{"element":"右下角的框","text":"明天见"}""")
        assertTrue("$o", o is ActionOutcome.Ran)
        assertTrue(screen.calls.isNotEmpty())
    }

    @Test fun readableTreeWithoutAMatchDoesNotLookBySight() = runTest {
        val inner = LocScreen(List(8) { i -> uiEl("@e$i", "设置项$i", listOf(0, i * 100, 999, i * 100 + 80)) } +
            field("@e9", "搜索", y = 900))
        val screen = Blind(inner)
        val vision = model()
        val c = content(type(executor(screen, vision), """{"element":"消息框","text":"明天见"}"""))
        assertEquals("element_not_found", c.s("error"))
        assertTrue(c.s("message"), "phone_observe" in c.s("message"))
        assertTrue(vision.requests.isEmpty())
        assertTrue(screen.calls.isEmpty())
    }
}
