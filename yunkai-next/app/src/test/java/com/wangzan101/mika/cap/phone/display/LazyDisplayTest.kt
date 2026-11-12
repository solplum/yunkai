package com.wangzan101.mika.cap.phone.display

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LazyDisplayTest {
    private class Screen

    @Test fun notCreatedUntilAsked() {
        var made = 0
        val d = LazyDisplay(720, 1600) { made++; Screen() }
        assertNull(d.current)
        assertEquals(0, made)
        assertEquals(720, d.width)
        assertEquals(1600, d.height)
    }

    @Test fun createdOnceOnFirstGet() = runTest {
        var made = 0
        val d = LazyDisplay(720, 1600) { made++; delay(10); Screen() }
        val got = (1..5).map { async { d.get() } }.awaitAll()
        assertEquals(1, made)
        assertTrue(got.all { it === got[0] })
        assertSame(got[0], d.current)
        assertSame(got[0], d.get())
        assertEquals(1, made)
    }

    @Test fun failedCreationIsRetried() = runTest {
        var tries = 0
        val d = LazyDisplay(720, 1600) { if (++tries == 1) throw IllegalStateException("createDisplay failed"); Screen() }
        runCatching { d.get() }
        assertNull(d.current)
        d.get()
        assertEquals(2, tries)
    }
}
