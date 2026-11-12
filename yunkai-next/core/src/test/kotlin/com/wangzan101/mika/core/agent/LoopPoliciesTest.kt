package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.tool.placeAfter
import com.wangzan101.mika.core.guard.GuardVerdict
import com.wangzan101.mika.core.guard.Severity
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.tool.Outcome
import com.wangzan101.mika.core.tool.Verb
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 主循环的各条策略:只看传进去的快照,返回决定。 */
class LoopPoliciesTest {
    private fun warn(code: String) = GuardVerdict(code, Severity.Warn, JsonObject(emptyMap()))

    // ---- 回合 ----

    @Test fun progressChecksAtHalfAndThreeQuarters() {
        val at = (1..21).filter { TurnPolicy.beforeStep(TurnSnapshot(it, 20)) != null }
        assertEquals(listOf(11, 16), at)
        assertEquals(Feedback.format("progress_check", "used" to 10, "max" to 20, "answer_or_failure" to ANSWER_OR_FAILURE),
            TurnPolicy.beforeStep(TurnSnapshot(11, 20))!!.text)
    }

    @Test fun shortTasksGetNoProgressCheck() {
        assertTrue((1..9).all { TurnPolicy.beforeStep(TurnSnapshot(it, 9)) == null })
    }

    @Test fun emptyReplyIsNudgedOnceThenStops() {
        assertEquals(Feedback["empty_reply"], TurnPolicy.afterEmpty(already = false, truncated = false)!!.text)
        assertEquals(Feedback["truncated_reply"], TurnPolicy.afterEmpty(already = false, truncated = true)!!.text)
        assertNull(TurnPolicy.afterEmpty(already = true, truncated = false))
        assertEquals(FailKind.Model, StopRules.noAnswer().kind)
    }

    @Test fun unconfirmedNudgeListsTheItems() {
        val n = TurnPolicy.unconfirmed(listOf("¥89", "「已发送」"))
        assertEquals(Feedback["unconfirmed_source"], n.source)
        assertTrue(n.text, "- ¥89\n- 「已发送」" in n.text)
    }

    @Test fun finalTurnOffersOnlyReportFailure() {
        val specs = listOf("ask_user", "end_not_done", "phone_tap").map { ToolSpec(it, "", "{}") }
        val verbs = mapOf("ask_user" to Verb.AskUser, "end_not_done" to Verb.EndNotDone, "phone_tap" to Verb.Tap)
        assertEquals(listOf("end_not_done"), TurnPolicy.finalTools(specs, verbs::get).map { it.name })
        assertEquals(Feedback["final_turn"], TurnPolicy.finalTurn().text)
    }

    // ---- 停止 ----

    @Test fun giveUpEndsBeforeGuardStop() {
        val stop = GuardVerdict("no_progress", Severity.Stop, JsonObject(emptyMap()))
        assertEquals(FailKind.GaveUp, (StopRules.afterAction(Outcome.Failure("x"), stop) as TaskState.Failed).kind)
        assertEquals(FailKind.Guard, (StopRules.afterAction(null, stop) as TaskState.Failed).kind)
        assertNull(StopRules.afterAction(null, null))
    }

    @Test fun finalTurnWithoutCallIsStepLimit() {
        assertEquals(StopRules.stepLimit(3), StopRules.afterFinalTurn(null, 3))
        assertEquals(FailKind.StepLimit, StopRules.stepLimit(3).kind)
    }

    @Test fun modelFailureReasons() {
        assertTrue("503" in StopRules.modelFailed(ModelError.Server(503)).reason)
        assertEquals("模型调用异常:boom", StopRules.modelFailed(IllegalStateException("boom")).reason)
    }

    // ---- 卡住提醒 ----

    @Test fun giveUpAdviceFromSecondStuckWarning() {
        val (s1, a1) = StuckAdvice.next(StuckState(), StuckInput(listOf(warn("no_progress")), null, "com.x"))
        assertNull(a1)
        val (s2, a2) = StuckAdvice.next(s1, StuckInput(listOf(warn("read_loop")), null, "com.x"))
        assertEquals(2, s2.warnings)
        assertEquals(Feedback.format("give_up_advice", "answer_or_failure" to ANSWER_OR_FAILURE), a2)
        // 不是卡住类的结论不算
        assertNull(StuckAdvice.next(StuckState(), StuckInput(listOf(warn("too_many_failures")), null, null)).second)
    }

    @Test fun progressResetsStuckAdviceHistory() {
        val first = StuckAdvice.next(StuckState(), StuckInput(listOf(warn("no_progress")), null, "com.x")).first
        val (afterProgress, advice) = StuckAdvice.next(first, StuckInput(listOf(warn("no_progress")), null, "com.x", progressed = true))
        assertEquals(1, afterProgress.warnings)
        assertNull(advice)
    }

    @Test fun revisitingOldStatesSuggestsAQuestionWhenUserChoiceIsMissing() {
        val first = StuckAdvice.next(StuckState(), StuckInput(listOf(warn("no_new_state")), null, "com.x")).first
        val advice = StuckAdvice.next(first, StuckInput(listOf(warn("no_new_state")), null, "com.x")).second
        assertTrue(advice.orEmpty().contains("ask_user"))
    }

    @Test fun repeatedInputCountsPerPlace() {
        var s = StuckState()
        fun type(place: String): String? = StuckAdvice.next(s, StuckInput(emptyList(), "咖啡", place)).also { s = it.first }.second
        assertNull(type("com.a"))
        assertNull(type("com.b"))
        assertNull(type("com.a"))
        val advice = StuckAdvice.next(s, StuckInput(listOf(warn("no_progress")), "咖啡", "com.a")).second
        assertEquals(Feedback.format("repeated_input_advice", "text" to "咖啡", "times" to 3, "answer_or_failure" to ANSWER_OR_FAILURE), advice)
    }

    @Test fun typedTextOnlyForSucceededNonSecretInput() {
        val args = buildJsonObject { put("text", " 咖啡 ") }
        val ok = buildJsonObject { put("ok", true); put("secret", false) }
        assertEquals("咖啡", StuckAdvice.typedText(Verb.TypeText, args, ok))
        assertNull(StuckAdvice.typedText(Verb.TypeText, args, buildJsonObject { put("ok", true) }))
        assertNull(StuckAdvice.typedText(Verb.TypeText, args, buildJsonObject { put("ok", false); put("secret", false) }))
        assertNull(StuckAdvice.typedText(Verb.Tap, args, ok))
        assertNull(StuckAdvice.typedText(Verb.TypeText, args, null))
    }

    // ---- 结果之后的说明 ----

    @Test fun imageNotes() {
        assertEquals(Feedback["recalled_note"], NoticePolicy.imageNote(Verb.ViewPastScreens, false))
        assertEquals(Feedback["file_image_note"], NoticePolicy.imageNote(Verb.ReadFile, true))
        assertEquals(Feedback["screenshot_note"], NoticePolicy.imageNote(Verb.Observe, false))
        assertEquals(Feedback["action_frame_note"], NoticePolicy.imageNote(Verb.Tap, false))
    }

    @Test fun knowledgeLookups() {
        assertEquals(listOf(KnowledgeLookup.App("com.x")), NoticePolicy.knowledgeAfter(Verb.Observe, Place.app("com.x"), true))
        assertEquals(listOf(KnowledgeLookup.Site("example.com")), NoticePolicy.knowledgeAfter(Verb.Observe, Place.site("WWW.Example.com"), true))
        assertEquals(listOf(KnowledgeLookup.Site("shop.example.com")), NoticePolicy.knowledgeAfter(Verb.Navigate, Place.site("shop.example.com"), true))
        assertEquals(emptyList<KnowledgeLookup>(), NoticePolicy.knowledgeAfter(Verb.Navigate, Place.site("shop.example.com"), false))
        // 动作把任务带到了别处,只有看过那里才找资料;打开网页不算看了 App
        assertEquals(emptyList<KnowledgeLookup>(), NoticePolicy.knowledgeAfter(Verb.Tap, Place.app("com.x"), true))
        assertEquals(emptyList<KnowledgeLookup>(), NoticePolicy.knowledgeAfter(Verb.Navigate, Place.app("com.x"), true))
        assertEquals(emptyList<KnowledgeLookup>(), NoticePolicy.knowledgeAfter(Verb.Observe, null, true))
    }

    @Test fun placeFromUrlWhenResultHasNoPlace() {
        val nav = buildJsonObject { put("ok", true); put("url", "https://shop.example.com/a") }
        assertEquals(Place.site("shop.example.com"), placeAfter(Verb.Navigate, nav))
        assertNull(placeAfter(Verb.Tap, nav))
    }

    // ---- 压缩时机 ----

    @Test fun compressionReportedOnlyWhenFoldingChanges() {
        var s = CompactionState()
        fun send(folded: Int) = CompactionPolicy.sent(s, folded).also { s = it.first }.second
        assertFalse(send(0))
        assertTrue(send(2))
        assertFalse(send(2))
        assertTrue(send(3))
        s = s.copy(aggressive = true)
        assertTrue(send(3))
    }

    @Test fun overflowRetriesOncePerEventOnlyIfItShrinks() {
        val (s, again) = CompactionPolicy.overflow(CompactionState(), retried = false) { true }
        assertTrue(again && s.aggressive)
        assertFalse(CompactionPolicy.overflow(CompactionState(), retried = false) { false }.second)
        // 已是狠压缩的任务再次超长:历史长了、压得更小就再重发一次
        assertTrue(CompactionPolicy.overflow(s, retried = false) { true }.second)
        // 同一次超长已经重发过:不再算、不再发
        assertFalse(CompactionPolicy.overflow(s, retried = true) { error("不该再算") }.second)
    }

    // ---- 重试 ----

    private val fixed = RetryPolicy(jitter = { 1.0 })

    @Test fun retriesOnlyTransientErrors() {
        assertEquals(1000L, fixed.delayFor(ModelError.Network(), 0))
        assertEquals(1000L, fixed.delayFor(ModelError.Server(503), 0))
        assertEquals(1000L, fixed.delayFor(ModelError.RateLimited(null), 0))
        for (e in listOf(ModelError.Server(400), ModelError.Server(404), ModelError.Auth(), ModelError.QuotaExceeded(),
            ModelError.ModelNotFound(), ModelError.ContentRefused(), ModelError.BadResponse(), ModelError.ContextTooLong(), IllegalStateException())) {
            assertNull("$e", fixed.delayFor(e, 0))
        }
    }

    @Test fun backoffDoublesUpToCapAndStopsAfterMaxRetries() {
        val p = RetryPolicy(maxRetries = 5, jitter = { 1.0 })
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 8000L), (0..4).map { p.delayFor(ModelError.Network(), it) })
        assertNull(p.delayFor(ModelError.Network(), 5))
    }

    @Test fun jitterKeepsHalfTheWait() {
        assertEquals(500L, RetryPolicy(jitter = { 0.0 }).delayFor(ModelError.Network(), 0))
        assertEquals(750L, RetryPolicy(jitter = { 0.5 }).delayFor(ModelError.Network(), 0))
    }

    @Test fun taskWaitBudgetStopsRetrying() {
        val p = RetryPolicy(jitter = { 1.0 }, taskWaitBudgetMs = 2500)
        assertEquals(1000L, p.delayFor(ModelError.Network(), 0, waitedInTaskMs = 1500))
        assertNull(p.delayFor(ModelError.Network(), 0, waitedInTaskMs = 1600))
        assertNull(p.delayFor(ModelError.RateLimited(5000), 0))
        assertNull(RetryPolicy.NONE.delayFor(ModelError.Server(503), 0))
    }

    @Test fun retryAfterIsHonouredAndCapped() {
        assertEquals(5000L, fixed.delayFor(ModelError.RateLimited(5000), 0))
        assertEquals(30_000L, fixed.delayFor(ModelError.RateLimited(600_000), 0))
        assertNull(fixed.delayFor(ModelError.RateLimited(5000), 3))
    }
}
