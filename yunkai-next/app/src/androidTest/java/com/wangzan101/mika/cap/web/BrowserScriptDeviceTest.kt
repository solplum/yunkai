package com.wangzan101.mika.cap.web

import com.wangzan101.mika.runAction
import com.wangzan101.mika.core.guard.OperationGuards
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.os.SystemClock
import android.view.WindowManager
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.action.execution
import com.wangzan101.mika.core.cap.web.cdp.CdpDiscovery
import com.wangzan101.mika.core.cap.web.cdp.CdpSession
import com.wangzan101.mika.core.cap.web.cdp.CdpStream
import com.wangzan101.mika.core.cap.web.cdp.CdpTransport
import com.wangzan101.mika.core.cap.web.cdp.evaluate
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.cap.web.SCRIPT_MAX_ACTIONS
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.cap.web.cdp.WebDevtools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.UUID
import kotlin.coroutines.resume
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.cap.phone.PhoneServices

/**
 * 真机测试:web_script 的整条路径。分发器 → 端口 → 隔离世界里的脚本 → 绑定 → 子步(真实守卫、关卡、记录)→ 真实点击与输入。
 * DevTools 会话在测试里自己连(本进程的调试套接字,按记号找到这个标签的页面);产品里由会话管理提供。
 */
@RunWith(AndroidJUnit4::class)
class BrowserScriptDeviceTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    /** 本测试自己的一份:换会话来源、连法都不影响别的测试。 */
    private val devtools = WebDevtools(PhoneServices.of(InstrumentationRegistry.getInstrumentation().targetContext).hub::openDevtoolsSocket)
    private lateinit var host: WebHost
    private lateinit var pool: WebTabPool
    private lateinit var screen: WebScreen
    private lateinit var tools: ToolDispatcher
    private val sessions = mutableMapOf<String, CdpSession>()
    private val events: MutableList<RunEvent> = Collections.synchronizedList(mutableListOf())
    private val sink = object : RunSink { override fun event(e: RunEvent) { events += e } }
    private var n = 0

    private val transport = CdpTransport { name ->
        val s = LocalSocket().apply { connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT)) }
        object : CdpStream {
            override val input = s.inputStream
            override val output = s.outputStream
            override fun close() = s.close()
        }
    }
    private val endpoint = "webview_devtools_remote_${Process.myPid()}"

    @Before
    fun setUp() {
        val bounds = ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        instr.runOnMainSync {
            WebView.setWebContentsDebuggingEnabled(true)
            host = WebHost(ctx, bounds.width(), bounds.height(), ctx.resources.displayMetrics.densityDpi)
            val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(instr.context)).build()
            pool = WebTabPool(ctx, host, devtools = devtools, assetLoaders = listOf(loader))
        }
        devtools.provider = { tab -> synchronized(sessions) { sessions[tab.id] } ?: runBlocking { connect(tab) } }
        screen = WebScreen(pool, TASK, host, SystemClock::uptimeMillis)
        tools = toolDispatcher(screen, web = screen)
        runBlocking {
            val r = tools.runAction(Part.ToolCall("nav", "web_navigate", """{"url":"https://appassets.androidplatform.net/assets/web-fixtures/script.html"}""")).content
            assertEquals(r.toString(), JsonPrimitive(true), r["ok"])
        }
    }

    @After
    fun tearDown() {
        synchronized(sessions) { sessions.values.forEach { it.close() }; sessions.clear() }
        instr.runOnMainSync {
            pool.close()
            host.close()
        }
    }

    /** 打记号,逐个页面目标求值找到这个标签。 */
    private suspend fun connect(tab: WebTab): CdpSession? {
        val mark = UUID.randomUUID().toString()
        js(tab, "window.__testMark='$mark'")
        for (t in CdpDiscovery.list(transport, endpoint).filter { it.type == "page" }) {
            val url = t.webSocketDebuggerUrl ?: continue
            val s = CdpSession.connect(transport, endpoint, CdpDiscovery.webSocketPath(url))
            val v = runCatching { s.evaluate("window.__testMark", timeoutMs = 3000)["value"]?.jsonPrimitive?.content }.getOrNull()
            if (v == mark) return s.also { synchronized(sessions) { sessions[tab.id] = it } }
            s.close()
        }
        return null
    }

    private suspend fun js(tab: WebTab, script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c -> tab.view.evaluateJavascript(script) { c.resume(it) } }
    }

    private suspend fun page(script: String) = js(pool.current(TASK)!!, script)

    private suspend fun script(code: String, timeoutMs: Int? = null): JsonObject {
        val id = "s${++n}"
        // 和循环里一样经执行入口:脚本里的每个动作作为 Script 来源逐个过守卫、关卡、判定并记录
        val executor = ActionExecutor(tools, OperationGuards(), ConfirmationGate(), gate = {}, recorder = ActionRecorder(sink))
        val extra = timeoutMs?.let { ""","timeoutMs":$it""" }.orEmpty()
        return executor.run(Part.ToolCall(id, "web_script", """{"code":${JsonPrimitive(code)}$extra}"""), Origin.Model(n)).execution().content
    }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test
    fun readsListWithQuery() = runBlocking<Unit> {
        val r = script("""
            const list = await mika.queryAll('li.item', { fields: ['text', 'box'] });
            const count = await mika.query('li.item', { fields: ['count'] });
            const first = await mika.query('li.item');
            const status = await mika.text('#status');
            const none = await mika.query('#nothing-here');
            return { texts: list.items.map((i) => i.text), total: list.total, count, first: first.text, status, none, visible: list.items[0].box.visible };
        """.trimIndent())
        assertEquals(r.toString(), "true", r.s("ok"))
        val v = Json.parseToJsonElement(r.s("result")).jsonObject
        assertEquals(5, v["texts"]!!.jsonArray.size)
        assertEquals("苹果 3 元", v["texts"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("5", v.s("total")); assertEquals("5", v.s("count")); assertEquals("苹果 3 元", v.s("first"))
        assertEquals("clicks=0", v.s("status")); assertEquals("null", v["none"].toString()); assertEquals("true", v.s("visible"))
        // 页面看不到 mika、绑定和脚本的东西
        assertEquals("\"undefined undefined undefined\"", page("typeof window.mika + ' ' + typeof window.__mika + ' ' + typeof window.__mikaRun"))
    }

    @Test
    fun secretFieldValuesReadAsMask() = runBlocking<Unit> {
        val r = script("""
            const q = (s) => mika.query(s, { fields: ['value', 'attrs'] });
            return { pw: await q('#pw'), otp: await q('#otp'), tok: await q('#tok'), name: await q('#name'), form: await mika.text('#f') };
        """.trimIndent())
        val v = Json.parseToJsonElement(r.s("result")).jsonObject
        for (k in listOf("pw", "otp", "tok")) assertEquals(k, "***", v[k]!!.jsonObject.s("value"))
        assertEquals("***", v["tok"]!!.jsonObject["attrs"]!!.jsonObject.s("value"))
        assertTrue(r.s("result"), "hunter2" !in r.s("result") && "654321" !in r.s("result") && "csrf-123" !in r.s("result"))
        // 按 value 属性选元素(一位一位猜)不行
        val guess = script("return await mika.query('input[value^=\"c\"]', { fields: ['count'] })")
        assertEquals(guess.toString(), "script_error", guess.s("error"))
        assertTrue(guess.s("message"), guess.s("message").startsWith("Error: invalid_selector"))
    }

    @Test
    fun pageGlobalsAreUnreachableAndHostileScriptsChangeNothing() = runBlocking<Unit> {
        // 模型代码手里没有页面对象:浏览器全局一概是 undefined;能拿到全局对象的路都封了
        val r = script("""
            const types = {};
            types.document = typeof document; types.window = typeof window; types.self = typeof self; types.globalThis = typeof globalThis;
            types.location = typeof location; types.history = typeof history; types.navigator = typeof navigator; types.fetch = typeof fetch;
            types.XMLHttpRequest = typeof XMLHttpRequest; types.WebSocket = typeof WebSocket; types.localStorage = typeof localStorage;
            types.open = typeof open; types.postMessage = typeof postMessage; types.setTimeout = typeof setTimeout; types.HTMLElement = typeof HTMLElement;
            types.Element = typeof Element; types.CSSStyleSheet = typeof CSSStyleSheet; types.Image = typeof Image; types.FormData = typeof FormData;
            types.console = typeof console; types.frames = typeof frames; types.top = typeof top; types.getComputedStyle = typeof getComputedStyle;
            types.pageSecret = typeof pageSecret; types.thisValue = typeof this;
            const escape = {};
            const tryIt = (k, f) => { try { const v = f(); escape[k] = typeof v === 'object' || typeof v === 'function' ? 'got ' + typeof v : 'ran'; } catch (e) { escape[k] = 'blocked'; } };
            tryIt('fnCtor', () => (function () {}).constructor('return this')());
            tryIt('asyncCtor', () => (async function () {}).constructor('return this'));
            tryIt('genCtor', () => (function* () {}).constructor('return this'));
            tryIt('mikaCtor', () => mika.tap.constructor('return this')());
            tryIt('indirectEval', () => (0, eval)('this'));
            tryIt('stackApi', () => { Error.prepareStackTrace = (e, s) => s; return new Error().stack; });
            tryIt('locationAssign', () => { location.href = '/assets/web-fixtures/host.html'; });
            tryIt('adopted', () => { document.adoptedStyleSheets = []; });
            tryIt('selectIndex', () => { document.getElementById('sel')[0] = null; });
            tryIt('style', () => { document.getElementById('inc').style.color = 'red'; });
            tryIt('click', () => { document.getElementById('inc').click(); });
            return { types, escape };
        """.trimIndent())
        assertEquals(r.toString(), "true", r.s("ok"))
        val v = Json.parseToJsonElement(r.s("result")).jsonObject
        for ((k, t) in v["types"]!!.jsonObject) assertEquals(k, "undefined", t.jsonPrimitive.content)
        for ((k, e) in v["escape"]!!.jsonObject) assertEquals(k, "blocked", e.jsonPrimitive.content)
        // 页面原样
        assertEquals("0", page("window.clicks"))
        assertEquals("\"\"", page("document.getElementById('inc').style.color"))
        assertEquals("2", page("document.getElementById('sel').options.length"))
        assertTrue(page("location.href").contains("script.html"))
        assertEquals("0", page("document.adoptedStyleSheets.length"))
    }

    @Test
    fun dynamicImportIsRefused() = runBlocking<Unit> {
        val r = script("await import('data:text/javascript,window.pwned=1'); return 1")
        assertEquals(r.toString(), "invalid_arguments", r.s("error"))
        assertEquals("null", page("window.pwned === undefined ? null : 1"))
    }

    @Test
    fun tapsTwiceAndTypesThroughMikaActions() = runBlocking<Unit> {
        val r = script("""
            const find = (s, name) => s.elements.find(e => e.name === name);
            let s = await mika.snapshot();
            await mika.tap(find(s, '加一'));
            s = await mika.snapshot();
            await mika.tap(find(s, '加一').ref);
            s = await mika.snapshot();
            const typed = await mika.type(find(s, '名字'), 'Mika');
            mika.log('typed', typed.verification);
            return await mika.text('#status');
        """.trimIndent())
        assertEquals(r.toString(), "true", r.s("ok"))
        assertEquals("\"clicks=2\"", r.s("result"))
        assertEquals("2", page("window.clicks"))
        assertEquals("\"Mika\"", page("document.getElementById('name').value"))
        assertEquals(listOf("snapshot", "phone_tap", "snapshot", "phone_tap", "snapshot", "type", "text"), r["actions"]!!.jsonArray.map { it.jsonObject.s("op") })
        assertTrue(r["logs"]!!.jsonArray[0].jsonPrimitive.content.startsWith("typed"))
        val recorded = events.filterIsInstance<RunEvent.ToolResult>().filter { it.source == "script" }
        assertEquals(6, recorded.size)
        assertTrue(recorded.all { it.callId!!.startsWith("s1.") })
    }

    @Test
    fun actionLimitStopsAtTwenty() = runBlocking<Unit> {
        val r = script("""
            for (let i = 0; i < 25; i++) {
              const s = await mika.snapshot();
              await mika.tap(s.elements.find(e => e.name === '加一'));
            }
        """.trimIndent(), timeoutMs = 60_000)
        assertEquals(r.toString(), "limit_exceeded", r.s("error"))
        assertEquals("$SCRIPT_MAX_ACTIONS", page("window.clicks"))
    }

    // 「提交订单」要花钱:询问时脚本里不做
    @Test
    fun irreversibleTapIsNotRun() = runBlocking<Unit> {
        val r = script("""
            const s = await mika.snapshot();
            await mika.tap(s.elements.find(e => e.name === '提交订单'));
            return 'after';
        """.trimIndent())
        assertEquals(r.toString(), "confirmation_required", r.s("error"))
        assertEquals("0", page("window.sends"))
    }

    @Test
    fun scriptErrorReportsLine() = runBlocking<Unit> {
        val r = script("const a = 1;\nnull.x;")
        assertEquals(r.toString(), "script_error", r.s("error"))
        assertEquals("2", r.s("line"))
    }

    @Test
    fun busyLoopTimesOutAndPageRecovers() = runBlocking<Unit> {
        val r = script("while (true) {}", timeoutMs = 2000)
        assertEquals(r.toString(), "timeout", r.s("error"))
        assertEquals("42", page("window.pageSecret"))
    }

    /** 读脚本(web_evaluate_js):真实 DOM 读得到,改不了、点不了、发不了 POST,秘密读出来是 ***,state 跨调用留着。 */
    private suspend fun read(code: String): JsonObject =
        tools.runAction(Part.ToolCall("r${++n}", "web_evaluate_js", """{"script":${JsonPrimitive(code)}}""")).content

    private fun JsonObject.result() = s("result").substringAfter('\n').substringBeforeLast('\n')

    @Test
    fun readScriptReadsButCannotChangeThePage() = runBlocking<Unit> {
        val nav = tools.runAction(Part.ToolCall("nav2", "web_navigate", """{"url":"https://appassets.androidplatform.net/assets/web-fixtures/export.html"}""")).content
        assertEquals(nav.toString(), JsonPrimitive(true), nav["ok"])
        val title = read("document.title")
        assertEquals(title.toString(), "new", title.s("state"))
        assertTrue(title.toString(), title.result().contains("\"export\""))
        assertTrue(read("document.querySelector('#pw').value").result().contains("\"***\""))
        assertTrue(read("document.querySelector('[name=csrf]').getAttribute('value')").result().contains("\"***\""))
        val before = page("document.body.innerHTML.length")
        for (code in listOf("document.body.appendChild(document.querySelector('#pw'))", "document.title = 'x'", "document.querySelector('#pw').focus()", "location.href = 'https://example.com/'")) {
            assertEquals(code, "read_only", read(code).s("error"))
        }
        assertEquals(before, page("document.body.innerHTML.length"))
        assertEquals("\"export\"", page("document.title"))
        // 读脚本不碰网络:同源 GET 也不行,别的发请求的路子也一样
        for (code in listOf("await fetch(location.href)", "await fetch(location.href, { method: 'POST' })", "await fetch('https://example.com/')",
            "window.navigator.sendBeacon(location.href, 'x')", "new window.XMLHttpRequest()", "new window.Image()",
            "new window.WebSocket('wss://example.com/')", "new window.EventSource(location.href)")) {
            val r = read(code)
            assertEquals("$code $r", "blocked_network", r.s("error"))
            assertTrue(r.toString(), r.toString().contains("web_capture"))
        }
        // 建元素、改 src 本来就是改页面
        assertEquals("read_only", read("document.createElement('img')").s("error"))
        read("state.n = 41; return 1;")
        assertTrue(read("state.n + 1").result().contains("42"))
        // 「显示密码」改成 text 之后也还是秘密
        page("revealPassword()")
        assertTrue(read("document.querySelector('#pw').value").result().contains("\"***\""))
    }

    private companion object {
        const val TASK = "task-script"
    }
}
