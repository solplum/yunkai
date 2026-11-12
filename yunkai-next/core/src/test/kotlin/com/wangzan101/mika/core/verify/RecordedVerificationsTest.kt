package com.wangzan101.mika.core.verify

import com.wangzan101.mika.core.record.RunEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordedVerificationsTest {
    private var t = 0L
    private fun reply(step: Int, tool: String?, answer: String = "a$step") =
        RunEvent.ModelReply(++t, step, "", tool, if (tool == "finish") """{"answer":"$answer"}""" else "{}", null, null, null)
    private fun result(step: Int, tool: String) = RunEvent.ToolResult(++t, step, tool, """{"ok":true}""", null, null)
    private fun verifier(step: Int? = null) = RunEvent.Verifier(++t, "fail", "{}", step)

    // 平常的一步:核查记在 finish 回复之前;最后一轮:记在之后。两种顺序混在一条记录里也各配各的
    @Test fun legacyRecordsPairEitherOrder() {
        val ev = listOf(
            reply(1, "phone_observe"), result(1, "phone_observe"),
            verifier(), reply(2, "finish"), result(2, "finish"),
            reply(3, "phone_observe"), result(3, "phone_observe"),
            reply(4, "finish"), result(4, "finish"), verifier(),
        )
        assertEquals(listOf(2 to 3, 9 to 7), RecordedVerifications.pairs(ev))
    }

    // 两次 finish 紧挨着:先核查后记回复的那次不会被配到上一次 finish
    @Test fun backToBackFinishesDoNotSwap() {
        val ev = listOf(verifier(), reply(1, "finish"), result(1, "finish"), verifier(), reply(2, "finish"), result(2, "finish"))
        assertEquals(listOf(0 to 1, 3 to 4), RecordedVerifications.pairs(ev))
    }

    @Test fun stepPairsRegardlessOfPosition() {
        // 带 step 的:最后一轮的核查(在回复之后)后面还跟着别的 finish 也配对正确
        val ev = listOf(reply(1, "finish"), result(1, "finish"), verifier(1), reply(2, "finish"), verifier(2))
        assertEquals(listOf(2 to 0, 4 to 3), RecordedVerifications.pairs(ev))
    }

    @Test fun evidenceExcludesTheCheckedFinishItself() {
        val ev = listOf(reply(1, "phone_observe"), result(1, "phone_observe"), reply(2, "finish"), result(2, "finish"), verifier(2))
        val (v, f) = RecordedVerifications.pairs(ev).single()
        assertEquals(listOf(ev[0], ev[1]), RecordedVerifications.before(ev, v, f))
    }
}
