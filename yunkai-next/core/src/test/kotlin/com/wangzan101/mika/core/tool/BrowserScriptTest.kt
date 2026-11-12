package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.cap.web.FakeWebPort
import com.wangzan101.mika.core.cap.web.ScriptAbort
import com.wangzan101.mika.core.cap.web.ScriptBridge
import com.wangzan101.mika.core.cap.web.ScriptRun
import com.wangzan101.mika.core.cap.web.WebPort
import com.wangzan101.mika.core.cap.web.WebTabHandedOver
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.cap.web.EVAL_RESULT_MAX_CHARS
import com.wangzan101.mika.core.cap.web.SCRIPT_DEFAULT_TIMEOUT_MS
import com.wangzan101.mika.core.cap.web.SCRIPT_MAX_ACTIONS
import com.wangzan101.mika.core.cap.web.SCRIPT_MAX_NAVIGATIONS
import com.wangzan101.mika.core.cap.web.SCRIPT_MAX_READS
import com.wangzan101.mika.core.testing.WEB_SCRIPT_TOOL
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.assembly.knownToolNames

/** 假端口:runScript 按 [script] 把调用发给桥,模拟页面里的脚本。 */
internal class ScriptWebPort(val inner: FakeWebPort = FakeWebPort(), var script: suspend (ScriptBridge) -> ScriptRun = { ScriptRun.Done(null) }) : WebPort by inner {
    var lastCode: String? = null
    var lastTimeout: Long? = null
    override suspend fun runScript(code: String, timeoutMs: Long, bridge: ScriptBridge): ScriptRun {
        lastCode = code; lastTimeout = timeoutMs
        return try { script(bridge) } catch (e: ScriptAbort) { ScriptRun.Failed(e.error, e.message.orEmpty()) }
    }
}

class BrowserScriptTest {
    private val subCalls = mutableListOf<Part.ToolCall>()

    private suspend fun dispatcher(web: WebPort): ToolDispatcher = toolDispatcher(FakeScreen(), web = web).also {
        it.execute(Part.ToolCall("n", "web_navigate", """{"url":"https://shop.example.com/"}"""))
    }

    /** 直接交给分发器执行的子步(真实路径由循环接上关卡,见 ScriptStepsTest)。 */
    private fun passthrough(d: ToolDispatcher) = SubActions { c -> subCalls += c; d.execute(c) }

    private suspend fun ToolDispatcher.script(code: String = "return 1", sub: SubActions? = null, extra: String = "") =
        execute(Part.ToolCall("c1", WEB_SCRIPT_TOOL, """{"code":${JsonPrimitive(code)}$extra}"""), 2, sub).content

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun args(vararg kv: Pair<String, Any>) = buildJsonObject {
        kv.forEach { (k, v) -> when (v) { is String -> put(k, v); is Int -> put(k, v); is Boolean -> put(k, v) } }
    }

    @Test fun offeredWithWebPort() {
        assertTrue(toolDispatcher(FakeScreen(), web = FakeWebPort()).definitions().any { it.name == WEB_SCRIPT_TOOL })
        assertFalse(toolDispatcher(FakeScreen()).definitions().any { it.name == WEB_SCRIPT_TOOL })
        assertTrue(WEB_SCRIPT_TOOL in knownToolNames())
    }

    @Test fun devtoolsUnavailableByDefault() = runTest {
        val r = dispatcher(FakeWebPort()).script()
        assertEquals("false", r.s("ok")); assertEquals("devtools_unavailable", r.s("error"))
        assertTrue(r["actions"]!!.jsonArray.isEmpty())
    }

    @Test fun runsOnTheWebWhicheverScreenWasUsedLast() = runTest {
        val web = ScriptWebPort()
        val d = dispatcher(web)
        d.execute(Part.ToolCall("p", "phone_observe", "{}"))
        d.script()
        assertEquals("return 1", web.lastCode)
    }

    @Test fun passesCodeAndTimeout() = runTest {
        val web = ScriptWebPort()
        val d = dispatcher(web)
        d.script("return 2", extra = ""","timeoutMs":5000""")
        assertEquals("return 2", web.lastCode); assertEquals(5000L, web.lastTimeout)
        d.script()
        assertEquals(SCRIPT_DEFAULT_TIMEOUT_MS.toLong(), web.lastTimeout)
        val bad = d.script(extra = ""","timeoutMs":90000""")
        assertEquals("invalid_arguments", bad.s("error"))
    }

    @Test fun snapshotTapTypeGoThroughSubActions() = runTest {
        var seen: JsonObject? = null
        val web = ScriptWebPort()
        val d = dispatcher(web)
        web.script = { b ->
            val snap = b.call("snapshot", JsonObject(emptyMap()))
            seen = snap
            assertEquals(JsonPrimitive(true), b.call("tap", args("ref" to "@e1"))["ok"])
            // 写操作之后旧编号作废,要再 snapshot
            assertEquals("no_snapshot", b.call("type", args("ref" to "@e1", "text" to "咖啡"))["error"]!!.jsonPrimitive.content)
            b.call("snapshot", JsonObject(emptyMap()))
            b.call("type", args("ref" to "@e1", "text" to "咖啡", "enter" to true))
            b.log("done 2")
            ScriptRun.Done("""{"n":2}""")
        }
        val r = d.script(sub = passthrough(d))
        assertEquals("true", r.s("ok")); assertEquals(com.wangzan101.mika.core.observation.Untrusted.wrap("""{"n":2}"""), r.s("result")); assertEquals("return", r.s("endedBy"))
        val v = seen!!["value"]!!.jsonObject
        val e1 = v["elements"]!!.jsonArray[0].jsonObject
        assertEquals("@e1", e1.s("ref")); assertEquals("搜索", e1.s("name")); assertTrue("center" in e1)
        assertEquals(listOf("c1.1", "c1.2", "c1.3", "c1.4"), subCalls.map { it.id })
        assertEquals(listOf("web_list_elements", "web_tap", "web_list_elements", "web_type_text"), subCalls.map { it.name })
        assertTrue("\"pressEnter\":true" in subCalls[3].argsJson)
        val acts = r["actions"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("snapshot", "tap", "snapshot", "type"), acts.map { it.s("op") })
        assertEquals("@e1", acts[1].s("ref"))
        assertEquals(listOf("done 2"), r["logs"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(web.inner.calls.any { it.startsWith("tapRef @e1") })
        assertTrue(web.inner.calls.any { it.startsWith("type @e1 咖啡") })
    }

    @Test fun actionLimitStopsScript() = runTest {
        val web = ScriptWebPort()
        val d = dispatcher(web)
        var thrown: ScriptAbort? = null
        web.script = { b ->
            try {
                repeat(SCRIPT_MAX_ACTIONS + 1) {
                    b.call("snapshot", JsonObject(emptyMap()))
                    b.call("tap", args("ref" to "@e1"))
                }
            } catch (e: ScriptAbort) { thrown = e }
            // 停下之后再调也不做
            b.call("snapshot", JsonObject(emptyMap()))
            ScriptRun.Done(null)
        }
        val r = d.script(sub = passthrough(d))
        assertEquals("limit_exceeded", thrown!!.error)
        assertEquals("false", r.s("ok")); assertEquals("limit_exceeded", r.s("error"))
        assertEquals(SCRIPT_MAX_ACTIONS, subCalls.count { it.name == "web_tap" })
        assertEquals(SCRIPT_MAX_ACTIONS + 1, subCalls.count { it.name == "web_list_elements" })
    }

    @Test fun readLimit() = runTest {
        val web = ScriptWebPort { b -> repeat(SCRIPT_MAX_READS + 5) { b.call("read", JsonObject(emptyMap())) }; ScriptRun.Done(null) }
        val d = dispatcher(web)
        val r = d.script(sub = passthrough(d))
        assertEquals("limit_exceeded", r.s("error"))
        assertEquals(SCRIPT_MAX_READS, subCalls.size)
        assertTrue("\"maxChars\":4000" in subCalls[0].argsJson)
    }

    @Test fun navigationLimitAndEnd() = runTest {
        val web = ScriptWebPort { b -> repeat(SCRIPT_MAX_NAVIGATIONS + 1) { b.call("navigate", args("url" to "https://shop.example.com/#$it")) }; ScriptRun.Done(null) }
        val d = dispatcher(web)
        assertEquals("limit_exceeded", d.script(sub = passthrough(d)).s("error"))
        assertEquals(SCRIPT_MAX_NAVIGATIONS, subCalls.size)
        // 整页跳走:脚本停在那里,照实说
        web.script = { b -> b.call("navigate", args("url" to "https://shop.example.com/2")); ScriptRun.Navigated }
        val r = d.script(sub = passthrough(d))
        assertEquals("true", r.s("ok")); assertEquals("navigation", r.s("endedBy"))
        assertEquals("https://shop.example.com/2", r["actions"]!!.jsonArray[0].jsonObject.s("url"))
    }

    @Test fun fatalSubResultStopsScript() = runTest {
        val web = ScriptWebPort()
        val d = dispatcher(web)
        // 关卡拦下:子步没执行,脚本停,结果里带拦下的原因
        val gated = SubActions { c ->
            subCalls += c
            if (c.name == "web_tap") ToolExecution(buildJsonObject {
                put("ok", false); put("error", "confirmation_required"); put("executed", false); put("action", "send"); put("matched", "发送")
            }) else d.execute(c)
        }
        var after: JsonObject? = null
        web.script = { b ->
            b.call("snapshot", JsonObject(emptyMap()))
            runCatching { b.call("tap", args("ref" to "@e1")) }
            after = runCatching { b.call("snapshot", JsonObject(emptyMap())) }.getOrNull()
            ScriptRun.Done("1")
        }
        val r = d.script(sub = gated)
        assertNull(after)
        assertEquals("confirmation_required", r.s("error")); assertEquals("发送", r.s("matched")); assertEquals("false", r.s("counts_as_failure"))
        val last = r["actions"]!!.jsonArray.last().jsonObject
        assertEquals("false", last.s("executed"))
        assertEquals(2, subCalls.size)
        assertFalse(web.inner.calls.any { it.startsWith("tapRef") })
    }

    @Test fun nonFatalErrorsGoBackToScript() = runTest {
        val results = mutableListOf<JsonObject>()
        val web = ScriptWebPort { b ->
            results += b.call("tap", args("ref" to "@e1"))
            results += b.call("tap", JsonObject(emptyMap()))
            results += b.call("eval", JsonObject(emptyMap()))
            results += b.call("read", args("maxChars" to 0))
            ScriptRun.Done(null)
        }
        val d = dispatcher(web)
        val r = d.script(sub = passthrough(d))
        assertEquals("true", r.s("ok")); assertEquals("null", r.s("result"))
        assertEquals(listOf("no_snapshot", "invalid_arguments", "unknown_call", "invalid_arguments"), results.map { it.s("error") })
        assertTrue(subCalls.isEmpty())
    }

    @Test fun pageReadsAreValidatedCountedAndHandedToThePort() = runTest {
        val got = mutableListOf<JsonObject>()
        val web = ScriptWebPort { b ->
            got += b.call("queryAll", buildJsonObject { put("selector", "li.item"); put("fields", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("text"), JsonPrimitive("box")))); put("limit", 5) })
            got += b.call("query", buildJsonObject { put("selector", "h1") })
            got += b.call("query", buildJsonObject { put("selector", "li"); put("fields", "count") })
            got += b.call("text", buildJsonObject { put("selector", "body"); put("maxChars", 100) })
            got += b.call("queryAll", buildJsonObject { put("selector", "input[value^=\"c\"]") })
            got += b.call("queryAll", buildJsonObject { put("selector", "li"); put("fields", "html") })
            got += b.call("queryAll", buildJsonObject { put("selector", "li"); put("limit", 999) })
            ScriptRun.Done(null)
        }
        val d = dispatcher(web)
        val r = d.script(sub = passthrough(d))
        val specs = got.take(4).map { it["read"]!!.jsonObject }
        assertEquals(listOf("all", "one", "count", "text"), specs.map { it.s("mode") })
        assertEquals("[\"text\",\"box\"]", specs[0]["fields"].toString()); assertEquals("5", specs[0].s("limit"))
        assertEquals("[\"text\"]", specs[1]["fields"].toString()); assertEquals("50", specs[1].s("limit"))
        assertEquals("100", specs[3].s("maxChars"))
        // 按 value 属性选元素(猜隐藏字段)、不认识的字段、超上限都不交给端口
        assertEquals("invalid_selector", got[4].s("error"))
        assertEquals("invalid_arguments", got[5].s("error")); assertEquals("invalid_arguments", got[6].s("error"))
        // 页面读不经工具,只记进动作列表
        assertTrue(subCalls.isEmpty())
        assertEquals(listOf("queryAll", "query", "query", "text"), r["actions"]!!.jsonArray.map { it.jsonObject.s("op") })
    }

    @Test fun pageReadsShareTheReadLimit() = runTest {
        val web = ScriptWebPort { b -> repeat(SCRIPT_MAX_READS + 1) { b.call("text", buildJsonObject { put("selector", "body") }) }; ScriptRun.Done(null) }
        assertEquals("limit_exceeded", dispatcher(web).script().s("error"))
    }

    @Test fun scrollAndWaitTextGoThroughSubActions() = runTest {
        val web = ScriptWebPort { b ->
            b.call("scroll", buildJsonObject { put("direction", "down"); put("amount", 2) })
            b.call("waitText", buildJsonObject { put("text", "完成"); put("timeoutMs", 2000) })
            ScriptRun.Done(null)
        }
        val d = dispatcher(web)
        d.script(sub = passthrough(d))
        assertEquals(listOf("web_scroll", "web_wait_for"), subCalls.map { it.name })
        assertTrue("\"amount\":2" in subCalls[0].argsJson)
        assertTrue("\"condition\":\"text_present\"" in subCalls[1].argsJson && "\"seconds\":2.0" in subCalls[1].argsJson)
    }

    @Test fun dynamicImportRejectedBeforeRunning() = runTest {
        val web = ScriptWebPort()
        val r = dispatcher(web).script("await import('data:text/javascript,1')")
        assertEquals("invalid_arguments", r.s("error"))
        assertNull(web.lastCode)
    }

    @Test fun withoutSubActionsNothingRuns() = runTest {
        val web = ScriptWebPort { b -> b.call("snapshot", JsonObject(emptyMap())); ScriptRun.Done(null) }
        val r = dispatcher(web).script()
        assertEquals("actions_unavailable", r.s("error"))
        assertEquals(0, web.inner.calls.count { it == "snapshot" })
    }

    @Test fun shapesErrorsAndTruncation() = runTest {
        val web = ScriptWebPort { ScriptRun.Failed("script_error", "TypeError: x is undefined", 3) }
        val d = dispatcher(web)
        val err = d.script()
        assertEquals("script_error", err.s("error")); assertEquals("3", err.s("line"))
        assertTrue(err.s("message").startsWith("TypeError: x is undefined"))
        web.script = { ScriptRun.Done("\"" + "x".repeat(EVAL_RESULT_MAX_CHARS + 10) + "\"") }
        val big = d.script()
        assertEquals(EVAL_RESULT_MAX_CHARS, big.s("result").length); assertEquals("true", big.s("truncated"))
        web.script = { b -> repeat(60) { b.log("l$it") }; ScriptRun.Done(null) }
        val logs = d.script()
        assertEquals(50, logs["logs"]!!.jsonArray.size); assertEquals("10", logs.s("logsDropped"))
        web.script = { throw WebTabHandedOver("t1") }
        assertEquals("handed_over", d.script().s("error"))
    }
}
