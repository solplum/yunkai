package com.wangzan101.mika.core.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import com.wangzan101.mika.core.cap.sandbox.FakeSandbox

class SandboxPathsTest {
    @Test fun relativeResolvesUnderWorkspace() {
        assertEquals("/workspace/a/b.txt", SandboxPaths.normalize("a/b.txt"))
        assertEquals("/workspace/a/b.txt", SandboxPaths.normalize("./a//c/../b.txt"))
        assertEquals("/workspace/sub/x", SandboxPaths.normalize("x", "/workspace/sub"))
        assertEquals("/workspace", SandboxPaths.normalize("/workspace/"))
    }

    @Test fun dotDotEscapeRejected() {
        assertNull(SandboxPaths.normalize("../etc/passwd"))
        assertNull(SandboxPaths.normalize("/workspace/../etc"))
        assertNull(SandboxPaths.normalize("/etc/passwd"))
        assertNull(SandboxPaths.normalize("/workspacex/a"))
        assertNull(SandboxPaths.normalize("/opt/mika/skills/../../../root"))
        assertNull(SandboxPaths.resolveHost(Files.createTempDirectory("r").toFile(), "../x"))
    }

    @Test fun skillsReadableNotWritable() {
        val p = SandboxPaths.normalize("/opt/mika/skills/app/x/scripts/a.py")
        assertEquals("/opt/mika/skills/app/x/scripts/a.py", p)
        assertFalse(SandboxPaths.isWritable(p!!))
        assertTrue(SandboxPaths.isWritable(SandboxPaths.normalize("out/a.png")!!))
        // 宿主映射也一样:技能目录只能读
        val sb = FakeSandbox()
        assertNotNull(sb.hostPath("c1", "/opt/mika/skills/app/x/scripts/a.py", forWrite = false))
        assertNull(sb.hostPath("c1", "/opt/mika/skills/app/x/scripts/a.py", forWrite = true))
    }

    @Test fun symlinkEscapeRejected() {
        val tmp = Files.createTempDirectory("paths").toFile()
        val root = File(tmp, "ws").apply { mkdirs() }
        val outside = File(tmp, "outside").apply { mkdirs() }
        File(outside, "secret").writeText("key")
        File(root, "inner").mkdirs()
        Files.createSymbolicLink(File(root, "x").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "dangling").toPath(), File(outside, "not-yet").toPath())
        Files.createSymbolicLink(File(root, "ok").toPath(), File(root, "inner").toPath())

        assertNull(SandboxPaths.resolveHost(root, "x"))
        assertNull(SandboxPaths.resolveHost(root, "x/secret"))
        // 指向外面、目标还不存在的链接:写下去就会落到外面
        assertNull(SandboxPaths.resolveHost(root, "dangling"))
        assertEquals(File(root, "inner/f").canonicalFile, SandboxPaths.resolveHost(root, "ok/f"))
        assertEquals(File(root, "new/file.txt").canonicalFile, SandboxPaths.resolveHost(root, "new/file.txt"))
    }
}
