package com.wangzan101.mika.task

import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.TriggerWindowState
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class FileTriggerStoreTest {
    @Test fun claimAndLedgerSurviveStoreReopen() {
        val file = Files.createTempDirectory("trigger-test").resolve("triggers.json").toFile()
        val first = FileTriggerStore(file)
        first.upsert(TriggerDefinition("hourly", "thread", "check", 1_000, 1_000))
        first.claimDue(3_100)
        val restored = FileTriggerStore(file)
        assertEquals(4_000L, restored.get("hourly")!!.nextAtMs)
        assertEquals(2L, restored.windows().single().missedIntervals)
        assertEquals(TriggerWindowState.Claimed, restored.windows().single().state)
        assertEquals(emptyList<Any>(), restored.claimDue(3_100))
    }

    @Test fun reminderAndCalendarSettingsSurviveStoreReopen() {
        val file = Files.createTempDirectory("trigger-test").resolve("triggers.json").toFile()
        FileTriggerStore(file).upsert(TriggerDefinition("daily", "thread", "remind", 1_000,
            reminderText = "该喝水了", calendarTime = "09:00", calendarZone = "Asia/Shanghai",
            calendarDays = listOf(1, 5)))
        val restored = FileTriggerStore(file).get("daily")!!
        assertEquals("该喝水了", restored.reminderText)
        assertEquals("09:00", restored.calendarTime)
        assertEquals(listOf(1, 5), restored.calendarDays)
    }
}
