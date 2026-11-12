package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.uistate.DOUBLE_TAP_GAP_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

class GesturePacingTest {
    private val lim = GesturePacing.Limits()

    // ---------- 不加节奏 ----------

    @Test fun instantKeepsTheOldTimingAndPoints() {
        val p = GesturePacing.Instant
        assertFalse(p.paced)
        assertEquals(0L, p.approach())
        assertEquals(GesturePacing.TAP_MS, p.press())
        assertEquals(GesturePacing.TAP_MS, p.doublePress())
        assertEquals(DOUBLE_TAP_GAP_MS, p.doubleGap())
        assertEquals(500L, p.duration(500))
        // 和 Rect.centerX/centerY 一样取整
        assertEquals(55 to 25, p.within(10, 0, 101, 51))
        assertEquals(7 to 9, p.near(7, 9))
        val s = p.stroke(listOf(100.0 to 900.0, 100.0 to 300.0), 400)
        assertEquals(listOf(GesturePacing.Point(100.0, 900.0, 0), GesturePacing.Point(100.0, 300.0, 400)), s.points)
        // 多点路径按路程匀速分配时间
        val poly = p.stroke(listOf(0.0 to 0.0, 0.0 to 100.0, 300.0 to 100.0), 800)
        assertEquals(listOf(0L, 200L, 800L), poly.points.map { it.t })
        assertEquals(800L, poly.durationMs)
    }

    // ---------- 点按 ----------

    @Test fun pressAndPausesStayInBounds() {
        repeat(500) { i ->
            val p = GesturePacing.human(Random(i))
            assertTrue(p.approach() in lim.approachMs)
            assertTrue(p.press() in lim.pressMs)
            val hold = p.doublePress()
            val gap = p.doubleGap()
            assertTrue(hold in lim.doublePressMs)
            // 系统认双击:第一下抬起到第二下按下 40–300 毫秒;按住远短于长按
            assertTrue(gap in 40L..300L && gap in lim.doubleGapMs)
            assertTrue(hold < 400)
        }
    }

    @Test fun jitteredPointStaysWellInsideTheElement() {
        repeat(2_000) { i ->
            val r = Random(i)
            val left = r.nextInt(0, 1000); val top = r.nextInt(0, 2000)
            val w = r.nextInt(1, 600); val h = r.nextInt(1, 300)
            val (x, y) = GesturePacing.human(Random(i)).within(left, top, left + w, top + h)
            val cx = (left + left + w) shr 1; val cy = (top + top + h) shr 1
            assertTrue("x=$x outside", x in left until left + w)
            assertTrue("y=$y outside", y in top until top + h)
            val reachX = if (w < lim.minJitterSpanPx) 0 else minOf((w * lim.jitterFraction).toInt(), lim.maxJitterPx)
            val reachY = if (h < lim.minJitterSpanPx) 0 else minOf((h * lim.jitterFraction).toInt(), lim.maxJitterPx)
            assertTrue("dx ${x - cx} > $reachX (w=$w)", abs(x - cx) <= reachX)
            assertTrue("dy ${y - cy} > $reachY (h=$h)", abs(y - cy) <= reachY)
        }
    }

    @Test fun tinyElementsAreTappedAtTheirCentre() {
        repeat(200) { i ->
            assertEquals(110 to 210, GesturePacing.human(Random(i)).within(100, 200, 120, 220))
        }
    }

    @Test fun jitterActuallyVaries() {
        val points = (0 until 200).map { GesturePacing.human(Random(it)).within(0, 0, 400, 200) }.toSet()
        assertTrue(points.size > 20)
    }

    @Test fun coordinateTapsMoveOnlyAFewPixels() {
        repeat(500) { i ->
            val (x, y) = GesturePacing.human(Random(i)).near(500, 1200)
            assertTrue(abs(x - 500) <= lim.pointJitterPx && abs(y - 1200) <= lim.pointJitterPx)
            val (ex, ey) = GesturePacing.human(Random(i)).near(0, 1)
            assertTrue(ex >= 0 && ey >= 0)
        }
    }

    // ---------- 滑动 ----------

    @Test fun strokeStartsAndEndsOnTheGivenPointsWithMonotonicTime() {
        repeat(500) { i ->
            val r = Random(i)
            val from = r.nextInt(0, 1080).toDouble() to r.nextInt(0, 2400).toDouble()
            val to = r.nextInt(0, 1080).toDouble() to r.nextInt(0, 2400).toDouble()
            val d = r.nextLong(50, 5001)
            val s = GesturePacing.human(Random(i)).stroke(listOf(from, to), d, bow = true, bounds = 1080 to 2400)
            val pts = s.points
            assertEquals(from, pts.first().x to pts.first().y)
            assertEquals(to, pts.last().x to pts.last().y)
            assertEquals(0L, pts.first().t)
            assertTrue(pts.zipWithNext().all { (a, b) -> b.t >= a.t })
            assertTrue(pts.size <= lim.maxPoints)
            assertTrue(pts.all { it.x in 0.0..1079.0 && it.y in 0.0..2399.0 })
            val still = hypot(to.first - from.first, to.second - from.second) < lim.stillPx
            if (still) {
                assertEquals(2, pts.size)
                assertTrue(s.durationMs >= d && s.durationMs <= d * (1 + lim.holdStretch) + 1)
            } else {
                assertTrue("${s.durationMs} vs $d", abs(s.durationMs - d) <= d * lim.durationJitter + 1)
                // 相邻两点的时间间隔不超过采样上限(点数封顶时除外)
                val gaps = pts.zipWithNext().map { (a, b) -> b.t - a.t }
                if (pts.size < lim.maxPoints) assertTrue(gaps.max() <= lim.sampleMs.last + 1)
            }
        }
    }

    @Test fun curveStaysCloseToTheLineAndAlwaysMovesForward() {
        repeat(300) { i ->
            val r = Random(i)
            val from = r.nextDouble(0.0, 1080.0) to r.nextDouble(0.0, 2400.0)
            val to = r.nextDouble(0.0, 1080.0) to r.nextDouble(0.0, 2400.0)
            val dist = hypot(to.first - from.first, to.second - from.second)
            if (dist < lim.stillPx) return@repeat
            val pts = GesturePacing.human(Random(i)).stroke(listOf(from, to), 400, bow = true).points
            val maxOff = minOf(dist * lim.bowFraction, lim.maxBowPx)
            for (p in pts) {
                val off = abs((to.second - from.second) * p.x - (to.first - from.first) * p.y + to.first * from.second - to.second * from.first) / dist
                // 二次贝塞尔离直线最远是控制点偏移的一半
                assertTrue("off $off > $maxOff", off <= maxOff / 2 + 1e-6)
            }
            val proj = pts.map { ((it.x - from.first) * (to.first - from.first) + (it.y - from.second) * (to.second - from.second)) / dist }
            assertTrue(proj.zipWithNext().all { (a, b) -> b >= a - 1e-6 })
        }
    }

    @Test fun quickSwipesReleaseAtAboutTheOldSpeedAndSlowDragsSettle() {
        val from = 540.0 to 1800.0
        val to = 540.0 to 600.0
        repeat(100) { i ->
            val fling = GesturePacing.human(Random(i)).stroke(listOf(from, to), 400).points
            val mean = 1200.0 / fling.last().t
            // 起步慢:第一段速度远低于平均
            assertTrue(speed(fling, 0) < mean * 0.5)
            // 松手前一段的速度和匀速滑动时差不多(甩出去的远近不变)
            val end = speed(fling, fling.size - 2)
            assertTrue("end $end vs mean $mean", end in mean * 0.75..mean * 1.1)
            val drag = GesturePacing.human(Random(i)).stroke(listOf(from, to), 1_500).points
            val dragMean = 1200.0 / drag.last().t
            // 慢拖收尾几乎停住,松手时不甩
            assertTrue(speed(drag, drag.size - 2) < dragMean * 0.2)
        }
    }

    @Test fun drawnPathsAreFollowedWithoutBow() {
        val path = listOf(100.0 to 100.0, 100.0 to 500.0, 600.0 to 500.0)
        val pts = GesturePacing.human(Random(3)).stroke(path, 600, bow = true).points
        // 每个点都在两段折线上
        assertTrue(pts.all { abs(it.x - 100.0) < 1e-6 || abs(it.y - 500.0) < 1e-6 })
        assertEquals(600.0 to 500.0, pts.last().x to pts.last().y)
    }

    @Test fun holdInPlaceOnlyGetsLonger() {
        repeat(200) { i ->
            val s = GesturePacing.human(Random(i)).stroke(listOf(300.0 to 300.0, 300.0 to 300.0), 600)
            assertEquals(2, s.points.size)
            assertTrue(s.durationMs in 600L..(600 * (1 + lim.holdStretch)).toLong() + 1)
        }
    }

    @Test fun multiFingerDurationVariesWithinBounds() {
        repeat(200) { i ->
            val d = GesturePacing.human(Random(i)).duration(500)
            assertTrue(abs(d - 500) <= 500 * lim.durationJitter + 1)
        }
    }

    @Test fun progressIsMonotonicFromZeroToOne() {
        for (e in listOf(0.0, 0.5, 0.9, 1.0)) {
            assertEquals(0.0, GesturePacing.progress(0.0, e), 1e-9)
            assertEquals(1.0, GesturePacing.progress(1.0, e), 1e-9)
            val xs = (0..100).map { GesturePacing.progress(it / 100.0, e) }
            assertTrue(xs.zipWithNext().all { (a, b) -> b >= a })
        }
    }

    @Test fun seededPacingIsReproducible() {
        val a = GesturePacing.human(Random(9)).stroke(listOf(0.0 to 0.0, 0.0 to 800.0), 400, bow = true)
        val b = GesturePacing.human(Random(9)).stroke(listOf(0.0 to 0.0, 0.0 to 800.0), 400, bow = true)
        assertEquals(a, b)
    }

    private fun speed(p: List<GesturePacing.Point>, i: Int): Double {
        val dt = (p[i + 1].t - p[i].t).coerceAtLeast(1)
        return hypot(p[i + 1].x - p[i].x, p[i + 1].y - p[i].y) / dt
    }
}
