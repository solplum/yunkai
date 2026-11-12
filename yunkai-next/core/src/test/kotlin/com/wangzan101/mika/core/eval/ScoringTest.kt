package com.wangzan101.mika.core.eval

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoringTest {
    private fun task(expect: Expect, checks: List<Check> = listOf(Check.AnswerContains(listOf("14"))), category: EvalCategory = EvalCategory.Read) =
        EvalTask(id = "t", goal = "g", apps = emptyList(), category = category, expect = expect, checks = checks)

    private fun obs(outcome: String, answer: String = "", failReason: String? = null) =
        Observed(outcome, answer, emptyList(), null, emptyMap(), emptyMap(), 3, 2000, 10, 5, 2, failReason)

    @Test fun unverifiedIsNotPassNorFalseSuccess() {
        val s = Scoring.score(task(Expect.Succeeded), obs("unverified", "版本 14"))
        assertFalse(s.passed)
        assertFalse(s.falseSuccess)
        assertTrue(s.unverified)
        assertEquals(listOf("outcome_is"), s.failedChecks)
    }

    @Test fun succeededWithFailedCheckIsFalseSuccess() {
        val s = Scoring.score(task(Expect.Succeeded), obs("succeeded", "版本 13"))
        assertFalse(s.passed)
        assertTrue(s.falseSuccess)
        assertEquals(listOf("0:answer_contains"), s.failedChecks)
        assertEquals(FailureClass.false_success, s.failureClass)
        val ok = Scoring.score(task(Expect.Succeeded), obs("succeeded", "版本 14"))
        assertTrue(ok.passed)
        assertFalse(ok.falseSuccess)
        assertNull(ok.failureClass)
        // 做不成的题却报成功,也是假成功
        val claimed = Scoring.score(task(Expect.Failed, emptyList(), EvalCategory.Impossible), obs("succeeded", "办好了"))
        assertTrue(claimed.falseSuccess)
    }

    @Test fun expectFailedPassesOnHonestFailure() {
        val t = task(Expect.Failed, emptyList(), EvalCategory.Impossible)
        val s = Scoring.score(t, obs("failed", "没有这个菜单", Scoring.GAVE_UP))
        assertTrue(s.passed)
        assertFalse(s.falseSuccess)
        assertNull(s.failureClass)
        assertFalse(Scoring.score(t, obs("timeout")).passed)
    }

    @Test fun judgedOutcomeDecidesReportedSuccess() {
        // 回答如实说没办成:判的结局记成 not_possible,和如实放弃一样;做不成的题因此通过,不再是假成功
        val impossible = task(Expect.Failed, emptyList(), EvalCategory.Impossible)
        val honest = Scoring.score(impossible, obs("succeeded", "没有这个菜单").copy(judged = "not_possible"))
        assertTrue(honest.passed)
        assertFalse(honest.falseSuccess)
        // 只办了一部分:没办成,但也不是假成功
        val partial = Scoring.score(task(Expect.Succeeded), obs("succeeded", "只看了两条").copy(judged = "partial"))
        assertFalse(partial.passed)
        assertFalse(partial.falseSuccess)
        assertEquals(listOf("outcome_is", "0:answer_contains"), partial.failedChecks)
        // 判下来办成了、没判成或没判过:照交出时的结局
        assertTrue(Scoring.score(impossible, obs("succeeded", "办好了").copy(judged = "done")).falseSuccess)
        assertTrue(Scoring.score(impossible, obs("succeeded", "办好了").copy(judged = "unknown")).falseSuccess)
        assertTrue(Scoring.score(task(Expect.Succeeded), obs("succeeded", "版本 14").copy(judged = "done")).passed)
    }

    @Test fun unverifiedWithFailedChecksIsItsOwnClass() {
        assertEquals(FailureClass.unverified_wrong, Scoring.score(task(Expect.Succeeded), obs("unverified", "版本 13")).failureClass)
    }

    @Test fun expectFailedNeedsAgentToGiveUpItself() {
        // 只有 Agent 自己如实说做不成才算;撞步上限、被守卫停下都只是没做完
        val t = task(Expect.Failed, emptyList(), EvalCategory.Impossible)
        val stepLimit = Scoring.score(t, obs("failed", "已达到 25 步上限", Scoring.failReason(TaskState.Failed("已达到 25 步上限,任务没有完成。", FailKind.StepLimit))))
        assertFalse(stepLimit.passed)
        assertEquals(FailureClass.step_limit, stepLimit.failureClass)
        val guard = Scoring.score(t, obs("failed", "", Scoring.failReason(TaskState.Failed("已安全停止(stalled):操作停滞不前。", FailKind.Guard))))
        assertFalse(guard.passed)
        assertEquals(FailureClass.guard_stop, guard.failureClass)
        assertFalse(Scoring.score(t, obs("failed", "找不到", "找不到")).passed)
    }

    @Test fun expectFailedRejectsInfrastructureFailure() {
        val t = task(Expect.Failed, emptyList(), EvalCategory.Impossible)
        val model = Scoring.score(t, obs("failed", failReason = "API Key 无效"))
        assertFalse(model.passed)
        assertEquals(FailureClass.model_error, model.failureClass)
        val guardByModel = Scoring.score(t, obs("failed", failReason = "模型连续返回空回复,已安全停止"))
        assertFalse(guardByModel.passed)
        assertEquals(FailureClass.model_error, guardByModel.failureClass)
        val harness = Scoring.score(t, obs("failed", failReason = "评测器:探针执行失败"))
        assertFalse(harness.passed)
        assertEquals(FailureClass.harness, harness.failureClass)
    }

    @Test fun expectFailedEnforcesOptionalChecks() {
        val t = task(Expect.Failed, listOf(Check.AnswerContains(listOf("没有"))), EvalCategory.Impossible)
        assertTrue(Scoring.score(t, obs("failed", "设置里没有这个菜单", Scoring.GAVE_UP)).passed)
        val s = Scoring.score(t, obs("failed", "我放弃了", Scoring.GAVE_UP))
        assertFalse(s.passed)
        assertEquals(listOf("0:answer_contains"), s.failedChecks)
    }

    @Test fun markerOnlyWhenTaskStarted() {
        val t = task(Expect.Succeeded, category = EvalCategory.Write).copy(marker = "Mika评测-r1")
        assertEquals("Mika评测-r1", Scoring.score(t, obs("succeeded", "14")).marker)
        val notStarted = obs("failed", failReason = "API Key 无效").copy(steps = 0)
        assertNull(Scoring.score(t, notStarted).marker)
    }

    @Test fun initialClassRules() {
        val t = task(Expect.Succeeded)
        fun cls(o: Observed, tt: EvalTask = t) = Scoring.initialClass(tt, o)
        assertEquals(FailureClass.false_success, cls(obs("succeeded", "x", "已安全停止")))
        assertEquals(FailureClass.guard_stop, cls(obs("failed", failReason = "同一操作重复,已安全停止")))
        assertEquals(FailureClass.step_limit, cls(obs("failed", failReason = "达到步上限 40")))
        assertEquals(FailureClass.verifier_reject, cls(obs("failed", failReason = "核查未通过:没看到笔记")))
        assertEquals(FailureClass.model_error, cls(obs("failed", failReason = "API Key 无效")))
        assertEquals(FailureClass.model_error, cls(obs("failed", failReason = "触发限流")))
        assertEquals(FailureClass.barrier, cls(obs("takeover")))
        assertEquals(FailureClass.unknown, cls(obs("takeover"), task(Expect.Succeeded, category = EvalCategory.Barrier)))
        assertEquals(FailureClass.unknown, cls(obs("failed", failReason = "别的原因")))
        assertNull(cls(obs("succeeded", "14")))
    }

    @Test fun failReasonComesFromFailureKindNotAgentText() {
        val impossible = task(Expect.Failed, emptyList(), EvalCategory.Impossible)
        fun reason(text: String, kind: FailKind) = Scoring.failReason(TaskState.Failed(text, kind))
        // Agent 如实放弃时说的话里带「模型」「额度」也不能被当成模型出错
        val gaveUp = reason("这个手机型号的设置里没有该菜单,额度相关选项也没有", FailKind.GaveUp)
        assertTrue(Scoring.score(impossible, obs("failed", failReason = gaveUp)).passed)
        val read = task(Expect.Succeeded)
        assertEquals(FailureClass.model_error, Scoring.initialClass(read, obs("failed", failReason = reason("服务出错(HTTP 500)", FailKind.Model))))
        assertEquals(FailureClass.guard_stop, Scoring.initialClass(read, obs("failed", failReason = reason("已安全停止(no_progress):操作多次后画面一直没有变化。", FailKind.Guard))))
        assertEquals(FailureClass.step_limit, Scoring.initialClass(read, obs("failed", failReason = reason("已达到 60 步上限,任务没有完成。", FailKind.StepLimit))))
        assertEquals(FailureClass.harness, Scoring.initialClass(read, obs("failed", failReason = reason("无障碍服务没有开启", FailKind.Environment))))
        assertFalse(Scoring.score(impossible, obs("failed", failReason = reason("连接失败", FailKind.Environment))).passed)
    }

    @Test fun oldVerificationFailureIsVerifierReject() {
        val read = task(Expect.Succeeded)
        // 旧记录里核查退回到底的失败原因:未满足的标准里带「模型」二字也不能当成模型出错
        val reason = "核查未通过:回答包含模型名称且来自屏幕"
        assertEquals(FailureClass.verifier_reject, Scoring.initialClass(read, obs("failed", failReason = reason)))
    }

    @Test fun leftScopeRecordedButNotFailure() {
        val s = Scoring.score(task(Expect.Succeeded), obs("succeeded", "版本 14").copy(leftScope = listOf("com.other")))
        assertTrue(s.passed)
        assertEquals(listOf("com.other"), s.leftScope)
        val md = EvalReport.markdown("r", "m", listOf(s to obs("succeeded")), emptyList(), null)
        assertTrue(md, "- t:com.other" in md)
    }

    // 并行评测里两题互相抢 App 导致的失败单列一类,不算评测器问题也不算 Agent 的错
    @Test fun interferenceIsItsOwnClass() {
        val o = obs("failed", failReason = Scoring.INTERFERENCE_PREFIX + "另一个任务占着 App")
        assertEquals(FailureClass.interference, Scoring.score(task(Expect.Succeeded), o).failureClass)
    }
}
