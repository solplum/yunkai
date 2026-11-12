package com.wangzan101.mika.cap.web

import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.cap.web.DownloadInfo
import com.wangzan101.mika.core.cap.web.EvalJs
import com.wangzan101.mika.core.cap.web.NavResult
import com.wangzan101.mika.core.cap.web.PageText
import com.wangzan101.mika.core.cap.web.TabInfo
import com.wangzan101.mika.core.cap.web.WebPort
import com.wangzan101.mika.core.cap.web.WebTabCrashed
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LazyWebPortTest {
    private class Fake : WebPort {
        val calls = mutableListOf<String>()
        override suspend fun snapshot() = ScreenSnapshot("wt1-1", "a.example.com", emptyList(), 100, 200).also { calls += "snapshot" }
        override suspend fun screenshotPng(): ByteArray? = null
        override suspend fun tapRef(ref: String, snapshotId: String) = ActionResult.Ok(changed = true).also { calls += "tap" }
        override suspend fun tapXY(x: Int, y: Int) = ActionResult.Ok(changed = true)
        override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = ActionResult.Ok()
        override suspend fun clearText(ref: String?, snapshotId: String?) = ActionResult.Ok()
        override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = ActionResult.Ok()
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = ActionResult.Ok()
        override suspend fun back() = ActionResult.Ok()
        override suspend fun pressKey(keyCode: Int) = ActionResult.Ok()
        override suspend fun openApp(packageName: String, cold: Boolean) = ActionResult.Failed("unsupported")
        override suspend fun waitStable(timeoutMs: Long) = true
        override suspend fun listApps(): List<Pair<String, String>> = emptyList()
        override suspend fun navigate(url: String, newTab: Boolean): NavResult {
            calls += "navigate $url"
            return NavResult(TabInfo("t1", url, "", true), true, 200, newTab, null)
        }
        override suspend fun readPage(maxChars: Int, offset: Int, scrollLazy: Boolean) = PageText("u", "t", "正文", emptyList(), emptyList(), false, null)
        override suspend fun evaluate(script: String, timeoutMs: Long, args: kotlinx.serialization.json.JsonObject?): EvalJs = EvalJs.Ok("1", false)
        override suspend fun tabs() = listOf(TabInfo("t1", "u", "t", true))
        override suspend fun switchTab(tabId: String): TabInfo? = null
        override suspend fun closeTab(tabId: String) = true
        override suspend fun downloads(): List<DownloadInfo> = emptyList()
        override suspend fun upload(ref: String, snapshotId: String, paths: List<String>) = ActionResult.Ok()
        override suspend fun historyBack() = ActionResult.Ok()
    }

    private var made = 0
    private val fake = Fake()
    private val lazy = LazyWebPort { made++; fake }

    @Test
    fun nothingIsBuiltUntilTheFirstNavigation() = runTest {
        // 手机任务也会碰到的读法:不建
        assertTrue(lazy.tabs().isEmpty())
        // 下载还没接入:不论建没建都说不可用
        assertTrue(runCatching { lazy.downloads() }.exceptionOrNull() is UnsupportedOperationException)
        assertEquals("", lazy.readPage(100, 0, true).text)
        assertEquals("no_tab_open", (lazy.evaluate("1", 5_000) as EvalJs.Failed).error)
        assertEquals(ActionResult.Failed("no_tab_open"), lazy.tapXY(1, 1))
        assertFalse(lazy.snapshot().hasWindow)
        assertNull(lazy.switchTab("t1"))
        assertFalse(lazy.closeTab("t1"))
        assertEquals(0, made)
        assertFalse(lazy.created)
        lazy.navigate("https://a.example.com/", false)
        assertEquals(1, made)
        assertTrue(lazy.created)
        // 建好之后全部转给它
        assertEquals("a.example.com", lazy.snapshot().packageName)
        assertEquals(1, lazy.tabs().size)
        assertEquals(listOf("navigate https://a.example.com/", "snapshot"), fake.calls)
    }

    @Test
    fun builtOnceEvenWhenNavigationsRace() = runTest {
        (1..5).map { async { lazy.navigate("https://a.example.com/$it", false) } }.awaitAll()
        assertEquals(1, made)
    }

    @Test
    fun failedBuildReportsAsCrashedTabAndCanRetry() = runTest {
        var fail = true
        val flaky = LazyWebPort { made++; if (fail) error("no WebView") else fake }
        val r = runCatching { flaky.navigate("https://a.example.com/", false) }
        assertTrue(r.exceptionOrNull() is WebTabCrashed)
        assertFalse(flaky.created)
        fail = false
        flaky.navigate("https://a.example.com/", false)
        assertTrue(flaky.created)
        assertEquals(2, made)
    }

    @Test
    fun interactionsWaitForTheFirstNavigation() = runTest {
        val combo = com.wangzan101.mika.core.uistate.CdpKeys.parseCombo("Escape")!!
        val at = com.wangzan101.mika.core.cap.web.WebPoint.Xy(1, 1)
        assertEquals("no_tab_open", (lazy.hover(at) as ActionResult.Failed).reason)
        assertEquals("no_tab_open", (lazy.pressCombo(combo) as ActionResult.Failed).reason)
        assertEquals(0, made)
        lazy.navigate("https://a.example.com/", false)
        // 建好后交给端口;端口没实现的按默认报 devtools_unavailable
        assertEquals("devtools_unavailable", (lazy.hover(at) as ActionResult.Failed).reason)
        assertEquals("devtools_unavailable", (lazy.drag(at, at, 3) as ActionResult.Failed).reason)
        assertEquals("devtools_unavailable", (lazy.selectOption("@e1", "wt1-1", "x") as ActionResult.Failed).reason)
        assertEquals("devtools_unavailable", (lazy.pressCombo(combo) as ActionResult.Failed).reason)
    }
}
