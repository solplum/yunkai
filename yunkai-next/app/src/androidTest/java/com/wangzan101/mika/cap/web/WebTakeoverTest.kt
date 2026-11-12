package com.wangzan101.mika.cap.web

import com.wangzan101.mika.ChineseUi
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.core.uistate.ActionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.util.Collections
import kotlin.coroutines.resume

/** 真机测试:网页接管把同一个 WebView 拿到主屏、再放回后台宿主,页面状态不丢。 */
@RunWith(AndroidJUnit4::class)
class WebTakeoverTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val tmp = TemporaryFolder()
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private lateinit var host: WebHost
    private lateinit var tab: WebTab
    private lateinit var registry: SiteRegistry
    private val actions: MutableList<WebHandover.Action> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        registry = SiteRegistry(tmp.root)
        val bounds = ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        instr.runOnMainSync {
            host = WebHost(ctx, bounds.width(), bounds.height(), ctx.resources.displayMetrics.densityDpi)
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(instr.context)).build()
            tab = WebTab(ctx, "t1", "task", listOf(loader))
            host.attach(tab)
        }
    }

    @After
    fun tearDown() {
        WebHandover.end()
        instr.waitForIdleSync()
        instr.runOnMainSync {
            tab.destroy()
            host.close()
        }
    }

    private suspend fun js(script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c -> tab.view.evaluateJavascript(script) { c.resume(it) } }
    }

    private fun displayOfView(): Int? {
        var id: Int? = null
        instr.runOnMainSync { id = tab.view.display?.displayId }
        return id
    }

    private fun awaitOnMain() = assertNotNull("WebView never reached the main screen",
        DeviceSupport.pollUntil(5_000) { displayOfView()?.takeIf { it == Display.DEFAULT_DISPLAY } })

    private fun awaitBackHome() = assertNotNull("WebView never went back to the background host",
        DeviceSupport.pollUntil(5_000) { displayOfView()?.takeIf { it == host.displayId } })

    private fun buttons(t: WebTab = tab): List<Button> {
        val out = mutableListOf<Button>()
        instr.runOnMainSync {
            fun walk(v: View) {
                if (v is Button) out += v
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(t.view.rootView)
        }
        return out
    }

    private fun click(label: String) {
        val b = buttons().firstOrNull { it.text == label }
        assertNotNull("no button $label in ${buttons().map { it.text }}", b)
        instr.runOnMainSync { b!!.performClick() }
    }

    /** 测试自己当任务服务:按钮记下来,交回类的结束这次接管。 */
    private fun showTask(reason: String = "login") = instr.runOnMainSync {
        assertTrue(WebHandover.show(ctx, tab, host, reason, "请登录示例商城", waitKey = 7, taskId = "task", onAction = { a ->
            actions += a
            when (a) {
                WebHandover.Action.HandBack, WebHandover.Action.FinishedMyself, WebHandover.Action.Done -> WebHandover.end(loggedIn = true, registry = registry)
                WebHandover.Action.Cancel, WebHandover.Action.PutAside -> WebHandover.end()
            }
        }))
    }

    private fun openForm() = runBlocking {
        assertTrue(tab.load("https://appassets.androidplatform.net/assets/web-fixtures/inputs.html"))
        val screen = WebScreen({ tab }, host, SystemClock::uptimeMillis)
        val s = screen.snapshot()
        val plain = s.elements.first { it.resourceId == "#plain" }
        assertTrue(screen.typeText(plain.ref, s.snapshotId, "填了一半") is ActionResult.Ok)
    }

    @Test
    fun sameWebViewMovesToMainAndBack() = runBlocking<Unit> {
        openForm()
        showTask()
        awaitOnMain()
        assertEquals("\"填了一半\"", js("document.getElementById('plain').value"))
        click("处理好了")
        awaitBackHome()
        assertEquals(listOf(WebHandover.Action.HandBack), actions.toList())
        assertEquals("\"填了一半\"", js("document.getElementById('plain').value"))
        assertNull(WebHandover.session.value)
    }

    @Test
    fun backKeyKeepsWaiting() = runBlocking<Unit> {
        openForm()
        showTask()
        awaitOnMain()
        instr.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        awaitBackHome()
        assertEquals(listOf(WebHandover.Action.PutAside), actions.toList())
    }

    @Test
    fun handBackCarriesInputCount() = runBlocking<Unit> {
        openForm()
        lateinit var counted: com.wangzan101.mika.takeover.HandoverActivity
        instr.runOnMainSync {
            WebHandover.show(ctx, tab, host, "login", null, waitKey = 1, taskId = "task", onAction = { a ->
                actions += a
                WebHandover.end(loggedIn = true, registry = registry)?.let { counted = it }
            })
        }
        awaitOnMain()
        val loc = IntArray(2)
        instr.runOnMainSync { tab.view.getLocationOnScreen(loc) }
        repeat(3) {
            val t = SystemClock.uptimeMillis()
            val x = loc[0] + 50f
            val y = loc[1] + 300f
            instr.sendPointerSync(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
            instr.sendPointerSync(MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0))
        }
        DeviceSupport.pollUntil(3_000) { WebHandover.session.value?.title?.takeIf { it == "inputs" } }
        click("处理好了")
        awaitBackHome()
        assertTrue("inputs=${counted.inputCount}", counted.inputCount >= 3)
        assertEquals("inputs", counted.stoppedAt)
    }

    @Test
    fun settingsLoginModeHasOnlyDone() = runBlocking<Unit> {
        val done = Collections.synchronizedList(mutableListOf<String>())
        instr.runOnMainSync { assertTrue(WebHandover.showLogin(ctx, "appassets.androidplatform.net") { done += it }) }
        val login = DeviceSupport.pollUntil(5_000) { WebHandover.session.value?.tab }
        assertNotNull(login)
        DeviceSupport.pollUntil(5_000) { buttons(login!!).takeIf { it.isNotEmpty() } }
        assertEquals(listOf("完成"), buttons(login!!).map { it.text.toString() })
        DeviceSupport.pollUntil(5_000) { WebHandover.session.value?.url }
        // buttons() 自己在主线程上取;点击另起一次主线程调用(不能嵌套)
        val doneButton = buttons(login).single()
        instr.runOnMainSync { doneButton.performClick() }
        assertNotNull(DeviceSupport.pollUntil(5_000) { done.firstOrNull() })
        assertEquals("appassets.androidplatform.net", done.single())
        assertNull(WebHandover.session.value)
    }

    @Test
    fun showRefusesATabOfAnotherTaskOrAClosedHost() = runBlocking<Unit> {
        openForm()
        instr.runOnMainSync {
            assertTrue(!WebHandover.show(ctx, tab, host, "login", null, waitKey = 1, taskId = "other-task", onAction = {}))
        }
        assertNull(WebHandover.session.value)
    }

    @Test
    fun buttonsOfAPastWaitAreIgnored() = runBlocking<Unit> {
        openForm()
        instr.runOnMainSync {
            assertTrue(WebHandover.show(ctx, tab, host, "login", null, waitKey = 7, taskId = "task",
                onAction = { actions += it }, currentKey = { 8 }))
        }
        awaitOnMain()
        click("处理好了")
        instr.waitForIdleSync()
        assertTrue(actions.isEmpty())
        assertNotNull(WebHandover.session.value)
    }

    @Test
    fun cancelWorksEvenForAPastWait() = runBlocking<Unit> {
        openForm()
        instr.runOnMainSync {
            assertTrue(WebHandover.show(ctx, tab, host, "login", null, waitKey = 7, taskId = "task",
                onAction = { actions += it }, currentKey = { 8 }))
        }
        awaitOnMain()
        click("取消任务")
        instr.waitForIdleSync()
        assertEquals(listOf(WebHandover.Action.Cancel), actions.toList())
    }

    @Test
    fun endIsOnceAndOnlyForItsOwnTab() = runBlocking<Unit> {
        openForm()
        showTask()
        awaitOnMain()
        lateinit var other: WebTab
        instr.runOnMainSync { other = WebTab(ctx, "t2", "task") }
        try {
            assertNull(WebHandover.end(tab = other))
            assertNotNull(WebHandover.session.value)
            assertNotNull(WebHandover.end(tab = tab))
            assertNull(WebHandover.end(tab = tab))
            awaitBackHome()
        } finally {
            instr.runOnMainSync { other.destroy() }
        }
    }

    @Test
    fun restoreSkipsAClosedHost() = runBlocking<Unit> {
        openForm()
        showTask()
        awaitOnMain()
        instr.runOnMainSync { host.close() }
        WebHandover.end()
        instr.waitForIdleSync()
        var parent: Any? = Unit
        instr.runOnMainSync { parent = tab.view.parent }
        assertNull(parent)
    }

    @Test
    fun marksSiteLoggedIn() = runBlocking<Unit> {
        openForm()
        showTask()
        awaitOnMain()
        DeviceSupport.pollUntil(3_000) { WebHandover.session.value?.url }
        click("我自己做完了")
        awaitBackHome()
        val e = registry.list().single()
        assertEquals("appassets.androidplatform.net", e.host)
        assertTrue(e.loggedInByUser)
    }
}
