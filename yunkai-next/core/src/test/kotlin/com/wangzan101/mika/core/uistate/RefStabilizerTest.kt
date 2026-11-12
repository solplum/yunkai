package com.wangzan101.mika.core.uistate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RefStabilizerTest {
    private var port = 0

    /** 端口每次读屏都从 @e1 重新编号、snapshotId 随机,和真端口一样。 */
    private fun shot(vararg els: Triple<String, Int, Int>, pkg: String = "com.x", width: Int = 1000, height: Int = 2000, list: Boolean = false,
                     rid: (String) -> String? = { null }): ScreenSnapshot {
        port++
        val items = els.mapIndexed { i, (name, x, y) ->
            UiElement("@e${i + 1}", "button", name, x, y, listOf(0, 0, 10, 10), rid(name), true, false, false)
        }
        // list:顶部以下是一个可滚动列表,顶部(y < 100 像素)的元素不在列表里
        val pane = UiElement("@e${items.size + 1}", "list", "", width / 2, height / 2, listOf(0, 100, 999, 999), null, false, false, true)
        return ScreenSnapshot("port$port", pkg, if (list) items + pane else items, width, height)
    }

    private fun el(name: String, x: Int = 500, y: Int = 500) = Triple(name, x, y)
    private fun ModelSnapshot.ref(name: String) = elements.single { it.name == name }.ref
    private fun ModelSnapshot.refs(name: String) = elements.filter { it.name == name }.map { it.ref }

    @Test fun snapshotIdsCarryThePrefix() {
        val st = RefStabilizer("w")
        val a = st.next(shot(el("搜索", 500, 100)))
        assertEquals("w1", a.snapshotId)
        assertTrue(st.resolve(a.ref("搜索"), "w1") is Resolved.Current)
        // 别的前缀的同号编号不是这块屏的
        assertEquals(Resolved.Stale, st.resolve(a.ref("搜索"), "p1"))
    }

    @Test fun unchangedElementKeepsRef() {
        val st = RefStabilizer()
        val a = st.next(shot(el("搜索", 500, 100), el("返回", 50, 100)))
        val b = st.next(shot(el("返回", 50, 100), el("新的", 300, 300), el("搜索", 500, 100)))
        assertEquals("s1", a.snapshotId); assertEquals("s2", b.snapshotId)
        assertEquals(a.ref("搜索"), b.ref("搜索"))
        assertEquals(a.ref("返回"), b.ref("返回"))
        // 模型编号映射到这一屏的端口编号
        assertEquals("@e3", b.toPort[b.ref("搜索")])
        assertEquals("@e1", b.toPort[b.ref("返回")])
        assertEquals("@e3", b.ref("新的"))
    }

    @Test fun movedWithin15PercentKeepsRef() {
        val st = RefStabilizer()
        val a = st.next(shot(el("确定", 500, 500)))
        val b = st.next(shot(el("确定", 500, 640))) // 140 ≤ 屏宽 1000 的 15%
        assertEquals(a.ref("确定"), b.ref("确定"))
    }

    @Test fun movedFartherGetsNewRef() {
        val st = RefStabilizer()
        val a = st.next(shot(el("确定", 500, 500)))
        val b = st.next(shot(el("确定", 500, 700)))
        assertNotEquals(a.ref("确定"), b.ref("确定"))
    }

    @Test fun keyIncludesResourceId() {
        val st = RefStabilizer()
        val a = st.next(shot(el("确定"), rid = { "a:id/ok" }))
        val b = st.next(shot(el("确定"), rid = { "a:id/other" }))
        assertNotEquals(a.ref("确定"), b.ref("确定"))
    }

    @Test fun duplicateKeysNeverKeepRefs() {
        val st = RefStabilizer()
        val a = st.next(shot(el("关注", 800, 300), el("关注", 800, 600), el("标题", 500, 50)))
        // 滚动一行之后同一位置已经是另一行的「关注」
        val b = st.next(shot(el("关注", 800, 300), el("关注", 800, 600), el("标题", 500, 50)))
        val old = a.refs("关注").toSet()
        assertTrue(b.refs("关注").none { it in old })
        assertEquals(2, b.refs("关注").toSet().size)
        assertEquals(a.ref("标题"), b.ref("标题"))
        // 一边唯一、另一边重复,也不沿用
        val c = st.next(shot(el("关注", 800, 300)))
        assertTrue(c.ref("关注") !in b.refs("关注"))
        val d = st.next(shot(el("关注", 800, 300), el("关注", 800, 600)))
        assertTrue(d.refs("关注").none { it == c.ref("关注") })
    }

    @Test fun unnamedElementsWithoutResourceIdNeverKeepRefs() {
        val st = RefStabilizer()
        val a = st.next(shot(el("", 500, 500)))
        val b = st.next(shot(el("", 500, 500)))
        assertNotEquals(a.elements.single().ref, b.elements.single().ref)
    }

    @Test fun retiredRefNeverReused() {
        val st = RefStabilizer()
        val a = st.next(shot(el("一", 100, 100), el("二", 200, 200), el("三", 300, 300)))
        assertEquals("@e3", a.ref("三"))
        val b = st.next(shot(el("一", 100, 100), el("二", 200, 200)))
        val c = st.next(shot(el("一", 100, 100), el("二", 200, 200), el("四", 300, 300), el("三", 300, 300)))
        assertTrue(b.elements.none { it.ref == "@e3" })
        assertNotEquals("@e3", c.ref("四"))
        assertNotEquals("@e3", c.ref("三")) // 退役后重新出现也是新编号
        val all = c.elements.map { it.ref }
        assertEquals(all.size, all.toSet().size)
    }

    @Test fun currentSnapshotResolvesWithoutRelocation() {
        val st = RefStabilizer()
        val a = st.next(shot(el("一", 100, 100), el("二", 200, 200)))
        val r = st.resolve(a.ref("二"), "s1") as Resolved.Current
        assertEquals("@e2", r.portRef); assertEquals(a.port.snapshotId, r.portSnapshotId); assertFalse(r.relocated)
    }

    @Test fun olderSnapshotRefStillCurrentIsRelocated() {
        val st = RefStabilizer()
        val a = st.next(shot(el("一", 100, 100), el("二", 200, 200)))
        val b = st.next(shot(el("一", 100, 100), el("二", 200, 260), el("零", 50, 900)))
        val r = st.resolve(a.ref("二"), "s1") as Resolved.Current
        assertEquals("@e2", r.portRef)
        assertEquals(b.port.snapshotId, r.portSnapshotId)
        assertTrue(r.relocated)
    }

    @Test fun retiredRefIsStale() {
        val st = RefStabilizer()
        val a = st.next(shot(el("一", 100, 100), el("二", 200, 200)))
        st.next(shot(el("二", 200, 200)))
        assertEquals(Resolved.Stale, st.resolve(a.ref("一"), "s1"))
        assertEquals(Resolved.Stale, st.resolve(a.ref("一"), "s2"))
    }

    @Test fun refNotInGivenSnapshotOrUnknownSnapshotIsStale() {
        val st = RefStabilizer()
        st.next(shot(el("一", 100, 100)))
        val b = st.next(shot(el("一", 100, 100), el("二", 200, 200)))
        // @e2 是第二屏才有的:配第一屏的 snapshotId 不成立
        assertEquals(Resolved.Stale, st.resolve(b.ref("二"), "s1"))
        assertEquals(Resolved.Stale, st.resolve(b.ref("一"), "s9"))
        assertEquals(Resolved.Stale, st.resolve(b.ref("一"), "port2"))
        assertEquals(Resolved.Stale, st.resolve("@e99", "s2"))
    }

    @Test fun singleSameNameNewRowGetsNewRef() {
        // 列表里只有一个「关注」;滚动后另一行的「关注」挪到附近:前面的用户名换了,不是同一个
        val st = RefStabilizer()
        val a = st.next(shot(el("张三", 300, 500), el("关注", 800, 500)))
        val b = st.next(shot(el("李四", 300, 520), el("关注", 800, 520)))
        assertNotEquals(a.ref("关注"), b.ref("关注"))
    }

    @Test fun listItemKeptOnlyInPlaceOnUnscrolledPage() {
        val st = RefStabilizer()
        val a = st.next(shot(el("标题", 500, 50), el("搜索", 900, 50), el("关注", 800, 500), list = true))
        val b = st.next(shot(el("标题", 500, 50), el("搜索", 900, 50), el("关注", 800, 500), list = true))
        assertEquals(a.ref("关注"), b.ref("关注"))
        // 上下文相同、挪了不到 15%,但在列表里:可能已经滚到另一行,不沿用
        val c = st.next(shot(el("标题", 500, 50), el("搜索", 900, 50), el("关注", 800, 600), list = true))
        assertNotEquals(b.ref("关注"), c.ref("关注"))
        assertEquals(b.ref("标题"), c.ref("标题"))
    }

    @Test fun sameButtonNewDialogGetsNewRef() {
        val st = RefStabilizer()
        val a = st.next(shot(el("删除这条记录?", 500, 900), el("确定", 500, 1100)))
        val b = st.next(shot(el("退出登录?", 500, 900), el("确定", 500, 1100)))
        assertNotEquals(a.ref("确定"), b.ref("确定"))
    }

    @Test fun chatSwitchSendGetsNewRef() {
        val st = RefStabilizer()
        val a = st.next(shot(el("张三", 500, 60), el("在吗", 300, 800), el("发送", 900, 1900)))
        val b = st.next(shot(el("李四", 500, 60), el("明天见", 300, 800), el("发送", 900, 1900)))
        assertNotEquals(a.ref("发送"), b.ref("发送"))
    }

    @Test fun foregroundAppChangeGivesAllNewRefs() {
        val st = RefStabilizer()
        val a = st.next(shot(el("搜索", 500, 100), el("返回", 50, 100)))
        val b = st.next(shot(el("搜索", 500, 100), el("返回", 50, 100), pkg = "com.y"))
        assertTrue(b.elements.none { e -> a.elements.any { it.ref == e.ref } })
    }

    @Test fun shiftLimitUsesShortSide() {
        // 横屏:宽 2000、高 1000,上限是 150 而不是 300
        val st = RefStabilizer()
        val a = st.next(shot(el("确定", 500, 300), width = 2000, height = 1000))
        val b = st.next(shot(el("确定", 500, 500), width = 2000, height = 1000))
        assertNotEquals(a.ref("确定"), b.ref("确定"))
    }

    @Test fun cumulativeShiftFromOldSnapshotIsStale() {
        // 每屏挪 100(都在 150 以内,编号沿用),但从第一屏算起已挪了 300
        val st = RefStabilizer()
        val a = st.next(shot(el("确定", 500, 500)))
        st.next(shot(el("确定", 500, 600)))
        st.next(shot(el("确定", 500, 700)))
        val d = st.next(shot(el("确定", 500, 800)))
        assertEquals(a.ref("确定"), d.ref("确定"))
        assertEquals(Resolved.Stale, st.resolve(a.ref("确定"), "s1"))
        assertTrue(st.resolve(a.ref("确定"), "s3") is Resolved.Current)
    }
}
