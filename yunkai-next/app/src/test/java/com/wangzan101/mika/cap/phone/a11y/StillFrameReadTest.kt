package com.wangzan101.mika.cap.phone.a11y

import com.wangzan101.mika.cap.phone.display.FrameMath
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StillFrameReadTest {
    private val cells = FrameMath.GRID_W * FrameMath.GRID_H
    private val pageA = IntArray(cells) { 200 }
    private val pageB = IntArray(cells) { 20 }
    /** 每 50 ms 都不一样的过渡帧(页面在滑入、键盘在升起)。 */
    private fun moving(t: Long) = IntArray(cells) { ((t / 50 * 37 + it * 11) % 256).toInt() }
    /** A 页上有一小块一直在动(动图头像):只动几格。 */
    private fun animatedA(t: Long) = pageA.copyOf().also { f -> for (i in 0 until 4) f[i] = if ((t / 100) % 2 == 0L) 0 else 255 }

    private class Clock { var now = 0L }

    @Test fun waitsForTheTransitionToEndBeforeReading() = runTest {
        val c = Clock()
        val frame = { if (c.now < 500) moving(c.now) else pageA }
        var readAt = -1L
        val r = StillFrameRead.read({ c.now }, { c.now += it }, frame) { readAt = c.now; "lines" }
        assertEquals("lines", r)
        assertTrue("read at $readAt", readAt >= 500)
    }

    @Test fun smallAnimationsDoNotDiscardTheText() = runTest {
        val c = Clock()
        // 头像一直在动:等到上限就认,认完只有那几格变了,照用
        val r = StillFrameRead.read({ c.now }, { c.now += it }, { animatedA(c.now) }) { c.now += 300; "lines" }
        assertEquals("lines", r)
        assertTrue(c.now <= StillFrameRead.STILL_CAP_MS + 400)
    }

    @Test fun pageSwitchDuringRecognitionReadsAgainOnTheNewPage() = runTest {
        val c = Clock()
        var reads = 0
        // 第一次认的时候换了页:丢掉,按新的一页再认
        val r = StillFrameRead.read({ c.now }, { c.now += it }, { if (reads == 0 || c.now < 1_000) pageA else pageB }) {
            reads++
            if (reads == 1) c.now = 1_000
            "read $reads"
        }
        assertEquals("read 2", r)
        // 一直在整页变:认两次都对不上,不给
        var n = 0
        assertNull(StillFrameRead.read({ c.now }, { c.now += it }, { if (n % 2 == 0) pageA else pageB }) { n++; "x" })
    }

    @Test fun noFrameMeansNothingToRead() = runTest {
        val c = Clock()
        assertNull(StillFrameRead.read<String>({ c.now }, { c.now += it }, { null }) { null })
        assertTrue(StillFrameRead.awaitStill({ c.now }, { c.now += it }, { null }))
        assertFalse(StillFrameRead.awaitStill({ c.now }, { c.now += it }, { moving(c.now) }))
    }

    @Test fun pageChangedNeedsAQuarterOfTheScreen() {
        val few = pageA.copyOf().also { for (i in 0 until 10) it[i] = 0 }
        assertTrue(FrameMath.differs(pageA, few))
        assertFalse(FrameMath.pageChanged(pageA, few))
        assertTrue(FrameMath.pageChanged(pageA, pageB))
        assertFalse(FrameMath.pageChanged(null, pageB))
    }
}
