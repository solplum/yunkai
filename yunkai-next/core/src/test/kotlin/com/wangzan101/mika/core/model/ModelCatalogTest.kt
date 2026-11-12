package com.wangzan101.mika.core.model

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ModelCatalogTest {
    private lateinit var server: MockWebServer
    private val catalog = ModelCatalog(OkHttpClient())

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private val base get() = server.url("/").toString().trimEnd('/')
    private fun ok(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun models(r: ModelListResult) = (r as ModelListResult.Ok).models
    private fun failure(r: ModelListResult) = (r as ModelListResult.Failed).reason

    @Test fun openAiCompatPlainListLeavesCapabilitiesUnknown() = runTest {
        server.enqueue(ok("""{"object":"list","data":[{"id":"deepseek-chat","object":"model"},{"id":"deepseek-reasoner","object":"model"}]}"""))
        val list = models(catalog.openAiCompat("$base/v1/", "sk"))
        assertEquals(listOf(ListedModel("deepseek-chat"), ListedModel("deepseek-reasoner")), list)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/v1/models", req.path)
        assertEquals("Bearer sk", req.getHeader("Authorization"))
    }

    @Test fun openAiCompatReadsCapabilitiesWhenGiven() = runTest {
        server.enqueue(ok("""{"data":[
            {"id":"a/vis","name":"Vis","context_length":131072,"architecture":{"input_modalities":["text","image"]},"supported_parameters":["tools","reasoning"]},
            {"id":"b/text","context_length":8192,"architecture":{"modality":"text->text"},"supported_parameters":["temperature"]}
        ]}"""))
        val (vis, text) = models(catalog.openAiCompat(base, null))
        assertEquals(ListedModel("a/vis", "Vis", toolCalls = true, vision = true, contextTokens = 131072, reasoning = true), vis)
        assertEquals(ListedModel("b/text", toolCalls = false, vision = false, contextTokens = 8192, reasoning = false), text)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test fun anthropicPagesAndReadsCapabilities() = runTest {
        server.enqueue(ok("""{"data":[{"type":"model","id":"claude-a","display_name":"A","max_input_tokens":1000000,
            "capabilities":{"image_input":{"supported":true},"thinking":{"supported":true}}}],"has_more":true,"last_id":"claude-a"}"""))
        server.enqueue(ok("""{"data":[{"type":"model","id":"claude-b","display_name":"B"}],"has_more":false,"last_id":"claude-b"}"""))
        val list = models(catalog.anthropic(base, "ak"))
        assertEquals(
            listOf(
                ListedModel("claude-a", "A", toolCalls = null, vision = true, contextTokens = 1_000_000, reasoning = true),
                ListedModel("claude-b", "B"),
            ),
            list,
        )
        val first = server.takeRequest()
        assertEquals("/v1/models?limit=1000", first.path)
        assertEquals("ak", first.getHeader("x-api-key"))
        assertEquals("2023-06-01", first.getHeader("anthropic-version"))
        assertEquals("/v1/models?limit=1000&after_id=claude-a", server.takeRequest().path)
    }

    /** 按协议逐个给地址的列表:形状照一家订阅服务的真实返回删减而来。 */
    private val perModelApi = """{"object":"list","data":[
        {"id":"deepseek-v4.1-flash","object":"model","name":"DeepSeek V4.1 Flash","type":"chat",
         "modalities":{"input":["text","image/jpeg","image/png"],"output":["text"]},
         "capabilities":{"tools":{"supported":true},"reasoning":{"supported":true,"effort":["low","high"]}},
         "limit":{"context":1000000,"output":384000},
         "api":{"chat-completions":{"base_url":"https://gw.test/inference/openai/v1",
                "compatibility":{"reasoning_field":"reasoning_content"},"capabilities":{"tools":{"choice":["auto","none"]}}}}},
        {"id":"glm-5.3","object":"model","name":"GLM-5.3",
         "modalities":{"input":["text"],"output":["text"]},
         "capabilities":{"tools":{"supported":true},"reasoning":{"supported":true}},"limit":{"context":1000000},
         "api":{"chat-completions":{"base_url":"https://gw.test/inference/openai/v1"}}},
        {"id":"grok-4.7","object":"model","name":"Grok 4.7",
         "modalities":{"input":["text","image/png"],"output":["text"]},
         "capabilities":{"tools":{"supported":true}},"limit":{"context":500000},
         "api":{"responses":{"base_url":"https://gw.test/inference/openai/v1"}}},
        {"id":"claude-haiku-5-5","object":"model","name":"Claude Haiku 5.5",
         "modalities":{"input":["text","image/webp"],"output":["text"]},
         "capabilities":{"tools":{"supported":true},"reasoning":{"supported":true}},"limit":{"context":1000000},
         "api":{"messages":{"base_url":"https://gw.test/inference/anthropic/v1"}}},
        {"id":"gemini-4-argon","object":"model","name":"Gemini 4 Argon",
         "modalities":{"input":["text","image/png","video/mp4"],"output":["text"]},
         "capabilities":{"tools":{"supported":true},"reasoning":{"supported":true}},"limit":{"context":1048576},
         "api":{"generate-content":{"base_url":"https://gw.test/inference/google/v1beta"}}},
        {"id":"both","api":{"responses":{"base_url":"https://gw.test/r"},"messages":{"base_url":"https://gw.test/m/v1"}}}
    ]}"""

    @Test fun openAiCompatReadsPerModelProtocolsAndCapabilities() = runTest {
        server.enqueue(ok(perModelApi))
        val list = models(catalog.openAiCompat("$base/zen/v1", "k"))
        assertEquals("/zen/v1/models", server.takeRequest().path)
        val chat = ModelEndpoint(ModelProtocol.ChatCompletions, "https://gw.test/inference/openai/v1", "reasoning_content")
        assertEquals(
            listOf(
                ListedModel("deepseek-v4.1-flash", "DeepSeek V4.1 Flash", toolCalls = true, vision = true, contextTokens = 1_000_000,
                    reasoning = true, endpoint = chat, chat = true),
                ListedModel("glm-5.3", "GLM-5.3", toolCalls = true, vision = false, contextTokens = 1_000_000, reasoning = true,
                    endpoint = chat.copy(reasoningField = null)),
                ListedModel("grok-4.7", "Grok 4.7", toolCalls = true, vision = true, contextTokens = 500_000,
                    endpoint = ModelEndpoint(ModelProtocol.Responses, "https://gw.test/inference/openai/v1")),
                ListedModel("claude-haiku-5-5", "Claude Haiku 5.5", toolCalls = true, vision = true, contextTokens = 1_000_000,
                    reasoning = true, endpoint = ModelEndpoint(ModelProtocol.Messages, "https://gw.test/inference/anthropic/v1")),
                ListedModel("gemini-4-argon", "Gemini 4 Argon", toolCalls = true, vision = true, contextTokens = 1_048_576,
                    reasoning = true, endpoint = ModelEndpoint(ModelProtocol.GenerateContent, "https://gw.test/inference/google/v1beta")),
                // 写了几种协议的,取会说的那一种
                ListedModel("both", endpoint = ModelEndpoint(ModelProtocol.Messages, "https://gw.test/m/v1")),
            ),
            list,
        )
        assertEquals(listOf(true, true, true, true, true, true), list.map { it.usable })
    }

    /** 列表写明了不是对话模型的(`type`,或输出里没有文字):记下来,选默认主模型时跳过。 */
    @Test fun openAiCompatReadsWhetherAModelChats() = runTest {
        server.enqueue(ok("""{"data":[
            {"id":"e","type":"embedding"},{"id":"r","type":"rerank"},{"id":"c","type":"chat"},
            {"id":"img","architecture":{"modality":"text->image"}},{"id":"o","architecture":{"output_modalities":["image"]}},
            {"id":"t","architecture":{"output_modalities":["text"]}},{"id":"plain"}
        ]}"""))
        val list = models(catalog.openAiCompat(base, null))
        assertEquals(listOf(false, false, true, false, false, null, null), list.map { it.chat })
    }

    @Test fun chatGptModelListKeepsListedSlugsInServerOrder() = runTest {
        server.enqueue(ok("""{"models":[
            {"slug":"gpt-b","display_name":"GPT B","visibility":"list","input_modalities":["text","image"],
             "context_window":272000,"supported_reasoning_levels":[{"effort":"low"}]},
            {"slug":"gpt-hidden","display_name":"Hidden","visibility":"hide"},
            {"slug":"gpt-a","display_name":"GPT A","visibility":"list","input_modalities":["text"]},
            {"slug":"odd","api":{"realtime":{"base_url":"https://x.test"}}}
        ]}"""))
        val list = models(catalog.openAiCompat("$base/v1", "token"))
        assertEquals("Bearer token", server.takeRequest().getHeader("Authorization"))
        assertEquals(
            listOf(
                ListedModel("gpt-b", "GPT B", vision = true, contextTokens = 272_000, reasoning = true),
                ListedModel("gpt-a", "GPT A", vision = false),
                ListedModel("odd", unsupportedApis = listOf("realtime")),
            ),
            list,
        )
        assertEquals(listOf(true, true, false), list.map { it.usable })
    }

    @Test fun adapterBaseUrlDropsTheVersionPathTheAdapterAddsItself() {
        assertEquals("https://gw.test/openai/v1", ModelEndpoint(ModelProtocol.ChatCompletions, "https://gw.test/openai/v1/").adapterBaseUrl)
        assertEquals("https://gw.test/anthropic", ModelEndpoint(ModelProtocol.Messages, "https://gw.test/anthropic/v1").adapterBaseUrl)
        assertEquals("https://gw.test/anthropic", ModelEndpoint(ModelProtocol.Messages, "https://gw.test/anthropic").adapterBaseUrl)
        assertEquals("https://gw.test/google", ModelEndpoint(ModelProtocol.GenerateContent, "https://gw.test/google/v1beta/").adapterBaseUrl)
        assertEquals("https://gw.test/openai/v1", ModelEndpoint(ModelProtocol.Responses, "https://gw.test/openai/v1/").adapterBaseUrl)
    }

    @Test fun eachAdapterCallsTheListedAddressWithoutDoublingTheVersion() = runTest {
        val caps = Capabilities(toolCalls = true, vision = false, contextTokens = 64_000, reasoning = false)
        val user = listOf(Message(Role.User, listOf(Part.Text("hi")), Author.User))
        val http = OkHttpClient()

        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        val chat = ModelEndpoint(ModelProtocol.ChatCompletions, "$base/inference/openai/v1")
        OpenAiCompatModel(chat.adapterBaseUrl, "k", "glm", caps, http).complete("s", user, emptyList())
        assertEquals("/inference/openai/v1/chat/completions", server.takeRequest().path)

        server.enqueue(ok("""{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}"""))
        val messages = ModelEndpoint(ModelProtocol.Messages, "$base/inference/anthropic/v1")
        AnthropicModel(messages.adapterBaseUrl, "k", "claude", caps, http).complete("s", user, emptyList())
        assertEquals("/inference/anthropic/v1/messages", server.takeRequest().path)

        server.enqueue(ok("""{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]}"""))
        val generate = ModelEndpoint(ModelProtocol.GenerateContent, "$base/inference/google/v1beta")
        GeminiModel(generate.adapterBaseUrl, "k", "gem", caps, http).complete("s", user, emptyList())
        assertEquals("/inference/google/v1beta/models/gem:generateContent", server.takeRequest().path)
    }

    @Test fun geminiKeepsOnlyGenerativeModelsAndPages() = runTest {
        server.enqueue(ok("""{"models":[
            {"name":"models/gemini-x","displayName":"X","inputTokenLimit":1048576,"supportedGenerationMethods":["generateContent","countTokens"],"thinking":true},
            {"name":"models/embed","supportedGenerationMethods":["embedContent"]}
        ],"nextPageToken":"p2"}"""))
        server.enqueue(ok("""{"models":[{"name":"models/gemini-y","supportedGenerationMethods":["generateContent"]}]}"""))
        val list = models(catalog.gemini(base, "gk"))
        assertEquals(listOf(ListedModel("gemini-x", "X", contextTokens = 1_048_576, reasoning = true), ListedModel("gemini-y")), list)
        val first = server.takeRequest()
        assertEquals("/v1beta/models?pageSize=1000", first.path)
        assertEquals("gk", first.getHeader("x-goog-api-key"))
        assertEquals("/v1beta/models?pageSize=1000&pageToken=p2", server.takeRequest().path)
    }

    @Test fun mapsHttpFailuresToReasons() = runTest {
        val cases = listOf(
            401 to ListFailure.Auth, 403 to ListFailure.Auth, 402 to ListFailure.Quota, 429 to ListFailure.RateLimited,
            404 to ListFailure.NotSupported, 503 to ListFailure.Server, 418 to ListFailure.BadResponse,
        )
        for ((status, reason) in cases) {
            server.enqueue(MockResponse().setResponseCode(status).setBody("{}"))
            assertEquals("HTTP $status", reason, failure(catalog.openAiCompat(base, "sk")))
        }
    }

    @Test fun geminiInvalidKeyIsAuth() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"""))
        assertEquals(ListFailure.Auth, failure(catalog.gemini(base, "bad")))
    }

    @Test fun nonJsonBodyIsBadResponse() = runTest {
        server.enqueue(MockResponse().setBody("<html>hello</html>"))
        assertEquals(ListFailure.BadResponse, failure(catalog.openAiCompat(base, "sk")))
        server.enqueue(ok("""{"object":"list"}"""))
        assertEquals(ListFailure.BadResponse, failure(catalog.openAiCompat(base, "sk")))
    }

    @Test fun networkAndUrlFailures() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(ListFailure.Network, failure(catalog.openAiCompat(base, "sk")))
        assertEquals(ListFailure.BadUrl, failure(catalog.openAiCompat("api.example.com", "sk")))
        assertEquals(ListFailure.BadUrl, failure(catalog.anthropic("", "ak")))
    }
}
