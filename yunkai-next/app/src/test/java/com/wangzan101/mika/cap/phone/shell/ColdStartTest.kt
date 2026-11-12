package com.wangzan101.mika.cap.phone.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class ColdStartTest {
    private val agent = 7

    @Test fun forceStopsOnlyWhenEveryTaskIsOnMikasScreen() {
        assertEquals(ColdStart.RESTART, ColdStart.decide(emptyList(), agent, visibleOnMain = false))
        assertEquals(ColdStart.RESTART, ColdStart.decide(listOf(agent, agent), agent, visibleOnMain = false))
    }

    @Test fun backgroundTaskOnAnotherScreenFallsBackToWarmStart() {
        assertEquals(ColdStart.WARM, ColdStart.decide(listOf(0), agent, visibleOnMain = false))
        assertEquals(ColdStart.WARM, ColdStart.decide(listOf(agent, 3), agent, visibleOnMain = false))
    }

    @Test fun visibleOnMainIsInUse() {
        assertEquals(ColdStart.IN_USE, ColdStart.decide(listOf(0), agent, visibleOnMain = true))
    }
}
