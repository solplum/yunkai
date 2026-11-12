// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.cap.sandbox

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.core.cap.sandbox.CommandResult
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Collections
import java.util.UUID

/**
 * 桥的真机用例:沙箱里的 `mika` 命令经抽象套接字回调 Mika。用装好的沙箱(python3 与 mika 客户端都是安装时放的);
 * 没装好就跳过。
 */
@RunWith(AndroidJUnit4::class)
class BridgeDeviceTest {
    private val shared = Collections.synchronizedList(mutableListOf<SharedFile>())
    private val sb = AndroidSandbox(context, SandboxState.get(context)) { shared += it }
    private val progress = Collections.synchronizedList(mutableListOf<String>())

    private fun sh(command: String): CommandResult =
        runBlocking { sb.run("bridge-ws", listOf("/bin/sh", "-lc", command), null, 30_000) { progress += it } }

    // 用装好的沙箱(装的时候装了 python3 和 mika 客户端);没装好就跳过,测试不自己解根文件系统
    @Before fun needsInstalledSandbox() {
        assumeTrue("sandbox not installed", SandboxState.get(context).status.value is SandboxStatus.Ready)
        assumeTrue("python3 not installed in the rootfs",
            Files.exists(File(SandboxDirs(context).rootfs, "usr/bin/python3").toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    // 导出到「下载/Mika」的测试文件删掉,不留在用户的下载目录里
    @After fun deleteExported() {
        for (f in shared) runCatching { context.contentResolver.delete(Uri.parse(f.uri), null, null) }
        SandboxDirs(context).workspace("bridge-ws").deleteRecursively()
    }

    @Test fun progressReachesCallback() {
        val r = sh("mika progress 第1步")
        assertEquals(r.output, 0, r.exitCode)
        assertEquals(listOf("第1步"), progress.toList())
    }

    @Test fun shareCallsExporter() {
        // 名字带随机串,下载目录里不会撞名;工作区外的文件先被路径规则拒掉
        val name = "mika-bridge-test-${UUID.randomUUID().toString().take(8)}.txt"
        val r = sh("echo hi > $name; mika share $name; echo rc=$?; mika share /etc/passwd; echo rc=$?")
        assertTrue(r.output, r.output.contains("\"savedAs\":\"Download/Yunkai/$name\""))
        assertTrue(r.output, r.output.contains("rc=0"))
        assertTrue(r.output, r.output.contains("path_outside_workspace"))
        assertEquals(r.output, 1, Regex("rc=1").findAll(r.output).count())
        assertEquals(listOf("Download/Yunkai/$name"), shared.map { it.savedAs })
    }

    @Test fun bridgeWorksWithNetOff() {
        val r = sh("mika info")
        assertEquals(r.output, 0, r.exitCode)
        assertTrue(r.output, r.output.contains("\"network\":false"))
        assertTrue(r.output, r.output.contains("\"workspace\":\"/workspace\""))
    }

    @Test fun sessionClosedAfterCommand() {
        val name = sh("echo \$MIKA_BRIDGE").output.trim()
        assertTrue(name, name.startsWith("mika-bridge-"))
        val refused = try {
            LocalSocket().use { it.connect(LocalSocketAddress(name)) }
            false
        } catch (e: IOException) {
            true
        }
        assertTrue("old bridge name still accepts connections", refused)
    }

    @Test fun wrongTokenIsRejected() {
        val r = sh("MIKA_TOKEN=forged mika info")
        assertEquals(r.output, 1, r.exitCode)
        assertTrue(r.output, r.output.contains("bad_token"))
    }

    companion object {
        private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    }
}
