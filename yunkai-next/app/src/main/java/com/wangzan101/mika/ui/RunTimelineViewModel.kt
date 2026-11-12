package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunMetrics
import com.wangzan101.mika.record.RunStore
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import com.wangzan101.mika.core.assembly.knownTools

enum class TagTone { Ok, Warn, Bad }

/** 时间线上的标签:守卫、核查、确认、接管、障碍的结论。 */
data class Tag(val text: String, val tone: TagTone)

/** 实际执行入口报出的活动;连续的读取可以合在一行,动作保持逐条可见。 */
data class TimelineActivity(
    val kind: String,
    val label: String,
    val target: String?,
    val effect: String?,
    val count: Int = 1,
    val obsIds: List<Int> = emptyList(),
)

/** 已记录的耗时。批量/脚本的子动作包含在外层工具耗时里，只计一次。 */
data class StepTiming(
    val modelMs: Long? = null,
    val toolMs: Long? = null,
    val retryMs: Long = 0,
) {
    val totalMs: Long? get() = if (modelMs != null && toolMs != null) modelMs + toolMs else null
}

/**
 * 时间线的一行。[said] 是模型这一步说的话(截到 80 字),[tool] 是工具的中文说法,
 * [detail] 是参数里最能说明对象的那一个(记录里已脱敏,原样显示),[screenshotId] 是这一步存下的画面。
 */
data class TimelineStep(
    val step: Int,
    val said: String,
    val tool: String?,
    val detail: String?,
    val result: String?,
    val tags: List<Tag>,
    val screenshotId: Int?,
    val activities: List<TimelineActivity> = emptyList(),
    val timing: StepTiming = StepTiming(),
)

data class TimelineHeader(
    val goal: String,
    val criteria: List<String>,
    val outcome: String,
    val verdict: String,
    val duration: String?,
    val modelCalls: Int,
    val usedMemory: List<String> = emptyList(),
    /** 这次运行的小结;记录里没有开始事件时为 null(只显示用时)。 */
    val metrics: RunMetrics? = null,
)

sealed interface TimelineUi {
    data object Loading : TimelineUi
    /** 记录已被清理(只保留最近 30 次)或编号不对。 */
    data object Expired : TimelineUi
    data class Ready(val header: TimelineHeader, val steps: List<TimelineStep>) : TimelineUi

    companion object {
        val EXPIRED_TEXT: String get() = tr(R.string.timeline_expired)
    }
}

/** 一次运行的只读时间线。[load] 读盘,要在后台线程调。 */
class RunTimelineViewModel(
    private val store: RunStore, private val runId: String,
    private val toolLabels: ToolLabels = ToolLabels(knownTools()),
    private val resolveMemory: (List<String>) -> List<String> = { emptyList() },
) {
    fun load(): TimelineUi {
        val summary = store.list().firstOrNull { it.id == runId }
        val events = store.events(runId)
        if (summary == null && events.isEmpty()) return TimelineUi.Expired
        val started = events.filterIsInstance<RunEvent.TaskStarted>().firstOrNull()
        val ended = events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()
        val startMs = summary?.startedAtMs ?: events.first().atMs
        val endMs = summary?.endedAtMs ?: ended?.atMs
        val header = TimelineHeader(
            goal = summary?.goal ?: started?.goal.orEmpty(),
            criteria = summary?.criteria?.takeIf { it.isNotEmpty() }
                ?: events.filterIsInstance<RunEvent.Criteria>().lastOrNull()?.criteria.orEmpty(),
            outcome = outcomeLabel(summary?.outcome ?: ended?.outcome,
                summary?.judged ?: events.filterIsInstance<RunEvent.OutcomeJudged>().lastOrNull()?.outcome),
            verdict = verdictLabel(summary?.verdict ?: events.filterIsInstance<RunEvent.Verifier>().lastOrNull()?.verdict),
            duration = endMs?.let { duration(it - startMs) },
            modelCalls = events.count { it is RunEvent.ModelReply },
            usedMemory = resolveMemory(events.filterIsInstance<RunEvent.MemoryUsed>().lastOrNull()?.ids.orEmpty()),
            metrics = RunMetrics.of(events, runId),
        )
        return TimelineUi.Ready(header, steps(events, toolLabels::label))
    }

    fun screen(screenshotId: Int): File? = store.screen(runId, screenshotId)

    companion object {
        const val SAID_MAX = 80

        /** 按步号归组;没有步号的事件(核查、确认、接管、障碍)挂在它之前最近的一步上。 */
        internal fun steps(events: List<RunEvent>, toolLabel: (String) -> String = { it }): List<TimelineStep> {
            val rows = LinkedHashMap<Int, TimelineStep>()
            val modelTimes = mutableMapOf<Int, Long>()
            val retryTimes = mutableMapOf<Int, Long>()
            val toolTimes = mutableMapOf<Int, MutableList<RunEvent.ToolResult>>()
            var current: Int? = null
            fun at(step: Int) = rows.getOrPut(step) { TimelineStep(step, "", null, null, null, emptyList(), null) }.also { current = step }
            fun tag(t: Tag) { current?.let { s -> rows[s] = rows.getValue(s).let { it.copy(tags = it.tags + t) } } }
            for (e in events) when (e) {
                // 改派掉的轻量模型回复没执行,不进步骤;调用次数照算
                is RunEvent.ModelReply -> {
                    val elapsed = e.latencyMs.coerceAtLeast(0) + e.retryWaitMs.coerceAtLeast(0)
                    if (elapsed > 0) modelTimes[e.step] = modelTimes.getOrDefault(e.step, 0) + elapsed
                    if (e.retryWaitMs > 0) retryTimes[e.step] = retryTimes.getOrDefault(e.step, 0) + e.retryWaitMs
                    if (e.acted) {
                        val row = at(e.step)
                        val said = listOf(row.said, e.text.trim()).filter { it.isNotEmpty() }.joinToString(" ")
                        rows[e.step] = row.copy(
                            said = said,
                            tool = e.tool?.let(toolLabel) ?: row.tool,
                            detail = e.argsJson?.let(::detailOf) ?: row.detail,
                        )
                    }
                }
                is RunEvent.ToolResult -> {
                    toolTimes.getOrPut(e.step) { mutableListOf() } += e
                    val row = at(e.step)
                    rows[e.step] = row.copy(
                        result = resultLabel(e.resultJson, e.changed),
                        tags = row.tags + guardTags(e.resultJson),
                        screenshotId = row.screenshotId ?: e.screenshotId,
                    )
                }
                is RunEvent.Media -> at(e.step).let { rows[e.step] = it.copy(screenshotId = it.screenshotId ?: e.mediaId) }
                is RunEvent.Activity -> {
                    val row = at(e.step)
                    val next = TimelineActivity(e.kind, toolLabel(e.label), e.target, e.effect, obsIds = e.obsIds)
                    val last = row.activities.lastOrNull()
                    val activities = if (e.kind == "observe" && last?.kind == "observe" && last.target == e.target) {
                        row.activities.dropLast(1) + last.copy(count = last.count + 1, obsIds = (last.obsIds + e.obsIds).distinct())
                    } else row.activities + next
                    rows[e.step] = row.copy(activities = activities)
                }
                is RunEvent.Verifier -> tag(verifierTag(e.verdict))
                is RunEvent.Confirmation -> tag(
                    when (e.decision) {
                        "approved" -> Tag(tr(R.string.tag_approved), TagTone.Ok)
                        "declined", "declined_before" -> Tag(tr(R.string.tag_declined), TagTone.Bad)
                        else -> Tag(tr(R.string.tag_needs_ok), TagTone.Warn)
                    },
                )
                is RunEvent.Takeover -> tag(Tag(tr(R.string.tag_takeover), TagTone.Warn))
                is RunEvent.Barrier -> tag(Tag(tr(R.string.tag_barrier), TagTone.Warn))
                else -> Unit
            }
            return rows.values.map { row ->
                val results = toolTimes[row.step].orEmpty()
                // do_steps 和复合工具也会记子动作;外层的 durationMs 已包含它们。
                val outer = results.filter { it.source == "model" && it.durationMs > 0 }
                val measured = (outer.ifEmpty { results.filter { it.durationMs > 0 } }).sumOf { it.durationMs }
                row.copy(
                    said = clipSaid(row.said),
                    timing = StepTiming(modelTimes[row.step], measured.takeIf { it > 0 }, retryTimes[row.step] ?: 0),
                )
            }
        }

        internal fun timingLabel(t: StepTiming): String {
            if (t.modelMs == null && t.toolMs == null) return tr(R.string.timing_unrecorded)
            return buildList {
                t.totalMs?.let { add(tr(R.string.timing_total, shortDuration(it))) }
                t.modelMs?.let { add(tr(R.string.timing_model, shortDuration(it))) }
                t.toolMs?.let { add(tr(R.string.timing_tool, shortDuration(it))) }
                if (t.retryMs > 0) add(tr(R.string.timing_retry, shortDuration(t.retryMs)))
            }.joinToString(" · ")
        }

        private fun shortDuration(ms: Long): String = when {
            ms < 1_000 -> tr(R.string.duration_ms, ms)
            ms < 60_000 -> tr(R.string.duration_seconds_decimal, "%.1f".format(java.util.Locale.ROOT, ms / 1_000.0))
            else -> duration(ms)
        }

        private fun clipSaid(s: String) = if (s.length <= SAID_MAX) s else s.take(SAID_MAX) + "…"

        private val DETAIL_KEYS = listOf("text", "package", "query", "key", "direction", "ref", "answer", "reason")

        private fun detailOf(argsJson: String): String? {
            val o = parse(argsJson) ?: return null
            return DETAIL_KEYS.firstNotNullOfOrNull { k -> (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }?.let(::clipSaid)
        }

        // 结果可能被截短成不合法的 JSON:ok / error 只按顶层键的写法找(元素文字里的引号是转义过的,不会误中)
        private val NOT_OK = Regex(""""ok"\s*:\s*false""")
        private val ERROR = Regex(""""error"\s*:\s*"([a-z_]+)"""")

        private fun resultLabel(json: String, changed: Boolean?): String = when {
            NOT_OK.containsMatchIn(json) -> ERROR.find(json)?.groupValues?.get(1)?.let { ERROR_LABELS[it] }?.let { tr(R.string.result_error_detail, it) } ?: tr(R.string.result_error)
            changed == true -> tr(R.string.result_effective)
            changed == false -> tr(R.string.result_unchanged)
            else -> tr(R.string.result_completed)
        }

        private fun guardTags(json: String): List<Tag> = guardObjects(json).mapNotNull { o ->
            val severity = (o["severity"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val what = (o["code"] as? JsonPrimitive)?.contentOrNull?.let { GUARD_LABELS[it] }
            val head = if (severity == "stop") tr(R.string.guard_stop) else tr(R.string.guard_warn)
            Tag(if (what == null) head else tr(R.string.guard_with_what, head, what), if (severity == "stop") TagTone.Bad else TagTone.Warn)
        }

        /**
         * 结果里 guard 数组的每一项。完整的 JSON 直接解析;被截短时从 `"guard":[` 往后逐个取出完整的对象,
         * 截断处那个不完整的丢掉。
         */
        private fun guardObjects(json: String): List<JsonObject> {
            parse(json)?.let { o -> return (o["guard"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty() }
            val start = GUARD_START.findAll(json).lastOrNull()?.range?.last?.plus(1) ?: return emptyList()
            val out = mutableListOf<JsonObject>()
            var depth = 0
            var inString = false
            var escaped = false
            var from = -1
            for (i in start until json.length) {
                val c = json[i]
                when {
                    escaped -> escaped = false
                    inString -> if (c == '\\') escaped = true else if (c == '"') inString = false
                    c == '"' -> inString = true
                    c == '{' -> { if (depth == 0) from = i; depth++ }
                    c == '}' -> { depth--; if (depth == 0) parse(json.substring(from, i + 1))?.let(out::add) }
                    c == ']' && depth == 0 -> break
                }
            }
            return out
        }

        private val GUARD_START = Regex(""""guard"\s*:\s*\[""")

        private fun parse(s: String): JsonObject? = try {
            Json.parseToJsonElement(s) as? JsonObject
        } catch (e: SerializationException) {
            null
        }

        private val GUARD_LABELS get() = mapOf(
            "repeating_action" to tr(R.string.guard_repeating),
            "no_progress" to tr(R.string.guard_no_progress),
            "too_many_failures" to tr(R.string.guard_failures),
        )

        private val ERROR_LABELS get() = mapOf(
            "ref_expired" to tr(R.string.err_ref_expired),
            "no_app_open" to tr(R.string.err_no_app),
            "invalid_arguments" to tr(R.string.err_invalid_args),
            "operation_failed" to tr(R.string.err_op_failed),
            "unknown_tool" to tr(R.string.err_unknown_tool),
            "protected_content" to tr(R.string.err_protected),
            "confirmation_required" to tr(R.string.err_confirm_required),
            "confirmation_mismatch" to tr(R.string.err_confirm_mismatch),
            "confirmation_declined" to tr(R.string.err_confirm_declined),
            "action_not_applied" to tr(R.string.err_not_applied),
            "verification_failed" to tr(R.string.verify_failed),
            "parallel_tool_call_skipped" to tr(R.string.err_parallel_skipped),
            "screenshot_unavailable" to tr(R.string.err_no_screenshot),
            "vision_unavailable" to tr(R.string.err_vision),
            "payment_requires_takeover" to tr(R.string.err_payment),
            "finish_missing_evidence" to tr(R.string.err_no_evidence),
            "double_click_unsupported" to tr(R.string.err_double_click),
        )

        private fun verifierTag(verdict: String) = when (verdict) {
            "pass" -> Tag(tr(R.string.verify_passed), TagTone.Ok)
            "fail" -> Tag(tr(R.string.verify_failed), TagTone.Bad)
            else -> Tag(tr(R.string.verify_unverified), TagTone.Warn)
        }

        private fun verdictLabel(verdict: String?) = if (verdict == null) tr(R.string.verify_none) else verifierTag(verdict).text

        /** [judged]:交出回答后判的结局;回答如实说没办成、只办了一部分的按它写。 */
        private fun outcomeLabel(outcome: String?, judged: String?) = when {
            outcome != "succeeded" && outcome != "unverified" -> outcomeLabel(outcome)
            judged == "partial" -> tr(R.string.outcome_partial)
            judged == "not_possible" -> tr(R.string.outcome_not_possible)
            else -> outcomeLabel(outcome)
        }

        private fun outcomeLabel(outcome: String?) = when (outcome) {
            "succeeded" -> tr(R.string.outcome_succeeded)
            "unverified" -> tr(R.string.outcome_unverified)
            "failed" -> tr(R.string.outcome_failed)
            "cancelled" -> tr(R.string.steer_cancelled)
            "error" -> tr(R.string.result_error)
            null -> tr(R.string.outcome_unfinished)
            else -> outcome
        }

        internal fun duration(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return if (s < 60) tr(R.string.duration_s, s) else tr(R.string.duration_m_s, s / 60, s % 60)
        }
    }
}
