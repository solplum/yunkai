package com.wangzan101.mika.cap.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.cap.sandbox.CommandResult
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** AndroidSandbox 在真机上跑命令;根文件系统按冒烟测试的方式准备在 files/sandbox-test/rootfs,不碰装好的沙箱。 */
@RunWith(AndroidJUnit4::class)
class AndroidSandboxDeviceTest {
    // 测试自己解的根文件系统,跟真正装好的沙箱分开放
    private val sb = AndroidSandbox(SandboxSmokeTest.testDirs, { SandboxStatus.Ready(network = false) }, { AndroidSandbox.dns(context) }, bridges = true)

    private fun sh(command: String, ws: String = "dev-a", timeoutMs: Long = 30_000): CommandResult =
        runBlocking { sb.run(ws, listOf("/bin/sh", "-lc", command), null, timeoutMs) {} }

    @Test fun reportsReady() {
        assertEquals(SandboxStatus.Ready(network = false), sb.status())
    }

    @Test fun echoRuns() {
        val r = sh("echo hi")
        assertEquals(r.output, 0, r.exitCode)
        assertEquals("hi\n", r.output)
        assertFalse(r.network)
    }

    @Test fun exitCodeAndOutput() {
        val r = sh("echo out; echo err >&2; pwd; exit 3")
        assertEquals(r.output, 3, r.exitCode)
        assertEquals(listOf("out", "err", "/workspace"), r.output.trim().lines())
    }

    @Test fun timeoutKillsAndReports() {
        val begin = System.currentTimeMillis()
        val r = sh("sleep 30", timeoutMs = 2_000)
        assertTrue(r.output, r.timedOut)
        assertEquals(124, r.exitCode)
        assertTrue("took ${System.currentTimeMillis() - begin} ms", System.currentTimeMillis() - begin < 8_000)
    }

    @Test fun endlessOutputIsBounded() {
        val rt = Runtime.getRuntime()
        System.gc()
        val before = rt.totalMemory() - rt.freeMemory()
        val r = sh("yes", timeoutMs = 3_000)
        val peak = rt.totalMemory() - rt.freeMemory()
        assertTrue(r.timedOut)
        assertTrue("dropped=${r.droppedBytes}", r.droppedBytes > 0)
        assertTrue(r.output.length <= ProcRunner.OUTPUT_LIMIT)
        assertTrue("heap grew ${(peak - before) shr 20} MB", peak - before < 8L shl 20)
    }

    @Test fun workspacesAreSeparate() {
        assertEquals(0, sh("echo a > only-a.txt", ws = "dev-sep-a").exitCode)
        assertEquals(0, sh("test -e only-a.txt", ws = "dev-sep-a").exitCode)
        assertTrue(sh("test -e only-a.txt", ws = "dev-sep-b").exitCode != 0)
    }

    @Test fun symlinkOutOfWorkspaceRejected() {
        val r = sh("rm -f x p; ln -s /proc/self/root/data x; ln -s /data/data/${context.packageName}/shared_prefs p; ls -la x p")
        assertEquals(r.output, 0, r.exitCode)
        assertNull(sb.hostPath("dev-a", "x", forWrite = false))
        assertNull(sb.hostPath("dev-a", "x/data", forWrite = false))
        assertNull(sb.hostPath("dev-a", "p", forWrite = true))
    }

    @Test fun cannotSeeSdcard() {
        val r = sh("ls /sdcard /storage/emulated/0 2>&1")
        assertTrue(r.output, r.exitCode != 0 || r.output.isBlank())
        // 经 /proc/self/root 绕到宿主的根目录也看不到
        val viaProc = sh("ls /proc/self/root/sdcard /proc/self/root/storage/emulated/0 2>&1")
        assertTrue(viaProc.output, viaProc.exitCode != 0 || viaProc.output.isBlank())
    }

    /**
     * RELEASE-GATING:断网时沙箱里够不到宿主的套接字(dnsproxyd 能替沙箱解析域名,等于开了一条出网的路)。
     * 每一条都必须失败;在真机上跑通之前不得发布沙箱。
     */
    @Test fun cannotReachHostSocketsWhenNetOff() {
        assertTrue(sh("ls /proc/self/root/dev/socket 2>&1").let { it.exitCode != 0 })
        val connect = sh(
            "if command -v python3 >/dev/null; then " +
                "python3 -c \"import socket; s = socket.socket(socket.AF_UNIX); s.connect('/proc/self/root/dev/socket/dnsproxyd')\"; " +
                "else echo x | nc -U /proc/self/root/dev/socket/dnsproxyd; fi 2>&1",
        )
        assertTrue(connect.output, connect.exitCode != 0)
        assertTrue(sh("ls /proc/1/root 2>&1").let { it.exitCode != 0 })
        val dns = sh("nslookup example.com 2>&1", timeoutMs = 20_000)
        assertTrue(dns.output, dns.exitCode != 0)
        assertFalse(dns.network)
    }

    /**
     * RELEASE-GATING:经 /proc/self/root、/proc/1/root 到不了宿主,宿主的 App 数据目录和本 App 的 shared_prefs 都读不到。
     * `readlink /proc/self/root` 显示的是宿主上根文件系统的路径(PRoot 不翻译这个链接):只是一串路径,
     * 顺着它走还在沙箱里,同样到不了。
     */
    @Test fun procRootStaysInGuest() {
        val pkg = context.packageName
        for (p in listOf(
            "/proc/self/root/data/data",
            "/proc/self/root/data/data/$pkg/shared_prefs",
            "/proc/self/root/data/user/0/$pkg/shared_prefs",
            "\"$(readlink /proc/self/root)/../../shared_prefs\"",
            "/proc/1/root",
        )) {
            val r = sh("ls $p 2>&1")
            assertTrue("$p: ${r.output}", r.exitCode != 0)
            assertTrue("$p: ${r.output}", r.output.contains("No such file or directory") || r.output.contains("Permission denied"))
        }
    }

    @Test fun fullOutputIsSavedInWorkspace() {
        val path = sb.saveOutput("dev-a", "完整输出")
        assertEquals("完整输出", sh("cat $path").output)
    }

    companion object {
        private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

        @BeforeClass
        @JvmStatic
        fun prepareRootfs() {
            SandboxSmokeTest.prepareTestSandbox()
        }
    }
}
