package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.replay.Anchors
import com.wangzan101.mika.core.replay.Selector
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.uistate.interfaces

private fun el(ref: String, role: String, name: String, b: List<Int>, clickable: Boolean = false, rid: String? = null, editable: Boolean = false) =
    UiElement(ref, role, name, 0, 0, b, rid, clickable, editable, false)

private class NotesScreen(private val inner: FakeScreen = FakeScreen()) : ScreenPort by inner {
    var current = listOf(
        el("@e1", "text", "备忘录", listOf(0, 0, 999, 60)),
        el("@e2", "button", "新建", listOf(800, 900, 999, 999), clickable = true, rid = "n:id/new"),
        el("@e3", "text", "12:30", listOf(0, 60, 200, 100)),
        el("@e4", "text", "全部笔记", listOf(0, 100, 999, 160)),
        el("@e5", "list", "", listOf(0, 160, 999, 900)),
        el("@e6", "button", "购物清单", listOf(0, 200, 999, 260), clickable = true),
        el("@e7", "button", "¥ 25.00", listOf(0, 300, 999, 360), clickable = true),
    )
    var n = 0
    override suspend fun snapshot(): ScreenSnapshot { n++; return ScreenSnapshot("s$n", "com.notes", current, 1080, 2400) }
    override suspend fun peek(): ScreenSnapshot = ScreenSnapshot("p", "com.notes", current, 1080, 2400)
}

class ToolDispatcherActionTargetTest {
    private fun call(name: String, args: String = "{}") = Part.ToolCall("c1", name, args)

    @Test fun tapByRefCapturesSelectorAndAnchors() = runTest {
        val s = NotesScreen(); val d = toolDispatcher(s).also { it.captureTargets = true }
        d.execute(call("phone_observe"))
        d.execute(call("phone_tap", """{"ref":"@e2","snapshotId":"s1"}"""))
        val (target, before) = d.interfaces.lastActionTarget()!!
        assertEquals(Selector("button", "新建", "n:id/new", listOf(800, 900, 999, 999), 0, uniqueAtRecord = true), target)
        // 时间样式的字、目标自己、列表里的条目都不当锚点
        assertEquals(Anchors("com.notes", listOf("备忘录", "全部笔记")), before)
    }

    @Test fun coordinateTapCapturesElementAtPoint() = runTest {
        val s = NotesScreen(); val d = toolDispatcher(s).also { it.captureTargets = true }
        d.execute(call("phone_observe"))
        // 落在列表(大)和「购物清单」(小)里:取最小的那个
        d.execute(call("phone_tap", """{"x":500,"y":230}"""))
        val (target, before) = d.interfaces.lastActionTarget()!!
        assertEquals(Selector("button", "购物清单", null, listOf(0, 200, 999, 260), 0, uniqueAtRecord = true), target)
        // 有资源 ID 的排前面
        assertEquals(listOf("新建", "备忘录", "全部笔记"), before.texts)
    }

    @Test fun nothingCapturedWhenOff() = runTest {
        val d = toolDispatcher(NotesScreen())
        d.execute(call("phone_observe"))
        d.execute(call("phone_tap", """{"ref":"@e2","snapshotId":"s1"}"""))
        assertNull(d.interfaces.lastActionTarget())
    }

    @Test fun editableTargetKeepsOnlyHintAndUnstableNamesDropped() = runTest {
        val s = NotesScreen(); val d = toolDispatcher(s).also { it.captureTargets = true }
        s.current = listOf(
            UiElement("@e1", "edit", "张三的手机号13800000000", 0, 0, listOf(0, 0, 999, 100), "n:id/body", false, true, false),
            UiElement("@e2", "edit", "搜索笔记", 0, 0, listOf(0, 100, 999, 200), "n:id/q", false, true, false, hint = true),
            el("@e3", "button", "删除 3 条", listOf(0, 200, 999, 300), clickable = true, rid = "n:id/del"),
        )
        d.execute(call("phone_observe"))
        d.execute(call("phone_type_text", """{"ref":"@e1","snapshotId":"s1","text":"x"}"""))
        assertEquals(Selector("edit", "", "n:id/body", listOf(0, 0, 999, 100), 0, uniqueAtRecord = true), d.interfaces.lastActionTarget()!!.first)
        d.execute(call("phone_type_text", """{"ref":"@e2","snapshotId":"s1","text":"x"}"""))
        assertEquals("搜索笔记", d.interfaces.lastActionTarget()!!.first!!.name)
        d.execute(call("phone_tap", """{"ref":"@e3","snapshotId":"s1"}"""))
        assertEquals(Selector("button", "", "n:id/del", listOf(0, 200, 999, 300), 0, uniqueAtRecord = true), d.interfaces.lastActionTarget()!!.first)
    }

    @Test fun nonWriteHasNoTarget() = runTest {
        val d = toolDispatcher(NotesScreen()).also { it.captureTargets = true }
        d.execute(call("phone_tap", """{"ref":"@e2","snapshotId":"s1"}"""))
        d.execute(call("phone_observe"))
        assertNull(d.interfaces.lastActionTarget())
    }

    @Test fun backHasAnchorsButNoTarget() = runTest {
        val d = toolDispatcher(NotesScreen()).also { it.captureTargets = true }
        d.execute(call("phone_observe"))
        d.execute(call("phone_back"))
        val (target, before) = d.interfaces.lastActionTarget()!!
        assertNull(target)
        assertEquals("com.notes", before.packageName)
    }

    @Test fun afterAnchorsAreNewlyAppearedTexts() = runTest {
        val s = NotesScreen(); val d = toolDispatcher(s).also { it.captureTargets = true }
        d.execute(call("phone_observe"))
        d.execute(call("phone_tap", """{"ref":"@e2","snapshotId":"s1"}"""))
        s.current = listOf(
            el("@e1", "text", "备忘录", listOf(0, 0, 999, 60)),
            el("@e8", "text", "新笔记", listOf(0, 60, 999, 120)),
            el("@e9", "edit", "写点什么", listOf(0, 120, 999, 900), editable = true),
            el("@e10", "button", "完成", listOf(800, 0, 999, 60), clickable = true),
        )
        // 输入框里的字是内容不是界面,不当锚点
        assertEquals(Anchors("com.notes", listOf("新笔记", "完成")), d.interfaces.actionAfter())
    }
}
