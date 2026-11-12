package com.wangzan101.mika.ui

import com.wangzan101.mika.ChineseUi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.core.usage.UsageCounts
import com.wangzan101.mika.core.usage.UsageKey
import com.wangzan101.mika.core.usage.UsageLedger
import com.wangzan101.mika.core.usage.UsagePurpose
import com.wangzan101.mika.core.usage.UsageQuery
import com.wangzan101.mika.core.usage.UsageRange
import com.wangzan101.mika.core.usage.UsageReport
import com.wangzan101.mika.core.usage.UsageRow
import com.wangzan101.mika.core.usage.UsageSnapshot
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class UsageScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val now = Instant.parse("2026-10-27T10:00:00Z")
    private val today = LocalDate.of(2026, 10, 27)
    private val h = now.toEpochMilli() / UsageLedger.HOUR_MS

    private fun row(provider: String, model: String, purpose: UsagePurpose, input: Long, output: Long, calls: Long = 1, missing: Long = 0) =
        UsageRow(h, UsageKey(provider, model, purpose), UsageCounts(input, output, cached = input / 2, calls = calls, callsWithoutUsage = missing))

    private val snapshot = UsageSnapshot(
        listOf(
            row("p1", "chat-large", UsagePurpose.Main, 80_000, 20_000, calls = 5, missing = 2),
            row("p1", "chat-large", UsagePurpose.Light, 10_000, 5_000, calls = 3),
            row("p1", "chat-small", UsagePurpose.Vision, 3_000, 1_000),
            row("old", "legacy", UsagePurpose.Main, 500, 100),
        ),
        mapOf("old" to "旧服务商"),
    )

    private fun report(range: UsageRange = UsageRange.LastDays(7)): UsageReport =
        UsageQuery.report(snapshot, range, now, ZoneOffset.UTC, mapOf("p1" to "服务商甲"))

    private fun content(
        r: UsageReport = report(), preset: UsagePreset = UsagePreset.Week, dark: Boolean = false,
        onPreset: (UsagePreset) -> Unit = {}, onPickCustom: () -> Unit = {},
    ) {
        compose.setContent {
            MikaTheme(dark = dark) { UsageContent(r, preset, today, null, onPreset, onPickCustom, onSelect = {}, onBack = {}) }
        }
    }

    @Test
    fun showsTotalTrendRankingsAndNotes() {
        content()
        compose.onNodeWithText("10 月 21 日 – 今天").assertIsDisplayed()
        compose.onNodeWithText("总用量").assertIsDisplayed()
        compose.onNodeWithTag("usage-total").assertTextEquals("12 万")
        compose.onNodeWithText("10 次").assertIsDisplayed()
        compose.onNodeWithText("每天").assertIsDisplayed()
        compose.onNodeWithTag("usage-chart").assertIsDisplayed()
        compose.onNodeWithTag("usage-rank-provider-p1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("点一家看它的模型").assertExists()
        // 图例、服务商排行、胶囊各一处
        compose.onAllNodesWithText("旧服务商（已删除）").assertCountEquals(3)
        compose.onNodeWithTag("usage-rank-model-p1-chat-large").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("usage-rank-model-old-legacy").assertExists()
        compose.onNodeWithText("有 2 次调用服务商没报用量，没算进去。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(UsageCopy.NOTE).performScrollTo().assertIsDisplayed()
        // 不分用途
        compose.onAllNodesWithText("主模型").assertCountEquals(0)
    }

    @Test
    fun tappingAColumnShowsItsBreakdown() {
        content(report(UsageRange.LastDays(1)), dark = true)
        compose.onAllNodesWithTag("usage-chart-tip").assertCountEquals(0)
        compose.onNodeWithTag("usage-chart").performClick()
        compose.onNodeWithTag("usage-chart-tip").assertIsDisplayed()
        compose.onNodeWithText("10 月 27 日 · 12 万").assertIsDisplayed()
        compose.onNodeWithText("服务商甲　11.9 万").assertIsDisplayed()
    }

    @Test
    fun presetsAreReportedAndCustomAsksForDates() {
        val picked = mutableListOf<UsagePreset>()
        content(onPreset = { picked += it })
        compose.onNodeWithTag("usage-range-Day").performClick()
        compose.onNodeWithTag("usage-range-Custom").performClick()
        assertEquals(listOf(UsagePreset.Day, UsagePreset.Custom), picked)
        compose.onAllNodesWithTag("usage-change-dates").assertCountEquals(0)
    }

    @Test
    fun customRangeOffersChangeDates() {
        var custom = 0
        content(report(UsageRange.Custom(LocalDate.of(2026, 10, 1), today)), UsagePreset.Custom, onPickCustom = { custom++ })
        compose.onNodeWithText("10 月 1 日 – 10 月 27 日").assertIsDisplayed()
        compose.onNodeWithTag("usage-change-dates").performClick()
        assertEquals(1, custom)
    }

    @Test
    fun emptyRangeShowsEmptyState() {
        content(UsageQuery.report(UsageSnapshot.EMPTY, UsageRange.Last24Hours, now, ZoneOffset.UTC, emptyMap()), UsagePreset.Day, dark = true)
        compose.onNodeWithTag("usage-empty").assertIsDisplayed()
        compose.onNodeWithText(UsageCopy.EMPTY_TITLE).assertIsDisplayed()
        compose.onAllNodesWithTag("usage-chart").assertCountEquals(0)
        compose.onAllNodesWithTag("usage-missing").assertCountEquals(0)
    }

    /** 点排行里的一家选中它(再点取消),模型排行只列这家的;胶囊和排行是同一个选择,总用量不变。 */
    @Test
    fun selectingAProviderNarrowsTheModelRanking() {
        var selected by mutableStateOf<String?>(null)
        val r = report()
        compose.setContent {
            MikaTheme { UsageContent(r, UsagePreset.Week, today, selected, onPreset = {}, onPickCustom = {}, onSelect = { selected = it }, onBack = {}) }
        }
        compose.onNodeWithTag("usage-rank-provider-p1").performScrollTo().performClick()
        assertEquals("p1", selected)
        compose.onNodeWithTag("usage-total").assertTextEquals("12 万")
        compose.onAllNodesWithTag("usage-rank-model-old-legacy").assertCountEquals(0)
        compose.onNodeWithTag("usage-chip-p1").performScrollTo().assertIsSelected()

        compose.onNodeWithTag("usage-rank-provider-p1").performScrollTo().performClick()
        assertEquals(null, selected)
        compose.onNodeWithTag("usage-chip-old").performScrollTo().performClick()
        assertEquals("old", selected)
        compose.onAllNodesWithTag("usage-rank-model-p1-chat-large").assertCountEquals(0)
        compose.onNodeWithTag("usage-chip-all").performScrollTo().performClick()
        assertEquals(null, selected)
    }
}
