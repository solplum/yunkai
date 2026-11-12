package com.wangzan101.mika.ui

import com.wangzan101.mika.ChineseUi
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.core.record.RunMetrics
import com.wangzan101.mika.core.record.RunStats
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 「查看过程」顶上的小结一行和开发者选项里的运行统计页(不用真机任务)。 */
@RunWith(AndroidJUnit4::class)
class RunMetricsUiTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    @Test fun summaryLineShowsAndExpands() {
        val m = RunMetrics(totalMs = 133_000, firstFeedbackMs = 3_200, modelCalls = 14, inputTokens = 80_000, outputTokens = 6_000,
            modelMs = 60_000, observeMs = 30_000, actMs = 20_000, steps = 9)
        compose.setContent { MikaTheme { Column { RunMetricsSummary(m) } } }
        compose.onNodeWithText(RunMetricsCopy.summaryLine(m)).assertIsDisplayed()
        compose.onNodeWithTag("run-metrics-summary").performClick()
        compose.onNodeWithText(RunMetricsCopy.MODEL).assertIsDisplayed()
        compose.onNodeWithText("9 步").assertIsDisplayed()
    }

    @Test fun statsPageShowsAggregatesAndExports() {
        val stats = RunStats.of(listOf(
            RunMetrics(totalMs = 10_000, firstFeedbackMs = 2_000, modelCalls = 4, outcome = "succeeded"),
            RunMetrics(totalMs = 30_000, firstFeedbackMs = 4_000, modelCalls = 8, outcome = "failed"),
        ))
        var range = 1
        var copied = 0
        compose.setContent { MikaTheme { RunStatsContent(stats, 1, { range = it }, onCopy = { copied++ }, onShare = {}, onBack = {}) } }
        compose.onNodeWithText(RunMetricsCopy.STATS_TITLE).assertIsDisplayed()
        compose.onNodeWithText("50%（1/2）").assertExists()
        compose.onNodeWithText("20 秒").assertExists()
        compose.onNodeWithTag("run-stats-range-100").performClick()
        assertEquals(2, range)
        compose.onNodeWithTag("run-stats-copy").performScrollTo().performClick()
        assertEquals(1, copied)
    }

    @Test fun statsPageWithoutRunsSaysSo() {
        compose.setContent { MikaTheme { RunStatsContent(null, 0, {}, onCopy = {}, onShare = {}, onBack = {}) } }
        compose.onNodeWithText(RunMetricsCopy.STATS_EMPTY).assertExists()
    }
}
