package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ListedModel
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 列表里的一个可选模型。[capabilities] 是合并后的能力:接口给了的照用,没给的沿用预设的初值;
 * [toolCallsKnown]、[visionKnown] 记下哪些是接口明确说的(界面上据此标「未确认」)。
 * [chat] 是接口说没说它是对话模型(见 [ListedModel.chat]),没说为 null。
 */
data class ModelOption(
    val id: String,
    val capabilities: Capabilities,
    val toolCallsKnown: Boolean,
    val visionKnown: Boolean,
    val contextKnown: Boolean,
    val displayName: String = "",
    val chat: Boolean? = null,
) {
    /** 供参考的能力标记;用户选模型时不按它们过滤。 */
    val canBeMain: Boolean get() = capabilities.toolCalls
    val canSee: Boolean get() = capabilities.vision
}

object ModelOptions {
    /**
     * 把列出的模型和预设能力合并。可当主模型的排前面,其余保持服务商给的顺序。
     * 只写了 Mika 不会说的接口协议的模型(见 [ListedModel.usable])调用必然失败,不列出来。
     */
    fun merge(listed: List<ListedModel>, base: Capabilities): List<ModelOption> = listed.filter { it.usable }.map { m ->
        ModelOption(
            id = m.id,
            capabilities = base.copy(
                toolCalls = m.toolCalls ?: base.toolCalls,
                vision = m.vision ?: base.vision,
                contextTokens = m.contextTokens ?: base.contextTokens,
                reasoning = m.reasoning ?: base.reasoning,
                // 看图定位准不准要实测过才算,新连的一律未验证
                groundingVerified = false,
            ),
            toolCallsKnown = m.toolCalls != null,
            visionKnown = m.vision != null,
            contextKnown = m.contextTokens != null,
            displayName = m.displayName,
            chat = m.chat,
        )
    }.sortedBy { if (it.canBeMain) 0 else 1 }

    /**
     * 拿来当主模型、试调用的先后:只要可当主模型、又不像别的用途的(见 [looksNonChat]);
     * 先自家的(模型名或显示名里有 [familyTokens] 里的词,见 [ProviderPreset.familyTokens]),
     * 再接口明确说能调用工具的,其余照列表的顺序。
     */
    fun candidates(options: List<ModelOption>, familyTokens: List<String> = emptyList()): List<String> =
        options.filter { it.canBeMain && !looksNonChat(it) }
            .sortedWith(compareBy({ if (inFamily(it, familyTokens)) 0 else 1 }, { if (it.toolCallsKnown) 0 else 1 }))
            .map { it.id }

    /** 默认选的主模型:[candidates] 的第一个;一个都没有就挑第一个可当主模型的。 */
    fun defaultMain(options: List<ModelOption>, familyTokens: List<String> = emptyList()): String? =
        candidates(options, familyTokens).firstOrNull() ?: options.firstOrNull { it.canBeMain }?.id

    /** 地址对得上的预设的自家模型词;认不出地址为空。 */
    fun familyOf(baseUrl: String): List<String> = ProviderPresets.forBaseUrl(baseUrl.trim())?.familyTokens.orEmpty()

    private fun inFamily(o: ModelOption, tokens: List<String>): Boolean =
        tokens.any { t -> o.id.contains(t, ignoreCase = true) || o.displayName.contains(t, ignoreCase = true) }

    /**
     * 看着不是对话模型(向量、重排、语音、画图、审核…):接口说了的照接口,没说看模型名按标点、斜杠切开的各段,
     * 有一段以 [NON_CHAT_WORDS] 里的词开头就算。
     */
    fun looksNonChat(o: ModelOption): Boolean =
        o.chat?.not() ?: o.id.lowercase().split(Regex("[^a-z0-9]+")).any { part -> NON_CHAT_WORDS.any { part.startsWith(it) } }

    private val NON_CHAT_WORDS = listOf(
        "embed", "rerank", "tts", "whisper", "transcribe", "speech", "audio", "realtime", "image", "dall", "moderation", "ocr", "asr",
    )

    /** 上下文长度的短写:64K、1M。 */
    fun contextLabel(tokens: Int): String = when {
        tokens >= 1_000_000 && tokens % 1_000_000 < 50_000 -> "${tokens / 1_000_000}M"
        tokens >= 1_000_000 -> String.format("%.1fM", tokens / 1_000_000.0)
        tokens >= 1000 -> if (tokens % 1024 == 0) "${tokens / 1024}K" else "${(tokens + 500) / 1000}K"
        else -> "$tokens"
    }

    /** 服务商的能力初值:地址对得上预设的用预设的,否则用「自定义」的。 */
    fun baseOf(p: Provider): Capabilities = (ProviderPresets.forBaseUrl(p.baseUrl) ?: ProviderPresets.custom).capabilities

    /**
     * 一个服务商可选的全部模型:记住的列表(合并能力初值;已有配置的用配置里的能力),
     * 再加上已有配置、但列表里没有的模型(例如手动填的)。
     */
    fun ofProvider(p: Provider, configs: List<ProviderConfig>): List<ModelOption> {
        val saved = configs.filter { it.providerId == p.id }
        val byModel = saved.associateBy { it.model }
        val listed = merge(p.models.orEmpty(), baseOf(p)).map { o -> byModel[o.id]?.let { o.copy(capabilities = it.capabilities) } ?: o }
        val rest = saved.filter { c -> listed.none { it.id == c.model } }.map {
            ModelOption(it.model, it.capabilities, toolCallsKnown = false, visionKnown = false, contextKnown = false)
        }
        return listed + rest
    }
}

/** 服务商的归组规则和显示名:同一接口类型、同一地址、同一名字算同一个服务商。 */
object ProviderConnection {
    fun keyOf(kind: ProviderConfig.Kind, baseUrl: String, name: String) = "${kind.name}|${normalizeUrl(baseUrl)}|${name.trim()}"

    /** 没起名字的用地址的主机名。 */
    fun displayName(c: ProviderConfig): String = c.name.ifBlank { nameForUrl(c.baseUrl) }

    fun nameForUrl(baseUrl: String): String =
        ProviderPresets.forBaseUrl(baseUrl)?.label ?: baseUrl.trim().toHttpUrlOrNull()?.host ?: baseUrl.trim()

    fun normalizeUrl(url: String) = url.trim().trimEnd('/')
}
