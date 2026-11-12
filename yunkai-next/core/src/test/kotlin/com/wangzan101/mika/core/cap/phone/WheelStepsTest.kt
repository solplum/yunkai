package com.wangzan101.mika.core.cap.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 拿几种模拟的滚轮跑 [WheelSteps]:一下一格的数字滚轮(循环、不循环、往前变小)、一下好几格的列表、文字列表。 */
class WheelStepsTest {
    /** 模拟一个滚轮:[values] 从上到下,[index] 选中的,一下滚 [perAction] 格,[wrap] 循环。 */
    private class Wheel(val values: List<String>, var index: Int, val wrap: Boolean, val perAction: Int = 1, val forwardIsNext: Boolean = true) {
        fun step(forward: Boolean) {
            val d = (if (forward == forwardIsNext) 1 else -1) * perAction
            index = if (wrap) Math.floorMod(index + d, values.size) else (index + d).coerceIn(0, values.size - 1)
        }
        val current get() = values[index]
        fun visible(): List<String> = (-2..2).mapNotNull { o ->
            val i = index + o
            if (wrap) values[Math.floorMod(i, values.size)] else values.getOrNull(i)
        }
    }

    /** 跑到停为止,返回最后一步和一共滚了几下。 */
    private fun run(w: Wheel, target: String, cap: Int = 80): Pair<WheelSteps.Step, Int> {
        val s = WheelSteps(target, cap)
        var guard = 0
        while (guard++ < 500) {
            when (val step = s.next(w.current, w.visible())) {
                is WheelSteps.Step.Act -> repeat(step.count) { w.step(step.forward) }
                else -> return step to s.actions
            }
        }
        error("did not stop")
    }

    private val minutes = (0..59).map { "%02d".format(it) }

    @Test fun numberPickerReaches40From0TheShortWayRound() {
        val w = Wheel(minutes, 0, wrap = true)
        val (step, actions) = run(w, "40")
        assertEquals(WheelSteps.Step.Done, step)
        assertEquals("40", w.current)
        assertEquals(20, actions)
    }

    @Test fun nonWrappingWheelGoesTheLongWay() {
        val w = Wheel(minutes, 0, wrap = false)
        val (step, actions) = run(w, "40")
        assertEquals(WheelSteps.Step.Done, step)
        assertEquals(40, actions)
    }

    @Test fun alreadyThereDoesNothing() {
        val w = Wheel(minutes, 9, wrap = true)
        assertEquals(WheelSteps.Step.Done to 0, run(w, "9"))
    }

    @Test fun wheelWhoseForwardDecreasesIsCalibrated() {
        val w = Wheel(minutes, 10, wrap = false, forwardIsNext = false)
        val (step, actions) = run(w, "15")
        assertEquals(WheelSteps.Step.Done, step)
        assertEquals("15", w.current)
        assertTrue("$actions", actions <= 15)
    }

    /** 循环的滚轮没有头:滚了值不动是这一下没起作用,报 not_moving(交给拖动),不报到头。 */
    @Test fun stuckWrappingWheelIsNotAtItsEnd() {
        val s = WheelSteps("40")
        val visible = listOf("58", "59", "00", "01", "02")
        assertTrue(s.next("00", visible) is WheelSteps.Step.Act)
        assertEquals(WheelSteps.Step.Stop("not_moving"), s.next("00", visible))
    }

    @Test fun valueBeyondTheEndStops() {
        val hours = (0..23).map { "$it" }
        val w = Wheel(hours, 20, wrap = false)
        val (step, _) = run(w, "30")
        assertEquals(WheelSteps.Step.Stop("at_end"), step)
        assertEquals("23", w.current)
    }

    @Test fun pageSizedStepsThatOvershootStop() {
        val w = Wheel(minutes, 0, wrap = false, perAction = 7)
        val (step, _) = run(w, "40")
        // 一下七格停不到 40(只能停在 7 的倍数):来回越过几次后停,交给拖动
        assertEquals(WheelSteps.Step.Stop("overshoot"), step)
    }

    @Test fun textListScansThenReverses() {
        val cities = listOf("北京", "天津", "上海", "重庆", "广州", "深圳", "杭州", "成都")
        val w = Wheel(cities, 5, wrap = false)
        val (step, _) = run(w, "北京")
        assertEquals(WheelSteps.Step.Done, step)
        assertEquals("北京", w.current)
    }

    @Test fun textMissingFromBothEndsStops() {
        val w = Wheel(listOf("上午", "下午"), 0, wrap = false)
        val (step, _) = run(w, "晚上")
        assertEquals(WheelSteps.Step.Stop("at_end"), step)
    }

    @Test fun capLimitsTheActions() {
        val w = Wheel((0..2000).map { "$it" }, 0, wrap = false)
        val (step, actions) = run(w, "1500", cap = 80)
        assertEquals(WheelSteps.Step.Stop("cap"), step)
        assertEquals(80, actions)
    }

    @Test fun unreadableValueStops() {
        assertEquals(WheelSteps.Step.Stop("unknown_value"), WheelSteps("9").next(null))
    }
}
