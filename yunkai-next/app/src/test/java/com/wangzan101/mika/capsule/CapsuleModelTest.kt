// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.capsule

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.RunProgress
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.takeover.Takeover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleModelTest {
    @get:Rule val texts = ChineseTexts()

    private val model = CapsuleModel(reasonLabel = { if (it == "payment") "付款" else null })

    private fun inputs(
        vararg runs: CapsuleRun,
        mode: CapsuleMode = CapsuleMode.DuringTasks,
        foreground: Boolean = false,
        interactive: Boolean = true,
        service: Boolean = true,
        cardOpen: Boolean = false,
    ) = CapsuleInputs(runs.toList(), mode, foreground, interactive, service, cardOpen)

    private fun run(key: String, state: TaskState, stage: Takeover.Stage = Takeover.Stage.None) = CapsuleRun(key, "c-$key", state, stage)
    private fun running(said: String? = null, step: Int = 3, reason: String? = null, progress: RunProgress? = null) =
        TaskState.Running(step, "note", "tap", progress, reason, narration = said)
    private val takeover = TaskState.WaitingTakeover("payment", "请输入支付密码")
    private val confirm = TaskState.WaitingConfirmation(ConfirmationRequest(IrrevAction.send, "张三", "明天见", null, "com.chat"))

    private fun shown(v: CapsuleView): CapsuleView.Shown = v as? CapsuleView.Shown ?: error("expected shown, got $v")

    // ---------- 什么时候不显示 ----------

    @Test fun hiddenWhenOffForegroundLockedOrNoService() {
        val r = run("a", running("在搜牙膏"))
        assertEquals(CapsuleView.Hidden, model.update(inputs(r, mode = CapsuleMode.Off), 0))
        assertEquals(CapsuleView.Hidden, model.update(inputs(r, foreground = true), 0))
        assertEquals(CapsuleView.Hidden, model.update(inputs(r, interactive = false), 0))
        assertEquals(CapsuleView.Hidden, model.update(inputs(r, service = false), 0))
        assertEquals("在搜牙膏", shown(model.update(inputs(r), 0)).text)
    }

    @Test fun noTasksHiddenDuringTasksIdleIconWhenAlways() {
        assertEquals(CapsuleView.Hidden, model.update(inputs(), 0))
        assertEquals(CapsuleKind.Idle, shown(model.update(inputs(mode = CapsuleMode.Always), 0)).kind)
        // 一直显示也照样在 云开 界面里、锁屏时不显示
        assertEquals(CapsuleView.Hidden, model.update(inputs(mode = CapsuleMode.Always, foreground = true), 0))
        assertEquals(CapsuleView.Hidden, model.update(inputs(mode = CapsuleMode.Always, interactive = false), 0))
    }

    @Test fun modeParsesWithAlwaysDefault() {
        assertEquals(CapsuleMode.Always, CapsuleMode.DEFAULT)
        assertEquals(CapsuleMode.Always, CapsuleMode.parse(null))
        assertEquals(CapsuleMode.Always, CapsuleMode.parse("something-old"))
        CapsuleMode.entries.forEach { assertEquals(it, CapsuleMode.parse(it.name)) }
    }

    // ---------- 那一行字 ----------

    @Test fun runningTextNarrationThenReasonThenFallback() {
        assertEquals("在搜 牙膏", CapsuleModel.runningText(running("  在搜\n牙膏 ", reason = "找搜索框")))
        assertEquals("找搜索框", CapsuleModel.runningText(running(" ", reason = "找搜索框")))
        assertEquals(CapsuleModel.THINKING, CapsuleModel.runningText(running()))
        assertEquals(CapsuleModel.PREPARING, CapsuleModel.runningText(running(step = 0)))
        assertEquals("照着路线做 3/7", CapsuleModel.runningText(running("在搜牙膏", progress = RunProgress.Replay(3, 7))))
        assertEquals("一次做 4 步", CapsuleModel.runningText(running(progress = RunProgress.Batch(4))))
    }

    @Test fun eachStateHasItsLine() {
        fun line(state: TaskState, stage: Takeover.Stage = Takeover.Stage.None) =
            shown(CapsuleModel(reasonLabel = { if (it == "payment") "付款" else null }).update(inputs(run("a", state, stage)), 0))
        assertEquals(CapsuleKind.NeedTakeover to "需要你：付款", line(takeover, Takeover.Stage.Waiting).let { it.kind to it.text })
        assertEquals("需要你：请登录", line(TaskState.WaitingTakeover("login", "请登录"), Takeover.Stage.Waiting).text)
        assertEquals(CapsuleKind.NeedConfirm to "要你同意：发送", line(confirm).let { it.kind to it.text })
        assertEquals(CapsuleKind.NeedAnswer to "云开 问你：要哪个颜色？", line(TaskState.WaitingInput("要哪个颜色？")).let { it.kind to it.text })
        assertEquals(CapsuleKind.Held to "先等等：你打开了这个 App", line(TaskState.Held(GateReason("paused", "你打开了这个 App"))).let { it.kind to it.text })
        // 要你在手机上动一下手(在系统的选择框里选一个):算要你处理
        val pick = line(TaskState.Held(GateReason("waiting_user", "「聊天」在问用哪个，请在屏幕上选一下", needsYou = true)))
        assertEquals(CapsuleKind.NeedStep to "需要你：「聊天」在问用哪个，请在屏幕上选一下", pick.kind to pick.text)
        assertTrue(pick.kind.needsYou)
        assertEquals("先等等", line(TaskState.Held(GateReason("waiting_unlock"))).text)
        val bar = line(takeover, Takeover.Stage.HandedOver)
        assertEquals(CapsuleKind.HandedOver, bar.kind)
        assertEquals(CapsuleModel.HANDED_OVER, bar.text)
        assertEquals("付款 · 弄好后点右边", bar.detail)
        // 已交还、循环还没往下走:不再要你
        assertEquals(CapsuleKind.Running, line(takeover).kind)
    }

    // ---------- 优先级与件数 ----------

    @Test fun highestPriorityWinsAndCountsTasks() {
        val v = shown(model.update(inputs(run("a", running("在搜牙膏")), run("b", TaskState.WaitingInput("要哪个？"))), 0))
        assertEquals(CapsuleKind.NeedAnswer, v.kind)
        assertEquals("b", v.runKey)
        assertEquals("c-b", v.conversationId)
        assertEquals(2, v.count)
        assertEquals(1, v.needCount)
        assertEquals("1 件要你处理 · 共 2 件", v.text)
    }

    @Test fun severalRunningAddCount() {
        val v = shown(model.update(inputs(run("a", running("在搜牙膏")), run("b", running("在看天气"))), 0))
        // 一样要紧时说先开始的那件
        assertEquals("a", v.runKey)
        assertEquals("在搜牙膏 · 共 2 件", v.text)
        assertEquals(0, v.needCount)
    }

    @Test fun priorityOrder() {
        val held = TaskState.Held(GateReason("paused", "等一下"))
        val all = listOf(
            run("run", running("x")), run("held", held), run("ask", TaskState.WaitingInput("?")), run("ok", confirm),
            run("need", takeover, Takeover.Stage.Waiting), run("hand", TaskState.WaitingTakeover("login", "登录"), Takeover.Stage.HandedOver),
        )
        val expected = listOf("hand", "need", "ok", "ask", "held", "run")
        for (i in expected.indices) {
            val left = all.filter { it.key in expected.drop(i) }
            assertEquals(expected[i], shown(CapsuleModel().update(inputs(*left.toTypedArray()), 0)).runKey)
        }
    }

    @Test fun handedOverBarSaysWhoIsWaitingBehind() {
        val v = shown(model.update(inputs(
            run("a", takeover, Takeover.Stage.HandedOver), run("b", TaskState.WaitingTakeover("login", "登录"), Takeover.Stage.Waiting),
        ), 0))
        assertEquals(CapsuleKind.HandedOver, v.kind)
        assertEquals("处理好这件后，还有 1 件等你", v.detail)
    }

    // ---------- 每句至少停 1.5 秒 ----------

    @Test fun sentenceStaysAtLeastMinimumAndLatestWins() {
        assertEquals("一", shown(model.update(inputs(run("a", running("一"))), 0)).text)
        assertEquals("一", shown(model.update(inputs(run("a", running("二"))), 500)).text)
        val v = shown(model.update(inputs(run("a", running("三"))), 900))
        assertEquals("一", v.text)
        assertEquals(CapsuleModel.MIN_SENTENCE_MS, model.nextChangeAt)
        // 到时候换成那时最新的一句,中间那句不出现
        assertEquals("三", shown(model.update(inputs(run("a", running("三"))), CapsuleModel.MIN_SENTENCE_MS)).text)
        assertNull(model.nextChangeAt)
    }

    @Test fun stateChangeSwitchesAtOnce() {
        model.update(inputs(run("a", running("一"))), 0)
        val v = shown(model.update(inputs(run("a", TaskState.WaitingInput("要哪个？"))), 100))
        assertEquals("云开 问你：要哪个？", v.text)
    }

    // ---------- 只变宽不变窄 ----------

    @Test fun widthEpochHoldsWithinStateAndRenewsOnChange() {
        val first = shown(model.update(inputs(run("a", running("一"))), 0)).widthEpoch
        assertEquals(first, shown(model.update(inputs(run("a", running("长一点的一句话"))), 2_000)).widthEpoch)
        assertEquals(first, shown(model.update(inputs(run("a", running("短"))), 4_000)).widthEpoch)
        val need = shown(model.update(inputs(run("a", TaskState.WaitingInput("?"))), 5_000)).widthEpoch
        assertNotEquals(first, need)
        val back = shown(model.update(inputs(run("a", running("一"))), 6_000)).widthEpoch
        assertNotEquals(need, back)
        // 换了一件事也重新定宽
        assertNotEquals(back, shown(model.update(inputs(run("b", running("一"))), 7_000)).widthEpoch)
    }

    // ---------- 一颗还是展开 ----------

    @Test fun runningRestsAsBallAndStepsNeverExpandIt() {
        val first = shown(model.update(inputs(run("a", running("在搜牙膏"))), 0))
        assertEquals(CapsuleKind.Running to false, first.kind to first.pill)
        // 换了好几步:还是一颗
        for ((t, said) in listOf(2_000L to "在比价", 4_000L to "在看运费", 6_000L to "在下单")) {
            assertTrue(!shown(model.update(inputs(run("a", running(said))), t)).pill)
        }
        assertTrue(!shown(model.update(inputs(run("a", TaskState.Held(GateReason("paused", "等一下")))), 7_000)).pill)
    }

    @Test fun severalRunsCountOnTheBall() {
        val v = shown(model.update(inputs(run("a", running("x")), run("b", running("y")), run("c", TaskState.Held(GateReason("paused")))), 0))
        assertEquals(3, v.count)
        assertTrue(!v.pill)
    }

    @Test fun needYouStaysAPillUntilTheCardIsOpened() {
        model.update(inputs(run("a", running("x"))), 0)
        val need = shown(model.update(inputs(run("a", confirm)), 100))
        assertEquals(CapsuleKind.NeedConfirm to true, need.kind to need.pill)
        // 不会自己缩回,也不排下一次变化
        assertNull(model.nextChangeAt)
        assertTrue(shown(model.update(inputs(run("a", confirm)), 600_000)).pill)
        // 打开卡片看过了,收起后是一颗(还是橙的)
        model.update(inputs(run("a", confirm), cardOpen = true), 600_100)
        val ball = shown(model.update(inputs(run("a", confirm)), 600_200))
        assertEquals(CapsuleKind.NeedConfirm to false, ball.kind to ball.pill)
    }

    @Test fun aNewNeedExpandsAgain() {
        model.update(inputs(run("a", confirm)), 0)
        model.update(inputs(run("a", confirm), cardOpen = true), 10)
        assertTrue(!shown(model.update(inputs(run("a", confirm)), 20)).pill)
        // 同一件事换成了另一个等待:展开
        val ask = TaskState.WaitingInput("要哪个颜色？")
        assertTrue(shown(model.update(inputs(run("a", ask)), 30)).pill)
        model.update(inputs(run("a", ask), cardOpen = true), 40)
        assertTrue(!shown(model.update(inputs(run("a", ask)), 50)).pill)
        // 另一件也要你:展开,写「2 件要你处理」
        val two = shown(model.update(inputs(run("a", ask), run("b", takeover, Takeover.Stage.Waiting)), 60))
        assertEquals("2 件要你处理 · 共 2 件" to true, two.text to two.pill)
    }

    @Test fun aNeedThatComesBackAfterBeingResolvedExpandsAgain() {
        model.update(inputs(run("a", confirm)), 0)
        model.update(inputs(run("a", confirm), cardOpen = true), 10)
        model.update(inputs(run("a", running("x"))), 20)
        assertTrue(shown(model.update(inputs(run("a", confirm)), 30)).pill)
    }

    // ---------- 办完之后 ----------

    @Test fun doneExpandsThenBallWithBadgeThenGone() {
        model.update(inputs(run("a", running("x"))), 0)
        val done = shown(model.update(inputs(run("a", TaskState.Succeeded("好了"))), 1_000))
        assertEquals(CapsuleKind.Done to true, done.kind to done.pill)
        assertEquals("c-a", done.conversationId)
        assertEquals(1_000 + CapsuleModel.PILL_MS, model.nextChangeAt)
        // 任务已从列表里拿掉,模型自己记着;缩回一颗,还是办好了(角标)
        val ball = shown(model.update(inputs(), 1_000 + CapsuleModel.PILL_MS))
        assertEquals(CapsuleKind.Done to false, ball.kind to ball.pill)
        assertEquals(1_000 + CapsuleModel.LINGER_MS, model.nextChangeAt)
        assertEquals(CapsuleView.Hidden, model.update(inputs(), 1_000 + CapsuleModel.LINGER_MS))
    }

    @Test fun afterLingerAlwaysModeReturnsToIdle() {
        model.update(inputs(run("a", TaskState.Failed("没找到", FailKind.GaveUp)), mode = CapsuleMode.Always), 0)
        assertEquals(CapsuleKind.Idle, shown(model.update(inputs(mode = CapsuleMode.Always), CapsuleModel.LINGER_MS)).kind)
    }

    @Test fun failedAndCancelledHaveTheirLines() {
        assertEquals(CapsuleKind.Failed, shown(CapsuleModel().update(inputs(run("a", TaskState.Failed("x", FailKind.Model))), 0)).kind)
        val stopped = shown(CapsuleModel().update(inputs(run("a", TaskState.Cancelled)), 0))
        assertEquals(CapsuleKind.Cancelled to "已停止", stopped.kind to stopped.text)
    }

    @Test fun activeTaskOutranksLingeringDone() {
        model.update(inputs(run("a", TaskState.Succeeded("好了"))), 0)
        val v = shown(model.update(inputs(run("b", running("在看天气"))), 100))
        assertEquals(CapsuleKind.Running, v.kind)
        assertEquals("b", v.runKey)
        // 结束的不算在「共 N 件」里
        assertEquals(1, v.count)
    }

    // ---------- 卡片展开时 ----------

    @Test fun openCardStopsTheClockForEndedRuns() {
        model.update(inputs(run("a", running("x"))), 0)
        model.update(inputs(run("a", TaskState.Succeeded("好了")), cardOpen = true), 1_000)
        // 展开着:过了很久也不消失,也不排下一次变化
        val v = shown(model.update(inputs(cardOpen = true), 100_000))
        assertEquals(CapsuleKind.Done, v.kind)
        assertNull(model.nextChangeAt)
        assertEquals(listOf("a"), model.cardRuns().map { it.run.key })
        // 收起后:看过了,直接是一颗带着角标,接着走剩下的时间
        val after = shown(model.update(inputs(), 200_000))
        assertEquals(CapsuleKind.Done to false, after.kind to after.pill)
        assertEquals(200_000 + CapsuleModel.LINGER_MS, model.nextChangeAt)
        assertEquals(CapsuleView.Hidden, model.update(inputs(), 200_000 + CapsuleModel.LINGER_MS))
    }

    @Test fun openCardKeepsAlreadyLingeringRunsUntilClosed() {
        model.update(inputs(run("a", TaskState.Succeeded("好了"))), 0)
        model.update(inputs(cardOpen = true), 5_000)
        assertEquals(1, model.cardRuns().size)
        model.update(inputs(cardOpen = true), 1_000_000)
        assertEquals(1, model.cardRuns().size)
        // 展开前已过的时间照算:收起后只剩 LINGER_MS - 5_000
        assertTrue(!shown(model.update(inputs(), 1_000_000)).pill)
        assertEquals(CapsuleView.Hidden, model.update(inputs(), 1_000_000 + CapsuleModel.LINGER_MS - 5_000))
    }

    @Test fun cardRunsActiveByPriorityThenEndedNewestFirst() {
        model.update(inputs(run("old", TaskState.Succeeded("a")), run("r", running("在搜"))), 0)
        model.update(inputs(run("new", TaskState.Failed("x", FailKind.Model)), run("r", running("在搜")), run("q", TaskState.WaitingInput("要哪个？"))), 10)
        val rows = model.cardRuns()
        assertEquals(listOf("q", "r", "new", "old"), rows.map { it.run.key })
        assertEquals(CapsuleKind.NeedAnswer to "云开 问你：要哪个？", rows[0].kind to rows[0].line)
        assertEquals(0L, rows[1].startedAt)
        assertNull(rows[0].startedAt)
        assertEquals(CapsuleKind.Failed, rows[2].kind)
    }

    @Test fun cardRunsKnowTakeoverQueueOrder() {
        model.update(inputs(run("a", takeover, Takeover.Stage.Waiting)), 0)
        model.update(inputs(run("a", takeover, Takeover.Stage.Waiting), run("b", takeover, Takeover.Stage.Waiting)), 10)
        val rows = model.cardRuns().associateBy { it.run.key }
        assertEquals(0 to 1, rows.getValue("a").ahead to rows.getValue("a").behind)
        assertEquals(1 to 0, rows.getValue("b").ahead to rows.getValue("b").behind)
        // 前面那件交还了:后面的排到最前
        model.update(inputs(run("a", running("接着做")), run("b", takeover, Takeover.Stage.Waiting)), 20)
        assertEquals(0, model.cardRuns().first { it.run.key == "b" }.ahead)
    }

    // ---------- 接管队列 ----------

    private fun queued(key: String, ahead: Int, behind: Int, stage: Takeover.Stage = Takeover.Stage.Waiting) =
        run(key, takeover, stage).copy(queueAhead = ahead, queueBehind = behind)

    @Test fun realQueueOrderBeatsFirstSeen() {
        // a 先出现,但队里 b 在前:胶囊和卡片都先说 b
        model.update(inputs(queued("a", 1, 0)), 0)
        val v = shown(model.update(inputs(queued("a", 1, 0), queued("b", 0, 1)), 10))
        assertEquals("b", v.runKey)
        val rows = model.cardRuns()
        assertEquals(listOf("b", "a"), rows.map { it.run.key })
        assertEquals(0 to 1, rows[0].ahead to rows[0].behind)
        assertEquals(1 to 0, rows[1].ahead to rows[1].behind)
    }

    @Test fun barCountsWhoIsBehindInTheRealQueue() {
        val v = shown(model.update(inputs(queued("a", 0, 2, Takeover.Stage.HandedOver), queued("b", 1, 1)), 0))
        assertEquals("处理好这件后，还有 2 件等你", v.detail)
    }

    @Test fun afterHandBackTheNextOneNeedsYouAtOnce() {
        model.update(inputs(queued("a", 0, 1, Takeover.Stage.HandedOver), queued("b", 1, 0)), 0)
        // a 交还、接着做:b 排到最前,马上成为「需要你」(不等换句的节流)
        val v = shown(model.update(inputs(run("a", running("接着做")), queued("b", 0, 0)), 100))
        assertEquals(CapsuleKind.NeedTakeover to "b", v.kind to v.runKey)
        assertEquals(0, model.cardRuns().first { it.run.key == "b" }.ahead)
    }

    // ---------- 半透明 ----------

    @Test fun ballFadesAfterIdleButNeverWhenNeedingYouOrExpanded() {
        assertEquals(1f, CapsuleFade.alpha(CapsuleKind.Running, 0, pill = false, idleFor = CapsuleFade.IDLE_MS - 1))
        assertEquals(CapsuleFade.BALL, CapsuleFade.alpha(CapsuleKind.Running, 0, pill = false, idleFor = CapsuleFade.IDLE_MS))
        assertEquals(CapsuleFade.BALL, CapsuleFade.alpha(CapsuleKind.Done, 0, pill = false, idleFor = 60_000))
        // 展开着、要你处理、别的几件里有要你处理的、「你来处理」那一条:不淡
        assertEquals(1f, CapsuleFade.alpha(CapsuleKind.Done, 0, pill = true, idleFor = 60_000))
        assertEquals(1f, CapsuleFade.alpha(CapsuleKind.NeedConfirm, 1, pill = false, idleFor = 60_000))
        assertEquals(1f, CapsuleFade.alpha(CapsuleKind.Running, 1, pill = false, idleFor = 60_000))
        assertEquals(1f, CapsuleFade.alpha(CapsuleKind.HandedOver, 0, pill = false, idleFor = 60_000))
        assertEquals(0.4f, CapsuleFade.BALL)
    }

    // ---------- 读屏 ----------

    @Test fun ballDescribesItsStatus() {
        assertEquals("云开 在干活：在搜牙膏 · 共 2 件，点开看详情", CapsuleCopy.describe(CapsuleView.Shown(CapsuleKind.Running, "在搜牙膏 · 共 2 件", count = 2)))
        assertEquals("要你同意：发送，点开处理", CapsuleCopy.describe(CapsuleView.Shown(CapsuleKind.NeedConfirm, "要你同意：发送")))
        assertEquals("云开 先等等：排队中，点开看详情", CapsuleCopy.describe(CapsuleView.Shown(CapsuleKind.Held, "先等等：排队中")))
        assertEquals("云开 办好了 · 点开看结果", CapsuleCopy.describe(CapsuleView.Shown(CapsuleKind.Done, "办好了 · 点开看结果")))
        assertEquals(CapsuleCopy.IDLE, CapsuleCopy.describe(CapsuleView.Shown(CapsuleKind.Idle, "")))
    }
}
