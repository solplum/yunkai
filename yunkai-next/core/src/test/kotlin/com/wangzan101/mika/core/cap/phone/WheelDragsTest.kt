package com.wangzan101.mika.core.cap.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * 拿模拟的、App 自己画的滚轮跑 [WheelDrags]:只能拖,读回的是选中项上下各 [radius] 项,选中项的名字可能带状态词。
 * 重现真机上的时钟:24 小时的时、分滚轮都循环,选中项叫「已选中，21」,上下是 20、22。
 */
class WheelDragsTest {
    /**
     * [size] 个值(0 起),[value] 选中的;手指往上拖一格 [rowPx] 像素转过一格(数变大,[upIncreases] 为 false 时变小);
     * 前 [deadDrags] 次拖动没有反应;[label] 是每一项的名字(选中项第二个参数为 true)。
     */
    private class Wheel(
        var value: Int,
        val size: Int,
        val wrap: Boolean,
        val radius: Int = 1,
        val rowPx: Int = 80,
        val upIncreases: Boolean = true,
        var deadDrags: Int = 0,
        val label: (Int, Boolean) -> String = { v, sel -> if (sel) "已选中，$v" else "$v" },
    ) {
        var drags = 0
        private fun visibleValues(): List<Pair<Int, Boolean>> = (-radius..radius).mapNotNull { o ->
            val v = value + o
            val shown = if (wrap) Math.floorMod(v, size) else v.takeIf { it in 0 until size }
            shown?.let { it to (o == 0) }
        }
        fun items() = visibleValues().map { (v, sel) -> label(v, sel) }
        fun selected() = visibleValues().indexOfFirst { it.second }
        /** 读回的各项中心:按估的一格 80 像素排(实际一格是 [rowPx]),好测校准。 */
        fun centers() = visibleValues().indices.map { 300 + it * 80 }
        fun drag(upPx: Int) {
            drags++
            if (deadDrags > 0) { deadDrags--; return }
            val n = (upPx.toDouble() / rowPx).roundToInt() * if (upIncreases) 1 else -1
            value = if (wrap) Math.floorMod(value + n, size) else (value + n).coerceIn(0, size - 1)
        }
    }

    /** 跑到停为止,返回最后一步和第一次拖了几格。 */
    private fun run(w: Wheel, target: String, rowHeight: Int = 80): Pair<WheelDrags.Step, Int?> {
        val plan = WheelDrags(target, rowHeight)
        var dragged = 0
        var first: Int? = null
        repeat(60) {
            when (val s = plan.next(w.items(), w.selected(), w.centers(), dragged)) {
                is WheelDrags.Step.Drag -> {
                    if (first == null) first = s.steps
                    dragged = (s.steps * plan.itemHeight * s.boost).roundToInt()
                    w.drag(dragged)
                }
                else -> return s to first
            }
        }
        error("did not stop")
    }

    @Test fun hoursFrom21To7WithStatePrefixedLabel() {
        val w = Wheel(21, 24, wrap = true)
        assertEquals(WheelDrags.Step.Done, run(w, "7").first)
        assertEquals(7, w.value)
    }

    @Test fun minutesFrom21To25() {
        val w = Wheel(21, 60, wrap = true)
        val (step, first) = run(w, "25")
        assertEquals(WheelDrags.Step.Done, step)
        assertEquals(25, w.value)
        assertEquals(4, first)
    }

    @Test fun englishStatePrefixAndUnitSuffix() {
        val en = Wheel(21, 24, wrap = true, label = { v, sel -> if (sel) "Selected, $v" else "$v" })
        assertEquals(WheelDrags.Step.Done, run(en, "7").first)
        assertEquals(7, en.value)
        val unit = Wheel(21, 24, wrap = true, label = { v, _ -> "${v}点" })
        assertEquals(WheelDrags.Step.Done, run(unit, "7").first)
        assertEquals(7, unit.value)
    }

    /** 看得到 23 → 0 时走近的一边:21 → 7 往前 10 格,不是往回 14 格。 */
    @Test fun wrapSeenTakesTheShortWayRound() {
        val w = Wheel(21, 24, wrap = true, radius = 3)
        val (step, first) = run(w, "7")
        assertEquals(WheelDrags.Step.Done, step)
        assertEquals(10, first)
        assertEquals(7, w.value)
    }

    /** 第一下拖了没反应(拖得太短、刚好被当成点按):不当作到头,拖远一点再来。 */
    @Test fun aDragWithNoEffectIsRetriedNotReportedAsTheEnd() {
        val w = Wheel(21, 24, wrap = true, deadDrags = 1)
        assertEquals(WheelDrags.Step.Done, run(w, "7").first)
        assertEquals(7, w.value)
    }

    /** 怎么拖都不动:报 not_moving(模型自己划),不报 at_end。 */
    @Test fun wheelThatNeverMovesIsNotMovingNotAtEnd() {
        val w = Wheel(21, 24, wrap = true, deadDrags = 100)
        assertEquals(WheelDrags.Step.Stop("not_moving"), run(w, "7").first)
        assertEquals(3, w.drags)
        assertEquals(21, w.value)
    }

    /** 不循环的滚轮拖到了头、往那边已经没有项:才是到头。 */
    @Test fun nonWrappingWheelStopsAtItsEnd() {
        val w = Wheel(8, 11, wrap = false)
        assertEquals(WheelDrags.Step.Stop("at_end"), run(w, "15").first)
        assertEquals(10, w.value)
    }

    /** 往上拖数变小的滚轮:第一次转动后校正方向。 */
    @Test fun wheelWhoseUpDragDecreasesIsCalibrated() {
        val w = Wheel(21, 60, wrap = true, upIncreases = false)
        assertEquals(WheelDrags.Step.Done, run(w, "25").first)
        assertEquals(25, w.value)
    }

    /** 一格比估的高:按实际转过的格数校准后补拖到位。 */
    @Test fun rowHeightIsCalibrated() {
        val w = Wheel(21, 60, wrap = true, rowPx = 120)
        val (step, _) = run(w, "40", rowHeight = 80)
        assertEquals(WheelDrags.Step.Done, step)
        assertEquals(40, w.value)
        assertTrue(w.drags <= 4)
    }

    @Test fun valueNotOnTheWheelIsNotForced() {
        val w = Wheel(0, 60, wrap = true, radius = 2)
        val (step, _) = run(w, "75")
        assertTrue("$step", step is WheelDrags.Step.Stop)
    }
}
