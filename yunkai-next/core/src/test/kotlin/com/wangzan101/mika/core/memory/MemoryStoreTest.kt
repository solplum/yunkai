package com.wangzan101.mika.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryStoreTest {
    private class FakePersistence(var stored: List<MemoryItem> = emptyList()) : MemoryPersistence {
        var saves = 0
        var failNext = false
        override fun load() = stored
        override fun save(items: List<MemoryItem>) {
            if (failNext) { failNext = false; throw java.io.IOException("disk full") }
            saves++; stored = items
        }
    }

    private var now = 1_000L
    private var seq = 0
    private fun store(p: FakePersistence = FakePersistence(), limit: Int = 500) =
        MemoryStore(p, clock = { now }, ids = { "id${++seq}" }, limit = limit)

    private val said = MemorySource(type = "user_said", quote = "外卖不要香菜", conversationId = "c1", atMs = 1L)

    private fun rejectCode(r: Result<*>) = (r.exceptionOrNull() as MemoryRejected).code

    @Test fun procedureStartsPendingOthersActive() {
        val s = store()
        assertEquals(MemoryStatus.active, s.add(MemoryKind.fact, "用户的妈妈备注为老妈", listOf("妈妈"), said).getOrThrow().item.status)
        assertEquals(MemoryStatus.active, s.add(MemoryKind.preference, "外卖不要香菜", listOf("外卖"), said).getOrThrow().item.status)
        assertEquals(MemoryStatus.pending, s.add(MemoryKind.procedure, "点外卖先比价", listOf("外卖"), said).getOrThrow().item.status)
    }

    @Test fun limitEnforced() {
        val s = store(limit = 2)
        s.add(MemoryKind.fact, "一", emptyList(), said).getOrThrow()
        s.add(MemoryKind.procedure, "二", emptyList(), said).getOrThrow() // pending 也算
        assertEquals("memory_full", rejectCode(s.add(MemoryKind.fact, "三", emptyList(), said)))
        assertEquals(2, s.items.value.size)
    }

    @Test fun contentLengthEnforced() {
        val s = store()
        assertEquals("too_long", rejectCode(s.add(MemoryKind.fact, "   ", emptyList(), said)))
        assertEquals("too_long", rejectCode(s.add(MemoryKind.fact, "字".repeat(201), emptyList(), said)))
        assertTrue(s.add(MemoryKind.fact, "字".repeat(200), emptyList(), said).isSuccess)
        val id = s.items.value.single().id
        assertEquals("too_long", rejectCode(s.update(id, "字".repeat(201), null, null)))
    }

    @Test fun sensitiveRejected() {
        val s = store()
        assertEquals("sensitive", rejectCode(s.add(MemoryKind.fact, "支付密码是123456", emptyList(), said)))
        assertEquals("sensitive", rejectCode(s.add(MemoryKind.fact, "用户喜欢清淡", listOf("验证码 8842"), said)))
        assertTrue(s.items.value.isEmpty())
    }

    @Test fun updateKeepsBeforeAndUndoRestores() {
        val s = store()
        val added = s.add(MemoryKind.preference, "外卖不要香菜", listOf("外卖"), said).getOrThrow()
        now = 2_000L
        val upd = s.update(added.item.id, "外卖不要香菜和葱", null, null).getOrThrow()
        assertEquals(added.item, upd.before)
        assertEquals("外卖不要香菜和葱", upd.item.content)
        assertEquals("外卖不要香菜", upd.item.replaces)
        assertEquals(listOf("外卖"), upd.item.keywords)
        assertEquals(2_000L, upd.item.updatedAt)
        assertTrue(s.undo(upd.undoToken))
        assertEquals(listOf(added.item), s.items.value)
        assertFalse(s.undo(upd.undoToken)) // 令牌用过即失效
        assertEquals("not_found", rejectCode(s.update("nope", "x", null, null)))
    }

    @Test fun forgetThenUndoRestores() {
        val s = store()
        val added = s.add(MemoryKind.fact, "用户住在浦东", emptyList(), said).getOrThrow()
        val f = s.forget(added.item.id).getOrThrow()
        assertEquals(added.item, f.before)
        assertTrue(s.items.value.isEmpty())
        assertTrue(s.undo(f.undoToken))
        assertEquals(listOf(added.item), s.items.value)
        assertEquals("not_found", rejectCode(s.forget("nope")))
    }

    @Test fun addThenUndoRemoves() {
        val s = store()
        val added = s.add(MemoryKind.fact, "用户住在浦东", emptyList(), said).getOrThrow()
        assertTrue(s.undo(added.undoToken))
        assertTrue(s.items.value.isEmpty())
        assertFalse(s.undo("unknown"))
    }

    @Test fun undoTokensKeepOnlyLatest50() {
        val s = store()
        val tokens = (1..51).map { s.add(MemoryKind.fact, "事实$it", emptyList(), said).getOrThrow().undoToken }
        assertFalse(s.undo(tokens.first()))
        assertTrue(s.undo(tokens.last()))
    }

    @Test fun approveMakesActive() {
        val s = store()
        val p = s.add(MemoryKind.procedure, "点外卖先比价", emptyList(), said).getOrThrow()
        assertTrue(s.approve(p.item.id))
        assertEquals(MemoryStatus.active, s.items.value.single().status)
        assertFalse(s.approve(p.item.id)) // 已生效
        assertFalse(s.approve("nope"))
        s.setPinned(p.item.id, true)
        assertTrue(s.items.value.single().pinned)
    }

    @Test fun markUsedCountsAndTimestamps() {
        val s = store()
        val a = s.add(MemoryKind.fact, "甲", emptyList(), said).getOrThrow().item
        val b = s.add(MemoryKind.fact, "乙", emptyList(), said).getOrThrow().item
        now = 5_000L
        s.markUsed(listOf(a.id))
        s.markUsed(listOf(a.id))
        val byId = s.items.value.associateBy { it.id }
        assertEquals(2, byId.getValue(a.id).useCount)
        assertEquals(5_000L, byId.getValue(a.id).lastUsedAt)
        assertEquals(0, byId.getValue(b.id).useCount)
        assertNull(byId.getValue(b.id).lastUsedAt)
    }

    @Test fun everyChangePersists() {
        val p = FakePersistence()
        val s = store(p)
        val a = s.add(MemoryKind.procedure, "做法", emptyList(), said).getOrThrow()
        assertEquals(1, p.saves)
        s.update(a.item.id, "新做法", listOf("k"), null).getOrThrow(); assertEquals(2, p.saves)
        s.approve(a.item.id); assertEquals(3, p.saves)
        s.setPinned(a.item.id, true); assertEquals(4, p.saves)
        s.markUsed(listOf(a.item.id)); assertEquals(5, p.saves)
        val f = s.forget(a.item.id).getOrThrow(); assertEquals(6, p.saves)
        s.undo(f.undoToken); assertEquals(7, p.saves)
        s.clearAll(); assertEquals(8, p.saves)
        assertEquals(s.items.value, p.stored)
        // 被拒的写入不落盘
        s.add(MemoryKind.fact, "", emptyList(), said)
        assertEquals(8, p.saves)
        // 重开时从落盘内容读回
        val p2 = FakePersistence()
        val s1 = store(p2)
        s1.add(MemoryKind.fact, "甲", emptyList(), said)
        assertEquals(p2.stored, store(p2).items.value)
    }

    @Test fun clearAllEmpties() {
        val s = store()
        val a = s.add(MemoryKind.fact, "甲", emptyList(), said).getOrThrow()
        s.clearAll()
        assertTrue(s.items.value.isEmpty())
        assertFalse(s.undo(a.undoToken)) // 清除后不能撤销回来
    }

    @Test fun updatedProcedureNeedsApprovalAgain() {
        val s = store()
        val p = s.add(MemoryKind.procedure, "点外卖先比价", emptyList(), said).getOrThrow()
        s.approve(p.item.id)
        assertEquals(MemoryStatus.pending, s.update(p.item.id, "点外卖先比三家", null, said).getOrThrow().item.status)
        s.approve(p.item.id)
        val manual = MemorySource(type = "manual", atMs = 3L)
        assertEquals(MemoryStatus.active, s.update(p.item.id, "点外卖先比两家", null, manual).getOrThrow().item.status)
        assertEquals(MemoryStatus.active, s.update(p.item.id, "点外卖先比四家", null, null).getOrThrow().item.status)
    }

    @Test fun updateUndoRefusedAfterLaterChange() {
        val s = store()
        val a = s.add(MemoryKind.fact, "甲", emptyList(), said).getOrThrow()
        val first = s.update(a.item.id, "乙", null, null).getOrThrow()
        val second = s.update(a.item.id, "丙", null, null).getOrThrow()
        assertFalse(s.undo(first.undoToken)) // 之后又改过,不能把「丙」抹掉
        assertEquals("丙", s.items.value.single().content)
        assertTrue(s.undo(second.undoToken))
        assertEquals("乙", s.items.value.single().content)
        val third = s.update(a.item.id, "丁", null, null).getOrThrow()
        s.forget(a.item.id).getOrThrow()
        assertFalse(s.undo(third.undoToken)) // 已忘掉的不会被更新撤销悄悄复活
        assertTrue(s.items.value.isEmpty())
    }

    @Test fun updateUndoKeepsUsageAndPin() {
        val s = store()
        val a = s.add(MemoryKind.fact, "甲", emptyList(), said).getOrThrow()
        val upd = s.update(a.item.id, "乙", null, null).getOrThrow()
        now = 9_000L
        s.markUsed(listOf(a.item.id))
        s.setPinned(a.item.id, true)
        assertTrue(s.undo(upd.undoToken)) // 用过、置顶不算又被改过
        val cur = s.items.value.single()
        assertEquals("甲", cur.content)
        assertEquals(1, cur.useCount)
        assertEquals(9_000L, cur.lastUsedAt)
        assertTrue(cur.pinned)
    }

    @Test fun unreadableFileNeverOverwrittenAndRetried() {
        var failures = 2
        val p = object : MemoryPersistence {
            var stored = listOf(MemoryItem("old", MemoryKind.fact, "原有的记忆", emptyList(), said, MemoryStatus.active, createdAt = 0, updatedAt = 0))
            var saves = 0
            override fun load(): List<MemoryItem> { if (failures-- > 0) throw java.security.KeyStoreException("busy"); return stored }
            override fun save(items: List<MemoryItem>) { saves++; stored = items }
        }
        val s = MemoryStore(p, clock = { now }, ids = { "id${++seq}" })
        assertFalse(s.available)
        assertTrue(s.items.value.isEmpty())
        assertEquals("unavailable", rejectCode(s.add(MemoryKind.fact, "新的", emptyList(), said))) // 第二次读也失败
        assertEquals(0, p.saves)
        s.markUsed(listOf("old"))
        assertEquals(0, p.saves)
        // 第三次读到了:原有的还在,新的加在后面
        assertTrue(s.add(MemoryKind.fact, "新的", emptyList(), said).isSuccess)
        assertTrue(s.available)
        assertEquals(listOf("原有的记忆", "新的"), p.stored.map { it.content })
    }

    @Test fun contentWhitespaceCollapsed() {
        val s = store()
        val a = s.add(MemoryKind.fact, "  用户住在\n浦东   张江 ", listOf(" 住 址 "), said).getOrThrow()
        assertEquals("用户住在 浦东 张江", a.item.content)
        assertEquals(listOf("住 址"), a.item.keywords)
        assertEquals("用户住在 张江", s.update(a.item.id, "用户住在\t\t张江", null, null).getOrThrow().item.content)
    }

    @Test fun replacesNotSetWhenContentUnchanged() {
        val s = store()
        val a = s.add(MemoryKind.fact, "用户住在浦东", emptyList(), said).getOrThrow()
        assertNull(s.update(a.item.id, "用户住在浦东", listOf("住址"), null).getOrThrow().item.replaces)
        val moved = s.update(a.item.id, "用户住在张江", null, null).getOrThrow().item
        assertEquals("用户住在浦东", moved.replaces)
        assertEquals("用户住在浦东", s.update(a.item.id, "用户住在张江", listOf("家"), null).getOrThrow().item.replaces)
    }

    @Test fun undoTokenKeptWhenPersistFails() {
        val p = FakePersistence()
        val s = store(p)
        val a = s.add(MemoryKind.fact, "甲", emptyList(), said).getOrThrow()
        p.failNext = true
        assertTrue(runCatching { s.undo(a.undoToken) }.isFailure)
        assertEquals(1, s.items.value.size)
        assertTrue(s.undo(a.undoToken))
        assertTrue(s.items.value.isEmpty())
    }
}
