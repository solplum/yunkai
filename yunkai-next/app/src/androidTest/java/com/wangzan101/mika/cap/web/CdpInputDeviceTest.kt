package com.wangzan101.mika.cap.web

import android.os.SystemClock
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import com.wangzan101.mika.core.uistate.CdpKeys
import com.wangzan101.mika.core.cap.web.cdp.CdpSession
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.Verification
import com.wangzan101.mika.core.cap.web.DEVTOOLS_UNAVAILABLE
import com.wangzan101.mika.core.cap.web.WebPoint
import com.wangzan101.mika.cap.web.cdp.WebDevtools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import com.wangzan101.mika.cap.phone.PhoneServices

/**
 * 真机测试:有 DevTools 会话时点击、悬停、拖动、下拉选择、组合键、插入文字都是可信的鼠标键盘事件;
 * 会话强制为空时退回原生输入,原有行为照旧(触摸带手指类型,悬停、拖动、下拉报 devtools_unavailable)。
 * 有会话的几项要等连接接入(devtools.provider)后才跑,没有会话时跳过。
 */
@RunWith(AndroidJUnit4::class)
class CdpInputDeviceTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    /** 本测试自己的一份:换会话来源、连法都不影响别的测试。 */
    private val devtools = WebDevtools(PhoneServices.of(InstrumentationRegistry.getInstrumentation().targetContext).hub::openDevtoolsSocket)
    private lateinit var host: WebHost
    private lateinit var tab: WebTab
    private lateinit var screen: WebScreen

    @Before
    fun setUp() {
        val bounds = ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val dpi = ctx.resources.displayMetrics.densityDpi
        instr.runOnMainSync {
            host = WebHost(ctx, bounds.width(), bounds.height(), dpi)
            val assets = WebViewAssetLoader.AssetsPathHandler(instr.context)
            val main = WebViewAssetLoader.Builder().addPathHandler("/assets/", assets).build()
            tab = WebTab(ctx, "t1", "task-1", listOf(main), devtools = devtools)
            host.attach(tab)
        }
        screen = WebScreen({ tab }, host, SystemClock::uptimeMillis)
        runBlocking { assertTrue(tab.load("https://appassets.androidplatform.net/assets/web-fixtures/interact.html")) }
    }

    @After
    fun tearDown() {
        instr.runOnMainSync {
            tab.destroy()
            host.close()
        }
    }

    private suspend fun js(script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c -> tab.view.evaluateJavascript(script) { c.resume(it) } }
    }

    private fun ScreenSnapshot.ref(resourceId: String): String {
        val e = elements.firstOrNull { it.resourceId == resourceId }
        assertNotNull("$resourceId not in ${elements.map { it.resourceId to it.name }}", e)
        return e!!.ref
    }

    /** 连接是异步建的:等一会儿;等不到就跳过需要会话的测试。 */
    private suspend fun requireSession(): CdpSession {
        val s = withTimeoutOrNull(10_000) {
            var s: CdpSession? = null
            while (s == null) { s = devtools.session(tab); if (s == null) delay(200) }
            s
        }
        assumeTrue("this tab has no DevTools session", s != null)
        return s!!
    }

    private fun noSession() { devtools.provider = { null } }

    /** 元素中心的 view 像素(页面 CSS 像素 × view 宽 ÷ 视口宽),给按坐标的落点用。 */
    private suspend fun viewCenter(id: String, dx: Double = 0.0): WebPoint.Xy {
        val r = js("(function(){var b=document.getElementById('$id').getBoundingClientRect();return [b.left+b.width/2,b.top+b.height/2,innerWidth].join(',')})()")
            .trim('"').split(',').map(String::toDouble)
        val k = withContext(Dispatchers.Main) { tab.view.width } / r[2]
        return WebPoint.Xy(((r[0] + dx) * k).roundToInt(), (r[1] * k).roundToInt())
    }

    /** 页面脚本没把滑块列成元素时按坐标。 */
    private suspend fun knob(s: ScreenSnapshot): WebPoint =
        s.elements.firstOrNull { it.resourceId == "#knob" }?.let { WebPoint.Ref(it.ref, s.snapshotId) } ?: viewCenter("knob")

    // ---------- 有会话 ----------

    @Test
    fun clickIsATrustedMouseClick() = runBlocking<Unit> {
        requireSession()
        val s = screen.snapshot()
        val r = screen.tapRef(s.ref("#btn"), s.snapshotId)
        assertTrue("$r", r is ActionResult.Ok && r.changed == true)
        assertEquals("1", js("window.clicks"))
        assertEquals("true", js("window.lastTrusted"))
        assertEquals("\"mouse\"", js("window.lastPointer"))
        // 按坐标点也一样
        assertTrue(screen.tapXY(viewCenter("btn").x, viewCenter("btn").y) is ActionResult.Ok)
        assertEquals("2", js("window.clicks"))
    }

    @Test
    fun hoverOpensACssMenu() = runBlocking<Unit> {
        requireSession()
        val s = screen.snapshot()
        val r = screen.hover(WebPoint.Ref(s.ref("#menu"), s.snapshotId))
        assertTrue("$r", r is ActionResult.Ok)
        assertEquals("\"block\"", js("getComputedStyle(document.querySelector('#menu .items')).display"))
        val s2 = screen.snapshot()
        assertTrue(screen.tapRef(s2.ref("#item"), s2.snapshotId) is ActionResult.Ok)
        assertEquals("1", js("window.itemClicks"))
    }

    @Test
    fun selectPicksTheOptionWithTrustedChange() = runBlocking<Unit> {
        requireSession()
        val s = screen.snapshot()
        val r = screen.selectOption(s.ref("#city"), s.snapshotId, "上海")
        assertTrue("$r", r is ActionResult.Ok)
        assertEquals(Verification.MATCH, (r as ActionResult.Ok).verification)
        assertEquals("\"sh\"", js("document.getElementById('city').value"))
        assertEquals("\"sh:true\"", js("window.change"))
        val s2 = screen.snapshot()
        val missing = screen.selectOption(s2.ref("#city"), s2.snapshotId, "广州")
        assertEquals("no_such_option", (missing as ActionResult.Failed).reason)
        assertTrue(missing.hint!!, missing.hint!!.contains("北京"))
        assertEquals("not_a_select", (screen.selectOption(s2.ref("#btn"), s2.snapshotId, "x") as ActionResult.Failed).reason)
    }

    @Test
    fun typingAndKeyCombos() = runBlocking<Unit> {
        requireSession()
        val s = screen.snapshot()
        val r = screen.typeText(s.ref("#text"), s.snapshotId, "hello 你好")
        assertEquals(Verification.MATCH, (r as ActionResult.Ok).verification)
        assertEquals("\"hello 你好\"", js("document.getElementById('text').value"))
        assertTrue(screen.pressCombo(CdpKeys.parseCombo("Control+A")!!) is ActionResult.Ok)
        assertTrue(screen.pressCombo(CdpKeys.parseCombo("Backspace")!!) is ActionResult.Ok)
        assertEquals("\"\"", js("document.getElementById('text').value"))
        assertTrue(js("window.keys.join('|')"), js("window.keys.join('|')").contains("Control+a:true"))
        assertTrue(screen.pressKey(android.view.KeyEvent.KEYCODE_ENTER) is ActionResult.Ok)
        assertTrue(js("window.keys.join('|')").contains("Enter:true"))
    }

    @Test
    fun dragMovesAPointerSlider() = runBlocking<Unit> {
        requireSession()
        val s = screen.snapshot()
        val to = viewCenter("knob", dx = 200.0)
        val r = screen.drag(knob(s), to, 10)
        assertTrue("$r", r is ActionResult.Ok)
        assertEquals("true", js("window.dragDone"))
        assertTrue(js("window.dragX"), js("window.dragX").toDouble() > 150)
    }

    // ---------- 没有会话:老路 ----------

    @Test
    fun withoutSessionTapsAreFingerTouches() = runBlocking<Unit> {
        noSession()
        val s = screen.snapshot()
        assertTrue(screen.tapRef(s.ref("#btn"), s.snapshotId) is ActionResult.Ok)
        assertEquals("1", js("window.clicks"))
        assertEquals("true", js("window.lastTrusted"))
        assertEquals("\"touch\"", js("window.lastPointer"))
    }

    @Test
    fun withoutSessionMouseOnlyActionsSayUnavailable() = runBlocking<Unit> {
        noSession()
        val s = screen.snapshot()
        assertEquals(DEVTOOLS_UNAVAILABLE, (screen.hover(WebPoint.Ref(s.ref("#menu"), s.snapshotId)) as ActionResult.Failed).reason)
        assertEquals(DEVTOOLS_UNAVAILABLE, (screen.drag(knob(s), viewCenter("knob", 200.0), 5) as ActionResult.Failed).reason)
        assertEquals(DEVTOOLS_UNAVAILABLE, (screen.selectOption(s.ref("#city"), s.snapshotId, "上海") as ActionResult.Failed).reason)
        assertEquals("\"\"", js("document.getElementById('city').value"))
    }

    @Test
    fun withoutSessionTypingAndCombosUseNativeKeys() = runBlocking<Unit> {
        noSession()
        val s = screen.snapshot()
        val r = screen.typeText(s.ref("#text"), s.snapshotId, "hello 你好")
        assertEquals(Verification.MATCH, (r as ActionResult.Ok).verification)
        assertTrue(screen.pressCombo(CdpKeys.parseCombo("Control+A")!!) is ActionResult.Ok)
        assertTrue(screen.pressCombo(CdpKeys.parseCombo("Backspace")!!) is ActionResult.Ok)
        assertEquals("\"\"", js("document.getElementById('text').value"))
        assertTrue(js("window.keys.join('|')"), js("window.keys.join('|')").contains("Control+a:true"))
    }
}
