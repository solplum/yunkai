package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.replay.Events
import com.wangzan101.mika.core.replay.MemRoutes
import com.wangzan101.mika.core.replay.Notes
import com.wangzan101.mika.core.replay.PagedScreen
import com.wangzan101.mika.core.replay.RouteMatcher
import com.wangzan101.mika.core.replay.ui
import com.wangzan101.mika.core.verify.Verifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

class AgentLoopReplayTest {
    private var seq = 0
    private fun call(name: String, args: String = "{}") = ModelReply(listOf(Part.ToolCall("m${++seq}", name, args)), StopReason.ToolUse, null)
    private fun text(s: String) = ModelReply(listOf(Part.Text(s)), StopReason.EndTurn, null)
    private val finish = text("写好了,屏上有新笔记")
    private val pick = """{"route": 1, "slots": ["今天天气不错"]}"""
    private val pass = """{"checks": [{"criterion": "t", "status": "met", "quote": "新笔记"}], "verdict": "pass", "reason": "屏上有"}"""
    private val goal = "在备忘录里写今天天气不错"

    private class Run(val sink: Events, val main: FakeModel, val store: MemRoutes, val screen: PagedScreen)

    private fun run(
        screen: PagedScreen = Notes.screen(), store: MemRoutes = MemRoutes(Notes.route()), main: FakeModel = FakeModel(finish),
        matcher: ChatModel = FakeModel(text(pick)), flags: SpeedFlags = SpeedFlags(replay = true), verifier: Verifier? = null,
        task: String = goal,
        /** 交出回答后判的结局(产品里在结果交给用户之后才判);路线只在办成时记成功。 */
        outcome: RunOutcome = RunOutcome.Done,
    ): Run {
        val sink = Events()
        runBlocking {
            val agent = AgentLoop(main, toolDispatcher(screen), "BASE", sink = sink, flags = flags, verifier = verifier,
                routes = RouteMatcher(matcher, store, null))
            agent.run(task)
            agent.noteOutcome(outcome)
        }
        return Run(sink, main, store, screen)
    }

    /** 交还时补进对话的全部文字:说明、以及读屏那对调用的结果。 */
    private fun firstPrompt(r: Run) = r.main.requests.first().drop(1).flatMap { it.parts }.joinToString("") {
        when (it) {
            is Part.Text -> it.text
            is Part.ToolResult -> it.content
            else -> ""
        }
    }

    @Test fun goalsThatNameTheWebAreNotReplayed() {
        val matcher = FakeModel(text(pick))
        val r = run(matcher = matcher, task = "用网页$goal")
        assertTrue(r.sink.all.filterIsInstance<RunEvent.Replay>().isEmpty())
        assertTrue(matcher.requests.isEmpty())
    }

    @Test fun divergedHandsBackWithSummaryAndFullScreen() {
        val s = Notes.screen()
        s.pages["home"] = listOf(ui("@e4", "text", "请先登录", listOf(0, 0, 999, 60), clickable = false))
        val r = run(screen = s)
        val msg = firstPrompt(r)
        assertTrue(msg, msg.contains("已按以往成功的做法自动完成 1/3 步"))
        assertTrue(msg, msg.contains("1. phone_launch_app com.notes"))
        assertTrue(msg, msg.contains("第 2 步预期"))
        assertTrue(msg, msg.contains("请先登录"))
        // 带着一份全量读屏(编号与 snapshotId),模型可直接接着做
        assertTrue(msg, msg.contains("\"snapshotId\"") && msg.contains("\"ref\":\"@e4\""))
        // 读屏是一对调用与结果(助手的调用在前,结果紧跟),各家接口的配对要求都满足
        val h = r.main.requests.first().withoutRuntime()
        val callAt = h.indexOfFirst { m -> m.role == Role.Assistant && m.parts.any { it is Part.ToolCall && it.id == "replay_handback" && it.name == "phone_list_elements" } }
        assertTrue(callAt > 0)
        assertEquals("replay_handback", (h[callAt + 1].parts.single() as Part.ToolResult).callId)
        assertEquals(callAt + 2, h.size)
        assertEquals(listOf(true, false), r.sink.all.filterIsInstance<RunEvent.Replay>().map { it.matched })
        assertEquals(1, r.store.m["r1"]!!.consecutiveMisses)
        assertFalse(r.store.m["r1"]!!.disabled)
    }

    @Test fun executedButUnchangedStepToldToModel() {
        val s = Notes.screen().apply { links.clear() }
        val msg = firstPrompt(run(screen = s))
        assertTrue(msg, msg.contains("第 2 步已执行,但没看到预期变化"))
    }

    @Test fun unreadableHandbackScreenStillContinues() {
        val s = Notes.screen().apply { failReads = true }
        val r = run(screen = s)
        val msg = firstPrompt(r)
        assertTrue(msg, msg.contains("当前界面没能读出来"))
        assertEquals("succeeded", r.sink.all.filterIsInstance<RunEvent.TaskEnded>().single().outcome)
    }

    @Test fun completedStillRequiresAnAnswer() {
        // 走完了也要模型自己回答;回答核实过才算路线的一次成功:这个回答引用的「新笔记」读到过(字面核对对上了,不用另请核查)
        val ok = run(main = FakeModel(text("写好了,在「新笔记」里")), verifier = Verifier(FakeModel(text(pass)), null))
        assertTrue(firstPrompt(ok).contains("已按以往的做法走完全部步骤"))
        assertEquals(1, ok.main.requests.size)
        assertTrue(ok.sink.all.none { it is RunEvent.Verifier })
        assertEquals(1, ok.store.m["r1"]!!.successes)

        val gaveUp = run(main = FakeModel(call("end_not_done", """{"message":"没写上"}""")), verifier = Verifier(FakeModel(text(pass)), null))
        assertTrue(gaveUp.sink.all.none { it is RunEvent.Verifier })
        assertEquals(0, gaveUp.store.m["r1"]!!.successes)

        // 回答里没有可核对的值、也没请核查:没核对过,不记成功
        assertEquals(0, run().store.m["r1"]!!.successes)
    }

    @Test fun routeSuccessOnlyWhenJudgedDone() {
        // 回答核实过,但判下来只办了一部分、没办成或没判成:都不记成功
        for (o in listOf(RunOutcome.Partial, RunOutcome.NotPossible, RunOutcome.Unknown)) {
            val r = run(main = FakeModel(text("写好了,在「新笔记」里")), verifier = Verifier(FakeModel(text(pass)), null), outcome = o)
            assertEquals(o.wire, 0, r.store.m["r1"]!!.successes)
        }
    }

    @Test fun twoMissesAtSameStepDisableRoute() {
        val store = MemRoutes(Notes.route())
        fun broken() = Notes.screen().apply { pages["home"] = listOf(ui("@e4", "text", "请先登录", listOf(0, 0, 999, 60), clickable = false)) }
        run(screen = broken(), store = store)
        assertFalse(store.m["r1"]!!.disabled)
        run(screen = broken(), store = store)
        assertTrue(store.m["r1"]!!.disabled)
        // 停用之后不再是候选:匹配模型不会被调用
        val m = FakeModel()
        run(store = store, matcher = m)
        assertTrue(m.requests.isEmpty())
    }

    @Test fun replayOffNoMatcherCall() {
        val m = FakeModel()
        val r = run(matcher = m, flags = SpeedFlags.OFF)
        assertTrue(m.requests.isEmpty())
        assertTrue(r.sink.all.none { it is RunEvent.Replay })
        assertTrue(r.screen.typed.isEmpty())
    }

    @Test fun noModelCallsDuringReplay() {
        val s = Notes.screen()
        var typedWhenFirstAsked: List<String>? = null
        // 主模型一被调用就记下当时端口收到的输入:回放全程不碰主模型
        val main = object : ChatModel by FakeModel() {
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                if (typedWhenFirstAsked == null) typedWhenFirstAsked = s.typed.toList()
                return finish
            }
        }
        val sink = Events()
        val matcher = FakeModel(text(pick))
        runBlocking {
            AgentLoop(main, toolDispatcher(s), "BASE", sink = sink, flags = SpeedFlags(replay = true),
                routes = RouteMatcher(matcher, MemRoutes(Notes.route()), null)).run(goal)
        }
        assertEquals(listOf("今天天气不错"), typedWhenFirstAsked)
        assertEquals(1, matcher.requests.size)
        val firstReply = sink.all.indexOfFirst { it is RunEvent.ModelReply }
        assertTrue(sink.all.withIndex().filter { it.value is RunEvent.Replay }.all { it.index < firstReply })
    }
}
