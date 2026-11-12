package com.wangzan101.mika.cap.phone.a11y

import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** 没有内置助手/无障碍时:不用手机的步骤照常,用到手机才报 phone_unavailable(带怎么弄好),之后还能再试。 */
class LazyPhoneScreenTest {
    private var attempts = 0
    private var available = false
    private val opened = mutableListOf<String>()

    private val real: ScreenPort = Proxy.newProxyInstance(ScreenPort::class.java.classLoader, arrayOf(ScreenPort::class.java)) { _, m, args ->
        if (m.name == "openApp") { opened += args[0] as String; ActionResult.Ok() } else throw UnsupportedOperationException(m.name)
    } as ScreenPort

    private val screen = LazyPhoneScreen(1080, 2400, listApps = { listOf("a.b" to "A") }) {
        attempts++
        if (!available) throw PhoneUnavailable("手机操作助手没在运行")
        real
    }

    @Test fun readsAndAppListWorkWithoutConnecting() = runTest {
        assertFalse(screen.snapshot().hasWindow)
        assertEquals(listOf("a.b" to "A"), screen.listApps())
        assertEquals(ActionResult.Failed("no_app_open"), screen.tapXY(1, 1))
        assertEquals(0, attempts)
        assertFalse(screen.acquired)
    }

    @Test fun launchingAnAppConnectsOrSaysWhy() = runTest {
        assertEquals(ActionResult.Failed(LazyPhoneScreen.PHONE_UNAVAILABLE, "手机操作助手没在运行"), screen.openApp("a.b"))
        assertFalse(screen.acquired)
        available = true
        assertTrue(screen.openApp("a.b") is ActionResult.Ok)
        assertTrue(screen.acquired)
        assertEquals(listOf("a.b"), opened)
        assertEquals(2, attempts)
    }
}
