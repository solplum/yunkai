package com.wangzan101.mika.task

import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunPhase
import com.wangzan101.mika.core.run.StoredRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files

class FileRunStateStoreTest {
    @Test fun savedReplyAndUnfinishedMarkerSurviveReopen() {
        val dir = Files.createTempDirectory("mika-run-store").toFile()
        try {
            val file = dir.resolve("runs.json")
            val first = FileRunStateStore(file)
            first.save(StoredRun(RunId("run-1"), "thread-1", "任务原话", RunPhase.Aftercare,
                reply = "回答原文", deliveryId = "delivery-1", roundInProgress = true,
                triggerWindowKey = "plan:1000", runSucceeded = false))
            val reopened = FileRunStateStore(file)
            assertEquals("回答原文", reopened.get(RunId("run-1"))?.reply)
            assertEquals("delivery-1", reopened.get(RunId("run-1"))?.deliveryId)
            assertEquals("plan:1000", reopened.get(RunId("run-1"))?.triggerWindowKey)
            assertEquals(false, reopened.get(RunId("run-1"))?.runSucceeded)
            assertEquals(1, reopened.interruptUnfinished().size)
            assertEquals(RunPhase.Interrupted, FileRunStateStore(file).get(RunId("run-1"))?.phase)
            // 账本只记阶段与交付:不存发给模型的上下文(图片、原始参数、密钥都不落盘)
            assertFalse(file.readText().contains("\"context\""))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun oldFileWithContextSnapshotStillLoadsAndDropsIt() {
        val dir = Files.createTempDirectory("mika-run-store").toFile()
        try {
            val file = dir.resolve("runs.json")
            file.writeText("""[{"id":"run-1","threadId":"t","goal":"g","phase":"running","roundInProgress":true,""" +
                """"context":{"messages":[],"summary":null,"userOriginals":[],"atMs":1}}]""")
            val store = FileRunStateStore(file)
            assertEquals(true, store.get(RunId("run-1"))?.roundInProgress)
            store.save(store.get(RunId("run-1"))!!.copy(phase = RunPhase.Ended))
            assertFalse(file.readText().contains("\"context\""))
        } finally {
            dir.deleteRecursively()
        }
    }
}
