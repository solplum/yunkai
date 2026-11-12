package com.wangzan101.mika.core.model

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StreamingModelsTest {
    private lateinit var server: MockWebServer
    private val caps = Capabilities(true, false, 32000, false)
    private val user = listOf(Message(Role.User, listOf(Part.Text("hi")), Author.User))

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun event(data: String) = "data: $data\n\n"
    private fun stream(vararg data: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(data.joinToString("") { event(it) })

    @Test fun openAiStreamsTextAndAssemblesSplitToolCall() = runTest {
        server.enqueue(stream(
            """{"choices":[{"delta":{"reasoning_content":"private","content":"先"}}]}""",
            """{"choices":[{"delta":{"content":"看","tool_calls":[{"index":0,"id":"c1","function":{"name":"phone_","arguments":"{\"re"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"tap","arguments":"f\":\"a1\"}"}}]},"finish_reason":"tool_calls"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":3}}""",
            "[DONE]",
        ))
        val updates = mutableListOf<String>()
        val model = OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "k", "m", caps, OkHttpClient())
        val reply = model.completeStreaming("s", user, emptyList(), updates::add)
        assertEquals(listOf("先", "先看"), updates)
        val thinking = Part.Thinking("private", ReasoningVia.ReasoningContent.tag(server.url("/v1/chat/completions").toString()))
        assertEquals(listOf(thinking, Part.Text("先看"), Part.ToolCall("c1", "phone_tap", """{"ref":"a1"}""")), reply.parts)
        assertEquals(StopReason.ToolUse, reply.stopReason)
        assertEquals(Usage(10, 3), reply.usage)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"stream\":true"))
    }

    /** 流式里思考在 `reasoning` 字段、或 `content` 是分块数组的,都认出来,记下收到的位置。 */
    @Test fun openAiStreamReadsReasoningFieldAndChunkedContent() = runTest {
        val model = OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "k", "m", caps, OkHttpClient())
        val endpoint = server.url("/v1/chat/completions").toString()
        server.enqueue(stream(
            """{"choices":[{"delta":{"reasoning":"想"}}]}""",
            """{"choices":[{"delta":{"reasoning":"好了","content":"答"}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            "[DONE]",
        ))
        val reply = model.completeStreaming("s", user, emptyList()) {}
        assertEquals(listOf(Part.Thinking("想好了", ReasoningVia.Reasoning.tag(endpoint)), Part.Text("答")), reply.parts)

        server.enqueue(stream(
            """{"choices":[{"delta":{"content":[{"type":"thinking","thinking":[{"type":"text","text":"先"}]}]}}]}""",
            """{"choices":[{"delta":{"content":[{"type":"thinking","thinking":[{"type":"text","text":"想"}]}]}}]}""",
            """{"choices":[{"delta":{"content":"答案"}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            "[DONE]",
        ))
        val updates = mutableListOf<String>()
        val chunked = model.completeStreaming("s", user, emptyList(), updates::add)
        assertEquals(listOf(Part.Thinking("先想", ReasoningVia.ThinkingChunks.tag(endpoint)), Part.Text("答案")), chunked.parts)
        assertEquals(listOf("答案"), updates)
    }

    @Test fun anthropicStreamsTextAndToolInput() = runTest {
        server.enqueue(stream(
            """{"type":"message_start","message":{"usage":{"input_tokens":10,"output_tokens":0}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"好"}}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"t1","name":"phone_tap","input":{}}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"ref\":\"a1\"}"}}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":5}}""",
            """{"type":"message_stop"}""",
        ))
        val updates = mutableListOf<String>()
        val model = AnthropicModel(server.url("/").toString().trimEnd('/'), "k", "m", caps, OkHttpClient())
        val reply = model.completeStreaming("s", user, emptyList(), updates::add)
        assertEquals(listOf("好"), updates)
        assertEquals(listOf(Part.Text("好"), Part.ToolCall("t1", "phone_tap", """{"ref":"a1"}""")), reply.parts)
        assertEquals(Usage(10, 5), reply.usage)
    }

    @Test fun geminiStreamsTextAndKeepsExplicitCallId() = runTest {
        server.enqueue(stream(
            """{"candidates":[{"content":{"parts":[{"text":"你"}]}}]}""",
            """{"candidates":[{"content":{"parts":[{"text":"好"},{"functionCall":{"id":"call9","name":"phone_tap","args":{}}}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":7,"candidatesTokenCount":3}}""",
        ))
        val updates = mutableListOf<String>()
        val model = GeminiModel(server.url("/").toString().trimEnd('/'), "k", "m", caps, OkHttpClient())
        val reply = model.completeStreaming("s", user, emptyList(), updates::add)
        assertEquals(listOf("你", "你好"), updates)
        assertEquals(listOf(Part.Text("你好"), Part.ToolCall("call9", "phone_tap", "{}")), reply.parts)
        assertEquals(StopReason.ToolUse, reply.stopReason)
        assertEquals("/v1beta/models/m:streamGenerateContent?alt=sse", server.takeRequest().path)
    }

    @Test fun truncatedStreamCannotBecomeFinalAnswer() = runTest {
        server.enqueue(stream("""{"choices":[{"delta":{"content":"半句"}}]}"""))
        val model = OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "k", "m", caps, OkHttpClient())
        try {
            model.completeStreaming("s", user, emptyList()) {}
            throw AssertionError("应拒绝不完整响应")
        } catch (e: ModelError.BadResponse) {
            assertTrue(e.message.orEmpty().contains("提前结束"))
        }
    }

    @Test fun openAiCompatibleEndpointCanStreamWithoutUsageOption() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("unknown field stream_options"))
        server.enqueue(stream(
            """{"choices":[{"delta":{"content":"继续"},"finish_reason":"stop"}]}""",
            "[DONE]",
        ))
        val model = OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "k", "m", caps, OkHttpClient())
        val updates = mutableListOf<String>()
        assertEquals(listOf(Part.Text("继续")), model.completeStreaming("s", user, emptyList(), updates::add).parts)
        assertEquals(listOf("继续"), updates)
        assertTrue(server.takeRequest().body.readUtf8().contains("stream_options"))
        val retried = server.takeRequest().body.readUtf8()
        assertTrue(retried.contains("\"stream\":true"))
        assertTrue(!retried.contains("stream_options"))
    }
}
