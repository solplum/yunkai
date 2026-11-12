package com.wangzan101.mika.capsule

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.ui.Exchange
import com.wangzan101.mika.ui.ExchangeStatus
import com.wangzan101.mika.ui.StepRow
import com.wangzan101.mika.ui.StepState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleCardModelTest {
    @get:Rule val texts = ChineseTexts()

    private fun card(
        key: String, state: TaskState, kind: CapsuleKind = CapsuleKind.Running, line: String = "", ahead: Int = 0, behind: Int = 0,
        exchange: Exchange? = null, onScreen: Boolean = true,
    ) = CardRun(CapsuleRun(key, "c-$key", state, goal = "目标$key", exchange = exchange, onScreen = onScreen), kind, line, ahead = ahead, behind = behind)

    /** 卡片说的是哪一件(任务卡片或对话卡片)。 */
    private fun keyOf(v: CardView?): String? = when (v) {
        is CardView.Detail -> v.run.run.key
        is CardView.Chat -> v.run?.run?.key
        else -> null
    }

    private val running = TaskState.Running(2, "note", "tap")

    // ---------- 只说一件 ----------

    @Test fun oneRunOpensItsDetailDirectly() {
        val v = CapsuleCards.of(listOf(card("a", running)), null) as CardView.Detail
        assertEquals("a", v.run.run.key)
    }

    @Test fun severalRunsShowOnlyTheFirstInImportanceOrder() {
        // 按 cardRuns 的先后(要你处理、进行中、刚结束)只取第一件,没有列表
        val need = card("n", TaskState.WaitingInput("几点？"), CapsuleKind.NeedAnswer)
        val done = card("d", TaskState.Succeeded("好了"), CapsuleKind.Done)
        assertEquals("n", keyOf(CapsuleCards.of(listOf(need, card("a", running), done))))
        assertEquals("a", keyOf(CapsuleCards.of(listOf(card("a", running), done))))
        // 只剩刚结束的:也是一件,不是列表
        assertEquals("d", keyOf(CapsuleCards.of(listOf(done, card("e", TaskState.Cancelled, CapsuleKind.Cancelled)))))
        assertNull(CapsuleCards.of(emptyList(), "c-a"))
    }

    // ---------- 任务卡片还是对话卡片 ----------

    @Test fun taskCardOnlyWhileOperatingAScreenNow() {
        val waits = listOf(
            running to CapsuleKind.Running,
            TaskState.Held(GateReason("q")) to CapsuleKind.Held,
            TaskState.WaitingTakeover("login", "登录") to CapsuleKind.NeedTakeover,
            TaskState.WaitingInput("几点？") to CapsuleKind.NeedAnswer,
            TaskState.WaitingConfirmation(ConfirmationRequest(IrrevAction.send, "张三", "明天见", null, "com.chat")) to CapsuleKind.NeedConfirm,
        )
        for ((s, k) in waits) {
            assertTrue("$s on screen", CapsuleCards.of(listOf(card("a", s, k)), null) is CardView.Detail)
            // 纯聊天(没有虚拟屏、没开网页)同样的状态是对话卡片
            val chat = CapsuleCards.of(listOf(card("a", s, k, onScreen = false)), null) as CardView.Chat
            assertEquals("c-a", chat.target)
            assertEquals(CapsuleCards.content(card("a", s, k)), chat.content)
        }
    }

    @Test fun finishedAppTaskIsAChatCard() {
        // 操作过 App、办完回了话:按此刻的状态,是对话卡片(画面还没放掉也一样)
        for ((s, k) in listOf(
            TaskState.Succeeded("好了") to CapsuleKind.Done,
            TaskState.Failed("没找到", FailKind.GaveUp) to CapsuleKind.Failed,
            TaskState.Cancelled to CapsuleKind.Cancelled,
        )) {
            assertTrue("$s", CapsuleCards.of(listOf(card("a", s, k, onScreen = true)), null) is CardView.Chat)
            assertTrue(!CapsuleCards.taskCard(card("a", s, k, onScreen = true)))
        }
        // 正在操作的那件变成纯聊天(画面放掉了):马上换成对话卡片
        assertTrue(CapsuleCards.taskCard(card("a", running)))
        assertTrue(!CapsuleCards.taskCard(card("a", running, onScreen = false)))
    }

    @Test fun stopKeyWhileStillGoing() {
        assertEquals("a", CapsuleCards.stopKey(CapsuleCards.of(listOf(card("a", running)), null)!!))
        assertEquals("a", CapsuleCards.stopKey(CapsuleCards.of(listOf(card("a", running, onScreen = false)), null)!!))
        assertEquals("a", CapsuleCards.stopKey(CapsuleCards.of(listOf(card("a", TaskState.WaitingInput("几点？"), CapsuleKind.NeedAnswer, onScreen = false)), null)!!))
        assertNull(CapsuleCards.stopKey(CapsuleCards.of(listOf(card("a", TaskState.Succeeded("好了"), CapsuleKind.Done)), null)!!))
        assertNull(CapsuleCards.stopKey(CapsuleCards.of(listOf(card("a", TaskState.WaitingTakeover("login", "登录"), CapsuleKind.NeedTakeover)), null)!!))
        assertNull(CapsuleCards.stopKey(CardView.Chat("c1")))
    }

    // ---------- 每种状态放什么 ----------

    @Test fun workingListsAllStepsEndingWithTheCurrentLine() {
        val ex = Exchange("g", listOf("打开购物", "点搜索框", "输入牙膏", "看结果").map { StepRow(1, it, "", StepState.Ok) }, ExchangeStatus.Working, live = true)
        assertEquals(listOf("打开购物", "点搜索框", "输入牙膏", "看结果", "挑销量高的"), CapsuleCards.steps(ex, "挑销量高的"))
        // 此刻那句就是最后一步:不重复
        assertEquals(listOf("打开购物", "点搜索框", "输入牙膏", "看结果"), CapsuleCards.steps(ex, "看结果"))
        assertEquals(listOf("在想下一步"), CapsuleCards.steps(null, "在想下一步"))
        assertEquals(CardContent.Working(listOf("在想")), CapsuleCards.content(card("a", running, line = "在想")))
    }

    @Test fun takeoverSaysWhyWithPaymentAndQueue() {
        val s = TaskState.WaitingTakeover("payment", "请输入支付密码", item = "牙膏", amount = "¥19.9", address = "家")
        val first = CapsuleCards.content(card("a", s, CapsuleKind.NeedTakeover, "需要你：付款", behind = 2)) as CardContent.Takeover
        assertEquals("需要你：付款", first.need)
        assertEquals("请输入支付密码\n商品:牙膏\n金额:¥19.9\n地址:家", first.body)
        assertEquals("处理好这件后，还有 2 件等你", first.queueLine)
        assertTrue(first.canTakeOver)
        val queued = CapsuleCards.content(card("b", s, CapsuleKind.NeedTakeover, ahead = 1)) as CardContent.Takeover
        assertEquals(CapsuleCards.QUEUED_AHEAD, queued.queueLine)
        assertTrue(!queued.canTakeOver)
        assertNull((CapsuleCards.content(card("c", s, CapsuleKind.NeedTakeover)) as CardContent.Takeover).queueLine)
    }

    @Test fun confirmUsesTheChatCardCopy() {
        val s = TaskState.WaitingConfirmation(ConfirmationRequest(IrrevAction.order, "店", "两支牙膏", "¥19.9", "com.shop"))
        val ex = Exchange("g", emptyList(), ExchangeStatus.NeedsConfirmation("要你同意:下单", "在 购物\n金额:¥19.9", 42), live = true)
        assertEquals(CardContent.Confirm("要你同意:下单", "在 购物\n金额:¥19.9", 42), CapsuleCards.content(card("a", s, CapsuleKind.NeedConfirm, exchange = ex)))
        // 还没有这一问一答时也能同意:编号和对话页、通知用的同一个
        val raw = CapsuleCards.content(card("a", s, CapsuleKind.NeedConfirm)) as CardContent.Confirm
        assertEquals(System.identityHashCode(s), raw.key)
        assertTrue(raw.body.contains("金额:¥19.9"))
    }

    @Test fun questionHeldAndResults() {
        assertEquals(CardContent.Question("要哪个？", listOf("大", "小")), CapsuleCards.content(card("a", TaskState.WaitingInput(" 要哪个？ ", listOf("大", "", "小")))))
        assertEquals(CardContent.Question("几点？", emptyList()), CapsuleCards.content(card("a", TaskState.WaitingInput("几点？"))))
        assertEquals(CardContent.Held("排队中", true), CapsuleCards.content(card("a", TaskState.Held(GateReason("q", "排队中", resumable = true)))))
        assertEquals(CardContent.Held("先等等", false), CapsuleCards.content(card("a", TaskState.Held(GateReason("q")))))
        assertEquals(CardContent.Result(CapsuleKind.Done), CapsuleCards.content(card("a", TaskState.Succeeded("下单了"))))
        assertEquals(CardContent.Result(CapsuleKind.Failed), CapsuleCards.content(card("a", TaskState.Failed("没找到", FailKind.GaveUp))))
        assertEquals(CardContent.Result(CapsuleKind.Cancelled), CapsuleCards.content(card("a", TaskState.Cancelled)))
    }

    @Test fun workingStepsAreCappedKeepingTheNewest() {
        val ex = Exchange("g", (1..80).map { StepRow(1, "第 $it 步", "", StepState.Ok) }, ExchangeStatus.Working, live = true)
        val steps = CapsuleCards.steps(ex, "在想下一步")
        assertEquals(CapsuleCards.STEPS, steps.size)
        assertEquals("第 32 步", steps.first())
        assertEquals("在想下一步", steps.last())
    }

    @Test fun screenOnlyBesideWorkTakeoverAndHeld() {
        assertTrue(CapsuleCards.showsScreen(CardContent.Working(emptyList())))
        assertTrue(CapsuleCards.showsScreen(CardContent.Takeover("", "", null, true)))
        assertTrue(CapsuleCards.showsScreen(CardContent.Held("", false)))
        assertTrue(!CapsuleCards.showsScreen(CardContent.Question("", emptyList())))
        assertTrue(!CapsuleCards.showsScreen(CardContent.Result(CapsuleKind.Done)))
    }

    // ---------- 输入框 ----------

    @Test fun inputGoesToTheRightPlace() {
        fun inputOf(state: TaskState, kind: CapsuleKind) = CapsuleCards.input(CapsuleCards.of(listOf(card("a", state, kind)), null)!!)?.kind
        assertEquals(CardInputKind.Steer, inputOf(running, CapsuleKind.Running))
        assertEquals(CardInputKind.Steer, inputOf(TaskState.Held(GateReason("q")), CapsuleKind.Held))
        assertEquals(CardInputKind.Answer, inputOf(TaskState.WaitingInput("几点？", listOf("8 点")), CapsuleKind.NeedAnswer))
        assertEquals(CardInputKind.Continue, inputOf(TaskState.Succeeded("好了"), CapsuleKind.Done))
        assertEquals(CardInputKind.Continue, inputOf(TaskState.Failed("x", FailKind.Model), CapsuleKind.Failed))
        // 要你接管、要你同意时先处理那件事,不给输入框
        assertNull(inputOf(TaskState.WaitingTakeover("login", "登录"), CapsuleKind.NeedTakeover))
        val confirm = TaskState.WaitingConfirmation(ConfirmationRequest(IrrevAction.send, "张三", "明天见", null, "com.chat"))
        assertNull(inputOf(confirm, CapsuleKind.NeedConfirm))
        assertEquals(CardInput(CapsuleCards.HINT_START, CardInputKind.Start), CapsuleCards.input(CardView.Chat(null)))
        assertEquals(CardInput(CapsuleCards.HINT_CONTINUE, CardInputKind.Continue), CapsuleCards.input(CapsuleCards.of(listOf(card("a", TaskState.Succeeded("好了"), CapsuleKind.Done)), null)!!))
        // 纯聊天在回答时也能插话;要你同意时先处理那件事
        assertEquals(CardInputKind.Steer, CapsuleCards.input(CapsuleCards.of(listOf(card("a", running, onScreen = false)), null)!!)?.kind)
        assertNull(CapsuleCards.input(CapsuleCards.of(listOf(card("a", confirm, CapsuleKind.NeedConfirm, onScreen = false)), null)!!))
    }

    @Test fun afterSendingTheCardFollowsThatConversation() {
        val done = card("a", TaskState.Succeeded("好了"), CapsuleKind.Done)
        val other = card("b", running)
        // 发完话、新任务还没起来:跟着的对话里只有结束的那件
        assertEquals("a", keyOf(CapsuleCards.of(listOf(other, done), follow = "c-a")))
        // 新任务起来了:进行中的优先
        val again = card("a2", running).let { it.copy(run = it.run.copy(conversationId = "c-a")) }
        assertEquals("a2", keyOf(CapsuleCards.of(listOf(other, done, again), follow = "c-a")))
        // 跟着的对话里已经没有任务:按先后取第一件
        assertEquals("b", keyOf(CapsuleCards.of(listOf(other, done), follow = "c-gone")))
    }

    @Test fun idleCardWhenNoRuns() {
        val idle = CardView.Chat("c1", title = "买牙膏")
        assertEquals(idle, CapsuleCards.of(emptyList(), null, idle = idle))
        assertEquals("c1", idle.target)
        assertNull(idle.copy(fresh = true).target)
        // 有任务时不显示空闲卡片
        assertTrue(CapsuleCards.of(listOf(card("a", running)), null, idle = idle) is CardView.Detail)
    }
}
