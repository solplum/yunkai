package com.wangzan101.mika.core.skills

import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.TaskState

@Suppress("EnumEntryName")
/** [unreviewed]:办完了但什么也没核对(回答里没有可核对的值),不说明技能灵不灵,停用时不算。 */
enum class SkillResult { verified, unverified, failed, verifier_rejected, unreviewed }

/**
 * 结果卡片下的技能提示:「Mika 学会了一项技能:… · 看看」([draftId])、
 * 「这项技能最近不灵,先停用了 · 恢复 · 删除」([suspended] 是技能名)。
 */
data class SkillNote(val text: String, val draftId: String? = null, val suspended: String? = null)

/** 个人技能的最近使用结果与自动停用(运行中不灵就停用)。 */
object SkillOutcomes {
    const val KEEP = 5
    private const val WINDOW = 3
    private const val BAD_IN_WINDOW = 2
    private const val INJECTED_STREAK = 2
    /** 结果卡片与核查退回:照着技能做却被核查退回,判为 verifier_rejected。 */
    const val VERIFIER_REJECTED_MARK = "核查未通过"

    /**
     * 追加一次结果并只留最近 [KEEP] 次;按规则可能转为停用。
     * [injectedOnly]:这次只是开头自动塞进了全文,Agent 没有主动去看,不能确定它照着做了——这时要连续两次不灵才停用。
     */
    fun record(s: PersonalSkill, r: SkillResult, followed: Boolean, injectedOnly: Boolean = false): PersonalSkill {
        val outcomes = (s.outcomes + r.name).takeLast(KEEP)
        if (s.status == SkillStatus.trial) return s.copy(outcomes = outcomes, status = when (r) {
            SkillResult.verified -> SkillStatus.active
            SkillResult.failed, SkillResult.verifier_rejected -> SkillStatus.suspended
            else -> SkillStatus.trial
        })
        val suspend = s.status == SkillStatus.active &&
            shouldSuspend(outcomes, followed && !injectedOnly && r == SkillResult.verifier_rejected, injectedOnly)
        return s.copy(outcomes = outcomes, status = if (suspend) SkillStatus.suspended else s.status)
    }

    /** 最近 3 次(没核对过的不算)里有 2 次不是核实过的,或这次照着做被核查退回;[injectedOnly] 时只看最近两次是否都不灵。 */
    fun shouldSuspend(all: List<String>, lastFollowedRejected: Boolean, injectedOnly: Boolean = false): Boolean {
        val outcomes = all.filter { it != SkillResult.unreviewed.name }
        return if (injectedOnly) outcomes.size >= INJECTED_STREAK && outcomes.takeLast(INJECTED_STREAK).none { it == SkillResult.verified.name }
        else lastFollowedRejected || outcomes.takeLast(WINDOW).count { it != SkillResult.verified.name } >= BAD_IN_WINDOW
    }

    /**
     * 任务结局对应的结果;取消的不记(null)。[outcome] 是交出回答后判的结局([RunOutcome]):
     * 回答如实说没办成、只办了一部分的算 failed;没判成的不说明技能灵不灵,算 unreviewed。
     */
    fun resultOf(end: TaskState, outcome: RunOutcome): SkillResult? = when (end) {
        is TaskState.Succeeded -> when {
            outcome == RunOutcome.Partial || outcome == RunOutcome.NotPossible -> SkillResult.failed
            // 退回一次后仍有没对上的:照着技能做也没做对,和以前被核查退回一样(判没判成都是)
            end.unconfirmed.isNotEmpty() -> SkillResult.verifier_rejected
            outcome == RunOutcome.Unknown -> SkillResult.unreviewed
            end.verified -> SkillResult.verified
            !end.reviewed -> SkillResult.unreviewed
            else -> SkillResult.unverified
        }
        is TaskState.Failed -> if (VERIFIER_REJECTED_MARK in end.reason) SkillResult.verifier_rejected else SkillResult.failed
        else -> null
    }
}
