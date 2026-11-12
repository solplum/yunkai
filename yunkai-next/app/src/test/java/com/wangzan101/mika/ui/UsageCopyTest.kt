package com.wangzan101.mika.ui

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.usage.ProviderUsage
import com.wangzan101.mika.core.usage.UsageBucket
import com.wangzan101.mika.core.usage.UsageCounts
import com.wangzan101.mika.core.usage.UsageKey
import com.wangzan101.mika.core.usage.UsagePurpose
import com.wangzan101.mika.core.usage.UsageQuery
import com.wangzan101.mika.core.usage.UsageRange
import com.wangzan101.mika.core.usage.UsageRow
import com.wangzan101.mika.core.usage.UsageSnapshot
import com.wangzan101.mika.core.usage.UsageStep
import com.wangzan101.mika.core.usage.UsageTotals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class UsageCopyTest {
    @get:Rule val texts = ChineseTexts()

    private val today = LocalDate.of(2026, 10, 27)

    @Test fun tokensUseWanAndYiWithThreeSignificantDigits() {
        assertEquals("0", UsageCopy.tokens(0))
        assertEquals("9999", UsageCopy.tokens(9_999))
        assertEquals("1 万", UsageCopy.tokens(10_000))
        assertEquals("1.23 万", UsageCopy.tokens(12_345))
        assertEquals("1.24 万", UsageCopy.tokens(12_350))
        assertEquals("12.3 万", UsageCopy.tokens(123_456))
        assertEquals("123 万", UsageCopy.tokens(1_234_567))
        assertEquals("1235 万", UsageCopy.tokens(12_345_678))
        assertEquals("10 万", UsageCopy.tokens(99_999))
        assertEquals("进位到一亿时改写成亿", "1 亿", UsageCopy.tokens(99_999_999))
        assertEquals("1.5 亿", UsageCopy.tokens(150_000_000))
        assertEquals("12.3 亿", UsageCopy.tokens(1_234_567_890))
    }

    @Test fun settingsValueSaysNoneForZero() {
        assertEquals("还没有", UsageCopy.settingsValue(0))
        assertEquals("近 7 天 12.3 万", UsageCopy.settingsValue(123_000))
        assertEquals("近 7 天 800", UsageCopy.settingsValue(800))
    }

    @Test fun missingNote() {
        assertEquals("有 3 次调用服务商没报用量，没算进去。", UsageCopy.missing(3))
    }

    @Test fun rangeAndAxisText() {
        assertEquals("过去 24 小时", UsageCopy.rangeText(UsageRange.Last24Hours, today))
        assertEquals("10 月 21 日 – 今天", UsageCopy.rangeText(UsageRange.LastDays(7), today))
        assertEquals("10 月 1 日 – 10 月 27 日", UsageCopy.rangeText(UsageRange.Custom(LocalDate.of(2026, 10, 1), today), today))
        assertEquals("2025 年 12 月 30 日 – 1 月 2 日",
            UsageCopy.rangeText(UsageRange.Custom(LocalDate.of(2026, 1, 2), LocalDate.of(2025, 12, 30)), today))
        assertEquals("10 月 3 日", UsageCopy.rangeText(UsageRange.Custom(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 3)), today))
        val hour = UsageBucket(LocalDateTime.of(2026, 10, 27, 18, 0), 0)
        assertEquals("18:00", UsageCopy.axis(hour, UsageStep.Hour, today))
        assertEquals("现在", UsageCopy.axis(hour, UsageStep.Hour, today, last = true))
        assertEquals("10/21", UsageCopy.axis(UsageBucket(LocalDate.of(2026, 10, 21).atStartOfDay(), 0), UsageStep.Day, today))
        assertEquals("今天", UsageCopy.axis(UsageBucket(today.atStartOfDay(), 0), UsageStep.Day, today))
    }

    @Test fun deletedNames() {
        val p = ProviderUsage("x", "旧服务商", deleted = true, totals = UsageTotals(), share = 0.0, models = emptyList())
        assertEquals("旧服务商（已删除）", UsageCopy.providerName(p))
        assertEquals("旧服务商", UsageCopy.providerName(p.copy(deleted = false)))
    }

    private val now = Instant.parse("2026-10-27T10:00:00Z")
    private val h = now.toEpochMilli() / 3_600_000
    private val snap = UsageSnapshot(
        listOf(
            UsageRow(h, UsageKey("p1", "a", UsagePurpose.Main), UsageCounts(input = 100, calls = 1)),
            UsageRow(h, UsageKey("p1", "b", UsagePurpose.Light), UsageCounts(input = 10, calls = 1)),
            UsageRow(h, UsageKey("p2", "c", UsagePurpose.Main), UsageCounts(input = 50, calls = 1)),
            UsageRow(h, UsageKey("p3", "d", UsagePurpose.Main), UsageCounts(input = 40, calls = 1)),
            UsageRow(h, UsageKey("p4", "e", UsagePurpose.Main), UsageCounts(input = 1, calls = 1)),
        ),
        mapOf("p2" to "旧服务商"),
    )
    private val report = UsageQuery.report(snap, UsageRange.LastDays(7), now, ZoneOffset.UTC, linkedMapOf("p1" to "甲", "p3" to "丙", "p4" to "丁"))

    @Test fun modelRankingIsAllProvidersOrJustTheSelectedOne() {
        val all = UsageCopy.modelRanking(report, null)
        assertEquals(listOf("a", "c", "d", "b", "e"), all.map { it.model.model })
        assertEquals(listOf("p1", "p2", "p3", "p1", "p4"), all.map { it.provider.id })
        assertEquals(100.0 / 201, all.first().share, 1e-9)
        val p1 = UsageCopy.modelRanking(report, "p1")
        assertEquals(listOf("a", "b"), p1.map { it.model.model })
        assertEquals("占比按这一排算", 100.0 / 110, p1.first().share, 1e-9)
    }

    @Test fun colorsFollowTheFixedOrderAndFoldBeyondThree() {
        // 连着的按连接先后:甲 0、丙 1、丁 2,删掉的排在后面
        assertEquals(mapOf("p1" to 0, "p3" to 1, "p4" to 2, "p2" to null), report.providers.associate { it.id to UsageCopy.colorSlot(it) })
    }

    @Test fun niceMaxGivesRoundGridlines() {
        assertEquals(1, UsageCopy.niceMax(0))
        assertEquals(1, UsageCopy.niceMax(1))
        assertEquals(10, UsageCopy.niceMax(9))
        assertEquals(20, UsageCopy.niceMax(11))
        assertEquals(400_000, UsageCopy.niceMax(312_345))
        assertEquals(1_000_000, UsageCopy.niceMax(812_345))
        assertEquals(1_000_000, UsageCopy.niceMax(1_000_000))
    }

    @Test fun periodAndStepText() {
        assertEquals("每小时", UsageCopy.stepLabel(UsageStep.Hour))
        assertEquals("每周", UsageCopy.stepLabel(UsageStep.Week))
        val week = UsageBucket(LocalDate.of(2026, 10, 1).atStartOfDay(), 0, lastDay = LocalDate.of(2026, 10, 7))
        assertEquals("10 月 1 日 – 10 月 7 日", UsageCopy.period(week, UsageStep.Week, today))
        assertEquals("10 月 1 日", UsageCopy.period(week, UsageStep.Day, today))
        assertEquals("0:00", UsageCopy.period(week, UsageStep.Hour, today))
        assertEquals("45%", UsageCopy.percent(0.449))
        assertEquals("<1%", UsageCopy.percent(0.004))
    }
}
