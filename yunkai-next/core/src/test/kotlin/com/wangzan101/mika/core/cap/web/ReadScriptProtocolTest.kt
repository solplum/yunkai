package com.wangzan101.mika.core.cap.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadScriptProtocolTest {
    private fun o(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private fun request(method: String, url: String, scriptFrame: Boolean = true, redirect: Boolean = false) = o("""
        {"request":{"method":"$method","url":"$url"},
         ${if (redirect) "\"redirectResponse\":{\"status\":302}," else ""}
         "initiator":{"type":"script","stack":{"callFrames":[{"url":"page.js"}],
           "parent":{"callFrames":[{"url":"${if (scriptFrame) ReadScriptProtocol.PRELUDE_URL else "app.js"}"}]}}}}""")

    @Test fun anyRequestFromTheScriptIsAViolation() {
        // 读脚本不碰网络:同源 GET 也不行
        for ((m, u) in listOf("GET" to "https://shop.test/api?x=1", "GET" to "https://shop.test:443/api", "POST" to "https://shop.test/api",
            "GET" to "https://evil.test/api", "PUT" to "https://api.shop.test/x")) {
            val v = ReadScriptProtocol.requestViolation(request(m, u))
            assertNotNull("$m $u", v)
            assertTrue(v!!, v.contains("web_capture"))
        }
        assertNotNull(ReadScriptProtocol.requestViolation(request("GET", "https://cdn.test/x", redirect = true)))
        // 页面自己的请求不管
        assertNull(ReadScriptProtocol.requestViolation(request("POST", "https://evil.test/x", scriptFrame = false)))
        assertNull(ReadScriptProtocol.requestViolation(request("GET", "https://shop.test/x", scriptFrame = false)))
    }

    @Test fun preludeHasNoWayToTheNetwork() {
        val p = ReadScriptProtocol.PRELUDE
        // 原生 fetch 不再留给读取世界;fetch 和能发请求的函数、构造器一律报 blocked_network
        assertFalse(p.contains("G.fetch"))
        assertFalse(p.contains("nativeFetch"))
        assertTrue(p.contains(ReadScriptProtocol.NETWORK_MESSAGE))
        for (n in listOf("fetch", "sendBeacon", "XMLHttpRequest", "WebSocket", "EventSource", "Image")) assertTrue(n, n in ReadScriptProtocol.NETWORK_NAMES)
        assertTrue(p.contains("const pageFetch = () => { live(); offline(); };"))
    }

    @Test fun originIgnoresDefaultPorts() {
        assertEquals("https://a.test", ReadScriptProtocol.originOf("https://A.test:443/x?y"))
        assertEquals("http://a.test:8080", ReadScriptProtocol.originOf("http://a.test:8080/"))
        assertNull(ReadScriptProtocol.originOf("not a url"))
    }

    @Test fun resultsAreRedactedAndCapped() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
        val r = ReadScriptProtocol.result(o("""{"ok":true,"json":"{\"title\":\"Shoes\",\"token\":\"abc123\",\"u\":\"https://shop.test/a/b/c\",\"j\":\"$jwt\"}","truncated":false}"""), fresh = true)
        r as EvalJs.Ok
        assertTrue(r.json, r.json.contains("\"token\":\"***\"") && r.json.contains("Shoes") && r.json.contains("https://shop.test/a/b/c"))
        assertFalse(r.json, r.json.contains(jwt))
        assertTrue(r.fresh)
        val big = "x".repeat(EVAL_RESULT_MAX_CHARS + 50)
        val cut = ReadScriptProtocol.result(o("""{"ok":true,"json":"\"$big\"","truncated":false}"""), fresh = false) as EvalJs.Ok
        assertEquals(EVAL_RESULT_MAX_CHARS, cut.json.length)
        assertTrue(cut.truncated)
    }

    @Test fun failuresKeepTheirCodeAndLine() {
        assertEquals(EvalJs.Failed("read_only", "line 3: read_only: changing the page is not allowed"),
            ReadScriptProtocol.result(o("""{"ok":false,"error":"read_only","message":"read_only: changing the page is not allowed","line":3}"""), false))
        assertEquals("script_error", (ReadScriptProtocol.result(null, false) as EvalJs.Failed).error)
    }

    @Test fun runExpressionCarriesCodeArgsAndLimits() {
        val e = ReadScriptProtocol.run("return \"a\"", o("""{"q":1}"""), 99)
        assertTrue(e, e.startsWith("globalThis.__mikaReadRun(\"return \\\"a\\\"\", {\"q\":1}, {resultChars: 99"))
        assertTrue(e, e.contains("resultChars: 99"))
        assertTrue(ReadScriptProtocol.run("1", null, 5).contains("\"1\", null,"))
    }

    @Test fun preludeNamesTheSourcesTheNetworkCheckLooksFor() {
        assertTrue(ReadScriptProtocol.PRELUDE.trimEnd().endsWith("//# sourceURL=${ReadScriptProtocol.PRELUDE_URL}"))
        assertTrue(ReadScriptProtocol.PRELUDE.contains("//# sourceURL=${ReadScriptProtocol.SOURCE_URL}"))
    }
}
