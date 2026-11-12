package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

private class Named(override val name: String, private val inner: FakeModel) : ChatModel by inner {
    val requests get() = inner.requests
    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply = inner.complete(system, messages, tools)
}

private class Events : RunSink {
    val list = mutableListOf<RunEvent>()
    override fun event(e: RunEvent) { list += e }
}

private var seq = 0
private fun call(name: String, args: String = "{}") = Part.ToolCall("l${++seq}", name, args)
private fun tools(vararg calls: Part.ToolCall) = ModelReply(calls.toList(), StopReason.ToolUse, null)
private fun finish() = com.wangzan101.mika.core.testing.answerReply("done")

/** 屏上只有一个「发送」按钮。 */
private class SendScreen(private val label: String = "发送", private val inner: FakeScreen = FakeScreen()) : com.wangzan101.mika.core.uistate.ScreenPort by inner {
    override suspend fun snapshot() = ScreenSnapshot("s1", "com.x",
        listOf(UiElement("@e1", "button", label, 500, 500, listOf(400, 450, 600, 550), null, true, false, false)), 1080, 2400)
}

class AgentLoopLightTest {
    private val on = SpeedFlags(lightSteps = true)

    private fun loop(main: ChatModel, light: ChatModel, sink: RunSink = RunSink.NONE, screen: com.wangzan101.mika.core.uistate.ScreenPort = FakeScreen()) =
        AgentLoop(main, toolDispatcher(screen), "BASE", sink = sink, flags = on, router = ModelRouter(main, light))

    @Test fun cleanStepsGoToLightAndAreRecorded() = runTest {
        // 第 1 步主模型;第 2 步前台 App 只见过一次,主模型;第 3 步起两步同 App、无错 → 轻量
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val light = Named("light", FakeModel(tools(call("phone_observe")), finish()))
        val sink = Events()
        val r = loop(main, light, sink).run("t")
        assertTrue(r is TaskState.Succeeded)
        // 第 4 步轻量模型想交结果,改问主模型
        assertEquals(2, light.requests.size)
        assertEquals(3, main.requests.size)
        val replies = sink.list.filterIsInstance<RunEvent.ModelReply>()
        assertEquals(listOf("main", "main", "light", "light_rejected", "main"), replies.map { it.role })
        assertEquals(listOf("main", "main", "light", "light", "main"), replies.map { it.model })
    }

    // 不带调用的回复是最终回答,只由主模型给:轻量模型这样回就改问主模型
    @Test fun lightAnswerIsAskedAgainOfMain() = runTest {
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val light = Named("light", FakeModel(com.wangzan101.mika.core.testing.answerReply("LIGHT_ANSWER")))
        val sink = Events()
        val r = loop(main, light, sink).run("t")
        assertTrue(r is TaskState.Succeeded)
        assertEquals(1, light.requests.size)
        assertEquals(3, main.requests.size)
        assertTrue(sink.list.any { it is RunEvent.State && it.name == "light_to_main" && it.detail == "no_tool" })
        assertEquals(listOf("main", "main", "light_rejected", "main"), sink.list.filterIsInstance<RunEvent.ModelReply>().map { it.role })
        // 改派后主模型看到的历史里没有那条没执行的回复
        val asked = main.requests.last()
        assertTrue(asked.flatMap { it.parts }.filterIsInstance<Part.Text>().none { "LIGHT_ANSWER" in it.text })
        assertEquals(asked.size, light.requests.single().size)
    }

    @Test fun lightCoordinateAndLocatedTapsGoToMain() = runTest {
        for (args in listOf("""{"x":500,"y":500}""", """{"ref":"@v1"}""", """{"x":900,"y":900}""")) {
            val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), tools(call("end_not_done", """{"message":"不点"}"""))))
            val light = Named("light", FakeModel(tools(call("phone_tap", args))))
            val sink = Events()
            loop(main, light, sink, SendScreen()).run("t")
            assertTrue(args, sink.list.any { it is RunEvent.State && it.name == "light_to_main" && it.detail == "phone_tap" })
            assertTrue(args, sink.list.filterIsInstance<RunEvent.ToolResult>().none { it.tool == "phone_tap" })
        }
    }

    @Test fun confirmationBlockedStepForcesMain() = runTest {
        // 第 2 步主模型点「提交订单」被确认关卡拦下:之后两步主模型,第 5 步才轮到轻量模型
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_tap", """{"ref":"@e1","snapshotId":"s1"}""")),
            tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val light = Named("light", FakeModel(tools(call("phone_observe")), finish()))
        val sink = Events()
        loop(main, light, sink, SendScreen("提交订单")).run("t")
        assertEquals(listOf("main", "main", "main", "main", "light", "light_rejected", "main"),
            sink.list.filterIsInstance<RunEvent.ModelReply>().map { it.role })
        assertTrue((sink.list.filterIsInstance<RunEvent.ToolResult>().first { it.tool == "phone_tap" }).resultJson.contains("confirmation_required"))
    }

    @Test fun permanentLightErrorTurnsRoutingOff() = runTest {
        val main = Named("main", FakeModel(*Array(6) { tools(call("phone_observe")) }, finish()))
        val light = Named("light", FakeModel(com.wangzan101.mika.core.model.ModelError.Auth()))
        val sink = Events()
        val r = loop(main, light, sink).run("t")
        assertTrue(r is TaskState.Succeeded)
        assertEquals(1, light.requests.size)
        assertEquals(1, sink.list.filterIsInstance<RunEvent.ModelReply>().count { it.role == "light_rejected" })
    }

    @Test fun otherModelsThinkingAndOpaqueAreNotSentAcross() = runTest {
        val mine = Part.ToolCall("m1", "phone_observe", "{}", opaque = "sigM")
        val theirs = Part.ToolCall("x1", "phone_observe", "{}", opaque = "sigL")
        val main = Named("main", FakeModel(ModelReply(listOf(Part.Thinking("tm"), mine), StopReason.ToolUse, null),
            tools(call("phone_observe")), tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val light = Named("light", FakeModel(ModelReply(listOf(Part.Thinking("tl"), theirs), StopReason.ToolUse, null), finish()))
        loop(main, light).run("t")
        // 第 3 步轻量模型;第 4 步它要交结果,改问主模型:轻量模型那条去掉思考和私有数据,主模型自己的原样
        val toMain = main.requests[2].filter { it.role == com.wangzan101.mika.core.model.Role.Assistant }.flatMap { it.parts }
        assertTrue(mine in toMain && Part.Thinking("tm") in toMain)
        assertTrue(theirs.copy(opaque = null) in toMain && Part.Thinking("tl") !in toMain)
        val toLight = light.requests.first().filter { it.role == com.wangzan101.mika.core.model.Role.Assistant }.flatMap { it.parts }
        assertTrue(mine.copy(opaque = null) in toLight && toLight.none { it is Part.Thinking })
    }

    @Test fun lightIrreversibleTapIsAskedAgainOfMain() = runTest {
        val tapSend = """{"ref":"@e1","snapshotId":"s1"}"""
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), tools(call("end_not_done", """{"message":"不发"}"""))))
        val light = Named("light", FakeModel(tools(call("phone_tap", tapSend))))
        val sink = Events()
        loop(main, light, sink, SendScreen()).run("t")
        assertEquals(1, light.requests.size)
        assertTrue(sink.list.any { it is RunEvent.State && it.name == "light_to_main" && it.detail == "phone_tap" })
        assertTrue(sink.list.filterIsInstance<RunEvent.ToolResult>().none { it.tool == "phone_tap" })
    }

    @Test fun screenshotsNeverGoToTextOnlyLight() = runTest {
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val light = Named("light", FakeModel())
        // 主模型看图:observe 附截图,轻量模型不看图就不接
        val seeing = object : ChatModel by main {
            override val capabilities = main.capabilities.copy(vision = true)
        }
        AgentLoop(seeing, toolDispatcher(FakeScreen(), visionEnabled = true), "BASE", flags = on,
            router = ModelRouter(seeing, light)).run("t")
        assertEquals(0, light.requests.size)
    }

    @Test fun lightErrorFallsBackToMain() = runTest {
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val light = Named("light", FakeModel(com.wangzan101.mika.core.model.ModelError.Network()))
        val r = loop(main, light).run("t")
        assertTrue(r is TaskState.Succeeded)
        // 轻量模型出错不重试,这一步直接改问主模型
        assertEquals(1, light.requests.size)
        assertEquals(3, main.requests.size)
    }

    @Test fun light503FallsBackImmediately() = runTest {
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val light = Named("light", FakeModel(com.wangzan101.mika.core.model.ModelError.Server(503)))
        val r = loop(main, light).run("t")
        assertTrue(r is TaskState.Succeeded)
        // 503 没有重发给轻量模型,这一步由主模型接
        assertEquals(1, light.requests.size)
        assertEquals(3, main.requests.size)
    }
}
