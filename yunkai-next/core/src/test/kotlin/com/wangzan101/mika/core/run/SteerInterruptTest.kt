package com.wangzan101.mika.core.run

import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionOutcome
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.action.OutwardPacing
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.web.DwellInterrupted
import com.wangzan101.mika.core.cap.web.PageDwell
import com.wangzan101.mika.core.cap.web.PagePacing
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 插话是补充:撤掉等着的模型请求、提前结束纯等待、收尾前到的话接着做;做过的一概保留,节奏保护不丢。 */
class SteerInterruptTest {
    private fun spec() = RunSpec(RunId("r1"), "看看", "thread-1")
    private fun call(id: String, name: String, args: String = "{}") = ModelReply(listOf(Part.ToolCall(id, name, args)), StopReason.ToolUse, null)

    /** 第 n 次请求(从 1 数)交给 [reply];收到的消息另存一份。 */
    private class Scripted(private val reply: suspend (Int) -> ModelReply) : ChatModel {
        override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 128_000, reasoning = false)
        val requests = mutableListOf<List<Message>>()
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            requests += messages.toList()
            return reply(requests.size)
        }
    }

    private fun List<Message>.steerIndex(text: String) =
        indexOfFirst { m -> m.author == Author.User && m.parts.filterIsInstance<Part.Text>().any { "${AgentLoop.STEER_MARKER}\n$text" == it.text } }

    private fun List<Message>.results() = flatMap { m -> m.parts.filterIsInstance<Part.ToolResult>() }

    @Test fun steerDuringModelCallCancelsItAndReasksAfterTheUntouchedHistory() = runTest {
        val inbox = RunInbox()
        val entered = CompletableDeferred<Unit>()
        var cancelled = false
        val model = Scripted { n ->
            when (n) {
                1 -> call("c1", "phone_observe")
                2 -> { entered.complete(Unit); try { awaitCancellation() } finally { cancelled = true } }
                else -> answerReply("完成")
            }
        }
        val running = async { AgentLoop(model, toolDispatcher(FakeScreen()), "BASE").run(spec(), inbox) }
        entered.await()
        // 普通插话(不是 Redirect)也撤掉等着的请求
        assertEquals(InputReceipt.Accepted, inbox.send(RunInput.Steer("m1", "顺便看看价格")))
        val end = running.await()
        assertTrue(cancelled)
        assertTrue("$end", end is TaskState.Succeeded)
        assertEquals(3, model.requests.size)
        val cut = model.requests[1]
        val again = model.requests[2]
        // 撤掉的那次请求发过的消息原样留在前面(包括第一步的工具结果),插话接在后面
        assertEquals(cut, again.take(cut.size))
        assertTrue(cut.results().any { it.callId == "c1" })
        assertEquals(cut.size, again.steerIndex("顺便看看价格"))
    }

    /** 后退要等 [release] 才做完。 */
    private class SlowBack : ScreenPort by FakeScreen() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var backs = 0
        override suspend fun back(): ActionResult { entered.complete(Unit); release.await(); backs++; return ActionResult.Ok(changed = true) }
    }

    @Test fun steerDuringToolExecutionLetsTheToolFinishThenFollowsItsResult() = runTest {
        val inbox = RunInbox()
        val screen = SlowBack()
        val model = Scripted { n -> if (n == 1) call("c1", "phone_back") else answerReply("完成") }
        val running = async { AgentLoop(model, toolDispatcher(screen), "BASE").run(spec(), inbox) }
        screen.entered.await()
        assertEquals(InputReceipt.Accepted, inbox.send(RunInput.Steer("m1", "回到首页")))
        screen.release.complete(Unit)
        running.await()
        assertEquals(1, screen.backs)
        assertEquals(2, model.requests.size)
        val next = model.requests[1]
        val resultAt = next.indexOfFirst { m -> m.parts.any { it is Part.ToolResult && it.callId == "c1" } }
        val result = next[resultAt].parts.filterIsInstance<Part.ToolResult>().single { it.callId == "c1" }
        assertFalse(result.content, result.isError)
        assertTrue(next.steerIndex("回到首页") > resultAt)
    }

    @Test fun steerEndsAPureWaitAtOnce() = runTest {
        val inbox = RunInbox()
        var askedAgainAt = -1L
        val model = Scripted { n ->
            when (n) {
                1 -> { launch { delay(1_000); inbox.send(RunInput.Steer("m1", "别等了")) }; call("c1", "wait", """{"seconds":10}""") }
                else -> { if (askedAgainAt < 0) askedAgainAt = currentTime; answerReply("完成") }
            }
        }
        AgentLoop(model, toolDispatcher(FakeScreen()), "BASE").run(spec(), inbox)
        assertTrue("$askedAgainAt", askedAgainAt in 1_000 until 10_000)
        val result = model.requests[1].results().single { it.callId == "c1" }
        assertEquals(JsonPrimitive(SKIPPED_FOR_NEW_MESSAGE), Json.parseToJsonElement(result.content).jsonObject["error"])
        assertTrue(model.requests[1].steerIndex("别等了") >= 0)
    }

    @Test fun steerWhileTheModelGivesUpKeepsTheRunGoing() = runTest {
        val inbox = RunInbox()
        val sink = object : RunSink {
            override fun event(e: RunEvent) {
                if (e is RunEvent.ToolResult && e.callId == "c1") runBlocking { inbox.send(RunInput.Steer("m1", "换个办法试试")) }
            }
        }
        val model = Scripted { n -> if (n == 1) call("c1", "end_not_done", """{"message":"找不到入口"}""") else answerReply("换了办法,办好了") }
        val end = AgentLoop(model, toolDispatcher(FakeScreen()), "BASE", sink = sink).run(spec(), inbox)
        assertEquals("换了办法,办好了", (end as TaskState.Succeeded).answer)
        assertTrue(model.requests[1].results().any { it.callId == "c1" })
        assertTrue(model.requests[1].steerIndex("换个办法试试") >= 0)
    }

    @Test fun steerBeforeTheAnswerIsHandedOverContinuesAndOnlyAClosedRunTakesNextTasks() = runTest {
        val inbox = RunInbox()
        var asked = 0
        // 回答已经写完、正要交出时到的话:不收尾,带着它接着做
        val loop = AgentLoop(Scripted { n -> answerReply("第${n}次") }, toolDispatcher(FakeScreen()), "BASE", beforeAccept = {
            if (++asked == 1) inbox.send(RunInput.Steer("m1", "再加一条"))
            com.wangzan101.mika.core.agent.BeforeAccept.Accept
        })
        val end = loop.run(spec(), inbox)
        assertEquals("第2次", (end as TaskState.Succeeded).answer)
        assertEquals(InputReceipt.NextTask, inbox.send(RunInput.Steer("m2", "下一件")))
    }

    @Test fun closeUnlessSteeredLeavesTheInboxOpenWhileWordsWait() = runTest {
        val inbox = RunInbox()
        inbox.send(RunInput.Steer("m1", "一"))
        assertFalse(inbox.closeUnlessSteered())
        assertEquals(InputReceipt.Accepted, inbox.send(RunInput.Steer("m2", "二")))
        assertEquals(listOf("一", "二"), inbox.takeSteers().map { it.text })
        assertTrue(inbox.closeUnlessSteered())
        assertEquals(InputReceipt.NextTask, inbox.send(RunInput.Steer("m3", "三")))
    }

    /** 一屏只有一个「立即沟通」;记下点了几次。 */
    private class GreetPage : ScreenPort by FakeScreen() {
        val taps = mutableListOf<String>()
        private val shown = listOf(UiElement("@e1", "button", "立即沟通", 900, 300, listOf(800, 100, 999, 160), null, true, false, false))
        override suspend fun snapshot() = ScreenSnapshot("s1", "com.jobs", shown, 1080, 2400)
        override suspend fun peek() = snapshot()
        override suspend fun tapRef(ref: String, snapshotId: String) = ActionResult.Ok(changed = true).also { taps += ref }
    }

    @Test fun steerDuringTheOutwardGapSkipsTheActionAndTheGapStillHoldsNextTime() = runTest {
        val inbox = RunInbox()
        val screen = GreetPage()
        val tools = toolDispatcher(screen)
        tools.execute(Part.ToolCall("o", "phone_observe", "{}"))
        val pacing = OutwardPacing.human(Random(9))
        val executor = ActionExecutor(tools, OperationGuards(), null, gate = {}, recorder = ActionRecorder(RunSink.NONE, { currentTime }), autonomous = true, pacing = pacing)
        suspend fun tap(id: String) = withContext(inbox.signal) {
            executor.run(Part.ToolCall(id, "phone_tap", """{"ref":"@e1","snapshotId":"s1"}"""), Origin.Model(1))
        }
        tap("c1")
        val gap = pacing.waitBefore("com.jobs", currentTime)
        assertTrue("$gap", gap > 2_000)
        val t0 = currentTime
        val sent = launch { delay(1_000); inbox.send(RunInput.Steer("m1", "先别发")) }
        val cut = tap("c2") as ActionOutcome.Ran
        sent.join()
        assertEquals(JsonPrimitive(SKIPPED_FOR_NEW_MESSAGE), cut.exec.content["error"])
        assertEquals(1_000L, currentTime - t0)
        assertEquals(listOf("@e1"), screen.taps)
        // 模型读到插话之后还要发:余下的间隔照样补上再做
        inbox.takeSteers()
        assertEquals(gap - 1_000, pacing.waitBefore("com.jobs", currentTime))
        tap("c3")
        assertTrue(currentTime - t0 >= gap)
        assertEquals(listOf("@e1", "@e1"), screen.taps)
    }

    @Test fun steerDuringPageDwellStopsItAndTheDwellIsKeptBeforeTheNextPageChange() = runTest {
        val inbox = RunInbox()
        val d = dwell()
        d.seen("https://shop.example.com/")
        d.seen("https://shop.example.com/list?page=2")
        val owed = PagePacing.human(Random(11)).let { p -> p.noteLoad("https://shop.example.com/", 0); p.noteLoad("https://shop.example.com/list?page=2", 0) }
        assertTrue("$owed", owed > 2_000)
        launch { delay(1_000); inbox.send(RunInput.Steer("m1", "换一家")) }
        val stopped = runCatching { withContext(inbox.signal) { d.before() } }.exceptionOrNull()
        assertTrue("$stopped", stopped is DwellInterrupted)
        assertEquals(1_000L, currentTime)
        inbox.takeSteers()
        // 没停够的那部分不算停过:下次换页之前补足
        assertEquals(owed - 1_000, withContext(inbox.signal) { d.before() })
        assertEquals(owed, currentTime)
        assertEquals(0, d.before())
    }

    private fun TestScope.dwell() = PageDwell(PagePacing.human(Random(11)), clock = { currentTime }, sleep = { delay(it) })
}
