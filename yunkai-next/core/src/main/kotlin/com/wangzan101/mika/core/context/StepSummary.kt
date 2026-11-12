package com.wangzan101.mika.core.context

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 折叠掉的步骤的结构化摘要:目标 / 已确认的事实 / 进展 / 下一步。每折一轮就在上一份的基础上更新([next])。
 * 内容全从任务原话、工具结果和模型自己说的话里确定性地取,不另调模型:每次压缩都要重算摘要,
 * 调模型既多一次往返,同样的历史也可能得出不同的摘要,请求前缀就不稳了。
 * 各段都有上限(事实每种 [MAX_FACT_VALUES] 个、进展 [MAX_PROGRESS] 行、更早的结局 [MAX_EARLIER_KINDS] 种,每项截到 [CLIP] 字),
 * 步数再多摘要也不会无限变长。
 */
data class StepSummary(
    val goal: String,
    /** 事实种类 → 值(去重,新的在后)。 */
    val facts: Map<String, List<String>> = emptyMap(),
    val progress: List<String> = emptyList(),
    /** 并成计数的更早步骤:结局 → 步数。 */
    val earlier: Map<String, Int> = emptyMap(),
    /** 模型在折叠段里最后说的打算。 */
    val intent: String? = null,
) {
    /** 折进一轮:[line] 是这一步的进展行(以「→ 结局」结尾),[results] 是这一轮成功的工具结果,[said] 是模型这一轮说的话。 */
    fun next(line: String, results: List<JsonObject>, said: String?): StepSummary {
        val f = facts.toMutableMap()
        for (r in results) for ((key, kind) in FACT_KEYS) {
            val v = (r[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            f[kind] = ((f[kind].orEmpty() - clip(v)) + clip(v)).takeLast(MAX_FACT_VALUES)
        }
        var p = progress + line
        var e = earlier
        while (p.size > MAX_PROGRESS) {
            val outcome = p.first().substringAfterLast("→ ").trim().let { if (it in e || e.size < MAX_EARLIER_KINDS) it else OTHER }
            e = e + (outcome to (e[outcome] ?: 0) + 1)
            p = p.drop(1)
        }
        return StepSummary(goal, f, p, e, said?.let(::clip)?.takeIf { it.isNotEmpty() } ?: intent)
    }

    fun render(header: String): String = buildString {
        appendLine(header)
        appendLine("只有最新的用户消息是当前任务，工具仍然可用；此前的用户原话仅供参考。")
        appendLine("目标:$goal")
        appendLine("已确认的事实:")
        if (facts.isEmpty()) appendLine("- (还没有)")
        FACT_KEYS.values.forEach { kind -> facts[kind]?.let { appendLine("- $kind:${it.joinToString("、")}") } }
        appendLine("进展:")
        if (earlier.isNotEmpty()) appendLine("- 更早的 ${earlier.values.sum()} 步:" + earlier.entries.joinToString("、") { "${it.key} ${it.value}" })
        progress.forEach { appendLine("- $it") }
        append("下一步:从下面保留的最近几步接着做")
        intent?.let { append("(折叠前最后的打算:$it)") }
    }

    private companion object {
        /** 结果里能当事实记下的短字段 → 事实种类。 */
        val FACT_KEYS = linkedMapOf("currentApp" to "到过的 App", "url" to "打开过的页面", "path" to "读写过的文件")
        const val MAX_FACT_VALUES = 4
        const val MAX_PROGRESS = 15
        const val MAX_EARLIER_KINDS = 5
        const val OTHER = "其他"
        const val CLIP = 100

        fun clip(s: String): String {
            val one = s.replace('\n', ' ').trim()
            return if (one.length <= CLIP) one else one.take(CLIP) + "…"
        }
    }
}
