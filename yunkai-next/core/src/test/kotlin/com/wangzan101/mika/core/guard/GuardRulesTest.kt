package com.wangzan101.mika.core.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuardRulesTest {
    @Test fun climbWarnsOncePerRungAndStopsAtTop() {
        val ladder = GuardRules.repeatLadder(diagnostic = false)
        assertNull(GuardRules.climb(2, ladder, 0, canStop = true))
        assertEquals(GuardRules.Rung(3, Severity.Warn), GuardRules.climb(3, ladder, 0, canStop = true))
        assertNull(GuardRules.climb(4, ladder, 3, canStop = true))
        assertEquals(GuardRules.Rung(6, Severity.Warn), GuardRules.climb(6, ladder, 3, canStop = true))
        assertNull(GuardRules.climb(11, ladder, 6, canStop = true))
        assertEquals(GuardRules.Rung(12, Severity.Stop), GuardRules.climb(12, ladder, 6, canStop = true))
        // 不许叫停的范围里最高一级也只提醒
        assertEquals(GuardRules.Rung(12, Severity.Warn), GuardRules.climb(13, ladder, 6, canStop = false))
    }

    @Test fun diagnosticReadsHaveHigherLadder() {
        assertNull(GuardRules.climb(5, GuardRules.repeatLadder(diagnostic = true), 0, canStop = true))
        assertEquals(Severity.Stop, GuardRules.climb(12, GuardRules.REREAD_LADDER, 8, canStop = true)!!.severity)
    }

    /** 安全网远在提醒之后:每条阶梯叫停的门槛至少是第一次提醒的两倍。 */
    @Test fun stopsAreFarBehindNudges() {
        val ladders = listOf(
            GuardRules.repeatLadder(false), GuardRules.repeatLadder(true), GuardRules.NO_EFFECT_LADDER, GuardRules.sameStateLadder(null),
            GuardRules.cycleLadder(null), GuardRules.noNewStateLadder(null), GuardRules.REREAD_LADDER, GuardRules.FAILURE_LADDER,
            GuardRules.sameStateLadder(5), GuardRules.cycleLadder(5), GuardRules.noNewStateLadder(5),
        )
        for (l in ladders) assert(l.last() >= 2 * l.first() && l == l.sorted()) { "ladder $l" }
        assertEquals(12, GuardRules.NO_EFFECT_LADDER.last())
    }

    /** 慢慢兜圈子的安全网跟着任务的步数上限走,但不低于下限。 */
    @Test fun lateStopsFollowTheStepBudget() {
        assertEquals(30, GuardRules.noNewStateLadder(null).last())
        assertEquals(50, GuardRules.noNewStateLadder(100).last())
        assertEquals(24, GuardRules.noNewStateLadder(20).last())
        assertEquals(25, GuardRules.sameStateLadder(100).last())
        assertEquals(20, GuardRules.cycleLadder(100).last())
    }

    @Test fun shortCyclesAreFound() {
        assertNull(GuardRules.cycle(listOf(1L, 2L)))
        assertEquals(GuardRules.Cycle(2, 3), GuardRules.cycle(listOf(1L, 2L, 1L)))
        assertEquals(2, GuardRules.cycle(listOf(5L, 3L, 1L, 2L, 1L, 2L))!!.rounds)
        assertNull(GuardRules.cycle(listOf(1L, 2L, 1L, 3L)))
        // 三个状态一圈
        val three = GuardRules.cycle(listOf(1L, 2L, 3L, 1L, 2L, 3L))!!
        assertEquals(3, three.period); assertEquals(2, three.rounds)
    }

    @Test fun failures() {
        assertNull(GuardRules.failures(1, 0))
        assertEquals(Severity.Warn, GuardRules.failures(2, 0))
        assertEquals(Severity.Warn, GuardRules.failures(1, 1))
        assertEquals(Severity.Warn, GuardRules.failures(7, 0))
        assertEquals(Severity.Stop, GuardRules.failures(8, 0))
        assertEquals(Severity.Stop, GuardRules.failures(3, 3))
    }
}
