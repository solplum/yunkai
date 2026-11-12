package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.core.workspace.SandboxTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProcRunnerArgsTest {
    private val dirs = SandboxDirs(File("/data/m/files/sandbox"), File("/data/m/cache/proot-tmp"), File("/data/app/lib/arm64"), JVM_GATE, SandboxTree())
    private val ws = dirs.workspace("conv1")

    private fun List<String>.hasPair(a: String, b: String) = indices.any { it + 1 < size && this[it] == a && this[it + 1] == b }

    @Test fun argvOrderAndBinds() {
        val argv = ProcRunner.argv(dirs, ws, "sub/dir", 60, network = false, standIns = listOf("stat"),
            command = listOf("/bin/sh", "-lc", "echo hi"), devices = listOf("-b", "/dev/null"))
        assertEquals("/data/app/lib/arm64/libmikarun.so", argv[0])
        assertTrue(argv.hasPair("--timeout", "60"))
        assertTrue(argv.hasPair("--net", "off"))
        val proot = argv.indexOf("/data/app/lib/arm64/libproot.so")
        assertEquals("--", argv[proot - 1])
        for (flag in listOf("--kill-on-exit", "--link2symlink", "-0")) assertTrue(flag, argv.indexOf(flag) > proot)
        assertTrue(argv.hasPair("-r", "/data/m/files/sandbox/rootfs"))
        assertTrue(argv.hasPair("-b", "/dev/null"))
        assertFalse("whole /dev must not be bound", argv.hasPair("-b", "/dev"))
        assertTrue(argv.hasPair("-b", "/data/m/files/sandbox/proc/stat:/proc/stat"))
        assertTrue(argv.hasPair("-b", "/data/m/files/sandbox/work/c-conv1:/workspace"))
        assertTrue(argv.hasPair("-b", "/data/m/files/sandbox/skills:/opt/mika/skills"))
        assertTrue(argv.hasPair("-w", "/workspace/sub/dir"))
        assertEquals(listOf("/bin/sh", "-lc", "echo hi"), argv.takeLast(3))
        assertTrue(argv.indexOf("-w") < argv.size - 3)

        val root = ProcRunner.argv(dirs, ws, "", 5, network = true, standIns = emptyList(), command = listOf("true"), devices = emptyList())
        assertTrue(root.hasPair("-w", "/workspace"))
        assertTrue(root.hasPair("--net", "on"))
    }

    @Test fun envIsMinimal() {
        val env = ProcRunner.env(dirs, null, null, "conv1")
        assertEquals(setOf("PROOT_LOADER", "PROOT_TMP_DIR", "HOME", "PATH", "LANG", "TMPDIR", "MIKA_CONVERSATION"), env.keys)
        assertEquals("/data/app/lib/arm64/libproot_loader.so", env["PROOT_LOADER"])
        assertEquals("/data/m/cache/proot-tmp", env["PROOT_TMP_DIR"])
        assertTrue(env.keys.none { it == "LD_PRELOAD" || it.startsWith("ANDROID_") })
        val bridged = ProcRunner.env(dirs, "mika-bridge-x", "tok", "conv1")
        assertEquals("mika-bridge-x", bridged["MIKA_BRIDGE"]); assertEquals("tok", bridged["MIKA_TOKEN"])
    }

    @Test fun oddConversationIdsStayInsideWork() {
        for (id in listOf("../../etc", "a/b", "", "对话一")) {
            val f = dirs.workspace(id)
            assertEquals(id, dirs.work, f.parentFile)
            assertFalse(id, f.name.contains(".."))
        }
        assertEquals(dirs.workspace("x/y"), dirs.workspace("x/y"))
        // 简单名字和哈希名字分在两个前缀下:「h-…」样子的 ID 不会撞上别的对话的哈希目录
        val hashed = dirs.workspace("a/b").name
        assertTrue(hashed, hashed.startsWith("h-"))
        assertEquals("c-$hashed", dirs.workspace(hashed).name)
    }
}
