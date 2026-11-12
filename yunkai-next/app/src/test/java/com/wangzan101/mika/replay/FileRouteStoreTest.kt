package com.wangzan101.mika.replay

import com.wangzan101.mika.core.replay.Anchors
import com.wangzan101.mika.core.replay.MAX_ROUTE_STEPS
import com.wangzan101.mika.core.replay.Route
import com.wangzan101.mika.core.replay.RouteStep
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileRouteStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun route(id: String, pkg: String, used: Long) = Route(id, "模板$id", 0, listOf(pkg), emptyMap(), emptyList(), lastUsedMs = used)

    @Test fun evictsLeastRecentlyUsedBeyondMax() {
        val dir = tmp.newFolder("routes")
        val store = FileRouteStore(dir, max = 2)
        store.put(route("a", "com.a", 10))
        store.put(route("b", "com.b", 5))
        store.put(route("c", "com.a", 20))
        assertEquals(setOf("a", "c"), store.all().map { it.id }.toSet())
        assertEquals(2, store.count())
        assertTrue(File(dir, "com.a/c.json").isFile)
        // 换一个实例读回来也一样
        assertEquals(setOf("a", "c"), FileRouteStore(dir, max = 2).all().map { it.id }.toSet())
    }

    @Test fun putSameIdReplaces() {
        val store = FileRouteStore(tmp.newFolder("routes"))
        store.put(route("a", "com.a", 1))
        store.put(route("a", "com.b", 2))
        assertEquals(listOf("com.b"), store.all().single().packages)
    }

    @Test fun clearRemovesAll() {
        val dir = tmp.newFolder("routes")
        val store = FileRouteStore(dir)
        store.put(route("a", "com.a", 1)); store.put(route("b", "com.b", 2))
        store.remove("a")
        assertEquals(listOf("b"), store.all().map { it.id })
        store.clear()
        assertEquals(0, store.count())
        assertTrue(store.all().isEmpty())
    }

    @Test fun putReplacingKeepsSuccessesAndDropsOld() {
        val store = FileRouteStore(tmp.newFolder("routes"))
        store.put(route("old", "com.a", 1).copy(template = "打开A", successes = 3))
        store.putReplacing(route("new", "com.b", 2).copy(template = "打开A"))
        val r = store.all().single()
        assertEquals("new", r.id); assertEquals(3, r.successes)
    }

    @Test fun tooLargeRouteRefusedAndOldKept() {
        val store = FileRouteStore(tmp.newFolder("routes"))
        store.put(route("a", "com.a", 1))
        val step = RouteStep("phone_back", "{}", null, Anchors(null, emptyList()), Anchors(null, emptyList()))
        val big = route("a", "com.a", 2).copy(steps = List(MAX_ROUTE_STEPS + 1) { step })
        assertThrows(IllegalArgumentException::class.java) { store.put(big) }
        assertEquals(1L, store.all().single().lastUsedMs)
    }

    @Test fun brokenFileIsDropped() {
        val dir = tmp.newFolder("routes")
        File(dir, "com.a").mkdirs(); File(dir, "com.a/x.json").writeText("{")
        val store = FileRouteStore(dir)
        store.put(route("a", "com.a", 1))
        assertEquals(listOf("a"), store.all().map { it.id })
    }

    // 两个任务同时给同一条路线记数:读改写一次完成,一次也不丢
    @Test fun updateIsAtomicAcrossThreads() {
        val store = FileRouteStore(tmp.newFolder("routes"))
        store.put(route("a", "p.one", 1))
        val threads = List(2) { Thread { repeat(50) { store.update("a") { it.copy(successes = it.successes + 1) } } } }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        assertEquals(100, store.all().single().successes)
        // 没有这条、或不改时什么也不做
        store.update("missing") { error("not called") }
        store.update("a") { null }
        assertEquals(100, store.all().single().successes)
    }
}
