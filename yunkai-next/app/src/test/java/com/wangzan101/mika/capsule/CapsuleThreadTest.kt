package com.wangzan101.mika.capsule

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState
import com.wangzan101.mika.ui.Exchange
import com.wangzan101.mika.ui.ExchangeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleThreadTest {
    @get:Rule val texts = ChineseTexts()

    private fun turn(user: String, outcome: String, status: String = "succeeded", runId: String? = null, steers: List<String> = emptyList()) =
        Turn(user, outcome, status, runId = runId, steers = steers)

    private fun live(key: String, state: TaskState, kind: CapsuleKind, runId: String? = null, draft: String? = null, line: String = "") = CardRun(
        CapsuleRun(key, "c1", state, goal = "问题$key", runId = runId,
            exchange = Exchange("问题$key", emptyList(), ExchangeStatus.Working, live = true, draft = draft)),
        kind, line,
    )

    private fun texts(t: ChatThread) = t.items.map {
        when (it) {
            is ThreadItem.User -> "你:${it.text}"
            is ThreadItem.Note -> "注:${it.text}"
            is ThreadItem.Answer -> "答:${it.text}"
        }
    }

    // ---------- 一轮怎么排 ----------

    @Test fun chatTurnIsBubbleAndFullAnswer() {
        // 回答原样留着 Markdown,卡片里排版
        val t = CapsuleThreads.build(listOf(PastTurn(turn("在吗", "# 在\n\n**有事**请说\n\n| 项 | 值 |\n|---|---|\n| a | 1 |\n"))), 10, emptyList())
        assertEquals(listOf("你:在吗", "答:# 在\n\n**有事**请说\n\n| 项 | 值 |\n|---|---|\n| a | 1 |"), texts(t))
        assertEquals("在吗", t.title)
    }

    @Test fun appTurnCollapsesToOneLineThenItsAnswer() {
        val past = listOf(
            PastTurn(turn("买牙膏", "下单了，共 ¥19.9", runId = "r1"), places = listOf("购物")),
            PastTurn(turn("查车票", "没有余票", status = "failed", runId = "r2"), places = listOf("12306", "浏览器", "地图")),
            PastTurn(turn("发消息", "已取消", status = "cancelled"), places = listOf("聊天")),
        )
        assertEquals(listOf(
            "你:买牙膏", "注:✓ 购物 · 办好了", "答:下单了，共 ¥19.9",
            "你:查车票", "注:12306、浏览器 等 · 没办成", "答:没有余票",
            "你:发消息", "注:聊天 · 已停止",
        ), texts(CapsuleThreads.build(past, 10, emptyList())))
    }

    @Test fun plainChatFailuresSayItAndSteersAreBubbles() {
        val past = listOf(
            PastTurn(turn("算一下", "模型没回", status = "failed")),
            PastTurn(turn("写首诗", "已取消", status = "cancelled", steers = listOf("短一点"))),
        )
        assertEquals(listOf("你:算一下", "注:没办成", "答:模型没回", "你:写首诗", "你:短一点", "注:已停止"), texts(CapsuleThreads.build(past, 10, emptyList())))
    }

    // 停了的那一轮:「已停止」那一行下面是交代;交代还在写时是「正在整理已做的事…」
    @Test fun stoppedTurnShowsItsSummaryUnderTheNote() {
        val past = listOf(
            PastTurn(turn("看设置", "停止前看完了网络、蓝牙;显示、声音还没看。", status = "cancelled", runId = "r1"), places = listOf("设置")),
            PastTurn(turn("写首诗", "已取消", status = "cancelled", runId = "r2")),
        )
        assertEquals(listOf(
            "你:看设置", "注:设置 · 已停止", "答:停止前看完了网络、蓝牙;显示、声音还没看。",
            "你:写首诗", "注:已停止", "注:正在整理已做的事…",
        ), texts(CapsuleThreads.build(past, 10, emptyList(), summarizing = setOf("r2"))))
        // 写不成:只留那一行
        assertEquals(listOf("你:写首诗", "注:已停止"), texts(CapsuleThreads.build(past.drop(1), 10, emptyList())))
        // 还没记进对话的那一件也一样
        val r = live("a", TaskState.Cancelled, CapsuleKind.Cancelled, runId = "r3")
        assertEquals(listOf("你:问题a", "注:已停止", "注:正在整理已做的事…"),
            texts(CapsuleThreads.build(emptyList(), 10, listOf(r), summarizing = setOf("r3"))))
    }

    @Test fun oldEvidenceSectionIsDropped() {
        val t = CapsuleThreads.build(listOf(PastTurn(turn("几点开门", "九点开门\n依据：官网"))), 10, emptyList())
        assertEquals(listOf("你:几点开门", "答:九点开门"), texts(t))
    }

    @Test fun placesAreAppsAndSitesInOrder() {
        fun act(target: String?) = RunEvent.Activity(0, 1, "act", "tap", target)
        val acts = listOf(act("com.shop.app"), act("网站 example.com"), act("com.shop.app"), act("ls -l"), act(null), act("/workspace/a.txt"))
        assertEquals(listOf("购物", "example.com"), CapsuleThreads.places(acts) { if (it == "com.shop.app") "购物" else it })
    }

    // ---------- 进行中的那一件 ----------

    @Test fun streamingAnswerGrowsAfterTheQuestion() {
        val r = live("a", TaskState.Running(1, "n"), CapsuleKind.Running, runId = "ra", draft = "**今天**晴")
        val t = CapsuleThreads.build(listOf(PastTurn(turn("早", "早上好", runId = "r0"))), 10, listOf(r))
        assertEquals(listOf("你:早", "答:早上好", "你:问题a", "答:**今天**晴"), texts(t))
        assertTrue((t.items.last() as ThreadItem.Answer).streaming)
        // 还没写出字:说此刻在做什么
        val thinking = live("a", TaskState.Running(1, "n"), CapsuleKind.Running, line = "在想下一步")
        assertEquals(listOf("你:问题a", "注:在想下一步"), texts(CapsuleThreads.build(emptyList(), 10, listOf(thinking))))
    }

    @Test fun liveWaitsShowTheQuestionAndAppLine() {
        val ask = live("a", TaskState.WaitingInput("要大的还是小的？"), CapsuleKind.NeedAnswer)
        assertEquals(listOf("你:问题a", "答:要大的还是小的？"), texts(CapsuleThreads.build(emptyList(), 10, listOf(ask))))
        val inApp = live("b", TaskState.Running(2, "n"), CapsuleKind.Running, draft = "在看")
        assertEquals(listOf("你:问题b", "注:购物 · 在干活", "答:在看"),
            texts(CapsuleThreads.build(emptyList(), 10, listOf(inApp)) { listOf("购物") }))
        val done = live("c", TaskState.Succeeded("好了"), CapsuleKind.Done)
        assertEquals(listOf("你:问题c", "注:✓ 购物 · 办好了", "答:好了"),
            texts(CapsuleThreads.build(emptyList(), 10, listOf(done)) { listOf("购物") }))
    }

    @Test fun liveTailSkipsWhatIsAlreadyRecorded() {
        val recorded = live("a", TaskState.Succeeded("好了"), CapsuleKind.Done, runId = "r1")
        val notYet = live("b", TaskState.Failed("x", FailKind.Model), CapsuleKind.Failed, runId = "r2")
        val queuedCancel = live("c", TaskState.Cancelled, CapsuleKind.Cancelled)
        val going = live("d", TaskState.Running(1, "n"), CapsuleKind.Running)
        val other = live("e", TaskState.Running(1, "n"), CapsuleKind.Running).let { it.copy(run = it.run.copy(conversationId = "c2")) }
        // 卡片里结束的按最近在前排;来回里先结束的在上,进行中的最后
        val tail = CapsuleThreads.liveTail(listOf(going, other, notYet, recorded, queuedCancel), "c1", setOf("r1"))
        assertEquals(listOf("b", "d"), tail.map { it.run.key })
    }

    @Test fun keysStayWhenTheLiveTurnGetsRecorded() {
        val r = live("a", TaskState.Succeeded("好了"), CapsuleKind.Done, runId = "r9")
        val before = CapsuleThreads.build(emptyList(), 10, listOf(r)).items.map { it.key }
        val after = CapsuleThreads.build(listOf(PastTurn(turn("问题a", "好了", runId = "r9"))), 10, emptyList()).items.map { it.key }
        assertEquals(before, after)
    }

    // ---------- 分页与打开时停在哪 ----------

    @Test fun latestTenTurnsFirstThenEarlier() {
        val past = (1..25).map { PastTurn(turn("问$it", "答$it", runId = "r$it")) }
        val first = CapsuleThreads.build(past, CapsuleThreads.PAGE, emptyList())
        assertTrue(first.hasEarlier)
        assertEquals("你:问16", texts(first).first())
        assertEquals("问1", first.title)
        val more = CapsuleThreads.build(past, CapsuleThreads.PAGE * 2, emptyList())
        assertEquals("你:问6", texts(more).first())
        // 往前加的几轮都在前面:原来那几条的 key 不变,列表据此停在原处
        assertEquals(first.items.map { it.key }, more.items.takeLast(first.items.size).map { it.key })
        val all = CapsuleThreads.build(past, CapsuleThreads.PAGE * 3, emptyList())
        assertTrue(!all.hasEarlier)
        assertEquals(50, all.items.size)
    }

    @Test fun anchorIsTheLatestQuestion() {
        val past = listOf(PastTurn(turn("一", "甲")), PastTurn(turn("二", "乙\n很长的回答")))
        val t = CapsuleThreads.build(past, 10, emptyList())
        assertEquals(2, t.anchor)
        assertEquals(ThreadItem.User("p1-u0", "二"), t.items[t.anchor])
        // 进行中的那一问在最后:从它读起
        val going = CapsuleThreads.build(past, 10, listOf(live("a", TaskState.Running(1, "n"), CapsuleKind.Running, draft = "在写")))
        assertEquals("你:问题a", texts(going)[going.anchor])
        assertEquals(0, ChatThread(emptyList()).anchor)
    }

    // 进行中插的话接在原话后面,下面写着去向(和对话页同一套说法);最新那句是打开时的落点
    @Test fun liveSteersFollowTheGoalWithWhereTheyWent() {
        val base = live("a", TaskState.Running(1, "n"), CapsuleKind.Running, draft = "在写")
        val run = base.copy(run = base.run.copy(steers = listOf(
            SteerNote("s1", "改成后天", SteerState.Joined),
            SteerNote("s2", "顺便订午饭", SteerState.Received),
        )))
        val t = CapsuleThreads.build(emptyList(), 10, listOf(run))
        val users = t.items.filterIsInstance<ThreadItem.User>()
        assertEquals(listOf("问题a", "改成后天", "顺便订午饭"), users.map { it.text })
        assertEquals(listOf(null, "已加入当前任务", "已收到，下一步处理"), users.map { it.note })
        assertEquals("你:顺便订午饭", texts(t)[t.anchor])
        assertEquals(t.items.size, t.items.map { it.key }.toSet().size)
    }
}
