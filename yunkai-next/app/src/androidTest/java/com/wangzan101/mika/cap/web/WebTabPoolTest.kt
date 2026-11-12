package com.wangzan101.mika.cap.web

import android.content.ComponentCallbacks2
import android.os.SystemClock
import android.view.WindowManager
import android.webkit.WebResourceResponse
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.core.uistate.ActionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.Collections
import kotlin.coroutines.resume

/** 真机测试:标签池的导航、弹窗、外跳、权限、崩溃、Cookie 保留、内存回收与任务隔离。不需要内置助手,不碰主屏。 */
@RunWith(AndroidJUnit4::class)
class WebTabPoolTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private lateinit var host: WebHost
    private lateinit var pool: WebTabPool
    private val events: MutableList<PoolEvent> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun fixture(name: String) = "https://appassets.androidplatform.net/assets/web-fixtures/$name"

    @Before
    fun setUp() {
        val bounds = ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        instr.runOnMainSync {
            host = WebHost(ctx, bounds.width(), bounds.height(), ctx.resources.displayMetrics.densityDpi)
            val assets = WebViewAssetLoader.AssetsPathHandler(instr.context)
            // /status/403:返回 403 的页面,测主文档状态
            val status = WebViewAssetLoader.PathHandler { path ->
                WebResourceResponse("text/html", "utf-8", ByteArrayInputStream("<title>forbidden</title>访问受限".toByteArray()))
                    .apply { setStatusCodeAndReasonPhrase(path.toIntOrNull() ?: 200, "Status") }
            }
            val main = WebViewAssetLoader.Builder().addPathHandler("/assets/", assets).addPathHandler("/status/", status).build()
            val other = WebViewAssetLoader.Builder().setDomain("fixtures2.androidplatform.net").addPathHandler("/assets/", assets).build()
            // 另一个可注册域名:androidplatform.net 下的子域与 appassets 同站
            val foreign = WebViewAssetLoader.Builder().setDomain("fixtures.mika-other.test").addPathHandler("/assets/", assets).build()
            pool = WebTabPool(ctx, host, assetLoaders = listOf(main, other, foreign))
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { pool.events.collect { events += it } }
    }

    @After
    fun tearDown() {
        scope.cancel()
        instr.runOnMainSync {
            pool.close()
            host.close()
        }
    }

    private fun screen(task: String) = WebScreen(pool, task, host, SystemClock::uptimeMillis)

    private suspend fun js(tab: WebTab, script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c -> tab.view.evaluateJavascript(script) { c.resume(it) } }
    }

    private fun current(task: String) = runBlocking(Dispatchers.Main) { pool.current(task) }

    private inline fun <reified T : PoolEvent> awaitEvent(timeoutMs: Long = 5_000, crossinline test: (T) -> Boolean = { true }): T {
        val found = DeviceSupport.pollUntil(timeoutMs) { synchronized(events) { events.filterIsInstance<T>().firstOrNull { test(it) } } }
        assertNotNull("no ${T::class.simpleName} in $events", found)
        return found!!
    }

    @Test
    fun navigateReportsFinalUrlTitleAndStatus() = runBlocking<Unit> {
        val s = screen("A")
        val r = s.navigate(fixture("redirect.html"), newTab = false)
        assertTrue(r.loaded)
        assertEquals(fixture("host.html?redirected=1"), r.tab.url)
        assertEquals("host", r.tab.title)
        assertEquals(200, r.httpStatus)
        assertNull(r.externalLink)
        val forbidden = s.navigate("https://appassets.androidplatform.net/status/403", newTab = false)
        assertEquals(403, forbidden.httpStatus)
        assertFalse(forbidden.newTab)
    }

    @Test
    fun popupOpensNewTabAndSwitches() = runBlocking<Unit> {
        val s = screen("A")
        val first = s.navigate(fixture("popup.html"), newTab = false).tab.tabId
        val snap = s.snapshot()
        s.tapRef(snap.elements.first { it.resourceId == "#one" }.ref, snap.snapshotId)
        val opened = awaitEvent<PoolEvent.NewTab> { it.taskId == "A" && it.fromPopup }
        assertEquals(2, s.tabs().size)
        assertEquals(opened.tabId, s.tabs().single { it.current }.tabId)
        DeviceSupport.pollUntil(5_000) { runBlocking(Dispatchers.Main) { pool.current("A")?.view?.url }?.takeIf { it.endsWith("host.html") } }
        assertTrue(s.tabs().any { it.tabId == first && !it.current })
    }

    @Test
    fun popupStormCappedAt4() = runBlocking<Unit> {
        val s = screen("A")
        s.navigate(fixture("popup.html"), newTab = false)
        val snap = s.snapshot()
        // 每次手势只放行一个 window.open:连点六次而不是一次开六个
        repeat(6) {
            val again = s.snapshot()
            s.tapRef(again.elements.first { it.resourceId == "#one" }.ref, again.snapshotId)
            delay(300)
            withContext(Dispatchers.Main) { pool.list("A").firstOrNull { !it.current }?.let { pool.switchTo("A", it.tabId) } }
        }
        awaitEvent<PoolEvent.Closed> { it.reason == "limit" }
        delay(1_000)
        assertTrue("${s.tabs()}", s.tabs().size <= 4)
    }

    @Test
    fun popupToOtherSiteAndScriptPopupsRefused() = runBlocking<Unit> {
        val s = screen("A")
        s.navigate(fixture("popup.html"), newTab = false)
        val snap = s.snapshot()
        s.tapRef(snap.elements.first { it.resourceId == "#other" }.ref, snap.snapshotId)
        delay(1_500)
        assertEquals("${s.tabs()}", 1, s.tabs().size)
        s.navigate(fixture("popup-auto.html"), newTab = false)
        delay(1_000)
        assertEquals("${s.tabs()}", 1, s.tabs().size)
    }

    @Test
    fun externalSchemeNotLaunched() = runBlocking<Unit> {
        fun topResumed() = DeviceSupport.sh("dumpsys activity activities").lines().map { it.trim() }
            .firstOrNull { it.startsWith("topResumedActivity") || it.startsWith("mTopResumedActivity") }
        val before = topResumed()
        assertNotNull(before)
        val s = screen("A")
        s.navigate(fixture("external.html"), newTab = false)
        val snap = s.snapshot()
        s.tapRef(snap.elements.first { it.resourceId == "#intent" }.ref, snap.snapshotId)
        val link = awaitEvent<PoolEvent.ExternalLink>()
        assertEquals("com.example.app", link.link.packageName)
        val snap2 = s.snapshot()
        s.tapRef(snap2.elements.first { it.resourceId == "#script" }.ref, snap2.snapshotId)
        awaitEvent<PoolEvent.ExternalLink> { it.link.url.startsWith("demoapp://script") }
        assertEquals(before, topResumed())
        assertTrue(current("A")!!.view.let { v -> runBlocking(Dispatchers.Main) { v.url } }!!.endsWith("external.html"))
    }

    @Test
    fun permissionRequestsDenied() = runBlocking<Unit> {
        val s = screen("A")
        s.navigate(fixture("perm.html"), newTab = false)
        val tab = current("A")!!
        val geo = DeviceSupport.pollUntil(5_000) { runBlocking { js(tab, "window.geo") }.takeIf { it != "\"pending\"" } }
        val cam = DeviceSupport.pollUntil(5_000) { runBlocking { js(tab, "window.cam") }.takeIf { it != "\"pending\"" } }
        assertEquals("\"denied\"", geo)
        assertTrue(cam, cam == "\"NotAllowedError\"" || cam == "\"unavailable\"")
    }

    @Test
    fun rendererCrashDoesNotKillApp() = runBlocking<Unit> {
        val s = screen("A")
        val crashedId = s.navigate(fixture("host.html"), newTab = false).tab.tabId
        val crashing = current("A")!!
        withContext(Dispatchers.Main) { crashing.view.loadUrl("chrome://crash") }
        assertEquals(crashedId, awaitEvent<PoolEvent.Crashed>().tabId)
        assertTrue(s.tabs().none { it.tabId == crashedId })
        assertTrue(s.tapXY(10, 10) is ActionResult.Failed)
        val again = s.navigate(fixture("host.html"), newTab = false)
        assertTrue(again.loaded)
        assertTrue(again.tab.tabId != crashedId)
    }

    @Test
    fun closeAllAtTaskEndKeepsCookies() = runBlocking<Unit> {
        val s = screen("A")
        s.navigate(fixture("cookie.html"), newTab = false)
        withContext(Dispatchers.Main) { pool.closeAll("A") }
        assertTrue(s.tabs().isEmpty())
        s.navigate(fixture("host.html"), newTab = false)
        assertTrue(js(current("A")!!, "document.cookie").contains("mika_test=1"))
    }

    @Test
    fun trimMemoryClosesBackgroundTabsAndReloadsOnSwitch() = runBlocking<Unit> {
        val s = screen("A")
        val first = s.navigate(fixture("host.html"), newTab = false).tab.tabId
        s.navigate(fixture("elements.html"), newTab = true)
        withContext(Dispatchers.Main) { pool.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) }
        assertFalse(runBlocking(Dispatchers.Main) { pool.isLive(first) })
        assertEquals(2, s.tabs().size)
        assertNotNull(s.switchTab(first))
        assertEquals(first, awaitEvent<PoolEvent.Reloaded>().tabId)
        DeviceSupport.pollUntil(5_000) { current("A")?.view?.let { v -> runBlocking(Dispatchers.Main) { v.url } }?.takeIf { it.endsWith("host.html") } }
    }

    @Test
    fun popupStaysOnItsSiteForItsWholeLife() = runBlocking<Unit> {
        val s = screen("A")
        s.navigate(fixture("popup.html"), newTab = false)
        val snap = s.snapshot()
        s.tapRef(snap.elements.first { it.resourceId == "#one" }.ref, snap.snapshotId)
        val popup = awaitEvent<PoolEvent.NewTab> { it.fromPopup }
        DeviceSupport.pollUntil(5_000) { current("A")?.view?.let { v -> runBlocking(Dispatchers.Main) { v.url } }?.takeIf { it.endsWith("host.html") } }
        val tab = current("A")!!
        // 另一个可注册域名(androidplatform.net 下的子域彼此同站)
        js(tab, "location.href = 'https://fixtures.mika-other.test/assets/web-fixtures/host.html'")
        delay(1_500)
        assertTrue(runBlocking(Dispatchers.Main) { tab.view.url }!!.startsWith("https://appassets.androidplatform.net/"))
        assertTrue(s.tabs().any { it.tabId == popup.tabId })
    }

    @Test
    fun visitsAreRegisteredAndSiteDataCleared() = runBlocking<Unit> {
        val registry = SiteRegistry(java.io.File(ctx.cacheDir, "pool-sites-${System.nanoTime()}"))
        lateinit var p: WebTabPool
        instr.runOnMainSync {
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(instr.context)).build()
            p = WebTabPool(ctx, host, assetLoaders = listOf(loader), registry = registry)
        }
        try {
            val s = WebScreen(p, "A", host, SystemClock::uptimeMillis)
            s.navigate(fixture("cookie.html"), newTab = false)
            assertEquals(listOf("appassets.androidplatform.net"), registry.list().map { it.host })
            var cleared = 0
            instr.runOnMainSync { cleared = p.clearSiteData("appassets.androidplatform.net") }
            assertTrue("cleared=$cleared", cleared >= 1)
            assertTrue(registry.list().isEmpty())
            val left = js(p.current("A")!!, "document.cookie")
            assertFalse(left, left.contains("mika_test"))
        } finally {
            instr.runOnMainSync { p.close() }
        }
    }

    @Test
    fun nonWebAddressesRefused() = runBlocking<Unit> {
        val s = screen("A")
        s.navigate(fixture("host.html"), newTab = false)
        val tab = current("A")!!
        assertFalse(tab.load("file:///sdcard/x.html"))
        assertFalse(tab.load("javascript:alert(1)"))
        assertTrue(runBlocking(Dispatchers.Main) { tab.view.url }!!.endsWith("host.html"))
    }

    @Test
    fun lockedTabIsLeftAloneWhileHandedOver() = runBlocking<Unit> {
        val s = screen("A")
        val id = s.navigate(fixture("host.html"), newTab = false).tab.tabId
        withContext(Dispatchers.Main) { pool.lock(id) }
        assertEquals(ActionResult.Failed("handed_over"), s.tapXY(10, 10))
        assertFalse(s.closeTab(id))
        withContext(Dispatchers.Main) {
            pool.closeAll("A")
            pool.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        }
        assertTrue(s.tabs().any { it.tabId == id })
        assertTrue(runBlocking(Dispatchers.Main) { pool.isLive(id) })
        withContext(Dispatchers.Main) {
            pool.unlock(id)
            pool.closeAll("A")
        }
        assertTrue(s.tabs().isEmpty())
    }

    /** 两个任务各一个标签池,共用一份全局上限:合起来不超过上限,关的是最久没用的那个(哪怕在别的任务里)。 */
    @Test
    fun globalCapIsSharedAcrossRuns() = runBlocking<Unit> {
        val budget = TabBudget(3)
        lateinit var a: WebTabPool
        lateinit var b: WebTabPool
        instr.runOnMainSync {
            a = WebTabPool(ctx, host, budget = budget)
            b = WebTabPool(ctx, host, budget = budget)
        }
        try {
            val first = withContext(Dispatchers.Main) { a.open("A", null).id }
            withContext(Dispatchers.Main) { a.open("A", null) }
            withContext(Dispatchers.Main) { b.open("B", null) }
            assertEquals(3, budget.total())
            // 满了:先关本任务(B)自己最久没用的;B 只有一个,换下的是它,A 的两个都留着(makeRoom 的顺序)
            withContext(Dispatchers.Main) { b.open("B", null) }
            assertEquals(3, budget.total())
            assertTrue(runBlocking(Dispatchers.Main) { a.isLive(first) })
            assertEquals(1, runBlocking(Dispatchers.Main) { b.list("B").size })
            assertEquals(2, runBlocking(Dispatchers.Main) { a.list("A").size })
            // 本任务没有可关的(新任务 C 的第一个标签):才关别处最久没用的,即 A 的第一个
            lateinit var c: WebTabPool
            instr.runOnMainSync { c = WebTabPool(ctx, host, budget = budget) }
            try {
                withContext(Dispatchers.Main) { c.open("C", null) }
                assertEquals(3, budget.total())
                assertFalse(runBlocking(Dispatchers.Main) { a.isLive(first) })
            } finally {
                instr.runOnMainSync { c.close() }
            }
        } finally {
            instr.runOnMainSync {
                a.close()
                b.close()
            }
        }
    }

    @Test
    fun tabsIsolatedByTask() = runBlocking<Unit> {
        val a = screen("A")
        val b = screen("B")
        a.navigate(fixture("host.html"), newTab = false)
        val bTab = b.navigate(fixture("elements.html"), newTab = false).tab.tabId
        assertNull(a.switchTab(bTab))
        assertFalse(a.closeTab(bTab))
        assertFalse(runBlocking(Dispatchers.Main) { pool.close("A", bTab) })
        assertEquals(1, b.tabs().size)
        assertTrue(a.tabs().none { it.tabId == bTab })
        withContext(Dispatchers.Main) { pool.closeAll("A") }
        assertEquals(1, b.tabs().size)
    }
}
