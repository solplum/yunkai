package com.wangzan101.mika.core.run

import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.agent.TaskState
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
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.assembly.toolDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class RunContractTest {
    private fun spec() = RunSpec(RunId("r1"), "看看", "thread-1")
    private fun done() = com.wangzan101.mika.core.testing.answerReply("完成")

    @Test fun oldAndNewEntrySendTheSameOrdinaryRequest() = runTest {
        val oldModel = FakeModel(done())
        val newModel = FakeModel(done())
        AgentLoop(oldModel, toolDispatcher(FakeScreen()), "BASE", clock = { 0L }, zone = ZoneId.of("UTC")).run("看看")
        AgentLoop(newModel, toolDispatcher(FakeScreen()), "BASE", clock = { 0L }, zone = ZoneId.of("UTC")).run(spec(), RunInbox())
        assertEquals(oldModel.system, newModel.system)
        assertEquals(oldModel.offeredTools, newModel.offeredTools)
        assertEquals(oldModel.requests, newModel.requests)
    }

    @Test fun steerBetweenStepsIsAnUntouchedUserMessage() = runTest {
        val inbox = RunInbox()
        val sink = object : RunSink {
            override fun event(e: RunEvent) {
                if (e is RunEvent.ToolResult && e.step == 1) {
                    assertEquals(InputReceipt.Accepted, runBlocking { inbox.send(RunInput.Steer("m1", "请再看一遍\n原样保留")) })
                }
            }
        }
        val model = FakeModel(ModelReply(listOf(Part.ToolCall("c1", "phone_observe", "{}")), StopReason.ToolUse, null), done())
        AgentLoop(model, toolDispatcher(FakeScreen()), "BASE", sink = sink).run(spec(), inbox)
        val user = model.requests[1].filter { it.author == Author.User }.last()
        assertEquals("${AgentLoop.STEER_MARKER}\n请再看一遍\n原样保留", (user.parts.first() as Part.Text).text)
        assertTrue(inbox.nextTaskMessages().isEmpty())
    }

    @Test fun eventBusFansOutDespiteOneBrokenSubscriber() {
        val bus = RunEventBus()
        val received = mutableListOf<RunEvent>()
        val broken = bus.subscribe(object : RunSink { override fun event(e: RunEvent) { error("broken") } })
        val healthy = bus.subscribe(object : RunSink { override fun event(e: RunEvent) { received += e } })
        val e = RunEvent.State(1, "running", "")
        bus.event(e)
        assertEquals(listOf(e), received)
        healthy.close()
        broken.close()
        bus.event(e)
        assertEquals(1, received.size)
    }

    @Test fun steerBeforeFinalAnswerMakesTheModelContinue() = runTest {
        val inbox = RunInbox()
        val sink = object : RunSink {
            override fun event(e: RunEvent) {
                if (e is RunEvent.ModelReply && e.step == 1) {
                    runBlocking { inbox.send(RunInput.Steer("late", "再补一句")) }
                }
            }
        }
        val model = FakeModel(done(), done())
        AgentLoop(model, toolDispatcher(FakeScreen()), "BASE", sink = sink).run(spec(), inbox)
        assertEquals(2, model.requests.size)
        assertTrue(model.requests[1].any { m -> m.author == Author.User && m.parts.filterIsInstance<Part.Text>().any { "再补一句" in it.text } })
    }

    @Test fun redirectCancelsOnlyTheModelRequestAndRetriesWithCorrection() = runTest {
        val entered = CompletableDeferred<Unit>()
        var calls = 0
        var firstCancelled = false
        val requests = mutableListOf<List<Message>>()
        val model = object : ChatModel {
            override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 128_000, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                requests += messages
                calls++
                if (calls == 1) {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { firstCancelled = true }
                }
                return done()
            }
        }
        val inbox = RunInbox()
        val running = async { AgentLoop(model, toolDispatcher(FakeScreen()), "BASE").run(spec(), inbox) }
        entered.await()
        assertEquals(InputReceipt.Accepted, inbox.send(RunInput.Redirect("fix", "改成查明天")))
        running.await()
        assertTrue(firstCancelled)
        assertEquals(2, calls)
        assertTrue(requests[1].any { m -> m.author == Author.User && m.parts.filterIsInstance<Part.Text>().any { "改成查明天" in it.text } })
    }

    @Test fun stopBypassesQueuedSteersAndLateSteerBecomesNextTask() = runTest {
        val inbox = RunInbox()
        assertEquals(InputReceipt.Accepted, inbox.send(RunInput.Steer("a", "一")))
        assertEquals(InputReceipt.Accepted, inbox.send(RunInput.Steer("b", "二")))
        assertEquals(InputReceipt.Accepted, inbox.send(RunInput.Cancel))
        assertTrue(inbox.wasCancelled)
        assertEquals(listOf("一", "二"), inbox.takeSteers().map { it.text })
        inbox.close()
        assertEquals(InputReceipt.NextTask, inbox.send(RunInput.Steer("c", "三")))
        assertFalse(inbox.nextTaskMessages().any { it.text == "三" })
    }

    @Test fun handleCarriesCancellationThroughTheRun() = runTest {
        val entered = CompletableDeferred<Unit>()
        val model = object : ChatModel {
            override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 128_000, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val loop = AgentLoop(model, toolDispatcher(FakeScreen()), "BASE")
        val handle = loop.start(this, spec())
        val events = mutableListOf<RunEvent>()
        handle.events.subscribe(object : RunSink { override fun event(e: RunEvent) { events += e } })
        entered.await()
        assertEquals(InputReceipt.Accepted, handle.cancel())
        assertEquals(TaskState.Cancelled, handle.await())
        yield()
        assertEquals(RunPhase.Ended, handle.phase.value)
        assertTrue(events.any { it is RunEvent.TaskEnded && it.outcome == "cancelled" })
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun channelAnswerMustNameTheQuestionItRepliesTo() = runTest {
        val asking = ModelReply(listOf(Part.ToolCall("ask", "ask_user", """{"prompt":"哪一个?"}""")), StopReason.ToolUse, null)
        val loop = AgentLoop(FakeModel(asking, done(), done()), toolDispatcher(FakeScreen()), "BASE")
        val handle = loop.start(this, spec())
        runCurrent()
        val askId = loop.activeAsk?.askId ?: error("question was not published")
        assertEquals(InputReceipt.NoPendingInteraction, handle.send(RunInput.Answer("旧问题的答案", "different")))
        assertEquals(InputReceipt.Accepted, handle.send(RunInput.Answer("这次的答案", askId)))
        handle.await()
        assertEquals(RunPhase.Ended, handle.phase.value)
    }
}
