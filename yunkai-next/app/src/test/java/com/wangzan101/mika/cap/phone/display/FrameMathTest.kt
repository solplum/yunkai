package com.wangzan101.mika.cap.phone.display

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameMathTest {
    private val w = 360
    private val h = 800
    private val black = 0xff000000.toInt()
    private val white = 0xffffffff.toInt()

    @Test fun allBlackIsBlack() {
        assertTrue(FrameMath.isBlack(w, h) { _, _ -> black })
        assertTrue(FrameMath.isBlack(w, h) { _, _ -> 0xff070707.toInt() })
    }

    @Test fun darkThemeWithTextIsNotBlack() {
        // 黑底上有一行白字
        assertFalse(FrameMath.isBlack(w, h) { _, y -> if (y in 300..340) white else black })
        assertFalse(FrameMath.isBlack(w, h) { _, _ -> 0xff202020.toInt() })
    }

    @Test fun pageChangeDiffers() {
        val a = FrameMath.lumaGrid(w, h) { _, _ -> white }
        val b = FrameMath.lumaGrid(w, h) { _, y -> if (y < h / 2) black else white }
        assertTrue(FrameMath.differs(a, b))
    }

    @Test fun cursorBlinkDoesNotDiffer() {
        val a = FrameMath.lumaGrid(w, h) { _, _ -> white }
        // 2 像素宽的光标
        val b = FrameMath.lumaGrid(w, h) { x, y -> if (x in 100..101 && y in 200..240) black else white }
        assertFalse(FrameMath.differs(a, b))
        assertFalse(FrameMath.differs(a, null))
    }
}
