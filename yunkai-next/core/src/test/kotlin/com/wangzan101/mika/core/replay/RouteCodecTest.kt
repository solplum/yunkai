package com.wangzan101.mika.core.replay

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteCodecTest {
    @Test fun roundTrip() {
        val r = Route(
            id = "r1", template = "在备忘录里写{1}", slots = 1, packages = listOf("com.notes"),
            appVersions = mapOf("com.notes" to "5.2"),
            steps = listOf(
                RouteStep("phone_launch_app", """{"package":"com.notes"}""", null, Anchors(null, emptyList()), Anchors("com.notes", listOf("备忘录"))),
                RouteStep("phone_tap", "{}", Selector("button", "新建", "n:id/new", listOf(1, 2, 3, 4), 0), Anchors("com.notes", listOf("备忘录")), Anchors("com.notes", listOf("完成"))),
            ),
            successes = 2, consecutiveMisses = 1, missStep = 1, disabled = false, lastUsedMs = 123,
        )
        assertEquals(r, RouteCodec.decode(RouteCodec.encode(r)))
    }

    @Test fun replacingKeepsSuccessesOfSameTemplate() {
        val store = object : RouteStore {
            val m = linkedMapOf<String, Route>()
            override fun all() = m.values.toList()
            override fun put(r: Route) { m[r.id] = r }
            override fun remove(id: String) { m.remove(id) }
            override fun clear() = m.clear()
            override fun count() = m.size
        }
        fun route(id: String, t: String, s: Int) = Route(id, t, 0, listOf("com.a"), emptyMap(), emptyList(), successes = s, lastUsedMs = 1)
        store.put(route("old", "打开A", 4))
        store.put(route("other", "打开B", 1))
        store.putReplacing(route("new", "打开A", 0))
        assertEquals(setOf("new", "other"), store.all().map { it.id }.toSet())
        assertEquals(4, store.all().first { it.id == "new" }.successes)
    }

    @Test fun samePathKeepsMissCountersChangedPathResets() {
        val step = RouteStep("phone_back", "{}", null, Anchors(null, emptyList()), Anchors(null, listOf("甲")))
        val old = Route("o", "t", 0, emptyList(), emptyMap(), listOf(step), successes = 2, consecutiveMisses = 2, missStep = 0, disabled = true, lastUsedMs = 1)
        // 锚点略有不同仍是同一条路
        val same = replacing(old.copy(id = "n", successes = 0, consecutiveMisses = 0, missStep = null, disabled = false,
            steps = listOf(step.copy(after = Anchors(null, listOf("乙"))))), listOf(old))
        assertEquals(listOf(2, 2, 0), listOf(same.successes, same.consecutiveMisses, same.missStep))
        assertEquals(true, same.disabled)
        val changed = replacing(Route("n", "t", 0, emptyList(), emptyMap(), listOf(step.copy(tool = "phone_press_key", argsJson = """{"key":"enter"}""")), lastUsedMs = 2), listOf(old))
        assertEquals(listOf(2, 0, null), listOf(changed.successes, changed.consecutiveMisses, changed.missStep))
        assertEquals(false, changed.disabled)
    }
}
