package com.wangzan101.mika.core.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunEventClipTest {
    @Test fun resultUnderCapIsKept() {
        val s = "x".repeat(RunEvent.MAX_FIELD * 5)
        assertEquals(s, RunEvent.clipResult(s))
    }

    @Test fun resultOverCapIsCutWithMarkerNamingOriginalLength() {
        val s = "x".repeat(RunEvent.MAX_RESULT + 10)
        val out = RunEvent.clipResult(s)
        assertTrue(out.startsWith("x".repeat(RunEvent.MAX_RESULT)))
        assertTrue(out, out.endsWith("…(已截短,原长 ${s.length} 字)"))
    }

    @Test fun plainFieldsStillClippedAtMaxField() {
        assertEquals(RunEvent.MAX_FIELD + "…(已截短)".length, RunEvent.clip("x".repeat(RunEvent.MAX_FIELD + 1)).length)
    }
}
