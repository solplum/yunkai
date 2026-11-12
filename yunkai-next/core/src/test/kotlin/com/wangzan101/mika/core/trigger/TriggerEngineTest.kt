package com.wangzan101.mika.core.trigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TriggerEngineTest {
    @Test fun idleOnlyDueWindowWaitsForWatchdogWithoutBeingClaimed() {
        val store = MemoryTriggerStore()
        store.upsert(TriggerDefinition("idle", "t", "do", 1_000, idleOnly = true))
        store.upsert(TriggerDefinition("normal", "t", "do", 1_000))
        var idle = false
        var armed: Long? = null
        val sent = mutableListOf<String>()
        val engine = TriggerEngine(store, { armed = it }, isIdle = { idle }, idleRetryMs = 500) { t, _ -> sent += t.id }
        engine.fire(1_000)
        assertEquals(listOf("normal"), sent)
        assertEquals(1_500L, armed)
        assertEquals(null, store.windows().firstOrNull { it.triggerId == "idle" })
        idle = true
        engine.fire(1_500)
        assertEquals(listOf("normal", "idle"), sent)
        assertEquals(1_000L, store.windows().first { it.triggerId == "idle" }.dueAtMs)
    }
    @Test fun claimsAndAdvancesBeforeDispatchAndCoalescesMissedIntervals() {
        val store = MemoryTriggerStore()
        store.upsert(TriggerDefinition("a", "t", "do", 1_000, intervalMs = 1_000))
        val seen = mutableListOf<TriggerWindow>()
        var armed: Long? = null
        val engine = TriggerEngine(store, { armed = it }) { trigger, window ->
            assertEquals("a", trigger.id)
            assertEquals(5_000L, store.get("a")!!.nextAtMs)
            assertEquals(TriggerWindowState.Claimed, store.windows().single().state)
            seen += window
        }
        engine.fire(4_100)
        assertEquals(1, seen.size)
        assertEquals(3L, seen.single().missedIntervals)
        assertEquals(64_100L, armed)
        engine.fire(4_100)
        assertEquals(1, seen.size)
    }

    @Test fun oneShotRetiresOnlyAfterConfirmedDelivery() {
        val store = MemoryTriggerStore()
        store.upsert(TriggerDefinition("once", "t", "do", 100))
        TriggerEngine(store, {}) { _, _ -> }.fire(100)
        assertFalse(store.get("once")!!.enabled)
        store.retireDeliveredOneShots()
        assertTrue(store.get("once") != null)
        store.mark("once:100", TriggerWindowState.Delivered)
        store.retireDeliveredOneShots()
        assertEquals(null, store.get("once"))
    }

    @Test fun restartMarksUncertainClaimAndRearmsOverdueWindowOnce() {
        val store = MemoryTriggerStore()
        store.upsert(TriggerDefinition("a", "t", "do", 500))
        store.claimDue(500)
        var armed: Long? = null
        TriggerEngine(store, { armed = it }) { _, _ -> error("must not dispatch") }.rearm(1_000)
        assertEquals(TriggerWindowState.Unknown, store.windows().single().state)
        assertEquals(null, armed)
        assertTrue(store.claimDue(1_000).isEmpty())
    }

    @Test fun dueWindowDoesNotOverlapUnfinishedPriorWindow() {
        val store = MemoryTriggerStore()
        store.upsert(TriggerDefinition("watch", "thread", "check", 1_000, 1_000))
        val engine = TriggerEngine(store, {}) { _, _ -> }
        engine.fire(1_000)
        engine.fire(2_000)
        assertEquals(1, store.windows().size)
        store.mark("watch:1000", TriggerWindowState.Delivered)
        engine.fire(2_000)
        assertEquals(2, store.windows().size)
    }

    @Test fun manualRunKeepsScheduledTime() {
        val store = MemoryTriggerStore()
        store.upsert(TriggerDefinition("watch", "thread", "check", 10_000, intervalMs = 5_000))
        val manual = store.claimNow("watch", 2_000)
        assertEquals("watch:2000", manual?.key)
        assertEquals(10_000L, store.get("watch")!!.nextAtMs)
        assertEquals(null, store.claimNow("watch", 2_001))
        store.mark(manual!!.key, TriggerWindowState.Delivered)
        store.retireDeliveredOneShots()
        assertEquals(10_000L, store.get("watch")!!.nextAtMs)
    }

    @Test fun calendarRepeatKeepsLocalClockAcrossDaylightSavingChange() {
        val before = Instant.parse("2026-03-07T13:00:00Z").toEpochMilli()
        val first = nextCalendarAt("09:00", "America/New_York", emptyList(), before)
        val second = nextCalendarAt("09:00", "America/New_York", emptyList(), first)
        assertEquals("2026-03-07T14:00:00Z", Instant.ofEpochMilli(first).toString())
        assertEquals("2026-03-08T13:00:00Z", Instant.ofEpochMilli(second).toString())
    }
}
