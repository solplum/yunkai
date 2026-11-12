package com.wangzan101.mika.cap.phone.a11y

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.TEST_PACKAGE
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.ScreenState
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.Verification
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 真机测试:在 Agent 虚拟屏上用系统设置验证读屏、操作和各项核实机制。 */
@RunWith(AndroidJUnit4::class)
class AndroidScreenTest {
    private val agent = DeviceSupport.newAgent()
    private val screen get() = agent.screen

    @After
    fun tearDown() = agent.release()

    private suspend fun openSettings(): ScreenSnapshot {
        val r = screen.openApp(SETTINGS)
        assertTrue("openApp: $r", r is ActionResult.Ok)
        assertNull((r as ActionResult.Ok).appSwitched)
        return snapUntil { s -> s.elements.any { it.name.startsWith("WLAN") && it.clickable } }
    }

    private suspend fun snapUntil(timeoutMs: Long = 5_000, test: (ScreenSnapshot) -> Boolean): ScreenSnapshot {
        val end = System.currentTimeMillis() + timeoutMs
        var s = screen.snapshot()
        while (!test(s) && System.currentTimeMillis() < end) {
            Thread.sleep(200)
            s = screen.snapshot()
        }
        assertTrue("screen never matched: ${s.elements.map { it.name }}", test(s))
        return s
    }

    private fun ScreenSnapshot.clickable(prefix: String): UiElement =
        elements.first { it.clickable && it.name.startsWith(prefix) }

    private fun ok(r: ActionResult): ActionResult.Ok {
        assertTrue("expected Ok, got $r", r is ActionResult.Ok)
        return r as ActionResult.Ok
    }

    @Test
    fun systemCommandUsesTaskDisplayAndKeepsMainScreen() = runBlocking<Unit> {
        val shell = DeviceSupport.shell()
        val main = DeviceSupport.mainScreenState(shell)
        screen.ensureDisplay()
        val blank = screen.snapshot()
        assertNull(blank.packageName)
        assertFalse(blank.hasWindow)
        val command = screen.systemCommand("printf '%s' \"\$MIKA_DISPLAY_ID\"")
        assertNotNull(command)
        assertEquals(0, command!!.exitCode)
        assertEquals(agent.display.displayId.toString(), command.output)
        assertEquals(main, DeviceSupport.mainScreenState(shell))
    }

    @Test
    fun opensAndReadsSettings() = runBlocking<Unit> {
        val s = openSettings()
        assertEquals(SETTINGS, s.packageName)
        assertEquals(720, s.width); assertEquals(1600, s.height)
        assertTrue(s.snapshotId.isNotBlank())
        assertFalse(s.protected)
        s.elements.forEachIndexed { i, e ->
            assertEquals("@e${i + 1}", e.ref)
            assertTrue("$e", e.bounds.size == 4 && e.bounds.all { it in 0..999 })
            assertTrue("$e", e.centerX in 0 until 720 && e.centerY in 0 until 1600)
        }
        assertNotNull(screen.screenshotPng())
        assertEquals(ScreenState.NORMAL, screen.checkScreen().state)
    }

    @Test
    fun tapReportsChangedAndUnchanged() = runBlocking<Unit> {
        val s = openSettings()
        // 标题文字不可点,点上去页面不变
        val title = s.elements.first { !it.clickable && !it.editable && !it.scrollable && it.name == "设置" }
        val miss = ok(screen.tapXY(title.centerX, title.centerY))
        assertEquals(false, miss.changed)

        val hit = ok(screen.tapRef(s.clickable("WLAN").ref, s.snapshotId))
        assertEquals(true, hit.changed)
        assertFalse(hit.relocated)

        assertEquals(true, ok(screen.back()).changed)
    }

    @Test
    fun staleRefRelocatesAfterMinorChange() = runBlocking<Unit> {
        val old = openSettings()
        // 屏幕中部一个有名字、名字唯一的可点条目
        val target = old.elements.first { e ->
            e.clickable && e.name.isNotBlank() && e.centerY in 800..1300 && old.elements.count { it.clickable && it.name == e.name } == 1
        }
        // 慢慢往上拖一小段,界面轻微挪动;再读一次屏,旧快照就过期了
        ok(screen.swipe(360, 1000, 360, 960))
        val now = screen.snapshot()
        val moved = now.elements.first { it.clickable && it.name == target.name }
        assertTrue("moved too far: $target -> $moved", kotlin.math.abs(moved.centerY - target.centerY) < 100)

        val r = ok(screen.tapRef(target.ref, old.snapshotId))
        assertTrue(r.relocated)
        assertEquals(true, r.changed)
        snapUntil { s -> s.elements.none { it.name.startsWith("WLAN") && it.clickable } }
    }

    @Test
    fun typeTextVerifiesReadback() = runBlocking<Unit> {
        val s = openSettings()
        val field = s.elements.first { it.editable }
        val r = ok(screen.typeText(field.ref, s.snapshotId, "蓝牙"))
        assertEquals(Verification.MATCH, r.verification)
        val after = snapUntil { snap -> snap.elements.any { it.editable && it.name == "蓝牙" } }

        ok(screen.clearText(after.elements.first { it.editable }.ref, after.snapshotId))
        snapUntil { snap -> snap.elements.none { it.editable && it.name == "蓝牙" } }
    }

    @Test
    fun typeTextWithoutFocusFails() = runBlocking<Unit> {
        openSettings()
        val r = screen.typeText(null, null, "WLAN")
        assertEquals(ActionResult.Failed("no_focus"), r)
    }

    @Test
    fun peekDoesNotRegisterRefsAndReportsFocus() = runBlocking<Unit> {
        val s = openSettings()
        val p = screen.peek()
        val wlan = p.elements.first { it.clickable && it.name.startsWith("WLAN") }
        // peek 的读屏不进编号登记表
        assertEquals(ActionResult.Failed("ref_expired"), screen.tapRef(wlan.ref, p.snapshotId))
        val search = s.elements.first { it.editable || it.name.contains("搜索") }
        ok(screen.tapRef(search.ref, s.snapshotId))
        // 搜索框拿到输入焦点后,读屏标出 focused
        assertNotNull("no focused field", pollUntil(5_000) { runBlocking { screen.peek() }.elements.firstOrNull { it.editable && it.focused } })
    }

    @Test
    fun typeTextInSearch() = runBlocking<Unit> {
        val s = openSettings()
        val search = s.elements.first { it.editable || it.name.contains("搜索") }
        ok(screen.tapRef(search.ref, s.snapshotId))
        // 搜索页的输入框拿到焦点后直接往焦点框里打字
        val typed = pollUntil(5_000) {
            runBlocking { screen.typeText(null, null, "WLAN") }.takeIf { it is ActionResult.Ok }
        }
        assertNotNull("no focused search field", typed)
        assertEquals(Verification.MATCH, (typed as ActionResult.Ok).verification)
        snapUntil { snap -> snap.elements.any { it.editable && it.name == "WLAN" } }
    }

    @Test
    fun appSwitchDetected() = runBlocking<Unit> {
        openSettings()
        DeviceSupport.startTestActivity(agent.display.displayId, PlainTestActivity::class.java)
        snapUntil { it.packageName == TEST_PACKAGE }
        val r = ok(screen.tapXY(360, 1400))
        assertEquals(TEST_PACKAGE, r.appSwitched)
    }

    @Test
    fun checkScreenDiagnosesBlankAndProtected() = runBlocking<Unit> {
        val blank = screen.checkScreen()
        assertEquals(ScreenState.BLANK, blank.state)
        assertFalse(blank.needsHuman)

        DeviceSupport.startTestActivity(agent.display.displayId, SecureTestActivity::class.java)
        snapUntil { s -> s.packageName == TEST_PACKAGE && s.elements.any { it.name == "secure page" } }
        val protectedCheck = pollUntil(5_000) {
            runBlocking { screen.checkScreen() }.takeIf { it.state == ScreenState.PROTECTED }
        }
        assertNotNull("secure page was not diagnosed as protected", protectedCheck)
        assertTrue(protectedCheck!!.needsHuman)
        assertTrue(screen.snapshot().protected)
        assertTrue(screen.isProtected())
    }

    @Test
    fun tapNavigatesIntoWlan() = runBlocking<Unit> {
        val shell = DeviceSupport.shell()
        val main = DeviceSupport.mainScreenState(shell)
        val s = openSettings()
        val r = ok(screen.tapRef(s.clickable("WLAN").ref, s.snapshotId))
        assertEquals(true, r.changed)
        val wlan = snapUntil { snap -> snap.elements.any { it.role == "Switch" } }
        assertTrue(wlan.elements.any { it.name == "WLAN" && !it.clickable })
        assertTrue("still on the home page", wlan.elements.none { it.clickable && it.name.startsWith("蓝牙") })
        assertEquals("main screen changed", main, DeviceSupport.mainScreenState(shell))
    }

    @Test
    fun mainDisplayFocusUnchanged() = runBlocking<Unit> {
        val shell = DeviceSupport.shell()
        val main = DeviceSupport.mainScreenState(shell)
        fun check(step: String) = assertEquals("main screen changed after $step", main, DeviceSupport.mainScreenState(shell))

        val s = openSettings(); check("openApp")
        ok(screen.tapRef(s.clickable("WLAN").ref, s.snapshotId)); check("phone_tap")
        ok(screen.swipe(360, 1200, 360, 600)); check("phone_swipe")
        ok(screen.back()); check("phone_back")
        val home = snapUntil { snap -> snap.elements.any { it.editable } }
        ok(screen.typeText(home.elements.first { it.editable }.ref, home.snapshotId, "WLAN")); check("typeText")
        ok(screen.pressKey(android.view.KeyEvent.KEYCODE_BACK)); check("pressKey")
        ok(screen.openApp(SETTINGS, cold = true)); check("cold openApp")
    }

    @Test
    fun coldLaunchReturnsToFirstPage() = runBlocking<Unit> {
        val s = openSettings()
        ok(screen.tapRef(s.clickable("WLAN").ref, s.snapshotId))
        snapUntil { snap -> snap.elements.any { it.role == "Switch" } }
        // 普通启动回到上次的页面,冷启动回到首页
        ok(screen.openApp(SETTINGS, cold = true))
        snapUntil { snap -> snap.elements.any { it.clickable && it.name.startsWith("WLAN") } }
    }
}
