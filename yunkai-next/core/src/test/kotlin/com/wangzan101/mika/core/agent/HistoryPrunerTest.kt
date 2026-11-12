package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.TOOLS

class HistoryPrunerTest {
    private val big = """[{"ref":"@e1","name":"${"x".repeat(500)}"}]"""
    private val task = Message(Role.User, listOf(Part.Text("task")), Author.User)
    private fun obs(id: String) = listOf(
        Message(Role.Assistant, listOf(Part.ToolCall(id, "phone_observe", "{}")), Author.Mika),
        Message(Role.Tool, listOf(Part.ToolResult(id, """{"ok":true,"currentApp":"com.x","snapshotId":"s$id","elements":$big}""", false)), Author.Mika),
    )
    private fun tap(id: String) = listOf(
        Message(Role.Assistant, listOf(Part.ToolCall(id, "phone_tap", "{}")), Author.Mika),
        Message(Role.Tool, listOf(Part.ToolResult(id, """{"ok":true,"changed":true}""", false)), Author.Mika),
    )
    private fun act(id: String, sid: String) = listOf(
        Message(Role.Assistant, listOf(Part.ToolCall(id, "phone_tap", "{}")), Author.Mika),
        Message(Role.Tool, listOf(Part.ToolResult(id,
            """{"ok":true,"screen":{"snapshotId":"$sid","base":"x","full":false,"added":$big,"removed":[],"changed":[],"unchanged":3}}""", false)), Author.Mika),
    )
    private fun shot(id: Int) = Message(Role.User, listOf(Part.Text("screen"), Part.Image("AAAA", "image/jpeg", 580, 1280, screenshotId = id)), Author.Mika)

    /** [n] 次读屏往返。 */
    private fun observations(n: Int) = listOf(task) + (1..n).flatMap { obs("c$it") }
    private fun results(ms: List<Message>) = ms.flatMap { it.parts }.filterIsInstance<Part.ToolResult>()
    /** 线前留最近 2 次往返原样(压缩时用的线由 [ContextCompressor] 定,这里只测裁法)。 */
    private val cut = HistoryPruner.Cut(2)
    private fun full(ms: List<Message>) = results(ms).filter { "@e1" in it.content }.map { it.callId }

    @Test fun cutLineKeepsTheLastWindowRounds() {
        assertEquals(listOf(0, 0, 1, 2, 3), (1..5).map { cut.before(it) })
        assertEquals(listOf(0, 1, 2), (1..3).map { HistoryPruner.LAST.before(it) })
    }

    @Test fun beforeTheLineShrinksExceptLatestFullRead() {
        val h = observations(7)
        val out = HistoryPruner.prune(h, TOOLS, cut)
        // 线在第 5 次之后:c1–c4 裁掉,线前最近的全量 c5 和线后的 c6、c7 原样
        assertEquals(listOf("c5", "c6", "c7"), full(out))
        val first = results(out).first { it.callId == "c1" }.content
        assertTrue(HistoryPruner.OMITTED in first && "\"snapshotId\":\"sc1\"" in first && "\"currentApp\":\"com.x\"" in first)
        assertEquals(h.size, out.size)
        assertEquals(results(h).map { it.callId }, results(out).map { it.callId })
        // 原历史不变
        assertTrue("@e1" in results(h).first { it.callId == "c1" }.content)
    }

    @Test fun smallResultsAndHeadUntouched() {
        val h = listOf(task) + (1..8).flatMap { obs("c$it") + tap("t$it") }
        val out = HistoryPruner.prune(h, TOOLS, cut)
        assertEquals(results(h).filter { it.callId.startsWith("t") }, results(out).filter { it.callId.startsWith("t") })
        assertEquals(task, out[0])
        assertEquals(observations(2), HistoryPruner.prune(observations(2), TOOLS, cut))
    }

    @Test fun neverBreaksToolPairs() {
        for (n in 1..14) {
            val h = observations(n)
            val out = HistoryPruner.prune(h, TOOLS, cut)
            assertEquals(h.map { it.role }, out.map { it.role })
            assertEquals(results(h).map { it.callId }, results(out).map { it.callId })
            assertTrue(full(out).isNotEmpty())
        }
    }

    @Test fun diffChainKeepsItsBase() {
        val h = listOf(task) + obs("c1") + (1..6).flatMap { act("a$it", "d$it") }
        val r = results(HistoryPruner.prune(h, TOOLS, cut))
        // 线前最近的全量 c1 是差异的底,不裁;线前的差异裁掉(短字段留着),线后的原样
        assertTrue("@e1" in r.first { it.callId == "c1" }.content)
        val diffs = r.filter { it.callId.startsWith("a") }
        assertTrue(diffs.take(4).all { HistoryPruner.OMITTED in it.content && "@e1" !in it.content && "\"ok\":true" in it.content })
        assertTrue(diffs.drop(4).all { "@e1" in it.content })
        // 全量的 screen 结果也算全量读屏:线前最近的全量是 f,c1 照常裁
        val withFull = listOf(task) + obs("c1") +
            listOf(Message(Role.Assistant, listOf(Part.ToolCall("f", "phone_back", "{}")), Author.Mika),
                Message(Role.Tool, listOf(Part.ToolResult("f", """{"ok":true,"screen":{"snapshotId":"z","full":true,"elements":$big}}""", false)), Author.Mika)) +
            (1..5).flatMap { act("a$it", "d$it") }
        val r2 = results(HistoryPruner.prune(withFull, TOOLS, cut))
        assertTrue("@e1" !in r2.first { it.callId == "c1" }.content)
        assertTrue("@e1" in r2.first { it.callId == "f" }.content)
    }

    // 压缩时线之前的图换占位;平常只有图的张数超过服务商上限时才换
    @Test fun imagesDroppedBeforeTheLineAndAtTheProviderLimit() {
        val file = Message(Role.User, listOf(Part.Text("file"), Part.Image("BBBB", "image/jpeg")), Author.Mika)
        val h = listOf(task) + tap("t0") + file + (1..6).flatMap { tap("t$it") + shot(it) }
        fun ids(out: List<Message>) = out.flatMap { it.parts }.filterIsInstance<Part.Image>().map { it.screenshotId }
        assertEquals(h, HistoryPruner.limitImages(h, Int.MAX_VALUE))
        // 压缩:线在第 6 次往返之后,画面 1–5 换成可回看的占位,读到的图片文件换成省略
        val tight = HistoryPruner.prune(h, TOOLS, HistoryPruner.LAST)
        assertEquals(listOf<Int?>(6), ids(tight))
        val texts = tight.flatMap { it.parts }.filterIsInstance<Part.Text>().map { it.text }
        assertTrue((1..5).all { HistoryPruner.olderImage(it) in texts } && HistoryPruner.OMITTED_IMAGE in texts)
        // 服务商明确给出上限后,只从最早的助手画面开始换,留下最多 4 张
        assertEquals(listOf<Int?>(3, 4, 5, 6), ids(HistoryPruner.limitImages(h, 4)))
        val early = listOf(task) + tap("t0") + file + (1..5).flatMap { tap("t$it") + shot(it) }
        assertEquals(listOf<Int?>(2, 3, 4, 5), ids(HistoryPruner.limitImages(early, 4)))
    }

    // 缩短按结果里读到的观察是什么样的东西,缩短后留着结局和观察编号
    @Test fun shrinksByObservationFormAndKeepsOutcomeAndObsIds() {
        fun round(id: String, tool: String, content: String, form: com.wangzan101.mika.core.observation.ObsForm, obsId: Int) = listOf(
            Message(Role.Assistant, listOf(Part.ToolCall(id, tool, "{}")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult(id, content, false, listOf(com.wangzan101.mika.core.observation.ReadTag(obsId, form)))), Author.Mika),
        )
        val long = "x".repeat(5000)
        val h = listOf(task) +
            round("i", "phone_launch_app", """{"ok":true,"currentApp":"com.x","note":"${"y".repeat(400)}"}""", com.wangzan101.mika.core.observation.ObsForm.Interface, 1) +
            round("p", "web_read_page", """{"ok":true,"url":"https://a.example.com/","text":"$long"}""", com.wangzan101.mika.core.observation.ObsForm.Page, 2) +
            round("s", "web_read_page", """{"ok":true,"url":"https://a.example.com/","text":"短的正文"}""", com.wangzan101.mika.core.observation.ObsForm.Page, 3) +
            round("m", "memory_search", """{"ok":true,"memories":[{"content":"$long"}]}""", com.wangzan101.mika.core.observation.ObsForm.UserSaid, 4) +
            round("c", "run_command", """{"ok":false,"error":"exit_nonzero","message":"命令失败","exitCode":2,"output":"$long"}""", com.wangzan101.mika.core.observation.ObsForm.Command, 5) +
            (1..6).flatMap { tap("t$it") }
        val rs = results(HistoryPruner.prune(h, TOOLS, cut)).associateBy { it.callId }
        // 界面状态一律缩短;页面、命令输出只在大时缩短;用户说的不缩
        assertTrue(HistoryPruner.OMITTED in rs["i"]!!.content && "\"obsIds\":[1]" in rs["i"]!!.content && "\"currentApp\":\"com.x\"" in rs["i"]!!.content)
        assertTrue(HistoryPruner.OMITTED_READ in rs["p"]!!.content && "\"obsIds\":[2]" in rs["p"]!!.content && "https://a.example.com/" in rs["p"]!!.content)
        assertTrue("短的正文" in rs["s"]!!.content && "obsIds" !in rs["s"]!!.content)
        assertTrue(long in rs["m"]!!.content)
        val c = rs["c"]!!.content
        assertTrue(c, "\"ok\":false" in c && "\"error\":\"exit_nonzero\"" in c && "命令失败" in c && "\"exitCode\":2" in c && "\"obsIds\":[5]" in c && long !in c)
    }

    @Test fun userMessagesNeverPruned() {
        val photo = Message(Role.User, listOf(Part.Text("就按这张图找"), Part.Image("CCCC", screenshotId = 9)), Author.User)
        val h = listOf(task) + tap("t0") + photo + (1..6).flatMap { tap("t$it") }
        assertTrue(photo in HistoryPruner.prune(h, TOOLS, cut))
        val withFrames = h + shot(10) + shot(11)
        assertTrue(photo in HistoryPruner.limitImages(withFrames, 1))
    }

    @Test fun recallBeforeTheLineLosesElementText() {
        val text = "x".repeat(500)
        val recall = listOf(
            Message(Role.Assistant, listOf(Part.ToolCall("r1", "view_past_screens", """{"ids":[1]}""")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("r1", """{"ok":true,"screenshots":[{"id":1,"step":1,"app":"com.x","elementsText":"$text"}]}""", false)), Author.Mika),
        )
        val old = results(HistoryPruner.prune(obs("c1") + recall + (2..6).flatMap { obs("c$it") }, TOOLS, cut)).first { it.callId == "r1" }.content
        assertTrue(old, HistoryPruner.OMITTED in old && text !in old && "\"id\":1" in old)
        val recent = (1..6).flatMap { obs("c$it") } + recall
        assertTrue(text in results(HistoryPruner.prune(recent, TOOLS, cut)).first { it.callId == "r1" }.content)
    }
}
