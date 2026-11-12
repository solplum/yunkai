package com.wangzan101.mika.core.record

import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.model.ModelError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

class AgentLoopTimingTest {
    private var now = 0L
    private val events = mutableListOf<RunEvent>()
    private val sink = object : RunSink {
        override fun event(e: RunEvent) { events += e }
        override fun media(mediaId: Int, png: ByteArray) = Unit
    }
    private var seq = 0
    private fun call(name: String, args: String = "{}") = Part.ToolCall("t${++seq}", name, args)
    private fun reply(c: Part.ToolCall) = ModelReply(listOf(c), StopReason.ToolUse, null)

    /** 每次调用把假时钟推进 [ms]。 */
    private fun slow(inner: ChatModel, ms: Long) = object : ChatModel by inner {
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            now += ms
            return inner.complete(system, messages, tools)
        }
    }

    private fun run(model: ChatModel, screen: ScreenPort = FakeScreen()) = runBlocking {
        AgentLoop(model, toolDispatcher(screen, false), "BASE", sink = sink, clock = { now }).run("查一下")
    }

    @Test fun modelLatencyRecorded() {
        run(slow(FakeModel(reply(call("phone_observe")), com.wangzan101.mika.core.testing.answerReply("x")), 1200))
        val replies = events.filterIsInstance<RunEvent.ModelReply>()
        assertEquals(2, replies.size)
        replies.forEach { assertEquals(1200L, it.latencyMs) }
    }

    @Test fun toolDurationRecorded() {
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = FakeScreen().snapshot().also { now += 300 }
        }
        run(FakeModel(reply(call("phone_observe")), com.wangzan101.mika.core.testing.answerReply("x")), screen)
        val results = events.filterIsInstance<RunEvent.ToolResult>()
        val observe = results.first { it.tool == "phone_observe" }
        assertEquals(300L, observe.durationMs)
        assertEquals("model", observe.source)
    }

    // 等用户回答(ask_user)的时间不算进工具耗时:那是等人,不是 Mika 慢
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun durationMsExcludesUserWaits() = runTest {
        val m = FakeModel(reply(call("ask_user", """{"prompt":"发给谁?"}""")), com.wangzan101.mika.core.testing.answerReply("x"))
        val l = AgentLoop(m, toolDispatcher(FakeScreen(), false), "BASE", sink = sink, clock = { now })
        val run = async { l.run("查一下") }
        runCurrent()
        assertTrue(l.state.value is TaskState.WaitingInput)
        now += 60_000
        assertTrue(l.answerInput("张三"))
        run.await()
        val asked = events.filterIsInstance<RunEvent.ToolResult>().first { it.tool == "ask_user" }
        assertEquals(0L, asked.durationMs)
    }

    // 重试前花的时间(失败的那次请求和退避)单记 retryWaitMs,不算进这次回复的 latencyMs
    @Test fun retryWaitSplitFromLatency() {
        var first = true
        val inner = FakeModel(com.wangzan101.mika.core.testing.answerReply("x"))
        val flaky = object : ChatModel by inner {
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                if (first) {
                    first = false
                    now += 500
                    throw ModelError.RateLimited(1)
                }
                now += 1200
                return inner.complete(system, messages, tools)
            }
        }
        run(flaky)
        val r = events.filterIsInstance<RunEvent.ModelReply>().single()
        assertEquals(1200L, r.latencyMs)
        assertEquals(500L, r.retryWaitMs)
        // 到手时刻含之前的重试:用户在这时看到回答
        assertEquals(1700L, r.repliedAtMs)
    }

    // 带调用的回复执行完才记;到手的时刻另记,第一次反馈按它算
    @Test fun replyArrivalRecordedBeforeToolTime() {
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = FakeScreen().snapshot().also { now += 300 }
        }
        run(slow(FakeModel(reply(call("phone_observe")), com.wangzan101.mika.core.testing.answerReply("x")), 1000), screen)
        val first = events.filterIsInstance<RunEvent.ModelReply>().first()
        assertEquals(1000L, first.repliedAtMs)
        assertTrue(first.atMs >= 1300)
        assertEquals(1000L, RunMetrics.of(events)!!.firstFeedbackMs)
    }

    // 失败的结局带上类别
    @Test fun failedEndCarriesItsKind() {
        val broken = object : ChatModel by FakeModel(com.wangzan101.mika.core.testing.answerReply("x")) {
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply = throw ModelError.Auth()
        }
        run(broken)
        val end = events.filterIsInstance<RunEvent.TaskEnded>().single()
        assertEquals("failed", end.outcome)
        assertEquals("model", end.kind)
    }
}
