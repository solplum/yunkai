package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.TOOLS

class ContextCompressorTest {
    private var seq = 0
    private val pad = "x".repeat(400)
    private val head = listOf(
        Message(Role.User, listOf(Part.Text("帮我查天气")), Author.Mika),
        Message(Role.User, listOf(Part.Text(ContextBuilder.envelope(ContextKind.Memory, "记忆", "1. 喜欢摄氏度"))), Author.Mika),
    )

    private fun round(name: String, args: String = "{}", result: String = """{"ok":true,"changed":true,"pad":"$pad"}"""): List<Message> {
        val id = "c${++seq}"
        return listOf(
            Message(Role.Assistant, listOf(Part.ToolCall(id, name, args)), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult(id, result, isError = false)), Author.Mika),
        )
    }

    private fun taps(n: Int) = (1..n).flatMap { round("phone_tap", """{"target":"按钮$it"}""") }
    private fun summary(ms: List<Message>) = ms.firstNotNullOfOrNull { m ->
        m.plainText().takeIf { it.startsWith(ContextCompressor.HEADER) }
    }
    private fun toolRounds(ms: List<Message>) = ms.count { m -> m.role == Role.Assistant && m.parts.any { it is Part.ToolCall } }

    @Test fun underBudgetUntouched() {
        val h = head + taps(20)
        val c = ContextCompressor(TOOLS).compress(h, contextTokens = 128_000)
        // 没越过触发点:原样发,一条不裁
        assertEquals(h, c.messages)
        assertEquals(0, c.foldedSteps)
    }

    /** 刚越过触发点的上下文:压一次就能回到触发点的一半以下,用最轻的一级(留最近 12 次往返)。 */
    private fun justOver(h: List<Message>) = (TokenEstimate.of(h) / ContextCompressor.COMPACT_AT).toInt() - 10

    @Test fun foldsOldestKeepsRecent12() {
        val h = head + taps(40)
        val c = ContextCompressor(TOOLS).compress(h, contextTokens = justOver(h))
        assertEquals(28, c.foldedSteps)
        assertEquals(12, toolRounds(c.messages))
        // 任务原话与成功标准原样在最前,其后是摘要,再后是最近 12 次往返原样
        assertEquals(head, c.messages.take(2))
        assertTrue(summary(c.messages) == c.messages[2].plainText())
        assertEquals(ContextKind.Notice, ContextBuilder.kindOf(c.messages[2]))
        assertEquals(h.takeLast(24), c.messages.drop(3))
        val s = summary(c.messages)!!
        assertTrue(s, "第 1 步 phone_tap(按钮1)→ 生效" in s && "第 28 步 phone_tap(按钮28)→ 生效" in s && "第 29 步" !in s)
    }

    @Test fun appendOnlyBetweenCompactions() {
        val all = head + taps(120)
        fun h(n: Int) = all.take(head.size + 2 * n)
        val c = ContextCompressor(TOOLS)
        val ctx = 8000
        val outs = (1..120).map { c.compress(h(it), contextTokens = ctx) }
        val rewrites = outs.zipWithNext().withIndex().filter { (_, p) -> p.first.messages != p.second.messages.take(p.first.messages.size) }.map { it.index + 2 }
        val folds = outs.zipWithNext().withIndex().filter { (_, p) -> p.first.foldedSteps != p.second.foldedSteps }.map { it.index + 2 }
        // 每次改写都是一次压缩(折叠数变了),两次压缩之间只追加;压缩次数远少于步数
        assertEquals(folds, rewrites)
        assertTrue("$rewrites", rewrites.isNotEmpty() && rewrites.size <= 10)
        rewrites.zipWithNext().forEach { (a, b) -> assertTrue("$rewrites", b - a > 1) }
        outs.forEach { assertTrue(TokenEstimate.of(it.messages) <= ctx * ContextCompressor.COMPACT_AT + 200) }
    }

    @Test fun oversizedNewestResultIsShrunkToFitTheWindow() {
        val huge = round("run_command", """{"command":"cat big.log"}""",
            """{"ok":true,"exitCode":0,"output":"${"日志".repeat(40_000)}"}""")
        val h = head + taps(3) + huge
        val ctx = 16_000
        val c = ContextCompressor(TOOLS).compress(h, contextTokens = ctx, fixedOverhead = 1000)
        assertTrue("${TokenEstimate.of(c.messages)}", TokenEstimate.of(c.messages) <= ctx - 1000)
        val last = c.messages.last().parts.filterIsInstance<Part.ToolResult>().single()
        assertTrue(last.content, HistoryPruner.OMITTED_READ in last.content && "\"exitCode\":0" in last.content)
        val calls = c.messages.flatMap { m -> m.parts.filterIsInstance<Part.ToolCall>().map { it.id } }
        val results = c.messages.flatMap { m -> m.parts.filterIsInstance<Part.ToolResult>().map { it.callId } }
        assertEquals(calls, results)
    }

    @Test fun overheadHeavySmallWindowNeverExceedsTheWindow() {
        val all = head + taps(40)
        val ctx = 4000
        val overhead = 3000
        val c = ContextCompressor(TOOLS)
        // 固定开销已超过触发点:回差推迟的再压缩也不会越过上下文的九成,发出的始终放得下
        for (n in 1..40) {
            val out = c.compress(all.take(head.size + 2 * n), contextTokens = ctx, fixedOverhead = overhead).messages
            assertTrue("$n: ${TokenEstimate.of(out)}", TokenEstimate.of(out) <= ctx - overhead)
        }
    }

    @Test fun foldedObservationsKeepScreenshotId() {
        val obs = round("phone_observe", "{}", """{"ok":true,"snapshotId":"s1","screenshotId":7,"elements":[],"pad":"$pad"}""")
        val dump = round("phone_list_elements", "{}", """{"ok":true,"snapshotId":"s2","screenshotId":8,"pad":"$pad"}""")
        val h = head + obs + dump + taps(20)
        val s = summary(ContextCompressor(TOOLS).compress(h, contextTokens = 1000).messages)!!
        assertTrue(s, "第 1 步 phone_observe #7→ 生效" in s && "第 2 步 phone_list_elements #8→ 生效" in s)
    }

    @Test fun pinnedMessagesKept() {
        val knowledge = Message(Role.User, listOf(Part.Text(ContextBuilder.envelope(ContextKind.AppKnowledge, "天气 App", "…"))), Author.Mika)
        val inputRound = round("ask_user", """{"prompt":"哪个城市?"}""", """{"ok":true}""") +
            Message(Role.User, listOf(Part.Text("北京")), Author.Mika)
        // 追问的回答也可能直接作为工具结果
        val inputAsResult = round("ask_user", """{"prompt":"哪天?"}""", """{"ok":true,"answer":"明天"}""")
        val confirmRound = round("request_confirmation", """{"action":"send"}""", """{"ok":true,"approved":true}""")
        val takeoverRound = round("hand_to_user", """{"reason":"login","message":"请登录"}""", """{"outcome":"complete","ok":true}""")
        val h = head + taps(2) + knowledge + inputRound + taps(2) + takeoverRound + inputAsResult + confirmRound + taps(20)
        val c = ContextCompressor(TOOLS).compress(h, contextTokens = 1000)
        for (m in head + knowledge + inputRound + takeoverRound + inputAsResult + confirmRound) assertTrue("$m", m in c.messages)
        assertTrue(c.foldedSteps > 0)
        val summaries = c.messages.mapNotNull { m -> m.plainText().takeIf { it.startsWith(ContextCompressor.HEADER) } }
        assertTrue(summaries.flatMap { it.lines() }.none { "request_" in it || "hand_to_user" in it })
        // 保留下来的顺序不变
        val kept = c.messages.filter { it in h }
        assertEquals(h.filter { it in kept }, kept)
    }

    @Test fun domainKnowledgeAfterWebRoundStaysPinned() {
        val nav = round("web_navigate", """{"url":"https://shop.example.com/"}""",
            """{"ok":true,"surface":"web","tabId":"t1","url":"https://shop.example.com/","pad":"$pad"}""")
        val siteKnowledge = Message(Role.User, listOf(Part.Text(ContextBuilder.envelope(ContextKind.SiteKnowledge, "好店", "网页做法…"))), Author.Mika)
        val h = head + taps(2) + nav + siteKnowledge + taps(20)
        val c = ContextCompressor(TOOLS).compress(h, contextTokens = 1000)
        assertTrue(c.foldedSteps > 0)
        assertTrue(siteKnowledge in c.messages)
        assertTrue("所在的往返被折叠了", nav.first() !in c.messages)
    }

    @Test fun pairingIntactAfterFold() {
        val skipped = Part.ToolCall("p2", "phone_tap", "{}")
        val parallel = listOf(
            Message(Role.Assistant, listOf(Part.ToolCall("p1", "phone_observe", "{}"), skipped), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("p1", """{"ok":true,"elements":[]}""", false),
                Part.ToolResult("p2", """{"ok":false,"error":"parallel_tool_call_skipped"}""", true)), Author.Mika),
            Message(Role.User, listOf(Part.Text("Current phone screenshot"), Part.Image("aaa")), Author.Mika),
        )
        val h = head + taps(3) + parallel + taps(3) + parallel.map { m ->
            Message(m.role, m.parts.map { p -> when (p) {
                is Part.ToolCall -> p.copy(id = p.id + "b"); is Part.ToolResult -> p.copy(callId = p.callId + "b"); else -> p } }, Author.Mika)
        } + taps(12)
        for (aggressive in listOf(false, true)) {
            val out = ContextCompressor(TOOLS).compress(h, contextTokens = 1000, aggressive = aggressive).messages
            val calls = out.flatMap { m -> m.parts.filterIsInstance<Part.ToolCall>().map { it.id } }
            val results = out.flatMap { m -> m.parts.filterIsInstance<Part.ToolResult>().map { it.callId } }
            assertEquals(calls.sorted(), results.sorted())
            out.forEachIndexed { i, m ->
                m.parts.filterIsInstance<Part.ToolResult>().forEach { r ->
                    val prev = out.subList(0, i).last { it.role == Role.Assistant }
                    assertTrue(prev.parts.any { it is Part.ToolCall && it.id == r.callId })
                }
            }
        }
    }

    @Test fun aggressiveKeeps6() {
        val h = head + taps(20)
        // 模型已说超长:不论估算多少都压
        val c = ContextCompressor(TOOLS).compress(h, contextTokens = 1_000_000, aggressive = true)
        // 折 14 次,留最近 6 次
        assertEquals(14, c.foldedSteps)
        assertEquals(6, toolRounds(c.messages))
        assertEquals(h.takeLast(12), c.messages.takeLast(12))
    }

    @Test fun summaryLineFormat() {
        val h = head +
            round("phone_tap", """{"ref":"@e3","snapshotId":"s1","target":"设置"}""", """{"ok":true,"changed":true}""") +
            round("phone_scroll", """{"direction":"down"}""", """{"ok":true,"changed":false}""") +
            round("phone_launch_app", """{"package":"com.x"}""", """{"ok":false,"error":"app_not_found","message":"…"}""") +
            round("phone_back") +
            round("phone_type_text", """{"text":"${"天气".repeat(30)}"}""", """{"ok":true}""") +
            listOf(Message(Role.Assistant, listOf(Part.Text("我想想")), Author.Mika), Message(Role.User, listOf(Part.Text("nudge")), Author.Mika)) +
            round("phone_observe", "{}", """{"ok":true,"elements":[]}""") +
            taps(12)
        val s = ContextCompressor(TOOLS).compress(h, contextTokens = 100).let { summary(it.messages)!! }
        assertEquals(
            listOf(
                ContextCompressor.HEADER,
                ContextCompressor.SUMMARY_REMINDER,
                "第 1 步 phone_tap(设置)→ 生效",
                "第 2 步 phone_scroll(down)→ 没变化",
                "第 3 步 phone_launch_app(com.x)→ app_not_found",
                "第 4 步 phone_back→ 生效",
                "第 5 步 phone_type_text(${"天气".repeat(10)}…)→ 生效",
                "第 7 步 phone_observe→ 生效",
            ),
            s.lines().take(8),
        )
    }

    @Test fun oneSummaryPerContiguousRun() {
        val takeover = round("hand_to_user", """{"reason":"login"}""", """{"outcome":"complete","ok":true}""")
        val h = head + taps(3) + takeover + taps(2) + taps(12)
        // 模型报过超长:留最近 6 次往返
        val out = ContextCompressor(TOOLS).compress(h, contextTokens = 1_000_000, aggressive = true).messages
        val texts = out.map { it.plainText() }
        // 开头 2 条,第一段摘要,保留的接管往返,第二段摘要,最近 6 次往返
        assertTrue(texts[2]!!.startsWith(ContextCompressor.HEADER) && "第 3 步" in texts[2]!! && "第 5 步" !in texts[2]!!)
        assertEquals(takeover, out.subList(3, 5))
        assertTrue(texts[5]!!.startsWith(ContextCompressor.HEADER))
        assertEquals(listOf(ContextCompressor.HEADER, ContextCompressor.SUMMARY_REMINDER) + listOf("第 5 步 phone_tap(按钮1)→ 生效", "第 6 步 phone_tap(按钮2)→ 生效") +
            (7..12).map { "第 $it 步 phone_tap(按钮${it - 6})→ 生效" },
            texts[5]!!.lines())
        assertEquals(2 + 1 + 2 + 1 + 12, out.size)
    }

    @Test fun resultOutcomes() {
        fun one(result: Part.ToolResult?): String {
            val id = "r${++seq}"
            val r = listOf(Message(Role.Assistant, listOf(Part.ToolCall(id, "phone_tap", "{}")), Author.Mika)) +
                listOfNotNull(result?.let { Message(Role.Tool, listOf(it.copy(callId = id)), Author.Mika) })
            val h = head + r + taps(12)
            return summary(ContextCompressor(TOOLS).compress(h, contextTokens = 100).messages)!!.lines()[2].substringAfter("→ ")
        }
        assertEquals("无结果", one(null))
        assertEquals("失败", one(Part.ToolResult("", """{"message":"boom"}""", isError = true)))
        assertEquals("ref_expired", one(Part.ToolResult("", """{"error":"ref_expired"}""", isError = true)))
        assertEquals("timeout", one(Part.ToolResult("", "timeout", isError = true)))
        assertEquals("失败", one(Part.ToolResult("", "出了点问题", isError = true)))
        assertEquals("生效", one(Part.ToolResult("", "done", isError = false)))
    }

    @Test fun fixedOverheadCountsAgainstBudget() {
        val h = head + taps(20)
        val size = TokenEstimate.of(h)
        val context = (size / 0.6).toInt() + 10
        assertEquals(0, ContextCompressor(TOOLS).compress(h, context).foldedSteps)
        assertTrue(ContextCompressor(TOOLS).compress(h, context, fixedOverhead = 100).foldedSteps > 0)
    }

    @Test fun keepsLatestFullReadAndDiffChain() {
        val full = round("phone_observe", "{}", """{"ok":true,"snapshotId":"s1","elements":[{"ref":"@e1"}],"pad":"$pad"}""")
        val diffs = (2..3).flatMap { round("phone_back", "{}", """{"ok":true,"screen":{"snapshotId":"s$it","full":false,"added":[]},"pad":"$pad"}""") }
        val h = head + taps(3) + full + diffs + taps(20)
        val out = ContextCompressor(TOOLS,diffScreens = true).compress(h, contextTokens = 1000).messages
        val kept = out.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().map { it.content }
        assertTrue(kept.any { "\"s1\"" in it })
        // 之前的普通往返照常折叠
        assertTrue(summary(out)!!.contains("tap(按钮1)"))
        // 狠压缩时不保留
        val hard = ContextCompressor(TOOLS,diffScreens = true).compress(h, contextTokens = 1000, aggressive = true).messages
        assertTrue(hard.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().none { "\"s1\"" in it.content })
    }
}
