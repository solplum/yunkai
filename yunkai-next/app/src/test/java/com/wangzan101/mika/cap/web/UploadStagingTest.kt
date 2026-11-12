package com.wangzan101.mika.cap.web

import com.wangzan101.mika.cap.sandbox.JVM_GATE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class UploadStagingTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun setup(): Triple<File, File, UploadStaging> {
        val ws = tmp.newFolder("ws")
        val root = File(tmp.root, "cache/webupload")
        return Triple(ws, root, UploadStaging(root, JVM_GATE, maxFileBytes = 1024, maxTotalBytes = 1500))
    }

    @Test fun copiesWorkspaceFilesIntoAPrivateStagingDir() {
        val (ws, root, staging) = setup()
        File(ws, "in").mkdirs(); File(ws, "in/photo.txt").writeText("photo")
        File(ws, "other").mkdirs(); File(ws, "other/photo.txt").writeText("second")
        val r = staging.stage(ws, listOf("/workspace/in/photo.txt", "/workspace/other/photo.txt")) as UploadStaging.Result.Ok
        assertEquals(listOf("photo", "second"), r.staged.files.map { it.readText() })
        assertEquals(listOf("photo.txt", "photo.txt"), r.staged.files.map { it.name })
        assertTrue(r.staged.files.all { it.canonicalPath.startsWith(root.canonicalPath + File.separator) })
        // 交出去之后工作区里的文件再换也不影响副本
        File(ws, "in/photo.txt").writeText("swapped")
        assertEquals("photo", r.staged.files[0].readText())
        staging.delete(r.staged)
        assertFalse(r.staged.dir.exists())
    }

    @Test fun refusesWhatTheGateRefuses() {
        val (ws, root, staging) = setup()
        val secret = tmp.newFolder("secret").let { File(it, "key").apply { writeText("k") } }
        Files.createSymbolicLink(File(ws, "link.txt").toPath(), secret.toPath())
        Files.createLink(File(ws, "hard.txt").toPath(), secret.toPath())
        File(ws, "big.bin").writeBytes(ByteArray(2000))
        File(ws, "a.bin").writeBytes(ByteArray(1000)); File(ws, "b.bin").writeBytes(ByteArray(1000))
        fun refused(vararg p: String) = (staging.stage(ws, p.toList()) as UploadStaging.Result.Refused).error
        assertEquals("file_not_found", refused("/workspace/link.txt"))
        assertEquals("linked_file", refused("/workspace/hard.txt"))
        assertEquals("path_outside_workspace", refused("/workspace/../etc/passwd"))
        assertEquals("path_outside_workspace", refused("/opt/mika/skills/x"))
        assertEquals("file_not_found", refused("/workspace/none.txt"))
        assertEquals("file_too_large", refused("/workspace/big.bin"))
        assertEquals("file_too_large", refused("/workspace/a.bin", "/workspace/b.bin"))
        // 拒绝时不留副本
        assertTrue(root.list().orEmpty().isEmpty())
        staging.clear()
        assertFalse(root.exists())
    }
}
