package com.wangzan101.mika.ui

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.record.RunMetrics
import com.wangzan101.mika.core.record.RunStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunMetricsCopyTest {
    @get:Rule val texts = ChineseTexts()

    @Test fun durations() {
        assertEquals("0 秒", RunMetricsCopy.duration(0))
        assertEquals("3.2 秒", RunMetricsCopy.duration(3_240))
        assertEquals("45 秒", RunMetricsCopy.duration(45_000))
        assertEquals("1 分 0 秒", RunMetricsCopy.duration(59_960))
        assertEquals("2 分 13 秒", RunMetricsCopy.duration(133_000))
        assertEquals("1 小时 5 分", RunMetricsCopy.duration(3_900_000))
    }

    @Test fun summaryLineReadsLikeASentence() {
        val m = RunMetrics(totalMs = 133_000, firstFeedbackMs = 3_200, modelCalls = 14, inputTokens = 80_000, outputTokens = 6_000)
        assertEquals("共 2 分 13 秒 · 和模型来回 14 次 · 8.6 万 Token · 第一次反馈 3.2 秒", RunMetricsCopy.summaryLine(m))
        assertEquals("共 1 秒 · 和模型来回 0 次 · 0 Token", RunMetricsCopy.summaryLine(RunMetrics(totalMs = 1_000)))
    }

    @Test fun breakdownShowsSharesTokensAndSteps() {
        val m = RunMetrics(totalMs = 10_000, modelMs = 5_000, observeMs = 2_000, actMs = 1_000, retryWaitMs = 500,
            inputTokens = 12_345, outputTokens = 100, cachedTokens = 10_000, steps = 4, observeCalls = 3, actCalls = 2, allCalls = 6)
        val rows = RunMetricsCopy.breakdown(m).toMap()
        assertEquals("5 秒 · 50%", rows[RunMetricsCopy.MODEL])
        assertEquals("2 秒 · 20%", rows[RunMetricsCopy.OBSERVE])
        assertEquals("1 秒 · 10%", rows[RunMetricsCopy.ACT])
        assertEquals("2 秒 · 20%", rows[RunMetricsCopy.OTHER])
        assertEquals("0.5 秒", rows["其中重试等待"])
        assertEquals("1.23 万", rows["输入 Token"])
        assertEquals("1 万", rows["缓存命中"])
        assertEquals("4 步", rows["步数"])
        assertEquals("3 次 / 2 次", rows["读屏 / 操作"])
        assertTrue(rows.getValue("全部模型请求").startsWith("6 次"))
    }

    @Test fun statsRows() {
        val s = RunStats(runs = 3, succeeded = 2, medianTotalMs = 20_000, medianFirstFeedbackMs = null, avgModelCalls = 6.0, avgTokens = 25_000.0,
            totalMs = 60_000, modelMs = 30_000, observeMs = 9_000, actMs = 6_000, otherMs = 15_000)
        val rows = RunMetricsCopy.statsRows(s).toMap()
        assertEquals("67%（2/3）", rows["办成率"])
        assertEquals("20 秒", rows["用时中位数"])
        assertEquals("—", rows["第一次反馈中位数"])
        assertEquals("6.0 次", rows["平均和模型来回"])
        assertEquals("2.5 万", rows["平均 Token"])
        val split = RunMetricsCopy.splitRows(s).toMap()
        assertEquals("50%", split[RunMetricsCopy.MODEL])
        assertEquals("25%", split[RunMetricsCopy.OTHER])
    }
}
