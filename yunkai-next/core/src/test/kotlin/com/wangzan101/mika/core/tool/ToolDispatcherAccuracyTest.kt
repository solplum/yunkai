package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenCheck
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.ScreenState
import com.wangzan101.mika.core.uistate.Verification
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

/** 端口核实到的事实(变化、被切走、重定位、回读、防截屏、诊断)要原样交给模型。 */
class ToolDispatcherAccuracyTest {
    private fun call(name: String, args: String = "{}") = Part.ToolCall("c1", name, args)
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.b(k: String) = this[k]!!.jsonPrimitive.boolean

    private fun JsonObject.effect() = this["effect"]!!.jsonObject

    @Test fun unchangedTapReportsNoEffectAndAnotherWay() = runTest {
        val s = FakeScreen().apply { result = ActionResult.Ok(changed = false) }
        val r = toolDispatcher(s).execute(call("phone_tap", """{"ref":"@e1","snapshotId":"a"}"""))
        assertEquals(false, r.changed)
        assertFalse(r.content.b("changed"))
        assertEquals("no_effect", r.content.effect().s("kind"))
        val h = r.content.s("hint")
        // 只说看到的事实,不暗示「没点中」;不要原样再来,换个做法
        assertFalse(h, "missed" in h)
        assertTrue(h, "do not repeat" in h.lowercase() && "another way" in h)
        // 没变就不算「屏幕过时」
        assertNull(r.content["interfaceStale"])
    }

    @Test fun portChangeWithoutVisibleTreeChangeIsSmallChange() = runTest {
        val s = FakeScreen().apply { result = ActionResult.Ok(changed = true) }
        val r = toolDispatcher(s).execute(call("phone_tap", """{"x":1,"y":1}"""))
        assertEquals(true, r.changed); assertTrue(r.content.b("changed"))
        assertEquals("small_change", r.content.effect().s("kind"))
        assertTrue(r.content.s("hint"), "Check" in r.content.s("hint"))
        // 动作之后没带回屏幕:模型手里那一屏已过时
        assertTrue(r.content.b("interfaceStale"))
    }

    @Test fun portWithoutVerificationGetsChangedFromTheEffect() = runTest {
        val r = toolDispatcher(FakeScreen()).execute(call("phone_back"))
        // 端口没说变没变:按前后两屏判,两屏一样就是没变
        assertEquals(false, r.changed); assertNull(r.content["changed"])
        assertEquals("no_effect", r.content.effect().s("kind"))
    }

    @Test fun unchangedBackHasBackSpecificHint() = runTest {
        val s = FakeScreen().apply { result = ActionResult.Ok(changed = false) }
        val r = toolDispatcher(s).execute(call("phone_back"))
        assertTrue(r.content.s("hint"), "back or close control" in r.content.s("hint"))
    }

    @Test fun appSwitchAndRelocationAreSurfaced() = runTest {
        val s = FakeScreen().apply { result = ActionResult.Ok(changed = true, appSwitched = "com.other", relocated = true) }
        val r = toolDispatcher(s).execute(call("phone_tap", """{"ref":"@e1","snapshotId":"a"}"""))
        assertEquals("com.other", r.content.s("app_switched"))
        assertTrue(r.content.b("relocated"))
        assertTrue("com.other" in r.content.s("hint") && "phone_launch_app" in r.content.s("hint"))
    }

    @Test fun relocatedTargetNameIsSurfaced() = runTest {
        val s = FakeScreen().apply { result = ActionResult.Ok(changed = true, relocated = true, relocatedTo = "跳过 2") }
        val r = toolDispatcher(s).execute(call("phone_tap", """{"ref":"@e1","snapshotId":"a"}"""))
        assertTrue(r.content.b("relocated")); assertEquals("跳过 2", r.content.s("relocatedTo"))
    }

    @Test fun typeTextCarriesRelocation() = runTest {
        val s = FakeScreen().apply { result = ActionResult.Ok(verification = Verification.MATCH, relocated = true, relocatedTo = "搜索框") }
        val r = toolDispatcher(s).execute(call("phone_type_text", """{"text":"hi","ref":"@e1","snapshotId":"a"}"""))
        assertTrue(r.content.b("relocated")); assertEquals("搜索框", r.content.s("relocatedTo"))
    }

    @Test fun appOpenedByTheTargetIsWordedSoftly() = runTest {
        val s = FakeScreen().apply { result = ActionResult.Ok(changed = true, appSwitched = "com.share", switchedByTarget = true) }
        val r = toolDispatcher(s).execute(call("phone_tap", """{"ref":"@e1","snapshotId":"a"}"""))
        assertEquals("com.share", r.content.s("app_switched"))
        assertTrue(r.content.b("opened_by_target"))
        val h = r.content.s("hint")
        assertTrue(h, "opened by" in h && "interruption" !in h)
    }

    @Test fun appInUseByUserHasExplanation() = runTest {
        val s = FakeScreen().apply { result = ActionResult.Failed("app_in_use_by_user") }
        val r = toolDispatcher(s).execute(call("phone_launch_app", """{"package":"com.x"}"""))
        assertEquals("app_in_use_by_user", r.content.s("error"))
        assertTrue("hand_to_user" in r.content.s("message"))
    }

    private class TypingScreen(val v: Verification?) : ScreenPort by FakeScreen() {
        val keys = mutableListOf<Int>()
        override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult = ActionResult.Ok(verification = v)
        override suspend fun pressKey(keyCode: Int): ActionResult { keys += keyCode; return ActionResult.Ok() }
    }

    @Test fun enterOnlyAfterVerifiedReadback() = runTest {
        val match = TypingScreen(Verification.MATCH)
        val r = toolDispatcher(match).execute(call("phone_type_text", """{"text":"WLAN","pressEnter":true}"""))
        assertEquals(listOf(66), match.keys)
        assertEquals("match", r.content.s("verification")); assertTrue(r.content.b("pressedEnter"))

        val unknown = TypingScreen(Verification.UNKNOWN)
        val u = toolDispatcher(unknown).execute(call("phone_type_text", """{"text":"WLAN","pressEnter":true}"""))
        assertTrue(unknown.keys.isEmpty())
        assertTrue(u.content.b("ok")); assertEquals("unknown", u.content.s("verification"))
        assertFalse(u.content.b("pressedEnter")); assertTrue(u.content.b("enterSkipped")); assertTrue("Enter" in u.content.s("hint"))

        val mismatch = TypingScreen(Verification.MISMATCH)
        val m = toolDispatcher(mismatch).execute(call("phone_type_text", """{"text":"WLAN","pressEnter":true}"""))
        assertTrue(mismatch.keys.isEmpty())
        assertFalse(m.content.b("ok")); assertEquals("action_not_applied", m.content.s("error"))
        assertEquals("mismatch", m.content.s("verification"))
    }

    private class FlagScreen(val changedDuringRead: Boolean, val prot: Boolean) : ScreenPort by FakeScreen() {
        override suspend fun snapshot() = ScreenSnapshot("p1", "com.x", emptyList(), 1080, 2400, changedDuringRead, prot)
        override suspend fun isProtected() = prot
    }

    @Test fun observeReportsChangedDuringReadAndProtected() = runTest {
        val plain = toolDispatcher(FlagScreen(false, false)).execute(call("phone_observe"))
        assertFalse(plain.content.b("screenChangedDuringRead")); assertNull(plain.content["protected"])

        val moving = toolDispatcher(FlagScreen(true, false)).execute(call("phone_list_elements"))
        assertTrue(moving.content.b("screenChangedDuringRead")); assertTrue("note" in moving.content)

        val prot = toolDispatcher(FlagScreen(false, true), visionEnabled = true).execute(call("phone_observe"))
        assertTrue(prot.content.b("protected")); assertTrue("hand_to_user" in prot.content.s("hint"))
        assertNull(prot.image) // 黑屏图不给模型
    }

    @Test fun screenshotOfProtectedPage() = runTest {
        val r = toolDispatcher(FlagScreen(false, true), visionEnabled = true).execute(call("phone_screenshot"))
        assertFalse(r.content.b("ok")); assertEquals("protected_content", r.content.s("error"))
        assertTrue(r.content.b("protected")); assertNull(r.image)
    }

    @Test fun checkScreenUsesPortDiagnosis() = runTest {
        val s = object : ScreenPort by FakeScreen() {
            override suspend fun checkScreen() = ScreenCheck(ScreenState.PROTECTED, "com.bank", true, "other", "secure window")
        }
        val r = toolDispatcher(s).execute(call("phone_diagnose"))
        assertEquals("protected", r.content.s("state")); assertTrue(r.content.b("needsHuman"))
        assertEquals("other", r.content.s("takeoverReason")); assertFalse(r.content.b("usable"))
        assertTrue("hand_to_user" in r.content.s("hint"))
        assertEquals("com.bank", r.content.s("currentApp"))

        val normal = object : ScreenPort by FakeScreen() {
            override suspend fun checkScreen() = ScreenCheck(ScreenState.NORMAL, "com.x", false)
        }
        val n = toolDispatcher(normal).execute(call("phone_diagnose"))
        assertTrue(n.content.b("usable")); assertFalse(n.content.b("needsHuman")); assertNull(n.content["hint"])
    }

    @Test fun scrollDescriptionSaysPlainlyWhichWayIsWhich() {
        val spec = toolDispatcher(FakeScreen()).definitions().single { it.name == "phone_scroll" }
        val text = spec.description + spec.parametersJsonSchema
        assertTrue(text, "further down" in text && "finger" in text)
    }

    @Test fun scrollResultSaysFingerDirectionAndEndWhenNothingMoved() = runTest {
        val moved = toolDispatcher(FakeScreen().apply { result = ActionResult.Ok(changed = true) })
            .execute(call("phone_scroll", """{"direction":"down"}""")).content
        assertEquals("up", moved.s("fingerMoved")); assertNull(moved["atEnd"]); assertNull(moved.effect()["kind"]?.takeIf { it.jsonPrimitive.content == "no_effect" })

        val stuck = toolDispatcher(FakeScreen().apply { result = ActionResult.Ok(changed = false) })
            .execute(call("phone_scroll", """{"direction":"up"}""")).content
        assertEquals("down", stuck.s("fingerMoved"))
        assertTrue(stuck.b("atEnd"))
        // 先确认方向再下「到头了」的结论;不用点击的「可能没点中」说法
        assertTrue(stuck.s("hint"), "direction" in stuck.s("hint") && "missed" !in stuck.s("hint"))
    }

    private class EmptyScreen : ScreenPort by FakeScreen() {
        override suspend fun snapshot() = ScreenSnapshot("p1", null, emptyList(), 1080, 2400, hasWindow = false)
        override suspend fun checkScreen() = ScreenCheck(ScreenState.BLANK, null, false, cause = "no window")
    }

    /** 有 App 窗口,只是根节点读不到:不是空屏,不该叫它 phone_launch_app。 */
    private class UnreadableScreen : ScreenPort by FakeScreen() {
        override suspend fun snapshot() = ScreenSnapshot("p1", null, emptyList(), 1080, 2400, hasWindow = true)
        override suspend fun checkScreen() = ScreenCheck(ScreenState.BLANK, null, false, cause = "unreadable window")
    }

    @Test fun unreadableWindowIsNotCalledEmpty() = runTest {
        val d = toolDispatcher(UnreadableScreen())
        for (tool in listOf("phone_observe", "phone_diagnose")) {
            val h = d.execute(call(tool)).content["hint"]?.jsonPrimitive?.content.orEmpty()
            assertFalse("$tool: $h", "screen is empty" in h)
        }
    }

    @Test fun emptyVirtualScreenSaysStartWithLaunchApp() = runTest {
        val d = toolDispatcher(EmptyScreen())
        for (tool in listOf("phone_observe", "phone_diagnose")) {
            val h = d.execute(call(tool)).content.s("hint")
            assertTrue("$tool: $h", "empty" in h && "phone_launch_app" in h)
        }
    }
}
