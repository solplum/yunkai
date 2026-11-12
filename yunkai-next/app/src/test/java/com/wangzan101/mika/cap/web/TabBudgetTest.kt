package com.wangzan101.mika.cap.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabBudgetTest {
    /** 一个任务(标签池)的假成员:按打开先后用预算的钟记「最近用过」。 */
    private class Run(val budget: TabBudget, val name: String) : TabBudget.Member {
        val tabs = LinkedHashMap<String, Long>()
        val locked = mutableSetOf<String>()
        val evicted = mutableListOf<String>()
        fun open(id: String) { tabs[id] = budget.tick() }
        override fun tabCount() = tabs.size
        override fun oldestSpare(except: String?) =
            tabs.filterKeys { it != except && it !in locked }.minByOrNull { it.value }?.toPair()
        override fun evictForBudget(tabId: String) { tabs.remove(tabId); evicted += tabId }
    }

    @Test
    fun totalCountsEveryRun() {
        val b = TabBudget(8)
        val a = Run(b, "a").also(b::join)
        val c = Run(b, "c").also(b::join)
        repeat(3) { a.open("a$it") }
        repeat(2) { c.open("c$it") }
        assertEquals(5, b.total())
        b.leave(c)
        assertEquals(3, b.total())
    }

    @Test
    fun whenFullTheOldestSpareTabAnywhereGoes() {
        val b = TabBudget(3)
        val a = Run(b, "a").also(b::join)
        val c = Run(b, "c").also(b::join)
        a.open("a1"); c.open("c1"); c.open("c2")
        assertTrue(b.full())
        assertTrue(b.evictOldest(except = null))
        assertEquals(listOf("a1"), a.evicted)
        assertFalse(b.full())
    }

    @Test
    fun lockedAndExceptedTabsAreNeverEvicted() {
        val b = TabBudget(2)
        val a = Run(b, "a").also(b::join)
        a.open("a1"); a.open("a2")
        a.locked += "a1"
        assertTrue(b.evictOldest(except = null))
        assertEquals(listOf("a2"), a.evicted)
        a.open("a3")
        // a1 锁着、a3 是要保留的:没有可关的
        assertFalse(b.evictOldest(except = "a3"))
    }
}
