package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.core.workspace.SandboxTree
import com.wangzan101.mika.core.cap.sandbox.SandboxPathContract
import com.wangzan101.mika.core.cap.sandbox.SandboxPort
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** AndroidSandbox 的路径规则在 JVM 上跑:目录换成临时目录,不起进程。 */
class AndroidSandboxContractTest : SandboxPathContract() {
    private val tmp: File = Files.createTempDirectory("android-sandbox").toFile()
    private fun dirs() = SandboxDirs(File(tmp, "files/sandbox"), File(tmp, "cache/proot-tmp"), File(tmp, "lib"), JVM_GATE, SandboxTree())
    override fun sandbox(): SandboxPort = AndroidSandbox(dirs(), { SandboxStatus.Ready(false) }, { emptyList() })
    override fun outsideDir(): File = File(tmp, "outside").apply { mkdirs() }

    // 读文件、搜文件、查上传路径不持有写树的许可:它们要是顺手建出工作区目录,重置删到一半时最后的 rmdir 就会 ENOTEMPTY
    @Test fun lookingUpPathsNeverCreatesTheWorkspace() {
        val sb = AndroidSandbox(dirs(), { SandboxStatus.Ready(false) }, { emptyList() })
        assertNotNull(sb.hostRoot("fresh", "/workspace/a.txt", forWrite = false))
        sb.uploadCheck("fresh", "/workspace/a.txt")
        assertFalse(dirs().workspace("fresh").exists())
        assertFalse(dirs().work.exists())
    }
}
