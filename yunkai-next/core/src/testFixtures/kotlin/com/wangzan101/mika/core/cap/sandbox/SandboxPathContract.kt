package com.wangzan101.mika.core.cap.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import com.wangzan101.mika.core.workspace.SandboxPaths

/**
 * 每个 [SandboxPort] 实现都要满足的路径规则:越界、符号链接逃逸、技能目录只读、完整输出落盘不出工作区。
 * 子类给出沙箱和一个在沙箱根目录之外的宿主目录(符号链接的目标)。
 */
abstract class SandboxPathContract {
    protected abstract fun sandbox(): SandboxPort
    /** 宿主上不属于任何工作区、也不属于技能目录的目录;测试只在里面建子目录。 */
    protected abstract fun outsideDir(): File
    protected open val workspace = "contract-ws"

    private val sb by lazy { sandbox() }
    private fun wsRoot(): File = sb.hostPath(workspace, SandboxPaths.WORKSPACE, forWrite = true)!!.also { it.mkdirs() }
    private fun freshOutside(): File = File(outsideDir(), "case-${System.nanoTime()}").apply { mkdirs() }

    @Test fun relativeAndAbsoluteNameTheSameFile() {
        val a = sb.hostPath(workspace, "d/a.txt", forWrite = true)
        assertNotNull(a)
        assertEquals(a, sb.hostPath(workspace, "/workspace/d/a.txt", forWrite = true))
        assertEquals(a, sb.hostPath(workspace, "/workspace/x/../d/./a.txt", forWrite = false))
        assertTrue(a!!.canonicalPath.startsWith(wsRoot().canonicalPath + File.separator))
    }

    @Test fun escapesRejected() {
        for (p in listOf("../x", "/workspace/../x", "/etc/passwd", "/workspacex/a", "/opt/mika/skills/../../../x")) {
            assertNull(p, sb.hostPath(workspace, p, forWrite = false))
            assertNull(p, sb.hostPath(workspace, p, forWrite = true))
        }
    }

    @Test fun skillsReadableNotWritable() {
        assertNotNull(sb.hostPath(workspace, "/opt/mika/skills/domain/x/SKILL.md", forWrite = false))
        assertNull(sb.hostPath(workspace, "/opt/mika/skills/domain/x/SKILL.md", forWrite = true))
    }

    @Test fun symlinkOutOfWorkspaceRejected() {
        val out = freshOutside()
        File(out, "secret").writeText("key")
        val root = wsRoot()
        Files.createSymbolicLink(File(root, "out-${out.name}").toPath(), out.toPath())
        Files.createSymbolicLink(File(root, "dangling-${out.name}").toPath(), File(out, "not-yet").toPath())
        for (p in listOf("out-${out.name}", "out-${out.name}/secret", "out-${out.name}/new.txt", "dangling-${out.name}")) {
            assertNull(p, sb.hostPath(workspace, p, forWrite = false))
            assertNull(p, sb.hostPath(workspace, p, forWrite = true))
        }
    }

    @Test fun workspacesAreSeparate() {
        assertNotEquals(sb.hostPath("$workspace-a", "f.txt", forWrite = true), sb.hostPath("$workspace-b", "f.txt", forWrite = true))
    }

    @Test fun saveOutputStaysInWorkspace() {
        val path = sb.saveOutput(workspace, "完整输出")
        assertTrue(path, path.startsWith("/workspace/.mika/out-"))
        assertEquals("完整输出", sb.hostPath(workspace, path, forWrite = false)!!.readText())
        // .mika 被换成指到外面的链接:不写,抛异常
        val out = freshOutside()
        val mika = File(wsRoot(), ".mika")
        mika.deleteRecursively()
        Files.createSymbolicLink(mika.toPath(), out.toPath())
        try {
            assertThrows(Exception::class.java) { sb.saveOutput(workspace, "leak") }
            assertTrue(out.list()!!.isEmpty())
        } finally {
            Files.delete(mika.toPath())
        }
    }
}
