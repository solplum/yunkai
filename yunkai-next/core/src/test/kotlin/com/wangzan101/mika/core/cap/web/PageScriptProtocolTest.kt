package com.wangzan101.mika.core.cap.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageScriptProtocolTest {
    private fun details(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test fun parsesCalls() {
        val c = PageScriptProtocol.parseCall("""{"id":3,"op":"tap","args":{"ref":"@e2"}}""")!!
        assertEquals(3, c.id); assertEquals("tap", c.op); assertEquals("\"@e2\"", c.args["ref"].toString())
        // 没有 args 当空对象;log 的 id 为 0
        assertEquals(JsonObject(emptyMap()), PageScriptProtocol.parseCall("""{"id":0,"op":"log"}""")!!.args)
    }

    @Test fun rejectsMalformedCalls() {
        for (bad in listOf("", "nope", "[]", """{"op":"tap"}""", """{"id":"1","op":"tap"}""", """{"id":-1,"op":"tap"}""", """{"id":1,"op":5}""")) {
            assertNull(bad, PageScriptProtocol.parseCall(bad))
        }
    }

    @Test fun settleEmbedsResultAsLiteral() {
        val r = buildJsonObject { put("ok", true); put("value", "a\"b</script>\n") }
        val e = PageScriptProtocol.settle(7, r)
        assertTrue(e.startsWith("globalThis.__mikaSettle && globalThis.__mikaSettle(7, {"))
        // JSON 的转义保证字符串不会把表达式截断
        assertTrue("\\\"b" in e && "\\n" in e)
    }

    @Test fun runPassesCodeAsDataNotSource() {
        // 模型代码作为字符串交给事先存下的 AsyncFunction 编译,不拼进表达式
        assertEquals("globalThis.__mikaRun(\"return '1\\\"'\")", PageScriptProtocol.run("return '1\"'"))
    }

    @Test fun errorLineFromStack() {
        // 编译出来的源码:函数头、") {"、'use strict';、里层箭头函数各一行,模型代码从第 5 行起
        val (msg, line) = PageScriptProtocol.error(details("""{"text":"Uncaught (in promise)","lineNumber":0,
            "exception":{"description":"TypeError: x\n    at eval (mika-script.js:7:9)\n    at mika-prelude.js:80:1"}}"""))
        assertEquals("TypeError: x", msg); assertEquals(3, line)
    }

    @Test fun errorWithoutScriptFrameHasNoLine() {
        val (msg, line) = PageScriptProtocol.error(details("""{"text":"Uncaught","lineNumber":2,
            "exception":{"description":"SyntaxError: Unexpected token '}'\n    at mika-prelude.js:80:16"}}"""))
        assertEquals("SyntaxError: Unexpected token '}'", msg); assertNull(line)
        val (m2, l2) = PageScriptProtocol.error(details("""{"text":"Uncaught 5","lineNumber":0}"""))
        assertEquals("Uncaught 5", m2); assertNull(l2)
    }

    @Test fun onlyPlainJavaScriptGlobalsAreAllowed() {
        // 放行的只有 ECMAScript 本身;没有一个是浏览器全局,也没有能把字符串当代码跑的
        val allowed = PageScriptProtocol.ALLOWED_GLOBALS
        for (g in listOf("document", "window", "self", "globalThis", "location", "fetch", "XMLHttpRequest", "setTimeout", "navigator", "history",
            "localStorage", "frames", "top", "parent", "eval", "Function", "console", "HTMLElement", "Element", "Node", "Event", "CSSStyleSheet",
            "Image", "Option", "WebSocket", "postMessage", "open", "structuredClone", "queueMicrotask", "WebAssembly")) {
            assertFalse(g, g in allowed)
        }
        for (g in listOf("JSON", "Math", "Array", "Object", "Promise", "Map", "Date", "RegExp", "Number", "String")) assertTrue(g, g in allowed)
    }

    @Test fun preludeShadowsEveryGlobalAndClosesStringEval() {
        val p = PageScriptProtocol.PRELUDE
        // 存下 AsyncFunction 之后才封构造器;遮住的名字取自全局对象和它整条原型链
        assertTrue(p.indexOf("const AsyncFunction =") < p.indexOf("'constructor', unavailable"))
        assertTrue("for (let o = G; o; o = O.getPrototypeOf(o)) for (const k of O.getOwnPropertyNames(o))" in p)
        for (k in listOf("fixed(G, 'eval', unavailable)", "fixed(G, 'Function', unavailable)", "fixed(G.Error, 'prepareStackTrace', undefined)")) assertTrue(k, k in p)
        // 严格模式、this 为 undefined、遮住的名字都传 undefined,最后一个参数才是 mika;返回值只交 JSON 字符串
        assertTrue("new AsyncFunction(...shadow, 'mika', \"'use strict';\\nreturn (async () => {\\n\" + code" in p)
        assertTrue("apply(fn, undefined, [...blanks, mika])" in p)
        assertTrue("stringify(r)" in p)
        // 前置脚本自己不碰 DOM:页面对象一个都没有经它交到模型代码手里
        for (dom in listOf("document.", "querySelector", "innerText", "HTMLElement")) assertFalse(dom, dom in p)
        assertTrue(p.trimEnd().endsWith("//# sourceURL=${PageScriptProtocol.PRELUDE_URL}"))
    }

    @Test fun preludeSendsCallsThroughTheOutbox() {
        val p = PageScriptProtocol.PRELUDE
        // 不靠 DevTools 绑定(那要打开 Runtime):调用放进发件箱,由 __mikaTake 交出,名字在模型代码里同样被遮住
        assertTrue("fixed(G, '__mikaTake'" in p)
        assertTrue(p.indexOf("fixed(G, '__mikaTake'") < p.indexOf("const shadow"))
        assertTrue(PageScriptProtocol.TAKE.startsWith("globalThis.__mikaTake("))
        for (op in listOf("snapshot", "read", "query", "queryAll", "text", "tap", "type", "scroll", "navigate", "waitText")) assertTrue(op, "call('$op'" in p)
    }

    @Test fun dynamicImportIsRejected() {
        assertNotNull(PageScriptProtocol.codeProblem("await import('data:text/javascript,1')"))
        assertNotNull(PageScriptProtocol.codeProblem("const m = import ( u )"))
        assertNotNull(PageScriptProtocol.codeProblem("import.meta"))
        assertNull(PageScriptProtocol.codeProblem("const important = 1; return important"))
    }

    @Test fun readerMasksSecretsWithThePageRule() {
        val r = PageScriptProtocol.READER
        // 和 page.js 同一条规则(那边的清单见 app 的 PageScriptSecretRuleTest)
        assertEquals(listOf("password", "hidden"), PageScriptProtocol.SECRET_INPUT_TYPES)
        for (t in listOf("current-password", "new-password", "one-time-code", "cc-number", "cc-csc")) assertTrue(t, t in PageScriptProtocol.SECRET_AUTOCOMPLETE)
        assertTrue("k === 'value' && secret(el)" in r)
        assertTrue("secret(el) ? (el.value ? MASK : '')" in r)
        assertTrue("const textOf = (el) => (secret(el) ? ''" in r)
        assertTrue("k.startsWith('on')" in r)
        // 读取器只交纯数据
        assertTrue(PageScriptProtocol.read(buildJsonObject { put("mode", "count") }).startsWith("globalThis.__mikaRead({"))
    }

    @Test fun backstopSeesScriptInitiatedRequests() {
        fun req(stack: String) = details("""{"requestId":"1","request":{"url":"https://x/"},"initiator":{"type":"script","stack":$stack}}""")
        assertTrue(PageScriptProtocol.requestFromScript(req("""{"callFrames":[{"url":"mika-script.js","lineNumber":3}]}""")))
        // 异步的 parent 链里也算
        assertTrue(PageScriptProtocol.requestFromScript(req("""{"callFrames":[],"parent":{"callFrames":[{"url":"mika-prelude.js"}]}}""")))
        // 页面自己的脚本发的(包括 mika.tap 触发的页面处理函数)不算
        assertFalse(PageScriptProtocol.requestFromScript(req("""{"callFrames":[{"url":"https://shop.example.com/app.js"}]}""")))
        assertFalse(PageScriptProtocol.requestFromScript(details("""{"requestId":"1","initiator":{"type":"other"}}""")))
    }

    @Test fun backstopStopsNavigationOutsideActions() {
        val nav = details("""{"frameId":"F1","reason":"scriptInitiated","url":"https://evil/"}""")
        assertTrue(PageScriptProtocol.navigationOutsideAction(nav, "F1", inAction = false))
        // mika.tap / mika.navigate 在做时页面跳转是那个动作的结果
        assertFalse(PageScriptProtocol.navigationOutsideAction(nav, "F1", inAction = true))
        // 子框架自己跳(广告轮播)不管
        assertFalse(PageScriptProtocol.navigationOutsideAction(nav, "F2", inAction = false))
    }
}
