package com.wangzan101.mika.core.model

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 每个请求都带的头:三种协议的适配器(一次性和流式)、列模型都带上,Key 的头不被盖掉;会话号由对话 id 推出。 */
class ClientHeadersTest {
    private lateinit var server: MockWebServer
    private val caps = Capabilities(toolCalls = true, vision = false, contextTokens = 64_000, reasoning = false)
    private val user = listOf(Message(Role.User, listOf(Part.Text("hi")), Author.Mika))
    private val session = ModelSession.of("conversation-1")
    private val headers = ClientHeaders.of("Mika/9.9.9", "x-test-session", session)

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private val base get() = server.url("/").toString().trimEnd('/')
    private fun ok(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun sse(vararg events: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { "data: $it\n\n" })

    private fun RecordedRequest.assertCarries() {
        assertEquals("Mika/9.9.9", getHeader("User-Agent"))
        assertEquals(session.id, getHeader("x-test-session"))
    }

    @Test fun sessionIdIsStablePerConversationAndOpaque() {
        assertEquals(ModelSession.of("c-1"), ModelSession.of("c-1"))
        assertNotEquals(ModelSession.of("c-1"), ModelSession.of("c-2"))
        assertFalse("raw id is not sent", ModelSession.of("c-1").id.contains("c-1"))
        assertNotEquals(ModelSession.fresh(), ModelSession.fresh())
    }

    @Test fun noSessionHeaderUnlessDeclared() {
        assertEquals(mapOf("User-Agent" to "Mika/1"), ClientHeaders.of("Mika/1", null, session))
        assertEquals(mapOf("User-Agent" to "Mika/1"), ClientHeaders.of("Mika/1", " ", session))
        assertEquals(session.id, ClientHeaders.of("Mika/1", "x-s", session)["x-s"])
    }

    @Test fun chatCompletionsCarriesHeaders() = runTest {
        server.enqueue(ok("""{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""))
        server.enqueue(sse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""", "[DONE]"))
        val m = OpenAiCompatModel(base, "sk", "m", caps, OkHttpClient(), headers = headers)
        m.complete("s", user, emptyList())
        m.completeStreaming("s", user, emptyList()) {}
        repeat(2) {
            val req = server.takeRequest()
            req.assertCarries()
            assertEquals("Bearer sk", req.getHeader("Authorization"))
        }
    }

    @Test fun messagesCarriesHeadersAndMaxTokens() = runTest {
        server.enqueue(ok("""{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}"""))
        AnthropicModel(base, "ak", "claude-x", caps, OkHttpClient(), maxTokens = 16, headers = headers).complete("s", user, emptyList())
        val req = server.takeRequest()
        req.assertCarries()
        assertEquals("ak", req.getHeader("x-api-key"))
        assertEquals(16, Json.parseToJsonElement(req.body.readUtf8()).jsonObject["max_tokens"]!!.jsonPrimitive.int)
    }

    @Test fun generateContentCarriesHeadersAndOptionalMaxTokens() = runTest {
        val reply = """{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""
        server.enqueue(ok(reply))
        server.enqueue(ok(reply))
        GeminiModel(base, "gk", "g", caps, OkHttpClient(), headers = headers, maxOutputTokens = 16).complete("s", user, emptyList())
        GeminiModel(base, "gk", "g", caps, OkHttpClient(), headers = headers).complete("s", user, emptyList())
        val limited = server.takeRequest()
        limited.assertCarries()
        assertEquals("gk", limited.getHeader("x-goog-api-key"))
        val body = Json.parseToJsonElement(limited.body.readUtf8()).jsonObject
        assertEquals(16, body["generationConfig"]!!.jsonObject["maxOutputTokens"]!!.jsonPrimitive.int)
        val plain = server.takeRequest()
        plain.assertCarries()
        assertNull(Json.parseToJsonElement(plain.body.readUtf8()).jsonObject["generationConfig"])
    }

    @Test fun responsesCarriesHeadersAndNoOutputLimit() = runTest {
        server.enqueue(sse("""{"type":"response.completed","response":{"status":"completed"}}"""))
        ResponsesModel(base, BearerToken.fixed("rk"), "gpt-x", caps, OkHttpClient(), headers = headers).complete("s", user, emptyList())
        val req = server.takeRequest()
        req.assertCarries()
        assertEquals("Bearer rk", req.getHeader("Authorization"))
        assertNull(Json.parseToJsonElement(req.body.readUtf8()).jsonObject["max_output_tokens"])
    }

    @Test fun catalogCarriesHeaders() = runTest {
        server.enqueue(ok("""{"data":[{"id":"m"}]}"""))
        server.enqueue(ok("""{"data":[{"id":"c"}],"has_more":false}"""))
        server.enqueue(ok("""{"models":[{"name":"models/g"}]}"""))
        val catalog = ModelCatalog(OkHttpClient())
        assertTrue(catalog.openAiCompat(base, "sk", headers) is ModelListResult.Ok)
        assertTrue(catalog.anthropic(base, "ak", headers) is ModelListResult.Ok)
        assertTrue(catalog.gemini(base, "gk", headers) is ModelListResult.Ok)
        repeat(3) { server.takeRequest().assertCarries() }
    }

    @Test fun withoutHeadersNothingExtraIsSent() = runTest {
        server.enqueue(ok("""{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""))
        OpenAiCompatModel(base, "sk", "m", caps, OkHttpClient()).complete("s", user, emptyList())
        assertNull(server.takeRequest().getHeader("x-test-session"))
    }
}
