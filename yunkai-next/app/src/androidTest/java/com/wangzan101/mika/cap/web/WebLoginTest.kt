package com.wangzan101.mika.cap.web

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import kotlin.coroutines.resume

/** 真机测试:退出一个网站只过期它的 Cookie;全部退出清空 Cookie 与网页存储。用 mika 网页配置。 */
@RunWith(AndroidJUnit4::class)
class WebLoginTest {
    @get:Rule val tmp = TemporaryFolder()
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private lateinit var registry: SiteRegistry
    private lateinit var login: WebLogin

    @Before
    fun setUp() {
        registry = SiteRegistry(tmp.root)
        instr.runOnMainSync {
            login = WebLogin(WebTab.cookieManager(), WebTab.webStorage(), registry)
            login.logoutAll()
        }
    }

    @After
    fun tearDown() = instr.runOnMainSync { login.logoutAll() }

    private fun cookies(url: String): String? {
        var c: String? = null
        instr.runOnMainSync { c = WebTab.cookieManager().getCookie(url) }
        return c
    }

    private fun set(url: String, line: String) = instr.runOnMainSync { WebTab.cookieManager().setCookie(url, line) }

    @Test
    fun logoutExpiresCookiesForHost() {
        set("https://shop.example.com/", "sid=1; Path=/")
        set("https://shop.example.com/", "wide=1; Path=/; Domain=.example.com")
        set("https://news.example.org/", "keep=1; Path=/")
        registry.markLoggedIn("shop.example.com")
        registry.visited("news.example.org")
        var expired = 0
        instr.runOnMainSync { expired = login.logout("shop.example.com") }
        assertTrue("expired=$expired", expired >= 2)
        assertTrue(cookies("https://shop.example.com/").isNullOrEmpty())
        assertEquals("keep=1", cookies("https://news.example.org/"))
        assertEquals(listOf("news.example.org"), registry.list().map { it.host })
    }

    @Test
    fun logoutAllClearsCookiesAndStorage() = runBlocking<Unit> {
        val bounds = ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        lateinit var host: WebHost
        lateinit var tab: WebTab
        instr.runOnMainSync {
            host = WebHost(ctx, bounds.width(), bounds.height(), ctx.resources.displayMetrics.densityDpi)
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(instr.context)).build()
            tab = WebTab(ctx, "t1", "task", listOf(loader))
            host.attach(tab)
        }
        try {
            val page = "https://appassets.androidplatform.net/assets/web-fixtures/host.html"
            assertTrue(tab.load(page))
            js(tab, "document.cookie = 'a=1; max-age=3600; path=/; secure'; localStorage.setItem('k', 'v')")
            assertNotNull(cookies(page))
            instr.runOnMainSync { login.logoutAll() }
            assertTrue(cookies(page).isNullOrEmpty())
            // 网页存储的清除是异步的:重新打开页面后读
            val cleared = DeviceSupport.pollUntil(5_000) {
                runBlocking {
                    tab.load(page)
                    js(tab, "localStorage.getItem('k')")
                }.takeIf { it == "null" }
            }
            assertEquals("null", cleared)
            assertTrue(registry.list().isEmpty())
        } finally {
            instr.runOnMainSync {
                tab.destroy()
                host.close()
            }
        }
    }

    @Test
    fun logoutAllAlsoClearsSessionCookies() {
        set("https://shop.example.com/", "session=1; Path=/")
        assertEquals("session=1", cookies("https://shop.example.com/"))
        instr.runOnMainSync { WebSites.logoutAll(ctx) }
        assertTrue(cookies("https://shop.example.com/").isNullOrEmpty())
    }

    @Test
    fun registryOrderShownInSettings() {
        registry.visited("a.example.com")
        registry.markLoggedIn("b.example.com")
        assertFalse(registry.list().first().host == "a.example.com")
    }

    private suspend fun js(tab: WebTab, script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c -> tab.view.evaluateJavascript(script) { c.resume(it) } }
    }
}
