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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ResponsesModelTest {
    private lateinit var server: MockWebServer
    private val caps = Capabilities(toolCalls = true, vision = true, contextTokens = 200_000, reasoning = true)
    private val user = listOf(Message(Role.User, listOf(Part.Text("hi")), Author.User))
    private val tool = ToolSpec("phone_tap", "点一下", """{"type":"object","properties":{"ref":{"type":"string"}}}""")

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun stream(vararg data: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(data.joinToString("") { "data: $it\n\n" })

    private fun model(token: BearerToken = BearerToken.fixed("k"), plan: Boolean = false, vision: Boolean = true) =
        ResponsesModel(server.url("/v1").toString().trimEnd('/'), token, "gpt-x", caps.copy(vision = vision), OkHttpClient(), plan)

    private fun completed(usage: String = """{"input_tokens":12,"output_tokens":4,"input_tokens_details":{"cached_tokens":8}}""") =
        """{"type":"response.completed","response":{"status":"completed","usage":$usage}}"""

    private fun sentBody(): JsonObject = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    @Test fun requestKeepsOnlyAllowedFieldsAndPutsSystemPromptInInstructions() = runTest {
        server.enqueue(stream(completed()))
        val history = listOf(
            Message(Role.System, listOf(Part.Text("备注")), Author.Mika),
            Message(Role.User, listOf(Part.Text("看图"), Part.Image("QUJD", "image/jpeg")), Author.User),
        )
        model().complete("你是 Mika", history, listOf(tool))
        val req = server.takeRequest()
        assertEquals("/v1/responses", req.path)
        assertEquals("Bearer k", req.getHeader("Authorization"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals(setOf("model", "instructions", "input", "tools", "store", "stream", "include"), body.keys)
        assertEquals("false", body["store"].toString())
        assertEquals("true", body["stream"].toString())
        assertEquals("你是 Mika", body["instructions"]!!.jsonPrimitive.content)
        assertEquals("""["reasoning.encrypted_content"]""", body["include"].toString())
        val input = body["input"]!!.jsonArray
        assertEquals("developer", input[0].jsonObject["role"]!!.jsonPrimitive.content)
        val content = input[1].jsonObject["content"]!!.jsonArray
        assertEquals("input_text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("input_image", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,QUJD", content[1].jsonObject["image_url"]!!.jsonPrimitive.content)
        assertTrue(input.none { it.jsonObject["role"]?.jsonPrimitive?.content == "system" })
        val t = body["tools"]!!.jsonArray.single().jsonObject
        assertEquals("function", t["type"]!!.jsonPrimitive.content)
        assertEquals("phone_tap", t["name"]!!.jsonPrimitive.content)
        assertEquals("false", t["strict"].toString())
        assertTrue(t["parameters"] is JsonObject)
    }

    @Test fun imagesBecomeAPlaceholderWhenTheModelCannotSee() = runTest {
        server.enqueue(stream(completed()))
        model(vision = false).complete("s", listOf(Message(Role.User, listOf(Part.Image("QUJD")), Author.User)), emptyList())
        val c = sentBody()["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray.single().jsonObject
        assertEquals("input_text", c["type"]!!.jsonPrimitive.content)
    }

    @Test fun streamsTextAndReadsFunctionCallsReasoningAndUsage() = runTest {
        server.enqueue(stream(
            """{"type":"response.created","response":{"status":"in_progress"}}""",
            """{"type":"response.output_item.done","item":{"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"想一下"}],"encrypted_content":"ENC"}}""",
            """{"type":"response.output_text.delta","delta":"先"}""",
            """{"type":"response.output_text.delta","delta":"点"}""",
            """{"type":"response.output_item.done","item":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"先点"}]}}""",
            """{"type":"response.output_item.done","item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"phone_tap","arguments":"{\"ref\":\"a1\"}"}}""",
            completed(),
        ))
        val updates = mutableListOf<String>()
        val reply = model().completeStreaming("s", user, listOf(tool), updates::add)
        assertEquals(listOf("先", "先点"), updates)
        assertEquals(Part.Thinking("想一下"), reply.parts[0])
        assertEquals(Part.Text("先点"), reply.parts[1])
        val call = reply.parts[2] as Part.ToolCall
        assertEquals(Triple("call_1", "phone_tap", """{"ref":"a1"}"""), Triple(call.id, call.name, call.argsJson))
        val saved = Json.parseToJsonElement(call.opaque!!).jsonArray.single().jsonObject
        assertEquals("ENC", saved["encrypted_content"]!!.jsonPrimitive.content)
        assertNull("编号只在服务端有意义,不回放", saved["id"])
        assertEquals(StopReason.ToolUse, reply.stopReason)
        assertEquals(Usage(12, 4, 8), reply.usage)
    }

    @Test fun replaysReasoningBeforeTheCallAndSendsToolResultsAsOutputs() = runTest {
        server.enqueue(stream(completed()))
        val reasoning = """[{"type":"reasoning","summary":[],"encrypted_content":"ENC"}]"""
        val history = user + listOf(
            Message(Role.Assistant, listOf(Part.Thinking("想一下"), Part.Text("先点"), Part.ToolCall("call_1", "phone_tap", "{}", reasoning)), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("call_1", "好了", isError = false)), Author.Mika),
        )
        model().complete("s", history, listOf(tool))
        val input = sentBody()["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("message", "reasoning", "message", "function_call", "function_call_output"), input.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("output_text", input[2]["content"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", input[3]["call_id"]!!.jsonPrimitive.content)
        assertNull(input[3]["id"])
        assertEquals("好了", input[4]["output"]!!.jsonPrimitive.content)
    }

    @Test fun textOnlyReplyEndsTheTurnAndKeepsNoReasoning() = runTest {
        server.enqueue(stream(
            """{"type":"response.output_item.done","item":{"type":"reasoning","encrypted_content":"ENC","summary":[]}}""",
            """{"type":"response.output_text.delta","delta":"好的"}""",
            completed(),
        ))
        val reply = model().complete("s", user, emptyList())
        assertEquals(listOf(Part.Text("好的")), reply.parts)
        assertEquals(StopReason.EndTurn, reply.stopReason)
    }

    @Test fun incompleteForOutputLimitIsMaxTokens() = runTest {
        server.enqueue(stream(
            """{"type":"response.output_text.delta","delta":"半"}""",
            """{"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"}}}""",
        ))
        val reply = model().complete("s", user, emptyList())
        assertEquals(StopReason.MaxTokens, reply.stopReason)
        assertEquals(listOf(Part.Text("半")), reply.parts)
    }

    @Test fun streamEndingBeforeCompletedIsANetworkFailure() = runTest {
        server.enqueue(stream("""{"type":"response.output_text.delta","delta":"半"}"""))
        expect<ModelError.Network> { model().complete("s", user, emptyList()) }
    }

    @Test fun usageErrorsInsideTheStreamAreMapped() = runTest {
        server.enqueue(stream("""{"type":"response.failed","response":{"status":"failed","error":{"code":"subscription_sharing_usage_limit_exceeded","message":"cap"}}}"""))
        expect<ModelError.ChatGptLimitReached> { model(plan = true).complete("s", user, emptyList()) }
        server.enqueue(stream("""{"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_unavailable","message":"later"}}}"""))
        val e = expect<ModelError.Server> { model(plan = true).complete("s", user, emptyList()) }
        assertEquals(503, e.status)
    }

    @Test fun planHttpErrorsAreNotRetryable() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"detail":"usage limit"}"""))
        expect<ModelError.ChatGptLimitReached> { model(plan = true).complete("s", user, emptyList()) }
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":"subscription_sharing_user_not_eligible","message":"plan"}}"""))
        val e = expect<ModelError.ChatGptNotEligible> { model(plan = true).complete("s", user, emptyList()) }
        assertEquals("plan", e.detail)
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":"subscription_sharing_unsupported_capability","param":"tools[0]"}}"""))
        val bad = expect<ModelError.BadResponse> { model(plan = true).complete("s", user, emptyList()) }
        assertTrue(bad.message!!.contains("tools[0]"))
        // 不用套餐额度的 429 照常是限流(会重试)
        server.enqueue(MockResponse().setResponseCode(429).setHeader("retry-after", "2").setBody("{}"))
        assertEquals(2000L, expect<ModelError.RateLimited> { model().complete("s", user, emptyList()) }.retryAfterMs)
        assertEquals(4, server.requestCount)
    }

    @Test fun rejectedTokenIsRefreshedOnceThenRetried() = runTest {
        val asked = mutableListOf<Boolean>()
        val token = object : BearerToken {
            override val refreshable = true
            override suspend fun get(fresh: Boolean): String { asked += fresh; return if (fresh) "new" else "old" }
        }
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        server.enqueue(stream("""{"type":"response.output_text.delta","delta":"好"}""", completed()))
        val reply = model(token).complete("s", user, emptyList())
        assertEquals(listOf(Part.Text("好")), reply.parts)
        assertEquals(listOf(false, true), asked)
        assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
        // 固定 Key 被拒不重试
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        expect<ModelError.Auth> { model().complete("s", user, emptyList()) }
        assertEquals(3, server.requestCount)
    }

    @Test fun dropsTheReasoningIncludeWhenTheServerRejectsIt() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":"unsupported_parameter","param":"include","message":"no"}}"""))
        server.enqueue(stream(completed()))
        model().complete("s", user, emptyList())
        assertTrue(sentBody().containsKey("include"))
        assertFalse(sentBody().containsKey("include"))
    }

    @Test fun refusalWithoutContentIsContentRefused() = runTest {
        server.enqueue(stream(
            """{"type":"response.output_item.done","item":{"type":"message","content":[{"type":"refusal","refusal":"no"}]}}""",
            completed(),
        ))
        expect<ModelError.ContentRefused> { model().complete("s", user, emptyList()) }
    }

    private suspend inline fun <reified T : ModelError> expect(block: () -> Unit): T {
        try {
            block()
        } catch (e: ModelError) {
            if (e is T) return e
            fail("expected ${T::class.simpleName}, got ${e::class.simpleName}: ${e.message}")
        }
        fail("expected ${T::class.simpleName}")
        throw AssertionError()
    }
}
