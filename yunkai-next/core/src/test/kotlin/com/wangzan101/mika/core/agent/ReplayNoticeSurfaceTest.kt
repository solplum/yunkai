package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.replay.MemRoutes
import com.wangzan101.mika.core.replay.Notes
import com.wangzan101.mika.core.replay.PagedScreen
import com.wangzan101.mika.core.replay.RouteMatcher
import com.wangzan101.mika.core.replay.RouteStep
import com.wangzan101.mika.core.replay.Selector
import com.wangzan101.mika.core.replay.ui
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.testing.PromptSurface
import kotlinx.coroutines.runBlocking
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

/**
 * 经验回放交还时告诉模型的说明,每种结局一条,经真的 AgentLoop 产生,原样写进金样(prompt-surface/feedback-replay.txt)。
 * 金样是在重构前的代码上生成的:说明改由反馈目录拼出后必须一字不差。
 */
class ReplayNoticeSurfaceTest {
    private var seq = 0
    private fun call(name: String, args: String = "{}") = ModelReply(listOf(Part.ToolCall("m${++seq}", name, args)), StopReason.ToolUse, null)
    private fun text(s: String) = ModelReply(listOf(Part.Text(s)), StopReason.EndTurn, null)

    private class FailingTap(base: PagedScreen) : ScreenPort by base {
        override suspend fun tapRef(ref: String, snapshotId: String): ActionResult = ActionResult.Failed("obscured")
    }

    /** 交还时补进对话的说明(带「按以往」的那段文字)。 */
    private fun notice(screen: ScreenPort, route: com.wangzan101.mika.core.replay.Route = Notes.route()): String {
        val main = FakeModel(text("写好了"))
        runBlocking {
            AgentLoop(main, toolDispatcher(screen), "BASE", flags = SpeedFlags(replay = true),
                routes = RouteMatcher(FakeModel(text("""{"route": 1, "slots": ["今天天气不错"]}""")), MemRoutes(route), null)).run("在备忘录里写今天天气不错")
        }
        return main.requests.first().flatMap { it.parts }.filterIsInstance<Part.Text>().map { it.text }.single { "按以往" in it }
    }

    @Test fun everyReplayOutcome() {
        val items = mutableListOf<Pair<String, String>>()
        items += "completed" to notice(Notes.screen())
        items += "diverged, not executed" to notice(Notes.screen().apply {
            pages["home"] = listOf(ui("@e4", "text", "请先登录", listOf(0, 0, 999, 60), clickable = false))
        })
        items += "diverged, executed without effect" to notice(Notes.screen().apply { links.clear() })
        items += "diverged with error code" to notice(FailingTap(Notes.screen()))
        val send = Notes.steps.take(1) + RouteStep("phone_tap", "{}", Selector("button", "提交订单", "n:id/send", listOf(0, 900, 300, 999), 0, true), Notes.homeA, Notes.editorA)
        items += "stopped for confirmation" to notice(Notes.screen().apply {
            pages["home"] = Notes.home + ui("@e5", "button", "提交订单", listOf(0, 900, 300, 999), rid = "n:id/send")
        }, Notes.route(send))
        PromptSurface.check("feedback-replay", PromptSurface.entries(items))
    }
}
