package com.wangzan101.mika.core.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class UsageQueryTest {
    private val utc = ZoneOffset.UTC
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val newYork = ZoneId.of("America/New_York")
    private val hour = UsageLedger.HOUR_MS

    private fun hourOf(t: Instant) = Math.floorDiv(t.toEpochMilli(), hour)
    private fun hourOf(local: LocalDateTime, zone: ZoneId) = hourOf(local.atZone(zone).toInstant())

    private fun row(h: Long, provider: String = "p1", model: String = "m1", purpose: UsagePurpose = UsagePurpose.Main,
                    input: Long = 0, output: Long = 0, cached: Long = 0, calls: Long = 1, missing: Long = 0) =
        UsageRow(h, UsageKey(provider, model, purpose), UsageCounts(input, output, cached, calls, missing))

    private fun snap(vararg rows: UsageRow, names: Map<String, String> = emptyMap()) = UsageSnapshot(rows.toList(), names)

    @Test fun last24HoursIsRollingAndHourly() {
        val now = Instant.parse("2026-10-27T10:20:00Z")
        val h = hourOf(now)
        val s = snap(row(h, input = 1), row(h - 23, input = 10), row(h - 24, input = 100), row(h + 1, input = 1000))
        val r = UsageQuery.report(s, UsageRange.Last24Hours, now, shanghai, mapOf("p1" to "甲"))
        assertTrue(r.hourly)
        assertEquals(24, r.series.size)
        assertEquals(11, r.totals.tokens)
        assertEquals(10, r.series.first().tokens)
        assertEquals(1, r.series.last().tokens)
        assertEquals("最后一根是此刻所在的小时,按本地时间", LocalDateTime.of(2026, 10, 27, 18, 0), r.series.last().start)
    }

    @Test fun lastDaysCountTodayAndUseLocalDays() {
        val now = Instant.parse("2026-10-27T03:00:00Z") // 上海 10-27 11:00,UTC 10-27 03:00
        // UTC 10-26 17:00 是上海 10-27 01:00
        val s = snap(row(hourOf(Instant.parse("2026-10-26T17:00:00Z")), input = 5))
        val sh = UsageQuery.report(s, UsageRange.LastDays(7), now, shanghai, emptyMap())
        assertFalse(sh.hourly)
        assertEquals(7, sh.series.size)
        assertEquals(LocalDate.of(2026, 10, 21).atStartOfDay(), sh.series.first().start)
        assertEquals(LocalDate.of(2026, 10, 27).atStartOfDay(), sh.series.last().start)
        assertEquals(5, sh.series.last().tokens)
        val u = UsageQuery.report(s, UsageRange.LastDays(7), now, utc, emptyMap())
        assertEquals("同一笔在 UTC 是前一天", 5, u.series[5].tokens)
        assertEquals(0, u.series.last().tokens)
    }

    @Test fun rangeStartsAtLocalMidnightOfFirstDay() {
        val now = Instant.parse("2026-10-27T03:00:00Z")
        val first = LocalDate.of(2026, 10, 21)
        val s = snap(
            row(hourOf(first.atStartOfDay(), shanghai), input = 1),
            row(hourOf(first.atStartOfDay(), shanghai) - 1, input = 100),
        )
        assertEquals(1, UsageQuery.report(s, UsageRange.LastDays(7), now, shanghai, emptyMap()).totals.tokens)
    }

    @Test fun dstDaysStillSplitAtLocalMidnight() {
        // 纽约 2026-03-08 凌晨 2 点进夏令时,那天只有 23 小时;11-01 出夏令时,那天 25 小时
        val now = LocalDateTime.of(2026, 3, 9, 12, 0).atZone(newYork).toInstant()
        val s = snap(
            row(hourOf(LocalDateTime.of(2026, 3, 6, 23, 0), newYork), input = 1000),
            row(hourOf(LocalDateTime.of(2026, 3, 8, 0, 0), newYork), input = 1),
            row(hourOf(LocalDateTime.of(2026, 3, 8, 23, 0), newYork), input = 2),
            row(hourOf(LocalDateTime.of(2026, 3, 9, 0, 0), newYork), input = 4),
        )
        val r = UsageQuery.report(s, UsageRange.LastDays(3), now, newYork, emptyMap())
        assertEquals(listOf(0L, 3L, 4L), r.series.map { it.tokens })
        assertEquals(7, r.totals.tokens)

        val fallNow = LocalDateTime.of(2026, 11, 2, 12, 0).atZone(newYork).toInstant()
        val day = LocalDate.of(2026, 11, 1)
        val start = hourOf(day.atStartOfDay(newYork).toInstant())
        val end = hourOf(day.plusDays(1).atStartOfDay(newYork).toInstant())
        assertEquals(25, end - start)
        val rows = (start - 1..end).map { row(it, input = 1) }.toTypedArray()
        val fall = UsageQuery.report(snap(*rows), UsageRange.LastDays(2), fallNow, newYork, emptyMap())
        assertEquals("11-01 的 25 个小时都算在那天,下一天零点那一小时归下一天", listOf(25L, 1L), fall.series.map { it.tokens })
    }

    @Test fun customRangeIsInclusiveAndSwapsReversedEnds() {
        val now = Instant.parse("2026-10-27T03:00:00Z")
        val s = snap(
            row(hourOf(LocalDate.of(2026, 10, 1).atStartOfDay(), utc), input = 1),
            row(hourOf(LocalDateTime.of(2026, 10, 3, 23, 0), utc), input = 2),
            row(hourOf(LocalDate.of(2026, 10, 4).atStartOfDay(), utc), input = 4),
        )
        val r = UsageQuery.report(s, UsageRange.Custom(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 1)), now, utc, emptyMap())
        assertEquals(3, r.series.size)
        assertEquals(listOf(1L, 0L, 2L), r.series.map { it.tokens })
        assertEquals(LocalDate.of(2026, 10, 1) to LocalDate.of(2026, 10, 3), UsageQuery.days(UsageRange.Custom(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 1)), LocalDate.of(2026, 10, 27)))
    }

    @Test fun totalsSortingSharesAndBreakdown() {
        val now = Instant.parse("2026-10-27T10:00:00Z")
        val h = hourOf(now)
        val s = snap(
            row(h, "p1", "a", UsagePurpose.Main, input = 300, output = 100, cached = 200, calls = 4, missing = 1),
            row(h - 1, "p1", "a", UsagePurpose.Light, input = 50, output = 50, calls = 2),
            row(h, "p1", "b", UsagePurpose.Vision, input = 100, calls = 1),
            row(h, "p2", "x", UsagePurpose.Main, input = 1000, output = 400, calls = 3),
            row(h, "gone", "y", UsagePurpose.Other, input = 0, calls = 2, missing = 2),
            names = mapOf("gone" to "旧服务商", "p1" to "旧名字"),
        )
        val r = UsageQuery.report(s, UsageRange.LastDays(1), now, utc, mapOf("p1" to "甲", "p2" to "乙"))
        assertEquals(UsageTotals(1450, 550, 200, 12, 3), r.totals)
        assertEquals(2000, r.totals.tokens)
        assertEquals(listOf("p2", "p1", "gone"), r.providers.map { it.id })
        assertEquals(0.7, r.providers[0].share, 1e-9)
        assertEquals(0.3, r.providers[1].share, 1e-9)

        val p1 = r.provider("p1")!!
        assertEquals("现在的名字优先", "甲", p1.name)
        assertFalse(p1.deleted)
        assertEquals(listOf("a", "b"), p1.models.map { it.model })
        assertEquals(500.0 / 600, p1.models[0].share, 1e-9)
        val a = p1.models[0]
        assertEquals(listOf(UsagePurpose.Main, UsagePurpose.Light), a.purposes.map { it.purpose })
        assertEquals(0.8, a.purposes[0].share, 1e-9)
        assertEquals(UsageTotals(300, 100, 200, 4, 1), a.purposes[0].totals)

        val gone = r.provider("gone")!!
        assertTrue(gone.deleted)
        assertEquals("已删除的用记账时的名字", "旧服务商", gone.name)
        assertEquals(0.0, gone.share, 0.0)
        assertEquals(2, gone.totals.callsWithoutUsage)
    }

    @Test fun barsSplitByProvider() {
        val now = Instant.parse("2026-10-27T10:00:00Z")
        val h = hourOf(now)
        val s = snap(row(h, "p1", input = 30), row(h, "p1", "b", input = 5), row(h, "p2", input = 10), row(h, "p3", calls = 1, missing = 1))
        val r = UsageQuery.report(s, UsageRange.Last24Hours, now, utc, emptyMap())
        assertEquals(UsageStep.Hour, r.step)
        assertEquals(mapOf("p1" to 35L, "p2" to 10L), r.series.last().byProvider)
        assertEquals(45, r.series.last().tokens)
    }

    @Test fun longRangesGoWeeklyFromTheFirstDay() {
        val now = Instant.parse("2026-10-27T10:00:00Z")
        val today = LocalDate.of(2026, 10, 27)
        val s = snap(
            row(hourOf(today.minusDays(89).atStartOfDay(), utc), input = 1),
            row(hourOf(today.minusDays(83).atStartOfDay(), utc), input = 2),
            row(hourOf(today.minusDays(82).atStartOfDay(), utc), input = 4),
            row(hourOf(today.atStartOfDay(), utc), input = 8),
        )
        val r = UsageQuery.report(s, UsageRange.LastDays(90), now, utc, emptyMap())
        assertEquals(UsageStep.Week, r.step)
        assertEquals(13, r.series.size)
        assertEquals(3L, r.series[0].tokens)
        assertEquals(today.minusDays(83), r.series[0].lastDay)
        assertEquals(4L, r.series[1].tokens)
        assertEquals("最后一格不满 7 天,截到今天", today, r.series.last().lastDay)
        assertEquals(8L, r.series.last().tokens)
        assertEquals(15, r.totals.tokens)
        assertEquals("45 天还按天", UsageStep.Day, UsageQuery.report(s, UsageRange.Custom(today.minusDays(44), today), now, utc, emptyMap()).step)
        assertEquals(UsageStep.Week, UsageQuery.report(s, UsageRange.Custom(today.minusDays(45), today), now, utc, emptyMap()).step)
        assertEquals(UsageStep.Day, UsageQuery.report(s, UsageRange.LastDays(30), now, utc, emptyMap()).step)
    }

    @Test fun providerOrderIsLiveOrderThenDeletedByFirstUse() {
        val now = Instant.parse("2026-10-27T10:00:00Z")
        val h = hourOf(now)
        val s = snap(
            row(h, "b", input = 1), row(h, "a", input = 100),
            row(h - 50, "gone2", input = 5), row(h - 1000, "gone1", input = 5), row(h, "gone1", input = 1),
        )
        val live = linkedMapOf("b" to "乙", "a" to "甲")
        val r = UsageQuery.report(s, UsageRange.Last24Hours, now, utc, live)
        assertEquals(listOf("a", "b", "gone1"), r.providers.map { it.id })
        assertEquals(mapOf("b" to 0, "a" to 1, "gone1" to 2), r.providers.associate { it.id to it.order })
        val wider = UsageQuery.report(s, UsageRange.LastDays(90), now, utc, live)
        assertEquals("换范围不变色", 2, wider.provider("gone1")!!.order)
        assertEquals(3, wider.provider("gone2")!!.order)
    }

    @Test fun emptyLedgerGivesZeroSeries() {
        val r = UsageQuery.report(UsageSnapshot.EMPTY, UsageRange.LastDays(30), Instant.parse("2026-10-27T03:00:00Z"), shanghai, emptyMap())
        assertEquals(30, r.series.size)
        assertEquals(UsageTotals(), r.totals)
        assertTrue(r.providers.isEmpty())
    }

    @Test fun purposeKeysRoundTripAndUnknownIsOther() {
        UsagePurpose.entries.forEach { assertEquals(it, UsagePurpose.of(it.key)) }
        assertEquals(UsagePurpose.Other, UsagePurpose.of("summarize"))
    }
}
