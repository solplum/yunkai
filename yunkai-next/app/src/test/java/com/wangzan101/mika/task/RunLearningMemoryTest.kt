package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.memory.MemoryExtractor
import com.wangzan101.mika.core.memory.MemoryKind
import com.wangzan101.mika.core.memory.MemorySource
import com.wangzan101.mika.settings.MemorySwitches
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.knownTools

class RunLearningMemoryTest {
    private val catalog = GuideCatalog.loadBundled()
    private val extractJson = """{"ops":[{"op":"add","kind":"preference","content":"用户点外卖不要香菜","quote":"以后都不要香菜"}]}"""

    private fun aftermath(utterances: List<String>, saved: List<com.wangzan101.mika.core.memory.Change> = emptyList()) = RunAftermath(
        "conv-1", "run-1", "点外卖", TaskState.Succeeded("ok"), utterances, saved, listOf("x"), emptySet(), knownTools(), events = { emptyList() },
    )

    @Test fun extractsFromUserWordsWithConversationSource() {
        val store = memoryStore()
        val model = TextModel(extractJson)
        val notes = runBlocking {
            RunLearning(MemorySwitches(), store, MapSkills(), catalog, MemoryExtractor(model), null, { "d" }).learn(aftermath(listOf("点外卖,以后都不要香菜")))
        }
        assertEquals(listOf("记住了:用户点外卖不要香菜"), notes.memoryNotes.map { n -> n.text(store.items.value.find { it.id == n.memoryId }) })
        assertEquals(listOf("x"), notes.usedMemory)
        assertEquals("conv-1", store.items.value.single().source.conversationId)
        assertEquals("Ui", store.items.value.single().source.channel)
        assertEquals("run-1", notes.runId)
    }

    @Test fun autoRememberOffSkipsExtractionButKeepsSavedNotes() {
        val store = memoryStore()
        val saved = store.add(MemoryKind.fact, "用户住在张江", emptyList(), MemorySource("user_said", "我住在张江", atMs = 1)).getOrThrow()
        val model = TextModel(extractJson)
        val notes = runBlocking {
            RunLearning(MemorySwitches(autoRemember = false), store, MapSkills(), catalog, MemoryExtractor(model), null, { "d" })
                .learn(aftermath(listOf("以后都不要香菜"), listOf(saved)))
        }
        assertTrue(model.requests.isEmpty())
        assertEquals(listOf("记住了:用户住在张江"), notes.memoryNotes.map { n -> n.text(store.items.value.find { it.id == n.memoryId }) })
    }

    @Test fun noUtterancesNoCall() {
        val model = TextModel(extractJson)
        runBlocking { RunLearning(MemorySwitches(), memoryStore(), MapSkills(), catalog, MemoryExtractor(model), null, { "d" }).learn(aftermath(emptyList())) }
        assertTrue(model.requests.isEmpty())
    }

    @Test fun noRunIdNoExtraction() {
        val model = TextModel(extractJson)
        runBlocking {
            RunLearning(MemorySwitches(), memoryStore(), MapSkills(), catalog, MemoryExtractor(model), null, { "d" })
                .learn(aftermath(listOf("以后都不要香菜")).copy(runId = null))
        }
        assertTrue(model.requests.isEmpty())
    }
}
