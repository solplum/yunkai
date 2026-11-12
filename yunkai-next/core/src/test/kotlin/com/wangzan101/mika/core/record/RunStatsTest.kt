package com.wangzan101.mika.core.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunStatsTest {
    private fun m(total: Long, first: Long?, calls: Int, tokens: Long, outcome: String = "succeeded", model: Long = 0, observe: Long = 0, act: Long = 0) =
        RunMetrics(totalMs = total, ended = true, firstFeedbackMs = first, modelCalls = calls, inputTokens = tokens, outcome = outcome,
            modelMs = model, observeMs = observe, actMs = act)

    @Test fun medianOfOddAndEvenAndEmpty() {
        assertEquals(3L, RunStats.median(listOf(5, 1, 3)))
        assertEquals(2L, RunStats.median(listOf(4, 1, 3, 1)))
        assertNull(RunStats.median(emptyList()))
    }

    @Test fun aggregatesSuccessMediansAveragesAndSplit() {
        val s = RunStats.of(listOf(
            m(10_000, 2_000, 4, 1_000, model = 5_000, observe = 2_000, act = 1_000),
            m(20_000, null, 6, 3_000, outcome = "failed", model = 10_000, observe = 4_000, act = 2_000),
            m(30_000, 4_000, 8, 2_000, outcome = "unverified", model = 15_000, observe = 3_000, act = 3_000),
        ))
        assertEquals(3, s.runs)
        assertEquals(2, s.succeeded)
        assertEquals(2.0 / 3, s.successRate, 1e-9)
        assertEquals(20_000L, s.medianTotalMs)
        // 没有第一次反馈的那次不参与
        assertEquals(3_000L, s.medianFirstFeedbackMs)
        assertEquals(6.0, s.avgModelCalls, 1e-9)
        assertEquals(2_000.0, s.avgTokens, 1e-9)
        assertEquals(60_000L, s.totalMs)
        assertEquals(30_000L, s.modelMs)
        assertEquals(9_000L, s.observeMs)
        assertEquals(6_000L, s.actMs)
        assertEquals(15_000L, s.otherMs)
    }

    @Test fun emptyListIsAllZero() {
        val s = RunStats.of(emptyList())
        assertEquals(0, s.runs)
        assertEquals(0.0, s.successRate, 0.0)
        assertNull(s.medianFirstFeedbackMs)
        assertEquals(0.0, s.avgModelCalls, 0.0)
    }
}
