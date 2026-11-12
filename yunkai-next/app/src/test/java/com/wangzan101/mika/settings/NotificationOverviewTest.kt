package com.wangzan101.mika.settings

import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.NotificationManager.IMPORTANCE_LOW
import android.app.NotificationManager.IMPORTANCE_NONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationOverviewTest {
    private fun of(app: Boolean = true, a: Int? = IMPORTANCE_HIGH, r: Int? = IMPORTANCE_HIGH, p: Int? = IMPORTANCE_LOW) =
        NotificationOverview.of(app, a, r, p)

    @Test
    fun expectedLevelsAreNormal() {
        val o = of()
        assertEquals(NotificationSummary.Normal, o.summary)
        assertEquals(ChannelLevel.HeadsUp, o.attention.level)
        assertEquals(ChannelLevel.HeadsUp, o.result.level)
        assertEquals(ChannelLevel.Quiet, o.progress.level)
        assertFalse(listOf(o.attention, o.result, o.progress).any { it.warn })
    }

    @Test
    fun appOffMakesEverythingOffWithoutRowWarnings() {
        val o = of(app = false, a = IMPORTANCE_NONE)
        assertEquals(NotificationSummary.AllOff, o.summary)
        for (row in listOf(o.attention, o.result, o.progress)) {
            assertEquals(ChannelLevel.Off, row.level)
            assertFalse(row.warn)
        }
    }

    @Test
    fun attentionLowToQuietWarns() {
        val o = of(a = IMPORTANCE_LOW)
        assertEquals(ChannelLevel.Quiet, o.attention.level)
        assertTrue(o.attention.warn)
        assertEquals(NotificationSummary.Lowered(1), o.summary)
    }

    @Test
    fun defaultImportanceIsNotHeadsUp() {
        val o = of(r = IMPORTANCE_DEFAULT)
        assertEquals(ChannelLevel.Quiet, o.result.level)
        assertTrue(o.result.warn)
    }

    @Test
    fun channelOffWarnsForAttentionAndResult() {
        val o = of(a = IMPORTANCE_NONE)
        assertEquals(ChannelLevel.Off, o.attention.level)
        assertTrue(o.attention.warn)
        assertEquals(NotificationSummary.Lowered(1), o.summary)
    }

    @Test
    fun progressOffOrHighNeverWarns() {
        assertEquals(NotificationSummary.Normal, of(p = IMPORTANCE_NONE).summary)
        assertEquals(ChannelLevel.Off, of(p = IMPORTANCE_NONE).progress.level)
        assertEquals(NotificationSummary.Normal, of(p = IMPORTANCE_HIGH).summary)
    }

    @Test
    fun countsEveryChannelThatWillNotPopUp() {
        assertEquals(NotificationSummary.Lowered(2), of(a = IMPORTANCE_LOW, r = IMPORTANCE_NONE, p = IMPORTANCE_NONE).summary)
    }

    @Test
    fun missingChannelsUseTheirInitialLevel() {
        assertEquals(NotificationSummary.Normal, of(a = null, r = null, p = null).summary)
    }
}
