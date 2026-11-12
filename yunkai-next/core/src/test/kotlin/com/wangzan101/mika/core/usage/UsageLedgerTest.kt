package com.wangzan101.mika.core.usage

import com.wangzan101.mika.core.model.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class UsageLedgerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val hour = UsageLedger.HOUR_MS
    private var clock = 1_000 * hour + 123
    private val file get() = File(tmp.root, "usage/usage.json")
    // 写出不靠后台定时:测试里直接调 flush
    private fun ledger() = UsageLedger(file, now = { clock }, flushDelayMs = 60_000)

    private val main = UsageSource("p1", "服务商甲", "m1", UsagePurpose.Main)
    private val light = UsageSource("p1", "服务商甲", "m1", UsagePurpose.Light)
    private val other = UsageSource("p2", "服务商乙", "m9", UsagePurpose.Vision)

    private fun UsageLedger.row(h: Long, s: UsageSource): UsageCounts? =
        snapshot().rows.firstOrNull { it.hour == h && it.key == UsageKey(s.providerId, s.model, s.purpose) }?.counts

    @Test fun sameHourAndKeyAddsUp() {
        val l = ledger()
        l.record(main, Usage(100, 10, 40))
        l.record(main, Usage(50, 5))
        l.record(main, null)
        assertEquals(UsageCounts(150, 15, 40, calls = 3, callsWithoutUsage = 1), l.row(1000, main))
    }

    @Test fun differentPurposeHourOrProviderAreSeparateCells() {
        val l = ledger()
        l.record(main, Usage(1, 1))
        l.record(light, Usage(2, 2))
        l.record(other, Usage(3, 3))
        clock += hour
        l.record(main, Usage(4, 4))
        assertEquals(4, l.snapshot().rows.size)
        assertEquals(UsageCounts(2, 2, 0, 1, 0), l.row(1000, light))
        assertEquals(UsageCounts(4, 4, 0, 1, 0), l.row(1001, main))
        assertEquals(mapOf("p1" to "服务商甲", "p2" to "服务商乙"), l.snapshot().providerNames)
    }

    @Test fun versionMovesOnEveryRecord() {
        val l = ledger()
        val v = l.version.value
        l.record(main, null)
        l.record(main, null)
        assertEquals(v + 2, l.version.value)
    }

    @Test fun persistsAcrossInstances() {
        val l = ledger()
        l.record(main, Usage(100, 10, 40))
        l.record(other, null)
        clock += hour
        l.record(light, Usage(7, 3))
        l.flush()
        val back = ledger()
        assertEquals(l.snapshot().rows.toSet(), back.snapshot().rows.toSet())
        assertEquals(l.snapshot().providerNames, back.snapshot().providerNames)
        assertTrue("临时文件不留下", !File(file.parentFile, file.name + ".tmp").exists())
    }

    @Test fun flushWritesInBackgroundAfterTheDelay() {
        val l = UsageLedger(file, now = { clock }, flushDelayMs = 10)
        l.record(main, Usage(1, 2))
        val deadline = System.currentTimeMillis() + 5_000
        while (!file.isFile && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(UsageCounts(1, 2, 0, 1, 0), UsageLedger(file, now = { clock }).row(1000, main))
    }

    @Test fun prunesOlderThanKeepDays() {
        val l = UsageLedger(file, now = { clock }, flushDelayMs = 60_000, keepDays = 2)
        l.record(main, Usage(1, 1))
        clock += 49 * hour
        l.record(main, Usage(2, 2))
        l.flush()
        assertEquals("写出时丢掉两天以前的", listOf(1049L), l.snapshot().rows.map { it.hour })
        clock += 49 * hour
        val back = UsageLedger(file, now = { clock }, keepDays = 2)
        assertTrue("读入时也丢", back.snapshot().rows.isEmpty())
    }

    @Test fun corruptFileStartsEmpty() {
        file.parentFile.mkdirs()
        file.writeText("{not json")
        val l = ledger()
        assertTrue(l.snapshot().rows.isEmpty())
        l.record(main, Usage(1, 1))
        l.flush()
        assertEquals(1, ledger().snapshot().rows.size)
    }

    @Test fun concurrentRecordsAreAllCounted() {
        val l = ledger()
        val threads = 8
        val each = 2_000
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) { t ->
            pool.execute {
                start.await()
                repeat(each) { i ->
                    l.record(if (t % 2 == 0) main else light, if (i % 4 == 0) null else Usage(10, 1, 2))
                    if (i % 500 == 0) l.flush()
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        val rows = l.snapshot().rows
        assertEquals(threads * each.toLong(), rows.sumOf { it.counts.calls })
        assertEquals(threads * each / 4L, rows.sumOf { it.counts.callsWithoutUsage })
        assertEquals(threads * each * 3 / 4 * 10L, rows.sumOf { it.counts.input })
        l.flush()
        assertEquals(rows.toSet(), ledger().snapshot().rows.toSet())
    }
}
