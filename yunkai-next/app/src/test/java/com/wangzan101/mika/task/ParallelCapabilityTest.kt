package com.wangzan101.mika.task

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ParallelCapabilityTest {
    @get:Rule val texts = ChineseTexts()

    @Test fun android13IsOne() {
        val limit = ParallelCapability.detect(sdk = 33, lowRam = false, userMax = 3, secondDisplayFailed = false)
        assertEquals(ParallelLimit(1, "安卓 14 以上才支持同时进行多个任务"), limit)
    }

    @Test fun lowRamIsOne() {
        val limit = ParallelCapability.detect(sdk = 34, lowRam = true, userMax = 2, secondDisplayFailed = false)
        assertEquals(1, limit.max)
        assertNotNull(limit.reason)
    }

    @Test fun secondDisplayFailureIsOne() {
        val limit = ParallelCapability.detect(sdk = 35, lowRam = false, userMax = 2, secondDisplayFailed = true)
        assertEquals(1, limit.max)
        assertNotNull(limit.reason)
    }

    @Test fun userMaxClamped() {
        assertEquals(ParallelLimit(3, null), ParallelCapability.detect(34, false, 9, false))
        assertEquals(ParallelLimit(1, null), ParallelCapability.detect(34, false, 0, false))
        val two = ParallelCapability.detect(34, false, ParallelCapability.DEFAULT_MAX, false)
        assertEquals(2, two.max)
        assertNull(two.reason)
    }
}
