package com.wangzan101.mika.task

import com.wangzan101.mika.DeviceTaskState
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.verify.Verifier
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/** 真机测试用的脚本模型:每次 complete 先报到(第几次、收到的历史),再等测试喂下一步工具调用。 */
class ScriptedModel : ChatModel {
    class Call(val number: Int, val messages: List<Message>)

    override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 64_000, reasoning = false)
    private val calls = Channel<Call>(Channel.UNLIMITED)
    private val replies = Channel<List<Part>>(Channel.UNLIMITED)
    private val n = AtomicInteger()
    /** 收到的完成前核查请求次数。 */
    val verifierRequests = AtomicInteger()
    /** 停止后写交代的请求次数;回 [stopSummary](默认空白,交代按记录直接拼),不占脚本也不计次。 */
    val summaryRequests = AtomicInteger()
    @Volatile var stopSummary: String = ""
    /** 核查的固定回答;默认通过。 */
    @Volatile var verdict: String = PASS
    /**
     * 从界面发起的任务在提议第一个工具调用时,会另问一次模型「用户是在讨论、要读还是要操作」;没配轻量模型时问的就是这个模型。
     * 设了它,这一问固定这样回答(ACT / READ / DISCUSS),不占脚本也不计次;为 null 时这一问和别的调用一样要测试来喂。
     */
    @Volatile var intent: String? = null

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        // 回答交出前的核查:固定回答,不占脚本也不计次
        if (system == Verifier.PROMPT) {
            verifierRequests.incrementAndGet()
            return ModelReply(listOf(Part.Text(verdict)), StopReason.EndTurn, null)
        }
        if (system == SUMMARY_PROMPT) {
            summaryRequests.incrementAndGet()
            return ModelReply(listOf(Part.Text(stopSummary)), StopReason.EndTurn, null)
        }
        // 给新对话起标题:固定回答,不占脚本也不计次
        if (system == TITLE_PROMPT) return ModelReply(listOf(Part.Text("测试对话")), StopReason.EndTurn, null)
        intent?.let { if (system == INTENT_PROMPT) return ModelReply(listOf(Part.Text(it)), StopReason.EndTurn, null) }
        calls.send(Call(n.incrementAndGet(), messages))
        val parts = replies.receive()
        return ModelReply(parts, if (parts.any { it is Part.ToolCall }) StopReason.ToolUse else StopReason.EndTurn, null)
    }

    /** [say] 是模型在这一步顺带说的话(界面上显示在步骤下面)。 */
    fun feed(name: String, args: String = "{}", say: String? = null) =
        replies.trySend(listOfNotNull(say?.let { Part.Text(it) }, Part.ToolCall("c${n.get()}", name, args)))

    /** 不带调用的回复:交给用户的最终回答,任务就此结束(核对没对上时会被退回一次)。 */
    fun answer(text: String) = replies.trySend(listOf(Part.Text(text)))

    companion object {
        private val INTENT_PROMPT by lazy { Prompts.role("intent_gate") }
        private val SUMMARY_PROMPT by lazy { Prompts.role("stop_summary") }
        private val TITLE_PROMPT by lazy { Prompts.role("conversation_title") }
        const val PASS = """{"checks": [{"criterion": "屏幕上显示了要的结果", "status": "met", "quote": null}], "verdict": "pass", "reason": "脚本"}"""
        const val CANNOT_VERIFY = """{"checks": [{"criterion": "屏幕上显示了要的结果", "status": "unknown", "quote": null}], "verdict": "cannot_verify", "reason": "脚本:看不到结果"}"""
    }

    fun awaitCall(): Call = runBlocking {
        withTimeoutOrNull(30_000) { calls.receive() } ?: error("model not called; task state ${DeviceTaskState.state.value}")
    }
}
