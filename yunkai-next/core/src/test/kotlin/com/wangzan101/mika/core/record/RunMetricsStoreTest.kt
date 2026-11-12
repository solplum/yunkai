package com.wangzan101.mika.core.record

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunMetricsStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val file get() = tmp.root.resolve("files/${RunMetricsStore.FILE_NAME}")
    private fun m(id: String, total: Long = 1_000, outcome: String = "succeeded") = RunMetrics(runId = id, totalMs = total, ended = true, outcome = outcome)

    @Test fun keepsOnlyTheLatestOnes() {
        val s = RunMetricsStore(file, keep = 3)
        (1..5).forEach { s.append(m("r$it")) }
        assertEquals(listOf("r3", "r4", "r5"), s.all().map { it.runId })
        assertEquals(3, file.readLines().count { it.isNotBlank() })
    }

    @Test fun survivesANewInstance() {
        RunMetricsStore(file).append(m("r1", total = 4_200))
        val back = RunMetricsStore(file).all().single()
        assertEquals("r1", back.runId)
        assertEquals(4_200L, back.totalMs)
    }

    @Test fun recentTakesTheNewestN() {
        val s = RunMetricsStore(file)
        (1..4).forEach { s.append(m("r$it")) }
        assertEquals(listOf("r3", "r4"), s.recent(2).map { it.runId })
        assertEquals(4, s.recent(null).size)
    }

    @Test fun sameRunIsReplacedNotDuplicated() {
        val s = RunMetricsStore(file)
        s.append(m("r1", total = 1))
        s.append(m("r1", total = 2))
        assertEquals(listOf(2L), s.all().map { it.totalMs })
    }

    @Test fun updateChangesOnlyThatRun() {
        val s = RunMetricsStore(file)
        s.append(m("r1")); s.append(m("r2"))
        s.update("r1") { it.copy(judged = "partial") }
        s.update("missing") { it.copy(judged = "done") }
        assertEquals(listOf("partial", null), s.all().map { it.judged })
    }

    @Test fun skipsCorruptLines() {
        file.parentFile.mkdirs()
        file.writeText("{not json\n" + RunMetricsStore.JSON.encodeToString(RunMetrics.serializer(), m("ok")) + "\n\n")
        assertEquals(listOf("ok"), RunMetricsStore(file).all().map { it.runId })
    }

    @Test fun wholeFileCorruptReadsEmptyAndRecovers() {
        file.parentFile.mkdirs()
        file.writeBytes(byteArrayOf(0, 1, 2, -1, -2))
        val s = RunMetricsStore(file)
        assertTrue(s.all().isEmpty())
        s.append(m("r1"))
        assertEquals(listOf("r1"), s.all().map { it.runId })
    }

    @Test fun missingFileIsEmpty() {
        assertTrue(RunMetricsStore(file).all().isEmpty())
    }

    @Test fun exportsAJsonArrayWithEveryField() {
        val out = Json.parseToJsonElement(RunMetricsStore.export(listOf(m("r1"), m("r2", outcome = "failed")))) as JsonArray
        assertEquals(2, out.size)
        val first = out[0] as JsonObject
        assertEquals(JsonPrimitive("r1"), first["runId"])
        // 默认值也写出来,分析时字段齐全
        assertTrue(first.containsKey("firstFeedbackMs"))
        assertTrue(first.containsKey("observeMs"))
        assertTrue(first.containsKey("judged"))
    }
}
