// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.core.record

import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.verify.EvidenceLedger
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.tool.FakeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.TOOLS
import com.wangzan101.mika.core.assembly.toolDispatcher

private class ListSink : RunSink {
    val events = mutableListOf<RunEvent>()
    val screens = mutableListOf<Pair<Int, ByteArray>>()
    override fun event(e: RunEvent) { events += e }
    override fun media(mediaId: Int, png: ByteArray) { screens += mediaId to png }
}

private var seq = 0
private fun call(name: String, args: String = "{}") = Part.ToolCall("r${++seq}", name, args)
private fun reply(vararg parts: Part, usage: Usage? = null) = ModelReply(parts.toList(), StopReason.ToolUse, usage)

class AgentLoopRecordTest {
    private fun run(model: FakeModel, screen: ScreenPort = FakeScreen(), vision: Boolean = false, frames: Boolean = false, goal: String = "帮我查", inline: Boolean = false): ListSink {
        val sink = ListSink()
        kotlinx.coroutines.runBlocking {
            AgentLoop(model, toolDispatcher(screen, vision, recordFrames = frames, inlineScreen = inline), "BASE", sink = sink).run(goal)
        }
        return sink
    }

    @Test fun emitsStartReplyResultEnd() {
        val m = FakeModel(
            reply(Part.Text("先看看"), call("phone_observe")),
            reply(call("phone_tap", """{"x":500,"y":500}""")),
            com.wangzan101.mika.core.testing.answerReply("好了"),
        )
        val ev = run(m).events
        assertEquals(
            // 坐标处没有元素:停止并记录 target_unresolved,不伪造执行活动
            // 最后一条不带调用的回复是回答:先字面核对,再记下代码找到的来源
            // 第一次读屏展开了手机一组:下一次问模型之前记一条 tools_opened
            listOf("TaskStarted", "PromptVersion", "ModelReply", "ToolResult", "Observed", "Activity", "State", "ModelReply", "ToolResult", "ModelReply", "AnswerCheck", "Sources", "TaskEnded"),
            ev.map { it::class.simpleName },
        )
        assertEquals("帮我查", (ev[0] as RunEvent.TaskStarted).goal)
        val r1 = ev[2] as RunEvent.ModelReply
        assertEquals(1, r1.step); assertEquals("先看看", r1.text); assertEquals("phone_observe", r1.tool)
        assertEquals(RunEvent.State((ev[6] as RunEvent.State).atMs, "tools_opened", "phone"), ev[6])
        val t2 = ev[8] as RunEvent.ToolResult
        assertEquals(2, t2.step); assertEquals("phone_tap", t2.tool)
        assertTrue("target_unresolved" in t2.resultJson)
        assertTrue("\"executed\":false" in t2.resultJson)
        assertEquals("observe", (ev[5] as RunEvent.Activity).kind)
        assertTrue(ev.filterIsInstance<RunEvent.Activity>().none { it.kind == "act" })
        val end = ev.last() as RunEvent.TaskEnded
        assertEquals("succeeded", end.outcome); assertEquals("好了", end.answerOrReason)
        // 观察保留采集时刻,但按因果关系记在所属工具结果之后。
        assertTrue(ev.zipWithNext().all { (a, b) -> a.atMs <= b.atMs || (a is RunEvent.ToolResult && b is RunEvent.Observed) })
    }

    @Test fun tokensRecordedFromUsage() {
        val m = FakeModel(
            reply(call("phone_observe"), usage = Usage(1200, 80, 1000)),
            com.wangzan101.mika.core.testing.answerReply("x"),
        )
        val replies = run(m).events.filterIsInstance<RunEvent.ModelReply>()
        assertEquals(1200, replies[0].inputTokens); assertEquals(80, replies[0].outputTokens); assertEquals(1000, replies[0].cachedTokens)
        assertNull(replies[1].inputTokens); assertNull(replies[1].outputTokens); assertNull(replies[1].cachedTokens)
    }

    @Test fun largeResultsKeptWhole() {
        val els = (1..150).map { UiElement("@e$it", "text", "第 $it 行很长很长的文字内容", 10, 10 * it, listOf(0, 0, 10, 10), null, false, false, false) }
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = ScreenSnapshot("s1", "com.x", els, 1080, 2400)
        }
        val m = FakeModel(reply(call("phone_observe")), com.wangzan101.mika.core.testing.answerReply("x"))
        val r = run(m, screen).events.filterIsInstance<RunEvent.ToolResult>().first()
        // 记录是回放和调试的依据:超过普通字段上限的工具结果也要整条留下
        assertTrue("length ${r.resultJson.length}", r.resultJson.length > RunEvent.MAX_FIELD)
        assertTrue(r.resultJson.contains("第 150 行很长很长的文字内容"))
        assertTrue(r.resultJson.startsWith("{\"ok\":true"))
    }

    @Test fun screensHandedToSinkWithIds() {
        val m = FakeModel(reply(call("phone_observe")), reply(call("phone_screenshot")), com.wangzan101.mika.core.testing.answerReply("x"))
        val sink = run(m, vision = true)
        assertEquals(listOf(1, 2), sink.screens.map { it.first })
        val saved = sink.events.filterIsInstance<RunEvent.Media>()
        assertEquals(listOf(1, 2), saved.map { it.mediaId })
        assertEquals(listOf(1, 2), saved.map { it.step })
        assertEquals("com.x", saved[0].place)
        assertEquals(listOf(1, 2), sink.events.filterIsInstance<RunEvent.ToolResult>().map { it.screenshotId })
    }

    @Test fun textOnlyModelStillRecordsFrames() {
        val m = FakeModel(reply(call("phone_observe")), com.wangzan101.mika.core.testing.answerReply("x"))
        val sink = run(m, frames = true)
        assertEquals(listOf(1), sink.screens.map { it.first })
        // 画面只进记录,不发给看不了图的模型
        assertTrue(m.requests[1].none { msg -> msg.parts.any { it is Part.Image } })
        val noFrames = run(FakeModel(reply(call("phone_observe")), com.wangzan101.mika.core.testing.answerReply("x")))
        assertTrue(noFrames.screens.isEmpty())
    }

    @Test fun passwordTypedIntoSecretFieldMasked() {
        val screen = FakeScreen().apply { result = ActionResult.Ok(secret = true) }
        val m = FakeModel(reply(call("phone_type_text", """{"text":"hunter2"}""")), com.wangzan101.mika.core.testing.answerReply("x"))
        val ev = run(m, screen).events
        val r = ev.filterIsInstance<RunEvent.ModelReply>().first()
        assertFalse(r.argsJson!!.contains("hunter2"))
        assertTrue(r.argsJson!!.contains("***"))
        assertTrue(ev.none { it.toString().contains("hunter2") })
    }

    @Test fun typedTextKeptOnlyWhenPortSaysNotSecret() {
        val m = FakeModel(reply(call("phone_type_text", """{"text":"天气"}""")), com.wangzan101.mika.core.testing.answerReply("x"))
        val r = run(m).events.filterIsInstance<RunEvent.ModelReply>().first()
        assertTrue(r.argsJson!!.contains("天气"))
    }

    @Test fun failedTypeTextMasked() {
        val screen = FakeScreen().apply { result = ActionResult.Failed("no_focus") }
        val m = FakeModel(reply(call("phone_type_text", """{"text":"hunter2"}""")), com.wangzan101.mika.core.testing.answerReply("x"))
        val ev = run(m, screen).events
        assertTrue(ev.none { it.toString().contains("hunter2") })
        assertTrue(ev.filterIsInstance<RunEvent.ModelReply>().first().argsJson!!.contains("***"))
    }

    @Test fun guardStopBeforeExecutionMasked() {
        // 同一个输入反复做，只有实际界面不变才会被守卫叫停；记录仍要脱敏
        val m = FakeModel(reply(call("phone_type_text", """{"text":"hunter2"}""")), repeatLast = true)
        val ev = run(m, FakeScreen().apply { result = ActionResult.Ok(changed = false, secret = true) }).events
        val end = ev.last() as RunEvent.TaskEnded
        assertEquals("failed", end.outcome)
        assertTrue(end.answerOrReason, end.answerOrReason.contains("no_progress"))
        val last = ev.filterIsInstance<RunEvent.ModelReply>().last()
        assertEquals(ev.filterIsInstance<RunEvent.ToolResult>().size, ev.filterIsInstance<RunEvent.ModelReply>().size)
        assertFalse(last.argsJson!!.contains("hunter2"))
        assertTrue(last.argsJson!!.contains("***"))
    }

    @Test fun throwingSinkDoesNotBreakTask() {
        val sink = object : RunSink {
            override fun event(e: RunEvent) { throw StackOverflowError("boom") }
            override fun media(mediaId: Int, png: ByteArray) { throw IllegalStateException() }
        }
        val m = FakeModel(reply(call("phone_observe")), com.wangzan101.mika.core.testing.answerReply("ok"))
        val end = kotlinx.coroutines.runBlocking { AgentLoop(m, toolDispatcher(FakeScreen(), true), "BASE", sink = sink).run("g") }
        com.wangzan101.mika.core.testing.assertAnswered("ok", end)
    }

    @Test fun eventsRoundTripAsJsonLines() {
        val all = listOf(
            RunEvent.TaskStarted(1, "g", "m"),
            RunEvent.Criteria(2, listOf("a"), listOf("b")),
            RunEvent.ModelReply(3, 1, "t", "phone_tap", "{}", 1, 2, 3),
            RunEvent.ToolResult(4, 1, "phone_tap", "{}", true, 1),
            RunEvent.Media(5, 1, 1, "com.x"),
            RunEvent.Observed(5, 1, "当前 App:com.x\n搜索", 3, "read", "phone", Place.app("com.x"), inputs = listOf("搜索"), focused = "搜索"),
            RunEvent.Activity(5, 1, "observe", "phone_observe", "com.x", "completed", listOf(3), callId = "r1"),
            RunEvent.State(6, "waiting_input", "?"),
            RunEvent.Confirmation(7, "send", "张三", "hi", null, "approved"),
            RunEvent.Takeover(8, "login", "handed_back", 1000, 3),
            RunEvent.Barrier(9, "login_wall"),
            RunEvent.Verifier(10, "pass", "{}", 3),
            RunEvent.Compressed(11, 5),
            RunEvent.TaskEnded(12, "succeeded", "ok"),
        )
        for (e in all) {
            val line = RunEvent.encode(e)
            assertFalse(line.contains('\n'))
            assertEquals(e, RunEvent.decode(line))
        }
        assertTrue(RunEvent.encode(all[0]).contains("\"type\":\"task_started\""))
    }

    @Test fun oldScreenEventsDecodeAsObservationAndMedia() {
        val read = RunEvent.decode("""{"type":"screen_text","atMs":7,"step":2,"app":"com.x","names":["搜索","结果"],"inputs":["关键字"],"focused":"关键字"}""")
        assertEquals(RunEvent.Observed(7, 2, "搜索\n结果", source = "phone", place = Place.app("com.x"), inputs = listOf("关键字"), focused = "关键字"), read)
        val frame = RunEvent.decode("""{"type":"screen_saved","atMs":8,"step":2,"screenshotId":3,"app":"com.x"}""")
        assertEquals(RunEvent.Media(8, 3, 2, "com.x"), frame)
    }

    @Test fun observationKeptWholeWhenResultIsClipped() {
        val els = (1..150).map { UiElement("@e$it", "text", "第 $it 行很长很长的文字内容", 10, 10 * it, listOf(0, 0, 10, 10), null, false, false, false) }
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = ScreenSnapshot("s1", "com.x", els, 1080, 2400)
        }
        val m = FakeModel(reply(call("phone_observe")), reply(call("phone_tap", """{"x":1,"y":1}""")), com.wangzan101.mika.core.testing.answerReply("x"))
        val texts = run(m, screen).events.filterIsInstance<RunEvent.Observed>()
        // 读界面记一份(和账本里那一条一样,带编号);tap 带回的那一屏也记(见 actionReturnedScreenIsRecorded)
        assertEquals(1, texts[0].step)
        assertEquals(1, texts[0].id)
        assertEquals(Place.app("com.x"), texts[0].place)
        assertEquals(listOf("当前 App:com.x") + els.map { it.name }, texts[0].text.lines())
    }

    @Test fun actionReturnedScreenIsRecorded() {
        for ((inline, vision) in listOf(true to false, false to true)) {
            val els = listOf(UiElement("@e1", "text", "明天晴 25 度", 10, 10, listOf(0, 0, 10, 10), null, false, false, false))
            val screen = object : ScreenPort by FakeScreen() {
                override suspend fun snapshot() = ScreenSnapshot("s1", "com.x", els, 1080, 2400)
            }
            val m = FakeModel(reply(call("phone_tap", """{"x":1,"y":1}""")), com.wangzan101.mika.core.testing.answerReply("明天晴 25 度"))
            val sink = run(m, screen, vision = vision, inline = inline)
            val texts = sink.events.filterIsInstance<RunEvent.Observed>()
            assertEquals("inline=$inline vision=$vision", listOf("当前 App:com.x", "明天晴 25 度"), texts.single().text.lines())
            // 评测取证据的口径能找到只出现在动作带回的屏幕里的答案
            assertTrue(com.wangzan101.mika.core.eval.Checks.grounded("明天晴 25 度", com.wangzan101.mika.core.eval.RunFacts.evidence(sink.events, TOOLS, "", "明天晴 25 度"), 1))
        }
    }

    @Test fun observationRedactedAndCapped() {
        val key = "sk-" + "a".repeat(30)
        val els = listOf(UiElement("@e1", "text", "密钥 $key", 1, 1, listOf(0, 0, 1, 1), null, false, false, false)) +
            (2..2000).map { UiElement("@e$it", "text", "x".repeat(50) + it, 1, 1, listOf(0, 0, 1, 1), null, false, false, false) }
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = ScreenSnapshot("s1", "com.x", els, 1080, 2400)
        }
        val m = FakeModel(reply(call("phone_list_elements")), com.wangzan101.mika.core.testing.answerReply("x"))
        val t = run(m, screen).events.filterIsInstance<RunEvent.Observed>().single()
        assertFalse(key in t.text)
        assertTrue(t.text.length <= EvidenceLedger.READ_CHARS)
    }

    // 新记录里每条进账的观察都记下了:离线重建的账本和运行时的一模一样(读界面、动作带回的界面、命令输出、工具事实)
    @Test fun recordedObservationsRebuildTheSameLedger() {
        val sb = com.wangzan101.mika.core.cap.sandbox.FakeSandbox().apply {
            next = { r -> com.wangzan101.mika.core.cap.sandbox.CommandResult(0, false, false, 10, "共 42 行 ${r.argv}", 0, false) }
        }
        val m = FakeModel(
            reply(call("phone_observe")),
            reply(call("phone_launch_app", """{"package":"com.x"}""")),
            reply(call("phone_tap", """{"x":1,"y":1}""")),
            reply(call("run_command", """{"command":"wc -l data.csv"}""")),
            com.wangzan101.mika.core.testing.answerReply("共 42 行"),
        )
        val sink = ListSink()
        val tools = toolDispatcher(FakeScreen(), true, inlineScreen = true, sandbox = sb, workspace = { "w" })
        kotlinx.coroutines.runBlocking { AgentLoop(m, tools, "BASE", sink = sink).run("数一下行数") }
        val live = kotlinx.coroutines.runBlocking { tools.evidence(fresh = false) }.ledger.all
        assertTrue(live.size >= 3)
        assertEquals(live, com.wangzan101.mika.core.eval.RunFacts.ledger(sink.events, TOOLS).all)
        // 每条都跟在读到它的那次调用的结果后面
        val ev = sink.events
        ev.forEachIndexed { i, e -> if (e is RunEvent.Observed) assertTrue(ev[i - 1] is RunEvent.ToolResult || ev[i - 1] is RunEvent.Observed) }
    }

    @Test fun barrierEventRecorded() {
        val m = FakeModel(
            reply(call("handle_obstacle", """{"kind":"access_blocked","reason":"操作频繁"}""")),
            reply(call("handle_obstacle", """{"kind":"bogus"}""")),
            com.wangzan101.mika.core.testing.answerReply("x"),
        )
        val ev = run(m).events
        // 只有合法的上报记一条障碍,且紧跟它的工具结果
        val b = ev.filterIsInstance<RunEvent.Barrier>()
        assertEquals(listOf("access_blocked"), b.map { it.kind })
        val at = ev.indexOf(b.single())
        assertEquals("handle_obstacle", (ev[at - 1] as RunEvent.ToolResult).tool)
    }
}
