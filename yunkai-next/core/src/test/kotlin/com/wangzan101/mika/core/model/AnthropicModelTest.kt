package com.wangzan101.mika.core.model

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AnthropicModelTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun model(vision: Boolean = false, maxTokens: Int = 4096) = AnthropicModel(
        server.url("/").toString().trimEnd('/'), "sk-ant", "claude-x",
        Capabilities(true, vision, 200000, false), OkHttpClient(), maxTokens,
    )
    private val user = listOf(Message(Role.User, listOf(Part.Text("hi")), Author.Mika))
    private fun ok(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun JsonObject.arr(k: String) = this[k]!!.jsonArray
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun sentBody() = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    @Test fun parsesToolUseBlocks() = runTest {
        server.enqueue(ok("""{"content":[{"type":"text","text":"ok"},{"type":"tool_use","id":"t1","name":"phone_observe","input":{}}],"stop_reason":"tool_use","usage":{"input_tokens":10,"output_tokens":3,"cache_read_input_tokens":7}}"""))
        val r = model().complete("sys", user, listOf(ToolSpec("phone_observe", "d", """{"type":"object"}""")))
        assertEquals(listOf(Part.Text("ok"), Part.ToolCall("t1", "phone_observe", "{}")), r.parts)
        assertEquals(StopReason.ToolUse, r.stopReason)
        assertEquals(Usage(17, 3, 7), r.usage)
        val req = server.takeRequest()
        assertEquals("/v1/messages", req.path)
        assertEquals("sk-ant", req.getHeader("x-api-key"))
        assertEquals("2023-06-01", req.getHeader("anthropic-version"))
        val j = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("4096", j.str("max_tokens"))
        assertEquals("claude-x", j.str("model"))
    }

    @Test fun parsesThinkingBlocksAndStopReasons() = runTest {
        server.enqueue(ok("""{"content":[{"type":"thinking","thinking":"想","signature":"s"},{"type":"text","text":"好"}],"stop_reason":"end_turn"}"""))
        val r = model().complete("s", user, emptyList())
        assertEquals(listOf(Part.Thinking("想"), Part.Text("好")), r.parts)
        assertEquals(StopReason.EndTurn, r.stopReason)
        server.enqueue(ok("""{"content":[{"type":"text","text":"x"}],"stop_reason":"max_tokens"}"""))
        assertEquals(StopReason.MaxTokens, model().complete("s", user, emptyList()).stopReason)
        server.enqueue(ok("""{"content":[{"type":"text","text":"x"}],"stop_reason":"refusal"}"""))
        assertEquals(StopReason.Other, model().complete("s", user, emptyList()).stopReason)
    }

    @Test fun marksCacheOnSystemAndTools() = runTest {
        server.enqueue(ok("""{"content":[],"stop_reason":"end_turn"}"""))
        val tools = listOf(ToolSpec("a", "d", """{"type":"object"}"""), ToolSpec("b", "d", """{"type":"object"}"""))
        model().complete("SYS", user, tools)
        val j = sentBody()
        val sys = j.arr("system")[0].jsonObject
        assertEquals("SYS", sys.str("text"))
        assertEquals("ephemeral", sys["cache_control"]!!.jsonObject.str("type"))
        val t = j.arr("tools")
        assertFalse(t[0].jsonObject.containsKey("cache_control"))
        assertEquals("ephemeral", t[1].jsonObject["cache_control"]!!.jsonObject.str("type"))
        assertEquals("object", t[0].jsonObject["input_schema"]!!.jsonObject.str("type"))
    }

    @Test fun toolResultIsUserMessageAndRolesAlternate() = runTest {
        server.enqueue(ok("""{"content":[],"stop_reason":"end_turn"}"""))
        val history = listOf(
            Message(Role.User, listOf(Part.Text("go")), Author.Mika),
            Message(Role.Assistant, listOf(Part.Thinking("t"), Part.Text("a"), Part.ToolCall("t1", "phone_tap", """{"ref":"x"}""")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("t1", "done", false)), Author.Mika),
            Message(Role.User, listOf(Part.Text("then")), Author.Mika),
        )
        model().complete("s", history, emptyList())
        val msgs = sentBody().arr("messages")
        assertEquals(listOf("user", "assistant", "user"), msgs.map { it.jsonObject.str("role") })
        val asst = msgs[1].jsonObject.arr("content")
        assertEquals(listOf("text", "tool_use"), asst.map { it.jsonObject.str("type") })
        assertEquals("x", asst[1].jsonObject["input"]!!.jsonObject.str("ref"))
        val u = msgs[2].jsonObject.arr("content")
        assertEquals("tool_result", u[0].jsonObject.str("type"))
        assertEquals("t1", u[0].jsonObject.str("tool_use_id"))
        assertEquals("done", u[0].jsonObject.str("content"))
        assertEquals("false", u[0].jsonObject.str("is_error"))
        assertEquals("then", u[1].jsonObject.str("text"))
    }

    @Test fun imagesOnlyWhenVision() = runTest {
        val m = listOf(Message(Role.User, listOf(Part.Text("look"), Part.Image("QUJD")), Author.Mika))
        server.enqueue(ok("""{"content":[],"stop_reason":"end_turn"}"""))
        model(vision = true).complete("s", m, emptyList())
        val img = sentBody().arr("messages")[0].jsonObject.arr("content")[1].jsonObject
        assertEquals("image", img.str("type"))
        assertEquals("QUJD", img["source"]!!.jsonObject.str("data"))
        assertEquals("image/png", img["source"]!!.jsonObject.str("media_type"))
        server.enqueue(ok("""{"content":[],"stop_reason":"end_turn"}"""))
        model(vision = false).complete("s", m, emptyList())
        val raw = server.takeRequest().body.readUtf8()
        assertFalse(raw.contains("\"image\""))
        assertTrue(raw.contains("截图已省略"))
    }

    @Test fun sendsJpegMime() = runTest {
        val m = listOf(Message(Role.User, listOf(Part.Image("QUJD", mime = "image/jpeg")), Author.Mika))
        server.enqueue(ok("""{"content":[],"stop_reason":"end_turn"}"""))
        model(vision = true).complete("s", m, emptyList())
        val img = sentBody().arr("messages")[0].jsonObject.arr("content")[0].jsonObject
        assertEquals("image/jpeg", img["source"]!!.jsonObject.str("media_type"))
    }

    @Test fun systemMessagesMergeIntoSystemText() = runTest {
        server.enqueue(ok("""{"content":[],"stop_reason":"end_turn"}"""))
        model().complete("S1", listOf(Message(Role.System, listOf(Part.Text("S2")), Author.Mika), user[0]), emptyList())
        val j = sentBody()
        assertEquals("S1\n\nS2", j.arr("system")[0].jsonObject.str("text"))
        assertEquals(1, j.arr("messages").size)
    }

    private suspend fun errorOf(status: Int, body: String): ModelError {
        server.enqueue(MockResponse().setResponseCode(status).setBody(body))
        try { model().complete("s", user, emptyList()) } catch (e: ModelError) { return e }
        fail("expected ModelError"); throw IllegalStateException()
    }

    @Test fun mapsErrors() = runTest {
        assertTrue(errorOf(401, "{}") is ModelError.Auth)
        assertTrue(errorOf(403, "{}") is ModelError.Auth)
        assertTrue(errorOf(404, "{}") is ModelError.ModelNotFound)
        assertTrue(errorOf(403, """{"error":{"type":"permission_error","message":"Your organization does not have access to the model"}}""") is ModelError.ModelNotAvailable)
        assertTrue(errorOf(413, "{}") is ModelError.ContextTooLong)
        assertTrue(errorOf(400, """{"error":{"type":"invalid_request_error","message":"prompt is too long: 250000 tokens"}}""") is ModelError.ContextTooLong)
        assertTrue(errorOf(400, """{"error":{"message":"Your credit balance is too low"}}""") is ModelError.QuotaExceeded)
        server.enqueue(MockResponse().setResponseCode(429).setHeader("retry-after", "2").setBody("{}"))
        val rl = try { model().complete("s", user, emptyList()); null } catch (e: ModelError.RateLimited) { e }
        assertEquals(2000L, rl!!.retryAfterMs)
        assertEquals(529, (errorOf(529, """{"error":{"type":"overloaded_error"}}""") as ModelError.Server).status)
        assertEquals(502, (errorOf(502, "x") as ModelError.Server).status)
    }

    @Test fun usageInputIsTotalIncludingCache() = runTest {
        server.enqueue(ok("""{"content":[],"stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":2,"cache_read_input_tokens":100,"cache_creation_input_tokens":20}}"""))
        assertEquals(Usage(125, 2, 100), model().complete("s", user, emptyList()).usage)
    }

    @Test fun omitsSystemWhenBlank() = runTest {
        server.enqueue(ok("""{"content":[],"stop_reason":"end_turn"}"""))
        model().complete("", user, emptyList())
        assertFalse(sentBody().containsKey("system"))
    }
}
