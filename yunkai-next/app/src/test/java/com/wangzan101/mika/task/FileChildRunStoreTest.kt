package com.wangzan101.mika.task

import com.wangzan101.mika.core.child.ChildPhase
import com.wangzan101.mika.core.child.ChildRecord
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class FileChildRunStoreTest {
    @Test fun runningChildBecomesUnknownAfterRestart() {
        val file = Files.createTempDirectory("child-test").resolve("children.json").toFile()
        FileChildRunStore(file).save(ChildRecord("child", "parent", ChildPhase.Running, atMs = 10))
        val restored = FileChildRunStore(file)
        assertEquals(ChildPhase.Unknown, restored.get("child")?.phase)
        assertEquals(ChildPhase.Unknown, FileChildRunStore(file).get("child")?.phase)
    }
}
