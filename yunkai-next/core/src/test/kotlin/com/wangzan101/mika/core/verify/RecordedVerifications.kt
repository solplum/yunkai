package com.wangzan101.mika.core.verify

import com.wangzan101.mika.core.eval.RunFacts
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.testing.RECORDED_TOOLS
import com.wangzan101.mika.core.tool.Verb
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 录下来的运行里,每次核查(verifier 事件)和它核的那次最终回答配对(新记录是不带调用的回复,旧记录是 finish 调用,
 * 见 [RunFacts.isFinalAnswer]),不依赖两者的先后:平常的一步先核查、核完才记回复,最后一轮先记回复再核查。
 *
 * - 事件带 step:配同一步的回答。
 * - 旧记录没有 step:它之后的第一条模型回复是还没配过的回答,就是它(先核查后记回复);否则配它之前最近的、还没配过的回答。
 *
 * 返回 (verifier 下标, 回答下标);配不上的核查不在里面。
 */
internal object RecordedVerifications {
    fun pairs(events: List<RunEvent>): List<Pair<Int, Int>> {
        val used = mutableSetOf<Int>()
        fun finishAt(i: Int) = RunFacts.isFinalAnswer(events[i]) && i !in used
        val out = mutableListOf<Pair<Int, Int>>()
        events.forEachIndexed { v, e ->
            if (e !is RunEvent.Verifier) return@forEachIndexed
            val f = if (e.step != null) {
                events.indices.lastOrNull { finishAt(it) && (events[it] as RunEvent.ModelReply).step == e.step }
            } else {
                val next = (v + 1 until events.size).firstOrNull { events[it] is RunEvent.ModelReply }
                next?.takeIf(::finishAt) ?: (v - 1 downTo 0).firstOrNull(::finishAt)
            } ?: return@forEachIndexed
            used += f
            out += v to f
        }
        return out
    }

    /** 核查 [v] 当时能用的材料:它之前的事件,去掉被核的那次 finish 自己的回复和结果(回答不能拿来证明自己)。 */
    fun before(events: List<RunEvent>, v: Int, f: Int): List<RunEvent> {
        val step = (events[f] as RunEvent.ModelReply).step
        return events.subList(0, v).filterIndexed { i, e ->
            i != f && !(e is RunEvent.ToolResult && e.tool == "finish" && e.step == step)
        }
    }

    /** 核查当时能对照的文字:之前的全部读屏文字和工具结果里的全部字符串(见 [before])。 */
    fun sources(events: List<RunEvent>, v: Int, f: Int): List<String> = before(events, v, f).mapNotNull { b ->
        when (b) {
            is RunEvent.Observed -> b.text.takeIf { b.kind == "read" }
            is RunEvent.ToolResult -> runCatching { strings(Json.parseToJsonElement(b.resultJson)).joinToString("\n") }.getOrDefault(b.resultJson)
            else -> null
        }
    }.filter { it.isNotBlank() }

    /**
     * 按证据账本重建运行时核查器拿到的材料:finish 后重读的一屏没有记下来,用最后一次读屏代替;
     * 加上账本([RunFacts.ledger])按回答挑出的条目——和运行时的 [Verifier] 用同一个挑法。
     */
    fun ledgerSources(events: List<RunEvent>, v: Int, f: Int, goal: String, answer: String): List<String> {
        val seen = before(events, v, f)
        val fresh = seen.filterIsInstance<RunEvent.Observed>().lastOrNull { it.kind == "read" }?.text?.takeIf { it.isNotBlank() }
        return listOfNotNull(fresh) + RunFacts.ledger(seen, RECORDED_TOOLS).forAnswer(goal, answer).map { it.text }
    }

    /** 按证据账本重建运行时交给核查模型的条目(全文;节选见 [Verifier.excerpts])。 */
    fun ledgerEntries(events: List<RunEvent>, v: Int, f: Int, goal: String, answer: String): List<com.wangzan101.mika.core.observation.Observation> =
        RunFacts.ledger(before(events, v, f), RECORDED_TOOLS).forAnswer(goal, answer)

    /**
     * 换成证据账本之前,运行时核查器拿到的那几格证据(旧的 EvidenceLog 窗口,只留作对比):最近 3 次读屏(最后一次当作 finish 后重读的那一屏)、
     * 最近 3 次读网页的结果(每条至多 6000 字)、最近 6 条沙箱输出(按头尾截到 4000 字)、最近 12 条打开网页 / App 的事实。
     * finish 之后重读的一屏和网页正文开头没有记下来,这里用最后一次读屏代替。
     */
    fun liveSources(events: List<RunEvent>, v: Int, f: Int): List<String> {
        val results = before(events, v, f).filterIsInstance<RunEvent.ToolResult>()
        fun text(r: RunEvent.ToolResult) = runCatching { strings(Json.parseToJsonElement(r.resultJson)).joinToString("\n") }.getOrDefault(r.resultJson)
        fun clip(t: String) = if (t.length <= OUTPUT_CHARS) t else t.take(OUTPUT_CHARS * 3 / 4) + "\n" + t.takeLast(OUTPUT_CHARS / 4)
        val screens = before(events, v, f).filterIsInstance<RunEvent.Observed>().filter { it.kind == "read" }.map { it.text }.takeLast(RECENT)
        val web = results.filter { RECORDED_TOOLS.verb(it.tool) in WEB_READS }.map { text(it).take(WEB_CHARS) }.takeLast(RECENT)
        val sandbox = results.filter { RECORDED_TOOLS.verb(it.tool) in SANDBOX }.map { clip(text(it)) }.takeLast(OUTPUTS)
        val facts = results.filter { RECORDED_TOOLS.verb(it.tool) in FACTS }.map { clip(text(it)) }.takeLast(OUTPUTS * 2)
        return (screens + web + sandbox + facts).filter { it.isNotBlank() }
    }

    private const val RECENT = 3
    private const val WEB_CHARS = 6000
    private const val OUTPUTS = 6
    private const val OUTPUT_CHARS = 4000
    private val WEB_READS = setOf(Verb.ReadPage, Verb.EvaluateJs, Verb.Downloads, Verb.Capture, Verb.Script)
    private val SANDBOX = setOf(Verb.RunCommand, Verb.RunBundledScript, Verb.ReadFile, Verb.WriteFile, Verb.SearchFiles, Verb.ShareFile)
    private val FACTS = setOf(Verb.Navigate, Verb.Tabs, Verb.LaunchApp)

    /** 被核的那次回答:回答是任务的结局时取 task_ended 里的全文(回复里记的可能截短了)。 */
    fun answer(events: List<RunEvent>, f: Int): String? {
        val r = events[f] as RunEvent.ModelReply
        val last = events.indexOfLast(RunFacts::isFinalAnswer) == f
        val ended = events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()?.takeIf { last && r.tool == null && it.outcome == "succeeded" }
        return ended?.answerOrReason ?: RunFacts.answerOf(r)
    }

    fun arg(json: String?, key: String): String? = runCatching {
        ((Json.parseToJsonElement(json.orEmpty()) as JsonObject)[key] as? JsonPrimitive)?.contentOrNull
    }.getOrNull()

    private fun strings(e: JsonElement): List<String> = when (e) {
        is JsonPrimitive -> if (e.isString) listOf(e.content) else emptyList()
        is JsonArray -> e.flatMap(::strings)
        is JsonObject -> e.values.flatMap(::strings)
    }
}
