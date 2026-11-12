package com.wangzan101.mika.capsule

import android.animation.ValueAnimator
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.Log
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import android.graphics.Rect
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ConversationStore
import java.util.UUID
import kotlinx.coroutines.withContext
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.a11y.MainScreenCapture
import com.wangzan101.mika.cap.phone.a11y.OverlayDrag
import com.wangzan101.mika.cap.phone.a11y.OverlayPlacement
import com.wangzan101.mika.cap.phone.a11y.OverlayWindow
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.userscreen.CapturedScreen
import com.wangzan101.mika.core.userscreen.UserScreenMessage
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.task.DeviceStateWatcher
import com.wangzan101.mika.task.TaskCenterState
import com.wangzan101.mika.task.TaskRun
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.task.TaskService.Companion.isActive
import com.wangzan101.mika.takeover.TakeoverQueue
import com.wangzan101.mika.ui.ChatCopy
import com.wangzan101.mika.ui.ChatViewModel
import com.wangzan101.mika.ui.Exchange
import com.wangzan101.mika.ui.ExchangeStatus
import com.wangzan101.mika.ui.ScreenCopy
import com.wangzan101.mika.ui.screenThumbnail
import com.wangzan101.mika.ui.ToolLabels
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.animationsOff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 悬浮胶囊的接线:把各任务的状态([TaskCenterState.runs] 和每个 [TaskRun] 的状态、接管阶段、活动)、开关、
 * Mika 界面在不在前台([AppForeground])、屏幕亮没亮([DeviceStateWatcher])、无障碍服务在不在交给 [CapsuleModel],
 * 按它的结果经 [com.wangzan101.mika.cap.phone.a11y.OverlayHost] 加上、挪动或拆掉浮层窗口。
 * 点一颗或胶囊展开卡片([CapsuleCards] 定内容,只说一件事),点卡片外面、收起按钮或返回收回成一颗(看过的不再展开,模型记着);卡片和胶囊上的按钮都转成任务服务现有的动作。
 * 一颗和卡片各在一个窗口里、各待各的位置(见 [window]):展开时一颗那个窗口空出来,卡片在自己的窗口里从那一颗的方位缩放、淡入,
 * 收起时缩回去,一颗再在边上冒出来([OverlayMotion]:只动图层,不挪窗口,也不逐帧改窗口大小)。
 * 双击一颗或胶囊(见 [DoubleTap]:单击要等双击的时限过去才展开)把用户此刻的屏幕发给卡片里那段对话,和在卡片里发一句话走同一条路;
 * 卡片输入框左边的截屏按钮截下来先挂在输入框上面,可以补一句再发。截的时候浮层先藏起来,截完再出来,一颗上闪一下。
 * 卡片底部的输入框点开时浮层才能接收键盘,发出、收起、点外面、按返回时马上还回去;输入法弹出时胶囊和卡片挪到它上面。
 * 卡片展开时才收那件事的画面(每秒至多 [PREVIEW_FPS] 张,没变不推)。平时是一颗图标,有事要说时展开成胶囊(模型定);
 * 可以拖,松手贴到最近的左边或右边;停的位置按边和高度比例记在 [FeatureFlags],转屏后按比例重新摆。
 * 卡片左右居中(对话卡片固定高,任务卡片按内容高、最多一样高),按着标题栏上下拖,上边的比例另记一份,换卡片时上边不动;
 * 对话卡片的来回从 [ConversationStore] 读(和对话页同一份),
 * 操作过的 App 从那一轮的运行记录([RunStore])里读。
 * 浮层画不出来(服务刚断开、系统不让)时这个服务实例上不再重试,胶囊不出现,通知照旧。全部在主线程。
 */
class CapsuleController(
    private val context: Context,
    private val tasks: TaskCenterState,
    private val foreground: AppForeground,
    /** 几个任务共用的接管队列:卡片和那一条上的先后从这里读。 */
    private val takeoverQueue: TakeoverQueue,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val model = CapsuleModel(::reasonLabel)
    private val flags = FeatureFlags.get(context)
    private val toolLabels by lazy { ToolLabels(knownTools()) }
    private val appLabels = HashMap<String, String>()
    /** 各任务这一问一答此刻的样子,和对话页同一套推法(步骤、要你做的事、结果)。 */
    private val exchanges = HashMap<String, Exchange>()

    private var view by mutableStateOf<CapsuleView.Shown?>(null)
    private var card by mutableStateOf<CardView?>(null)
    private var preview by mutableStateOf<Bitmap?>(null)
    private var zoomed by mutableStateOf(false)
    private var dock by mutableStateOf(flags.capsuleDock())
    private var inputUi by mutableStateOf(CardInputUi())
    /** 截到一屏时加一:一颗上闪一下。 */
    private var flash by mutableIntStateOf(0)
    private val taps = DoubleTap(ViewConfiguration.getDoubleTapTimeout().toLong())
    private var tapJob: Job? = null
    /** 正在截屏(浮层藏着):这时的点按、再截都不算。 */
    private var capturing = false
    /** 在卡片里发了话的那个对话:展开时跟着它的任务,好看到回答。 */
    private var follow: String? = null
    /** 空闲卡片接着的最近那段对话(点开空闲图标时读一次)。 */
    private var idle: CardView.Chat? = null

    /**
     * 对话卡片的来回:[threadOf] 那个对话记下的全部轮次([past],后台从 [ConversationStore] 读,和对话页同一份;还没读到为 null),
     * 排最后 [shownTurns] 轮,接上还没记进去的任务;[thread] 是此刻给界面的。[threadMark] 是上次读时这个对话里有哪些任务、结束没有,
     * 变了(任务结束、拿掉,那一轮这时才记进对话)就重读。[placesOf] 按运行记录编号记着那一轮操作过的 App 和网站。
     */
    private var thread by mutableStateOf<ChatThread?>(null)
    private var threadOf: String? = null
    private var past: List<PastTurn>? = null
    /** 这个对话起好的标题(没有时卡片用第一句话)。 */
    private var pastTitle: String? = null
    private var shownTurns = CapsuleThreads.PAGE
    private var threadMark: Any? = null
    private var threadJob: Job? = null
    private val placesOf = HashMap<String, List<String>>()
    private val runStore by lazy { RunStore(context) }

    /** 卡片看得见的最高高度(像素,按屏幕和输入法定);0 是还没摆过。[cardFraction] 是卡片上边的比例,记在 [FeatureFlags]。 */
    private var cardHeight by mutableIntStateOf(0)
    private var cardFraction = flags.capsuleCardFraction()
    /** 卡片标题栏在窗口里的位置:只有按着它能拖卡片。 */
    private var cardHandle: Rect? = null
    /** 用户屏幕上输入法的上边;没弹出为 null。胶囊和卡片都让开它。 */
    private var imeTop: Int? = null
    private var imeJob: Job? = null

    /** 胶囊此刻的透明度(不碰它几秒后淡下去);[lastActive] 是上次碰到或状态变了的时刻。 */
    private var alpha by mutableStateOf(1f)
    private var lastActive = 0L
    private var look: Any? = null
    private var fadeJob: Job? = null

    /** 卡片展开着;[closing] 是正在缩回胶囊那边(动效做完才真的收起)。 */
    private var expanded = false
    private var closing by mutableStateOf(false)
    /** 卡片真的开始出来了:这时一颗才让开(卡片排版、出来前一颗还在原处,不空着);卡片开始往回缩时一颗就回来。 */
    private var cardOut by mutableStateOf(false)
    /** 展开前那一颗的中心(屏幕纵坐标)和卡片窗口的上边:卡片从那一颗的方位长出来、缩回去。 */
    private var anchorY = 0
    private var cardTop by mutableIntStateOf(0)
    private var previewKey: String? = null
    private var previewJob: Job? = null

    /** 「你来处理」那一条的高度比例(只在这次运行里记着)。 */
    private var barFraction = 0f
    private var dragging = false
    private var dragStartX = 0
    private var dragStartY = 0
    private var placedY = 0
    /** 卡片窗口的上边。 */
    private var cardY = 0
    private var snap: ValueAnimator? = null

    /**
     * 两个浮层窗口,各自待在自己的位置上,展开、收起时谁都不挪:[window] 放一颗、胶囊和那一条,停在边上;[cardWindow] 放卡片,
     * 左右居中(收起时里面是空的,窗口没有大小,不挡下面的 App)。一个窗口在一颗和卡片的位置间来回挪,系统会把挪动做成滑过去的动画
     * (安卓原版的窗口移动动画),看上去是一颗从中间滑回边上、卡片从边上滑到中间。
     */
    private var window: OverlayWindow? = null
    private var cardWindow: OverlayWindow? = null
    /** 窗口加在哪个服务实例上;服务换了,旧窗口已随旧实例拆掉。 */
    private var windowOn: ScreenAccessibility? = null
    /** 在这个服务实例上加窗口失败过:不再试,等服务重连。 */
    private var failedOn: ScreenAccessibility? = null
    private var tick: Job? = null
    private val ticks = MutableStateFlow(0)
    private var last: Snapshot? = null

    private val _showing = MutableStateFlow(false)
    /** 胶囊此刻真的显示在亮着的屏幕上;任务服务据此把「需要你」通知发得安静些。 */
    val showing: StateFlow<Boolean> = _showing.asStateFlow()

    fun start() {
        val screen = DeviceStateWatcher(context) { -1 }
        scope.launch {
            combine(
                combine(runs(), flags.capsuleModeChanges(), foreground.visible, screen.locked, ScreenAccessibility.instance, ::Snapshot),
                ticks,
            ) { s, _ -> s }.collect(::render)
        }
        // 停止后的交代写好(或写不成)时那一轮已改进对话:重读记下的轮次
        scope.launch { tasks.summarizing.collect { threadOf?.let(::loadPast) } }
        // 卡片上这个对话的标题变了(用户在侧栏改了名、后台起好了标题):卡片顶上「接着「…」聊」跟着换
        scope.launch {
            val store = ConversationStore.shared(context)
            store.changes.collect { id ->
                if (id != threadOf) return@collect
                val title = withContext(Dispatchers.IO) { store.title(id) }
                if (id == threadOf && title != pastTitle) {
                    pastTitle = title
                    rebuildThread()
                }
            }
        }
        // 转屏:按记下的边和比例重新摆;系统给新尺寸可能稍晚一点,过一会儿再摆一次
        context.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                place()
                scope.launch {
                    delay(ROTATE_SETTLE_MS)
                    place()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = Unit
        })
    }

    private class Snapshot(
        val runs: List<CapsuleRun>, val mode: CapsuleMode, val mikaInForeground: Boolean, val locked: Boolean,
        val service: ScreenAccessibility?,
    )

    /**
     * 各任务此刻的样子。任务收尾后会从 [TaskCenterState.runs] 里拿掉;拿掉时它的状态已是终态,
     * 再带它一轮,模型才能记下「办好了」并停一会儿。没到终态就被拿掉的(服务被销毁)直接不算。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun runs(): Flow<List<CapsuleRun>> {
        var previous = emptyList<TaskRun>()
        return tasks.runs.flatMapLatest { current ->
            val gone = previous.filter { r -> current.none { it === r } && !r.state.value.isActive() }
            previous = current
            val all = current + gone
            exchanges.keys.retainAll(all.map { it.key }.toSet())
            if (all.isEmpty()) flowOf(emptyList())
            else combine(all.map { r ->
                // 此刻在操作画面:有自己的虚拟屏,或正在用内置浏览器(卡片据此选任务卡片还是对话卡片)
                combine(r.state, r.takeoverStage, r.activities, r.displayId, r.webOpen) { s, st, acts, display, web ->
                    CapsuleRun(
                        r.key, r.conversationId, s, st, r.goal, exchange(r, s, acts),
                        runId = r.runId.value, onScreen = display >= 0 || web,
                    )
                }.combine(r.steers) { c, steers -> c.copy(steers = steers) }
            }) { it.toList() }.combine(takeoverQueue.changes) { list, _ ->
                // 接管队里的先后:排队变了(前面那件交还了)就重读,下一件马上成为「需要你」
                list.map { c ->
                    val owner = all.firstOrNull { it.key == c.key }?.takeover ?: return@map c
                    val ahead = takeoverQueue.ahead(owner)
                    if (ahead < 0) c else c.copy(queueAhead = ahead, queueBehind = takeoverQueue.behind(owner))
                }
            }
        }
    }

    private fun exchange(r: TaskRun, s: TaskState, acts: List<RunEvent.Activity>): Exchange {
        val before = exchanges[r.key] ?: Exchange(r.goal, emptyList(), ExchangeStatus.Working, live = true)
        return ChatViewModel.reduce(before.copy(activities = acts), s, ::appLabel, toolLabels::label).also { exchanges[r.key] = it }
    }

    private fun render(s: Snapshot) {
        last = s
        val service = s.service
        if (windowOn != null && windowOn !== service) {
            window = null
            cardWindow = null
            windowOn = null
        }
        val inputs = CapsuleInputs(
            s.runs, s.mode, s.mikaInForeground, screenInteractive = !s.locked,
            serviceAvailable = service != null && service !== failedOn, cardOpen = expanded,
        )
        val shown = model.update(inputs, System.currentTimeMillis()) as? CapsuleView.Shown
        schedule(model.nextChangeAt)
        if (shown == null || service == null) {
            hide()
            return
        }
        // App 已交到你手上:顶部那一条代替卡片
        if (shown.kind == CapsuleKind.HandedOver) resetCard()
        view = shown
        card = if (expanded) CapsuleCards.of(model.cardRuns(), follow, idle) else null
        if (expanded && card == null) resetCard()
        updateThread(card as? CardView.Chat, s.runs)
        // 换成了没有输入框的内容(例如变成要你同意):输入框收起,焦点还给下面的 App
        if (inputUi.open && card?.let(CapsuleCards::input) == null) closeInput()
        val detail = card as? CardView.Detail
        watchPreview(detail?.takeIf { CapsuleCards.showsScreen(it.content) }?.run?.run?.key)
        val w = window ?: openWindows(service)
        if (w == null) {
            view = null
            card = null
            _showing.value = false
            return
        }
        applyDrag()
        place()
        // 换了状态、件数,展开或缩回:马上恢复,重新计时;只换了一句不算(一颗上看不到那句)
        val nowLook = listOf(shown.kind, shown.pill, shown.count, shown.needCount)
        if (nowLook != look) {
            look = nowLook
            wake()
        } else fade()
        _showing.value = true
    }

    /** 加上两个窗口(先一颗的,再卡片的,卡片在上面);有一个加不上就都不要,退回通知。 */
    private fun openWindows(service: ScreenAccessibility): OverlayWindow? {
        val overlays = service.overlays()
        val ball = overlays.open(OverlayPlacement(Gravity.TOP or Gravity.RIGHT)) { CapsuleOverlay() }
        val cardW = ball?.let { overlays.open(OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL), ::outside) { CardOverlay() } }
        if (ball == null || cardW == null) {
            ball?.close()
            failedOn = service
            Log.w(TAG, "capsule overlay unavailable, falling back to notifications")
            return null
        }
        window = ball
        cardWindow = cardW
        windowOn = service
        for (it in listOf(ball, cardW)) {
            it.onResize(::place)
            it.onTouch(::wake)
        }
        cardW.onBack(::back)
        watchIme(service)
        return ball
    }

    private fun refresh() {
        last?.let(::render)
    }

    private fun hide() {
        tapJob?.cancel()
        taps.reset()
        snap?.cancel()
        fadeJob?.cancel()
        look = null
        imeJob?.cancel()
        imeJob = null
        imeTop = null
        dragging = false
        window?.close()
        window = null
        cardWindow?.close()
        cardWindow = null
        windowOn = null
        view = null
        resetCard()
        card = null
        _showing.value = false
    }

    private fun resetCard() {
        expanded = false
        closing = false
        cardOut = false
        follow = null
        zoomed = false
        cardHandle = null
        closeInput()
        inputUi = CardInputUi()
        watchPreview(null)
        updateThread(null, emptyList())
    }

    // ---------- 输入 ----------

    /** 点了输入框:这时才让浮层能接收键盘,输入法对着它弹出。 */
    private fun openInput() {
        if (inputUi.open) return
        cardWindow?.setFocusable(true)
        inputUi = inputUi.copy(open = true)
    }

    /** 收起输入框,立刻把焦点还给下面的 App。 */
    private fun closeInput() {
        if (!inputUi.open) return
        inputUi = inputUi.copy(open = false)
        cardWindow?.setFocusable(false)
    }

    /** 返回键:先收起输入框,再关放大的画面,最后收起卡片。 */
    private fun back() {
        when {
            inputUi.open -> closeInput()
            zoomed -> zoom(false)
            expanded -> collapse()
        }
    }

    /** 放大或关掉画面;放大时卡片不能拖(没有标题栏)。 */
    private fun zoom(on: Boolean) {
        zoomed = on
        applyDrag()
    }

    /**
     * 发出输入框里的话,和在对话页里发的走同一条路(都记进那个对话):在跑、等条件时插话给它,
     * Mika 问你时作为回答,办完后在同一个对话里接着说,空闲时接着最近的对话或开新对话;后两种之后卡片跟着那个对话的任务看回答。
     */
    private fun send() {
        val text = inputUi.draft.trim()
        val shot = inputUi.screen?.shot
        val c = card ?: return
        val kind = CapsuleCards.input(c)?.kind ?: return
        if (text.isEmpty() && shot == null) return
        inputUi = CardInputUi()
        cardWindow?.setFocusable(false)
        if (shot != null) deliver(c, kind, shot, text) else submit(c, kind, text, CapsuleCards.conversationOf(c))
        refresh()
    }

    /** 按输入框此刻交给谁([kind])发出 [text];开始说时进 [conversationId](为 null 时新开一个)。 */
    private fun submit(c: CardView, kind: CardInputKind, text: String, conversationId: String?) {
        val run = when (c) {
            is CardView.Detail -> c.run.run
            is CardView.Chat -> c.run?.run
        }
        when (kind) {
            CardInputKind.Steer -> run?.let { TaskService.start(context, text, it.conversationId, messageId = UUID.randomUUID().toString()) }
            CardInputKind.Answer -> run?.let { TaskService.answer(context, text, it.key) }
            CardInputKind.Continue -> run?.let { startIn(it.conversationId, text) }
            CardInputKind.Start -> startIn(conversationId ?: ConversationStore.shared(context).newConversation(), text)
        }
    }

    /**
     * 截下的屏幕连同附言 [words] 发出:先存进这句话要去的那个对话(新对话这时就定下编号),
     * 再写成带屏幕的一句话([UserScreenMessage])照常发出;存不下就不发。
     */
    private fun deliver(c: CardView, kind: CardInputKind, shot: CapturedScreen, words: String) {
        val conversationId = CapsuleCards.conversationOf(c) ?: ConversationStore.shared(context).newConversation()
        scope.launch {
            val saved = runCatching { (context.applicationContext as MikaApp).userScreens.save(conversationId, shot) }
                .onFailure { Log.w(TAG, "could not save the screen", it) }.getOrNull() ?: return@launch
            submit(c, kind, UserScreenMessage.compose(saved.id, words), conversationId)
            refresh()
        }
    }

    /**
     * 双击:把此刻的屏幕发给单击会展开的那张卡片里的对话,和在那张卡片里发一句话一样(在跑时插话、问你时作为回答、
     * 办完接着说、空闲时接着最近的对话或开新对话),然后展开卡片看回答。那张卡片没有输入框(要你接管、同意)时
     * 没有地方可发,就和单击一样展开。
     */
    private fun sendScreen() {
        val v = view ?: return
        if (capturing) return
        scope.launch {
            if (v.kind == CapsuleKind.Idle) idle = loadIdle()
            val target = CapsuleCards.of(model.cardRuns(), follow, idle)
            val kind = target?.let(CapsuleCards::input)?.kind
            if (target == null || kind == null) {
                expand()
                return@launch
            }
            val shot = captureScreen() ?: return@launch
            deliver(target, kind, shot, "")
            CapsuleCards.conversationOf(target)?.let { follow = it }
            open()
            refresh()
        }
    }

    /** 卡片里的截屏按钮:截下来挂在输入框上面,补不补一句都行。 */
    private fun captureForCard() {
        if (capturing) return
        scope.launch {
            val shot = captureScreen() ?: return@launch
            val thumb = withContext(Dispatchers.Default) { shot.jpeg?.let(::screenThumbnail) }
            if (expanded) inputUi = inputUi.copy(screen = PendingScreen(shot, thumb))
        }
    }

    /**
     * 截下用户此刻的屏幕:输入框开着先收起(等输入法下去),浮层藏起来、真的从屏幕上下去了再截,截完马上出来、一颗上闪一下;
     * 再在后台整理屏上的字。前台是 Mika 自己、截不到时为 null。
     */
    private suspend fun captureScreen(): CapturedScreen? {
        val service = windowOn ?: return null
        val w = window ?: return null
        val c = cardWindow ?: return null
        capturing = true
        try {
            if (inputUi.open) {
                closeInput()
                withTimeoutOrNull(IME_GONE_MS) { while (imeTop != null) delay(IME_POLL_MS) }
            }
            // 空着的那个窗口(展开时一颗那个、收起时卡片那个)不画东西,不用等它
            suspend fun <T> OverlayWindow.hidden(block: suspend () -> T): T = if (height > 0) hiddenWhile(block) else block()
            val (shot, read) = w.hidden { c.hidden { MainScreenCapture.shoot(service) } }
            flash++
            wake()
            val area = service.overlays().area()
            val r = MainScreenCapture.build(service, shot, read, area.insetTop, area.insetBottom) { ConfirmationNotifier.appLabel(context, it) }
            return (r as? MainScreenCapture.Result.Captured)?.shot
        } finally {
            capturing = false
        }
    }

    /**
     * 在对话 [conversationId] 里开始说 [text];别的任务在跑时排上,不丢。卡片接着跟这个对话;
     * 空闲卡片也改成接着这个对话(新开的对话标题就是这句话)。
     */
    private fun startIn(conversationId: String, text: String) {
        TaskService.start(context, text, conversationId, queue = true)
        follow = conversationId
        idle = idle?.let { CardView.Chat(conversationId, title = if (it.target == conversationId) it.title else ScreenCopy.plain(text)) }
    }

    /** 点开空闲图标:先读最近那段对话是哪个、标题是什么,再展开(来回由 [updateThread] 读)。 */
    private fun openIdle() {
        scope.launch {
            idle = loadIdle()
            open()
            refresh()
        }
    }

    /** 空闲卡片接着的那段对话:最近的那个(没有任何对话时开新对话)。 */
    private suspend fun loadIdle(): CardView.Chat = withContext(Dispatchers.IO) {
        runCatching {
            val last = ConversationStore.shared(context).list().firstOrNull()
            CardView.Chat(last?.id, title = last?.title?.let(ScreenCopy::plain))
        }.getOrElse { CardView.Chat(null) }
    }

    // ---------- 对话卡片的来回 ----------

    /**
     * 对话卡片要的来回:换了对话从头读(先读最近 [CapsuleThreads.PAGE] 轮);这个对话里的任务出现、结束、拿掉时重读记下的轮次
     * (任务结束后那一轮才记进对话);其余时候只按此刻的任务重排(在回答的字跟着长)。不是对话卡片时清掉。
     */
    private fun updateThread(chat: CardView.Chat?, runs: List<CapsuleRun>) {
        val id = chat?.target
        if (id == null) {
            threadJob?.cancel()
            threadOf = null
            past = null
            threadMark = null
            shownTurns = CapsuleThreads.PAGE
            // 空闲卡片的新对话:来回是空的
            thread = if (chat != null) ChatThread(emptyList()) else null
            return
        }
        val mark = runs.filter { it.conversationId == id }.map { it.key to it.state.isActive() }
        if (id != threadOf) {
            threadOf = id
            past = null
            shownTurns = CapsuleThreads.PAGE
            thread = null
        }
        if (mark != threadMark) {
            threadMark = mark
            loadPast(id)
        }
        rebuildThread()
    }

    /** 按记下的轮次和卡片里此刻的任务排出来回;记下的还没读到时先空着。 */
    private fun rebuildThread() {
        val id = threadOf ?: return
        val p = past ?: return
        val live = CapsuleThreads.liveTail(model.cardRuns(), id, p.mapNotNull { it.turn.runId }.toSet())
        val built = CapsuleThreads.build(p, shownTurns, live, tasks.summarizing.value) { CapsuleThreads.places(it.run.exchange?.activities.orEmpty(), ::appLabel) }
        thread = pastTitle?.let { built.copy(title = it) } ?: built
    }

    /** 后台读对话 [id] 记下的轮次,连同要排出来的那几轮操作过的 App 和网站(从运行记录里读,读过的记着),读到后重排。 */
    private fun loadPast(id: String) {
        threadJob?.cancel()
        val known = HashMap(placesOf)
        val n = shownTurns
        threadJob = scope.launch {
            val (turns, found) = withContext(Dispatchers.IO) {
                val turns = runCatching { ConversationStore.shared(context).turns(id) }.getOrDefault(emptyList())
                turns to turns.takeLast(n).mapNotNull { it.runId }.filter { it !in known }.associateWith(::placesIn)
            }
            val title = withContext(Dispatchers.IO) { ConversationStore.shared(context).title(id) }
            placesOf += found
            if (threadOf != id) return@launch
            pastTitle = title
            past = turns.map { PastTurn(it, it.runId?.let(placesOf::get).orEmpty()) }
            rebuildThread()
        }
    }

    /** 那次运行操作过的 App 和网站;记录已清掉或读不了时为空(当作纯聊天)。在后台线程调。 */
    private fun placesIn(runId: String): List<String> = runCatching {
        CapsuleThreads.places(runStore.events(runId).filterIsInstance<RunEvent.Activity>()) { ConfirmationNotifier.appLabel(context, it) }
    }.getOrDefault(emptyList())

    /** 滚到了顶:往前多排 [CapsuleThreads.PAGE] 轮(先读出它们操作过的 App)。 */
    private fun loadEarlier() {
        val id = threadOf ?: return
        val p = past ?: return
        if (p.size <= shownTurns) return
        shownTurns += CapsuleThreads.PAGE
        loadPast(id)
    }

    /** 跟着用户屏幕上的输入法:弹出、收起、变高时重新摆。 */
    private fun watchIme(service: ScreenAccessibility) {
        imeJob?.cancel()
        imeJob = scope.launch {
            service.overlays().imeTop().collect {
                imeTop = it
                place()
            }
        }
    }

    /** 到了模型说的时刻再算一次(换句、办完后缩小、消失)。 */
    private fun schedule(at: Long?) {
        tick?.cancel()
        tick = at?.let { t ->
            scope.launch {
                delay((t - System.currentTimeMillis()).coerceAtLeast(0L))
                ticks.value++
            }
        }
    }

    // ---------- 半透明 ----------

    /** 碰到了:马上恢复,从现在起重新计时。 */
    private fun wake() {
        lastActive = System.currentTimeMillis()
        fade()
    }

    private fun fade() {
        fadeJob?.cancel()
        val v = view ?: return
        val idleFor = System.currentTimeMillis() - lastActive
        alpha = if (expanded) 1f else CapsuleFade.alpha(v.kind, v.needCount, v.pill, idleFor)
        if (!expanded && idleFor < CapsuleFade.IDLE_MS) fadeJob = scope.launch {
            delay(CapsuleFade.IDLE_MS - idleFor)
            fade()
        }
    }

    // ---------- 小画面 ----------

    /** 只给展开的那件事收画面;换了一件或收起就停。 */
    private fun watchPreview(key: String?) {
        if (key == previewKey) return
        previewKey = key
        previewJob?.cancel()
        previewJob = null
        preview = null
        zoomed = false
        val run = key?.let(::runFor) ?: return
        previewJob = scope.launch {
            run.frames.filterNotNull().previewFrames(same = ::sameFrame).flowOn(Dispatchers.Default).collect { preview = it }
        }
    }

    /** 新的一帧和上一张一样(同一张图,或尺寸相同、像素都相同)就不推:网页画面每次给的是新拷贝。 */
    private fun sameFrame(a: Bitmap, b: Bitmap): Boolean =
        a === b || !a.isRecycled && !b.isRecycled && a.width == b.width && a.height == b.height && a.sameAs(b)

    // ---------- 位置与拖动 ----------

    /** 能摆的范围;[ime] 为 true 时让开输入法。 */
    private fun area(ime: Boolean = true): DockArea? = windowOn?.overlays()?.area()?.let {
        val room = room()
        DockArea.of(it, margin = dp(EDGE_MARGIN_DP), gap = dp(TOP_GAP_DP), room = room)
            .let { a -> if (ime) a.above(imeTop, dp(TOP_GAP_DP), room) else a }
    }

    private fun room() = dp(CapsuleShadowRoom.value.toInt())

    private fun isBar() = view?.kind == CapsuleKind.HandedOver

    /**
     * 按记下的位置摆窗口:胶囊贴在记下的那一边,「你来处理」那一条在顶部正中,卡片左右居中、上边按记下的比例摆;
     * 放不下或输入法盖到时往上挪(卡片最高高度同时变矮)。卡片换了高度(换卡片、内容变了)窗口跟着变,再摆一次,上边不动。
     */
    private fun place() {
        val w = window ?: return
        if (dragging || snap?.isRunning == true) return
        val a = area() ?: return
        placeCard(a)
        val h = w.height.coerceAtLeast(dp(PILL_DP))
        if (isBar()) {
            placedY = CapsuleDocking.fitY(CapsuleDocking.y(barFraction, a, dp(BAR_DP)), a, h)
            w.place(OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, placedY))
            return
        }
        // 卡片展开着时这个窗口是空的,照样停在边上:收起时一颗就在原处出来
        val d = dock
        // 输入法盖到原来的位置:胶囊和卡片都挪到它上面,收起后回原处
        val base = area(ime = false) ?: a
        val natural = CapsuleDocking.fitY(CapsuleDocking.y(d.fraction, base, dp(PILL_DP)), base, h)
        placedY = CapsuleDocking.fitY(natural, a, h)
        val side = if (d.side == DockSide.Left) Gravity.LEFT else Gravity.RIGHT
        w.place(OverlayPlacement(Gravity.TOP or side, CapsuleDocking.edgeOffset(d.side, a), placedY))
    }

    /**
     * 摆卡片窗口:左右居中、上边按记下的比例。卡片最高高度按屏幕和输入法定,变了内容跟着重排,窗口变了尺寸再摆一次。
     * 刚展开、窗口还空着时按卡片会有的高度先摆好(对话卡片固定高;任务卡片按内容高,先按最矮的算),内容出来时不用再挪。
     */
    private fun placeCard(a: DockArea) {
        val w = cardWindow ?: return
        val c = card ?: return
        windowOn?.overlays()?.area()?.let { cardHeight = CardDocking.maxHeight(it.height, a, room()) }
        val base = area(ime = false) ?: a
        val expected = if (c is CardView.Chat) cardHeight + 2 * room() else dp(PILL_DP)
        cardY = CardDocking.y(cardFraction, base, a, cardFull(base), maxOf(w.height, expected))
        cardTop = cardY
        w.place(OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, cardY))
    }

    /** 最高的卡片(对话卡片)在没让输入法的范围 [base] 里的窗口高(含阴影边):卡片上边的比例按它算。 */
    private fun cardFull(base: DockArea): Int =
        CardDocking.maxHeight(windowOn?.overlays()?.area()?.height ?: 0, base, room()) + 2 * room()

    /** 能拖哪里:胶囊和那一条整个能拖;卡片只有按着标题栏能拖(中间上下滑是滚动),放大画面时不能拖。 */
    private fun applyDrag() {
        window?.draggable(drag)
        cardWindow?.draggable(cardDrag, cardHandle.takeIf { !zoomed } ?: Rect())
    }

    /** 卡片报来标题栏的位置。 */
    private fun onHandle(r: Rect) {
        if (r == cardHandle) return
        cardHandle = r
        applyDrag()
    }

    /** 按着标题栏上下拖卡片(左右居中不动);松手记下上边的比例,下次展开、换卡片和转屏后按它摆。 */
    private val cardDrag = object : OverlayDrag {
        override fun start() {
            dragging = true
            dragStartY = cardY
        }

        override fun move(dx: Float, dy: Float) {
            val w = cardWindow ?: return
            val a = area() ?: return
            if (!dragging) return
            val base = area(ime = false) ?: a
            w.place(OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, CardDocking.dragY(dragStartY, dy, base, a, cardFull(base), w.height)))
        }

        override fun end(dx: Float, dy: Float) {
            val w = cardWindow
            val a = area()
            val was = dragging
            dragging = false
            if (!was || w == null || a == null) return
            val base = area(ime = false) ?: a
            val full = cardFull(base)
            cardFraction = CardDocking.release(CardDocking.dragY(dragStartY, dy, base, a, full, w.height), base, full)
            flags.setCapsuleCardFraction(cardFraction)
            place()
        }
    }

    /** 拖胶囊(左右上下)或「你来处理」那一条(只上下);卡片见 [cardDrag]。 */
    private val drag = object : OverlayDrag {
        override fun start() {
            val w = window ?: return
            val a = area() ?: return
            snap?.cancel()
            wake()
            dragging = true
            dragStartX = CapsuleDocking.dockedX(dock.side, a, w.width)
            dragStartY = placedY
        }

        override fun move(dx: Float, dy: Float) {
            val w = window ?: return
            val a = area() ?: return
            if (!dragging) return
            val y = CapsuleDocking.fitY(dragStartY + dy.toInt(), a, w.height)
            if (isBar()) w.place(OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, y))
            else w.place(OverlayPlacement(Gravity.TOP or Gravity.LEFT, CapsuleDocking.dragX(dragStartX + dx.toInt(), a, w.width), y))
        }

        override fun end(dx: Float, dy: Float) {
            val w = window
            val a = area()
            if (!dragging || w == null || a == null) {
                dragging = false
                return
            }
            dragging = false
            val y = CapsuleDocking.fitY(dragStartY + dy.toInt(), a, w.height)
            if (isBar()) {
                barFraction = CapsuleDocking.fraction(y, a, dp(BAR_DP))
                place()
                return
            }
            val x = CapsuleDocking.dragX(dragStartX + dx.toInt(), a, w.width)
            dock = CapsuleDocking.release(x, y, w.width, w.height, a)
            flags.setCapsuleDock(dock)
            snapTo(x, y, CapsuleDocking.dockedX(dock.side, a, w.width))
        }
    }

    /** 松手后滑到边上;关了动画时直接摆过去。 */
    private fun snapTo(fromX: Int, y: Int, toX: Int) {
        val w = window ?: return
        placedY = y
        if (animationsOff(context) || fromX == toX) {
            place()
            return
        }
        snap = ValueAnimator.ofInt(fromX, toX).setDuration(SNAP_MS).apply {
            addUpdateListener { w.place(OverlayPlacement(Gravity.TOP or Gravity.LEFT, it.animatedValue as Int, y)) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    snap = null
                    place()
                }
            })
            start()
        }
    }

    /** 读屏时代替拖动:换到另一边。 */
    private fun swapSide() {
        dock = dock.copy(side = if (dock.side == DockSide.Left) DockSide.Right else DockSide.Left)
        flags.setCapsuleDock(dock)
        place()
    }

    // ---------- 点按与卡片 ----------

    /** 点一颗或胶囊:第一下先等一等,双击的时限里没有第二下才展开卡片;有就是双击,发出此刻的屏幕。 */
    private fun tap() {
        if (capturing) return
        when (taps.tap(SystemClock.uptimeMillis())) {
            DoubleTap.Tap.Double -> {
                tapJob?.cancel()
                sendScreen()
            }
            DoubleTap.Tap.First -> {
                tapJob?.cancel()
                tapJob = scope.launch {
                    delay(taps.timeoutMs)
                    if (taps.settle(SystemClock.uptimeMillis())) expand()
                }
            }
        }
    }

    /** 展开卡片(空闲图标是空闲卡片)。 */
    private fun expand() {
        val v = view ?: return
        if (v.kind == CapsuleKind.Idle) openIdle()
        else {
            open()
            refresh()
        }
    }

    /** 展开卡片:先记下此刻那一颗的方位,卡片从那边长出来。 */
    private fun open() {
        if (!expanded) anchorY = placedY + (window?.height ?: 0) / 2
        expanded = true
        closing = false
    }

    /** 点在窗口外面:先关掉放大的画面,再收起卡片。 */
    private fun outside() {
        when {
            zoomed -> zoom(false)
            expanded -> collapse()
        }
    }

    private fun freshChat(fresh: Boolean) {
        idle = idle?.copy(fresh = fresh)
        refresh()
    }

    /** 收起卡片:输入框马上收起(焦点还给下面的 App),卡片缩回那一颗的方位后([closed])才真的收起;关了动画时直接收起。 */
    private fun collapse() {
        if (!expanded) return
        closeInput()
        if (closing) return
        if (animationsOff(context) || card == null) {
            resetCard()
            refresh()
            return
        }
        closing = true
    }

    private fun closed() {
        if (!closing) return
        resetCard()
        refresh()
    }

    /** 卡片缩放的原点:那一颗停靠的那一边,高度是那一颗的中心在卡片上的位置(超出卡片时取上下边)。 */
    private fun cardOrigin(size: Size): TransformOrigin {
        val y = if (size.height > 0f) ((anchorY - cardTop) / size.height).coerceIn(0f, 1f) else 0f
        return TransformOrigin(if (dock.side == DockSide.Right) 1f else 0f, y)
    }

    private fun runFor(key: String): TaskRun? = tasks.runs.value.firstOrNull { it.key == key }

    private val actions = object : CardActions {
        override fun collapse() = this@CapsuleController.collapse()
        override fun stop(runKey: String) = TaskService.cancel(context, runKey)
        override fun takeOver(runKey: String) {
            // 和通知上的「我来处理」一样带着这次等待的编号:点在已经换掉的旧等待上不算数
            TaskService.takeOver(context, waitKey = runFor(runKey)?.takeover?.waitKey, runKey = runKey)
            collapse()
        }
        override fun resume(runKey: String) = TaskService.resume(context, runKey)
        override fun answer(runKey: String, text: String) = TaskService.answer(context, text, runKey)
        override fun approve(runKey: String, requestKey: Int) = TaskService.approve(context, requestKey, runKey)
        override fun decline(runKey: String, requestKey: Int) = TaskService.decline(context, requestKey, runKey)
        override fun openMika(conversationId: String) {
            open(conversationId)
            collapse()
        }
        override fun zoom(on: Boolean) = this@CapsuleController.zoom(on)
        override fun openInput() = this@CapsuleController.openInput()
        override fun draft(text: String) {
            inputUi = inputUi.copy(draft = text)
        }
        override fun send() = this@CapsuleController.send()
        override fun freshChat(fresh: Boolean) = this@CapsuleController.freshChat(fresh)
        override fun loadEarlier() = this@CapsuleController.loadEarlier()
        override fun captureScreen() = captureForCard()
        override fun dropScreen() {
            inputUi = inputUi.copy(screen = null)
        }
    }

    /** 卡片窗口里:展开时是卡片,收起时什么都没有(窗口没有大小)。 */
    @Composable
    private fun CardOverlay() {
        val c = card ?: return
        MikaTheme(reduceMotion = animationsOff(context)) {
            val height = if (cardHeight > 0) with(LocalDensity.current) { cardHeight.toDp() } else Dp.Unspecified
            OverlayMotion(open = !closing, origin = ::cardOrigin, onClosed = ::closed, onOpened = { cardOut = true }) {
                CapsuleCard(c, preview, zoomed, actions, { elapsed(it) }, input = inputUi, thread = thread, height = height, onHandle = ::onHandle)
            }
        }
    }

    /** 边上那个窗口里:一颗、胶囊或那一条;卡片出来后是空的,卡片往回缩时一颗同时从边上冒出来。 */
    @Composable
    private fun CapsuleOverlay() {
        val v = view ?: return
        if (card != null && cardOut && !closing) return
        MikaTheme(reduceMotion = animationsOff(context)) {
            OverlayMotion(open = true, origin = { TransformOrigin(if (dock.side == DockSide.Right) 1f else 0f, 0.5f) }, onClosed = {}) {
                CapsuleContent(
                    v,
                    onTap = ::tap,
                    onSendScreen = ::sendScreen,
                    flash = flash,
                    onStop = { v.runKey?.let { TaskService.cancel(context, it) } },
                    onHandBack = { v.runKey?.let { TaskService.handBack(context, runKey = it) } },
                    side = dock.side,
                    tapLabel = if (v.kind == CapsuleKind.Idle) CapsuleCopy.TALK else CapsuleCopy.EXPAND,
                    onSwapSide = if (v.kind == CapsuleKind.HandedOver) null else ::swapSide,
                    alpha = alpha,
                )
            }
        }
    }

    /** 用时,每秒更新;不显示用时时为 null。 */
    @Composable
    private fun elapsed(startedAt: Long?): String? {
        if (startedAt == null) return null
        val now by produceState(System.currentTimeMillis(), startedAt) {
            while (true) {
                value = System.currentTimeMillis()
                delay(1_000L - value % 1_000L)
            }
        }
        return ChatCopy.clock(now - startedAt)
    }

    /** 回到 Mika 的那个对话(和点通知同一个入口)。 */
    private fun open(conversationId: String?) {
        runCatching { context.startActivity(TaskService.openAppIntent(context, conversationId)) }
            .onFailure { Log.w(TAG, "could not open Mika from capsule", it) }
    }

    private fun appLabel(pkg: String): String = appLabels.getOrPut(pkg) { ConfirmationNotifier.appLabel(context, pkg) }

    private fun reasonLabel(reason: String): String? = when (reason) {
        "captcha" -> R.string.takeover_reason_captcha
        "login" -> R.string.takeover_reason_login
        "access_blocked" -> R.string.takeover_reason_access_blocked
        "payment" -> R.string.takeover_reason_payment
        // 「需要你决定」接在「需要你：」后面重复了,用模型写的话
        else -> null
    }?.let(context::getString)

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    private companion object {
        const val TAG = "MikaCapsule"
        /** 看得见的胶囊离屏幕左右边、离状态栏和底部。 */
        const val EDGE_MARGIN_DP = 12
        const val TOP_GAP_DP = 8
        /** 胶囊、那一条连同阴影边的窗口高度(按比例摆高度时用)。 */
        const val PILL_DP = 40 + 16
        const val BAR_DP = 48 + 16
        const val SNAP_MS = 220L

        const val ROTATE_SETTLE_MS = 300L
        /** 截屏前收起卡片里的输入法,最多等它这么久下去。 */
        const val IME_GONE_MS = 700L
        const val IME_POLL_MS = 30L
    }
}
