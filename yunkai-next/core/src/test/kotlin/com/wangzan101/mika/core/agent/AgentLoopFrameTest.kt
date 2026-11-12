// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

/** 能看图的主模型:动作后自动附图;历史里的画面在裁剪线之前换成可回看的占位。 */
class AgentLoopFrameTest {
    private var seq = 0
    private fun call(name: String, args: String = "{}") = Part.ToolCall("f${++seq}", name, args)
    private fun tools(c: Part.ToolCall) = ModelReply(listOf(c), StopReason.ToolUse, null)
    private fun finish() = com.wangzan101.mika.core.testing.answerReply("done")
    private fun images(msgs: List<Message>) = msgs.flatMap { it.parts }.filterIsInstance<Part.Image>()

    @Test fun actionResultFollowedByFrameAndOnlyLatestImageSent() = runTest {
        val tap = call("phone_tap", """{"x":500,"y":50}""")
        val m = FakeModel(tools(call("phone_observe")), tools(tap), *(1..5).map { tools(call("phone_tap", """{"x":${it * 100},"y":50}""")) }.toTypedArray(), finish())
        AgentLoop(m, toolDispatcher(FakeScreen(), visionEnabled = true), "BASE").run("t")
        val msgs = m.requests[2]
        val at = msgs.indexOfFirst { it.parts.any { p -> p is Part.ToolResult && p.callId == tap.id } }
        assertEquals(Role.Tool, msgs[at].role)
        // 图紧跟在工具结果后面,用和 observe 一样的用户消息(各家接口的调用/结果配对不受影响)
        val frame = msgs[at + 1]
        assertEquals(Role.User, frame.role)
        val img = frame.parts.filterIsInstance<Part.Image>().single()
        assertEquals(2, img.screenshotId)
        assertTrue("\"screenshotId\":2" in (msgs[at].parts.single() as Part.ToolResult).content)
        // 发过的画面不再换成占位:裁剪线越过前 5 次往返之后,那几张也原样留着(请求的开头不变,缓存照样命中)
        assertEquals(listOf<Int?>(1, 2), images(msgs).map { it.screenshotId })
        assertEquals((1..6).toList(), images(m.requests[6]).map { it.screenshotId })
        val seventh = m.requests[7]
        assertEquals((1..7).toList(), images(seventh).map { it.screenshotId })
        assertTrue(seventh.none { msg -> msg.parts.any { it is Part.Text && it.text.startsWith("(更早的画面已省略") } })
    }

    @Test fun textOnlyMainGetsNoFrames() = runTest {
        val m = FakeModel(tools(call("phone_observe")), tools(call("phone_tap", """{"x":500,"y":50}""")), finish())
        AgentLoop(m, toolDispatcher(FakeScreen()), "BASE").run("t")
        assertTrue("images in requests: ${m.requests.map { images(it).map(Part.Image::screenshotId) }}", m.requests.all { images(it).isEmpty() })
        assertEquals(5, m.requests[2].withoutRuntime().size)
    }

    @Test fun adaptiveVisionDoesNotSendReadableScreenImagesToModel() = runTest {
        val m = FakeModel(tools(call("phone_observe")), tools(call("phone_back")), finish())
        AgentLoop(m, toolDispatcher(FakeScreen(), visionEnabled = true, adaptiveVision = true, inlineScreen = true), "BASE").run("t")
        assertTrue(m.requests.all { images(it).isEmpty() })
        assertTrue(m.requests[2].any { message -> message.parts.any { part ->
            part is Part.ToolResult && "\"screen\"" in part.content
        } })
    }

    @Test fun batchEndAttachesOneFrame() = runTest {
        val batch = call("do_steps", """{"steps":[{"tool":"phone_back","args":{}},{"tool":"phone_back","args":{}}]}""")
        val m = FakeModel(tools(batch), finish())
        AgentLoop(m, toolDispatcher(FakeScreen(), visionEnabled = true), "BASE", flags = SpeedFlags(batchSteps = true)).run("t")
        val msgs = m.requests[1]
        val at = msgs.indexOfFirst { it.parts.any { p -> p is Part.ToolResult && p.callId == batch.id } }
        assertEquals(Role.User, msgs[at + 1].role)
        assertEquals(1, images(msgs).size)
        assertEquals(images(msgs).single(), msgs[at + 1].parts.filterIsInstance<Part.Image>().single())
    }

    @Test fun adaptiveBatchExecutesTwoActionsInOneModelRoundWithoutImages() = runTest {
        val screen = FakeScreen().apply { result = com.wangzan101.mika.core.uistate.ActionResult.Ok(changed = true) }
        val batch = call("do_steps", """{"steps":[{"tool":"phone_back","args":{}},{"tool":"phone_back","args":{}}]}""")
        val m = FakeModel(tools(batch), finish())
        AgentLoop(m, toolDispatcher(screen, visionEnabled = true, adaptiveVision = true, inlineScreen = true), "BASE",
            flags = SpeedFlags(batchSteps = true, inlineScreen = true, adaptiveVision = true)).run("t")
        assertEquals(2, m.requests.size)
        assertEquals(listOf("back", "back"), screen.calls)
        assertTrue("images in requests: ${m.requests.map { images(it).map(Part.Image::screenshotId) }}", m.requests.all { images(it).isEmpty() })
    }
}
