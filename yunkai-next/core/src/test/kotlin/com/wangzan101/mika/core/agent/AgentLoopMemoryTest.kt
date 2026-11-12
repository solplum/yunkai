package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.memory.MemoryKind
import com.wangzan101.mika.core.memory.MemorySource
import com.wangzan101.mika.core.memory.UserUtterances
import com.wangzan101.mika.core.memory.finishReply
import com.wangzan101.mika.core.memory.memStore
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.TOOLS
import com.wangzan101.mika.core.assembly.toolDispatcher

class AgentLoopMemoryTest {
    private val manual = MemorySource("manual", atMs = 1)
    private fun Message.text() = plainText()

    private fun store() = memStore().apply {
        add(MemoryKind.preference, "用户点外卖不要香菜", listOf("外卖"), manual).getOrThrow()
        add(MemoryKind.fact, "用户的公司在张江", listOf("公司"), manual).getOrThrow()
    }

    @Test fun blockInsertedBeforeTaskAfterContext() {
        val s = store()
        val model = FakeModel(finishReply)
        runBlocking {
            AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = s)
                .run("帮我点个外卖", listOf(Turn("查一下天气", "晴", "succeeded")))
        }
        val msgs = model.requests.first().withoutRuntime().map { it.text() }
        assertTrue(msgs[0].startsWith(Conversation.HEADER))
        assertTrue(msgs[1].startsWith("关于用户的记忆"))
        assertTrue(msgs[1].contains("用户点外卖不要香菜"))
        assertFalse(msgs[1].contains("张江"))
        assertEquals("帮我点个外卖", msgs[2])
    }

    @Test fun noRelevantMemoryNoBlock() {
        val s = store()
        val model = FakeModel(finishReply)
        val loop = AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = s)
        runBlocking { loop.run("看看明天天气") }
        assertEquals(listOf("看看明天天气"), model.requests.first().withoutRuntime().map { it.text() })
        assertTrue(loop.usedMemoryIds.isEmpty())
        assertTrue(s.items.value.all { it.useCount == 0 })
    }

    @Test fun usedIdsRecordedAndMarked() {
        val s = store()
        val events = mutableListOf<RunEvent>()
        val loop = AgentLoop(FakeModel(finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", sink = object : RunSink { override fun event(e: RunEvent) { events += e } }, memory = s)
        runBlocking { loop.run("帮我点个外卖") }
        val id = s.items.value.first().id
        assertEquals(listOf(id), loop.usedMemoryIds)
        assertEquals(1, s.items.value.first().useCount)
        assertEquals(listOf(listOf(id)), events.filterIsInstance<RunEvent.MemoryUsed>().map { it.ids })
    }

    @Test fun disabledMeansNoBlockNoTools() {
        val s = store()
        val model = FakeModel(finishReply)
        val loop = AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = null)
        runBlocking { loop.run("帮我点个外卖") }
        assertEquals(listOf("帮我点个外卖"), model.requests.first().withoutRuntime().map { it.text() })
        assertFalse(model.offeredTools.first().any { it.startsWith("memory_") })
        assertTrue(loop.usedMemoryIds.isEmpty())
    }
}

class MemoryBlockCompressionTest {
    @Test fun memoryBlockSurvivesAggressiveCompression() {
        val block = Message(Role.User, listOf(Part.Text("关于用户的记忆(用户告诉过你的;可能过时,和用户这次说的冲突时以这次为准):\n1. 用户点外卖不要香菜")), Author.Mika)
        val history = mutableListOf(block, Message(Role.User, listOf(Part.Text("帮我点个外卖")), Author.Mika))
        repeat(30) { i ->
            history += Message(Role.Assistant, listOf(Part.ToolCall("c$i", "phone_observe", "{}")), Author.Mika)
            history += Message(Role.Tool, listOf(Part.ToolResult("c$i", "{\"ok\":true,\"x\":\"" + "很".repeat(500) + "\"}", false)), Author.Mika)
        }
        val out = ContextCompressor(TOOLS).compress(history, contextTokens = 2_000, aggressive = true)
        assertTrue(out.foldedSteps > 0)
        assertEquals(block, out.messages.first())
    }
}
