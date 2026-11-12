package com.wangzan101.mika.record

import com.wangzan101.mika.core.record.RunEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RunStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private var clock = 1_760_000_000_000L
    private fun store(keep: Int = 30, external: File? = null, encode: (ByteArray) -> ByteArray? = { png -> png + byteArrayOf(9) }) =
        RunStore(tmp.root.resolve("files"), { external }, keep, { clock }, encode)

    @Test fun writesRunJsonEventsAndScreens() {
        val s = store()
        val h = s.open("查天气")
        h.event(RunEvent.TaskStarted(clock, "查天气", "m1"))
        h.event(RunEvent.Criteria(clock, listOf("看到今天的气温"), emptyList()))
        h.event(RunEvent.ModelReply(clock, 1, "看一下", "phone_observe", "{}", 100, 10, 50))
        h.event(RunEvent.ToolResult(clock, 1, "phone_observe", "{\"ok\":true}", null, 1))
        h.media(1, byteArrayOf(1, 2))
        h.event(RunEvent.Media(clock, 1, 1, "com.x"))
        h.event(RunEvent.ModelReply(clock, 2, "", "finish", "{}", 200, 20, null))
        h.event(RunEvent.Verifier(clock, "pass", "{}"))
        clock += 5_000
        h.event(RunEvent.TaskEnded(clock, "succeeded", "25 度"))
        h.close()

        val dir = tmp.root.resolve("files/runs/${h.runId}")
        assertTrue(h.runId, Regex("""\d{8}-\d{6}-[a-z0-9]{6}""").matches(h.runId))
        assertTrue(dir.resolve("run.json").isFile)
        assertEquals(8, dir.resolve("events.jsonl").readLines().size)
        assertArrayEquals(byteArrayOf(1, 2, 9), dir.resolve("screens/0001.jpg").readBytes())
        assertEquals(dir.resolve("screens/0001.jpg"), s.screen(h.runId, 1))
        assertNull(s.screen(h.runId, 2))

        val sum = s.list().single()
        assertEquals(h.runId, sum.id)
        assertEquals("查天气", sum.goal)
        assertEquals("m1", sum.model)
        assertEquals("succeeded", sum.outcome)
        assertEquals("25 度", sum.answerOrReason)
        assertEquals(listOf("看到今天的气温"), sum.criteria)
        assertEquals("pass", sum.verdict)
        assertEquals(2, sum.steps)
        assertEquals(300, sum.inputTokens); assertEquals(30, sum.outputTokens); assertEquals(50, sum.cachedTokens)
        assertEquals(sum.startedAtMs + 5_000, sum.endedAtMs)
    }

    // 跑起来过的运行关上时记一份小结;交出回答后判的结局补进去;记录清掉了小结还在
    @Test fun closingWritesMetricsThatOutliveTheRecord() {
        val s = store(keep = 1)
        val h = s.open("查天气")
        h.event(RunEvent.TaskStarted(clock, "查天气", "m1"))
        h.event(RunEvent.ModelReply(clock + 2_000, 1, "", null, null, 100, 10, null, 1_500, repliedAtMs = clock + 2_000))
        h.event(RunEvent.TaskEnded(clock + 3_000, "succeeded", "晴"))
        h.close()
        val m = s.metrics.all().single()
        assertEquals(h.runId, m.runId)
        assertEquals(3_000L, m.totalMs)
        assertEquals(2_000L, m.firstFeedbackMs)
        s.append(h.runId, RunEvent.OutcomeJudged(clock + 4_000, "partial"))
        assertEquals("partial", s.metrics.all().single().judged)

        clock += 1_000
        s.open("别的").close()
        assertTrue(s.list().none { it.id == h.runId })
        assertEquals(listOf(h.runId), s.metrics.all().map { it.runId })
    }

    @Test fun runThatNeverStartedWritesNoMetrics() {
        val s = store()
        s.open("没跑起来").close()
        assertTrue(s.metrics.all().isEmpty())
    }

    @Test fun keepsOnlyLatest30() {
        val s = store()
        val ids = (1..35).map {
            clock += 1_000
            s.open("任务 $it").also { h -> h.close() }.runId
        }
        val left = s.list().map { it.id }
        assertEquals(30, left.size)
        assertEquals(ids.drop(5).reversed(), left) // 新的在前
        ids.take(5).forEach { assertFalse(tmp.root.resolve("files/runs/$it").exists()) }
    }

    @Test fun eventsRoundTrip() {
        val s = store()
        val h = s.open("g")
        val written = listOf(
            RunEvent.TaskStarted(1, "g", "m"),
            RunEvent.ToolResult(2, 1, "phone_tap", "{\"ok\":true,\"note\":\"换行\\n也行\"}", true, null),
            RunEvent.Takeover(3, "login", "handed_back", 1200, 4),
            RunEvent.TaskEnded(4, "failed", "没办成"),
        )
        written.forEach(h::event)
        h.close()
        assertEquals(written, s.events(h.runId))
        assertEquals(emptyList<RunEvent>(), s.events("no-such-run"))
    }

    @Test fun mirrorCopiesRunToExternalDir() {
        val ext = tmp.newFolder("ext")
        val s = store(external = ext)
        val h = s.open("g")
        h.event(RunEvent.TaskStarted(1, "g", "m"))
        h.media(1, byteArrayOf(5))
        h.close()
        s.mirrorToExternal(h.runId)
        assertTrue(ext.resolve("runs/${h.runId}/run.json").isFile)
        assertTrue(ext.resolve("runs/${h.runId}/events.jsonl").isFile)
        assertTrue(ext.resolve("runs/${h.runId}/screens/0001.jpg").isFile)
    }

    @Test fun goalInRunJsonIsRedacted() {
        val key = "sk-" + "abcdefghijklmnopqrstuvwx"
        val s = store()
        s.open("用 $key 调接口").close()
        val goal = s.list().single().goal
        assertFalse(goal.contains(key))
        assertTrue(goal.contains("***"))
    }

    @Test fun slowEncoderNeverBlocksCallerAndDropsOldestFrames() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val s = store(encode = { png -> entered.countDown(); release.await(5, TimeUnit.SECONDS); png })
        val h = s.open("g")
        val t0 = System.nanoTime()
        h.media(1, byteArrayOf(1))
        assertTrue(entered.await(5, TimeUnit.SECONDS)) // 写线程卡在第 1 张上
        for (id in 2..20) h.media(id, byteArrayOf(id.toByte()))
        repeat(50) { h.event(RunEvent.State(clock, "x", "$it")) }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
        assertTrue("caller blocked for $elapsedMs ms", elapsedMs < 1_000)
        release.countDown()
        h.close()
        val saved = (1..20).filter { s.screen(h.runId, it) != null }
        // 卡住的那张 + 积压里最新的几张
        assertEquals(listOf(1) + (21 - RunHandle.MAX_PENDING_FRAMES..20).toList(), saved)
        assertEquals(50, s.events(h.runId).size)
    }

    @Test fun runJsonRefreshedBeforeClose() {
        val s = store()
        val h = s.open("g")
        for (step in 1..RunHandle.SUMMARY_EVERY) h.event(RunEvent.ModelReply(clock, step, "", "phone_observe", "{}", 10, 1, null))
        val deadline = System.currentTimeMillis() + 5_000
        while (s.list().single().steps < RunHandle.SUMMARY_EVERY && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(RunHandle.SUMMARY_EVERY, s.list().single().steps)
        h.close()
    }

    @Test fun externalMirrorKeepsOnlyLatest() {
        val ext = tmp.newFolder("ext")
        val s = store(keep = 2, external = ext)
        val ids = (1..3).map {
            clock += 1_000
            s.open("g$it").also { h -> h.close(); s.mirrorToExternal(h.runId) }.runId
        }
        assertEquals(ids.drop(1), ext.resolve("runs").list()!!.sorted())
    }
}
