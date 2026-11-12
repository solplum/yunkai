package com.wangzan101.mika.core.uistate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenDiagnosisTest {
    private fun facts(
        hasWindow: Boolean = true, readable: Int = 10, changing: Boolean = false, black: List<Boolean> = listOf(false, false),
        texts: List<String> = listOf("设置", "WLAN"), password: Boolean = false,
    ) = ScreenFacts("com.x", hasWindow, readable, changing, black, texts, password)

    @Test fun normalScreen() {
        val c = ScreenDiagnosis.classify(facts())
        assertEquals(ScreenState.NORMAL, c.state); assertFalse(c.needsHuman)
    }

    @Test fun blankWithoutWindowOrElements() {
        assertEquals(ScreenState.BLANK, ScreenDiagnosis.classify(facts(hasWindow = false, readable = 0, black = emptyList())).state)
        val c = ScreenDiagnosis.classify(facts(readable = 0, texts = emptyList()))
        assertEquals(ScreenState.BLANK, c.state); assertFalse(c.needsHuman)
    }

    @Test fun unreadableWindowIsNotNoWindow() {
        val none = ScreenFacts(null, false, 0, false, emptyList(), emptyList(), false, anyWindow = false)
        assertEquals(ScreenDiagnosis.NO_WINDOW, ScreenDiagnosis.classify(none).cause)
        assertEquals(ScreenDiagnosis.UNREADABLE_WINDOW, ScreenDiagnosis.classify(none.copy(anyWindow = true)).cause)
    }

    @Test fun blackFrameWithWindowIsProtected() {
        val c = ScreenDiagnosis.classify(facts(black = listOf(true, true)))
        assertEquals(ScreenState.PROTECTED, c.state); assertTrue(c.needsHuman); assertEquals("other", c.takeoverReason)
        assertEquals("payment", ScreenDiagnosis.classify(facts(black = listOf(true, true), texts = listOf("确认支付"))).takeoverReason)
        assertEquals("captcha", ScreenDiagnosis.classify(facts(black = listOf(true, true), texts = listOf("安全验证"))).takeoverReason)
        assertEquals("login", ScreenDiagnosis.classify(facts(black = listOf(true, true), password = true)).takeoverReason)
        // 没有窗口时的黑屏只是空白
        assertEquals(ScreenState.BLANK, ScreenDiagnosis.classify(facts(hasWindow = false, readable = 0, black = listOf(true, true))).state)
    }

    @Test fun oneBlackFrameIsNotProtected() {
        assertEquals(ScreenState.NORMAL, ScreenDiagnosis.classify(facts(black = listOf(true))).state)
        assertEquals(ScreenState.NORMAL, ScreenDiagnosis.classify(facts(black = listOf(false, true))).state)
    }

    @Test fun blackWhileChangingIsLoading() {
        val c = ScreenDiagnosis.classify(facts(changing = true, black = listOf(true, true)))
        assertEquals(ScreenState.LOADING, c.state); assertFalse(c.needsHuman)
    }

    @Test fun changingScreenIsLoading() {
        val c = ScreenDiagnosis.classify(facts(changing = true))
        assertEquals(ScreenState.LOADING, c.state); assertFalse(c.needsHuman)
    }

    @Test fun humanOnlyPages() {
        assertEquals("captcha", ScreenDiagnosis.classify(facts(texts = listOf("请拖动滑块完成安全验证"))).takeoverReason)
        assertEquals("payment", ScreenDiagnosis.classify(facts(texts = listOf("请输入支付密码"))).takeoverReason)
        assertEquals("login", ScreenDiagnosis.classify(facts(password = true)).takeoverReason)
        assertEquals("login", ScreenDiagnosis.classify(facts(texts = listOf("手机号", "获取验证码"))).takeoverReason)
        assertTrue(ScreenDiagnosis.classify(facts(texts = listOf("Verify you are human"))).needsHuman)
        // 普通设置页里的「密码」字样不算
        assertFalse(ScreenDiagnosis.classify(facts(texts = listOf("密码与安全", "隐私"))).needsHuman)
    }
}
