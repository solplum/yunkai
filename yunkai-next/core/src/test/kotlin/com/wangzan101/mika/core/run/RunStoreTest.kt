package com.wangzan101.mika.core.run

import org.junit.Assert.assertEquals
import org.junit.Test

class RunStoreTest {
    @Test fun lateUserTextIsHandedOffExactlyOnce() {
        val inbox = RunInbox()
        kotlinx.coroutines.runBlocking { inbox.send(RunInput.Steer("m1", "原话")) }
        assertEquals(listOf(RunInbox.UserSteer("m1", "原话")), inbox.handoffUnconsumed())
        assertEquals(emptyList<RunInbox.UserSteer>(), inbox.handoffUnconsumed())
    }

    @Test fun recoveryMarksOnlyUnfinishedRunsAndNeverRestartsThem() {
        val store = MemoryRunStore()
        val running = StoredRun(RunId("a"), "thread", "send a note", RunPhase.Running, roundInProgress = true)
        val completed = StoredRun(RunId("b"), "thread", "read a file", RunPhase.Ended, reply = "done")
        store.save(running)
        store.save(completed)

        assertEquals(listOf(running), store.interruptUnfinished())
        assertEquals(RunPhase.Interrupted, store.get(running.id)?.phase)
        assertEquals(completed, store.get(completed.id))
        assertEquals(emptyList<StoredRun>(), store.interruptUnfinished())
    }
}
