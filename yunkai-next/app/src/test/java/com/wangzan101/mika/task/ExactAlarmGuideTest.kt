package com.wangzan101.mika.task

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactAlarmGuideTest {
    // 没有准时闹钟权限时只主动提醒一次;有权限不打扰
    @Test fun promptsOnceOnlyWhenPermissionMissing() {
        assertTrue(ExactAlarmGuide.shouldPrompt(granted = false, prompted = false))
        assertFalse(ExactAlarmGuide.shouldPrompt(granted = false, prompted = true))
        assertFalse(ExactAlarmGuide.shouldPrompt(granted = true, prompted = false))
    }
}
