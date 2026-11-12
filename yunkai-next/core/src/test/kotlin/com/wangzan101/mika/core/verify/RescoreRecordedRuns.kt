package com.wangzan101.mika.core.verify

import com.wangzan101.mika.core.record.RunEvent
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/**
 * 离线重算:用现在的 [Grounding] 重新核对录下来的运行(不调模型)。没设目录时跳过。
 *
 *   MIKA_RESCORE_DIR=/path/to/runs ./gradlew :core:test --tests '*RescoreRecordedRuns*' --rerun
 *
 * 递归找目录下所有 events.jsonl;每次核查(verifier 事件)按 [RecordedVerifications] 配上它核的那次 finish(不论先后)取回答,
 * 证据用核查之前的全部读屏文字和工具结果里的全部字符串(不含那次 finish 自己的),相对日期按那次核查的时刻换算(时区取
 * MIKA_RESCORE_ZONE,没设用本机的)。结果写到 <目录>/rescore.tsv:运行、当时的结论与理由、现在的兜底结论、找不到的事实。
 */
class RescoreRecordedRuns {
    @Test fun rescore() {
        val dir = (System.getenv("MIKA_RESCORE_DIR") ?: System.getProperty("mika.rescore.dir"))?.let(::File)
        assumeTrue("MIKA_RESCORE_DIR not set", dir != null && dir.isDirectory)
        val zone = System.getenv("MIKA_RESCORE_ZONE")?.let(ZoneId::of) ?: ZoneId.systemDefault()
        val rows = mutableListOf("run\tthen\tthen_reason\tnow\tnot_found")
        dir!!.walk().filter { it.name == "events.jsonl" }.sorted().forEach { f ->
            val events = f.readLines().mapNotNull { runCatching { RunEvent.decode(it) }.getOrNull() }
            val goal = events.filterIsInstance<RunEvent.TaskStarted>().firstOrNull()?.goal.orEmpty()
            for ((i, fi) in RecordedVerifications.pairs(events)) {
                val e = events[i] as RunEvent.Verifier
                val answer = RecordedVerifications.answer(events, fi) ?: continue
                val g = Grounding.check(goal, answer, RecordedVerifications.sources(events, i, fi),
                    java.time.Instant.ofEpochMilli(e.atMs).atZone(zone).toLocalDate())
                val now = when (g.found) { true -> "found"; false -> "not_found"; null -> "nothing_checkable" }
                val thenReason = RecordedVerifications.arg(e.detailJson, "reason").orEmpty()
                rows += listOf(f.parentFile.name, e.verdict, thenReason, now, g.unmatched.joinToString(" | ") { "${it.kind}:${it.text}" })
                    .joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
            }
        }
        File(dir, "rescore.tsv").writeText(rows.joinToString("\n") + "\n")
        println(rows.joinToString("\n"))
    }
}
