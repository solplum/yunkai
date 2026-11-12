package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.tool.FAILURE_MESSAGES
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ChoiceOption
import com.wangzan101.mika.core.uistate.LaunchChoice
import com.wangzan101.mika.core.uistate.ScreenPort
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareTextTest {
    private class ShareScreen(var result: ActionResult = ActionResult.Ok(changed = true)) : ScreenPort by FakeScreen() {
        val shared = mutableListOf<Pair<String, String>>()
        override suspend fun shareText(packageName: String, text: String): ActionResult { shared += packageName to text; return result }
    }

    private fun call(pkg: String, text: String) = Part.ToolCall("s1", "phone_share_text", buildJsonObject {
        put("package", pkg); put("text", text); put("reason", "message the user asked to send")
    }.toString())

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test fun opensTheShareScreenAndSaysNothingWasSent() = runTest {
        val screen = ShareScreen()
        val r = toolDispatcher(screen).execute(call("com.example.chat", "明天 7 点见"))
        assertEquals(listOf("com.example.chat" to "明天 7 点见"), screen.shared)
        assertEquals("true", r.content.s("ok"))
        assertTrue(r.content.s("hint"), Feedback["share.opened"] in r.content.s("hint"))
    }

    @Test fun appWithoutTextShareIsCannotShare() = runTest {
        val r = toolDispatcher(ShareScreen(ActionResult.Failed("cannot_share"))).execute(call("com.example.notes", "hi"))
        assertEquals("false", r.content.s("ok"))
        assertEquals("cannot_share", r.content.s("error"))
        assertEquals(FAILURE_MESSAGES.getValue("cannot_share"), r.content.s("message"))
    }

    @Test fun chooserIsSuccessWithTheChoiceInsteadOfTheShareHint() = runTest {
        val choice = LaunchChoice("android", listOf(ChoiceOption("Chat", false), ChoiceOption("Chat (分身应用)", true)))
        val r = toolDispatcher(ShareScreen(ActionResult.Ok(chooser = choice))).execute(call("com.example.chat", "hi"))
        assertEquals("true", r.content.s("ok"))
        assertEquals("android", r.content["chooser"]!!.jsonObject.s("package"))
        val hint = r.content.s("hint")
        assertTrue(hint, Feedback.format("launch.chooser", "pkg" to "android") in hint)
        assertFalse(hint, Feedback["share.opened"] in hint)
    }

    @Test fun badPackageIsRejectedBeforeSharing() = runTest {
        val screen = ShareScreen()
        val r = toolDispatcher(screen).execute(call("not a package", "hi"))
        assertEquals("false", r.content.s("ok"))
        assertTrue(screen.shared.isEmpty())
    }
}
