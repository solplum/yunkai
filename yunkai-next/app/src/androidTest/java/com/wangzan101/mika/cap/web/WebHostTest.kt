package com.wangzan101.mika.cap.web

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume

/**
 * 真机测试:网页在 App 自建的私有虚拟显示上后台渲染。不需要内置助手,也不碰主屏。
 * 夹具在测试包的 assets 里,经 WebViewAssetLoader 以 https 地址提供,不依赖外网。
 */
@RunWith(AndroidJUnit4::class)
class WebHostTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private lateinit var host: WebHost
    private lateinit var tab: WebTab

    private val fixture = "https://appassets.androidplatform.net/assets/web-fixtures/host.html"

    @Before
    fun setUp() {
        val bounds = ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val dpi = ctx.resources.displayMetrics.densityDpi
        instr.runOnMainSync {
            host = WebHost(ctx, bounds.width(), bounds.height(), dpi)
            // 夹具在测试包里,所以用测试包的 context 读 assets
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(instr.context))
                .build()
            tab = WebTab(ctx, "t1", "task-1", listOf(loader))
            host.attach(tab)
        }
    }

    @After
    fun tearDown() {
        instr.runOnMainSync {
            host.detach(tab.view)
            tab.destroy()
            host.close()
        }
    }

    private suspend fun js(script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c -> tab.view.evaluateJavascript(script) { c.resume(it) } }
    }

    private suspend fun num(expr: String): Int {
        val raw = js(expr)
        return raw.toIntOrNull() ?: throw AssertionError("$expr is not a number: $raw")
    }

    private fun topResumed(): String? = DeviceSupport.sh("dumpsys activity activities").lines()
        .map { it.trim() }.firstOrNull { it.startsWith("topResumedActivity") || it.startsWith("mTopResumedActivity") }

    @Test
    fun rendersInBackgroundWithoutTouchingMainScreen() = runBlocking<Unit> {
        val before = topResumed()
        assertNotNull("topResumedActivity not found in dumpsys", before)
        assertTrue(tab.load(fixture))
        assertEquals("\"host\"", js("document.title"))
        assertEquals(before, topResumed())
        assertNotEquals(HostMode.Detached, host.mode)
    }

    @Test
    fun pageThinksItIsVisible() = runBlocking<Unit> {
        assertTrue(tab.load(fixture))
        assertEquals("\"visible\"", js("document.visibilityState"))
    }

    @Test
    fun timersAndAnimationFramesRun() = runBlocking<Unit> {
        assertTrue(tab.load(fixture))
        val t0 = num("window.ticks")
        val f0 = num("window.frames")
        Thread.sleep(2_000)
        val ticks = num("window.ticks") - t0
        val frames = num("window.frames") - f0
        assertTrue("ticks=$ticks", ticks >= 10)
        assertTrue("frames=$frames", frames >= 10)
    }

    @Test
    fun frameIsNotBlack() = runBlocking<Unit> {
        assertTrue(tab.load(fixture))
        val png = DeviceSupport.pollUntil(5_000) { host.latestFramePng()?.takeIf { isMostlyRed(it) } }
        assertNotNull("no red frame; mode=${host.mode} last=${host.latestFramePng()?.size}", png)
    }

    @Test
    fun touchProducesTrustedClick() = runBlocking<Unit> {
        assertTrue(tab.load(fixture))
        // CSS 像素 × (视图宽 / innerWidth) = WebView 视图像素(桌面版仿真把 1280 CSS 像素缩进视图宽度,不能用 devicePixelRatio)
        val viewW = withContext(Dispatchers.Main) { tab.view.width }
        val c = js(
            "(function(){var r=document.getElementById('btn').getBoundingClientRect();" +
                "var d=$viewW/window.innerWidth;return [(r.left+r.width/2)*d,(r.top+r.height/2)*d].join(',');})()"
        ).trim('"').split(',').map { it.toFloat() }
        val down = SystemClock.uptimeMillis()
        withContext(Dispatchers.Main) {
            MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, c[0], c[1], 0).also { tab.view.dispatchTouchEvent(it) }.recycle()
        }
        Thread.sleep(50)
        withContext(Dispatchers.Main) {
            MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, c[0], c[1], 0)
                .also { tab.view.dispatchTouchEvent(it) }.recycle()
        }
        val trusted = DeviceSupport.pollUntil(3_000) { runBlocking { js("window.clickTrusted") }.takeIf { it != "null" } }
        assertEquals("true", trusted)
    }

    private fun isMostlyRed(png: ByteArray): Boolean {
        val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return false
        var red = 0
        var total = 0
        for (y in 0 until bmp.height step bmp.height / 20) for (x in 0 until bmp.width step bmp.width / 20) {
            val p = bmp.getPixel(x, y)
            total++
            if (Color.red(p) > 200 && Color.green(p) < 60 && Color.blue(p) < 60) red++
        }
        return red * 2 > total
    }
}
