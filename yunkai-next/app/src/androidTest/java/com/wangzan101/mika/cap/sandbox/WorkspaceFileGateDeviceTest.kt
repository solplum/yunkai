package com.wangzan101.mika.cap.sandbox

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.workspace.FdSys
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.workspace.WorkspaceFileGateContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 按句柄的安卓实现跑同一套关口规则,另加只在真机上测得了的:FIFO 不阻塞、检查之后再换文件也碰不到外面。 */
@RunWith(AndroidJUnit4::class)
class WorkspaceFileGateDeviceTest : WorkspaceFileGateContract() {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    override val sys: FdSys = AndroidFdSys
    override fun tempDir(): File = File(context.filesDir, "gate-test-${System.nanoTime()}").apply { mkdirs() }

    @After fun cleanUp() { tmp.deleteRecursively() }

    @Test fun fifoRefusedWithoutBlocking() {
        Os.mkfifo(File(ws, "pipe").path, 0x180)
        val begin = System.currentTimeMillis()
        for (op in listOf<() -> Unit>({ gate.openRead(ws, "pipe").close() }, { gate.openWrite(ws, "pipe", append = false).close() })) {
            try { op(); fail("FIFO accepted") } catch (e: SandboxError) { assertEquals("not_a_file", e.code) }
        }
        assertTrue("took ${System.currentTimeMillis() - begin} ms", System.currentTimeMillis() - begin < 3_000)
    }

    /** 打开之后把目录换成指向外面的链接:句柄上的读写仍是原来那个文件,外面的文件不受影响。 */
    @Test fun swapAfterOpenDoesNotRedirect() {
        File(ws, "d").mkdirs(); File(ws, "d/a.txt").writeText("inside")
        gate.openRead(ws, "d/a.txt").use { g ->
            assertTrue(File(ws, "d").renameTo(File(ws, "d-old")))
            Os.symlink(secret.path, File(ws, "d").path)
            assertEquals("inside", String(g.read(0, 100)))
        }
        val w = gate.createNew(ws, "e/new.txt")
        File(ws, "e").renameTo(File(ws, "e-old")); Os.symlink(secret.path, File(ws, "e").path)
        w.use { it.write("x".toByteArray()) }
        assertFalse(File(secret, "new.txt").exists())
        assertEquals("x", File(ws, "e-old/new.txt").readText())
    }

    /** /proc/self/fd 读出的根目录与文件路径同出一源,/data/user/0 与 /data/data 的写法差别不影响判断。 */
    @Test fun realPathsComeFromTheHandle() {
        File(ws, "x.txt").writeText("1")
        gate.openRead(File(context.dataDir, ws.relativeTo(context.dataDir).path), "x.txt").use { assertEquals(1L, it.size) }
    }
}
