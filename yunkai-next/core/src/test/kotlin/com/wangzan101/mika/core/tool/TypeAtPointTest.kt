package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.gate.IrreversibleDetector
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.risk.GateTarget
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.UnseenField
import com.wangzan101.mika.core.uistate.Verification
import com.wangzan101.mika.core.vision.JvmTestImageOps
import com.wangzan101.mika.core.vision.LocScreen
import com.wangzan101.mika.core.vision.VisionService
import com.wangzan101.mika.core.vision.says
import com.wangzan101.mika.core.vision.uiEl
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 往一点上打字、清空:树里的编号照旧,坐标和看图找到的 @vN 先点再粘贴,粘贴的核对就是点没点中的凭据。 */
class TypeAtPointTest {
    private class PointScreen(
        private val inner: LocScreen = LocScreen(listOf(uiEl("@e1", "WLAN", listOf(0, 100, 999, 160)))),
        var typed: ActionResult = ActionResult.Ok(changed = true, verification = Verification.MATCH),
    ) : ScreenPort by inner {
        val calls = mutableListOf<String>()
        override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult { calls += "type $ref $text"; return typed }
        override suspend fun typeAt(x: Int, y: Int, text: String): ActionResult { calls += "typeAt $x,$y $text"; return typed }
        override suspend fun clearAt(x: Int, y: Int): ActionResult { calls += "clearAt $x,$y"; return ActionResult.Ok(changed = true) }
        override suspend fun pressKey(keyCode: Int): ActionResult { calls += "key $keyCode"; return ActionResult.Ok() }
    }

    private var seq = 0
    private fun call(name: String, args: String) = Part.ToolCall("t${++seq}", name, args)
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun d(s: PointScreen) = toolDispatcher(s, vision = VisionService(says(), JvmTestImageOps))

    @Test fun treeRefTypesDirectlyAsBefore() = runTest {
        val s = PointScreen()
        val d = d(s)
        val snap = d.execute(call("phone_observe", "{}")).content.s("snapshotId")
        val r = d.execute(call("phone_type_text", """{"text":"你好","ref":"@e1","snapshotId":"$snap"}""")).content
        assertEquals(r.toString(), "true", r.s("ok"))
        assertEquals(listOf("type @e1 你好"), s.calls)
    }

    @Test fun coordinatesTapThenPasteAndMatchIsOk() = runTest {
        val s = PointScreen()
        val r = d(s).execute(call("phone_type_text", """{"text":"明天见","x":500,"y":900,"pressEnter":true}""")).content
        assertEquals(r.toString(), "true", r.s("ok"))
        // 0–999 换成像素,只点一次、打一次;核对一致才按回车
        assertEquals(listOf("typeAt 540,2161 明天见", "key 66"), s.calls)
        assertEquals("match", r.s("verification"))
    }

    @Test fun mismatchAtThePointIsAnErrorAndNotRetried() = runTest {
        val s = PointScreen(typed = UnseenField.typed("明天见", emptyList(), emptyList(), changed = false, keyboardUp = false, atPoint = true))
        val r = d(s).execute(call("phone_type_text", """{"text":"明天见","x":500,"y":900,"pressEnter":true}""")).content
        assertEquals("false", r.s("ok"))
        assertEquals(UnseenField.POINT_NOT_TYPED, r.s("error"))
        assertTrue(r.s("message"), Feedback["type.point_not_typed"] in r.s("message"))
        assertEquals(listOf("typeAt 540,2161 明天见"), s.calls)
    }

    @Test fun unverifiedPasteDoesNotPressEnter() = runTest {
        val s = PointScreen(typed = ActionResult.Ok(changed = true, verification = Verification.UNKNOWN))
        val r = d(s).execute(call("phone_type_text", """{"text":"明天见","x":500,"y":900,"pressEnter":true}""")).content
        assertEquals("true", r.s("ok")); assertEquals("true", r.s("enterSkipped"))
        assertFalse(s.calls.any { it.startsWith("key") })
    }

    @Test fun visionRefTypesAtTheFoundPoint() = runTest {
        val s = PointScreen()
        val d = d(s)
        val found = d.execute(call("phone_find_visually", """{"target":"WLAN","text":"WLAN"}""")).content
        val r = d.execute(call("phone_type_text", """{"text":"hi","ref":"${found.s("ref")}","snapshotId":"${found.s("snapshotId")}"}""")).content
        assertEquals(r.toString(), "true", r.s("ok"))
        assertEquals(listOf("typeAt 540,312 hi"), s.calls)
        assertEquals("WLAN", r.s("located"))
        // 清空同样接受一点
        val c = d.execute(call("phone_clear_text", """{"x":10,"y":20}""")).content
        assertEquals("true", c.s("ok"))
        assertEquals("clearAt 11,48", s.calls.last())
    }

    @Test fun staleVisionRefIsNotTyped() = runTest {
        val s = PointScreen()
        val r = d(s).execute(call("phone_type_text", """{"text":"hi","ref":"@v9","snapshotId":"loc_x"}""")).content
        assertEquals("ref_expired", r.s("error"))
        assertTrue(s.calls.isEmpty())
    }

    @Test fun refAndPointTogetherAreRejected() = runTest {
        val r = d(PointScreen()).execute(call("phone_type_text", """{"text":"hi","ref":"@e1","snapshotId":"s","x":1,"y":2}""")).content
        assertEquals("invalid_arguments", r.s("error"))
    }

    @Test fun pointTypingJudgesTheTapToo() {
        fun args(s: String) = Json.parseToJsonElement(s).jsonObject
        assertTrue(IrreversibleDetector.needsCheck(GateTarget.Enter, args("""{"text":"hi","x":500,"y":900}""")))
        assertTrue(IrreversibleDetector.needsCheck(GateTarget.Enter, args("""{"text":"hi","ref":"@v1","snapshotId":"loc_1"}""")))
        assertFalse(IrreversibleDetector.needsCheck(GateTarget.Enter, args("""{"text":"hi","ref":"@e1","snapshotId":"s1"}""")))
        // 那一点上是发送按钮:点它就按发送判
        val send = com.wangzan101.mika.core.uistate.ScreenSnapshot("s1", "com.example.chat",
            listOf(uiEl("@e1", "发送", listOf(800, 880, 999, 940)).copy(clickable = true)), 1080, 2400)
        assertTrue(IrreversibleDetector.classify(GateTarget.Enter, args("""{"text":"hi","x":900,"y":910}"""), send, send) != null)
        assertTrue(IrreversibleDetector.classify(GateTarget.Enter, args("""{"text":"hi","x":100,"y":100}"""), send, send) == null)
    }
}
