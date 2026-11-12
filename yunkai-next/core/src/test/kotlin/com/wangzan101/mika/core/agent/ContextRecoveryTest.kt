package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.testing.TOOLS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextRecoveryTest {
    private val task = Message(Role.User, listOf(Part.Text("把每一屏都看一遍")), Author.User)
    private val state = ContextBuilder.envelope(ContextKind.RuntimeState, "运行时", "当前界面:App com.x\n步数:第 3 步")

    private fun round(n: Int) = listOf(
        Message(Role.Assistant, listOf(Part.ToolCall("c$n", "phone_tap", """{"target":"按钮$n"}""")), Author.Mika),
        Message(Role.Tool, listOf(Part.ToolResult("c$n", """{"ok":true,"pad":"${"x".repeat(400)}"}""", false)), Author.Mika),
    )

    @Test fun restoresDuplicateUserLinesAndPinnedRule() {
        val user = Message(Role.User, listOf(Part.Text("再看一次")), Author.User)
        val rule = Message(Role.User, listOf(Part.Text(ContextBuilder.envelope(ContextKind.Memory, "用户", "只读"))), Author.Mika)
        assertEquals(listOf(user, user, rule), ContextRecovery.ensure(listOf(user, user, rule), listOf(user)))
    }

    // 运行时状态接在插话末尾;折叠把它去掉后插话仍算在,不再被接到最新的消息后面
    @Test fun steerWithRuntimeStateStaysInPlaceAfterCompaction() {
        val steer = Message(Role.User, listOf(Part.Text("<user_interjection>\n顺便看下设置"), Part.Text(state)), Author.User)
        val full = listOf(task) + (1..3).flatMap(::round) + steer + (4..20).flatMap(::round)
        for (structured in listOf(false, true)) {
            val compressed = ContextCompressor(TOOLS, structured = structured).compress(full, contextTokens = 1000).messages
            assertTrue(compressed.any { ContextCompressor.HEADER in it.toString() })
            val sent = ContextRecovery.ensure(full, compressed)
            assertEquals(compressed, sent)
            val at = sent.indexOfFirst { m -> m.author == Author.User && "顺便看下设置" in m.toString() }
            assertEquals(1, sent.count { m -> m.author == Author.User && "顺便看下设置" in m.toString() })
            // 在保留下来的最近几步之前,最后一条仍是最新的工具结果
            assertTrue(at < sent.indexOfFirst { it.role == Role.Assistant })
            assertEquals(full.last(), sent.last())
        }
    }

    @Test fun genuinelyMissingMessageGoesBackToItsPosition() {
        val steer = Message(Role.User, listOf(Part.Text("<user_interjection>\n换个城市"), Part.Text(state)), Author.User)
        val summary = Message(Role.User, listOf(Part.Text(ContextBuilder.envelope(ContextKind.Notice, "之前的步骤", ContextCompressor.HEADER))), Author.Mika)
        val full = listOf(task) + round(1) + steer + round(2) + round(3)
        val compressed = listOf(task, summary) + round(2) + round(3)
        val sent = ContextRecovery.ensure(full, compressed)
        assertEquals(listOf(task, summary, ContextBuilder.withoutRuntimeState(steer)) + round(2) + round(3), sent)
    }

    @Test fun neverInsertsBetweenACallAndItsResult() {
        val steer = Message(Role.User, listOf(Part.Text("<user_interjection>\n停一下")), Author.User)
        val r2 = round(2)
        // 完整历史里插话夹在调用与结果之间(并行写入时可能这样记)
        val full = listOf(task) + r2[0] + steer + r2[1] + round(3)
        val sent = ContextRecovery.ensure(full, listOf(task) + r2 + round(3))
        assertEquals(listOf(task) + r2 + steer + round(3), sent)
    }
}
