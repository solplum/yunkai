package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role

/**
 * 一轮对话:用户说了什么、结果如何。[outcome] 是成功时的答案或失败原因;
 * [status] 为 succeeded / unverified(办完了但没能核实,[note] 是原因,[unconfirmed] 是标成未确认的几处)/ failed / cancelled。
 * [runId] 是这轮的运行记录;没开起记录或更早的轮次为 null。
 */
/**
 * [memoryNotes]/[usedMemory]/[skillNotes]:结果卡片下的记忆与技能提示,任务结束后补上;不进给模型的上文。
 * [result]:交出回答后判的结局([RunOutcome.wire]),后台补上;没判过为 null。
 */
data class Turn(
    val userText: String, val outcome: String, val status: String, val note: String? = null, val runId: String? = null,
    val memoryNotes: List<com.wangzan101.mika.core.memory.MemoryNote> = emptyList(),
    val usedMemory: List<String> = emptyList(),
    val skillNotes: List<com.wangzan101.mika.core.skills.SkillNote> = emptyList(),
    val unconfirmed: List<String> = emptyList(),
    val result: String? = null,
    /** 任务进行中用户插的、已交给这个任务的话(界面在那一轮下面显示)。 */
    val steers: List<String> = emptyList(),
    /** 这一轮真的用过的能力组(手机、网页、沙箱……);下一轮开局预先展开,不进给模型的上文。 */
    val groups: List<String> = emptyList(),
)

object Conversation {
    const val HEADER = "本对话之前的几轮(供参考,当前状态以这次读到的为准):"

    /** 停止的那一轮还没有交代(或没什么可交代)时记下的话。 */
    const val CANCELLED_OUTCOME = "已取消"

    /** 停止的那一轮补上的交代;没有时为 null。 */
    fun stopSummary(t: Turn): String? =
        t.outcome.trim().takeIf { t.status == "cancelled" && it.isNotEmpty() && it != CANCELLED_OUTCOME }

    /** 把之前的轮次做成一条用户角色的参考消息,最近的在最后;太长就丢掉较早的轮次。没有轮次返回 null。 */
    fun context(turns: List<Turn>, maxTurns: Int = 5, maxChars: Int = 4000): Message? {
        var recent = turns.takeLast(maxTurns.coerceAtLeast(0))
        if (recent.isEmpty()) return null
        fun render(ts: List<Turn>, clip: Int) = ts.mapIndexed { i, t -> line(i + 1, t, clip) }.joinToString("\n")
        while (recent.size > 1 && HEADER.length + 1 + render(recent, Int.MAX_VALUE).length > maxChars) recent = recent.drop(1)
        var body = render(recent, Int.MAX_VALUE)
        if (HEADER.length + 1 + body.length > maxChars) {
            // 只剩一轮还超长:各字段截断
            body = render(recent, ((maxChars - HEADER.length) / 3).coerceAtLeast(20))
        }
        return Message(Role.User, listOf(Part.Text("$HEADER\n$body")), Author.Mika)
    }

    private fun line(n: Int, t: Turn, clip: Int): String {
        // 回答如实说没办成或只办了一部分:按交出后判的结局说,下一轮别当它办成了
        val answered = t.status == "succeeded" || t.status == "unverified"
        val result = when {
            answered && t.result == RunOutcome.Partial.wire -> "只办成了一部分:${t.outcome.clip(clip)}"
            answered && t.result == RunOutcome.NotPossible.wire -> "没有办成:${t.outcome.clip(clip)}"
            t.status == "succeeded" -> "结果:${t.outcome.clip(clip)}"
            t.status == "unverified" -> "结果(没能核实):${t.outcome.clip(clip)}"
            // 停止后补上的交代(做过什么、哪些没做完)带上,下一句「继续」才知道从哪接着做;旧记录里只有「已取消」
            t.status == "cancelled" -> stopSummary(t)?.let { "你中途停止了这次任务,停止前:${it.clip(clip)}" } ?: "你取消了这次任务"
            else -> "没有完成,原因:${t.outcome.clip(clip)}"
        }
        return "$n. 用户说:${t.userText.clip(clip)};$result"
    }

    private fun String.clip(max: Int) = if (length <= max) this else take(max) + "…"
}
