package com.wangzan101.mika.core.testing

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 假模型收到的一次请求(消息列表是当时的副本)。 */
data class FakeRequest(val system: String, val messages: List<Message>, val tools: List<ToolSpec>) {
    val toolNames: List<String> get() = tools.map { it.name }
}

/**
 * 按剧本回复的模型:每次请求取剧本里的下一项,返回回复或抛出 [ModelError];收到的每次请求都记在 [requests]。
 * 剧本用完还被调用就报断言错误(说明循环比剧本多问了一次)。用 [fakeModel] 写剧本。
 */
class FakeChatModel(
    override val name: String,
    override val capabilities: Capabilities,
    script: List<(FakeRequest) -> ModelReply>,
) : ChatModel {
    private val queue = ArrayDeque(script)
    val requests = mutableListOf<FakeRequest>()
    /** 剧本里还没用到的项数;场景结束时应为 0。 */
    val remaining: Int get() = queue.size

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        val req = FakeRequest(system, messages.toList(), tools.toList())
        requests += req
        val next = queue.removeFirstOrNull() ?: throw AssertionError("$name 的剧本用完了:第 ${requests.size} 次请求没有准备回复")
        return next(req)
    }
}

fun fakeModel(
    name: String = "fake",
    contextTokens: Int = 128_000,
    vision: Boolean = false,
    toolCalls: Boolean = true,
    script: FakeScript.() -> Unit,
): FakeChatModel = FakeChatModel(
    name, Capabilities(toolCalls = toolCalls, vision = vision, contextTokens = contextTokens, reasoning = false),
    FakeScript(name).apply(script).items.toList(),
)

/** 剧本:一项对应一次请求。 */
class FakeScript internal constructor(private val prefix: String) {
    internal val items = mutableListOf<(FakeRequest) -> ModelReply>()
    private var seq = 0
    private fun nextId() = "${prefix}_${++seq}"

    /** 一次回复;不写 [stop] 时有工具调用为 ToolUse,否则 EndTurn。 */
    fun reply(stop: StopReason? = null, build: ReplyBuilder.() -> Unit) {
        items += { ReplyBuilder(::nextId).apply(build).build(stop) }
    }

    /** 按收到的请求现算回复(例如要用上一次结果里的 snapshotId)。 */
    fun replyTo(stop: StopReason? = null, build: ReplyBuilder.(FakeRequest) -> Unit) {
        items += { req -> ReplyBuilder(::nextId).apply { build(req) }.build(stop) }
    }

    fun call(name: String, vararg args: Pair<String, Any?>) = reply { call(name, *args) }
    fun text(vararg chunks: String) = reply { text(*chunks) }
    /** 不带调用的回复:交给用户的最终回答。 */
    fun answer(text: String) = text(text)
    fun reportFailure(message: String) = call("end_not_done", "message" to message)

    /** 输出到上限被截断:只有半截文字,停止原因 MaxTokens。 */
    fun truncated(partial: String) = reply(StopReason.MaxTokens) { text(partial) }

    /** 参数不是合法 JSON 的工具调用。 */
    fun malformedCall(name: String, rawArgs: String = "{\"ref\":") = reply { callJson(name, rawArgs) }

    fun error(e: ModelError) { items += { throw e } }
    fun serverError(status: Int = 503) = error(ModelError.Server(status))
    fun rateLimited(retryAfterMs: Long? = 0) = error(ModelError.RateLimited(retryAfterMs))
    fun networkError() = error(ModelError.Network())
    fun contextOverflow() = error(ModelError.ContextTooLong())

    /** 辅助角色:核查结论;[met] 是逐条标准与屏上的引文。 */
    fun verdict(verdict: String, reason: String, vararg met: Pair<String, String?>) = text(buildJsonObject {
        put("checks", buildJsonArray {
            met.forEach { (c, q) -> add(buildJsonObject { put("criterion", c); put("status", if (verdict == "fail") "unmet" else "met"); put("quote", q) }) }
        })
        put("verdict", verdict); put("reason", reason)
    }.toString())

    fun repeat(times: Int, block: FakeScript.() -> Unit) { kotlin.repeat(times) { block() } }
}

class ReplyBuilder internal constructor(private val nextId: () -> String) {
    private val parts = mutableListOf<Part>()
    private var usage: Usage? = null

    /** 每块一个文字片段,模拟分块到达的文字(循环按顺序拼接)。 */
    fun text(vararg chunks: String) { chunks.forEach { parts += Part.Text(it) } }
    fun thinking(text: String) { parts += Part.Thinking(text) }
    fun call(name: String, vararg args: Pair<String, Any?>) { callJson(name, JsonObject(args.associate { (k, v) -> k to json(v) }).toString()) }
    fun callJson(name: String, argsJson: String, opaque: String? = null) { parts += Part.ToolCall(nextId(), name, argsJson, opaque) }
    fun usage(input: Int, output: Int, cached: Int = 0) { usage = Usage(input, output, cached) }

    internal fun build(stop: StopReason?) =
        ModelReply(parts.toList(), stop ?: if (parts.any { it is Part.ToolCall }) StopReason.ToolUse else StopReason.EndTurn, usage)
}

private fun json(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is List<*> -> JsonArray(v.map(::json))
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to json(x) })
    else -> error("不支持的参数类型:${v::class}")
}
