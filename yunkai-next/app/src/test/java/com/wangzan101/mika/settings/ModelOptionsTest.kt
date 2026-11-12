package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelEndpoint
import com.wangzan101.mika.core.model.ModelProtocol
import com.wangzan101.mika.core.model.PromptCaching
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelOptionsTest {
    private val base = Capabilities(toolCalls = true, vision = false, contextTokens = 64_000, reasoning = false, promptCaching = PromptCaching.Explicit)

    @Test fun unknownCapabilitiesKeepPresetValues() {
        val o = ModelOptions.merge(listOf(ListedModel("m")), base).single()
        assertEquals(base, o.capabilities)
        assertFalse(o.toolCallsKnown)
        assertFalse(o.visionKnown)
        assertFalse(o.contextKnown)
    }

    @Test fun knownCapabilitiesOverridePresetButKeepCaching() {
        val o = ModelOptions.merge(listOf(ListedModel("m", toolCalls = false, vision = true, contextTokens = 1_000_000, reasoning = true)), base).single()
        assertEquals(Capabilities(false, true, 1_000_000, true, promptCaching = PromptCaching.Explicit), o.capabilities)
        assertTrue(o.toolCallsKnown && o.visionKnown && o.contextKnown)
        assertFalse(o.canBeMain)
    }

    @Test fun toolCapableFirstAndDefaultMainPrefersConfirmed() {
        val listed = listOf(ListedModel("embed", toolCalls = false), ListedModel("guess"), ListedModel("sure", toolCalls = true))
        val options = ModelOptions.merge(listed, base)
        assertEquals(listOf("guess", "sure", "embed"), options.map { it.id })
        assertEquals("sure", ModelOptions.defaultMain(options))
        assertEquals("guess", ModelOptions.defaultMain(ModelOptions.merge(listOf(ListedModel("guess")), base)))
        assertEquals(null, ModelOptions.defaultMain(ModelOptions.merge(listOf(ListedModel("embed", toolCalls = false)), base)))
    }

    /** 先自家的(预设的模型词),再接口明说能调用工具的,其余照列表顺序;明说不会调用工具的、像向量 / 语音 / 画图的不试。 */
    @Test fun candidatesPreferTheProvidersOwnFamily() {
        val listed = listOf(
            ListedModel("antdigital/Ling-3.1-flash", toolCalls = true),
            ListedModel("text-embedding-v4"),
            ListedModel("gte-rerank-v2"),
            ListedModel("qwen3-tts-flash"),
            ListedModel("wanx-image", chat = false),
            ListedModel("qwen-max"),
            ListedModel("qwen-plus", toolCalls = true),
            ListedModel("Other/Chat", displayName = "Qwen inside"),
            ListedModel("qwen-coder", toolCalls = false),
            ListedModel("moonshot-v1"),
        )
        val options = ModelOptions.merge(listed, base)
        val qwen = ProviderPresets.all.single { it.label == "通义千问" }.familyTokens
        assertEquals(listOf("qwen"), qwen)
        assertEquals(
            listOf("qwen-plus", "qwen-max", "Other/Chat", "antdigital/Ling-3.1-flash", "moonshot-v1"),
            ModelOptions.candidates(options, qwen),
        )
        assertEquals("qwen-plus", ModelOptions.defaultMain(options, qwen))
        // 认不出是哪家:接口明说能调用工具的在前,其余照列表顺序
        assertEquals(listOf("antdigital/Ling-3.1-flash", "qwen-plus", "qwen-max", "Other/Chat", "moonshot-v1"), ModelOptions.candidates(options))
        assertEquals(qwen, ModelOptions.familyOf("https://dashscope.aliyuncs.com/compatible-mode/v1/"))
        assertEquals(emptyList<String>(), ModelOptions.familyOf("https://openrouter.ai/api/v1"))
    }

    @Test fun nonChatModelsAreRecognised() {
        fun opt(id: String, chat: Boolean? = null) = ModelOptions.merge(listOf(ListedModel(id, chat = chat)), base).single()
        for (id in listOf("text-embedding-3-small", "bge-reranker", "whisper-1", "gpt-4o-mini-tts", "gpt-4o-transcribe", "dall-e-3",
            "gpt-image-1", "qwen-vl-ocr", "paraformer-realtime-v2", "omni-moderation-latest", "qwen3-asr-flash")) {
            assertTrue(id, ModelOptions.looksNonChat(opt(id)))
        }
        for (id in listOf("qwen-plus", "deepseek-chat", "gpt-5-mini", "claude-sonnet-4-5", "qwen-vl-max", "step-2-16k")) {
            assertFalse(id, ModelOptions.looksNonChat(opt(id)))
        }
        assertFalse("接口说是对话模型就照接口", ModelOptions.looksNonChat(opt("my-embed-chat", chat = true)))
        assertTrue(ModelOptions.looksNonChat(opt("x", chat = false)))
        // 全都像别的用途:没有候选,默认主模型退回第一个可当主模型的
        val only = ModelOptions.merge(listOf(ListedModel("text-embedding-v4")), base)
        assertEquals(emptyList<String>(), ModelOptions.candidates(only))
        assertEquals("text-embedding-v4", ModelOptions.defaultMain(only))
    }

    @Test fun modelsOnlyReachableByUnsupportedProtocolsAreNotOffered() {
        val chat = ModelEndpoint(ModelProtocol.ChatCompletions, "https://gw.test/openai/v1")
        val listed = listOf(
            ListedModel("realtime-only", toolCalls = true, unsupportedApis = listOf("realtime")),
            ListedModel("chat", toolCalls = true, endpoint = chat),
            ListedModel("responses", toolCalls = true, endpoint = ModelEndpoint(ModelProtocol.Responses, "https://gw.test/openai/v1")),
            ListedModel("plain"),
        )
        val options = ModelOptions.merge(listed, base)
        assertEquals(listOf("chat", "responses", "plain"), options.map { it.id })
        assertEquals("chat", ModelOptions.defaultMain(options))
    }

    @Test fun openCodeGoPresetListsFromItsCatalogAddress() {
        val p = ProviderPresets.all.single { it.label == "OpenCode Go" }
        assertEquals(ProviderConfig.Kind.OpenAiCompat, p.kind)
        assertEquals("https://opencode.ai/zen/go/v1", p.baseUrl)
        assertEquals(p, ProviderPresets.forBaseUrl("https://opencode.ai/zen/go/v1/"))
    }

    @Test fun contextLabels() {
        assertEquals("64K", ModelOptions.contextLabel(64_000))
        assertEquals("128K", ModelOptions.contextLabel(131_072))
        assertEquals("200K", ModelOptions.contextLabel(200_000))
        assertEquals("1M", ModelOptions.contextLabel(1_048_576))
        assertEquals("2M", ModelOptions.contextLabel(2_000_000))
    }

    @Test fun sameProviderKeyIgnoresTrailingSlash() {
        assertEquals(
            ProviderConnection.keyOf(ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com", "DeepSeek"),
            ProviderConnection.keyOf(ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com/", "DeepSeek "),
        )
        val local = ProviderConfig("3", ProviderConfig.Kind.OpenAiCompat, "http://192.168.1.5:8000/v1", "local", base)
        assertEquals("192.168.1.5", ProviderConnection.displayName(local))
    }

    @Test fun providerOptionsAreRememberedListPlusSavedConfigs() {
        val p = Provider("p", "DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com", listOf(ListedModel("a"), ListedModel("b")))
        val savedCaps = base.copy(vision = true)
        val configs = listOf(
            ProviderConfig("1", p.kind, p.baseUrl, "b", savedCaps, p.name, "p"),
            ProviderConfig("2", p.kind, p.baseUrl, "typed", base, p.name, "p"),
            ProviderConfig("3", p.kind, p.baseUrl, "other", base, p.name, "q"),
        )
        val options = ModelOptions.ofProvider(p, configs)
        assertEquals(listOf("a", "b", "typed"), options.map { it.id })
        assertTrue("已有配置的用配置里的能力", options.first { it.id == "b" }.capabilities.vision)
        assertEquals(emptyList<String>(), ModelOptions.ofProvider(p.copy(models = null), emptyList()).map { it.id })
    }
}
