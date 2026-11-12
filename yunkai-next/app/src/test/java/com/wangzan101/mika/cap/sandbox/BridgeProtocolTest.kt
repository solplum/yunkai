// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.core.workspace.SandboxError
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class BridgeProtocolTest {
    private val token = "secret-token"
    private val progressed = mutableListOf<String>()
    private val shared = mutableListOf<Pair<String, String?>>()
    private var shareResult: () -> String = { "Download/Yunkai/a.png" }

    private fun req(method: String, params: JsonObject = JsonObject(emptyMap()), tok: String = token) =
        buildJsonObject { put("token", tok); put("method", method); put("params", params) }.toString()

    private fun handle(line: String, network: Boolean = false, gate: ProgressGate = ProgressGate()) = runBlocking {
        BridgeProtocol.handle(line, token,
            progress = { t -> gate.offer().also { if (it) progressed += t } },
            share = { p, n -> shared += p to n; shareResult() },
            info = { buildJsonObject { put("version", "1.0"); put("network", network); put("workspace", "/workspace") } })
    }

    private fun BridgeReply.obj() = Json.parseToJsonElement(json).jsonObject
    private fun BridgeReply.s(k: String) = obj()[k]!!.jsonPrimitive.content

    @Test fun badTokenRejected() {
        for (bad in listOf(req("info", tok = "wrong"), """{"method":"info"}""", """{"token":7,"method":"info"}""")) {
            val r = handle(bad)
            assertEquals("bad_token", r.s("error"))
            assertTrue(r.close)
        }
        assertTrue(progressed.isEmpty())
    }

    @Test fun malformedCloses() {
        for (bad in listOf("not json", "[1,2]", "", "{")) {
            val r = handle(bad)
            assertEquals(bad, "bad_request", r.s("error"))
            assertTrue(r.close)
        }
    }

    @Test fun oversizeRejected() {
        val big = req("progress", buildJsonObject { put("text", "x".repeat(70_000)) })
        val r = handle(big)
        assertEquals("too_large", r.s("error")); assertTrue(r.close)
        // 读的时候就截住:超过 64 KB 不再往下读
        val line = BridgeProtocol.readLine(ByteArrayInputStream(ByteArray(70_000) { 'a'.code.toByte() } + "\n".toByteArray()))
        assertEquals(BridgeProtocol.Line.TooLarge, line)
        val ok = BridgeProtocol.readLine(ByteArrayInputStream("ab\ncd\n".toByteArray()))
        assertEquals("ab", (ok as BridgeProtocol.Line.Text).text)
        assertEquals(BridgeProtocol.Line.End, BridgeProtocol.readLine(ByteArrayInputStream("half".toByteArray())))
    }

    @Test fun progressRateLimited() {
        var now = 10_000L
        val gate = ProgressGate(1_000) { now }
        assertEquals("true", handle(req("progress", buildJsonObject { put("text", "第1步") }), gate = gate).s("shown"))
        now += 400
        assertEquals("false", handle(req("progress", buildJsonObject { put("text", "第2步") }), gate = gate).s("shown"))
        now += 700
        assertEquals("true", handle(req("progress", buildJsonObject { put("text", "第3步\u0007\n") }), gate = gate).s("shown"))
        assertEquals(listOf("第1步", "第3步"), progressed)
        assertEquals("bad_params", handle(req("progress")).s("error"))
    }

    @Test fun unknownMethod() {
        for (m in listOf("ask_model", "phone_tap", "phone_observe", "ask_user", "")) {
            val r = handle(req(m))
            assertEquals("unknown_method", r.s("error"))
            assertFalse(r.close)
        }
    }

    @Test fun infoReportsNetwork() {
        val r = handle(req("info"), network = false).obj()
        assertEquals("true", r["ok"]!!.jsonPrimitive.content)
        assertEquals("false", r["network"]!!.jsonPrimitive.content)
        assertEquals("/workspace", r["workspace"]!!.jsonPrimitive.content)
        assertEquals("true", handle(req("info"), network = true).obj()["network"]!!.jsonPrimitive.content)
    }

    @Test fun phoneRequestUsesCurrentTaskHandler() = runBlocking {
        val params = buildJsonObject {
            put("tool", "query")
            put("args", buildJsonObject { put("text", "设置") })
        }
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val ok = BridgeProtocol.handle(req("phone", params), token,
            progress = { true }, share = { _, _ -> "" }, info = { JsonObject(emptyMap()) },
            phone = { tool, args ->
                calls += tool to args
                buildJsonObject { put("ok", true); put("matches", 1) }
            })
        assertEquals("true", ok.s("ok"))
        assertEquals("1", ok.s("matches"))
        assertEquals("query", calls.single().first)
        assertEquals("设置", calls.single().second["text"]!!.jsonPrimitive.content)

        val denied = BridgeProtocol.handle(req("phone", params, tok = "wrong"), token,
            progress = { true }, share = { _, _ -> "" }, info = { JsonObject(emptyMap()) },
            phone = { _, _ -> error("invalid token reached phone callback") })
        assertEquals("bad_token", denied.s("error"))
        assertEquals("phone_unavailable", handle(req("phone", params)).s("error"))
        assertEquals("bad_params", handle(req("phone", buildJsonObject { put("tool", "query") })).s("error"))
    }

    @Test fun shareGoesThroughCallbackAndMapsErrors() {
        val ok = handle(req("share", buildJsonObject { put("path", "/workspace/out/a.png"); put("name", "图.png") }))
        assertEquals("Download/Yunkai/a.png", ok.s("savedAs"))
        assertEquals(listOf("/workspace/out/a.png" to "图.png"), shared)
        shareResult = { throw SandboxError("share_unavailable", "not yet") }
        assertEquals("share_unavailable", handle(req("share", buildJsonObject { put("path", "/workspace/a") })).s("error"))
        // 意外的失败只回错误码,不把内部信息交给沙箱
        shareResult = { throw IllegalStateException("/data/user/0/secret path") }
        val failed = handle(req("share", buildJsonObject { put("path", "/workspace/a") }))
        assertEquals("share_failed", failed.s("error"))
        assertFalse(failed.json, failed.json.contains("secret"))
        shareResult = { throw SandboxError("path_outside_workspace", "no") }
        assertEquals("path_outside_workspace", handle(req("share", buildJsonObject { put("path", "/etc/passwd") })).s("error"))
        assertEquals("bad_params", handle(req("share")).s("error"))
    }

    @Test fun progressTextCleaned() {
        val emoji = "\uD83D\uDE00"
        assertEquals("ab", BridgeProtocol.cleanProgress("a\u200Bb"))
        assertEquals("evil.exe", BridgeProtocol.cleanProgress("evil\u202E.exe"))
        assertEquals("ab", BridgeProtocol.cleanProgress("a\u2028\u2029\u0085\u0007b"))
        assertEquals("x".repeat(299) + emoji, BridgeProtocol.cleanProgress("x".repeat(299) + emoji + "tail"))
        val cut = BridgeProtocol.cleanProgress(emoji.repeat(400))
        assertEquals(300, cut.codePointCount(0, cut.length))
        assertFalse(cut.last().isHighSurrogate())
    }
}
