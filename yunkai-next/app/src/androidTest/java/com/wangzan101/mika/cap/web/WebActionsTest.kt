package com.wangzan101.mika.cap.web

import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.ScreenState
import com.wangzan101.mika.core.uistate.Verification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume

/** 真机测试:网页上的点击、打字、清空、滚动、后退都走真实输入,并诚实回读。不需要内置助手,不碰主屏。 */
@RunWith(AndroidJUnit4::class)
class WebActionsTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private lateinit var host: WebHost
    private lateinit var tab: WebTab
    private lateinit var screen: WebScreen

    private fun fixture(name: String) = "https://appassets.androidplatform.net/assets/web-fixtures/$name"

    @Before
    fun setUp() {
        val bounds = ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val dpi = ctx.resources.displayMetrics.densityDpi
        instr.runOnMainSync {
            host = WebHost(ctx, bounds.width(), bounds.height(), dpi)
            val assets = WebViewAssetLoader.AssetsPathHandler(instr.context)
            val main = WebViewAssetLoader.Builder().addPathHandler("/assets/", assets).build()
            val other = WebViewAssetLoader.Builder().setDomain("fixtures2.androidplatform.net").addPathHandler("/assets/", assets).build()
            tab = WebTab(ctx, "t1", "task-1", listOf(main, other))
            host.attach(tab)
        }
        screen = WebScreen({ tab }, host, SystemClock::uptimeMillis)
    }

    @After
    fun tearDown() {
        instr.runOnMainSync {
            tab.destroy()
            host.close()
        }
    }

    private fun open(name: String) = runBlocking { assertTrue("load $name", tab.load(fixture(name))) }

    private suspend fun js(script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c -> tab.view.evaluateJavascript(script) { c.resume(it) } }
    }

    private fun ScreenSnapshot.ref(resourceId: String): String {
        val e = elements.firstOrNull { it.resourceId == resourceId }
        assertNotNull("$resourceId not in ${elements.map { it.resourceId to it.name }}", e)
        return e!!.ref
    }

    private suspend fun status() = js("document.getElementById('status').textContent")

    @Test
    fun tapByRefTrustedAndChanged() = runBlocking<Unit> {
        open("inputs.html")
        val s = screen.snapshot()
        assertTrue(s.snapshotId.startsWith("wt1-"))
        val r = screen.tapRef(s.ref("#counter"), s.snapshotId)
        assertTrue("$r", r is ActionResult.Ok)
        assertEquals(true, (r as ActionResult.Ok).changed)
        assertEquals("1", js("window.clicks"))
        assertEquals("true", js("window.lastTrusted"))
    }

    @Test
    fun tapUnchangedReportsFalse() = runBlocking<Unit> {
        open("inputs.html")
        val s = screen.snapshot()
        val r = screen.tapRef(s.ref("#noop"), s.snapshotId) as ActionResult.Ok
        assertEquals(false, r.changed)
        assertEquals(null, r.appSwitched)
    }

    @Test
    fun obscuredButtonNotClickedAndNamesCover() = runBlocking<Unit> {
        open("inputs.html")
        js("document.getElementById('cookie').style.display = 'none'")
        val s = screen.snapshot()
        val buy = s.ref("#buy")
        js("document.getElementById('cookie').style.display = ''")
        val r = screen.tapRef(buy, s.snapshotId)
        assertTrue("$r", r is ActionResult.Failed)
        assertEquals("obscured", (r as ActionResult.Failed).reason)
        assertTrue(r.hint, r.hint!!.contains("Cookie"))
        assertEquals("0", js("window.buyClicks"))
    }

    @Test
    fun staleRefRelocatesAfterReRender() = runBlocking<Unit> {
        open("inputs.html")
        val s = screen.snapshot()
        val target = s.ref("#target")
        js("rerender()")
        val r = screen.tapRef(target, s.snapshotId)
        assertTrue("$r", r is ActionResult.Ok)
        assertTrue((r as ActionResult.Ok).relocated)
        assertEquals("目标按钮", r.relocatedTo)
        assertEquals("1", js("window.targetClicks"))
    }

    @Test
    fun staleRefAfterNavigation() = runBlocking<Unit> {
        open("inputs.html")
        val s = screen.snapshot()
        open("host.html")
        assertEquals(ActionResult.Failed("ref_expired"), screen.tapRef(s.ref("#target"), s.snapshotId))
        assertEquals(ActionResult.Failed("ref_expired"), screen.tapRef("@e1", "wt1-999"))
    }

    @Test
    fun editorReceivesRealInput() = runBlocking<Unit> {
        open("inputs.html")
        val s = screen.snapshot()
        val r = screen.typeText(s.ref("#editor"), s.snapshotId, "你好 Mika") as ActionResult.Ok
        assertEquals(Verification.MATCH, r.verification)
        val st = status()
        assertFalse(st, st.contains("inputs=0 "))
        assertTrue(st, st.endsWith("text=你好 Mika\""))
        // 再打一次是替换,不是接在后面
        val s2 = screen.snapshot()
        assertEquals(Verification.MATCH, (screen.typeText(s2.ref("#editor"), s2.snapshotId, "第二次") as ActionResult.Ok).verification)
        assertTrue(status().endsWith("text=第二次\""))
    }

    @Test
    fun plainFieldMatches() = runBlocking<Unit> {
        open("inputs.html")
        val s = screen.snapshot()
        val r = screen.typeText(s.ref("#plain"), s.snapshotId, "hello world") as ActionResult.Ok
        assertEquals(Verification.MATCH, r.verification)
        assertFalse(r.secret)
        assertEquals("\"hello world\"", js("document.getElementById('plain').value"))
    }

    @Test
    fun typingRefusesWhenPageStealsFocus() = runBlocking<Unit> {
        open("inputs.html")
        val s = screen.snapshot()
        assertEquals(ActionResult.Failed("focus_moved"), screen.typeText(s.ref("#thief"), s.snapshotId, "不该落到别处"))
        assertEquals("\"\"", js("document.getElementById('plain').value"))
        assertEquals("\"\"", js("document.getElementById('thief').value"))
        assertEquals(ActionResult.Failed("focus_moved"), screen.clearText(s.ref("#thief"), s.snapshotId))
    }

    @Test
    fun reformattingFieldReportsMismatch() = runBlocking<Unit> {
        open("inputs.html")
        val s = screen.snapshot()
        val r = screen.typeText(s.ref("#phone"), s.snapshotId, "13812345678") as ActionResult.Ok
        assertEquals(Verification.MISMATCH, r.verification)
        assertEquals("\"138 1234 5678\"", js("document.getElementById('phone').value"))
    }

    @Test
    fun passwordFieldUnknownNoEnter() = runBlocking<Unit> {
        open("inputs.html")
        val s = screen.snapshot()
        val r = screen.typeText(s.ref("#pw"), s.snapshotId, "s3cret") as ActionResult.Ok
        assertEquals(Verification.UNKNOWN, r.verification)
        assertTrue(r.secret)
        assertEquals("6", js("document.getElementById('pw').value.length"))
        delay(300)
        assertEquals("0", js("window.submits"))
        assertFalse(screen.snapshot().elements.any { "s3cret" in it.name })
    }

    @Test
    fun clearTextReadsBackEmpty() = runBlocking<Unit> {
        open("inputs.html")
        val s = screen.snapshot()
        screen.typeText(s.ref("#plain"), s.snapshotId, "要清掉的字")
        val s2 = screen.snapshot()
        val r = screen.clearText(s2.ref("#plain"), s2.snapshotId)
        assertTrue("$r", r is ActionResult.Ok)
        assertEquals(Verification.MATCH, (r as ActionResult.Ok).verification)
        assertEquals("\"\"", js("document.getElementById('plain').value"))
    }

    @Test
    fun scrollContainerMoves() = runBlocking<Unit> {
        open("inputs.html")
        val s = screen.snapshot()
        val box = s.elements.first { it.resourceId == "#box" }
        assertTrue(box.scrollable)
        val r = screen.scroll(box.ref, s.snapshotId, down = true) as ActionResult.Ok
        assertEquals(true, r.changed)
        assertTrue(js("document.getElementById('box').scrollTop").toDouble() > 0)
        assertEquals("0", js("Math.round(window.scrollY)"))
    }

    @Test
    fun backUsesHistoryAndNoHistoryFails() = runBlocking<Unit> {
        open("inputs.html")
        assertEquals(ActionResult.Failed("no_history"), screen.back())
        open("host.html")
        val r = screen.back()
        assertTrue("$r", r is ActionResult.Ok)
        assertEquals(true, (r as ActionResult.Ok).changed)
        assertTrue(js("location.pathname").endsWith("inputs.html\""))
    }

    @Test
    fun restlessPageReturnsWithinCap() = runBlocking<Unit> {
        open("restless.html")
        val s = screen.snapshot()
        val start = SystemClock.uptimeMillis()
        val r = screen.tapRef(s.ref("#still"), s.snapshotId)
        assertTrue("$r", r is ActionResult.Ok)
        assertTrue("took ${SystemClock.uptimeMillis() - start} ms", SystemClock.uptimeMillis() - start < 3_500)
        val s2 = screen.snapshot()
        assertTrue(s2.elements.isNotEmpty())
    }

    @Test
    fun disallowedKeyRejected() = runBlocking<Unit> {
        open("inputs.html")
        assertEquals(ActionResult.Failed("key_not_allowed"), screen.pressKey(KeyEvent.KEYCODE_A))
        assertEquals(ActionResult.Failed("key_not_allowed"), screen.pressKey(KeyEvent.KEYCODE_BACK))
        assertTrue(screen.pressKey(KeyEvent.KEYCODE_TAB) is ActionResult.Ok)
    }

    @Test
    fun checkScreenUsesWebFacts() = runBlocking<Unit> {
        open("frames.html")
        delay(300)
        val plain = screen.checkScreen()
        assertEquals(ScreenState.NORMAL, plain.state)
        assertEquals("appassets.androidplatform.net", plain.currentApp)
        open("inputs.html")
        val login = screen.checkScreen()
        assertEquals("$login", ScreenState.NEEDS_HUMAN, login.state)
    }

    @Test
    fun snapshotCarriesWebInfoAndSite() = runBlocking<Unit> {
        open("inputs.html")
        val s = screen.snapshot()
        assertEquals("appassets.androidplatform.net", s.packageName)
        assertEquals(fixture("inputs.html"), s.web!!.url)
        assertEquals("inputs", s.web!!.title)
        assertEquals("t1", s.web!!.tabId)
        // 端口自己为核实读的屏不登记编号,模型手里的编号仍可用
        screen.peek()
        assertTrue(screen.tapRef(s.ref("#noop"), s.snapshotId) is ActionResult.Ok)
    }

    @Test
    fun mainScreenUnaffected() = runBlocking<Unit> {
        fun topResumed() = DeviceSupport.sh("dumpsys activity activities").lines().map { it.trim() }
            .firstOrNull { it.startsWith("topResumedActivity") || it.startsWith("mTopResumedActivity") }
        fun imeShown() = DeviceSupport.sh("dumpsys input_method").lines().map { it.trim() }
            .firstOrNull { it.startsWith("mInputShown=") || it.contains(" mInputShown=") }
        val before = topResumed()
        val imeBefore = imeShown()
        assertNotNull(before)
        open("inputs.html")
        val s = screen.snapshot()
        screen.tapRef(s.ref("#counter"), s.snapshotId)
        screen.typeText(s.ref("#plain"), s.snapshotId, "abc")
        screen.pressKey(KeyEvent.KEYCODE_ENTER)
        screen.scroll(null, null, down = true)
        assertEquals(before, topResumed())
        assertEquals(imeBefore, imeShown())
    }
}
