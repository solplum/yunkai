package com.wangzan101.mika.core.cap.web

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** 停留时顺手滚几下的安排:够长才滚,下数、幅度、净移动都有界,时刻落在停留里。 */
class IdleScrollTest {
    private val l = IdleScroll.Limits()

    @Test
    fun `short dwells get no scrolls`() {
        val s = IdleScroll(Random(1))
        assertTrue(s.plan(0).isEmpty())
        assertTrue(s.plan(l.minWaitMs - 1).isEmpty())
    }

    @Test
    fun `plans are bounded small and mostly downward`() {
        var ups = 0
        var steps = 0
        for (seed in 0 until 500) {
            val wait = 3_000L + seed * 53 % 27_000
            val plan = IdleScroll(Random(seed)).plan(wait)
            assertTrue("$seed $plan", plan.size in 1..l.count.last)
            assertTrue("$seed $plan", plan.sumOf { abs(it.fraction) } <= l.maxTotal + 1e-9)
            val net = plan.sumOf { it.fraction }
            assertTrue("$seed $plan", net >= -1e-9 && net <= l.maxNet + 1e-9)
            // 第一下总往下;往回拨不超过之前往下滚的
            assertTrue("$seed $plan", plan.first().fraction > 0)
            var pos = 0.0
            plan.forEach { pos += it.fraction; assertTrue("$seed $plan", pos >= -1e-9) }
            plan.forEach { assertTrue("$seed $plan", abs(it.fraction) <= l.stepFraction.endInclusive + 1e-9) }
            // 时刻在停留里,前后留出余地,两下之间隔得开
            plan.forEach { assertTrue("$seed $plan", it.atMs >= l.leadMs && it.atMs <= wait - l.tailMs) }
            plan.zipWithNext().forEach { (a, b) -> assertTrue("$seed $plan", b.atMs - a.atMs >= l.gapMs) }
            ups += plan.count { it.fraction < 0 }
            steps += plan.size
        }
        assertTrue("$ups/$steps", ups > 0 && ups * 4 < steps)
    }
}
