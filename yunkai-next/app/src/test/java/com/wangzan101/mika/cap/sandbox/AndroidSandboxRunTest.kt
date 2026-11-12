// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.core.workspace.SandboxTree
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
class AndroidSandboxRunTest {
    private val tmp: File = Files.createTempDirectory("android-sandbox-run").toFile()
    private val sb = AndroidSandbox(SandboxDirs(File(tmp, "files/sandbox"), File(tmp, "cache"), File(tmp, "lib"), JVM_GATE, SandboxTree()), { SandboxStatus.Ready(false) }, { emptyList() })

    @Test fun cwdOutsideWorkspaceRejected() = runBlocking {
        for (cwd in listOf("../..", "/etc", "/opt/mika/skills/x", "a/../../b")) {
            try {
                sb.run("c1", listOf("true"), cwd, 1_000) {}
                fail("cwd $cwd was accepted")
            } catch (e: SandboxError) {
                assertEquals(cwd, "path_outside_workspace", e.code)
            }
        }
    }

    /** 技能目录还没同步也先建好再绑:源目录缺了 PRoot 会往命令输出里写警告。 */
    @Test fun bindSourcesExistBeforeLaunch() = runBlocking {
        val dirs = SandboxDirs(File(tmp, "s3/files/sandbox"), File(tmp, "s3/cache"), File(tmp, "s3/lib"), JVM_GATE, SandboxTree())
        val sb = AndroidSandbox(dirs, { SandboxStatus.Ready(false) }, { emptyList() })
        assertFalse(dirs.skills.exists())
        runCatching { sb.run("c1", listOf("true"), null, 1_000) {} } // JVM 上没有启动器,启动本身会失败
        assertTrue(dirs.skills.isDirectory)
        assertTrue(dirs.workspace("c1").isDirectory)
        assertTrue(dirs.tmp.isDirectory)
    }

    @Test fun existingSandboxGetsUpdatedClientScript() = runBlocking {
        val dirs = SandboxDirs(File(tmp, "client/files/sandbox"), File(tmp, "client/cache"), File(tmp, "client/lib"), JVM_GATE, SandboxTree())
        val client = File(dirs.rootfs, "usr/local/bin/mika")
        client.parentFile!!.mkdirs()
        client.writeText("old bridge")
        val sb = AndroidSandbox(dirs, { SandboxStatus.Ready(false) }, { emptyList() },
            clientScript = { "new bridge".toByteArray() })
        runCatching { sb.run("c1", listOf("true"), null, 1_000) {} } // JVM 上不启动 PRoot
        assertEquals("new bridge", client.readText())
        assertTrue(client.canExecute())
    }

    /** 工具调用时运行环境还没好:开始准备并等它;准备完成就就绪,超时返回当时的状态,不支持不等。 */
    @Test fun awaitReadyStartsPreparationAndWaits() = runBlocking {
        var status: SandboxStatus = SandboxStatus.NotInstalled
        var starts = 0
        val dirs = SandboxDirs(File(tmp, "s4/files/sandbox"), File(tmp, "s4/cache"), File(tmp, "s4/lib"), JVM_GATE, SandboxTree())
        val sb = AndroidSandbox(dirs, { status }, { emptyList() }, startPreparing = {
            starts++
            kotlinx.coroutines.GlobalScope.launch { kotlinx.coroutines.delay(50); status = SandboxStatus.Ready(false) }
        })
        assertEquals(SandboxStatus.Ready(false), sb.awaitReady(5_000))
        assertEquals(1, starts)
        assertEquals(SandboxStatus.Ready(false), sb.awaitReady(5_000))
        assertEquals(1, starts) // 已就绪不再准备

        status = SandboxStatus.Installing(10, "解包")
        val slow = AndroidSandbox(dirs, { status }, { emptyList() }, startPreparing = { kotlinx.coroutines.GlobalScope.launch { kotlinx.coroutines.delay(60_000) } })
        assertEquals(SandboxStatus.Installing(10, "解包"), slow.awaitReady(100))

        status = SandboxStatus.Unsupported("x86")
        assertEquals(SandboxStatus.Unsupported("x86"), AndroidSandbox(dirs, { status }, { emptyList() }, startPreparing = { fail("must not prepare"); null }).awaitReady(100))
        status = SandboxStatus.Broken("坏了")
        assertEquals(SandboxStatus.Broken("坏了"), AndroidSandbox(dirs, { status }, { emptyList() }).awaitReady(100))
    }

    @Test fun preparerNeedsWork() {
        assertTrue(RuntimePreparer.needsWork(SandboxStatus.NotInstalled, null, "v2", skipBroken = false))
        assertTrue(RuntimePreparer.needsWork(SandboxStatus.Ready(false), "v1", "v2", skipBroken = false)) // Mika 升级了
        assertFalse(RuntimePreparer.needsWork(SandboxStatus.Ready(true), "v2", "v2", skipBroken = false))
        assertTrue(RuntimePreparer.needsWork(SandboxStatus.Broken("x"), "v2", "v2", skipBroken = false))
        assertFalse(RuntimePreparer.needsWork(SandboxStatus.Broken("x"), "v2", "v2", skipBroken = true))
        assertFalse(RuntimePreparer.needsWork(SandboxStatus.Installing(5, "解包"), null, "v2", skipBroken = false))
        assertFalse(RuntimePreparer.needsWork(SandboxStatus.Unsupported("x86"), null, "v2", skipBroken = false))
    }

    @Test fun exit124OnlyCountsAsTimeoutAtTheLimit() {
        assertFalse(sb.launcherTimedOut(124, 200, 60))
        assertTrue(sb.launcherTimedOut(124, 59_600, 60))
        assertFalse(sb.launcherTimedOut(1, 60_000, 60))
    }

    @Test fun shareGoesToExporterWithItsOwnWorkspace() = runBlocking {
        val dirs = SandboxDirs(File(tmp, "s2/files/sandbox"), File(tmp, "s2/cache"), File(tmp, "s2/lib"), JVM_GATE, SandboxTree())
        val calls = mutableListOf<Pair<String, String>>()
        var fail: Exception? = null
        val sb = AndroidSandbox(dirs, { SandboxStatus.Ready(false) }, { emptyList() }, exporter = { g, n ->
            // 交给导出的是过了关口的句柄:内容从它读
            calls += String(g.read(0, 100)) to n; fail?.let { throw it }; com.wangzan101.mika.chat.SharedFile(n, g.size, "Download/Yunkai/$n", "content://x")
        })
        val f = sb.hostPath("c1", "out/a.txt", forWrite = true)!!.apply { parentFile?.mkdirs(); writeText("a") }
        assertEquals("Download/Yunkai/a.txt", sb.share(f, "a.txt"))
        assertEquals("a" to "a.txt", calls.single())
        // 工作区里的硬链接(可能链着 Mika 的私有文件)不导出
        val private = File(tmp, "s2/private.db").apply { writeText("secret") }
        val linked = File(f.parentFile, "linked.db")
        Files.createLink(linked.toPath(), private.toPath())
        assertEquals("linked_file", runCatching { sb.share(linked, "linked.db") }.exceptionOrNull().let { (it as SandboxError).code })
        assertEquals(1, calls.size)
        fail = FileTooLarge(1L shl 30)
        assertEquals("file_too_large", runCatching { sb.share(f, "a.txt") }.exceptionOrNull().let { (it as SandboxError).code })
        val outside = File(tmp, "elsewhere.txt").apply { writeText("x") }
        assertEquals("path_outside_workspace", runCatching { sb.share(outside, "x.txt") }.exceptionOrNull().let { (it as SandboxError).code })
        val none = AndroidSandbox(dirs, { SandboxStatus.Ready(false) }, { emptyList() })
        assertEquals("share_unavailable", runCatching { none.share(f, "a.txt") }.exceptionOrNull().let { (it as SandboxError).code })
    }

    // 网页上传与沙箱同一套路径规则:只认本对话工作区里的普通文件
    @Test fun uploadCheckUsesWorkspaceRules() {
        val ok = sb.hostPath("c1", "in/a.pdf", forWrite = true)!!.apply { parentFile?.mkdirs(); writeText("a") }
        assertEquals(com.wangzan101.mika.core.workspace.WorkspacePathCheck.Ok, sb.uploadCheck("c1", "/workspace/in/a.pdf"))
        assertEquals(com.wangzan101.mika.core.workspace.WorkspacePathCheck.NotFound, sb.uploadCheck("c1", "/workspace/in/none.pdf"))
        assertEquals(com.wangzan101.mika.core.workspace.WorkspacePathCheck.NotFound, sb.uploadCheck("c1", "/workspace/in"))
        assertEquals(com.wangzan101.mika.core.workspace.WorkspacePathCheck.Outside, sb.uploadCheck("c1", "/opt/mika/skills/skills/x/SKILL.md"))
        assertEquals(com.wangzan101.mika.core.workspace.WorkspacePathCheck.Outside, sb.uploadCheck("c1", "/workspace/../etc/passwd"))
        assertEquals(com.wangzan101.mika.core.workspace.WorkspacePathCheck.NotFound, sb.uploadCheck("c2", "/workspace/in/a.pdf")) // 别的对话看不到
        // 指向工作区外的链接不跟过去
        val outside = File(tmp, "secret.txt").apply { writeText("s") }
        Files.createSymbolicLink(File(ok.parentFile, "link.txt").toPath(), outside.toPath())
        assertTrue(sb.uploadCheck("c1", "/workspace/in/link.txt") != com.wangzan101.mika.core.workspace.WorkspacePathCheck.Ok)
    }
}
