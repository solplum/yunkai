package com.wangzan101.mika.cap.phone.shell

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.setup.BuiltinShellManager
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.wangzan101.mika.shell.IMikaShell

/**
 * 真机测试:内置助手经无线调试起来、交接 Binder、在自己的虚拟屏上注入点击,解绑后助手留着、再绑定直接可用。
 * 前提:安卓 13+,无线调试开着。配对二选一:
 * - 带 `-e pairingCode <6 位码>`(可加 `-e pairingPort <端口>`),此时系统的「使用配对码配对」窗口要开着;
 * - 已经用 Mika 的钥匙配对过:带 `-e assumePaired true`。
 */
@RunWith(AndroidJUnit4::class)
class BuiltinHelperDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val phone = PhoneServices.of(context)
    private val engine = phone.shellManager.engine

    private fun helperUp(): IMikaShell = runBlocking {
        when {
            args.getString("pairingCode") != null && engine.status.value != BuiltinShellStatus.Running ->
                engine.startPairing(args.getString("pairingCode")!!, args.getString("pairingPort")?.toIntOrNull())
            else -> {
                if (args.getString("assumePaired") == "true") {
                    context.getSharedPreferences("builtin_shell", Context.MODE_PRIVATE).edit().putBoolean("paired", true).commit()
                }
                engine.retry()
                assumeTrue("not paired: pass -e pairingCode <code> or -e assumePaired true", engine.status.value != BuiltinShellStatus.NotPaired)
            }
        }
        assertEquals(BuiltinShellStatus.Running, engine.status.value)
        withTimeout(5_000) { phone.hub.shell.filterNotNull().first() }
    }

    private fun poll(timeoutMs: Long, check: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (check()) return true
            Thread.sleep(100)
        }
        return check()
    }

    @Test
    fun helperRunsAsShellDrivesItsDisplayAndSurvivesUnbind() {
        assumeTrue(Build.VERSION.SDK_INT >= BuiltinShellManager.MIN_SDK)
        val shell = helperUp()
        val ids = shell.identity()
        assertEquals("helper uid", 2000, ids[0])
        assertTrue("helper pid", ids[1] > 0 && ids[1] != android.os.Process.myPid())

        val conn = BuiltinShellConnection(phone.hub) { engine.need() }
        val main = InstrumentationRegistry.getInstrumentation()
        main.runOnMainSync { conn.bind() }
        val bound = runBlocking { withTimeout(5_000) { conn.shell.filterNotNull().first() } }
        assertSame(shell, bound)

        val reader = ImageReader.newInstance(720, 1280, PixelFormat.RGBA_8888, 2)
        val dm = context.getSystemService(DisplayManager::class.java)
        try {
            val displayId = bound.createDisplay(reader.surface, 720, 1280, 320)
            assertTrue("createDisplay -> $displayId", displayId > 0)
            assertNotNull("display $displayId not visible", dm.getDisplay(displayId))
            assertTrue("injectTap", bound.injectTap(displayId, 100, 100))

            // 解绑:屏被撤掉,和服务结束一样;助手进程和它的 Binder 还在
            main.runOnMainSync { conn.unbind() }
            assertNull(conn.shell.value)
            assertTrue("display not released on unbind", poll(5_000) { dm.getDisplay(displayId) == null })
            assertTrue("helper died on unbind", shell.asBinder().pingBinder())
            assertSame(shell, phone.hub.shell.value)

            // 再绑定:直接拿到同一个助手,还能接着用
            main.runOnMainSync { conn.bind() }
            val again = runBlocking { withTimeout(5_000) { conn.shell.filterNotNull().first() } }
            assertSame(shell, again)
            val second = again.createDisplay(reader.surface, 720, 1280, 320)
            assertTrue("createDisplay after rebind -> $second", second > 0)
            assertTrue(again.injectTap(second, 50, 50))
        } finally {
            main.runOnMainSync { conn.unbind() }
            reader.close()
        }
    }
}
