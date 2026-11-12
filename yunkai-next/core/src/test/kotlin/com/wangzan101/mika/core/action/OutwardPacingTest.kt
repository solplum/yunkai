package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 对外动作之间的间隔:第一次不等、隔够了不等、别的网站各算各的,间隔有上下限且随次数变长;执行入口照它等,等的时间记成等待。 */
class OutwardPacingTest {
    private val limits = OutwardPacing.Limits()

    @Test fun firstActionOnASiteDoesNotWait() {
        val p = OutwardPacing.human(Random(1))
        assertEquals(0, p.waitBefore("example.com", 0))
        p.noteDone("example.com", 0)
        assertEquals(0, p.waitBefore("other.com", 10))
        assertEquals(0, OutwardPacing.Off.also { it.noteDone("example.com", 0) }.waitBefore("example.com", 1))
    }

    @Test fun gapIsWithinTheFirstRangeAndCountsElapsedTime() {
        repeat(50) { seed ->
            val p = OutwardPacing.human(Random(seed))
            p.noteDone("example.com", 1_000)
            val gap = p.waitBefore("example.com", 1_000)
            assertTrue("$gap", gap in limits.firstGapMs)
            // 中间读页面、想下一步花掉的时间算在间隔里
            assertEquals(gap - 3_000, p.waitBefore("example.com", 4_000))
            assertEquals(0, p.waitBefore("example.com", 1_000 + gap))
        }
    }

    @Test fun gapGrowsWithCountAndNeverPassesTheCap() {
        val p = OutwardPacing.human(Random(3))
        var t = 0L
        for (n in 1..40) {
            p.noteDone("example.com", t)
            val gap = p.waitBefore("example.com", t)
            val lo = (limits.firstGapMs.first + (n - 1) * limits.perActionMs.first).coerceAtMost(limits.maxGapMs)
            assertTrue("action $n: $gap", gap in lo..limits.maxGapMs)
            t += gap
        }
    }

    @Test fun oldActionsFallOutOfTheWindow() {
        val p = OutwardPacing.human(Random(5), OutwardPacing.Limits(windowMs = 60_000))
        p.noteDone("example.com", 0)
        assertEquals(0, p.waitBefore("example.com", 61_000))
    }

    @Test fun sitesAreRegistrableDomainsOnTheWebAndPackagesOnThePhone() {
        assertEquals("example.com", OutwardPacing.siteOf("www.example.com", web = true))
        assertEquals("example.com", OutwardPacing.siteOf("m.example.com", web = true))
        assertEquals("com.chat.app", OutwardPacing.siteOf("com.chat.app", web = false))
        assertEquals(null, OutwardPacing.siteOf(null, web = false))
    }

    /** 一屏:一个「立即沟通」、一个普通按钮;记下点了什么。 */
    private class GreetPage : ScreenPort by FakeScreen() {
        val taps = mutableListOf<String>()
        private val shown = listOf(
            UiElement("@e1", "button", "立即沟通", 900, 300, listOf(800, 100, 999, 160), null, true, false, false),
            UiElement("@e2", "button", "下一个", 900, 1300, listOf(800, 500, 999, 560), null, true, false, false),
        )
        override suspend fun snapshot() = ScreenSnapshot("s1", "com.jobs", shown, 1080, 2400)
        override suspend fun peek() = snapshot()
        override suspend fun tapRef(ref: String, snapshotId: String) = ActionResult.Ok(changed = true).also { taps += ref }
    }

    // 自主执行时也照判定隔开:第二次「立即沟通」前等够间隔,记成这次调用的等待,不算进操作用时;普通点击不等
    @Test fun executorWaitsBetweenOutwardTapsAndRecordsTheWait() = runTest {
        val events = mutableListOf<RunEvent>()
        val sink = object : RunSink { override fun event(e: RunEvent) { events += e } }
        val screen = GreetPage()
        val tools = toolDispatcher(screen)
        tools.execute(Part.ToolCall("o", "phone_observe", "{}"))
        val pacing = OutwardPacing.human(Random(9))
        val executor = ActionExecutor(tools, OperationGuards(), null, gate = {}, recorder = ActionRecorder(sink, { currentTime }), autonomous = true, pacing = pacing)
        suspend fun tap(id: String, ref: String) = executor.run(Part.ToolCall(id, "phone_tap", """{"ref":"$ref","snapshotId":"s1"}"""), Origin.Model(1))
        tap("c1", "@e1")
        val gap = pacing.waitBefore("com.jobs", currentTime)
        assertTrue("$gap", gap in limits.firstGapMs)
        val before = currentTime
        tap("c2", "@e2")
        val spent = currentTime - before
        tap("c3", "@e1")
        assertEquals(listOf("@e1", "@e2", "@e1"), screen.taps)
        val results = events.filterIsInstance<RunEvent.ToolResult>().associateBy { it.callId }
        assertEquals(0L, results.getValue("c1").waitMs)
        assertEquals(0L, results.getValue("c2").waitMs)
        // 普通点击花掉的时间算在间隔里,只补不够的部分
        assertEquals(gap - spent, results.getValue("c3").waitMs)
        assertTrue(results.getValue("c3").durationMs < results.getValue("c3").waitMs)
        // 活动上标出哪几次是对外动作:停止后交代时按它数发出了几次
        val outward = events.filterIsInstance<RunEvent.Activity>().associate { it.callId to it.outward }
        assertEquals(mapOf("c1" to true, "c2" to false, "c3" to true), outward)
    }
}
