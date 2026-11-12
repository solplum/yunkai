package com.wangzan101.mika.cap.phone.a11y

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.TEST_PACKAGE
import com.wangzan101.mika.core.cap.phone.WheelMath
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机测试:在 Agent 虚拟屏上打开 [WheelPickerTestActivity],把原版数字滚轮从 0 转到 40,一次调用做完。
 * 「锁住」那个只能走无障碍滚动(一下一格,从 0 往回走 20 格最近);读屏时两个都标成滚轮。
 */
@RunWith(AndroidJUnit4::class)
class WheelPickerDeviceTest {
    private val agent = DeviceSupport.newAgent()
    private val screen get() = agent.screen

    @After
    fun tearDown() = agent.release()

    private fun open(): ScreenSnapshot {
        DeviceSupport.sh("am start --display ${agent.display.displayId} -n $TEST_PACKAGE/${WheelPickerTestActivity::class.java.name}")
        val s = DeviceSupport.pollUntil(8_000) {
            runBlocking { screen.snapshot() }.takeIf { s -> s.elements.count { it.role == "NumberPicker" } == 2 }
        }
        return s ?: error("wheel page never showed: ${runBlocking { screen.snapshot() }.elements.map { "${it.role}:${it.name}" }}")
    }

    /** 原版数字滚轮在读屏里不带名字:按位置认,页面上左边是「分」、右边是「锁住」(见 [WheelPickerTestActivity])。 */
    private fun ScreenSnapshot.picker(label: String): UiElement {
        val pickers = elements.filter { it.role == "NumberPicker" }.sortedBy { it.centerX }
        check(pickers.size == 2) { "expected 2 NumberPickers, got ${pickers.map { it.centerX }}" }
        return if (label == WheelPickerTestActivity.MINUTES) pickers[0] else pickers[1]
    }

    private fun flatten(n: com.wangzan101.mika.core.uistate.UiTreeNode): List<com.wangzan101.mika.core.uistate.UiTreeNode> =
        listOf(n) + n.children.flatMap(::flatten)

    /** 再让工具调到同一个值一次:滚轮真的已经停在那里,就一下都不用动、起点就是它。 */
    private suspend fun assertStaysAt(label: String, value: String) {
        val s = screen.snapshot()
        val again = screen.setWheel(s.picker(label).ref, s.snapshotId, value)
        assertTrue("$again", again.reached && again.attempts == 0 && WheelMath.matches(again.from, value))
    }

    @Test
    fun wheelsAreMarkedInTheTree() = runBlocking<Unit> {
        val s = open()
        val wheels = s.windows.flatMap { it.nodes }.flatMap(::flatten).filter { it.wheel }
        assertEquals(2, wheels.size)
    }

    @Test
    fun lockedWheelReaches40From0ByStepping() = runBlocking<Unit> {
        val s = open()
        val o = screen.setWheel(s.picker(WheelPickerTestActivity.LOCKED).ref, s.snapshotId, "40")
        assertTrue("$o", o.reached)
        assertEquals("actions", o.method)
        assertEquals("00", o.from)
        assertTrue("$o", WheelMath.matches(o.value, "40"))
        // 0 → 40 往回走 20 格最近;每格一下,读回不另算
        assertTrue("$o", o.attempts in 20..24)
        assertStaysAt(WheelPickerTestActivity.LOCKED, "40")
    }

    @Test
    fun typableWheelReaches40() = runBlocking<Unit> {
        val s = open()
        val o = screen.setWheel(s.picker(WheelPickerTestActivity.MINUTES).ref, s.snapshotId, "40")
        assertTrue("$o", o.reached)
        assertTrue("$o", o.method in setOf("text", "actions"))
        assertStaysAt(WheelPickerTestActivity.MINUTES, "40")
        // 另一个滚轮没被动过
        assertStaysAt(WheelPickerTestActivity.LOCKED, "0")
    }

    @Test
    fun valueNotOnTheWheelIsReportedNotForced() = runBlocking<Unit> {
        val s = open()
        val o = screen.setWheel(s.picker(WheelPickerTestActivity.LOCKED).ref, s.snapshotId, "75")
        assertTrue("$o", !o.reached)
        assertTrue("$o", o.value != null)
    }
}
