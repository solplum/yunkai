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

class GeminiModelTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun model(vision: Boolean = false) = GeminiModel(
        server.url("/").toString().trimEnd('/'), "gk", "gemini-x",
        Capabilities(true, vision, 1000000, false), OkHttpClient(),
    )
    private val user = listOf(Message(Role.User, listOf(Part.Text("hi")), Author.Mika))
    private fun ok(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun JsonObject.arr(k: String) = this[k]!!.jsonArray
    private fun JsonObject.obj(k: String) = this[k]!!.jsonObject
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun sentBody() = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
    private fun cand(parts: String, finish: String = "STOP") =
        """{"candidates":[{"content":{"role":"model","parts":$parts},"finishReason":"$finish"}]}"""

    @Test fun parsesFunctionCalls() = runTest {
        server.enqueue(ok(cand("""[{"text":"ok"},{"functionCall":{"name":"phone_tap","args":{"ref":"a1"}}},{"functionCall":{"name":"phone_tap","args":{}}}]""")))
        val r = model().complete("s", user, emptyList())
        assertEquals(
            listOf(Part.Text("ok"), Part.ToolCall("gemini-0-phone_tap", "phone_tap", """{"ref":"a1"}"""), Part.ToolCall("gemini-1-phone_tap", "phone_tap", "{}")),
            r.parts,
        )
        assertEquals(StopReason.ToolUse, r.stopReason)
        val req = server.takeRequest()
        assertEquals("/v1beta/models/gemini-x:generateContent", req.path)
        assertEquals("gk", req.getHeader("x-goog-api-key"))
    }

    @Test fun usesIdWhenPresent() = runTest {
        server.enqueue(ok(cand("""[{"functionCall":{"id":"c9","name":"phone_tap","args":{}}}]""")))
        assertEquals(Part.ToolCall("c9", "phone_tap", "{}"), model().complete("s", user, emptyList()).parts[0])
    }

    @Test fun parsesThoughtParts() = runTest {
        server.enqueue(ok(cand("""[{"text":"想","thought":true},{"text":"好"}]""")))
        val r = model().complete("s", user, emptyList())
        assertEquals(listOf(Part.Thinking("想"), Part.Text("好")), r.parts)
        assertEquals(StopReason.EndTurn, r.stopReason)
        assertFalse(sentBody().toString().contains("thinkingConfig"))
    }

    @Test fun systemAndToolsInRequest() = runTest {
        server.enqueue(ok(cand("""[]""")))
        val schema = """{"${'$'}schema":"x","type":"object","additionalProperties":false,"properties":{"a":{"type":"object","additionalProperties":false,"properties":{"b":{"type":"string","default":"d"}}}}}"""
        model().complete("SYS", user, listOf(ToolSpec("t", "d", schema), ToolSpec("noargs", "d", """{"type":"object","additionalProperties":false}""")))
        val j = sentBody()
        assertEquals("SYS", j.obj("systemInstruction").arr("parts")[0].jsonObject.str("text"))
        val decl = j.arr("tools")[0].jsonObject.arr("functionDeclarations")
        val p = decl[0].jsonObject.obj("parameters")
        assertFalse(p.containsKey("additionalProperties")); assertFalse(p.containsKey("\$schema"))
        val inner = p.obj("properties").obj("a")
        assertFalse(inner.containsKey("additionalProperties"))
        assertEquals("d", inner.obj("properties").obj("b").str("default"))
        assertFalse(decl[1].jsonObject.containsKey("parameters"))
    }

    @Test fun blankSystemOmitted() = runTest {
        server.enqueue(ok(cand("""[]""")))
        model().complete("", user, emptyList())
        assertFalse(sentBody().containsKey("systemInstruction"))
    }

    @Test fun toolResultAsFunctionResponse() = runTest {
        server.enqueue(ok(cand("""[]""")))
        val history = listOf(
            Message(Role.User, listOf(Part.Text("go")), Author.Mika),
            Message(Role.Assistant, listOf(Part.Thinking("t"), Part.Text("a"), Part.ToolCall("gemini-0-phone_tap", "phone_tap", """{"ref":"x"}""")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("gemini-0-phone_tap", "done", false)), Author.Mika),
            Message(Role.User, listOf(Part.Text("next")), Author.Mika),
        )
        model().complete("s", history, emptyList())
        val c = sentBody().arr("contents")
        assertEquals(listOf("user", "model", "user"), c.map { it.jsonObject.str("role") })
        val mp = c[1].jsonObject.arr("parts")
        assertEquals(2, mp.size)
        val fc = mp[1].jsonObject.obj("functionCall")
        assertEquals("phone_tap", fc.str("name")); assertEquals("x", fc.obj("args").str("ref"))
        val up = c[2].jsonObject.arr("parts")
        val fr = up[0].jsonObject.obj("functionResponse")
        assertEquals("phone_tap", fr.str("name"))
        assertEquals("done", fr.obj("response").str("content"))
        assertFalse(fr.obj("response").containsKey("is_error"))
        assertEquals("next", up[1].jsonObject.str("text"))
    }

    @Test fun errorToolResultFlagged() = runTest {
        server.enqueue(ok(cand("""[]""")))
        val history = listOf(
            Message(Role.Assistant, listOf(Part.ToolCall("c", "phone_tap", "{}")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("c", "boom", true)), Author.Mika),
        )
        model().complete("s", history, emptyList())
        val fr = sentBody().arr("contents")[1].jsonObject.arr("parts")[0].jsonObject.obj("functionResponse")
        assertEquals("true", fr.obj("response").str("is_error"))
    }

    @Test fun imagesOnlyWhenVision() = runTest {
        val msgs = listOf(Message(Role.User, listOf(Part.Image("B64")), Author.Mika))
        server.enqueue(ok(cand("""[]""")))
        model(vision = true).complete("s", msgs, emptyList())
        val inline = sentBody().arr("contents")[0].jsonObject.arr("parts")[0].jsonObject.obj("inlineData")
        assertEquals("image/png", inline.str("mimeType")); assertEquals("B64", inline.str("data"))
        server.enqueue(ok(cand("""[]""")))
        model(vision = false).complete("s", msgs, emptyList())
        val p = sentBody().arr("contents")[0].jsonObject.arr("parts")[0].jsonObject
        assertEquals("[截图已省略:当前模型不支持看图]", p.str("text"))
    }

    @Test fun sendsJpegMime() = runTest {
        val msgs = listOf(Message(Role.User, listOf(Part.Image("B64", mime = "image/jpeg")), Author.Mika))
        server.enqueue(ok(cand("""[]""")))
        model(vision = true).complete("s", msgs, emptyList())
        val inline = sentBody().arr("contents")[0].jsonObject.arr("parts")[0].jsonObject.obj("inlineData")
        assertEquals("image/jpeg", inline.str("mimeType"))
    }

    private suspend fun errorOf(status: Int, body: String, vararg headers: Pair<String, String>): ModelError {
        val r = MockResponse().setResponseCode(status).setBody(body)
        headers.forEach { r.setHeader(it.first, it.second) }
        server.enqueue(r)
        try { model().complete("s", user, emptyList()) } catch (e: ModelError) { return e }
        fail("expected error"); throw IllegalStateException()
    }
    private fun err(code: Int, status: String, msg: String) = """{"error":{"code":$code,"status":"$status","message":"$msg"}}"""

    @Test fun mapsErrors() = runTest {
        assertTrue(errorOf(400, err(400, "INVALID_ARGUMENT", "The input token count (2000000) exceeds the maximum number of tokens allowed (1048576).")) is ModelError.ContextTooLong)
        assertTrue(errorOf(400, err(400, "INVALID_ARGUMENT", "bad field")) is ModelError.BadResponse)
        assertTrue(errorOf(401, err(401, "UNAUTHENTICATED", "no")) is ModelError.Auth)
        assertTrue(errorOf(403, err(403, "PERMISSION_DENIED", "no")) is ModelError.Auth)
        val rl = errorOf(429, err(429, "RESOURCE_EXHAUSTED", "slow down"), "Retry-After" to "3")
        assertEquals(3000L, (rl as ModelError.RateLimited).retryAfterMs)
        assertTrue(errorOf(429, err(429, "RESOURCE_EXHAUSTED", "You exceeded your current quota")) is ModelError.RateLimited)
        assertTrue(errorOf(404, err(404, "NOT_FOUND", "models/x not found")) is ModelError.ModelNotFound)
        assertTrue(errorOf(403, err(403, "PERMISSION_DENIED", "Your project does not have access to model x")) is ModelError.ModelNotAvailable)
        assertEquals(503, (errorOf(503, err(503, "UNAVAILABLE", "busy")) as ModelError.Server).status)
    }

    @Test fun usage() = runTest {
        server.enqueue(ok("""{"candidates":[{"content":{"parts":[{"text":"x"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":5,"cachedContentTokenCount":40}}"""))
        assertEquals(Usage(100, 5, 40), model().complete("s", user, emptyList()).usage)
    }

    @Test fun finishReasons() = runTest {
        server.enqueue(ok(cand("""[{"text":"x"}]""", "MAX_TOKENS")))
        assertEquals(StopReason.MaxTokens, model().complete("s", user, emptyList()).stopReason)
        server.enqueue(ok(cand("""[{"text":"x"}]""", "SAFETY")))
        assertEquals(StopReason.Other, model().complete("s", user, emptyList()).stopReason)
        server.enqueue(ok("""{"candidates":[{"finishReason":"SAFETY"}]}"""))
        try { model().complete("s", user, emptyList()); fail() } catch (e: ModelError) { assertTrue(e is ModelError.ContentRefused) }
        server.enqueue(ok("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        try { model().complete("s", user, emptyList()); fail() } catch (e: ModelError) { assertTrue(e is ModelError.ContentRefused) }
    }

    @Test fun thoughtSignatureRoundTrip() = runTest {
        server.enqueue(ok(cand("""[{"functionCall":{"name":"phone_tap","args":{}},"thoughtSignature":"SIG=="},{"functionCall":{"name":"phone_back","args":{}}}]""")))
        val r = model().complete("s", user, emptyList())
        val calls = r.parts.filterIsInstance<Part.ToolCall>()
        assertEquals("SIG==", calls[0].opaque); assertEquals(null, calls[1].opaque)
        server.takeRequest()
        server.enqueue(ok(cand("""[]""")))
        model().complete("s", user + Message(Role.Assistant, r.parts, Author.Mika), emptyList())
        val mp = sentBody().arr("contents")[1].jsonObject.arr("parts")
        assertEquals("SIG==", mp[0].jsonObject.str("thoughtSignature"))
        assertFalse(mp[1].jsonObject.containsKey("thoughtSignature"))
    }

    // 别的模型产生的调用(没有签名):一轮里第一个调用发跳过校验的占位签名,其余照旧不带
    @Test fun foreignCallGetsSkipSignature() = runTest {
        server.enqueue(ok(cand("""[]""")))
        val foreign = Message(Role.Assistant, listOf(Part.Text("看看"), Part.ToolCall("c1", "phone_tap", "{}"), Part.ToolCall("c2", "phone_back", "{}")), Author.Mika)
        model().complete("s", user + foreign, emptyList())
        val mp = sentBody().arr("contents")[1].jsonObject.arr("parts")
        assertEquals("skip_thought_signature_validator", mp[1].jsonObject.str("thoughtSignature"))
        assertFalse(mp[0].jsonObject.containsKey("thoughtSignature"))
        assertFalse(mp[2].jsonObject.containsKey("thoughtSignature"))
    }

    @Test fun outputTokensIncludeThoughts() = runTest {
        server.enqueue(ok("""{"candidates":[{"content":{"parts":[{"text":"x"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5,"thoughtsTokenCount":7}}"""))
        assertEquals(Usage(10, 12, 0), model().complete("s", user, emptyList()).usage)
    }

    @Test fun sanitizeKeepsPropertyNamesLikeKeywords() = runTest {
        server.enqueue(ok(cand("""[]""")))
        val schema = """{"type":"object","${'$'}defs":{},"properties":{"additionalProperties":{"type":"string","additionalProperties":false},"${'$'}id":{"type":"string"}}}"""
        model().complete("s", user, listOf(ToolSpec("t", "d", schema)))
        val p = sentBody().arr("tools")[0].jsonObject.arr("functionDeclarations")[0].jsonObject.obj("parameters")
        assertFalse(p.containsKey("\$defs"))
        val props = p.obj("properties")
        assertTrue(props.containsKey("additionalProperties")); assertTrue(props.containsKey("\$id"))
        assertFalse(props.obj("additionalProperties").containsKey("additionalProperties"))
    }
}
