package com.wangzan101.mika.core.workspace

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 关口的规则:每个 [FdSys] 实现都跑同一套。JVM 上是按路径的 NioFdSys,真机上是按句柄的安卓实现
 * (app 的 WorkspaceFileGateDeviceTest,另加 FIFO 等只在真机上测得了的)。
 */
abstract class WorkspaceFileGateContract {
    protected abstract val sys: FdSys
    /** 一个空的临时目录;测试在里面建工作区和「外面」。 */
    protected abstract fun tempDir(): File

    protected val tmp: File by lazy { tempDir() }
    protected val ws by lazy { File(tmp, "ws").apply { mkdirs() } }
    protected val secret by lazy { File(tmp, "secret").apply { mkdirs(); File(this, "key.txt").writeText("private") } }
    protected val gate by lazy { WorkspaceFileGate(sys) }

    protected fun code(block: () -> Unit): String = try { block(); fail("expected a refusal"); "" } catch (e: SandboxError) { e.code }
    protected fun link(at: File, to: File) { Files.createSymbolicLink(at.toPath(), to.toPath()) }
    /** 系统不让建硬链接时跳过这条测试。 */
    protected fun hardLink(at: File, to: File) {
        org.junit.Assume.assumeTrue("hard links not allowed here", runCatching { Files.createLink(at.toPath(), to.toPath()) }.isSuccess)
    }

    @Test fun readsAPlainFileOnItsHandle() {
        File(ws, "d").mkdirs(); File(ws, "d/a.txt").writeText("hello")
        gate.openRead(ws, "d/a.txt").use { g ->
            assertEquals(5, g.size)
            assertEquals("hello", String(g.read(0, 100)))
            assertEquals("llo", String(g.read(2, 3)))
            assertEquals("a.txt", g.name)
        }
    }

    @Test fun linksAnywhereOnThePathAreRefused() {
        link(File(ws, "dir"), secret)
        link(File(ws, "f.txt"), File(secret, "key.txt"))
        File(ws, "real").mkdirs(); link(File(ws, "inner"), File(ws, "real")) // 指向工作区里面的也不跟
        assertEquals("not_a_file", code { gate.openRead(ws, "dir/key.txt").close() })
        assertEquals("not_a_file", code { gate.openRead(ws, "f.txt").close() })
        assertEquals("not_a_file", code { gate.openRead(ws, "inner/x").close() })
        assertEquals("not_a_file", code { gate.createNew(ws, "dir/new.txt").close() })
        assertEquals("not_a_file", code { gate.openWrite(ws, "f.txt", append = false).close() })
        assertEquals("private", File(secret, "key.txt").readText())
        assertFalse(File(secret, "new.txt").exists())
    }

    @Test fun hardLinkedFilesAreRefusedBeforeAnyWrite() {
        val target = File(secret, "db").apply { writeText("keep me") }
        hardLink(File(ws, "h.txt"), target)
        assertEquals("linked_file", code { gate.openRead(ws, "h.txt").close() })
        // 截断在核实之后:链进来的文件不会被清空
        assertEquals("linked_file", code { gate.openWrite(ws, "h.txt", append = false).close() })
        assertEquals("keep me", target.readText())
        assertEquals("linked_file", code { gate.delete(ws, "h.txt") })
        assertTrue(File(ws, "h.txt").exists())
    }

    @Test fun dotDotAndMissingAndDirectories() {
        assertEquals("path_outside_workspace", code { gate.openRead(ws, "../secret/key.txt").close() })
        assertEquals("file_not_found", code { gate.openRead(ws, "none.txt").close() })
        File(ws, "sub").mkdirs()
        assertEquals("not_a_file", code { gate.openRead(ws, "sub").close() })
    }

    @Test fun createNewNeverOverwritesAndNumbers() {
        gate.createNew(ws, "downloads/a.pdf", under = "downloads").use { it.write("1".toByteArray()) }
        assertEquals("file_exists", code { gate.createNew(ws, "downloads/a.pdf").close() })
        val (name, g) = gate.createNumbered(ws, "downloads", "a.pdf")
        g.use { it.write("2".toByteArray()) }
        assertEquals("a (2).pdf", name)
        assertEquals("1", File(ws, "downloads/a.pdf").readText())
        assertEquals("2", File(ws, "downloads/a (2).pdf").readText())
        // under 只认那一层目录本身
        assertEquals("path_outside_workspace", code { gate.createNew(ws, "downloads/x/b.pdf", under = "downloads").close() })
    }

    @Test fun downloadsDirSwappedForALinkCannotRedirectTheWrite() {
        link(File(ws, "downloads"), secret)
        assertEquals("not_a_file", code { gate.createNumbered(ws, "downloads", "x.sh").second.close() })
        assertEquals(listOf("key.txt"), secret.list()!!.toList())
    }

    @Test fun writeTruncatesOrAppendsOnTheVerifiedHandle() {
        gate.openWrite(ws, "out/n.txt", append = false).use { it.write("abc".toByteArray()) }
        gate.openWrite(ws, "out/n.txt", append = true).use { it.write("de".toByteArray()); assertEquals(5, it.currentSize()) }
        gate.openWrite(ws, "out/n.txt", append = false).use { it.write("z".toByteArray()) }
        assertEquals("z", File(ws, "out/n.txt").readText())
    }

    @Test fun deleteOnlyRemovesTheVerifiedFile() {
        val g = gate.createNew(ws, "downloads/p.part")
        // 核实之后名字被换成了别的文件:不删
        File(ws, "downloads/p.part").delete(); File(ws, "downloads/p.part").writeText("someone else")
        g.delete(); g.close()
        assertEquals("someone else", File(ws, "downloads/p.part").readText())
        gate.delete(ws, "downloads/p.part")
        assertFalse(File(ws, "downloads/p.part").exists())
    }

    @Test fun deleteTreeRemovesLinksNotTheirTargets() {
        File(ws, "t/a/b").mkdirs(); File(ws, "t/a/b/f.txt").writeText("x")
        link(File(ws, "t/a/out"), secret)
        link(File(ws, "t/key"), File(secret, "key.txt"))
        File(ws, "t/ro").mkdirs(); File(ws, "t/ro/f").writeText("r"); File(ws, "t/ro").setWritable(false)
        gate.deleteTree(ws, "t")
        assertFalse(File(ws, "t").exists())
        assertEquals("private", File(secret, "key.txt").readText())
        gate.deleteTree(ws, "never-was") // 不存在不报错
    }

    @Test fun replaceSwapsOutLinksInsteadOfWritingThroughThem() {
        File(ws, "etc").mkdirs()
        link(File(ws, "etc/resolv.conf"), File(secret, "key.txt"))
        gate.replace(ws, "etc/resolv.conf", "nameserver 1.1.1.1\n".toByteArray())
        assertEquals("private", File(secret, "key.txt").readText())
        assertFalse(Files.isSymbolicLink(File(ws, "etc/resolv.conf").toPath()))
        assertEquals("nameserver 1.1.1.1\n", File(ws, "etc/resolv.conf").readText())
        val target = File(secret, "hard").apply { writeText("h") }
        hardLink(File(ws, "etc/hosts"), target)
        gate.replace(ws, "etc/hosts", "127.0.0.1 localhost\n".toByteArray())
        assertEquals("h", target.readText())
        assertTrue(File(ws, "etc").list()!!.none { it.startsWith(".mika-new-") })
    }

    @Test fun symlinkReplacesTheEntryAndMkdirsAnchored() {
        gate.symlink(ws, "dev/fd", "/proc/self/fd")
        assertEquals("/proc/self/fd", Files.readSymbolicLink(File(ws, "dev/fd").toPath()).toString())
        gate.symlink(ws, "dev/fd", "/proc/self/fd")
        link(File(ws, "sym"), secret)
        assertEquals("not_a_file", code { gate.mkdirs(ws, "sym/inside") })
        assertFalse(File(secret, "inside").exists())
    }

    @Test fun listAndBytes() {
        gate.createNew(ws, ".mika/out-1.txt").use { it.write(byteArrayOf(1, 2)) }
        assertEquals(listOf("out-1.txt"), gate.list(ws, ".mika"))
        assertEquals(emptyList<String>(), gate.list(ws, "none"))
        gate.openRead(ws, ".mika/out-1.txt").use { assertArrayEquals(byteArrayOf(1, 2), it.input().readBytes()) }
    }
}
