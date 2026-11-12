package com.wangzan101.mika.core.memory

import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryExtractorTest {
    private val source = { q: String -> MemorySource("user_said", q, "conv", 1L) }
    private fun reply(json: String) = ModelReply(listOf(Part.Text(json)), StopReason.EndTurn, null)

    private fun run(json: Any, utterances: List<String>, store: MemoryStore = memStore(), model: FakeModel = FakeModel(if (json is String) reply(json) else json)): Pair<List<Change>, MemoryStore> {
        val related = MemoryExtractor.related(utterances, store.items.value)
        return runBlocking { MemoryExtractor(model).extract(utterances, related, store, source) } to store
    }

    private fun storeWith(vararg contents: Pair<MemoryKind, String>) = memStore().apply {
        contents.forEach { (k, c) -> add(k, c, listOf("外卖"), MemorySource("manual", atMs = 0)).getOrThrow() }
    }

    @Test fun addsFactFromUserWords() {
        val (changes, store) = run("""{"ops":[{"op":"add","kind":"preference","content":"用户点外卖不要香菜","keywords":["外卖"],"quote":"以后点外卖都不要香菜"}]}""",
            listOf("帮我点外卖,以后点外卖都不要香菜"))
        val added = changes.single() as Change.Added
        assertEquals("用户点外卖不要香菜", added.item.content)
        assertEquals("conv", added.item.source.conversationId)
        val note = MemoryExtractor.notes(changes).single()
        assertEquals(MemoryNote(NoteKind.added, added.item.id, added.undoToken, null), note)
        assertEquals("记住了:用户点外卖不要香菜", note.text(store.items.value.single()))
        assertEquals("记住了:一条已删除的记忆", note.text(null))
        assertEquals(1, store.items.value.size)
    }

    @Test fun quoteNotInUtterancesDropped() {
        val (changes, store) = run("""{"ops":[{"op":"add","kind":"fact","content":"用户的地址是张江路100号","quote":"张江路100号"}]}""", listOf("帮我查下快递"))
        assertTrue(changes.isEmpty()); assertTrue(store.items.value.isEmpty())
    }

    @Test fun updateTargetsExistingAndKeepsBefore() {
        val store = storeWith(MemoryKind.preference to "用户点外卖不要香菜")
        val (changes, _) = run("""{"ops":[{"op":"update","target":"#1","kind":"preference","content":"用户点外卖不要香菜和葱","quote":"以后外卖也别放葱"}]}""",
            listOf("点外卖,以后外卖也别放葱"), store)
        val u = changes.single() as Change.Updated
        assertEquals("用户点外卖不要香菜", u.before.content)
        assertEquals("用户点外卖不要香菜和葱", store.items.value.single().content)
        assertEquals("更新了:用户点外卖不要香菜和葱(原来:用户点外卖不要香菜)", MemoryExtractor.notes(changes).single().text(store.items.value.single()))
    }

    @Test fun unknownTargetDropped() {
        val store = storeWith(MemoryKind.preference to "用户点外卖不要香菜")
        val (changes, _) = run("""{"ops":[{"op":"update","target":"#7","content":"用户点外卖要辣","quote":"以后外卖要辣"},{"op":"forget","target":"m1","quote":"忘掉外卖的事"}]}""",
            listOf("点外卖,以后外卖要辣,忘掉外卖的事"), store)
        assertTrue(changes.isEmpty())
        assertEquals("用户点外卖不要香菜", store.items.value.single().content)
    }

    @Test fun forgetRemovesTargetAndIsUndoable() {
        val store = storeWith(MemoryKind.fact to "用户点外卖送到公司")
        val (changes, _) = run("""{"ops":[{"op":"forget","target":"#1","quote":"忘掉我外卖送公司的事"}]}""", listOf("点外卖送家里,忘掉我外卖送公司的事"), store)
        val f = changes.single() as Change.Forgot
        assertTrue(store.items.value.isEmpty())
        assertEquals("忘掉了一条记忆", MemoryExtractor.notes(changes).single().text(null))
        assertTrue(store.undo(f.undoToken))
        assertEquals(1, store.items.value.size)
    }

    @Test fun forgetWithoutForgetWordsIgnored() {
        val store = storeWith(MemoryKind.fact to "用户点外卖送到公司")
        val (changes, _) = run("""{"ops":[{"op":"forget","target":"#1","quote":"外卖送家里"}]}""", listOf("点外卖送家里"), store)
        assertTrue(changes.isEmpty()); assertEquals(1, store.items.value.size)
    }

    @Test fun oneOffParameterNotSaved() {
        val store = storeWith(MemoryKind.preference to "用户点外卖不要香菜")
        val (changes, _) = run("""{"ops":[{"op":"update","target":"#1","content":"用户点外卖多放香菜","quote":"这次多放香菜"}]}""", listOf("点外卖,这次多放香菜"), store)
        assertTrue(changes.isEmpty())
        assertEquals("用户点外卖不要香菜", store.items.value.single().content)
    }

    @Test fun procedureGoesPending() {
        val (changes, store) = run("""{"ops":[{"op":"add","kind":"procedure","content":"点外卖前先比较两家价格","quote":"以后点外卖都先比价"}]}""", listOf("以后点外卖都先比价"))
        val a = changes.single() as Change.Added
        assertEquals(MemoryStatus.pending, store.items.value.single().status)
        assertEquals(MemoryNote(NoteKind.pending, a.item.id, a.undoToken, a.item.id), MemoryExtractor.notes(changes).single())
        assertEquals("要我以后都这样做吗?点外卖前先比较两家价格", MemoryExtractor.notes(changes).single().text(store.items.value.single()))
    }

    @Test fun atMostFiveOps() {
        val ops = listOf("红", "橙", "黄", "绿", "青", "蓝", "紫").joinToString(",") { """{"op":"add","kind":"preference","content":"用户喜欢${it}色","quote":"我喜欢这些"}""" }
        val (changes, _) = run("""{"ops":[$ops]}""", listOf("我喜欢这些"))
        assertEquals(5, changes.size)
    }

    @Test fun garbageJsonGivesNoChanges() {
        assertTrue(run("我觉得没什么要记的", listOf("以后外卖不要香菜")).first.isEmpty())
        assertTrue(run("""{"ops":"nope"}""", listOf("以后外卖不要香菜")).first.isEmpty())
    }

    @Test fun modelErrorGivesNoChanges() {
        assertTrue(run(ModelError.Auth(), listOf("以后外卖不要香菜")).first.isEmpty())
    }

    @Test fun noUtterancesNoCall() {
        val model = FakeModel()
        assertTrue(runBlocking { MemoryExtractor(model).extract(emptyList(), emptyList(), memStore(), source) }.isEmpty())
        assertTrue(model.requests.isEmpty())
    }

    @Test fun promptContainsOnlyUserWords() {
        val store = storeWith(MemoryKind.preference to "用户点外卖不要香菜")
        val model = FakeModel(reply("""{"ops":[]}"""))
        run("", listOf("帮我点外卖", "要辣的"), store, model)
        val texts = model.requests.single().flatMap { it.parts }.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
        assertEquals("用户本次说的话:\n1. 帮我点外卖\n2. 要辣的\n\n已有的相关记忆:\n#1 [preference] 用户点外卖不要香菜", texts)
        assertTrue(model.offeredTools.single().isEmpty())
        assertFalse(texts.contains("phone_observe"))
    }
}
