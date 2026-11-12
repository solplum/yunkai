// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.cap.sandbox

import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.workspace.SandboxError
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

/** 导出到「下载/Yunkai」:经 MediaStore 查得到、大小一致;超限、工作区外、链接一律不导。 */
@RunWith(AndroidJUnit4::class)
class ShareExporterDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val exporter = ShareExporter(context)
    private val ws = SandboxDirs(context).workspace("share-device-test").apply { mkdirs() }
    private val exported = mutableListOf<String>()

    @After fun cleanUp() {
        for (uri in exported) runCatching { context.contentResolver.delete(android.net.Uri.parse(uri), null, null) }
        ws.deleteRecursively()
    }

    private fun expectError(code: String, block: suspend () -> Unit) = runBlocking {
        try { block(); fail("expected $code") } catch (e: SandboxError) { assertEquals(code, e.code) }
    }

    /** 和 AndroidSandbox.share 一样:经关口打开工作区里的 [rel],再从这个句柄导出。 */
    private suspend fun export(rel: String, name: String) = AndroidFdSys.gate.openRead(ws, rel).use { exporter.export(it, name) }

    @Test fun exportsToDownloadsYunkai() = runBlocking {
        val f = File(ws, "out.txt").apply { writeText("你好".repeat(1000)) }
        val name = "mika-test-${System.currentTimeMillis()}.txt"
        val card = export(f.name, name)
        exported += card.uri
        val saved = card.savedAs
        assertTrue(saved, saved.startsWith("Download/Yunkai/"))
        assertEquals(f.length(), card.size)
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads.SIZE, MediaStore.Downloads.RELATIVE_PATH),
            "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(saved.removePrefix("Download/Yunkai/")), null,
        )!!.use { c ->
            assertTrue("not found in MediaStore", c.moveToFirst())
            assertEquals(f.length(), c.getLong(0))
            assertTrue(c.getString(1), c.getString(1).trimEnd('/').endsWith("Download/Yunkai"))
        }
    }

    @Test fun tooLargeRejected() {
        val big = File(ws, "big.bin")
        RandomAccessFile(big, "rw").use { it.setLength(101L shl 20) }
        runBlocking {
            try { export("big.bin", "big.bin"); fail("exported a file over 100 MB") } catch (e: FileTooLarge) { }
        }
    }

    @Test fun outsideAndLinksRejected() {
        val outside = File(context.filesDir, "share-outside.txt").apply { writeText("secret") }
        try {
            expectError("path_outside_workspace") { export("../../../share-outside.txt", "x.txt") }
            val link = File(ws, "link.txt")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            // 不跟随链接打开:链接本身打不开
            expectError("not_a_file") { export("link.txt", "x.txt") }
            File(ws, "out.txt").writeText("a")
            expectError("invalid_arguments") { export("out.txt", "a‮txt.exe") }
        } finally {
            outside.delete()
        }
    }

    /** 检查之后换成 FIFO:不跟随、不阻塞地打开,按类型拒掉,不会卡在读上。 */
    @Test fun fifoRejectedWithoutBlocking() {
        val fifo = File(ws, "pipe.txt")
        android.system.Os.mkfifo(fifo.path, 0x180)
        val begin = System.currentTimeMillis()
        expectError("not_a_file") { export("pipe.txt", "pipe.txt") }
        assertTrue("took ${System.currentTimeMillis() - begin} ms", System.currentTimeMillis() - begin < 3_000)
    }

    /** 有多个硬链接的文件不导(可能链着 Mika 自己的私有文件)。系统不让建硬链接时跳过。 */
    @Test fun hardLinkedFileRejected() {
        val secret = File(context.filesDir, "share-secret.txt").apply { writeText("secret") }
        val link = File(ws, "linked.txt")
        try {
            val made = runCatching { android.system.Os.link(secret.path, link.path) }.isSuccess
            org.junit.Assume.assumeTrue("hard links not allowed here", made)
            expectError("linked_file") { export("linked.txt", "linked.txt") }
        } finally {
            secret.delete()
        }
    }

    @Test fun installPackagesRejected() {
        val apk = File(ws, "app.apk").apply { writeText("PK") }
        expectError("share_refused_type") { export(apk.name, "app.apk") }
        File(ws, "out.txt").writeText("a")
        expectError("share_refused_type") { export("out.txt", "x.aab") }
    }
}
