package com.wangzan101.mika.core.cap.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 读脚本前置脚本的只读约束,真跑一遍:在 Node 里用一套假 DOM 装上 [ReadScriptProtocol.PRELUDE],
 * 看改页面、点、跳转、任何网络请求、读秘密、改内置对象都被拦下,读照常。机器上没有 Node 时跳过。
 */
class ReadScriptEnforcementTest {
    companion object {
        private val cases = linkedMapOf<String, Pair<String, JsonObject?>>()
        private lateinit var cache: JsonObject

        private fun case(name: String, code: String, args: JsonObject? = null) { cases[name] = code to args }

        private fun node(): String? = listOf("node", "/opt/homebrew/bin/node", "/usr/local/bin/node", "/usr/bin/node").firstOrNull { n ->
            runCatching { ProcessBuilder(n, "--version").redirectErrorStream(true).start().let { it.waitFor(10, TimeUnit.SECONDS) && it.exitValue() == 0 } }.getOrDefault(false)
        }

        @BeforeClass @JvmStatic fun runAll() {
            case("count", "document.querySelectorAll('li').length")
            case("texts", "[...document.querySelectorAll('li')].map(e => e.textContent)")
            case("attrs", "const out = []; document.querySelectorAll('li').forEach(e => out.push(e.getAttribute('data-id'))); return out;")
            case("title", "document.title")
            case("setText", "document.querySelector('li').textContent = 'x'; return 1;")
            case("setTitle", "document.title = 'x'")
            case("append", "document.body.appendChild(document.querySelector('li'))")
            case("create", "document.createElement('div')")
            case("click", "document.querySelector('li').click()")
            case("setAttr", "document.querySelector('li').setAttribute('a', 'b')")
            case("callbackSet", "document.querySelectorAll('li').forEach(e => e.setAttribute('a', 'b')); return 1;")
            case("viaCall", "const li = document.querySelector('li'); return li.setAttribute.call(li, 'a', 'b');")
            case("viaReflect", "const li = document.querySelector('li'); return Reflect.apply(li.setAttribute, li, ['a', 'b']);")
            case("viaProto", "const p = Object.getPrototypeOf(document.querySelector('li')); return p === null;")
            case("defineProp", "Object.defineProperty(document.querySelector('li'), 'x', { value: 1 })")
            case("setValue", "document.querySelector('input').value = 'x'")
            case("navigateHref", "location.href = 'https://evil.test/'")
            case("navigateAssign", "location.assign('https://evil.test/')")
            case("openWindow", "window.open('https://evil.test/')")
            case("cookie", "document.cookie")
            case("password", "document.querySelector('input').value")
            case("hiddenAttr", "document.querySelectorAll('input')[1].getAttribute('value')")
            case("visibleValue", "document.querySelectorAll('input')[2].value")
            case("formHtml", "document.querySelector('form').outerHTML")
            case("byValue", "document.querySelector('input[value^=h]')")
            case("post", "await fetch('/api', { method: 'POST' })")
            case("body", "await fetch('/api', { body: 'x' })")
            case("crossOrigin", "await fetch('https://evil.test/api')")
            case("get", "const r = await fetch('/api/items?page=2'); return { status: r.status, url: r.url, data: await r.json() };")
            case("plainFetch", "fetch('/api/items')")
            case("xhr", "const x = new window.XMLHttpRequest(); x.open('GET', '/api'); x.send(); return 1;")
            case("beacon", "window.navigator.sendBeacon('/log', 'x')")
            case("image", "new window.Image()")
            case("socket", "new window.WebSocket('wss://shop.test/s')")
            case("events", "new window.EventSource('/stream')")
            case("readNavigator", "window.navigator.userAgent")
            case("stateSet", "state.n = 41; return 1;")
            case("stateGet", "state.n + 1")
            case("args", "args.q + '!'", buildJsonObject { put("q", "shoes") })
            case("argsFrozen", "args.q = 'x'; return args.q;", buildJsonObject { put("q", "shoes") })
            case("hiddenGlobals", "[typeof process, typeof globalThis, typeof self, typeof setTimeout]")
            case("stringCode", "(() => {}).constructor('return 1')()")
            case("patchBuiltin", "WeakMap.prototype.get = () => 1; return 1;")
            case("big", "'x'.repeat(100)")
            case("syntax", "return (")
            case("errorLine", "const a = 1;\nnull.x;")
        }

        private fun results(): JsonObject {
            if (::cache.isInitialized) return cache
            val n = node()
            assumeTrue("no node on this machine", n != null)
            val dir = kotlin.io.path.createTempDirectory("read-script").toFile()
            val prelude = File(dir, "prelude.js").apply { writeText(ReadScriptProtocol.PRELUDE) }
            val list = File(dir, "cases.json").apply {
                writeText(buildJsonArray {
                    for ((name, c) in cases) add(buildJsonObject {
                        put("name", name); put("code", c.first); c.second?.let { put("args", it) }
                        if (name == "big") put("resultChars", 10)
                    })
                }.toString())
            }
            val harness = File(dir, "harness.js").apply { writeText(ReadScriptEnforcementTest::class.java.getResource("/read-script/harness.js")!!.readText()) }
            val p = ProcessBuilder(n, harness.path, prelude.path, list.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertTrue("node timed out", p.waitFor(60, TimeUnit.SECONDS))
            cache = runCatching { Json.parseToJsonElement(out).jsonObject }.getOrElse { error("harness output: $out") }
            dir.deleteRecursively()
            return cache
        }
    }

    private fun r(name: String): JsonObject = results().getValue(name).jsonObject
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun ok(name: String): String { val o = r(name); assertEquals("$name: $o", "true", o.s("ok")); return o.s("json")!! }
    private fun blocked(name: String, code: String) {
        val o = r(name)
        assertEquals("$name: $o", "false", o.s("ok"))
        assertEquals("$name: $o", code, o.s("error"))
        assertEquals("$name changed the page", 0, o.getValue("mutations").jsonPrimitive.int)
        assertEquals("$name clicked", 0, o.getValue("clicks").jsonPrimitive.int)
        assertEquals("$name navigated", 0, o.getValue("navigations").jsonPrimitive.int)
    }

    @Test fun readsTheRealDom() {
        assertEquals("2", ok("count"))
        assertEquals("""["apple","pear"]""", ok("texts"))
        assertEquals("""["1","2"]""", ok("attrs"))
        assertEquals("\"Shop\"", ok("title"))
        assertEquals("true", ok("viaProto"))
        assertEquals("\"shoes\"", ok("visibleValue"))
    }

    @Test fun mutationIsBlocked() {
        for (n in listOf("setText", "setTitle", "append", "create", "click", "setAttr", "callbackSet", "viaCall", "viaReflect", "defineProp", "setValue")) blocked(n, "read_only")
    }

    @Test fun navigationIsBlocked() {
        for (n in listOf("navigateHref", "navigateAssign", "openWindow")) blocked(n, "read_only")
    }

    @Test fun secretsStayHidden() {
        blocked("cookie", "read_only")
        assertEquals("\"***\"", ok("password"))
        assertEquals("\"***\"", ok("hiddenAttr"))
        val html = ok("formHtml")
        assertTrue(html, "hunter2" !in html && "tok-123" !in html && "shoes" in html)
        blocked("byValue", "invalid_selector")
    }

    @Test fun noNetworkAtAll() {
        // 同源 GET 也不行;别的发请求的路子(XHR、sendBeacon、图片、WebSocket、EventSource)一样报 blocked_network,一个请求也没发出去
        for (n in listOf("post", "body", "crossOrigin", "get", "plainFetch", "xhr", "beacon", "image", "socket", "events")) {
            blocked(n, "blocked_network")
            assertTrue(n, r(n).s("message").orEmpty().contains("web_capture"))
            assertEquals(n, 0, (r(n).getValue("requests") as JsonArray).size)
        }
        // 读页面已有的东西照常
        assertEquals("\"test\"", ok("readNavigator"))
    }

    @Test fun stateAndArgs() {
        ok("stateSet")
        assertEquals("42", ok("stateGet"))
        assertEquals("\"shoes!\"", ok("args"))
        assertEquals("script_error", r("argsFrozen").s("error"))
    }

    @Test fun noWayOut() {
        assertEquals("""["undefined","undefined","undefined","undefined"]""", ok("hiddenGlobals"))
        blocked("stringCode", "read_only")
        assertEquals("script_error", r("patchBuiltin").s("error"))
    }

    @Test fun resultsAreCappedAndErrorsLocated() {
        assertEquals("\"xxxxxxxxx", ok("big"))
        assertEquals("true", r("big").s("truncated"))
        assertEquals("script_error", r("syntax").s("error"))
        assertEquals("2", r("errorLine").s("line"))
    }
}
