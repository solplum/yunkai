package com.wangzan101.mika.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryContextTest {
    private fun numberedLines(message: String) = message.lines().filter { Regex("^\\d+\\. ").containsMatchIn(it) }

    @Test fun pinnedAlwaysIncluded() {
        val pinned = (1..6).map { mem("置顶的事$it", pinned = true) }
        val block = MemoryContext.build(distractors(10) + pinned, goal = "查明天的天气", lastUserTurn = null)
        assertEquals(MemoryContext.MAX_PINNED, block.usedIds.size)
        assertTrue(block.usedIds.all { id -> pinned.any { it.id == id } })
    }

    @Test fun pinnedPendingNotIncluded() {
        val p = mem("置顶但未批准的做法", kind = MemoryKind.procedure, status = MemoryStatus.pending, pinned = true)
        assertNull(MemoryContext.build(listOf(p), goal = "随便", lastUserTurn = null).message)
    }

    @Test fun relevantCappedAt8() {
        val relevant = (1..12).map { mem("外卖习惯第${it}条", keywords = listOf("外卖")) }
        val block = MemoryContext.build(distractors(30) + relevant, goal = "点个外卖", lastUserTurn = null)
        assertEquals(MemoryContext.MAX_RELEVANT, block.usedIds.size)
        assertTrue(block.usedIds.all { id -> relevant.any { it.id == id } })
    }

    @Test fun proceduresOnlyApprovedAndCappedAt3() {
        val approved = (1..5).map { mem("点外卖时先比较第${it}家", kind = MemoryKind.procedure) }
        val pending = mem("点外卖时先领券", kind = MemoryKind.procedure, status = MemoryStatus.pending)
        val fact = mem("外卖送到公司前台", keywords = listOf("外卖"))
        val block = MemoryContext.build(distractors(20) + approved + pending + fact, goal = "点外卖", lastUserTurn = null)
        val msg = block.message!!
        assertFalse(msg.contains("先领券"))
        assertEquals(MemoryContext.MAX_PROCEDURES, block.usedIds.count { id -> approved.any { it.id == id } })
        assertTrue(fact.id in block.usedIds)
        val procSection = msg.substringAfter("用户批准的做法(和用户这次说的冲突时以这次为准):")
        assertEquals(3, numberedLines(procSection).size)
        assertTrue(numberedLines(procSection).all { it.contains("先比较") })
        assertFalse(msg.substringBefore("用户批准的做法").contains("先比较"))
    }

    @Test fun pinnedProceduresCountTowardCap() {
        val pinnedProcs = (1..2).map { mem("置顶做法$it", kind = MemoryKind.procedure, pinned = true) }
        val approved = (1..3).map { mem("点外卖时先比较第${it}家", kind = MemoryKind.procedure) }
        val block = MemoryContext.build(distractors(20) + pinnedProcs + approved, goal = "点外卖", lastUserTurn = null)
        assertTrue(pinnedProcs.all { it.id in block.usedIds })
        assertEquals(1, block.usedIds.count { id -> approved.any { it.id == id } })
    }

    @Test fun budget1200Respected() {
        val filler = "很".repeat(180)
        val relevant = (1..8).map { mem("外卖$it$filler", keywords = listOf("外卖")) }
        val pinned = (1..4).map { mem("置顶$it$filler", pinned = true) }
        val block = MemoryContext.build(distractors(20) + relevant + pinned, goal = "点外卖", lastUserTurn = null)
        val msg = block.message!!
        assertTrue(msg.length <= MemoryContext.MAX_CHARS)
        // 置顶的最后去:四条置顶都在,相关的只剩装得下的
        assertTrue(pinned.all { it.id in block.usedIds })
        assertTrue(block.usedIds.size in 5..6)
    }

    @Test fun budgetDropsLowestScoreFirst() {
        val filler = "很".repeat(185)
        val strong = (1..3).map { mem("外卖不要香菜$it$filler") }
        val weak = (1..5).map { mem("外卖$it$filler", keywords = listOf("外卖")) }
        val block = MemoryContext.build(distractors(20) + weak + strong, goal = "外卖不要香菜", lastUserTurn = null)
        assertTrue(block.message!!.length <= MemoryContext.MAX_CHARS)
        assertTrue(strong.all { it.id in block.usedIds })
        assertTrue(block.usedIds.size < 8)
    }

    @Test fun singleMemoryOneSharedTermUsed() {
        val only = mem("用户周末喜欢喝咖啡", keywords = listOf("咖啡"))
        assertEquals(listOf(only.id), MemoryContext.build(listOf(only), goal = "买杯咖啡", lastUserTurn = null).usedIds)
    }

    @Test fun belowMinScoreDropped() {
        // 每条都带同一个检索词:算相关,但这个词毫无区分度,分数低于阈值
        val same = (1..60).map { mem("外卖第${it}单", keywords = listOf("外卖")) }
        assertTrue(MemoryIndex(same).search("外卖", 1).single().second < MemoryContext.MIN_SCORE)
        val block = MemoryContext.build(same, goal = "外卖", lastUserTurn = null)
        assertNull(block.message)
        assertTrue(block.usedIds.isEmpty())
    }

    @Test fun lastUserTurnJoinsQuery() {
        val target = mem("用户的妈妈在微信里备注为老妈", keywords = listOf("妈妈", "微信"))
        val block = MemoryContext.build(distractors(10) + target, goal = "发过去", lastUserTurn = "把照片发给我妈妈")
        assertEquals(listOf(target.id), block.usedIds)
    }

    @Test fun headerSaysCurrentRequestWins() {
        val block = MemoryContext.build(distractors(10) + mem("外卖不要香菜"), goal = "点外卖,这次多放香菜", lastUserTurn = null)
        assertTrue(block.message!!.startsWith("关于用户的记忆(用户告诉过你的;可能过时,和用户这次说的冲突时以这次为准):"))
    }

    @Test fun proceduresOnlyBlockAlsoSaysCurrentRequestWins() {
        val block = MemoryContext.build(
            distractors(10) + mem("点外卖时先比价", kind = MemoryKind.procedure), goal = "点外卖", lastUserTurn = null,
        )
        assertTrue(block.message!!.startsWith("用户批准的做法(和用户这次说的冲突时以这次为准):"))
    }

    @Test fun emptyGivesNull() {
        assertEquals(MemoryBlock(null, emptyList()), MemoryContext.build(emptyList(), goal = "点外卖", lastUserTurn = null))
        assertNull(MemoryContext.build(distractors(10), goal = "点外卖", lastUserTurn = null).message)
    }

    @Test fun usedIdsMatchListed() {
        val items = distractors(20) + listOf(
            mem("外卖不要香菜", keywords = listOf("外卖")), mem("外卖送到公司前台", keywords = listOf("外卖")),
            mem("置顶的家庭住址在张江", pinned = true),
            mem("点外卖时先比价", kind = MemoryKind.procedure),
        )
        val block = MemoryContext.build(items, goal = "点外卖", lastUserTurn = null)
        val msg = block.message!!
        val listed = numberedLines(msg).map { it.substringAfter(". ") }
        val byId = items.associateBy { it.id }
        assertEquals(listed, block.usedIds.map { byId.getValue(it).content })
        assertEquals(4, block.usedIds.size)
    }
}
