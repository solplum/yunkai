package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.TOOLS

/** 读屏以外的大结果(读网页、命令输出、读文件……)按同一条裁剪线裁:线前的只剩短字段。 */
class HistoryPrunerBigReadsTest {
    private fun round(id: String, tool: String, content: String) = listOf(
        Message(Role.Assistant, listOf(Part.ToolCall(id, tool, "{}")), Author.Mika),
        Message(Role.Tool, listOf(Part.ToolResult(id, content, false)), Author.Mika),
    )

    private fun page(i: Int, chars: Int, filler: String) = buildJsonObject {
        put("ok", true); put("url", "https://example.com/$i"); put("text", filler.repeat(chars / filler.length)); put("nextOffset", chars)
    }.toString()

    private fun results(ms: List<Message>) = ms.flatMap { it.parts }.filterIsInstance<Part.ToolResult>()

    @Test fun fivePageReadsOf30kFitA64kBudget() {
        val history = listOf(Message(Role.User, listOf(Part.Text("task")), Author.Mika)) +
            (1..5).flatMap { round("c$it", "web_read_page", page(it, 30_000, "网页正文内容")) }
        assertTrue(TokenEstimate.of(history) > 64_000)
        val out = ContextCompressor(TOOLS).compress(history, contextTokens = 64_000).messages
        assertTrue("estimate ${TokenEstimate.of(out)}", TokenEstimate.of(out) < 64_000)
        val rs = results(out)
        // 只缩短旧结果还超:旧的读取只剩短字段(能按 url/nextOffset 再读)或折进摘要;最近一次原样
        for (r in rs.dropLast(1)) {
            val o = Json.parseToJsonElement(r.content) as JsonObject
            assertEquals(HistoryPruner.OMITTED_READ, o["text"]!!.jsonPrimitive.content)
            assertEquals(30_000, o["nextOffset"]!!.jsonPrimitive.content.toInt())
            assertTrue(o["url"]!!.jsonPrimitive.content.startsWith("https://"))
        }
        assertEquals(results(history).last().content, rs.last().content)
    }

    @Test fun sameRuleForEveryReadingToolAndReferenceIsKept() {
        val big = "x".repeat(10_000)
        val history = listOf(Message(Role.User, listOf(Part.Text("task")), Author.Mika)) +
            round("a", "knowledge_read", buildJsonObject { put("content", big) }.toString()) +
            round("b", "run_command", buildJsonObject { put("output", big); put("exitCode", 0) }.toString()) +
            round("c", "read_file", buildJsonObject { put("content", big); put("path", "/workspace/a.txt"); put("nextOffset", 10) }.toString()) +
            round("d", "web_evaluate_js", buildJsonObject { put("result", JsonObject(mapOf("rows" to JsonPrimitive(big)))) }.toString()) +
            round("e", "run_bundled_script", "plain text output " + big) +
            round("f", "search_files", buildJsonObject { put("matches", big) }.toString()) +
            round("g", "phone_tap", "{\"ok\":true}")
        val rs = results(HistoryPruner.prune(history, TOOLS, HistoryPruner.Cut(2))).associateBy { it.callId }
        assertEquals(big, (Json.parseToJsonElement(rs["a"]!!.content) as JsonObject)["content"]!!.jsonPrimitive.content)
        assertTrue(HistoryPruner.OMITTED_READ in rs["b"]!!.content && "\"exitCode\":0" in rs["b"]!!.content)
        assertTrue(HistoryPruner.OMITTED_READ in rs["c"]!!.content && "/workspace/a.txt" in rs["c"]!!.content)
        // 7 次往返:裁剪线在第 5 次之后,线前的 b–e 都裁(参考资料 a 除外),线后的 f 原样
        assertTrue(HistoryPruner.OMITTED_READ in rs["d"]!!.content)
        assertEquals(HistoryPruner.OMITTED_READ, rs["e"]!!.content)
        assertEquals(results(history).first { it.callId == "f" }.content, rs["f"]!!.content)
        // 小结果不动
        val small = listOf(Message(Role.User, listOf(Part.Text("t")), Author.Mika)) + (1..9).flatMap { round("s$it", "run_command", "{\"output\":\"ok\"}") }
        assertEquals(small, HistoryPruner.prune(small, TOOLS, HistoryPruner.Cut(2)))
    }
}
