package com.wangzan101.mika.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecentPagesTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000L

    private fun store(limit: Int = RecentPages.LIMIT) = RecentPages(tmp.root, limit, clock = { now })

    @Test
    fun newestFirstAndPersisted() {
        val r = store()
        r.add("https://a.example.com/", "A"); now += 10
        r.add("https://b.example.com/", null); now += 10
        r.add("https://c.example.com/", "C")
        assertEquals(listOf("https://c.example.com/", "https://b.example.com/", "https://a.example.com/"), r.entries.value.map { it.url })
        assertEquals("", r.entries.value[1].title)
        assertEquals(r.entries.value, store().entries.value)
    }

    @Test
    fun sameAddressMovesToTopOnce() {
        val r = store()
        r.add("https://a.example.com/x", "Old"); now += 10
        r.add("https://b.example.com/", "B"); now += 10
        r.add("https://a.example.com/x#section", "New")
        val list = r.entries.value
        assertEquals(2, list.size)
        assertEquals("https://a.example.com/x#section", list[0].url)
        assertEquals("New", list[0].title)
        assertEquals(1_020L, list[0].openedAtMs)
    }

    @Test
    fun keepsOnlyTheLimit() {
        val r = store(limit = 3)
        (1..5).forEach { r.add("https://s$it.example.com/", "S$it"); now += 1 }
        assertEquals(listOf("S5", "S4", "S3"), r.entries.value.map { it.title })
        assertEquals(3, store(limit = 3).entries.value.size)
    }

    @Test
    fun defaultLimitIsTen() {
        val r = store()
        (1..12).forEach { r.add("https://s$it.example.com/", "S$it") }
        assertEquals(10, r.entries.value.size)
        assertEquals("S12", r.entries.value.first().title)
    }

    @Test
    fun retitleKeepsOrder() {
        val r = store()
        r.add("https://a.example.com/", ""); now += 10
        r.add("https://b.example.com/", "B")
        r.retitle("https://a.example.com/", "A 页面")
        assertEquals(listOf("B", "A 页面"), r.entries.value.map { it.title })
        // 没有这一条时不加
        r.retitle("https://z.example.com/", "Z")
        assertEquals(2, r.entries.value.size)
    }

    @Test
    fun clearEmptiesAndPersists() {
        val r = store()
        r.add("https://a.example.com/", "A")
        r.clear()
        assertTrue(r.entries.value.isEmpty())
        assertTrue(store().entries.value.isEmpty())
    }

    @Test
    fun brokenFileStartsEmpty() {
        File(tmp.root, "browser_recent.json").writeText("{not json")
        val r = store()
        assertTrue(r.entries.value.isEmpty())
        r.add("https://a.example.com/", "A")
        assertEquals(1, store().entries.value.size)
    }
}
