package com.wangzan101.mika.cap.phone.shell

import com.wangzan101.mika.cap.phone.shell.MainScreenLog.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenLogTest {
    private fun t(id: Int, display: Int, pkg: String = "p$id", top: String = "A", user: Int = 0, standard: Boolean = true) =
        Task(id, display, pkg, user, top, standard)

    @Test fun baselineScanRecordsNothing() {
        val log = MainScreenLog()
        log.scan(listOf(t(1, 0)), now = 100, record = false)
        log.scan(listOf(t(2, 0), t(1, 0)), now = 200, record = false)
        assertTrue(log.since(0).isEmpty())
        assertEquals(2, log.topTaskId)
    }

    @Test fun newTopTaskIsRecordedWithWhereItWasBefore() {
        val log = MainScreenLog()
        log.scan(listOf(t(1, 0), t(5, 0), t(9, 4)), now = 100, record = false)
        log.scan(listOf(t(2, 0, user = 10), t(1, 0), t(5, 0), t(9, 4)), now = 200, record = true)
        log.scan(listOf(t(5, 0), t(2, 0), t(1, 0), t(9, 4)), now = 300, record = true)
        log.scan(listOf(t(9, 0), t(5, 0), t(2, 0), t(1, 0)), now = 400, record = true)
        val a = log.since(0)
        assertEquals(listOf(2, 5, 9), a.map { it.taskId })
        assertEquals(listOf(MainScreenLog.NEW, 0, 4), a.map { it.from })
        assertEquals(10, a[0].userId)
        assertEquals(listOf(200L, 300L, 400L), a.map { it.at })
        assertEquals(0, log.displayOf(9))
        assertEquals(-1, log.displayOf(42))
    }

    @Test fun newPageInTheSameTopTaskIsRecordedAsSameTask() {
        val log = MainScreenLog()
        log.scan(listOf(t(1, 0, top = "A")), now = 100, record = false)
        log.scan(listOf(t(1, 0, top = "B")), now = 200, record = true)
        log.scan(listOf(t(1, 0, top = "B")), now = 300, record = true)
        val a = log.since(0)
        assertEquals(1, a.size)
        assertTrue(a[0].sameTask)
        assertEquals(0, a[0].from)
    }

    @Test fun otherDisplaysDoNotCountAndOldRecordsArePruned() {
        val log = MainScreenLog(keepMs = 1_000)
        log.scan(listOf(t(1, 0), t(9, 4)), now = 100, record = false)
        log.scan(listOf(t(8, 4), t(1, 0), t(9, 4)), now = 200, record = true)
        assertTrue(log.since(0).isEmpty())
        log.scan(listOf(t(2, 0, standard = false), t(1, 0)), now = 300, record = true)
        assertEquals(listOf(false), log.since(0).map { it.standard })
        log.scan(listOf(t(2, 0, standard = false), t(1, 0)), now = 5_000, record = true)
        assertTrue(log.since(0).isEmpty())
        assertTrue(log.since(301).isEmpty())
    }
}
