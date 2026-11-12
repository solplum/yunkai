package com.wangzan101.mika.ui

import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.task.TaskGate

import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.settings.Problem
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.settings.SystemStatus
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState
import com.wangzan101.mika.ui.PipPhysics.Corner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @get:Rule val texts = ChineseTexts()

    @get:Rule val tmp = TemporaryFolder()

    private val state = MutableStateFlow<TaskState>(TaskState.Idle)
    private val stage = MutableStateFlow(Takeover.Stage.None)
    private val calls = mutableListOf<String>()
    private val acts = MutableStateFlow<List<RunEvent.Activity>>(emptyList())
    private val steerFlow = MutableStateFlow<List<SteerNote>>(emptyList())
    private val steerIds = mutableListOf<String>()
    private val actions = object : TaskActions {
        override fun start(goal: String, conversationId: String) { calls += "start:$goal" }
        override fun steer(conversationId: String, messageId: String, text: String) { calls += "steer:$text"; steerIds += messageId }
        override fun cancel() { calls += "cancel" }
        override fun resume() { calls += "resume" }
        override fun takeOver() { calls += "takeOver" }
        override fun handBack() { calls += "handBack" }
        override fun finishedMyself() { calls += "finishedMyself" }
        override fun skip() { calls += "skip" }
        override fun answer(text: String) { calls += "answer:$text" }
        override fun approve(key: Int) { calls += "approve:$key" }
        override fun decline(key: Int) { calls += "decline:$key" }
        override fun show(conversationId: String) { calls += "show:$conversationId" }
        override fun cancelIn(conversationId: String) { calls += "cancel@$conversationId" }
        override fun resumeIn(conversationId: String) { calls += "resume@$conversationId" }
        override fun answerIn(conversationId: String, text: String) { calls += "answer@$conversationId:$text" }
        override fun approveIn(conversationId: String, key: Int) { calls += "approve@$conversationId:$key" }
    }

    private val ready = SystemStatus(34, BuiltinShellStatus.Running, AccessibilityState.On, notificationsAllowed = true)
    private val ok = Prerequisites.check(ready, hasMainModel = true)

    private fun TestScope.vm(store: ConversationStore = ConversationStore(tmp.newFolder())) =
        ChatViewModel(backgroundScope, state, stage, actions, store, appLabel = { if (it == "com.chat") "聊天" else it },
            activities = acts, steers = steerFlow).also { runCurrent() }

    private fun TestScope.emit(s: TaskState) {
        state.value = s
        runCurrent()
    }

    /** 运行事件里又多了一件实际做过的事。 */
    private fun TestScope.did(step: Int, kind: String, tool: String, target: String?, effect: String = "completed", title: String? = null, failed: Boolean = false) {
        acts.value = acts.value + RunEvent.Activity(0, step, kind, tool, target, effect, title = title, failed = failed)
        runCurrent()
    }

    @Test fun blocksWhenPrerequisiteMissing() = runTest {
        val vm = vm()
        val check = Prerequisites.check(ready.copy(accessibility = AccessibilityState.Off), hasMainModel = false)
        assertFalse(vm.send("查天气", check))
        assertTrue(calls.isEmpty())
        // 无障碍只在操作手机 App 时需要,不挡任务;挡住的只有没模型
        assertEquals(listOf(Problem.NoModel), vm.ui.value.blocked)
        assertTrue(vm.ui.value.exchanges.isEmpty())

        // 通知没开不算挡路
        assertTrue(vm.send("查天气", Prerequisites.check(ready.copy(notificationsAllowed = false), hasMainModel = true)))
        assertNull(vm.ui.value.blocked)
        assertEquals(listOf("start:查天气"), calls)
    }

    @Test fun showsStepsFromState() = runTest {
        val vm = vm()
        vm.send("打开设置看电量", ok)
        emit(TaskState.Running(0, "正在准备"))
        assertTrue(vm.ui.value.exchanges.single().steps.isEmpty())

        emit(TaskState.Running(1, ""))
        var steps = vm.ui.value.exchanges.single().steps
        assertTrue(steps.isEmpty())
        assertEquals("正在处理", liveLine(vm.ui.value.exchanges.single()))

        // 选了工具还没做:不按工具名列一行,等实际做了再列
        emit(TaskState.Running(1, "我先打开设置", "phone_launch_app"))
        assertTrue(vm.ui.value.exchanges.single().steps.isEmpty())
        did(1, "act", "phone_launch_app", "com.chat", "place_changed")
        steps = vm.ui.value.exchanges.single().steps
        assertEquals(StepRow(1, "打开 App · 聊天", "", StepState.Run), steps.single())
        assertEquals("打开 App · 聊天", liveLine(vm.ui.value.exchanges.single()))

        // 读取:不出现「看屏幕」;连着读同一处合成一行;不知道读的是哪里的不列
        emit(TaskState.Running(2, "我先打开设置"))
        emit(TaskState.Running(2, "phone_observe", "phone_observe"))
        did(2, "observe", "phone_observe", "com.chat", title = "会话列表")
        did(2, "observe", "phone_diagnose", "com.chat", title = "会话列表")
        did(2, "observe", "phone_observe", null)
        steps = vm.ui.value.exchanges.single().steps
        assertEquals(listOf("打开 App · 聊天", "看了聊天的会话列表"), steps.map { it.text })
        assertEquals(listOf(StepState.Ok, StepState.Run), steps.map { it.state })

        emit(TaskState.WaitingTakeover("captcha", "出现滑块验证码"))
        val ex = vm.ui.value.exchanges.single()
        assertEquals(StepState.Wait, ex.steps.last().state)
        assertEquals("需要你处理", liveLine(ex))
        assertEquals(ExchangeStatus.NeedsTakeover("captcha", "出现滑块验证码"), ex.status)
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)

        vm.takeOver()
        assertEquals("takeOver", calls.last())
    }

    @Test fun paymentTakeoverCarriesDetailsAndCanBeSkipped() = runTest {
        val vm = vm()
        vm.send("买一杯咖啡", ok)
        emit(TaskState.WaitingTakeover("payment", "请付款", item = "拿铁 大杯", amount = "¥18", address = "公司前台"))
        assertEquals(
            ExchangeStatus.NeedsTakeover("payment", "请付款", "拿铁 大杯", "¥18", "公司前台"),
            vm.ui.value.exchanges.single().status,
        )
        vm.skip()
        assertEquals("skip", calls.last())
    }

    // 回答原样显示;来源不从回答里拆,由运行记录里代码记下的那份给出
    @Test fun finalAnswerShownAsIs() = runTest {
        val vm = vm()
        vm.send("电量多少", ok)
        emit(TaskState.Running(1, "phone_observe", "phone_observe"))
        did(1, "observe", "phone_observe", "com.chat")
        emit(TaskState.Running(2, "phone_observe")) // 下一步还在等模型就结束了:这一行不留
        emit(TaskState.Succeeded("电量 86%,正在充电。来源:设置 › 电池"))
        val ex = vm.ui.value.exchanges.single()
        assertEquals(ExchangeStatus.Done("电量 86%,正在充电。来源:设置 › 电池", null), ex.status)
        assertEquals(listOf(StepRow(1, "看了聊天", "", StepState.Ok)), ex.steps)
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)

        assertEquals("没有依据段落" to null, splitEvidence("没有依据段落"))
        // 旧记录(对话文件)里的写法:最后单独一行以「依据:」开头,照旧拆出来
        assertEquals("已把亮度调到 50%。\n自动亮度已关闭。" to "设置 › 显示 页面的亮度滑块显示 50%,自动亮度开关为关",
            splitEvidence("已把亮度调到 50%。\n自动亮度已关闭。\n依据: 设置 › 显示 页面的亮度滑块显示 50%,自动亮度开关为关"))
        assertEquals("结论。" to "来自搜索结果第一条", splitEvidence("结论。证据：来自搜索结果第一条"))
    }

    @Test fun unverifiedIsMarkedOnTheResult() = runTest {
        val vm = vm()
        vm.send("查余额", ok)
        emit(TaskState.Running(1, "phone_observe", "phone_observe"))
        emit(TaskState.Succeeded("余额 10 元", verified = false, note = "结果页受系统防截屏保护"))
        val ex = vm.ui.value.exchanges.single()
        assertEquals(ExchangeStatus.Done("余额 10 元", null, verified = false, note = "结果页受系统防截屏保护"), ex.status)
        assertEquals("办完了,但没能核实:结果页受系统防截屏保护", (ex.status as ExchangeStatus.Done).unverifiedLine)
        assertEquals(PipMode.Done, vm.ui.value.pip.mode)

        vm.send("再查", ok)
        emit(TaskState.Running(1, "phone_observe", "phone_observe"))
        emit(TaskState.Succeeded("余额 10 元"))
        assertNull((vm.ui.value.exchanges.last().status as ExchangeStatus.Done).unverifiedLine)
    }

    @Test fun liveLineUsesTheModelsReasonAndCollapsesToASummary() = runTest {
        var now = 1_000L
        val screen = MutableStateFlow(false)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = screen, clock = { now },
            activities = acts).also { runCurrent() }
        vm.send("查上海到杭州的车次", ok)
        emit(TaskState.Running(1, "", "phone_launch_app", reason = "打开 12306"))
        did(1, "act", "phone_launch_app", "cn.railway")
        screen.value = true
        runCurrent()
        var ex = vm.ui.value.exchanges.single()
        assertEquals(StepRow(1, "打开 App · cn.railway", "", StepState.Run, "打开 12306"), ex.steps.single())
        assertEquals("打开 12306", liveLine(ex))
        assertTrue(vm.ui.value.operatingPhone)

        // 模型请求在途时显示中性状态，不虚构新步骤。
        emit(TaskState.Running(2, ""))
        ex = vm.ui.value.exchanges.single()
        assertEquals(1, ex.steps.size)
        assertEquals("正在处理", liveLine(ex))

        emit(TaskState.Running(2, "", "phone_tap", reason = "正在 12306 搜索上海到杭州的车次"))
        assertEquals("正在 12306 搜索上海到杭州的车次", liveLine(vm.ui.value.exchanges.single()))
        did(2, "act", "phone_tap", "cn.railway", "changed")

        // 等解锁时不算在后台操作
        emit(TaskState.Held(TaskGate.LOCKED))
        assertFalse(vm.ui.value.operatingPhone)

        emit(TaskState.Running(3, "正在核实结果", reason = "正在核实结果"))
        did(3, "observe", "phone_observe", "cn.railway", title = "查询结果")
        now = 81_000L
        emit(TaskState.Succeeded("有 3 趟\n依据:12306 查询结果页"))
        ex = vm.ui.value.exchanges.single()
        assertEquals(80_000L, ex.elapsedMs)
        assertEquals("做了 3 步 · 1 分 20 秒", stepsSummary(ex))
        assertFalse(vm.ui.value.operatingPhone)
        // 不知道用时就只说步数
        assertEquals("做了 3 步", stepsSummary(ex.copy(elapsedMs = null)))
    }

    /** 顶栏副标题「正在操作 …」:最近一件有对象的事所在的 App(包名换成 App 名)。 */
    @Test fun livePlaceIsTheLatestTargetedApp() = runTest {
        val vm = vm()
        vm.send("看聊天", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        assertNull(vm.livePlace(vm.ui.value.exchanges.single()))
        did(1, "act", "phone_launch_app", "com.chat")
        did(2, "act", "phone_wait", null)
        val ex = vm.ui.value.exchanges.single()
        assertEquals("聊天", vm.livePlace(ex))
        assertEquals("正在操作聊天" to ChatCopy.Tone.Accent, ChatCopy.subtitle(ex, vm.livePlace(ex)))
    }

    // 随调用写的进度说明优先于调用里的用意
    @Test fun liveLinePrefersNarration() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder())).also { runCurrent() }
        vm.send("查车次", ok)
        emit(TaskState.Running(1, "先打开 12306 看看", "phone_launch_app", reason = "打开 12306", narration = "先打开 12306 看看"))
        assertEquals("先打开 12306 看看", liveLine(vm.ui.value.exchanges.single()))
        emit(TaskState.Running(2, "", "phone_tap", reason = "点搜索"))
        assertEquals("点搜索", liveLine(vm.ui.value.exchanges.single()))
    }

    @Test fun streamedTextIsVisibleDuringRequestAndClearedAtCompletion() = runTest {
        val vm = vm()
        vm.send("解释这个概念", ok)
        emit(TaskState.Running(1, "", preview = "第一段"))
        var ex = vm.ui.value.exchanges.single()
        assertEquals("第一段", ex.draft)
        assertTrue(ex.steps.isEmpty())
        assertEquals("正在处理", liveLine(ex))

        emit(TaskState.Running(1, "", preview = "第一段和第二段"))
        assertEquals("第一段和第二段", vm.ui.value.exchanges.single().draft)

        emit(TaskState.Succeeded("完整答复"))
        ex = vm.ui.value.exchanges.single()
        assertNull(ex.draft)
        assertEquals(ExchangeStatus.Done("完整答复", null), ex.status)
    }

    // 退回一次后仍没对上的几处:结果卡片上逐条列出,不打绿勾
    @Test fun unconfirmedItemsAreListedOnTheCard() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder())).also { runCurrent() }
        vm.send("看价格", ok)
        emit(TaskState.Running(1, "", "phone_observe"))
        emit(TaskState.Succeeded("咖啡豆 ¥79", verified = false, note = "未确认:79", unconfirmed = listOf("79")))
        val done = vm.ui.value.exchanges.single().status as ExchangeStatus.Done
        assertEquals("咖啡豆 ¥79", done.answer)
        assertNull(done.evidence)
        assertEquals("这几处没能确认:79", done.unverifiedLine)
    }

    @Test fun phoneHintOnlyWhileTheScreenIsOpen() = runTest {
        val screen = MutableStateFlow(false)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = screen).also { runCurrent() }
        vm.send("算一下 1+1", ok)
        emit(TaskState.Running(1, "", "run_command"))
        // 没开过屏(文件、网页、纯对话的任务):不说在后台操作手机
        assertFalse(vm.ui.value.operatingPhone)
        screen.value = true
        runCurrent()
        assertTrue(vm.ui.value.operatingPhone)
        emit(TaskState.Succeeded("2"))
        assertFalse(vm.ui.value.operatingPhone)
    }

    @Test fun answerPartsPrefersAStructuredEvidence() {
        assertEquals("电量 86%" to "设置 › 电池", answerParts("电量 86%\n依据:设置 › 电池"))
        assertEquals("电量 86%" to "电池页", answerParts("电量 86% ", evidence = " 电池页 "))
        assertEquals("电量 86%\n依据:设置 › 电池" to "电池页", answerParts("电量 86%\n依据:设置 › 电池", evidence = "电池页"))
        // 给了空的就照旧从回答里拆
        assertEquals("电量 86%" to "设置 › 电池", answerParts("电量 86%\n依据:设置 › 电池", evidence = " "))
        assertEquals("没有依据" to null, answerParts("没有依据"))
    }

    // 新记录:来源由代码从证据账本里找(sources 事件),逐条列出,画面是这些读取那一步的
    @Test fun evidenceSourceUsesRecordedSources() {
        val events = listOf(
            RunEvent.Media(1, 1, 1, "com.chat"),
            RunEvent.Media(2, 2, 3, "网站 www.12306.cn"),
            RunEvent.Sources(3, listOf(
                com.wangzan101.mika.core.verify.AnswerSource(1, "screen", "com.chat", null, 1, listOf(1)),
                com.wangzan101.mika.core.verify.AnswerSource(4, "web", "https://www.12306.cn/index", "车次查询", 3, emptyList()),
            )),
        )
        val source = evidenceSource(events) { if (it == "com.chat") "聊天" else it }
        assertEquals(EvidenceSource("www.12306.cn", listOf(1), listOf("第 1 步 · 聊天", "第 3 步 · www.12306.cn · 车次查询")), source)
        assertEquals("来源 · www.12306.cn · 1 张截图", sourceLabel(source))
    }

    @Test fun evidenceSourceIsTheLastSavedScreen() {
        val events = listOf(
            RunEvent.Media(1, 1, 1, "com.chat"),
            RunEvent.Media(2, 2, 3, "网站 www.12306.cn"),
            RunEvent.Media(3, 3, 3, null),
        )
        val source = evidenceSource(events) { if (it == "com.chat") "聊天" else it }
        assertEquals(EvidenceSource("www.12306.cn", listOf(2, 3)), source)
        assertEquals("来源 · www.12306.cn · 2 张截图", sourceLabel(source))
        assertEquals(EvidenceSource("聊天", listOf(1)), evidenceSource(events.take(1)) { if (it == "com.chat") "聊天" else it })
        // 没有截图(文件、纯对话的任务)、还没取到时只写「来源」
        assertEquals(EvidenceSource(null, emptyList()), evidenceSource(emptyList()))
        assertEquals("来源", sourceLabel(EvidenceSource(null, emptyList())))
        assertEquals("来源", sourceLabel(null))
    }

    @Test fun livePreviewVisibleOnlyWhileRunning() = runTest {
        val screen = MutableStateFlow(false)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = screen).also { runCurrent() }
        assertFalse(vm.ui.value.pip.visible)
        vm.send("任务", ok)
        emit(TaskState.Running(1, "", null))
        screen.value = true
        runCurrent()
        assertTrue(vm.ui.value.pip.visible)
        assertEquals(PipMode.Running, vm.ui.value.pip.mode)

        emit(TaskState.Succeeded("好了"))
        assertTrue(vm.ui.value.pip.visible)
        assertEquals(PipMode.Done, vm.ui.value.pip.mode)
        advanceTimeBy(ChatViewModel.DONE_LINGER_MS + 1)
        runCurrent()
        assertFalse(vm.ui.value.pip.visible)

        screen.value = false
        runCurrent()
        vm.send("再来", ok)
        emit(TaskState.Running(1, "", null))
        screen.value = true
        runCurrent()
        assertTrue(vm.ui.value.pip.visible)
        emit(TaskState.Failed("出错了", FailKind.GaveUp))
        assertFalse(vm.ui.value.pip.visible)
    }

    @Test fun pipOnlyOnceTheTaskHasAScreen() = runTest {
        val screen = MutableStateFlow(false)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = screen).also { runCurrent() }
        vm.send("打开设置看安卓版本", ok)
        assertFalse(vm.ui.value.pip.visible)
        emit(TaskState.Running(1, "phone_observe", "phone_observe"))
        assertFalse(vm.ui.value.pip.visible)
        vm.expandPip()
        assertFalse(vm.ui.value.pip.expanded)
        // 开 App 前的提问只在聊天卡片里
        emit(TaskState.WaitingInput("哪个?", null))
        assertFalse(vm.ui.value.pip.visible)
        emit(TaskState.Running(2, "phone_launch_app", "phone_launch_app"))

        screen.value = true // 第一次 phone_launch_app 建了屏
        runCurrent()
        assertTrue(vm.ui.value.pip.visible)
        assertEquals(PipMode.Running, vm.ui.value.pip.mode)
        // 开过屏之后的接管照常要你处理
        emit(TaskState.WaitingTakeover("login", "需要登录"))
        assertTrue(vm.ui.value.pip.visible)
        assertEquals(PipMode.NeedsYou, vm.ui.value.pip.mode)

        // 收尾先撤屏再报结束:完成后停留的那几秒照样显示
        screen.value = false
        runCurrent()
        emit(TaskState.Succeeded("安卓 14\n依据:关于手机页"))
        assertTrue(vm.ui.value.pip.visible)
        assertEquals(PipMode.Done, vm.ui.value.pip.mode)
    }

    @Test fun chatOnlyTaskNeverShowsPip() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = MutableStateFlow(false)).also { runCurrent() }
        vm.send("你好", ok)
        emit(TaskState.Running(1, "phone_observe", "phone_observe"))
        emit(TaskState.Running(2, ""))
        emit(TaskState.Succeeded("你好!", verified = false, reviewed = false))
        assertFalse(vm.ui.value.pip.visible)
        val done = vm.ui.value.exchanges.single().status as ExchangeStatus.Done
        assertEquals(ExchangeStatus.Done("你好!", null, verified = false, reviewed = false), done)
        // 什么也没核对的聊天:不打标
        assertNull(done.unverifiedLine)
    }

    @Test fun pipSnapsToNearestCorner() {
        val b = PipPhysics.Bounds(1080f, 2400f, 200f, 420f, margin = 30f, top = 300f, bottom = 250f)
        assertEquals(850f to 330f, b.position(Corner.TopEnd))
        assertEquals(30f to 1700f, b.position(Corner.BottomStart))
        // 停在右上附近、没有速度:回右上
        assertEquals(Corner.TopEnd, PipPhysics.snapCorner(700f, 500f, 0f, 0f, b))
        // 同一位置向下甩:投射落点在下半屏
        assertEquals(Corner.BottomEnd, PipPhysics.snapCorner(700f, 500f, 0f, 2500f, b))
        // 向左下甩
        assertEquals(Corner.BottomStart, PipPhysics.snapCorner(700f, 500f, -2000f, 2500f, b))
        assertEquals(499f, PipPhysics.project(1000f), 0.01f)
        // 窗口太矮(比如键盘弹出):不钻到顶栏下面,下方的角退到和上方一样
        val short = b.copy(height = 900f)
        assertEquals(30f to 330f, short.position(Corner.BottomStart))

        // 临界阻尼:收敛到目标,不越过
        val spring = PipPhysics.Spring()
        var m = PipPhysics.Motion(0f, 0f)
        var max = 0f
        repeat(120) {
            m = spring.step(m, 100f, 1f / 60)
            max = maxOf(max, m.position)
        }
        assertTrue(spring.settled(m, 100f))
        assertTrue("overshoot $max", max <= 100.5f)
    }

    @Test fun pipAutoExpandsWhenNeedsUser() = runTest {
        val screen = MutableStateFlow(false)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = screen).also { runCurrent() }
        vm.send("任务", ok)
        emit(TaskState.Running(1, "", null))
        screen.value = true
        runCurrent()
        assertFalse(vm.ui.value.pip.expanded)

        emit(TaskState.WaitingTakeover("login", "需要登录"))
        assertTrue(vm.ui.value.pip.expanded)
        assertEquals(PipMode.NeedsYou, vm.ui.value.pip.mode)

        vm.collapsePip()
        assertFalse(vm.ui.value.pip.expanded)
        stage.value = Takeover.Stage.HandedOver
        runCurrent()
        assertFalse(vm.ui.value.pip.expanded) // 同一次等待里不反复弹开
        assertEquals(Takeover.Stage.HandedOver, vm.ui.value.takeoverStage)

        emit(TaskState.Running(2, "", null))
        assertFalse(vm.ui.value.pip.expanded)
        // 只有接管自动展开:提问时要让输入框和选项留在眼前
        emit(TaskState.WaitingInput("选哪个?", listOf("A", "B")))
        assertFalse(vm.ui.value.pip.expanded)
        assertEquals(PipMode.NeedsYou, vm.ui.value.pip.mode)
        assertEquals(ComposerMode.Answer, vm.ui.value.composer)
        assertTrue(vm.send("A", ok))
        assertEquals("answer:A", calls.last())
    }

    @Test fun startWaitsUntilServiceFinishedPreviousTask() = runTest {
        val busy = MutableStateFlow(true)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), busy)
        runCurrent()
        assertTrue(vm.send("下一个", ok))
        runCurrent()
        assertTrue(calls.isEmpty())
        busy.value = false
        runCurrent()
        assertEquals(listOf("start:下一个"), calls)
    }

    @Test fun stopWhileQueuedBehindServiceCancelsTheStart() = runTest {
        val busy = MutableStateFlow(true)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), busy)
        runCurrent()
        assertTrue(vm.send("下一个", ok))
        runCurrent()
        vm.cancel()
        busy.value = false
        runCurrent()
        assertTrue(calls.toString(), calls.isEmpty()) // 没开始,也不去取消服务里收尾中的上一个任务
        assertEquals(ExchangeStatus.Cancelled(), vm.ui.value.exchanges.single().status)
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)
        assertFalse(vm.ui.value.pip.visible)
    }

    @Test fun historyLoadsWhenOpeningConversation() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        store.append(id, Turn("查天气", "晴\n依据:天气 App", "succeeded"))
        store.append(id, Turn("订票", "验证码过不去", "failed"))
        store.append(id, Turn("查余额", "余额 10 元\n依据:钱包页", "unverified", note = "防截屏"))
        val vm = vm(store)
        vm.openConversation(id)
        val ex = vm.ui.value.exchanges
        assertEquals(listOf("查天气", "订票", "查余额"), ex.map { it.goal })
        assertEquals(ExchangeStatus.Done("余额 10 元", "钱包页", verified = false, note = "防截屏"), ex[2].status)
        assertEquals(ExchangeStatus.Done("晴", "天气 App"), ex[0].status)
        assertEquals(ExchangeStatus.Failed("验证码过不去"), ex[1].status)

        vm.send("再查一次", ok)
        assertEquals("start:再查一次", calls.last())
        assertEquals(id, vm.ui.value.conversationId)
    }

    @Test fun confirmationCardShowsButtons() = runTest {
        val vm = vm()
        assertTrue(vm.send("给张三发明天见", ok))
        emit(TaskState.Running(3, "打字", "phone_type_text"))
        did(3, "act", "phone_type_text", "com.chat", "changed")
        val waiting = TaskState.WaitingConfirmation(ConfirmationRequest(IrrevAction.send, "张三", "明天见", null, "com.chat"))
        emit(waiting)
        val ex = vm.ui.value.exchanges.last()
        assertEquals(ExchangeStatus.NeedsConfirmation("要你同意:发送", "在 聊天\n对象:张三\n内容:明天见", ConfirmationNotifier.requestKey(waiting)), ex.status)
        assertEquals(StepState.Wait, ex.steps.last().state)
        assertEquals(PipMode.NeedsYou, vm.ui.value.pip.mode)
        assertEquals("等你同意", liveLine(ex))
        assertFalse(vm.ui.value.operatingPhone)
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)
        // 回答后回到运行
        emit(TaskState.Running(4, "", null))
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.last().status)
    }

    @Test fun approveCallsAction() = runTest {
        val vm = vm()
        // 卡片按钮带着它所属的申报
        vm.approve(7)
        vm.decline(8)
        assertEquals(listOf("approve:7", "decline:8"), calls)
    }

    @Test fun endedTaskCarriesItsRunId() = runTest {
        val endedRunId = MutableStateFlow<String?>("r-before")
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        store.append(id, Turn("查天气", "晴", "succeeded", runId = "r-old"))
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, endedRunId = endedRunId)
        runCurrent()
        vm.openConversation(id)
        assertEquals("r-old", vm.ui.value.exchanges.single().runId)

        vm.send("查电量", ok)
        emit(TaskState.Running(1, "看看", "phone_observe"))
        assertNull(vm.ui.value.exchanges.last().runId) // 还在跑时不给入口
        // 服务先记下这次的编号再报结束
        endedRunId.value = "r-new"
        emit(TaskState.Succeeded("85%"))
        assertEquals("r-new", vm.ui.value.exchanges.last().runId)

        // 下一个任务的编号后来才到:已结束的那条不跟着变
        vm.send("再查", ok)
        emit(TaskState.Running(1, "看看", "phone_observe"))
        endedRunId.value = "r-next"
        runCurrent()
        assertEquals("r-new", vm.ui.value.exchanges[1].runId)
        emit(TaskState.Failed("没找到", FailKind.GaveUp))
        assertEquals("r-next", vm.ui.value.exchanges[2].runId)
        assertEquals("r-new", vm.ui.value.exchanges[1].runId)
    }

    // 停止:当场已停止、输入框空出来;交代还在写时标「正在整理」,写好记进对话后成为这一轮的回答
    @Test fun stoppedTurnShowsSummaryWhenItArrives() = runTest {
        val endedRunId = MutableStateFlow<String?>(null)
        val summarizing = MutableStateFlow<Set<String>>(emptySet())
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, endedRunId = endedRunId, summarizing = summarizing)
        runCurrent()
        vm.openConversation(id)
        vm.send("看四个设置页", ok)
        emit(TaskState.Running(1, "看看", "phone_launch_app"))
        vm.cancel()
        assertEquals("cancel", calls.last())
        // 服务先登记「正在整理」、记下编号,再报结束,接着记下「已取消」那一轮
        summarizing.value = setOf("r1")
        endedRunId.value = "r1"
        emit(TaskState.Cancelled)
        store.append(id, Turn("看四个设置页", "已取消", "cancelled", runId = "r1"))
        runCurrent()
        assertEquals(ExchangeStatus.Cancelled(summarizing = true), vm.ui.value.exchanges.single().status)
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)

        store.updateTurn(id, "r1") { it.copy(outcome = "停止前看完了网络、蓝牙;显示、声音还没看。") }
        runCurrent()
        summarizing.value = emptySet()
        runCurrent()
        val ex = vm.ui.value.exchanges.single()
        assertEquals(ExchangeStatus.Cancelled("停止前看完了网络、蓝牙;显示、声音还没看。"), ex.status)
        assertEquals("r1", ex.runId)
        // 重新打开对话:从存下的那份读出交代
        vm.newConversation()
        vm.openConversation(id)
        assertEquals(ExchangeStatus.Cancelled("停止前看完了网络、蓝牙;显示、声音还没看。"), vm.ui.value.exchanges.single().status)
    }

    // 交代写不成(或没什么可交代):「正在整理」去掉,只留已停止
    @Test fun stoppedTurnWithoutSummaryDropsTheHint() = runTest {
        val endedRunId = MutableStateFlow<String?>(null)
        val summarizing = MutableStateFlow<Set<String>>(emptySet())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), endedRunId = endedRunId, summarizing = summarizing)
        runCurrent()
        vm.send("看设置", ok)
        emit(TaskState.Running(1, "看看", "phone_launch_app"))
        summarizing.value = setOf("r2")
        endedRunId.value = "r2"
        emit(TaskState.Cancelled)
        assertEquals(ExchangeStatus.Cancelled(summarizing = true), vm.ui.value.exchanges.single().status)
        summarizing.value = emptySet()
        runCurrent()
        assertEquals(ExchangeStatus.Cancelled(), vm.ui.value.exchanges.single().status)
    }

    @Test fun attachmentsGoWithTheNextMessage() = runTest {
        val sandbox = MutableStateFlow(false)
        val imported = mutableListOf<Pair<String, List<String>>>()
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = sandbox,
            importAttachments = { id, uris ->
                imported += id to uris
                com.wangzan101.mika.cap.sandbox.ImportResult(listOf("/workspace/inbox/报表 (1).csv" to 2048L), listOf("big.iso"))
            },
            describeAttachments = { uris -> uris.map { PendingAttachment(it, if (it.endsWith("1")) "报表.csv" else "big.iso", 10L) } },
        ).also { runCurrent() }
        // 沙箱没就绪:没有附件按钮,也不收
        assertFalse(vm.ui.value.attachEnabled)
        vm.attach(listOf("content://x/1"))
        runCurrent()
        assertTrue(vm.ui.value.attachments.isEmpty())

        sandbox.value = true
        runCurrent()
        assertTrue(vm.ui.value.attachEnabled)
        // 选好时只挂在输入框上方,不复制
        vm.attach(listOf("content://x/1", "content://x/2"))
        runCurrent()
        assertEquals(listOf("content://x/1", "content://x/2"), vm.ui.value.attachments.map { it.uri })
        assertTrue(imported.isEmpty())

        // 发送时才复制进工作区,路径写进消息
        assertTrue(vm.send("汇总一下", ok))
        runCurrent()
        val id = vm.ui.value.conversationId!!
        assertEquals(listOf(id to listOf("content://x/1", "content://x/2")), imported)
        assertEquals(listOf("start:汇总一下\n附件:/workspace/inbox/报表 (1).csv(2 KB)"), calls)
        assertTrue(vm.ui.value.attachments.isEmpty())
        assertEquals(listOf("big.iso"), vm.ui.value.rejectedAttachments)
        assertFalse(vm.ui.value.importing)
        // 任务在跑时不收附件
        vm.attach(listOf("content://x/3"))
        runCurrent()
        assertTrue(vm.ui.value.attachments.isEmpty())
    }

    @Test fun attachmentsAloneCanBeSent() = runTest {
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true),
            importAttachments = { _, _ -> com.wangzan101.mika.cap.sandbox.ImportResult(listOf("/workspace/inbox/a.jpg" to 3L * 1024 * 1024), emptyList()) },
        ).also { runCurrent() }
        assertTrue(vm.toggleAttachment(PendingAttachment("content://m/1", "a.jpg", 1L)))
        assertTrue(vm.send("", ok))
        runCurrent()
        assertEquals(listOf("start:附件:/workspace/inbox/a.jpg(3.0 MB)"), calls)
    }

    @Test fun nothingIsSentWhenEveryAttachmentFailsAndThereAreNoWords() = runTest {
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true),
            importAttachments = { _, _ -> com.wangzan101.mika.cap.sandbox.ImportResult(emptyList(), listOf("huge.mov")) },
        ).also { runCurrent() }
        vm.toggleAttachment(PendingAttachment("content://m/1", "huge.mov", 1L))
        vm.send("", ok)
        runCurrent()
        assertTrue(calls.isEmpty())
        assertTrue(vm.ui.value.exchanges.isEmpty())
        assertEquals(listOf("huge.mov"), vm.ui.value.rejectedAttachments)
    }

    @Test fun pickingPhotosStopsAtTheLimit() = runTest {
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true),
            describeAttachments = { uris -> uris.map { PendingAttachment(it, "p.jpg", 1L) } },
        ).also { runCurrent() }
        repeat(AttachTray.LIMIT) { assertTrue(vm.toggleAttachment(PendingAttachment("content://m/$it", "p.jpg", 1L))) }
        assertFalse(vm.toggleAttachment(PendingAttachment("content://m/x", "p.jpg", 1L)))
        // 再点已选的一张就取消
        assertTrue(vm.toggleAttachment(PendingAttachment("content://m/0", "p.jpg", 1L)))
        assertEquals(AttachTray.LIMIT - 1, vm.ui.value.attachments.size)
        vm.attach(listOf("content://f/1", "content://f/2"))
        runCurrent()
        assertEquals(AttachTray.LIMIT, vm.ui.value.attachments.size)
        assertEquals(1, vm.ui.value.overLimit)
        vm.dismissRejected()
        assertEquals(0, vm.ui.value.overLimit)
    }

    @Test fun sharedFilesShowOnTheLiveExchangeAndAreRestored() = runTest {
        val files = MutableStateFlow<List<com.wangzan101.mika.chat.SharedFile>>(emptyList())
        val store = ConversationStore(tmp.newFolder())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, sharedFiles = files).also { runCurrent() }
        vm.send("导出表格", ok)
        emit(TaskState.Running(1, "整理"))
        val f = com.wangzan101.mika.chat.SharedFile("表.xlsx", 5000, "Download/Mika/表.xlsx", "content://media/1")
        files.value = listOf(f)
        runCurrent()
        assertEquals(listOf(f), vm.ui.value.exchanges.last().files)
        emit(TaskState.Succeeded("好了\n依据: 已导出"))
        assertEquals(listOf(f), vm.ui.value.exchanges.last().files)

        val id = vm.ui.value.conversationId!!
        store.append(id, Turn("导出表格", "好了", "succeeded"), listOf(f))
        vm.openConversation(id)
        assertEquals(listOf(f), vm.ui.value.exchanges.single().files)
    }

    @Test fun sizeLabels() {
        assertEquals("12 B", ChatViewModel.sizeLabel(12))
        assertEquals("2 KB", ChatViewModel.sizeLabel(2048))
        assertEquals("1.5 MB", ChatViewModel.sizeLabel(1_572_864))
    }

    // ---- 并行任务(开关 parallelTasks 打开)----
    private val shown = MutableStateFlow<Pair<String, TaskState>?>(null)
    private val active = MutableStateFlow<List<String>>(emptyList())

    private fun TestScope.parallelVm(
        store: ConversationStore,
        files: MutableStateFlow<List<com.wangzan101.mika.chat.SharedFile>> = MutableStateFlow(emptyList()),
    ) = ChatViewModel(
        backgroundScope, state, stage, actions, store, serviceBusy = MutableStateFlow(true),
        parallelFlag = { true }, shownTask = shown, activeConversations = active, runGoal = { if (it == "b") "查快递" else null },
        sharedFiles = files,
    ).also { runCurrent() }

    private fun TestScope.show(conversationId: String, s: TaskState) {
        shown.value = conversationId to s
        runCurrent()
    }

    /** 删掉屏上这个对话:当场换成新的空对话,侧栏不再列它,删的事交给后台。 */
    @Test fun deletingTheOpenConversationShowsAFreshOne() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.append("b", Turn("订票", "好了", "succeeded"))
        val removed = mutableListOf<String>()
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, removeConversation = { removed += it; store.delete(it) }).also { runCurrent() }
        vm.openConversation("a")
        assertEquals(1, vm.ui.value.exchanges.size)

        vm.deleteConversation("a")
        runCurrent()
        assertEquals(listOf("a"), removed)
        assertEquals(setOf("a"), vm.deleted.value)
        assertNull(vm.ui.value.conversationId)
        assertTrue(vm.ui.value.exchanges.isEmpty())
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)

        // 删别的对话:屏上的不动
        vm.openConversation("b")
        vm.deleteConversation("x")
        runCurrent()
        assertEquals("b", vm.ui.value.conversationId)
        assertEquals(listOf("订票"), vm.ui.value.exchanges.map { it.goal })
    }

    /** 侧栏里改名、置顶:存下来,侧栏按存下的重排(置顶的在「置顶」一段,最近置顶的在前);删掉的对话不再改。 */
    @Test fun renameAndPinReorderTheDrawer() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.append("b", Turn("订票", "好了", "succeeded"))
        store.append("c", Turn("写周报", "好了", "succeeded"))
        var now = 1_000L
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, clock = { now }, removeConversation = { store.delete(it) }).also { runCurrent() }
        fun sections() = drawerSections(store.list(), CurrentConversation(null, false, "", ""), emptyList(), vm::runningGoal, vm.canSwitch(), 0L)

        vm.renameConversation("b", " 回家的票 ")
        vm.setPinned("c", true)
        now = 2_000L
        vm.setPinned("b", true)
        val s = sections()
        assertEquals(listOf("回家的票", "写周报"), s.pinned.map { it.title })
        assertEquals(listOf("a"), s.recent.map { it.id })

        vm.setPinned("b", false)
        assertEquals(listOf("c"), sections().pinned.map { it.id })
        assertEquals(setOf("a", "b"), sections().recent.mapNotNull { it.id }.toSet())

        vm.deleteConversation("c")
        runCurrent()
        vm.renameConversation("c", "不该写进去")
        vm.setPinned("c", true)
        assertNull(store.title("c"))
        assertNull(store.pinnedAt("c"))
    }

    /** 删掉的对话又有人往里存了(悬浮胶囊接着在那个编号里说):侧栏重新列出它。 */
    @Test fun aDeletedConversationThatIsWrittenAgainIsListedAgain() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded"))
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, removeConversation = { store.delete(it) }).also { runCurrent() }
        vm.deleteConversation("a")
        runCurrent()
        assertEquals(setOf("a"), vm.deleted.value)
        store.append("a", Turn("再来", "好", "succeeded"))
        runCurrent()
        assertTrue(vm.deleted.value.isEmpty())
    }

    /** 并行时删掉正在跑任务的屏上对话:不再跟那个任务,它之后的状态也不接到新对话里。 */
    @Test fun deletingARunningOpenConversationStopsFollowingIt() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val vm = parallelVm(store)
        vm.switchTo("a")
        assertTrue(vm.send("设置版本号", ok))
        active.value = listOf("a")
        show("a", TaskState.Running(1, "打开设置"))
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)

        vm.deleteConversation("a")
        runCurrent()
        val fresh = vm.ui.value.conversationId
        assertTrue(fresh != null && fresh != "a")
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)
        assertFalse(vm.ui.value.pip.visible)
        show("a", TaskState.Running(2, "看屏幕"))
        show("a", TaskState.Cancelled)
        assertTrue(vm.ui.value.exchanges.isEmpty())
        assertEquals(fresh, vm.ui.value.conversationId)
    }

    @Test fun otherRunningExcludesCurrent() = runTest {
        val vm = parallelVm(ConversationStore(tmp.newFolder()))
        vm.switchTo("a")
        active.value = listOf("a", "b", "c")
        runCurrent()
        assertEquals(listOf("b", "c"), vm.otherRunning.value)
        vm.switchTo("b")
        runCurrent()
        assertEquals(listOf("a", "c"), vm.otherRunning.value)
    }

    @Test fun switchToLoadsThatConversation() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("b", Turn("查天气", "晴\n依据:天气 App", "succeeded"))
        val vm = parallelVm(store)
        // 对话 a 的任务在跑
        vm.switchTo("a")
        assertTrue(vm.send("设置版本号", ok))
        show("a", TaskState.Running(1, "打开设置"))
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)

        // 对话 b 也有任务在跑:切过去先让服务显示它,载入历史并接上它此刻的状态
        active.value = listOf("a", "b")
        show("b", TaskState.Running(2, "看屏幕"))
        vm.switchTo("b")
        assertEquals("b", vm.ui.value.conversationId)
        assertTrue("show:b" in calls)
        assertEquals(listOf("查天气", "查快递"), vm.ui.value.exchanges.map { it.goal })
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.last().status)
        // a 的状态不进 b 的对话
        show("a", TaskState.Succeeded("done"))
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.last().status)

        // 屏上是 b:停止只发给 b 的任务,不先改显示
        calls.clear()
        vm.cancel()
        assertEquals(listOf("cancel@b"), calls)

        // 没有任务的新对话:别的任务还在跑也能开新任务,不等服务空下来
        vm.newConversation()
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)
        assertTrue(vm.send("新建闹钟", ok))
        assertEquals("start:新建闹钟", calls.last())
    }

    private val learnCalls = mutableListOf<String>()
    private val savedNotes = mutableListOf<com.wangzan101.mika.memory.RunNotes>()
    private val learning = object : LearningActions {
        override suspend fun undo(token: String): Boolean { learnCalls += "undo:$token"; return token != "expired" }
        override suspend fun approve(pendingId: String): Boolean { learnCalls += "approve:$pendingId"; return true }
        override suspend fun forget(memoryId: String): Boolean { learnCalls += "forget:$memoryId"; return true }
        override fun viewSkill(draftId: String) { learnCalls += "view:$draftId" }
        override suspend fun restoreSkill(name: String) { learnCalls += "restore:$name" }
        override suspend fun deleteSkill(name: String) { learnCalls += "delete:$name" }
        override suspend fun save(notes: com.wangzan101.mika.memory.RunNotes) { savedNotes += notes }
    }
    private val notesFlow = MutableStateFlow<Map<String, com.wangzan101.mika.memory.RunNotes>>(emptyMap())
    private fun item(id: String, content: String) = com.wangzan101.mika.core.memory.MemoryItem(
        id, com.wangzan101.mika.core.memory.MemoryKind.preference, content, emptyList(),
        com.wangzan101.mika.core.memory.MemorySource("manual", atMs = 0), com.wangzan101.mika.core.memory.MemoryStatus.active, createdAt = 0, updatedAt = 0,
    )
    private val items = MutableStateFlow(listOf(item("a", "用户点外卖不要香菜"), item("b", "用户喝咖啡不加糖"), item("p1", "点外卖先比价")))

    private fun TestScope.vmWithHistory(): ChatViewModel {
        val store = ConversationStore(tmp.newFolder())
        store.append("c1", Turn("点外卖", "已下单\n依据: 订单页", "succeeded", runId = "r1"))
        return ChatViewModel(backgroundScope, state, stage, actions, store, runNotes = notesFlow, learning = learning, memoryItems = items).also {
            runCurrent(); it.openConversation("c1"); runCurrent()
        }
    }

    private val remembered = com.wangzan101.mika.core.memory.MemoryNote(com.wangzan101.mika.core.memory.NoteKind.added, "a", "t1", null)
    private val procedure = com.wangzan101.mika.core.memory.MemoryNote(com.wangzan101.mika.core.memory.NoteKind.pending, "p1", "t2", "p1")

    @Test fun memoryNotesShownUnderResult() = runTest {
        val vm = vmWithHistory()
        notesFlow.value = mapOf("r1" to com.wangzan101.mika.memory.RunNotes("c1", "r1", listOf(remembered)))
        runCurrent()
        val note = vm.ui.value.exchanges.single().memoryNotes.single()
        assertEquals("记住了:用户点外卖不要香菜", vm.noteText(note))
        // 内容现取:那条删掉后写「一条已删除的记忆」
        items.value = items.value.filter { it.id != "a" }
        assertEquals("记住了:一条已删除的记忆", vm.noteText(note))
        // 重新打开对话时从存下的那一轮读回(只存了种类和编号)
        val store = ConversationStore(tmp.newFolder())
        store.append("c2", Turn("点外卖", "ok", "succeeded", runId = "r2", memoryNotes = listOf(remembered), usedMemory = listOf("a")))
        val vm2 = ChatViewModel(backgroundScope, state, stage, actions, store).also { runCurrent(); it.openConversation("c2"); runCurrent() }
        assertEquals(listOf(remembered), vm2.ui.value.exchanges.single().memoryNotes)
    }

    @Test fun undoRemovesNote() = runTest {
        val vm = vmWithHistory()
        val expired = remembered.copy(undoToken = "expired")
        notesFlow.value = mapOf("r1" to com.wangzan101.mika.memory.RunNotes("c1", "r1", listOf(remembered, expired)))
        runCurrent()
        vm.undoNote("r1", remembered); runCurrent()
        assertEquals(listOf("undo:t1"), learnCalls)
        assertEquals(listOf(expired), vm.ui.value.exchanges.single().memoryNotes)
        // 令牌失效:不删提示,只去掉「撤销」
        vm.undoNote("r1", expired); runCurrent()
        assertEquals(listOf(expired.copy(undoToken = null)), vm.ui.value.exchanges.single().memoryNotes)
        assertEquals(listOf(expired.copy(undoToken = null)), savedNotes.last().memoryNotes)
    }

    @Test fun pendingProcedureShowsYesNo() = runTest {
        val vm = vmWithHistory()
        val second = procedure.copy(undoToken = "expired", pendingId = "p2", memoryId = "p2")
        notesFlow.value = mapOf("r1" to com.wangzan101.mika.memory.RunNotes("c1", "r1", listOf(procedure, second)))
        runCurrent()
        assertEquals("要我以后都这样做吗?点外卖先比价", vm.noteText(procedure))
        vm.approveNote("r1", procedure); runCurrent()
        assertEquals(listOf("approve:p1"), learnCalls)
        // 「不要」时令牌已失效:直接删掉那条做法
        vm.rejectNote("r1", vm.ui.value.exchanges.single().memoryNotes.single()); runCurrent()
        assertEquals(listOf("approve:p1", "undo:expired", "forget:p2"), learnCalls)
        assertTrue(vm.ui.value.exchanges.single().memoryNotes.isEmpty())
    }

    @Test fun usedMemoryCountShown() = runTest {
        val vm = vmWithHistory()
        notesFlow.value = mapOf("r1" to com.wangzan101.mika.memory.RunNotes("c1", "r1", usedMemory = listOf("a", "b")))
        runCurrent()
        val ex = vm.ui.value.exchanges.single()
        assertEquals(listOf("用户点外卖不要香菜", "用户喝咖啡不加糖"), vm.usedMemoryTexts(ex.usedMemory))
        // 全部清除后不再列出(界面据此不显示「用到 n 条记忆」)
        items.value = emptyList()
        assertTrue(vm.usedMemoryTexts(ex.usedMemory).isEmpty())
    }

    @Test fun suspendedSkillRestoreAndView() = runTest {
        val vm = vmWithHistory()
        val learned = com.wangzan101.mika.core.skills.SkillNote("Mika 学会了一项技能:发微信", draftId = "d1")
        val suspended = com.wangzan101.mika.core.skills.SkillNote("这项技能最近不灵,先停用了", suspended = "wechat-msg")
        notesFlow.value = mapOf("r1" to com.wangzan101.mika.memory.RunNotes("c1", "r1", skillNotes = listOf(learned, suspended)))
        runCurrent()
        vm.viewSkill(learned)
        vm.restoreSkill("r1", suspended); runCurrent()
        assertEquals(listOf("view:d1", "restore:wechat-msg"), learnCalls)
        assertEquals(listOf(learned), vm.ui.value.exchanges.single().skillNotes)
    }

    // 在没有任务的对话里按按钮,绝不落到别的对话的任务上;按下时带的是那一刻屏上的对话
    @Test fun buttonsInConversationWithoutRunGoNowhere() = runTest {
        val vm = parallelVm(ConversationStore(tmp.newFolder()))
        active.value = listOf("a")
        show("a", TaskState.WaitingInput("哪个?", null))
        vm.switchTo("b")
        calls.clear()
        vm.cancel(); vm.resume(); vm.answer("第一个"); vm.approve(7)
        assertTrue(calls.isEmpty())

        vm.switchTo("a")
        calls.clear()
        vm.resume()
        vm.switchTo("b")
        assertEquals(listOf("resume@a"), calls.filter { "@" in it })
    }

    // 接管进度只跟屏上这个对话的任务;切到没有任务的对话时清掉
    @Test fun takeoverStageFollowsScreenConversation() = runTest {
        val vm = parallelVm(ConversationStore(tmp.newFolder()))
        active.value = listOf("a")
        vm.switchTo("a")
        show("a", TaskState.WaitingTakeover("login", "请登录"))
        stage.value = Takeover.Stage.HandedOver
        runCurrent()
        assertEquals(Takeover.Stage.HandedOver, vm.ui.value.takeoverStage)
        vm.switchTo("b")
        assertEquals(Takeover.Stage.None, vm.ui.value.takeoverStage)
        stage.value = Takeover.Stage.Waiting
        runCurrent()
        assertEquals(Takeover.Stage.None, vm.ui.value.takeoverStage)
        vm.newConversation()
        assertEquals(Takeover.Stage.None, vm.ui.value.takeoverStage)
    }

    // 导出的文件和成功标准一样是每个任务各自的:服务显示的是别的对话的任务时,不挂到屏上这个对话的卡片上
    @Test fun sharedFilesFollowScreenConversation() = runTest {
        val files = MutableStateFlow<List<com.wangzan101.mika.chat.SharedFile>>(emptyList())
        val vm = parallelVm(ConversationStore(tmp.newFolder()), files)
        active.value = listOf("a", "b")
        vm.switchTo("a")
        show("a", TaskState.Running(1, "整理"))
        val mine = com.wangzan101.mika.chat.SharedFile("a.csv", 1, "Download/Mika/a.csv", "content://media/1")
        files.value = listOf(mine)
        runCurrent()
        assertEquals(listOf(mine), vm.ui.value.exchanges.last().files)
        show("b", TaskState.Running(1, "查快递"))
        files.value = listOf(com.wangzan101.mika.chat.SharedFile("b.csv", 1, "Download/Mika/b.csv", "content://media/2"))
        runCurrent()
        assertEquals(listOf(mine), vm.ui.value.exchanges.last().files)
    }

    // 每个对话的卡片用它自己那次的运行编号,不管服务当时显示的是哪个任务
    @Test fun endedCardUsesItsConversationsRunId() = runTest {
        val ended = MutableStateFlow<Map<String, String?>>(emptyMap())
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), serviceBusy = MutableStateFlow(true),
            endedRunId = MutableStateFlow("r-shown-other"), endedRunIds = ended,
            parallelFlag = { true }, shownTask = shown, activeConversations = active,
        ).also { runCurrent() }
        vm.switchTo("a")
        assertTrue(vm.send("设置版本号", ok))
        show("a", TaskState.Running(1, "打开设置"))
        // 别的对话的任务结束了,只记在它自己名下
        ended.value = mapOf("b" to "r-b")
        runCurrent()
        assertNull(vm.ui.value.exchanges.last().runId)
        ended.value = mapOf("b" to "r-b", "a" to "r-a")
        show("a", TaskState.Succeeded("12"))
        assertEquals("r-a", vm.ui.value.exchanges.last().runId)
    }

    // 开关在界面建好之后才打开:开始任务时重读,不用重建界面
    @Test fun parallelFlagReadWhenStarting() = runTest {
        var flag = false
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            parallelFlag = { flag }, shownTask = shown, activeConversations = active,
        ).also { runCurrent() }
        flag = true
        assertTrue(vm.send("设置版本号", ok))
        assertEquals("start:设置版本号", calls.last())
        val first = vm.ui.value.conversationId
        active.value = listOf(first!!)
        runCurrent()
        // 新对话里能接着开第二个
        vm.newConversation()
        assertTrue(vm.ui.value.conversationId != first)
        assertTrue(vm.send("新建闹钟", ok))
        assertEquals("start:新建闹钟", calls.last())
    }

    // 换对话时也重读开关
    @Test fun parallelFlagReadWhenSwitching() = runTest {
        var flag = false
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            parallelFlag = { flag }, shownTask = shown, activeConversations = active,
        ).also { runCurrent() }
        active.value = listOf("b")
        runCurrent()
        flag = true
        vm.openConversation("b")
        // 并行时切到有任务在跑的对话会让服务显示它
        assertTrue("show:b" in calls)
    }

    // 有没有屏也是每个任务各自的:显示中的是别的对话的任务时不进这张卡片
    @Test fun otherConversationsScreenStaysOut() = runTest {
        val screen = MutableStateFlow(true)
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = screen,
            parallelFlag = { true }, shownTask = shown, activeConversations = active,
        ).also { runCurrent() }
        vm.switchTo("a")
        // 别的对话的任务开着屏:新任务还没有屏,不出画中画
        assertTrue(vm.send("设置版本号", ok))
        assertFalse(vm.ui.value.pip.visible)
        show("b", TaskState.Running(1, "看屏幕"))
        runCurrent()
        show("a", TaskState.Running(1, "打开设置"))
        runCurrent()
    }

    // 真机上界面线程是立即调度的:服务一换显示,状态当场就送进来。切走再切回一个还在跑的对话,
    // 那一问一答的原话、步骤(包括切走期间的)、文件都要在,不能另起一条空原话的
    @Test fun switchingBackRestoresTheRunningExchange() = runTest {
        val runStates = mapOf("a" to MutableStateFlow<TaskState>(TaskState.Running(0, "")), "b" to MutableStateFlow<TaskState>(TaskState.Running(0, "")))
        val fileA = com.wangzan101.mika.chat.SharedFile("a.csv", 1, "Download/Mika/a.csv", "content://media/1")
        val runFiles = mapOf("a" to listOf(fileA), "b" to emptyList())
        val goals = mapOf("a" to "第一个任务", "b" to "第二个任务")
        val files = MutableStateFlow<List<com.wangzan101.mika.chat.SharedFile>>(emptyList())
        // 和服务一样:换显示时先抄文件,再抄带对话编号的状态
        val immediate = object : TaskActions by actions {
            override fun show(conversationId: String) {
                files.value = runFiles.getValue(conversationId)
                shown.value = conversationId to runStates.getValue(conversationId).value
            }
        }
        fun publish(c: String, s: TaskState) {
            runStates.getValue(c).value = s
            if (shown.value?.first == c) shown.value = c to s
        }
        val ui = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))
        val vm = ChatViewModel(
            ui, state, stage, immediate, ConversationStore(tmp.newFolder()), serviceBusy = MutableStateFlow(true),
            parallelFlag = { true }, shownTask = shown, activeConversations = active, runGoal = goals::get, runState = runStates::get,
            sharedFiles = files,
        )
        vm.switchTo("a")
        assertTrue(vm.send("第一个任务", ok))
        active.value = listOf("a")
        immediate.show("a")
        publish("a", TaskState.Running(1, "打开设置", "phone_launch_app"))

        // 切到没有任务的 b 开第二个任务:a 的那一问一答停到一边,a 接着跑
        vm.switchTo("b")
        assertTrue(vm.send("第二个任务", ok))
        active.value = listOf("a", "b")
        immediate.show("b")
        publish("b", TaskState.Running(1, "看屏幕"))
        publish("a", TaskState.Running(2, "点版本号", "phone_tap", reason = "点版本号"))
        assertEquals("第二个任务", vm.ui.value.exchanges.last().goal)

        vm.switchTo("a")
        val ex = vm.ui.value.exchanges.last()
        assertEquals("第一个任务", ex.goal)
        assertEquals(listOf(fileA), ex.files)
        // 切走期间 a 的进展照样记着
        assertEquals("点版本号", ex.line)
        assertEquals(ExchangeStatus.Working, ex.status)
        assertEquals(1, vm.ui.value.exchanges.size)
    }

    // 任务在跑时输入框照样能发:发出去的话挂在这一问一答下面,交给当前任务;去向由服务报回来
    @Test fun messageWhileRunningSteersTheTask() = runTest {
        val vm = vm()
        vm.send("订明天的会议室", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)

        assertTrue(vm.send("改成后天", ok))
        assertEquals("steer:改成后天", calls.last())
        var ex = vm.ui.value.exchanges.single()
        assertEquals(listOf("改成后天"), ex.steers.map { it.text })
        assertNull(steerLabel(ex.steers.single().state))
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)

        // 模型下一步之前拿到了这句话
        steerFlow.value = listOf(SteerNote(steerIds.single(), "改成后天", SteerState.Joined))
        runCurrent()
        assertEquals("已加入当前任务", steerLabel(vm.ui.value.exchanges.single().steers.single().state))

        // 又说一句,任务就收尾了:这句没交到任务手里,作为下一件事
        assertTrue(vm.send("顺便订午饭", ok))
        emit(TaskState.Succeeded("订好了"))
        ex = vm.ui.value.exchanges.single()
        assertEquals(listOf(SteerState.Joined, SteerState.Next), ex.steers.map { it.state })
        assertEquals("将作为下一件事", steerLabel(ex.steers.last().state))
        assertEquals(1, vm.ui.value.exchanges.size)
    }

    // 任务的收件箱一收下就有回音「已收到，下一步处理」;任务就此收尾时它和还没发到的一样作为下一件事
    @Test fun receivedSteerIsShownAtOnceAndBecomesNextIfTheRunEnds() = runTest {
        val vm = vm()
        vm.send("订明天的会议室", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        assertTrue(vm.send("改成后天", ok))
        steerFlow.value = listOf(SteerNote(steerIds.single(), "改成后天", SteerState.Received))
        runCurrent()
        val note = vm.ui.value.exchanges.single().steers.single()
        assertEquals(SteerState.Received, note.state)
        assertEquals("已收到，下一步处理", steerLabel(note.state))
        emit(TaskState.Succeeded("订好了"))
        assertEquals(SteerState.Next, vm.ui.value.exchanges.single().steers.single().state)
    }

    @Test fun stopCancelsReceivedSteersToo() = runTest {
        val vm = vm()
        vm.send("订明天的会议室", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        assertTrue(vm.send("改成后天", ok))
        steerFlow.value = listOf(SteerNote(steerIds.single(), "改成后天", SteerState.Received))
        runCurrent()
        vm.cancel()
        assertEquals(SteerState.Cancelled, vm.ui.value.exchanges.single().steers.single().state)
    }

    // 收尾之后服务才报回去向:照样改到那一问一答上
    @Test fun lateSteerOutcomeStillLandsOnItsExchange() = runTest {
        val vm = vm()
        vm.send("查快递", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        vm.send("只看顺丰的", ok)
        emit(TaskState.Failed("网络断了", FailKind.Environment))
        steerFlow.value = listOf(SteerNote(steerIds.single(), "只看顺丰的", SteerState.Next))
        runCurrent()
        assertEquals(SteerState.Next, vm.ui.value.exchanges.single().steers.single().state)
    }

    // 没成功的动作只说没成功,不把错误码给人看
    @Test fun failedActionHidesErrorCode() = runTest {
        val vm = vm()
        vm.send("发消息", ok)
        emit(TaskState.Running(1, "", "phone_tap"))
        did(1, "act", "phone_tap", "com.chat", "ref_expired", failed = true)
        val row = vm.ui.value.exchanges.single().steps.single()
        assertEquals("点击 · 聊天", row.title)
        assertEquals("没成功", row.subtitle)
        assertFalse(row.toString().contains("ref_expired"))
    }

    // 原话的任务还在等服务收尾上一个时插的话:先开始原话的任务,再把插话交给它
    @Test fun steerDuringPendingStartIsSentAfterTheStart() = runTest {
        val busy = MutableStateFlow(true)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), busy, steers = steerFlow)
        runCurrent()
        assertTrue(vm.send("订会议室", ok))
        assertTrue(vm.send("要大一点的", ok))
        runCurrent()
        assertTrue(calls.isEmpty())
        assertEquals(listOf("要大一点的"), vm.ui.value.exchanges.single().steers.map { it.text })
        busy.value = false
        runCurrent()
        assertEquals(listOf("start:订会议室", "steer:要大一点的"), calls)
    }

    // 停止时还没交到任务手里的插话一起作废,标成已取消;还在等服务时停止,插话也不发
    @Test fun stopCancelsPendingSteers() = runTest {
        val vm = vm()
        vm.send("查快递", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        vm.send("只看顺丰的", ok)
        vm.cancel()
        assertEquals("cancel", calls.last())
        assertEquals(SteerState.Cancelled, vm.ui.value.exchanges.single().steers.single().state)
        assertEquals("已取消", steerLabel(SteerState.Cancelled))
        emit(TaskState.Cancelled)
        assertEquals(SteerState.Cancelled, vm.ui.value.exchanges.single().steers.single().state)

        val busy = MutableStateFlow(true)
        calls.clear()
        val vm2 = ChatViewModel(backgroundScope, MutableStateFlow(TaskState.Idle), stage, actions, ConversationStore(tmp.newFolder()), busy)
        runCurrent()
        vm2.send("订会议室", ok)
        vm2.send("要大一点的", ok)
        vm2.cancel()
        busy.value = false
        runCurrent()
        assertTrue(calls.isEmpty())
        assertEquals(SteerState.Cancelled, vm2.ui.value.exchanges.single().steers.single().state)
    }

    // 插话可能成为下一件事:和开新任务过同一道检查
    @Test fun steerIsCheckedLikeANewTask() = runTest {
        val vm = vm()
        vm.send("查快递", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        assertFalse(vm.send("只看顺丰的", Prerequisites.check(ready, hasMainModel = false)))
        assertEquals(listOf(Problem.NoModel), vm.ui.value.blocked)
        assertTrue(vm.ui.value.exchanges.single().steers.isEmpty())
    }

    // 交给任务的插话随那一轮存下:重开对话、重启后照样显示在那一轮下面
    @Test fun joinedSteersSurviveReopening() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        store.append(id, Turn("订会议室", "订好了", "succeeded", steers = listOf("要大一点的")))
        val vm = vm(store)
        vm.openConversation(id)
        val ex = vm.ui.value.exchanges.single()
        assertEquals(listOf("要大一点的"), ex.steers.map { it.text })
        assertEquals("已加入当前任务", steerLabel(ex.steers.single().state))
    }

    // ---- 别处(悬浮胶囊、定时任务)开始的任务和存下的轮次 ----

    /** 开关关着,服务的状态带着对话编号([shown])。 */
    private fun TestScope.taggedVm(store: ConversationStore, endedRunId: MutableStateFlow<String?> = MutableStateFlow(null)) = ChatViewModel(
        backgroundScope, state, stage, actions, store, endedRunId = endedRunId,
        shownTask = shown, activeConversations = active, runGoal = { if (it == "a") "胶囊里说的" else null }, steers = steerFlow, activities = acts,
    ).also { runCurrent() }

    /** 服务显示的任务换了状态:带编号的那份先写,和服务里一样。 */
    private fun TestScope.serve(conversationId: String, s: TaskState) {
        shown.value = conversationId to s
        state.value = s
        runCurrent()
    }

    // 别处跑完存进屏上这个对话的一轮:当场补上,只一条
    @Test fun turnSavedElsewhereAppearsOnce() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
        val vm = vm(store)
        vm.openConversation("a")
        store.append("a", Turn("订会议室", "订好了", "succeeded", runId = "r2", steers = listOf("要大一点的")))
        runCurrent()
        assertEquals(listOf("查天气", "订会议室"), vm.ui.value.exchanges.map { it.goal })
        assertEquals(listOf("要大一点的"), vm.ui.value.exchanges.last().steers.map { it.text })
        // 后面改了那一轮(补上记忆提示):重读,不多出一条
        store.updateTurn("a", "r2") { it.copy(outcome = "订好了 3 楼") }
        runCurrent()
        assertEquals(2, vm.ui.value.exchanges.size)
        assertEquals("订好了 3 楼", (vm.ui.value.exchanges.last().status as ExchangeStatus.Done).answer)
    }

    // 存进别的对话的轮次不动屏上这个对话
    @Test fun turnSavedInAnotherConversationLeavesScreenAlone() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
        val vm = vm(store)
        vm.openConversation("a")
        val before = vm.ui.value
        store.append("b", Turn("订会议室", "订好了", "succeeded", runId = "r2"))
        runCurrent()
        assertEquals(before, vm.ui.value)
    }

    // 这里跟的任务结束、那一轮存进对话:不论先存还是先报结束,都只一条,保留这里的步骤
    @Test fun ownRunSavedIsNotDuplicated() = runTest {
        val ended = MutableStateFlow<String?>(null)
        for (savedFirst in listOf(false, true)) {
            val store = ConversationStore(tmp.newFolder())
            val vm = taggedVm(store, ended)
            assertTrue(vm.send("查快递", ok))
            val id = vm.ui.value.conversationId!!
            serve(id, TaskState.Running(1, "", "phone_launch_app"))
            did(1, "act", "phone_launch_app", "com.chat")
            val turn = Turn("查快递", "到了", "succeeded", runId = "r-$savedFirst")
            ended.value = "r-$savedFirst"
            if (savedFirst) { store.append(id, turn); runCurrent() }
            serve(id, TaskState.Succeeded("到了"))
            if (!savedFirst) { store.append(id, turn); runCurrent() }
            val ex = vm.ui.value.exchanges.single()
            assertEquals("r-$savedFirst", ex.runId)
            assertTrue(ex.live)
            assertEquals(1, ex.steps.size)
            assertEquals(ComposerMode.Idle, vm.ui.value.composer)
            acts.value = emptyList()
        }
    }

    // 屏上这个对话里别处开始的任务:接着显示,原话用任务自己的,别处插的话也挂上;结束存下后只一条
    @Test fun runStartedElsewhereInThisConversationIsFollowed() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
        val ended = MutableStateFlow<String?>(null)
        val vm = taggedVm(store, ended)
        vm.openConversation("a")
        active.value = listOf("a")
        serve("a", TaskState.Running(1, "打开设置"))
        assertEquals(listOf("查天气", "胶囊里说的"), vm.ui.value.exchanges.map { it.goal })
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.last().status)
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)
        steerFlow.value = listOf(SteerNote("m1", "只看顺丰的", SteerState.Joined))
        runCurrent()
        assertEquals(listOf("只看顺丰的"), vm.ui.value.exchanges.last().steers.map { it.text })
        ended.value = "r2"
        serve("a", TaskState.Succeeded("好了"))
        store.append("a", Turn("胶囊里说的", "好了", "succeeded", runId = "r2", steers = listOf("只看顺丰的")))
        runCurrent()
        assertEquals(listOf("查天气", "胶囊里说的"), vm.ui.value.exchanges.map { it.goal })
        assertEquals(listOf("只看顺丰的"), vm.ui.value.exchanges.last().steers.map { it.text })
    }

    // 打开(或回到)一个有任务在跑、这里还没在跟的对话:当场接上,不等下一次状态变化
    @Test fun openingOrRefreshingAttachesActiveRun() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val vm = taggedVm(store)
        active.value = listOf("a")
        shown.value = "a" to TaskState.Running(2, "看屏幕")
        vm.openConversation("a")
        assertTrue("show:a" in calls)
        assertEquals("胶囊里说的", vm.ui.value.exchanges.single().goal)
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.single().status)

        // 回到前台时再接一次:已经在跟,不另起一条
        vm.refresh()
        assertEquals(1, vm.ui.value.exchanges.size)
    }

    // 开关关着时别的对话的任务不接到屏上这个对话里;这里正在跟的任务被换下显示时让服务显示回来
    @Test fun serialRunInAnotherConversationIsNotAttached() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
        val vm = taggedVm(store)
        vm.openConversation("a")
        active.value = listOf("b")
        serve("b", TaskState.Running(1, "打开设置"))
        assertEquals(listOf("查天气"), vm.ui.value.exchanges.map { it.goal })
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)
        serve("b", TaskState.Succeeded("好了"))
        assertEquals(1, vm.ui.value.exchanges.size)

        assertTrue(vm.send("查快递", ok))
        active.value = listOf("a")
        serve("a", TaskState.Running(1, "", "phone_launch_app"))
        calls.clear()
        // 定时任务在对话 b 里排上,服务显示了它
        active.value = listOf("a", "b")
        serve("b", TaskState.Held(TaskGate.paused("排队中:前面还有 1 个任务")))
        assertEquals(listOf("show:a"), calls)
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.last().status)
    }

    // 还没有对话时接上的任务:连同那个对话存下的轮次一起显示,之后存进来的也对得上
    @Test fun adoptingARunLoadsItsConversation() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
        val vm = taggedVm(store)
        active.value = listOf("a")
        serve("a", TaskState.Running(1, "打开设置"))
        assertEquals("a", vm.ui.value.conversationId)
        assertEquals(listOf("查天气", "胶囊里说的"), vm.ui.value.exchanges.map { it.goal })
    }

    // ---- 别处开始的任务,按服务里真实的先后:先显示(状态送出),再登记进任务列表 ----

    private val goals = mutableMapOf<String, String>()
    private val endedRun = MutableStateFlow<String?>(null)

    private fun TestScope.registryVm(store: ConversationStore, parallel: Boolean) = ChatViewModel(
        backgroundScope, state, stage, actions, store, appLabel = { if (it == "com.chat") "聊天" else it }, endedRunId = endedRun, parallelFlag = { parallel },
        shownTask = shown, activeConversations = active, runGoal = goals::get, steers = steerFlow, activities = acts,
    ).also { runCurrent() }

    /** 服务开始一个任务:先显示它(带编号的状态、状态),之后才登记进任务列表(对话、原话)。 */
    private fun TestScope.startedElsewhere(conversationId: String, goal: String) {
        val s = TaskState.Running(0, "正在准备")
        shown.value = conversationId to s
        state.value = s
        runCurrent()
        goals[conversationId] = goal
        active.value = active.value + conversationId
        runCurrent()
    }

    // 屏上这个对话里别处(悬浮胶囊)发的话:在跑时就显示那句话和步骤,和在这里发的一样
    @Test fun runStartedElsewhereShowsMessageWhileRunning() = runTest {
        for (parallel in listOf(false, true)) {
            val store = ConversationStore(tmp.newFolder())
            store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
            val vm = registryVm(store, parallel)
            vm.openConversation("a")
            startedElsewhere("a", "胶囊里说的")
            assertEquals("parallel=$parallel", listOf("查天气", "胶囊里说的"), vm.ui.value.exchanges.map { it.goal })
            assertEquals(ComposerMode.Busy, vm.ui.value.composer)
            shown.value = "a" to TaskState.Running(1, "打开设置", "phone_launch_app")
            state.value = shown.value!!.second
            runCurrent()
            did(1, "act", "phone_launch_app", "com.chat")
            assertEquals(listOf("打开 App · 聊天"), vm.ui.value.exchanges.last().steps.map { it.text })
            // 回到前台再接一次:不另起一条
            vm.refresh()
            assertEquals(2, vm.ui.value.exchanges.size)
            assertEquals("胶囊里说的", vm.ui.value.exchanges.last().goal)
            endedRun.value = "r2-$parallel"
            serve("a", TaskState.Succeeded("好了"))
            store.append("a", Turn("胶囊里说的", "好了", "succeeded", runId = "r2-$parallel"))
            runCurrent()
            assertEquals(listOf("查天气", "胶囊里说的"), vm.ui.value.exchanges.map { it.goal })
            active.value = emptyList(); acts.value = emptyList(); goals.clear(); calls.clear()
        }
    }

    // 屏上还没有对话时接上别处开始的任务:那句话也在
    @Test fun adoptedRunShowsItsMessage() = runTest {
        val vm = registryVm(ConversationStore(tmp.newFolder()), parallel = false)
        startedElsewhere("a", "胶囊里说的")
        assertEquals("a", vm.ui.value.conversationId)
        assertEquals(listOf("胶囊里说的"), vm.ui.value.exchanges.map { it.goal })
    }

    // 别处在新对话里开始的任务:侧栏列在进行中;打开它(回到 Mika)就看到那句话和在跑的状态
    @Test fun runInNewConversationElsewhereOpensWithMessage() = runTest {
        for (parallel in listOf(false, true)) {
            val store = ConversationStore(tmp.newFolder())
            store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
            val vm = registryVm(store, parallel)
            vm.openConversation("a")
            startedElsewhere("n", "新对话里说的")
            assertEquals(listOf("查天气"), vm.ui.value.exchanges.map { it.goal })
            assertEquals(listOf("n"), vm.otherRunning.value)
            val sections = drawerSections(store.list(), CurrentConversation("a", false, "查天气", ""), vm.otherRunning.value, vm::runningGoal, vm.canSwitch(), 0L)
            assertEquals(listOf("新对话里说的"), sections.running.map { it.title })
            vm.openConversation("n")
            assertEquals("n", vm.ui.value.conversationId)
            assertEquals(listOf("新对话里说的"), vm.ui.value.exchanges.map { it.goal })
            assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.single().status)
            serve("n", TaskState.Succeeded("好了"))
            active.value = emptyList(); goals.clear(); calls.clear()
        }
    }
}
