package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.execute
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 动作让 App 开到了用户主屏上:结果的样子和给模型的那句。 */
class OpenedElsewhereTest {
    private suspend fun tap(result: ActionResult): JsonObject {
        val screen = FakeScreen().also { it.result = result }
        return toolDispatcher(screen).execute(Part.ToolCall("t", "phone_tap", """{"x":500,"y":500}""")).content
    }

    private fun JsonObject.hint() = this["hint"]!!.jsonPrimitive.content

    @Test fun pulledBackAppIsReportedAsOpenedAndNotAsAnInterruption() = runTest {
        val c = tap(ActionResult.Ok(changed = true, appSwitched = "com.chat", switchedByTarget = true,
            opened = OpenedElsewhere("com.chat", "Chat", pulledBack = true)))
        val o = c["opened"]!!.jsonObject
        assertEquals("com.chat", o["package"]!!.jsonPrimitive.content)
        assertEquals("Chat", o["app"]!!.jsonPrimitive.content)
        assertEquals("true", o["pulledBack"]!!.jsonPrimitive.content)
        assertFalse("onUserScreen" in o)
        val hint = c.hint()
        assertTrue(hint, Feedback.format("spill.pulled_back", "app" to "Chat") in hint)
        assertFalse(hint, Feedback.format("switch.by_target", "pkg" to "com.chat") in hint)
        assertEquals("place_changed", c["effect"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
    }

    @Test fun systemQuestionOnTheUserScreenIsLeftForTheUser() = runTest {
        val choice = LaunchChoice("android", listOf(ChoiceOption("Chat", null), ChoiceOption("Chat", null)))
        val c = tap(ActionResult.Ok(changed = false, chooser = choice, opened = OpenedElsewhere("android", "Chat", pulledBack = false, picker = true)))
        val o = c["opened"]!!.jsonObject
        assertEquals("true", o["picker"]!!.jsonPrimitive.content)
        assertEquals("true", o["onUserScreen"]!!.jsonPrimitive.content)
        assertEquals("false", o["pulledBack"]!!.jsonPrimitive.content)
        assertTrue("options are still listed", "chooser" in c)
        val hint = c.hint()
        assertTrue(hint, Feedback.format("spill.user_picking", "app" to "Chat") in hint)
        // 留给用户选:不教模型去点哪个,也不说没效果
        assertFalse(hint, Feedback.format("launch.chooser", "pkg" to "android") in hint)
        assertFalse(hint, Feedback["effect.no_effect"] in hint)
        assertEquals("place_changed", c["effect"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
    }

    @Test fun afterTheUserSteps() {
        val closed = OpenedElsewhere("android", "Chat", pulledBack = false, picker = true, closed = true)
        assertEquals(Feedback["spill.picker_closed"], openedHint(closed))
        assertFalse("onUserScreen" in closed.toJson())
        assertEquals("true", closed.toJson()["closed"]!!.jsonPrimitive.content)
        val done = OpenedElsewhere("com.pay", "Pay", pulledBack = false, byUser = true)
        assertEquals(Feedback.format("spill.user_done", "app" to "Pay"), openedHint(done))
        assertFalse("onUserScreen" in done.toJson())
        val back = OpenedElsewhere("com.chat", "Chat", pulledBack = true, inProfile = OpenedElsewhere.MAIN_PROFILE)
        assertEquals(Feedback.format("spill.pulled_back", "app" to "Chat"), openedHint(back))
        assertNull(back.toJson()["onUserScreen"])
    }

    @Test fun appTheUserIsUsingIsNotNoEffect() = runTest {
        val c = tap(ActionResult.Ok(changed = false, opened = OpenedElsewhere("com.chat", "Chat", pulledBack = false)))
        assertEquals("true", c["opened"]!!.jsonObject["onUserScreen"]!!.jsonPrimitive.content)
        val effect = c["effect"]!!.jsonObject
        assertEquals("place_changed", effect["kind"]!!.jsonPrimitive.content)
        val hint = c.hint()
        assertTrue(hint, Feedback.format("spill.in_use", "app" to "Chat") in hint)
        assertFalse(hint, Feedback["effect.no_effect"] in hint)
        assertFalse(hint, Feedback["tap_unchanged"] in hint)
    }

    @Test fun otherProfileIsFlagged() = runTest {
        val c = tap(ActionResult.Ok(changed = true, inProfile = OpenedElsewhere.OTHER_PROFILE))
        assertEquals("other", c["inProfile"]!!.jsonPrimitive.content)
        assertTrue(c.hint(), Feedback["profile.other"] in c.hint())
        val main = tap(ActionResult.Ok(changed = true, inProfile = OpenedElsewhere.MAIN_PROFILE))
        assertEquals("main", main["inProfile"]!!.jsonPrimitive.content)
        assertFalse(Feedback["profile.other"] in (main["hint"]?.jsonPrimitive?.content ?: ""))
    }

    @Test fun effectOnUserScreenIsAPlaceChange() {
        val e = ActionEffect.classify(Verb.Tap, null, null, PortFacts(changed = false, onUserScreen = "com.chat"), null)!!
        assertEquals(EffectKind.PlaceChanged, e.kind)
        assertEquals("true", e.toJson()["app"]!!.jsonObject["onUserScreen"]!!.jsonPrimitive.content)
    }
}
