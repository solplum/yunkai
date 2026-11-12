// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.ui

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.agent.RunProgress
import com.wangzan101.mika.core.agent.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.wangzan101.mika.settings.Problem
import org.junit.Test

class StatusCopyTest {
    @get:Rule val texts = ChineseTexts()

    @Test
    fun paymentLinesOnePerField() {
        assertEquals("商品:拿铁 大杯\n金额:¥18\n地址:公司前台", StatusCopy.paymentLines("拿铁 大杯", "¥18", "公司前台"))
        assertEquals("金额:¥18", StatusCopy.paymentLines(null, "¥18", " "))
        assertNull(StatusCopy.paymentLines(null, null, null))
    }

    // 照着记住的路线做、一次做几步时,卡片上写明在干什么
    @Test
    fun replayAndBatchCopy() {
        assertEquals("按以往的做法快速执行中(第 2/5 步)", StatusCopy.progress(RunProgress.Replay(2, 5)))
        assertEquals("一次执行 3 步", StatusCopy.progress(RunProgress.Batch(3)))

        val ex = Exchange("查电量", emptyList(), ExchangeStatus.Working, live = true)
        val replaying = ChatViewModel.reduce(ex, TaskState.Running(0, "按以往的做法操作", progress = RunProgress.Replay(2, 5)))
        assertEquals("按以往的做法快速执行中(第 2/5 步)", liveLine(replaying))
        val batching = ChatViewModel.reduce(replaying, TaskState.Running(1, "一起做", "do_steps", RunProgress.Batch(3)))
        assertEquals("一次执行 3 步", liveLine(batching))
        // 步骤列表只列实际做过的事
        assertEquals(emptyList<StepRow>(), batching.steps)
    }

    @Test
    fun headsUpCopyForEachCombination() {
        val both = Problem.AttentionLowered(attention = true, result = true)
        val b = StatusCopy.banner(both)
        assertEquals("「需要你处理」的通知不会弹出", b.title)
        assertEquals(
            "要你接手或同意时，这类通知现在只安静地进通知栏，容易错过。「任务结果」的通知也一样。点「去打开」，打开「在屏幕上弹出」。\n" +
                "系统设置 › 应用 › 云开 › 通知 › 需要你处理 › 在屏幕上弹出（有的系统叫「横幅」「悬浮通知」）",
            b.detail,
        )
        assertEquals("去打开", b.link)
        assertEquals(Fix.OpenAttentionChannel, StatusCopy.directFix(both))
        assertEquals(Fix.OpenAttentionChannel, StatusCopy.explain(both, "14").fix)

        val attentionOnly = StatusCopy.banner(Problem.AttentionLowered(true, false))
        assertFalse(attentionOnly.detail.contains("也一样"))

        val resultOnly = Problem.AttentionLowered(attention = false, result = true)
        assertEquals("「任务结果」的通知不会弹出", StatusCopy.banner(resultOnly).title)
        assertTrue(StatusCopy.banner(resultOnly).detail.contains("通知 › 任务结果 › 在屏幕上弹出"))
        assertEquals(Fix.OpenResultChannel, StatusCopy.directFix(resultOnly))
        val e = StatusCopy.explain(resultOnly, "14")
        assertEquals("去打开", e.action)
        assertEquals(Fix.OpenResultChannel, e.fix)
    }

    // 频道设置页打不开时依次退回,第一个能开的就停
    @Test
    fun firstThatStartsFallsBack() {
        val tried = mutableListOf<String>()
        assertTrue(firstThatStarts(listOf("channel", "app")) { tried += it; it == "app" })
        assertEquals(listOf("channel", "app"), tried)
        tried.clear()
        assertTrue(firstThatStarts(listOf("channel", "app")) { tried += it; true })
        assertEquals(listOf("channel"), tried)
        assertFalse(firstThatStarts(listOf("channel", "app")) { false })
    }
}
