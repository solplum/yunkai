package com.wangzan101.mika.core.replay

import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

class AgentLoopActionTargetTest {
    private var seq = 0
    private fun call(name: String, args: String = "{}") = Part.ToolCall("a${++seq}", name, args)
    private fun reply(c: Part.ToolCall) = ModelReply(listOf(c), StopReason.ToolUse, null)

    private fun events(flags: SpeedFlags, result: ActionResult): List<RunEvent> {
        val screen = FakeScreen().also { it.result = result }
        val m = FakeModel(
            reply(call("phone_observe")),
            reply(call("phone_tap", """{"ref":"@e1","snapshotId":"snap1"}""")),
            com.wangzan101.mika.core.testing.answerReply("好了"),
        )
        val got = mutableListOf<RunEvent>()
        val sink = object : RunSink { override fun event(e: RunEvent) { got += e }; override fun media(mediaId: Int, png: ByteArray) {} }
        runBlocking { AgentLoop(m, toolDispatcher(screen), "BASE", sink = sink, confirmations = null, flags = flags).run("点搜索") }
        return got
    }

    @Test fun emitsActionTargetAfterWriteWhenReplayOn() {
        val t = events(SpeedFlags(replay = true), ActionResult.Ok(changed = true)).filterIsInstance<RunEvent.ActionTarget>().single()
        assertEquals("phone_tap", t.tool); assertEquals(2, t.step); assertTrue(t.effective)
        assertEquals(Selector("button", "搜索", "a:id/s", listOf(0, 0, 999, 100), 0, uniqueAtRecord = true), t.target)
        assertEquals("com.x", t.before.packageName)
        // 和同一调用的工具结果按调用 id 配对
        val all = events(SpeedFlags(replay = true), ActionResult.Ok(changed = true))
        val at = all.filterIsInstance<RunEvent.ActionTarget>().single()
        assertTrue(at.callId != null)
        assertEquals(at.callId, all.filterIsInstance<RunEvent.ToolResult>().single { it.tool == "phone_tap" }.callId)
    }

    @Test fun ineffectiveWriteMarked() {
        assertFalse(events(SpeedFlags(replay = true), ActionResult.Ok(changed = false)).filterIsInstance<RunEvent.ActionTarget>().single().effective)
    }

    @Test fun nothingWhenReplayOff() {
        assertTrue(events(SpeedFlags.OFF, ActionResult.Ok(changed = true)).none { it is RunEvent.ActionTarget })
    }

    @Test fun batchSubStepsEmitActionTargetsAndFrames() {
        val m = FakeModel(
            reply(call("phone_observe")),
            reply(Part.ToolCall("b", "do_steps", """{"steps":[{"tool":"phone_tap","args":{"ref":"@e1","snapshotId":"p1"}},{"tool":"phone_back","args":{}},{"tool":"wait","args":{"seconds":0.2}}]}""")),
            com.wangzan101.mika.core.testing.answerReply("好了"),
        )
        val got = mutableListOf<RunEvent>()
        val shots = mutableListOf<Int>()
        val sink = object : RunSink { override fun event(e: RunEvent) { got += e }; override fun media(mediaId: Int, png: ByteArray) { shots += mediaId } }
        val screen = FakeScreen().also { it.result = ActionResult.Ok(changed = true) }
        runBlocking {
            AgentLoop(m, toolDispatcher(screen, recordFrames = true, inlineScreen = true), "BASE", sink = sink, confirmations = null,
                flags = SpeedFlags(replay = true, batchSteps = true, inlineScreen = true)).run("点搜索")
        }
        val targets = got.filterIsInstance<RunEvent.ActionTarget>().filter { it.source == "batch" }
        assertEquals(listOf("phone_tap", "phone_back"), targets.map { it.tool })
        assertEquals(listOf("b.1", "b.2"), targets.map { it.callId })
        assertEquals("搜索", targets[0].target!!.name)
        assertFalse(targets.any { it.incomplete })
        // 界面变了的子步各存一张画面(wait 没有屏幕),工具结果里带编号
        val subResults = got.filterIsInstance<RunEvent.ToolResult>().filter { it.source == "batch" }
        assertEquals(2, subResults.count { it.screenshotId != null })
        assertTrue(shots.containsAll(subResults.mapNotNull { it.screenshotId }))
        assertEquals(subResults.mapNotNull { it.screenshotId }, got.filterIsInstance<RunEvent.Media>().map { it.mediaId }.filter { it in subResults.mapNotNull { r -> r.screenshotId } })
    }
}
