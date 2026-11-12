package com.wangzan101.mika.core.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunMetricsTest {
    private fun started(at: Long = 1_000) = RunEvent.TaskStarted(at, "查一下天气", "m1")

    private fun reply(
        at: Long, step: Int, tool: String?, latency: Long = 0, retry: Long = 0, repliedAt: Long? = null,
        input: Int? = 100, output: Int? = 10, cached: Int? = 0, role: String? = null,
    ) = RunEvent.ModelReply(at, step, "", tool, null, input, output, cached, latency, role = role, retryWaitMs = retry, repliedAtMs = repliedAt)

    private fun result(at: Long, step: Int, tool: String, duration: Long, callId: String, source: String = "model", wait: Long = 0) =
        RunEvent.ToolResult(at, step, tool, "{\"ok\":true}", true, null, duration, wait, source = source, callId = callId)

    private fun activity(at: Long, step: Int, kind: String, label: String, callId: String, source: String = "model") =
        RunEvent.Activity(at, step, kind, label, source = source, callId = callId)

    /** 打开 App、读一屏、交回答:每一项都能对上。 */
    @Test fun computesEachFieldFromASimpleRun() {
        val events = listOf(
            started(),
            // 第 1 步:回复 3.2 秒时到手,执行完才记
            result(3_900, 1, "phone_launch_app", 700, "c1"),
            activity(3_900, 1, "act", "phone_launch_app", "c1"),
            reply(3_900, 1, "phone_launch_app", latency = 2_000, repliedAt = 3_200, input = 1_000, output = 50, cached = 600),
            result(5_400, 2, "phone_observe", 300, "c2", wait = 120),
            activity(5_400, 2, "observe", "phone_observe", "c2"),
            reply(5_400, 2, "phone_observe", latency = 1_000, repliedAt = 5_100, input = 2_000, output = 40, cached = 1_500),
            reply(7_000, 3, null, latency = 1_500, repliedAt = 7_000, input = 3_000, output = 200, cached = 2_500),
            RunEvent.TaskEnded(10_000, "succeeded", "晴"),
        )
        val m = RunMetrics.of(events, "r1")!!
        assertEquals("r1", m.runId)
        assertEquals("查一下天气", m.goal)
        assertEquals(1_000L, m.startedAtMs)
        assertEquals(9_000L, m.totalMs)
        assertTrue(m.ended)
        assertEquals(2_200L, m.firstFeedbackMs)
        assertEquals(3, m.modelCalls)
        assertEquals(6_000L, m.inputTokens)
        assertEquals(290L, m.outputTokens)
        assertEquals(4_600L, m.cachedTokens)
        assertEquals(6_290L, m.tokens)
        assertEquals(4_500L, m.modelMs)
        assertEquals(300L, m.observeMs)
        assertEquals(700L, m.actMs)
        assertEquals(120L, m.uiWaitMs)
        assertEquals(9_000L - 4_500 - 300 - 700, m.otherMs)
        assertEquals(1, m.observeCalls)
        assertEquals(1, m.actCalls)
        assertEquals(3, m.steps)
        assertEquals("succeeded", m.outcome)
        assertNull(m.failKind)
        assertTrue(m.succeeded)
        assertNull(m.allCalls)
    }

    @Test fun retryWaitsAreKeptOutOfModelTimeAndLandInOther() {
        val m = RunMetrics.of(listOf(
            started(0),
            reply(5_000, 1, null, latency = 1_000, retry = 3_000, repliedAt = 5_000),
            RunEvent.TaskEnded(5_000, "succeeded", "x"),
        ))!!
        assertEquals(1_000L, m.modelMs)
        assertEquals(3_000L, m.retryWaitMs)
        assertEquals(4_000L, m.otherMs)
    }

    @Test fun repliesWithoutUsageAreCountedButAddNoTokens() {
        val m = RunMetrics.of(listOf(
            started(0),
            reply(1_000, 1, "phone_observe", input = null, output = null, cached = null),
            reply(2_000, 2, null, input = 500, output = 20, cached = null),
            RunEvent.TaskEnded(2_000, "succeeded", "x"),
        ))!!
        assertEquals(2, m.modelCalls)
        assertEquals(1, m.callsWithoutUsage)
        assertEquals(500L, m.inputTokens)
        assertEquals(20L, m.outputTokens)
        assertEquals(0L, m.cachedTokens)
    }

    @Test fun failedRunKeepsItsKindAndIsNotASuccess() {
        val m = RunMetrics.of(listOf(started(0), RunEvent.TaskEnded(800, "failed", "模型出错", kind = "model")))!!
        assertEquals("failed", m.outcome)
        assertEquals("model", m.failKind)
        assertFalse(m.succeeded)
        assertNull(m.firstFeedbackMs)
    }

    @Test fun cancelledRunIsNotASuccess() {
        val m = RunMetrics.of(listOf(started(0), reply(400, 1, "phone_observe"), RunEvent.TaskEnded(900, "cancelled", "已取消")))!!
        assertEquals("cancelled", m.outcome)
        assertFalse(m.succeeded)
        assertEquals(900L, m.totalMs)
    }

    @Test fun unfinishedRunRunsToItsLastEvent() {
        val m = RunMetrics.of(listOf(started(0), reply(400, 1, "phone_observe"), result(1_200, 1, "phone_observe", 300, "c1")))!!
        assertFalse(m.ended)
        assertEquals(RunMetrics.UNFINISHED, m.outcome)
        assertEquals(1_200L, m.totalMs)
    }

    @Test fun unverifiedCountsAsSuccessUnlessJudgedOtherwise() {
        val base = listOf(started(0), reply(100, 1, null), RunEvent.TaskEnded(100, "unverified", "x"))
        assertTrue(RunMetrics.of(base)!!.succeeded)
        val partial = RunMetrics.of(base + RunEvent.OutcomeJudged(200, "partial"))!!
        assertEquals("partial", partial.judged)
        assertFalse(partial.succeeded)
        assertTrue(RunMetrics.of(base + RunEvent.OutcomeJudged(200, "done"))!!.succeeded)
    }

    @Test fun firstFeedbackSkipsRejectedLightRepliesAndUsesReplyArrival() {
        val m = RunMetrics.of(listOf(
            started(1_000),
            // 轻量模型的回复改派掉了,用户没看到
            reply(1_500, 1, "phone_tap", role = "light_rejected"),
            result(4_000, 1, "phone_observe", 500, "c1"),
            activity(4_000, 1, "observe", "phone_observe", "c1"),
            reply(4_000, 1, "phone_observe", repliedAt = 3_000, role = "main"),
            RunEvent.TaskEnded(5_000, "succeeded", "x"),
        ))!!
        assertEquals(2_000L, m.firstFeedbackMs)
        assertEquals(2, m.modelCalls)
        assertEquals(1, m.lightCalls)
    }

    @Test fun oldRecordsWithoutArrivalTimeUseTheReplyTime() {
        val m = RunMetrics.of(listOf(started(0), reply(2_500, 1, null), RunEvent.TaskEnded(2_500, "succeeded", "x")))!!
        assertEquals(2_500L, m.firstFeedbackMs)
    }

    @Test fun replayedStepIsFeedbackWithoutAnyModelCall() {
        val m = RunMetrics.of(listOf(
            started(0),
            result(900, 0, "phone_launch_app", 600, "r1", source = "replay"),
            activity(900, 0, "act", "phone_launch_app", "r1", source = "replay"),
            RunEvent.TaskEnded(1_000, "succeeded", "x"),
        ))!!
        assertEquals(900L, m.firstFeedbackMs)
        assertEquals(600L, m.actMs)
        assertEquals(0, m.modelCalls)
    }

    /** 一批动作:外层的耗时含子步,子步里的读计入读,其余算操作,不重复计。 */
    @Test fun batchSplitsItsInnerStepsWithoutDoubleCounting() {
        val m = RunMetrics.of(listOf(
            started(0),
            result(100, 2, "phone_observe", 200, "b.1", source = "batch"),
            activity(100, 2, "observe", "phone_observe", "b.1", source = "batch"),
            result(600, 2, "phone_tap", 500, "b.2", source = "batch"),
            activity(600, 2, "act", "phone_tap", "b.2", source = "batch"),
            result(1_000, 2, "do_steps", 1_000, "b"),
            RunEvent.TaskEnded(1_000, "succeeded", "x"),
        ))!!
        assertEquals(200L, m.observeMs)
        assertEquals(800L, m.actMs)
        assertEquals(1, m.observeCalls)
        assertEquals(1, m.actCalls)
    }

    /** 一批里各步做完后按条件等界面的时间(外层的 waitMs)算等待,落在其他里,不算操作。 */
    @Test fun batchConditionWaitsAreWaitingNotActing() {
        val m = RunMetrics.of(listOf(
            started(0),
            result(500, 2, "phone_tap", 500, "b.1", source = "batch"),
            activity(500, 2, "act", "phone_tap", "b.1", source = "batch"),
            result(4_000, 2, "do_steps", 4_000, "b", wait = 3_000),
            RunEvent.TaskEnded(4_000, "succeeded", "x"),
        ))!!
        assertEquals(1_000L, m.actMs)
        assertEquals(3_000L, m.uiWaitMs)
        assertEquals(3_000L, m.otherMs)
        assertEquals(0L, m.modelMs)
    }

    @Test fun scriptReadsInsideAnActionAreCountedAsReads() {
        val m = RunMetrics.of(listOf(
            started(0),
            result(300, 1, "web_read", 300, "s1", source = "script"),
            activity(300, 1, "observe", "web_read", "s1", source = "script"),
            result(1_000, 1, "web_script", 1_000, "c1"),
            activity(1_000, 1, "act", "web_script", "c1"),
            RunEvent.TaskEnded(1_000, "succeeded", "x"),
        ))!!
        assertEquals(300L, m.observeMs)
        assertEquals(700L, m.actMs)
    }

    @Test fun pacedDwellCountsAsWaitNotAction() {
        // 网页换页前补的停留记在 waitMs:算等待,不算操作
        val m = RunMetrics.of(listOf(
            started(0),
            result(5_000, 1, "web_tap", 5_000, "c1", wait = 4_000),
            activity(5_000, 1, "act", "web_tap", "c1"),
            RunEvent.TaskEnded(5_000, "succeeded", "x"),
        ))!!
        assertEquals(1_000L, m.actMs)
        assertEquals(4_000L, m.uiWaitMs)
    }

    @Test fun controlCallsAreNeitherReadsNorActions() {
        val m = RunMetrics.of(listOf(started(0), result(2_000, 1, "wait", 2_000, "c1"), RunEvent.TaskEnded(2_000, "succeeded", "x")))!!
        assertEquals(0L, m.observeMs)
        assertEquals(0L, m.actMs)
        assertEquals(2_000L, m.otherMs)
    }

    @Test fun allRequestsComeFromTheTallyEvent() {
        val m = RunMetrics.of(listOf(
            started(0), reply(100, 1, null), RunEvent.TaskEnded(100, "succeeded", "x"),
            RunEvent.ModelCalls(120, calls = 4, unreported = 1, inputTokens = 900, outputTokens = 30, cachedTokens = 0),
        ))!!
        assertEquals(4, m.allCalls)
        assertEquals(900L, m.allInputTokens)
        assertEquals(30L, m.allOutputTokens)
        // 收尾后的那一条不改总时长
        assertEquals(100L, m.totalMs)
    }

    @Test fun noStartEventMeansNoMetrics() {
        assertNull(RunMetrics.of(listOf(reply(100, 1, null))))
        assertNull(RunMetrics.of(emptyList()))
    }

    @Test fun builderFedOneByOneMatchesThePureFunction() {
        val events = listOf(
            started(0), reply(800, 1, "phone_observe", latency = 500, repliedAt = 600),
            result(800, 1, "phone_observe", 200, "c1"), activity(800, 1, "observe", "phone_observe", "c1"),
            RunEvent.TaskEnded(1_000, "succeeded", "x"),
        )
        val b = RunMetrics.Builder("r")
        events.forEach(b::add)
        assertEquals(RunMetrics.of(events, "r"), b.build())
    }

    @Test fun survivesAJsonRoundTrip() {
        val m = RunMetrics.of(listOf(started(0), reply(800, 1, null, repliedAt = 700), RunEvent.TaskEnded(1_000, "failed", "x", "guard")), "r")!!
        val back = RunMetricsStore.JSON.decodeFromString(RunMetrics.serializer(), RunMetricsStore.JSON.encodeToString(RunMetrics.serializer(), m))
        assertEquals(m, back)
    }

    @Test fun newEventFieldsDecodeFromOldLines() {
        val e = RunEvent.decode("""{"type":"task_ended","atMs":1,"outcome":"failed","answerOrReason":"x"}""") as RunEvent.TaskEnded
        assertNull(e.kind)
        val r = RunEvent.decode("""{"type":"model_reply","atMs":1,"step":1,"text":"","tool":null,"argsJson":null,"inputTokens":null,"outputTokens":null,"cachedTokens":null}""")
            as RunEvent.ModelReply
        assertNull(r.repliedAtMs)
        val calls = RunEvent.decode(RunEvent.encode(RunEvent.ModelCalls(5, 3, 1, 10, 2, 0)))
        assertEquals(RunEvent.ModelCalls(5, 3, 1, 10, 2, 0), calls)
    }
}
