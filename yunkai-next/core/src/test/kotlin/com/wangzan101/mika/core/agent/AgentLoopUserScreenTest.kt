package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.userscreen.ScreenLine
import com.wangzan101.mika.core.userscreen.UserScreen
import com.wangzan101.mika.core.userscreen.UserScreenMessage
import com.wangzan101.mika.core.userscreen.UserScreenSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** 用户发来此刻的屏幕时,模型看到的样子:那句话、截图、详情;之前几轮里的只剩一行;只发屏幕时不问意图判断。 */
class AgentLoopUserScreenTest {
    private val at = 1_761_624_120_000L // 2025-10-28 04:02 UTC
    private val chat = UserScreen("s1", at, "com.example.chat", "聊天", lines = listOf(
        ScreenLine("周六那个聚会你还去吗", 300, 200), ScreenLine("应该去吧,怎么了", 700, 260),
    ))
    private val bank = UserScreen("s2", at, "com.example.bank", "银行", blocked = true)
    private val png = byteArrayOf(1, 2, 3)

    private val source = object : UserScreenSource {
        override suspend fun screen(id: String) = listOf(chat, bank).firstOrNull { it.id == id }
        override suspend fun image(id: String) = png.takeIf { id == "s1" }
    }

    private fun loop(model: FakeModel, goal: String, vision: Boolean = false, prior: List<Turn> = emptyList(), judge: FakeModel? = null,
        events: MutableList<RunEvent> = mutableListOf()): AgentLoop {
        val spec = RunSpec(RunId("r"), goal, "c", prior)
        val tools = toolDispatcher(FakeScreen(), visionEnabled = vision, runSpec = spec, userScreens = source)
        return AgentLoop(model, tools, "B", zone = ZoneOffset.UTC, userScreens = source, intentJudge = judge,
            sink = object : RunSink { override fun event(e: RunEvent) { events += e } })
    }

    @Test fun screenWithoutWordsBecomesSentenceImageAndDetail() = runBlocking {
        val model = FakeModel(answerReply("好"))
        val goal = UserScreenMessage.compose("s1", "")
        loop(model, goal, vision = true).run(goal)
        val msgs = model.requests.first().withoutRuntime()
        val said = msgs.first { it.author == com.wangzan101.mika.core.model.Author.User }
        assertEquals("用户刚截下他正在看的屏幕(聊天,10月28日 04:02),没有附言。", (said.parts.first() as Part.Text).text)
        assertEquals(1, said.parts.count { it is Part.Image })
        val detail = msgs[msgs.indexOf(said) + 1]
        assertEquals(ContextKind.UserScreen, ContextBuilder.kindOf(detail))
        val body = ContextBuilder.bodyOf(detail)!!
        assertTrue(body.contains("屏幕编号:s1"))
        assertTrue(body.contains("(300,200) 周六那个聚会你还去吗"))
        assertTrue(body.contains("<untrusted>"))
        assertTrue("没有附言时带上按屏幕猜意图的规矩", body.contains("without words"))
    }

    @Test fun screenWithWordsHasNoExtraRuleAndNoImageWhenMainModelIsBlind() = runBlocking {
        val model = FakeModel(answerReply("好"))
        val goal = UserScreenMessage.compose("s1", "第二句是谁说的")
        loop(model, goal, vision = false).run(goal)
        val msgs = model.requests.first().withoutRuntime()
        val said = msgs.first { it.author == com.wangzan101.mika.core.model.Author.User }
        assertEquals("用户刚截下他正在看的屏幕(聊天,10月28日 04:02),附言:第二句是谁说的", (said.parts.single() as Part.Text).text)
        val body = ContextBuilder.bodyOf(msgs[msgs.indexOf(said) + 1])!!
        assertFalse(body.contains("without words"))
        assertTrue(model.offeredTools.first().contains("view_user_screen"))
    }

    @Test fun blockedScreenSaysItCouldNotBeCaptured() = runBlocking {
        val model = FakeModel(answerReply("好"))
        val goal = UserScreenMessage.compose("s2", "")
        loop(model, goal, vision = true).run(goal)
        val msgs = model.requests.first().withoutRuntime()
        val said = msgs.first { it.author == com.wangzan101.mika.core.model.Author.User }
        assertTrue((said.parts.single() as Part.Text).text.contains("不让截图"))
        assertTrue(ContextBuilder.bodyOf(msgs[msgs.indexOf(said) + 1])!!.contains("did not allow screenshots"))
    }

    @Test fun earlierScreensCollapseToOneLine() = runBlocking {
        val model = FakeModel(answerReply("好"))
        val prior = listOf(Turn(UserScreenMessage.compose("s1", "看看这个"), "她在提醒你", "succeeded"))
        loop(model, "那我怎么回", prior = prior).run("那我怎么回", prior)
        val first = model.requests.first().withoutRuntime().first().plainText()
        assertTrue(first.startsWith(Conversation.HEADER))
        assertTrue(first.contains("(用户之前发来的屏幕 s1:聊天,10月28日 04:02;屏上开头是「周六那个聚会你还去吗 应该去吧,怎么了」) 看看这个"))
        assertFalse(first.contains("[[screen:"))
        // 之前几轮里有屏幕:能按编号回看
        assertTrue(model.offeredTools.first().contains("view_user_screen"))
    }

    @Test fun noScreenNoTool() = runBlocking {
        val model = FakeModel(answerReply("好"))
        loop(model, "看看天气").run("看看天气")
        assertFalse(model.offeredTools.first().contains("view_user_screen"))
    }

    // 只发了屏幕:没有话可判,不问意图判断,提议的调用照常做
    @Test fun screenWithoutWordsSkipsIntentGate() = runBlocking {
        val main = FakeModel(ModelReply(listOf(Part.ToolCall("c1", "phone_list_apps", "{}")), StopReason.ToolUse, null), answerReply("好"))
        val judge = FakeModel(answerReply("DISCUSS"))
        val events = mutableListOf<RunEvent>()
        val goal = UserScreenMessage.compose("s1", "")
        loop(main, goal, judge = judge, events = events).run(goal)
        assertTrue(judge.requests.isEmpty())
        assertTrue(events.any { it is RunEvent.ToolResult && it.tool == "phone_list_apps" })
    }

    @Test fun viewUserScreenReturnsTextAndImage() = runBlocking {
        val main = FakeModel(ModelReply(listOf(Part.ToolCall("c1", "view_user_screen", """{"id":"s1"}""")), StopReason.ToolUse, null), answerReply("好"))
        val goal = UserScreenMessage.compose("s1", "")
        loop(main, goal, vision = true).run(goal)
        val result = main.requests[1].last { it.parts.any { p -> p is Part.ToolResult } }.parts.filterIsInstance<Part.ToolResult>().single()
        assertTrue(result.content.contains("周六那个聚会你还去吗"))
        assertTrue(result.content.contains("\"imageAttached\":true"))
    }
}
