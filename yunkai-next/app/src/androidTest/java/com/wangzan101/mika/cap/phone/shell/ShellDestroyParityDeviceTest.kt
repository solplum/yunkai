package com.wangzan101.mika.cap.phone.shell

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * destroy 单独一个类:它会结束外壳进程。只验收它自己能留下的痕迹——剪贴板还原、静音解除、虚拟屏释放、binder 失效;
 * 收尾时等连接重新拿到一个活的新外壳(进程死后的自动恢复),后面的测试类才不受影响。
 */
@RunWith(AndroidJUnit4::class)
class ShellDestroyParityDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var probe: ShellProbe
    private var screen: ParityScreen? = null
    private var dead: IBinder? = null
    private var audioBefore = "default"
    private var clipBefore: String? = null

    @Before
    fun setUp() {
        probe = ShellProbe(context)
        DeviceSupport.shell().restoreClip()
        audioBefore = ParityAudio.mode(SETTINGS)
        clipBefore = ParityClip.text(context)
    }

    @After
    fun tearDown() {
        runCatching { probe.close() }
        // 外壳已死时 releaseDisplay 会抛异常,这里只为关掉本地的 ImageReader
        runCatching { screen?.close() }
        ParityAudio.set(SETTINGS, audioBefore)
        ParityClip.put(context, clipBefore)
        val old = dead ?: return
        val fresh = pollUntil(RECOVER_MS) {
            runCatching { DeviceSupport.shell() }.getOrNull()?.takeIf { it.asBinder() != old }
        }
        assertNotNull("no live shell came back after destroy", fresh)
        ParityScreen(fresh!!, 720, 1600, 320).close()
    }

    @Test
    fun destroyUndoesEverythingAndEndsTheProcess() {
        val shell = DeviceSupport.shell()
        val s = ParityScreen(shell, 720, 1600, 320).also { screen = it }
        sh("am start --display ${s.id} -f 0x18000000 -n ${ShellProbe.COMPONENT}")
        assertNotNull(probe.await { it.kind == ShellProbe.RESUMED && it.display == s.id })
        val baseline = ParityAudio.mode(SETTINGS)
        assertEquals(true, shell.setAudioMuted(SETTINGS, true))
        assertEquals("ignore", ParityAudio.mode(SETTINGS))
        ParityClip.put(context, "parity-user")
        assertEquals(ShellService.CLIP_SET, shell.setClipText("parity-ours"))

        val binder = shell.asBinder().also { dead = it }
        // 进程在回复前就退出,调用方多半收到 DeadObjectException;正常返回也算
        runCatching { shell.destroy() }

        assertNotNull("binder still alive", pollUntil(5_000) { true.takeIf { !binder.isBinderAlive } })
        val dm = context.getSystemService(DisplayManager::class.java)
        assertNotNull("display ${s.id} not released", pollUntil(5_000) { true.takeIf { dm.getDisplay(s.id) == null } })
        assertEquals("audio not unmuted", baseline, ParityAudio.mode(SETTINGS))
        assertEquals("clipboard not restored", "parity-user", ParityClip.text(context))
    }

    private companion object {
        // 新后端由看门狗拉起,间隔比旧后端的立即重绑长
        const val RECOVER_MS = 60_000L
    }
}
