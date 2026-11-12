// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
// Modified for Yunkai Next isolated prototype (2026-10-30); see repository-root UPSTREAM.md.
package com.wangzan101.mika.cap.phone.display

import android.hardware.display.DisplayManager
import android.os.ParcelFileDescriptor
import android.view.Display
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.shell.IMikaShell
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.shell.BuiltinShellConnection
import com.wangzan101.mika.cap.phone.shell.ShellService
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** 真机测试:需要内置助手在运行(已配对、无线调试开着)。 */
@RunWith(AndroidJUnit4::class)
class AgentDisplayTest {
    // 整个测试类共用一条连接,与 App 里长期持有一条连接的用法一致
    companion object {
        private const val SETTINGS = "com.android.settings"
        private val SHELL_PROCESS get() = "${InstrumentationRegistry.getInstrumentation().targetContext.packageName}:helper"
        private lateinit var connection: BuiltinShellConnection

        @BeforeClass
        @JvmStatic
        fun bindShell() {
            val phone = PhoneServices.of(InstrumentationRegistry.getInstrumentation().targetContext)
            connection = BuiltinShellConnection(phone.hub) { phone.shellManager.engine.need() }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { connection.bind() }
            awaitShell()
        }

        @AfterClass
        @JvmStatic
        fun unbindShell() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { connection.unbind() }
        }

        private fun awaitShell(): IMikaShell =
            DeviceSupport.awaitShell(connection)
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val shell: IMikaShell get() = awaitShell()
    private var display: AgentDisplay? = null

    @After
    fun tearDown() {
        display?.release()
    }

    private fun newDisplay() = AgentDisplay(shell, 720, 1600, 320).also { display = it }

    @Test
    fun createsTrustedPublicDisplay() {
        val d = newDisplay()
        val dm = context.getSystemService(DisplayManager::class.java)
        val shown = dm.getDisplay(d.displayId)
        assertNotNull("display ${d.displayId} not visible to the app", shown)
        assertEquals(0, shown.flags and Display.FLAG_PRIVATE)
        assertTrue("display is not trusted", shown.flags and displayFlagTrusted() != 0)
    }

    @Test
    fun startsSettingsOnDisplay() {
        val d = newDisplay()
        assertEquals(ShellService.START_OK, shell.startApp(d.displayId, SETTINGS))
        val task = pollUntil(5_000) { shell.topTaskId(d.displayId).takeIf { it != -1 } }
        assertNotNull("no task on display ${d.displayId}", task)
    }

    @Test
    fun startAppRefusesAppVisibleOnMainScreen() {
        val d = newDisplay()
        shellCommand("am start -W -a android.settings.SETTINGS --display 0")
        try {
            val mainTop = pollUntil(5_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it != -1 } }
            assertEquals(ShellService.START_IN_USE_ON_MAIN, shell.startApp(d.displayId, SETTINGS))
            Thread.sleep(1_000)
            assertEquals("main screen top task changed", mainTop, shell.topTaskId(Display.DEFAULT_DISPLAY))
            assertEquals(-1, shell.topTaskId(d.displayId))
        } finally {
            // 测试自己在主屏打开的设置:回桌面并结束它,免得收尾动画期间仍算「主屏可见」影响后面的用例
            shellCommand("input -d 0 keyevent KEYCODE_HOME")
            shellCommand("am force-stop $SETTINGS")
        }
    }

    @Test
    fun startAppReportsMissingPackage() {
        val d = newDisplay()
        assertEquals(ShellService.START_NOT_FOUND, shell.startApp(d.displayId, "com.wangzan101.no.such.app"))
    }

    @Test
    fun frameArrives() {
        val d = newDisplay()
        shell.startApp(d.displayId, SETTINGS)
        val png = pollUntil(2_000) { d.latestFramePng() }
        assertNotNull(png)
        // PNG 文件头
        assertEquals(0x89.toByte(), png!![0])
        assertEquals('P'.code.toByte(), png[1])
    }

    @Test
    fun framesFlowEmits() = runBlocking {
        val d = newDisplay()
        shell.startApp(d.displayId, SETTINGS)
        val frame = withTimeoutOrNull(2_000) { d.frames.filterNotNull().first() }
        assertNotNull("no preview frame within 2s", frame)
        assertTrue("preview width ${frame!!.width} > 360", frame.width <= 360)
    }

    @Test
    fun releaseClearsFrames() = runBlocking {
        val d = newDisplay()
        shell.startApp(d.displayId, SETTINGS)
        withTimeout(2_000) { d.frames.filterNotNull().first() }
        d.release()
        assertEquals(null, d.frames.value)
    }

    @Test
    fun movesTaskBetweenVirtualDisplays() {
        val d = newDisplay()
        shell.startApp(d.displayId, SETTINGS)
        val task = pollUntil(5_000) { shell.topTaskId(d.displayId).takeIf { it != -1 } }!!
        // 在两块虚拟屏之间挪,不碰用户的主屏
        val other = AgentDisplay(shell, 720, 1600, 320)
        try {
            assertTrue(shell.moveTask(task, other.displayId))
            assertEquals(task, pollUntil(3_000) { shell.topTaskId(other.displayId).takeIf { it == task } })
            assertNotEquals(task, shell.topTaskId(d.displayId))
        } finally {
            other.release()
        }
    }

    @Test
    fun coldStartDoesNotKillAppWithTaskOnAnotherScreen() {
        val d = newDisplay()
        // 用另一块虚拟屏代表「Mika 屏以外的任务」,不碰用户的主屏
        val other = AgentDisplay(shell, 720, 1600, 320)
        try {
            assertEquals(ShellService.START_OK, shell.startApp(other.displayId, SETTINGS))
            assertNotNull(pollUntil(5_000) { shell.topTaskId(other.displayId).takeIf { it != -1 } })
            val pid = pollUntil(3_000) { shellCommand("pidof $SETTINGS").trim().takeIf { it.isNotEmpty() } }
            assertNotNull(pid)
            // 另一块屏也是 Mika 的屏(代表另一个任务):直接拒绝、不强停,返回「被别的任务占用」
            assertEquals(ShellService.START_IN_USE_BY_OTHER, shell.restartApp(d.displayId, SETTINGS))
            assertEquals("settings process was killed", pid, shellCommand("pidof $SETTINGS").trim())
        } finally {
            other.release()
        }
    }

    @Test
    fun injectKeyRefusesKeysOutsideTheAllowlist() {
        val d = newDisplay()
        // 只用打到虚拟屏上也无害的键;电源、音量这类绝不在真机上试
        assertFalse(shell.injectKey(d.displayId, KeyEvent.KEYCODE_HOME))
        assertFalse(shell.injectKey(d.displayId, KeyEvent.KEYCODE_SEARCH))
    }

    @Test
    fun injectsInputOnDisplay() {
        val d = newDisplay()
        val mainTop = shell.topTaskId(Display.DEFAULT_DISPLAY)
        shell.startApp(d.displayId, SETTINGS)
        assertNotNull(pollUntil(5_000) { shell.topTaskId(d.displayId).takeIf { it != -1 } })
        val home = stableFrame(d)

        assertTrue(shell.injectTap(d.displayId, 360, 800))
        val afterTap = pollUntil(3_000) { d.latestFramePng()?.takeIf { !it.contentEquals(home) } }
        assertNotNull("tap had no visible effect on the virtual display", afterTap)
        val subPage = stableFrame(d)

        assertTrue(shell.injectKey(d.displayId, KeyEvent.KEYCODE_BACK))
        val afterBack = pollUntil(3_000) { d.latestFramePng()?.takeIf { !it.contentEquals(subPage) } }
        assertNotNull("BACK had no visible effect on the virtual display", afterBack)

        assertEquals("main screen top task changed", mainTop, shell.topTaskId(Display.DEFAULT_DISPLAY))
    }

    @Test
    fun mutesAndRestoresAudio() {
        val before = shellCommand("appops get $SETTINGS PLAY_AUDIO").trim()
        assertTrue(shell.setAudioMuted(SETTINGS, true))
        assertTrue(shellCommand("appops get $SETTINGS PLAY_AUDIO").contains("PLAY_AUDIO: ignore"))
        assertTrue(shell.setAudioMuted(SETTINGS, false))
        assertEquals(before, shellCommand("appops get $SETTINGS PLAY_AUDIO").trim())
    }

    @Test
    fun muteFailsForUnknownPackage() {
        assertFalse(shell.setAudioMuted("com.wangzan101.no.such.app", true))
    }

    /** 助手进程被杀后,看门狗重起助手,连接拿到可用的新服务。 */
    @Test
    fun reconnectsAfterShellProcessDies() {
        val before = shell
        val pid = shellPid()
        assertNotNull("no $SHELL_PROCESS process", pid)
        shellCommand("kill $pid")
        val after = pollUntil(30_000) {
            connection.shell.value?.takeIf { it.asBinder() != before.asBinder() && it.asBinder().pingBinder() }
        }
        assertNotNull("shell did not come back after its process died", after)
        assertNotEquals(pid, shellPid())
        val d = AgentDisplay(after!!, 720, 1600, 320).also { display = it }
        assertTrue(d.displayId >= 0)
    }

    private fun shellPid(): Int? = shellCommand("ps -A -o PID,NAME").lines()
        .map { it.trim().split(Regex("\\s+")) }
        .firstOrNull { it.size == 2 && it[1] == SHELL_PROCESS }
        ?.get(0)?.toIntOrNull()

    private fun shellCommand(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    /** 等画面静止(相隔 500ms 的两帧一样),返回该帧。 */
    private fun stableFrame(d: AgentDisplay): ByteArray {
        var last = pollUntil(2_000) { d.latestFramePng() }!!
        repeat(10) {
            Thread.sleep(500)
            val now = d.latestFramePng()!!
            if (now.contentEquals(last)) return now
            last = now
        }
        return last
    }

    // Display.FLAG_TRUSTED 是隐藏常量(1 shl 7),反射取不到时用其数值
    private fun displayFlagTrusted(): Int =
        runCatching { Display::class.java.getField("FLAG_TRUSTED").getInt(null) }.getOrDefault(1 shl 7)

    private fun <T : Any> pollUntil(timeoutMs: Long, block: () -> T?): T? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            block()?.let { return it }
            Thread.sleep(50)
        }
        return block()
    }
}
