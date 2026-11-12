package com.wangzan101.mika.settings

import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.ui.AddForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProviderPresetsTest {
    @get:Rule val texts = ChineseTexts()

    private fun preset(label: String) = ProviderPresets.all.single { it.label == label }

    /** 已有的几家次序不变;新加的国内几家接在国内的后面,国外的接在国外的后面,自己跑的在「自定义」前面。 */
    @Test fun presetsInOrder() {
        assertEquals(
            listOf(
                "DeepSeek", "通义千问", "Kimi", "智谱",
                "硅基流动", "火山方舟", "MiniMax", "阶跃星辰", "腾讯混元", "百度千帆",
                "OpenRouter", "OpenCode Go", "Anthropic", "Gemini",
                "OpenAI", "xAI", "Mistral", "Groq",
                "Ollama", "LM Studio",
                "自定义",
            ),
            ProviderPresets.all.map { it.label },
        )
        assertEquals(ProviderPresets.custom, ProviderPresets.all.last())
        assertEquals("", ProviderPresets.custom.baseUrl)
    }

    /** 名字、接口类型、地址都照各家文档。 */
    @Test fun newPresetsUseTheDocumentedEndpoints() {
        val expected = mapOf(
            "硅基流动" to "https://api.siliconflow.cn/v1",
            "火山方舟" to "https://ark.cn-beijing.volces.com/api/v3",
            "MiniMax" to "https://api.minimax.cn/v1",
            "阶跃星辰" to "https://api.stepfun.com/v1",
            "腾讯混元" to "https://tokenhub.tencentmaas.com/v1",
            "百度千帆" to "https://qianfan.baidubce.com/v2",
            "OpenAI" to "https://api.openai.com/v1",
            "xAI" to "https://api.x.ai/v1",
            "Mistral" to "https://api.mistral.ai/v1",
            "Groq" to "https://api.groq.com/openai/v1",
            "Ollama" to "http://127.0.0.1:11434/v1",
            "LM Studio" to "http://127.0.0.1:1234/v1",
        )
        expected.forEach { (label, url) -> assertEquals(label, url, preset(label).baseUrl) }
        // OpenAI 的新主力模型调用工具要走 Responses;别的都是 OpenAI 兼容
        assertEquals(ProviderConfig.Kind.OpenAiResponses, preset("OpenAI").kind)
        (expected.keys - "OpenAI").forEach { assertEquals(it, ProviderConfig.Kind.OpenAiCompat, preset(it).kind) }
        expected.keys.forEach { assertTrue(it, preset(it).capabilities.toolCalls) }
    }

    /** 地址、名字不重复(按地址认预设);云端的都走 https,明文 http 只给自己机器上跑的。 */
    @Test fun urlsAreUniqueAndOnlySelfHostedUsePlainHttp() {
        val withUrl = ProviderPresets.all.filter { it.baseUrl.isNotEmpty() }
        assertEquals(withUrl.size, withUrl.map { it.baseUrl }.distinct().size)
        assertEquals(ProviderPresets.all.size, ProviderPresets.all.map { it.label }.distinct().size)
        withUrl.forEach { p ->
            if (p.selfHosted) assertTrue(p.label, p.baseUrl.startsWith("http://127.0.0.1:"))
            else assertTrue(p.label, p.baseUrl.startsWith("https://"))
            assertEquals(p, ProviderPresets.forBaseUrl(p.baseUrl))
        }
        assertEquals(listOf("Ollama", "LM Studio"), ProviderPresets.all.filter { it.selfHosted }.map { it.label })
    }

    /** 自己跑的服务:地址可改、Key 可不填,名字仍用预设名;云端预设地址不给改、Key 必填。 */
    @Test fun selfHostedPresetsHaveEditableUrlAndOptionalKey() {
        val ollama = AddForm.of(preset("Ollama")).copy(baseUrl = "http://192.168.1.20:11434/v1")
        assertTrue(ollama.urlEditable)
        assertFalse(ollama.keyRequired)
        assertTrue("不填 Key 也能测试", ollama.canTest)
        assertEquals("Ollama", ollama.providerName)
        val openAi = AddForm.of(preset("OpenAI"))
        assertFalse(openAi.urlEditable)
        assertTrue(openAi.keyRequired)
        assertFalse(openAi.canTest)
        assertTrue(openAi.copy(apiKey = "sk").canTest)
    }

    /** 主机名对得上就认出是哪家(路径不论);自己跑的服务、认不出的地址不算。 */
    @Test fun presetByHost() {
        assertEquals("Gemini", ProviderPresets.forHost("https://generativelanguage.googleapis.com")?.label)
        assertEquals("Gemini", ProviderPresets.forHost(" https://generativelanguage.googleapis.com/v1beta/openai/ ")?.label)
        assertEquals("Anthropic", ProviderPresets.forHost("https://api.anthropic.com/v1")?.label)
        assertEquals("OpenAI", ProviderPresets.forHost("https://api.openai.com")?.label)
        assertEquals("DeepSeek", ProviderPresets.forHost("https://api.deepseek.com/v1")?.label)
        assertNull(ProviderPresets.forHost("https://models.example.com/v1"))
        assertNull(ProviderPresets.forHost("http://127.0.0.1:11434/v1"))
        assertNull(ProviderPresets.forHost("not a url"))
        // 每个有地址、不是自己跑的预设都认得出自己
        ProviderPresets.all.filter { it.baseUrl.isNotEmpty() && !it.selfHosted }.forEach {
            assertEquals(it.label, ProviderPresets.forHost(it.baseUrl)?.label)
        }
    }
}
