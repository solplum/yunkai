package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.testing.fakeModel
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.TOOLS
import com.wangzan101.mika.core.assembly.toolDispatcher

class StructuredCompactionTest {
    private var seq = 0
    private val head = listOf(
        Message(Role.User, listOf(Part.Text("帮我查上海的天气")), Author.User),
        Message(Role.User, listOf(Part.Text(ContextBuilder.envelope(ContextKind.Memory, "记忆", "1. 喜欢摄氏度"))), Author.Mika),
    )

    private fun round(name: String, args: String = "{}", result: String = """{"ok":true,"changed":true}""", said: String? = null): List<Message> {
        val id = "c${++seq}"
        return listOf(
            Message(Role.Assistant, listOfNotNull(said?.let { Part.Text(it) }, Part.ToolCall(id, name, args)), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult(id, result, isError = false)), Author.Mika),
        )
    }

    private fun taps(n: Int) = (1..n).flatMap { round("phone_tap", """{"target":"按钮$it"}""") }
    private fun summaries(ms: List<Message>) = ms.mapNotNull { m -> m.plainText().takeIf { it.startsWith(ContextCompressor.HEADER) } }

    @Test fun fourSectionsFromResults() {
        val h = head +
            round("phone_launch_app", """{"package":"com.weather"}""", """{"ok":true,"currentApp":"com.weather"}""", said = "先打开天气 App") +
            round("web_navigate", """{"url":"https://wttr.in/"}""", """{"ok":true,"url":"https://wttr.in/Shanghai"}""") +
            round("phone_tap", """{"target":"刷新"}""", """{"ok":false,"error":"ref_expired"}""", said = "刷新一下再读") +
            taps(12)
        val s = summaries(ContextCompressor(TOOLS,structured = true).compress(h, contextTokens = 100).messages).single()
        assertEquals(
            listOf(
                ContextCompressor.HEADER,
                ContextCompressor.SUMMARY_REMINDER,
                "目标:帮我查上海的天气",
                "已确认的事实:",
                "- 到过的 App:com.weather",
                "- 打开过的页面:https://wttr.in/Shanghai",
                "进展:",
                "- 第 1 步 phone_launch_app(com.weather)→ 生效",
                "- 第 2 步 web_navigate→ 生效",
                "- 第 3 步 phone_tap(刷新)→ ref_expired",
            ) + (4..14).map { "- 第 $it 步 phone_tap(按钮${it - 3})→ 生效" } +
                "下一步:从下面保留的最近几步接着做(折叠前最后的打算:刷新一下再读)",
            s.lines(),
        )
    }

    @Test fun oneSummaryAcrossPinnedRoundsAndPairsIntact() {
        val takeover = round("hand_to_user", """{"reason":"login"}""", """{"outcome":"complete","ok":true}""")
        val h = head + taps(3) + takeover + taps(2) + taps(12)
        val out = ContextCompressor(TOOLS,structured = true).compress(h, contextTokens = 100).messages
        // 一份摘要在第一段的位置;接管往返原样保留在后面
        assertEquals(1, summaries(out).size)
        assertTrue(out[2].plainText().startsWith(ContextCompressor.HEADER))
        assertEquals(takeover, out.subList(3, 5))
        val calls = out.flatMap { m -> m.parts.filterIsInstance<Part.ToolCall>().map { it.id } }
        val results = out.flatMap { m -> m.parts.filterIsInstance<Part.ToolResult>().map { it.callId } }
        assertEquals(calls, results)
    }

    @Test fun progressRollsOlderStepsIntoCounts() {
        val h = head + (1..20).flatMap { i ->
            if (i % 5 == 0) round("phone_tap", """{"target":"坏$i"}""", """{"ok":false,"error":"ref_expired"}""") else round("phone_tap", """{"target":"好$i"}""")
        } + taps(12)
        val s = summaries(ContextCompressor(TOOLS,structured = true).compress(h, contextTokens = 100).messages).single().lines()
        // 上下文很小,只留最近一次往返:折了 31 步,最近 15 步逐条,更早 16 步并成计数
        assertTrue(s.toString(), "- 更早的 16 步:生效 13、ref_expired 3" in s)
        assertEquals(15, s.count { it.startsWith("- 第 ") })
        assertTrue("- 第 17 步 phone_tap(好17)→ 生效" in s && s.none { it.startsWith("- 第 16 步") })
    }

    @Test fun plainSummaryWhenOff() {
        val h = head + taps(15)
        val s = summaries(ContextCompressor(TOOLS).compress(h, contextTokens = 100).messages).single()
        assertTrue("目标:" !in s && s.lines()[1] == ContextCompressor.SUMMARY_REMINDER && s.lines()[2].startsWith("第 1 步"))
    }

    @Test fun calibratedEstimateDecidesWhenToCompact() {
        val h = head + taps(20)
        val context = (TokenEstimate.of(h) / ContextCompressor.COMPACT_AT).toInt() + 10
        val c = ContextCompressor(TOOLS)
        assertEquals(h, c.compress(h, context).messages)
        // 服务商报的比粗估多一倍:同样的历史按校准后的估算已越过触发点,压缩一次
        c.calibrate(1000, 2000)
        assertTrue(c.compress(h, context).foldedSteps > 0)
    }

    @Test fun compactionIsStickyForTheTask() {
        val all = head + taps(20)
        val context = (TokenEstimate.of(all) / ContextCompressor.COMPACT_AT).toInt() + 10
        val c = ContextCompressor(TOOLS)
        assertTrue(c.compress(all, context, fixedOverhead = 100).foldedSteps > 0)
        // 回到触发点以下仍接着用压缩的结果;新的实例(新任务)从原样起
        assertTrue(c.compress(all, context).foldedSteps > 0)
        assertEquals(0, ContextCompressor(TOOLS).compress(all, context).foldedSteps)
        c.forget()
        assertEquals(0, c.compress(all, context).foldedSteps)
    }

    @Test fun userTextInFoldedRoundsIsKept() {
        val said = Message(Role.User, listOf(Part.Text("顺便把亮度也调低"), Part.Image("PPPP")), Author.User)
        val h = head + round("phone_tap", """{"target":"设置"}""") + said + taps(14)
        for (structured in listOf(false, true)) {
            val out = ContextCompressor(TOOLS,structured = structured).compress(h, contextTokens = 100).messages
            assertTrue(summaries(out).isNotEmpty() && said in out)
        }
    }

    @Test fun summaryStaysBounded() {
        val long = "x".repeat(5000)
        val h = head + (1..200).flatMap { i ->
            round("tool_$i$long", """{"target":"$long"}""", """{"ok":false,"error":"$long$i","url":"https://e.com/$i$long","currentApp":"app$i"}""", said = long)
        } + taps(12)
        val s = summaries(ContextCompressor(TOOLS,structured = true).compress(h, contextTokens = 100).messages).single()
        assertTrue("${s.length}", s.length < 4000)
        assertTrue(long !in s)
        val plain = summaries(ContextCompressor(TOOLS).compress(h, contextTokens = 100).messages).single()
        assertTrue(plain.lines().drop(1).all { it.length < 120 })
    }

    @Test fun loopUsesStructuredSummaryWhenFlagOn() = runTest {
        val m = fakeModel("main", contextTokens = 100) {
            for (i in 1..14) call("wait", "seconds" to 1 + i / 100.0)
            answer("等完了")
        }
        AgentLoop(m, toolDispatcher(FakeScreen()), "BASE", flags = SpeedFlags(structuredCompaction = true)).run("等一会儿")
        val s = summaries(m.requests.last().messages).single()
        assertTrue(s, "目标:等一会儿" in s && "进展:" in s && "下一步:" in s)
    }

    @Test fun calibratedRatioIsSmoothedAndBounded() {
        assertEquals(2.0, TokenEstimate.calibrated(null, 1000, 2000), 1e-9)
        assertEquals(1.0 * 0.7 + 2.0 * 0.3, TokenEstimate.calibrated(1.0, 1000, 2000), 1e-9)
        assertEquals(TokenEstimate.MAX_SCALE, TokenEstimate.calibrated(null, 10, 10_000), 1e-9)
        assertEquals(TokenEstimate.MIN_SCALE, TokenEstimate.calibrated(null, 10_000, 10), 1e-9)
        assertEquals(1.3, TokenEstimate.calibrated(1.3, 0, 500), 1e-9)
    }
}
