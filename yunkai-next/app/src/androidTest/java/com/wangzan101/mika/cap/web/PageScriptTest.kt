package com.wangzan101.mika.cap.web

import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume
import kotlin.math.abs

/** 真机测试:页面脚本读元素树、读页、核对输入。夹具经 WebViewAssetLoader 从测试包提供,第二个主机名用来造跨源框架。 */
@RunWith(AndroidJUnit4::class)
class PageScriptTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private lateinit var host: WebHost
    private lateinit var tab: WebTab
    private lateinit var page: PageScript

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
        page = PageScript(tab)
    }

    @After
    fun tearDown() {
        instr.runOnMainSync {
            tab.destroy()
            host.close()
        }
    }

    private fun open(name: String) = runBlocking {
        assertTrue("load $name", tab.load(fixture(name)))
        page.install()
    }

    private suspend fun js(script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c -> tab.view.evaluateJavascript(script) { c.resume(it) } }
    }

    private suspend fun tree(): JsonObject = Json.parseToJsonElement(page.tree()).jsonObject
    private fun JsonObject.els() = this["els"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content
    private suspend fun byId(id: String) = tree().els().firstOrNull { it.str("id") == id }
    private suspend fun refOf(id: String): String {
        val els = tree().els()
        val i = els.indexOfFirst { it.str("id") == id }
        assertTrue("$id not in tree", i >= 0)
        return "@e${i + 1}"
    }

    @Test
    fun rolesAndAccessibleNames() = runBlocking<Unit> {
        open("elements.html")
        val expected = mapOf(
            "b1" to ("button" to "外部标签"),
            "b2" to ("button" to "区域标签"),
            "i3" to ("textbox" to "关联标签"),
            "img4" to ("image" to "替代文字"),
            "b5" to ("button" to "提示标题"),
            "i6" to ("textbox" to "占位文字"),
            "b7" to ("button" to "按钮文字"),
            "a8" to ("link" to "链接文字"),
            "s9" to ("combobox" to "选项一"),
            "c10" to ("checkbox" to "勾选"),
            "h11" to ("heading" to "三级标题"),
            "d12" to ("button" to "可点区域"),
            "ce13" to ("textbox" to "编辑区：已有内容"),
            "file14" to ("file" to "选择文件"),
        )
        val els = tree().els().associateBy { it.str("id") }
        for ((id, rn) in expected) {
            val e = els[id] ?: throw AssertionError("$id missing")
            assertEquals("$id role", rn.first, e.str("r"))
            assertEquals("$id name", rn.second, e.str("n"))
        }
        assertEquals("1", els["a8"]!!.str("c"))
        assertEquals("1", els["i3"]!!.str("e"))
        assertEquals("0", els["h11"]!!.str("c"))
    }

    @Test
    fun focusAndPlaceholderCarried() = runBlocking<Unit> {
        open("elements.html")
        js("document.getElementById('i6').focus()")
        val els = tree().els().associateBy { it.str("id") }
        assertEquals("1", els["i6"]!!.str("f"))
        assertNull(els["b7"]!!["f"])
        assertEquals("占位文字", els["i6"]!!.str("ph"))
        assertNull(els["b7"]!!["ph"])
    }

    @Test
    fun hiddenAndOffscreenExcluded() = runBlocking<Unit> {
        open("elements.html")
        val ids = tree().els().mapNotNull { it.str("id") }.toSet()
        assertTrue("b7" in ids)
        for (h in listOf("h-none", "h-vis", "h-op", "h-op2", "h-zero", "h-off", "h-below")) assertFalse("$h listed", h in ids)
    }

    @Test
    fun coveredElementExcluded() = runBlocking<Unit> {
        open("elements.html")
        assertNull(byId("under"))
        assertTrue(tree().els().any { it.str("n") == "遮罩层" })
    }

    @Test
    fun openShadowRootIncluded() = runBlocking<Unit> {
        open("shadow.html")
        val ids = tree().els().mapNotNull { it.str("id") }
        assertTrue("inshadow" in ids)
        assertFalse("inclosed" in ids)
    }

    @Test
    fun sameOriginFrameOffsetApplied() = runBlocking<Unit> {
        open("frames.html")
        val btn = pollTree { it.els().filter { e -> e.str("id") == "childbtn" }.takeIf { l -> l.isNotEmpty() } }
        assertEquals("only the same-origin child is read", 1, btn.size)
        val b = btn[0]
        assertTrue("x=${b.str("x")}", abs(b["x"]!!.jsonPrimitive.int - 60) <= 1)
        assertTrue("y=${b.str("y")}", abs(b["y"]!!.jsonPrimitive.int - 110) <= 1)
    }

    @Test
    fun crossOriginFrameListedAsFrame() = runBlocking<Unit> {
        open("frames.html")
        val frame = pollTree { it.els().firstOrNull { e -> e.str("r") == "frame" } }
        assertEquals("内嵌页面:fixtures2.androidplatform.net", frame.str("n"))
    }

    @Test
    fun passwordValueNeverExposed() = runBlocking<Unit> {
        open("elements.html")
        val raw = page.tree()
        assertFalse(raw.contains("secret123"))
        assertFalse("role override must not expose the value", raw.contains("hunter22"))
        assertEquals("密码：已填 9 位", byId("pw")!!.str("n"))
        assertEquals(FieldValue.Password(9), page.fieldValue(refOf("pw")))
        js("document.getElementById('pw').focus()")
        assertEquals(FieldValue.Password(9), page.fieldValue(null))
        js("document.getElementById('i6').focus()")
        assertEquals(FieldValue.Text(""), page.fieldValue(null))
        assertEquals(FieldValue.NotAField, page.fieldValue(refOf("b7")))
    }

    @Test
    fun capsAt400AndMarksTruncated() = runBlocking<Unit> {
        open("long.html")
        val t = tree()
        assertEquals(400, t.els().size)
        assertTrue(t["trunc"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun noDomAttributesWritten() = runBlocking<Unit> {
        assertTrue(tab.load(fixture("elements.html")))
        val before = js("document.documentElement.outerHTML")
        page.install()
        page.tree()
        page.centerOf("@e1")
        page.hitTest("@e1")
        page.readPage(12_000, 0)
        assertEquals(before, js("document.documentElement.outerHTML"))
    }

    @Test
    fun readPageMainContentAndHeadings() = runBlocking<Unit> {
        open("elements.html")
        val p = Json.parseToJsonElement(page.readPage(12_000, 0)).jsonObject
        val text = p.str("text")!!
        assertTrue(text, text.lines().contains("# 主标题"))
        assertTrue(text, text.lines().contains("## 小节标题"))
        assertTrue(text, text.contains("正文第一段"))
        assertFalse(text, text.contains("导航链接"))
        assertFalse(text, text.contains("页脚文字"))
        val links = p["links"]!!.jsonArray.map { it.jsonObject }
        assertTrue("$links", links.any { it.str("text") == "正文链接" && it.str("url") == fixture("long.html") })
        val images = p["images"]!!.jsonArray.map { it.jsonObject }
        assertTrue("$images", images.any { it.str("alt") == "正文图片" })
        assertFalse(p["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun readPagePaginates() = runBlocking<Unit> {
        open("long.html")
        val all = StringBuilder()
        var offset = 0
        var pages = 0
        while (true) {
            val p = Json.parseToJsonElement(page.readPage(10_000, offset)).jsonObject
            val text = p.str("text")!!
            all.append(text)
            pages++
            val next = p["nextOffset"]?.jsonPrimitive?.intOrNull ?: break
            assertTrue(p["truncated"]!!.jsonPrimitive.boolean)
            assertEquals(offset + text.length, next)
            offset = next
            assertTrue("too many pages", pages < 50)
        }
        assertTrue(pages > 1)
        val markers = Regex("P-(\\d{4})").findAll(all).map { it.groupValues[1].toInt() }.toList()
        assertEquals((1..4000).toList(), markers)
    }

    @Test
    fun readPageNoScrollLeavesLazyContentAlone() = runBlocking<Unit> {
        open("long.html")
        js("window.scrollTo(0, 50)")
        val text = Json.parseToJsonElement(page.readPage(30_000, 0, scroll = false)).jsonObject.str("text")!!
        assertFalse(text.contains("LAZY-LOADED-SECTION"))
        assertEquals(0 to 50, page.scrollState(null))
    }

    @Test
    fun readPageTriggersLazyLoadAndRestoresScroll() = runBlocking<Unit> {
        open("long.html")
        js("window.scrollTo(0, 50)")
        assertEquals(0 to 50, page.scrollState(null))
        val text = Json.parseToJsonElement(page.readPage(30_000, 0)).jsonObject.str("text")!!
        assertTrue(text.contains("LAZY-LOADED-SECTION"))
        assertEquals(0 to 50, page.scrollState(null))
    }

    @Test
    fun bridgeNotDiscoverable() = runBlocking<Unit> {
        open("elements.html")
        val n = page.bridgeName
        val exposed = js(
            "Object.getOwnPropertyNames(window).filter(function (k) { try { var v = window[k];" +
                " return v && typeof v === 'object' && typeof v.tree === 'function'; } catch (e) { return false; } }).length"
        )
        assertEquals("0", exposed)
        assertEquals("\"undefined\"", js("typeof window['$n']()"))
        assertEquals("\"undefined\"", js("typeof window['$n']('guess')"))
        assertEquals("false", js("Object.getOwnPropertyDescriptor(window, '$n').configurable"))
        js("try { window['$n'] = null; delete window['$n']; } catch (e) {}")
        assertEquals("\"function\"", js("typeof window['$n']"))
    }

    @Test
    fun documentStartBeatsPagePoisoning() = runBlocking<Unit> {
        page.install()
        assumeTrue("document-start injection not supported", page.documentStart)
        assertTrue(tab.load(fixture("poison.html")))
        val t = tree()
        assertTrue("$t", t.els().any { it.str("id") == "btn" && it.str("r") == "button" })
    }

    @Test
    fun digestGivesPartsDialogsAndDeclaredTimes() = runBlocking<Unit> {
        open("digest.html")
        val raw = page.digest()!!
        val d = com.wangzan101.mika.core.cap.web.PageDigest.parse(raw)
        assertEquals(1, d.dialogs.size)
        assertTrue(d.dialogs[0].joinToString(" ").contains("We use cookies"))
        val r = com.wangzan101.mika.core.cap.web.PageDigest.render(d, unfold = false)
        assertTrue(r.text, r.text.contains("# Rain expected tomorrow"))
        assertTrue(r.text, r.text.contains("<<collapsed"))
        assertFalse(r.text, r.text.contains("Story four"))
        assertTrue(r.links.any { it.second == "https://agency.example.org/report" })
        val (published, updated) = com.wangzan101.mika.core.cap.web.PageDigest.dates(d.signals)
        assertEquals("2026-10-20T08:30:00+08:00", published)
        assertEquals("2026-10-21T09:00:00Z", updated)
        assertTrue(page.contentSample()!!.first > 0)
    }

    @Test
    fun bridgeOnlyInMainFrame() = runBlocking<Unit> {
        page.install()
        assertTrue(tab.load(fixture("frames.html")))
        page.tree()
        delay(300)
        val n = page.bridgeName
        assertEquals("\"function\"", js("typeof window['$n']"))
        assertEquals("\"undefined\"", js("typeof document.getElementById('same').contentWindow['$n']"))
    }

    @Test
    fun revealedPasswordInFrameStaysHidden() = runBlocking<Unit> {
        open("export.html")
        delay(300)
        page.tree()
        js("document.getElementById('pwframe').contentWindow.reveal()")
        assertEquals("\"text\"", js("document.getElementById('pwframe').contentDocument.getElementById('framepw').type"))
        assertFalse(page.tree().contains("frame-secret-7"))
    }

    @Test
    fun autocompletePasswordTreatedAsPassword() = runBlocking<Unit> {
        open("export.html")
        assertFalse(page.tree().contains("pw-secret-5"))
        assertEquals(FieldValue.Password("pw-secret-5".length), page.fieldValue(refOf("pw2")))
    }

    @Test
    fun lateInjectionRotatesBridgePerDocument() = runBlocking<Unit> {
        val late = PageScript(tab, useDocumentStart = false)
        assertTrue(tab.load(fixture("host.html")))
        late.install()
        assertFalse(late.documentStart)
        val first = late.bridgeName
        assertTrue(late.tree().contains("btn"))
        assertTrue(tab.load(fixture("elements.html")))
        assertTrue(late.tree().contains("b7"))
        val second = late.bridgeName
        assertTrue("$first vs $second", first != second)
        assertEquals("\"undefined\"", js("typeof window['$first']"))
    }

    @Test
    fun revealedPasswordStaysHidden() = runBlocking<Unit> {
        open("export.html")
        js("revealPassword()")
        assertEquals("\"text\"", js("document.getElementById('pw').type"))
        val raw = page.tree()
        assertFalse(raw.contains("pw-secret-1"))
        assertEquals(FieldValue.Password("pw-secret-1".length), page.fieldValue(refOf("pw")))
    }

    private suspend fun <T : Any> pollTree(timeoutMs: Long = 5_000, pick: (JsonObject) -> T?): T {
        val end = System.currentTimeMillis() + timeoutMs
        var last: JsonObject
        do {
            last = tree()
            pick(last)?.let { return it }
            delay(200)
        } while (System.currentTimeMillis() < end)
        throw AssertionError("not found in tree: $last")
    }
}
