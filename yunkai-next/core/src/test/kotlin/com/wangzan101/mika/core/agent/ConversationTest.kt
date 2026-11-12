package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTest {
    private fun text(turns: List<Turn>, maxTurns: Int = 5, maxChars: Int = 4000) =
        (Conversation.context(turns, maxTurns, maxChars)!!.parts.single() as Part.Text).text

    @Test fun noTurnsNoMessage() {
        assertNull(Conversation.context(emptyList()))
    }

    @Test fun recentTurnsLastAndTruncated() {
        val turns = (1..8).map { Turn("问题$it", "答案$it", "succeeded") }
        val m = Conversation.context(turns, maxTurns = 3)!!
        assertEquals(Role.User, m.role)
        val t = (m.parts.single() as Part.Text).text
        assertFalse("问题5" in t)
        assertTrue(t.indexOf("问题6") < t.indexOf("问题7") && t.indexOf("问题7") < t.indexOf("问题8"))
        // 超长时丢掉较早的,保留最近的
        val long = (1..5).map { Turn("问题$it", "长".repeat(300), "succeeded") }
        val capped = text(long, maxChars = 800)
        assertTrue(capped.length <= 800)
        assertTrue("问题5" in capped && "问题1" !in capped)
        // 单轮就超长:截断而不是超出
        assertTrue(text(listOf(Turn("问", "长".repeat(5000), "succeeded")), maxChars = 500).length <= 500)
    }

    @Test fun failedTurnShowsReason() {
        // 停止的那一轮没有交代时只说取消了(停止后补上交代的见 StopSummaryTest)
        val t = text(listOf(Turn("订票", "验证码一直过不去", "failed"), Turn("查天气", Conversation.CANCELLED_OUTCOME, "cancelled")))
        assertTrue("没有完成,原因:验证码一直过不去" in t)
        assertTrue("你取消了这次任务" in t && "停止前" !in t)
    }

    @Test fun unverifiedTurnIsLabelled() {
        val t = text(listOf(Turn("查余额", "余额 10 元", "unverified", note = "防截屏")))
        assertTrue(t, "结果(没能核实):余额 10 元" in t)
    }
}
