package com.wangzan101.mika.core.visual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 画面粗比较:合成的画面(像素函数),整屏与目标区域分别算变了多少。 */
class FrameDiffTest {
    private val w = 1080
    private val h = 2400
    private fun gray(v: Int) = (0xff shl 24) or (v shl 16) or (v shl 8) or v

    /** 白底,[boxes] 里的矩形(像素)涂成 [v]。 */
    private fun frame(v: Int = 0, vararg boxes: IntArray) = FrameGrid.sample(w, h) { x, y ->
        if (boxes.any { x in it[0] until it[2] && y in it[1] until it[3] }) gray(v) else gray(255)
    }!!

    @Test fun sameFrameHasNoChange() {
        val c = FrameDiff.compare(frame(), frame())!!
        assertEquals(0.0, c.whole, 0.0)
        assertFalse(c.visible); assertFalse(c.large)
    }

    @Test fun cursorSizedChangeIsNotVisible() {
        val c = FrameDiff.compare(frame(), frame(0, intArrayOf(500, 1000, 504, 1040)))!!
        assertFalse(c.visible)
    }

    @Test fun toggleInTheTargetRegionIsVisibleThereButSmallOverall() {
        val toggle = intArrayOf(880, 300, 1040, 400)
        val c = FrameDiff.compare(frame(), frame(0, toggle), roi = listOf(800, 110, 970, 180))!!
        assertTrue(c.whole < FrameTuning.FRAME_LARGE)
        assertTrue(c.region!! >= FrameTuning.REGION_VISIBLE)
        assertTrue(c.visible)
    }

    @Test fun newPageIsLarge() {
        val c = FrameDiff.compare(frame(), frame(30, intArrayOf(0, 0, w, h * 2 / 3)))!!
        assertTrue(c.large)
    }

    @Test fun mismatchedOrMissingFramesAreUnknown() {
        assertNull(FrameDiff.compare(null, frame()))
        assertNull(FrameDiff.compare(frame(), FrameGrid(2, 2, IntArray(4))))
        assertNull(FrameGrid.sample(10, 10) { _, _ -> 0 })
        assertNotNull(FrameDiff.compare(frame(), frame(), roi = listOf(0, 0, 999, 999))!!.region)
    }
}
