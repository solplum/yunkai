package com.wangzan101.mika.core.cap.sandbox

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission

class RootfsExtractorTest {
    @get:Rule val tmp = TemporaryFolder()

    private sealed interface E { val name: String }
    private data class Dir(override val name: String, val mode: Int = 0x1ed /* 0755 */) : E
    private data class Reg(override val name: String, val body: String, val mode: Int = 0x1a4 /* 0644 */) : E
    private data class Sym(override val name: String, val target: String) : E
    private data class Hard(override val name: String, val target: String) : E
    private data class Special(override val name: String, val type: Byte) : E

    private fun tarGz(vararg entries: E): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(GzipCompressorOutputStream(bytes)).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            for (e in entries) {
                when (e) {
                    is Dir -> tar.putArchiveEntry(TarArchiveEntry(e.name).apply { mode = e.mode or 0x4000 })
                    is Reg -> {
                        val data = e.body.toByteArray()
                        // preserveAbsolutePath:否则写入器会悄悄去掉开头的 /
                        tar.putArchiveEntry(TarArchiveEntry(e.name, true).apply { mode = e.mode or 0x8000; size = data.size.toLong() })
                        tar.write(data)
                    }
                    is Sym -> tar.putArchiveEntry(TarArchiveEntry(e.name, TarConstants.LF_SYMLINK).apply { linkName = e.target })
                    is Hard -> tar.putArchiveEntry(TarArchiveEntry(e.name, TarConstants.LF_LINK).apply { linkName = e.target })
                    is Special -> tar.putArchiveEntry(TarArchiveEntry(e.name, e.type))
                }
                tar.closeArchiveEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    private fun perms(f: File): Set<PosixFilePermission> =
        Files.getPosixFilePermissions(f.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun dest(): File = tmp.newFolder("root")

    private fun assertUnsafe(
        input: ByteArrayInputStream,
        dest: File,
        maxBytes: Long = 1L shl 30,
        maxEntries: Int = 200_000,
    ) {
        try {
            RootfsExtractor.extract(input, dest, maxBytes, maxEntries)
            fail("expected UnsafeArchiveException")
        } catch (_: UnsafeArchiveException) {
        }
    }

    @Test fun extractsFilesDirsAndModes() {
        val d = dest()
        val r = RootfsExtractor.extract(
            tarGz(
                Dir("./"),
                Dir("./bin/"),
                Reg("./bin/tool", "#!/bin/sh\necho hi\n", mode = 0x1ed /* 0755 */),
                Dir("./etc/", mode = 0x1c0 /* 0700 */),
                Reg("./etc/motd", "hello", mode = 0x9a4 /* 04644: setuid 位必须丢掉 */),
            ),
            d,
        )
        assertEquals(2, r.files)
        assertEquals(2, r.dirs)
        assertEquals(0, r.symlinks)
        assertEquals(0, r.hardlinksCopied)
        assertTrue(r.skipped.isEmpty())
        assertEquals("hello", File(d, "etc/motd").readText())
        assertEquals("#!/bin/sh\necho hi\n", File(d, "bin/tool").readText())
        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE,
            ),
            perms(File(d, "bin/tool")),
        )
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ),
            perms(File(d, "etc/motd")),
        )
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
            perms(File(d, "etc")),
        )
    }

    @Test fun keepsAbsoluteSymlinkTargetsVerbatim() {
        val d = dest()
        val r = RootfsExtractor.extract(
            tarGz(Dir("bin/"), Sym("bin/sh", "/bin/busybox"), Sym("bin/rel", "../etc/x")),
            d,
        )
        assertEquals(2, r.symlinks)
        assertTrue(Files.isSymbolicLink(File(d, "bin/sh").toPath()))
        assertEquals("/bin/busybox", Files.readSymbolicLink(File(d, "bin/sh").toPath()).toString())
        assertEquals("../etc/x", Files.readSymbolicLink(File(d, "bin/rel").toPath()).toString())
    }

    @Test fun hardlinkBecomesCopy() {
        val d = dest()
        val r = RootfsExtractor.extract(
            tarGz(Dir("bin/"), Reg("bin/busybox", "BB", mode = 0x1ed), Hard("bin/ls", "bin/busybox")),
            d,
        )
        assertEquals(1, r.files)
        assertEquals(1, r.hardlinksCopied)
        val copy = File(d, "bin/ls")
        assertFalse(Files.isSymbolicLink(copy.toPath()))
        assertEquals("BB", copy.readText())
        assertTrue(PosixFilePermission.OWNER_EXECUTE in perms(copy))
        // 是独立副本:改一个不影响另一个
        copy.writeText("changed")
        assertEquals("BB", File(d, "bin/busybox").readText())
    }

    @Test fun longPaxNamesWork() {
        val d = dest()
        val longName = "dir/" + "n".repeat(200)
        val r = RootfsExtractor.extract(tarGz(Dir("dir/"), Reg(longName, "long")), d)
        assertEquals(1, r.files)
        assertEquals("long", File(d, longName).readText())
    }

    @Test fun dotDotEntryRejected() {
        val outer = tmp.newFolder("outer")
        val d = File(outer, "root").apply { mkdirs() }
        assertUnsafe(tarGz(Reg("../evil", "x")), d)
        assertFalse(File(outer, "evil").exists())
    }

    @Test fun absoluteEntryRejected() {
        val d = dest()
        assertUnsafe(tarGz(Reg("/tmp/mika-evil-${System.nanoTime()}", "x")), d)
    }

    @Test fun hardlinkOutsideRejected() {
        val d = dest()
        assertUnsafe(tarGz(Hard("stolen", "../../etc/passwd")), d)
    }

    @Test fun entryThroughSymlinkedDirRejected() {
        val outside = tmp.newFolder("outside")
        val d = dest()
        assertUnsafe(tarGz(Sym("link", outside.absolutePath), Reg("link/evil", "x")), d)
        assertFalse(File(outside, "evil").exists())
    }

    @Test fun deviceNodesSkipped() {
        val d = dest()
        val r = RootfsExtractor.extract(
            tarGz(
                Dir("dev/"),
                Special("dev/null", TarConstants.LF_CHR),
                Special("dev/sda", TarConstants.LF_BLK),
                Special("dev/fifo", TarConstants.LF_FIFO),
                Reg("dev/keep", "k"),
            ),
            d,
        )
        assertEquals(listOf("dev/null", "dev/sda", "dev/fifo"), r.skipped)
        assertEquals(1, r.files)
        assertFalse(File(d, "dev/null").exists())
    }

    @Test fun ownerAlwaysWritable() {
        val d = dest()
        RootfsExtractor.extract(
            tarGz(Dir("ro/", mode = 0x16d /* 0555 */), Reg("ro/file", "r", mode = 0x124 /* 0444 */)),
            d,
        )
        val f = File(d, "ro/file")
        assertTrue(PosixFilePermission.OWNER_WRITE in perms(f))
        assertFalse(PosixFilePermission.GROUP_WRITE in perms(f))
        val dirPerms = perms(File(d, "ro"))
        assertTrue(PosixFilePermission.OWNER_WRITE in dirPerms)
        assertTrue(PosixFilePermission.OWNER_EXECUTE in dirPerms)
        f.writeText("w")
    }

    @Test fun regularFileReplacesExistingSymlinkWithoutFollowing() {
        val outside = tmp.newFolder("outside2")
        val victim = File(outside, "victim").apply { writeText("orig") }
        val d = dest()
        RootfsExtractor.extract(tarGz(Sym("f", victim.absolutePath), Reg("f", "new")), d)
        assertEquals("orig", victim.readText())
        assertFalse(Files.isSymbolicLink(File(d, "f").toPath()))
        assertEquals("new", File(d, "f").readText())
    }

    @Test fun contentSurvivesLargeFile() {
        val d = dest()
        val body = "0123456789".repeat(100_000)
        RootfsExtractor.extract(tarGz(Reg("big", body)), d)
        assertArrayEquals(body.toByteArray(), File(d, "big").readBytes())
    }

    @Test fun hardlinkToSymlinkCopiesTheLink() {
        val d = dest()
        val r = RootfsExtractor.extract(tarGz(Dir("bin/"), Sym("bin/sh", "/bin/busybox"), Hard("bin/sh2", "bin/sh")), d)
        assertEquals(1, r.hardlinksCopied)
        val p = File(d, "bin/sh2").toPath()
        assertTrue(Files.isSymbolicLink(p))
        assertEquals("/bin/busybox", Files.readSymbolicLink(p).toString())
    }

    @Test fun hardlinkThroughSymlinkedDirRejected() {
        val outside = tmp.newFolder("outside3")
        File(outside, "secret").writeText("s")
        val d = dest()
        assertUnsafe(tarGz(Sym("link", outside.absolutePath), Hard("stolen", "link/secret")), d)
        assertFalse(File(d, "stolen").exists())
    }

    @Test fun tooManyBytesRejected() {
        assertUnsafe(tarGz(Reg("big", "x".repeat(20))), dest(), maxBytes = 10)
    }

    @Test fun hardlinkCopiesCountTowardBytes() {
        val d = dest()
        // 单看条目大小只有 8 字节,但每个硬链接副本都要再占 8 字节
        assertUnsafe(tarGz(Reg("f", "12345678"), Hard("h1", "f"), Hard("h2", "f")), d, maxBytes = 20)
    }

    @Test fun tooManyEntriesRejected() {
        assertUnsafe(tarGz(Reg("a", ""), Reg("b", ""), Reg("c", "")), dest(), maxEntries = 2)
    }

    @Test fun skippedListIsCapped() {
        val entries = (1..1005).map { Special("p$it", TarConstants.LF_FIFO) }.toTypedArray<E>()
        val r = RootfsExtractor.extract(tarGz(*entries), dest())
        assertEquals(1000, r.skipped.size)
        assertEquals(1005, r.skippedTotal)
    }

    @Test fun nulInNameRejected() {
        // 超过 100 字走 pax 头,NUL 能原样带进条目名
        assertUnsafe(tarGz(Reg("a".repeat(120) + "\u0000b", "x")), dest())
    }

    @Test fun nulInSymlinkTargetRejected() {
        assertUnsafe(tarGz(Sym("s", "/" + "t".repeat(120) + "\u0000x")), dest())
    }

    @Test fun deleteTreeDoesNotFollowLinks() {
        val outside = tmp.newFolder("outside4")
        val keep = File(File(outside, "sub").apply { mkdirs() }, "keep").apply { writeText("k") }
        val d = dest()
        RootfsExtractor.extract(
            tarGz(
                Dir("ro/", mode = 0x16d),
                Reg("ro/f", "r", mode = 0x124),
                Sym("abs", outside.absolutePath),
                Sym("ro/rel", "../../outside4/sub"),
            ),
            d,
        )
        // 解包后再把目录改成只读,模拟沙箱里留下的只读目录
        Files.setPosixFilePermissions(File(d, "ro").toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
        RootfsExtractor.deleteTree(d)
        assertFalse(d.exists())
        assertTrue(keep.exists())
        assertEquals("k", keep.readText())
    }

    @Test fun deleteTreeOnMissingDirIsNoop() {
        RootfsExtractor.deleteTree(File(tmp.root, "nope"))
    }
}
