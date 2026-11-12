package com.wangzan101.mika.core.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalReportCompareTest {
    private fun s(id: String, passed: Boolean, falseSuccess: Boolean = false) =
        TaskScore(id, passed, falseSuccess, unverified = false, failedChecks = emptyList(), failureClass = null)

    private val base = listOf(s("a", true), s("b", true), s("c", true), s("d", false))

    @Test fun deltasAndGate() {
        val treated = listOf(s("a", true), s("b", true), s("c", false), s("d", false))
        val report = EvalReport.compare(base, treated, mapOf("send-note" to 3, "set-alarm" to 1))
        assertTrue(report.contains("| 办成 | 3/4 | 2/4 | -1 |"))
        assertTrue(report.contains("| 假成功 | 0/4 | 0/4 | 0 |"))
        assertTrue(report.contains("- send-note:3 次"))
        assertTrue(report.contains("通过:"))
        assertTrue(EvalReport.gate(base, treated).passed)

        // 少了两题:不通过
        val worse = listOf(s("a", true), s("b", false), s("c", false), s("d", false))
        val g = EvalReport.gate(base, worse)
        assertFalse(g.passed)
        assertEquals(1, g.reasons.size)
        assertTrue(g.reasons.single().contains("少了 2 题"))

        // 有一题假成功:不通过,并列出题号
        val lying = listOf(s("a", true), s("b", true), s("c", true), s("d", false, falseSuccess = true))
        val g2 = EvalReport.gate(base, lying)
        assertFalse(g2.passed)
        assertTrue(g2.reasons.single().contains("d"))
        assertTrue(EvalReport.compare(base, lying, emptyMap()).contains("不通过:"))
    }

    @Test fun mediansWhenObserved() {
        fun o(steps: Int, ms: Long) = Observed("succeeded", "", emptyList(), null, emptyMap(), emptyMap(), steps, ms, 0, 0, 0, null)
        val r = EvalReport.compare(base, base, emptyMap(), listOf(o(10, 60_000), o(20, 80_000)), listOf(o(8, 50_000), o(12, 70_000)))
        assertTrue(r.contains("| 步数中位数 | 15 | 10 | -5 |"))
        assertTrue(r.contains("| 耗时中位数(秒) | 70 | 60 | -10 |"))
        assertTrue(r.contains("没有用到学到的技能。"))
    }

    @Test fun tokensPairedByTaskId() {
        fun o(input: Long, cached: Long, output: Long) =
            Observed("succeeded", "", emptyList(), null, emptyMap(), emptyMap(), 1, 1, input, output, cached, null)
        // 基线 a b c d,带技能一轮只跑了 a c:只比 a、c 两题
        val treated = listOf(s("c", true), s("a", true))
        val r = EvalReport.compare(base, treated, emptyMap(),
            listOf(o(1000, 200, 50), o(99_999, 0, 9_999), o(3000, 600, 70), o(99_999, 0, 9_999)), listOf(o(2400, 1800, 60), o(800, 600, 40)))
        assertTrue(r, r.contains("| 输入 token(配对 2 题中位数) | 2000 | 1600 | -400 |"))
        assertTrue(r, r.contains("| 输出 token(配对 2 题中位数) | 60 | 50 | -10 |"))
        assertTrue(r, r.contains("| 缓存命中率 %(配对 2 题中位数) | 20 | 75 | +55 |"))
    }
}
