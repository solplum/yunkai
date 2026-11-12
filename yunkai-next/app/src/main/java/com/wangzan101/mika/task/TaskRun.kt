package com.wangzan101.mika.task

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.core.cap.web.IdleScroll
import com.wangzan101.mika.core.cap.web.PagePacing
import com.wangzan101.mika.core.cap.web.SitePace
import kotlin.random.Random
import com.wangzan101.mika.core.action.OutwardPacing
import com.wangzan101.mika.core.usage.RunUsageTally
import com.wangzan101.mika.core.shell.AccessibilityRepair
import com.wangzan101.mika.ui.StatusCopy
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.cap.sandbox.SandboxState
import com.wangzan101.mika.cap.sandbox.AndroidSandbox
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.chat.SharedFile
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen
import com.wangzan101.mika.cap.phone.a11y.LazyPhoneScreen
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.a11y.PhoneUnavailable
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.a11y.launchableApps
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.userscreen.UserScreenMessage
import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.SystemPrompt
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.ModelRouter
import com.wangzan101.mika.core.memory.MemoryExtractor
import com.wangzan101.mika.core.memory.MemoryStore
import com.wangzan101.mika.core.memory.UserUtterances
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.skills.SkillDrafter
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.memory.MemoryRuntime
import com.wangzan101.mika.settings.MemorySwitches
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.replay.RouteMatcher
import com.wangzan101.mika.core.replay.RouteRecorder
import com.wangzan101.mika.replay.FileRouteStore
import com.wangzan101.mika.core.agent.JudgedOutcome
import com.wangzan101.mika.core.agent.OutcomeJudge
import com.wangzan101.mika.core.agent.StopSummary
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.ui.ToolLabels
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.child.ChildAgentRunner
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.RunInput
import com.wangzan101.mika.core.run.InputReceipt
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.knowledge.KnowledgeStore
import com.wangzan101.mika.core.knowledge.AppKnowledgeDrafter
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.resource.ResourceScope
import com.wangzan101.mika.core.tool.HandBack
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.vision.VisionService
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.cap.phone.display.LazyAgentDisplay
import com.wangzan101.mika.record.RunHandle
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.RunApproval
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.takeover.HandoverActivity
import com.wangzan101.mika.takeover.HandoverActivityCounter
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.takeover.TakeoverPort
import com.wangzan101.mika.takeover.stoppedAt
import com.wangzan101.mika.vision.BitmapImageOps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import com.wangzan101.mika.core.knowledge.ChannelFacts
import androidx.webkit.WebViewCompat
import com.wangzan101.mika.cap.web.LazyWebPort
import com.wangzan101.mika.cap.web.WebHandover
import com.wangzan101.mika.cap.web.WebSites
import com.wangzan101.mika.cap.web.WebStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.assembly.legacyNames
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.web.WEB

/**
 * 一个任务的全部状态:虚拟屏、循环、关卡、接管、静音、监视。[TaskService] 只管服务生命周期、意图分发与通知。
 * 本任务自己的流只在它是「显示中」的任务([service.taskState.shownRun])时同步写到服务对外的流;
 * 别的任务(并行时)、或被新任务换下去的旧任务的写入只落在自己这份,不会盖掉显示中的那个。
 * [scope] 是服务的作用域:旁路协程挂在它下面,不随任务协程一起取消,由 [execute] 收尾时取消。
 * [slot] 决定它的通知编号与按钮请求码;[parallel] 为 true 时(开关 parallelTasks)打开 App 前查别的任务是否在用。
 */
class TaskRun(
    val conversationId: String,
    val goal: String,
    private val service: TaskService,
    private val scope: CoroutineScope,
    val slot: Int = 0,
    val parallel: Boolean = false,
) {
    internal var provenance = RunProvenance()
    internal var triggerWindowKey: String? = null
    internal var triggerActionGoal: String? = null
    internal var triggerThreadId: String? = null
    internal var mcpExternalId: String? = null
    internal val conditionCheck get() = triggerActionGoal != null
    @Volatile private var children: ChildAgentRunner? = null
    /** 自己的一份;显示中时同步写到服务对外的那份。[show] 时整份抄过去。 */
    /** App 给的模型来源、评测钩子、屏幕画面的开关。 */
    private val env = TaskEnvironment.of(service)
    /** 这段对话对外的会话号:这次运行建的模型(主、轻量、看图,及核查、意图判断、停止交代等旁路)都用它。 */
    private val session = ModelSession.of(conversationId)
    /** App 给的手机服务(共用的 shell 连接、「去设置」提示)、记忆、经验路线、异常退出后的恢复。 */
    private val phoneServices = PhoneServices.of(service)
    private val memoryRuntime = MemoryRuntime.of(service)
    private val routeStore = FileRouteStore.shared(service)
    private val recovery = TaskRecovery.of(service)

    private inner class Mirrored<T>(initial: T, private val shown: MutableStateFlow<T>) {
        private val own = MutableStateFlow(initial)
        val flow: StateFlow<T> = own.asStateFlow()
        var value: T
            get() = own.value
            set(v) {
                synchronized(service.taskState.shownLock) {
                    own.value = v
                    if (service.taskState.shownRun === this@TaskRun) shown.value = v
                }
            }
        fun copyOut() { shown.value = own.value }
    }

    /** 静音等恢复记录按它分开,几个任务各记各的。 */
    val key: String = UUID.randomUUID().toString()
    private val inbox = RunInbox()

    private val _runId = Mirrored<String?>(null, service.taskState._runId)
    private val _sharedFiles = Mirrored<List<SharedFile>>(emptyList(), service.taskState._sharedFiles)
    private val _state = Mirrored<TaskState>(TaskState.Running(0, TaskService.PREPARING), service.taskState._state)
    /** 同一状态带上对话编号,和 [_state] 一起写:界面据此只收屏上那个对话的状态。 */
    private val _tagged = Mirrored<Pair<String, TaskState>?>(conversationId to _state.value, service.taskState._shownTask)
    private val _takeoverStage = Mirrored(Takeover.Stage.None, service.taskState._takeoverStage)
    private val _frames = Mirrored<Bitmap?>(null, service.taskState._frames)
    private val _activities = Mirrored<List<RunEvent.Activity>>(emptyList(), service.taskState._activities)
    /** 活动只追加;发布的是前 n 条的只读视图,每来一条不复制已有的。 */
    private val activityLog = ArrayList<RunEvent.Activity>()
    private fun logView(n: Int): List<RunEvent.Activity> = object : AbstractList<RunEvent.Activity>() {
        override val size = n
        override fun get(index: Int): RunEvent.Activity = synchronized(activityLog) { activityLog[index] }
    }
    private val _steers = Mirrored<List<SteerNote>>(emptyList(), service.taskState._steers)
    private val _displayId = Mirrored(-1, env.agentDisplayId)
    /** 正在用内置浏览器:画中画改显示网页画面。 */
    private val _webOpen = Mirrored(false, env.webScreenOpen)

    val state: StateFlow<TaskState> = _state.flow
    val runId: StateFlow<String?> = _runId.flow
    /** 本任务导出的文件;结束时随这一轮存进对话。 */
    val sharedFiles: StateFlow<List<SharedFile>> = _sharedFiles.flow
    val takeoverStage: StateFlow<Takeover.Stage> = _takeoverStage.flow
    val frames: StateFlow<Bitmap?> = _frames.flow
    /** 本任务到此刻的活动(实际做过的事);悬浮胶囊的卡片按任务取,不只看显示中的那个。 */
    val activities: StateFlow<List<RunEvent.Activity>> = _activities.flow
    /** 本任务的虚拟屏编号;屏到第一次 launch_app 才建,之前和释放之后为 -1。 */
    val displayId: StateFlow<Int> = _displayId.flow
    /** 此刻在用内置浏览器(工具的界面是网页);没用、任务结束后为 false。 */
    val webOpen: StateFlow<Boolean> = _webOpen.flow
    /** 本任务进行中插的话和它们的去向;悬浮胶囊的对话卡片按任务取。 */
    val steers: StateFlow<List<SteerNote>> = _steers.flow
    // 带对话编号的那份先写:收不带编号的状态时读到的编号就已经是这个任务的(界面据此不把别的对话的状态接到屏上)
    private val mirrored = listOf(_runId, _sharedFiles, _tagged, _state, _takeoverStage, _frames, _displayId, _webOpen, _activities, _steers)

    private fun setState(s: TaskState) = synchronized(service.taskState.shownLock) {
        _tagged.value = conversationId to s
        _state.value = s
    }

    /** 让界面显示这个任务:成为显示中的任务,并把自己的各项抄到服务对外的流。 */
    fun show() = synchronized(service.taskState.shownLock) {
        service.taskState.shownRun = this
        mirrored.forEach { it.copyOut() }
    }

    /** 跑 [execute] 的协程,由服务启动后填上。 */
    @Volatile var job: Job? = null
        internal set(v) {
            field = v
            if (cancelRequested) v?.cancel()
        }
    @Volatile private var cancelRequested = false
    /** 用户点了停止:还没交给模型的插话一起作废,不当成下一件事。 */
    @Volatile private var stopPressed = false
    @Volatile private var loop: AgentLoop? = null
    val activeAsk: Delivery.Ask? get() = loop?.activeAsk
    @Volatile private var gate: TaskGate? = null
    @Volatile var takeover: Takeover? = null
        private set
    @Volatile var muteFailedFor: String? = null
        private set
    @Volatile private var awake: KeepAwake? = null
    /** shell 进程没了,虚拟屏和上面的 App 一起没了;任务没法接着做。 */
    @Volatile var shellDied = false
        internal set
    /** 发布与收尾互斥;[ended] 之后不再写状态,迟到的发布不能盖掉终态或重新拿锁。 */
    private val publishLock = Any()
    private var ended = false

    fun cancel() {
        stopPressed = true
        children?.cancelAll(RunId(key))
        val running = loop
        val t = takeover
        when {
            t != null -> scope.launch { t.cancel() }
            running != null -> inbox.cancel()
            else -> {
                // 刚从队里放出来、协程还没挂上时点的取消:挂上时立即取消
                cancelRequested = true
                job?.cancel()
            }
        }
    }

    fun resume() { gate?.resume() }
    fun takeOver(waitKey: Int?) { takeover?.let { t -> scope.launch { t.handOver(waitKey) } } }
    fun handBack(waitKey: Int?) { takeover?.let { t -> scope.launch { t.handBack(waitKey) } } }
    fun finishedMyself(waitKey: Int?) { takeover?.let { t -> scope.launch { t.finishedMyself(waitKey) } } }
    fun skip(waitKey: Int?) { takeover?.let { t -> scope.launch { t.skip(waitKey) } } }
    fun putAside(waitKey: Int?) { takeover?.let { t -> scope.launch { t.putAside(waitKey) } } }

    /** 回答提问;已经不在等(超时后才答、已答过)时调 [tooLate] 告诉用户这条回答没用上。 */
    fun answer(text: String, tooLate: () -> Unit) {
        if (loop == null) return
        if (_state.value is TaskState.WaitingInput) scope.launch {
            if (inbox.send(RunInput.Answer(text)) != InputReceipt.Accepted) tooLate()
        }
        else tooLate()
    }

    /**
     * 同一对话在任务进行中的新原话，交给下一次模型步；准备阶段也会先排在 Inbox。
     * [messageId] 是界面给这句话的编号,界面据此标出它的去向(见 [SteerNote])。
     */
    fun steer(text: String, messageId: String = UUID.randomUUID().toString()) {
        synchronized(service.taskState.shownLock) { _steers.value = _steers.value + SteerNote(messageId, text, SteerState.Pending) }
        scope.launch {
            // 收件箱收下了就马上告诉用户:模型此刻的请求已撤掉,下一步就带着这句话想(交给模型时再改成已加入)
            if (inbox.send(RunInput.Steer(messageId, text)) == InputReceipt.Accepted) markSteers(setOf(messageId), SteerState.Received, from = SteerState.Pending)
            else {
                if (stopPressed) markSteers(setOf(messageId), SteerState.Cancelled)
                else {
                    markSteers(setOf(messageId), SteerState.Next)
                    service.deferMessage(conversationId, text)
                }
            }
        }
    }

    /** 收尾时还没交给模型的插话:作为这个对话的下一件事;停止了([cancelled])就一起作废。 */
    fun handoffUnconsumed(cancelled: Boolean = stopPressed) {
        val left = inbox.handoffUnconsumed()
        markSteers(left.map { it.messageId }.toSet(), if (cancelled) SteerState.Cancelled else SteerState.Next)
        if (!cancelled) left.forEach { service.deferMessage(conversationId, it.text) }
    }

    /** 已交给这个任务的插话,随这一轮存进对话。 */
    /** 这次任务真的用过的能力组(手机、网页、沙箱);记进这一轮,下一轮开局预先展开。 */
    @Volatile var usedGroups: List<String> = emptyList()
        private set

    fun joinedSteers(): List<String> = _steers.value.filter { it.state == SteerState.Joined }.map { it.text }

    /** 把 [ids] 这几句改成 [to];给了 [from] 时只改还在那个状态的(收下和交给模型可能先后颠倒,不往回改)。 */
    private fun markSteers(ids: Set<String>, to: SteerState, from: SteerState? = null) {
        if (ids.isEmpty()) return
        synchronized(service.taskState.shownLock) {
            _steers.value = _steers.value.map { if (it.id in ids && (from == null || it.state == from)) it.copy(state = to) else it }
        }
    }

    /** 运行事件里界面要跟的两样:实际做过的事,和插话已交给模型。 */
    private val liveEvents = object : RunSink {
        override fun event(e: RunEvent) {
            when {
                e is RunEvent.Activity -> synchronized(service.taskState.shownLock) {
                    val n = synchronized(activityLog) { activityLog += e; activityLog.size }
                    _activities.value = logView(n)
                }
                e is RunEvent.State && e.name == "steer_received" -> markSteers(setOf(e.detail), SteerState.Joined)
            }
        }
    }

    /** 通知和卡片的按钮都带着它所属的申报 [key];点在已经换掉的旧通知、旧卡片上不算数。 */
    fun confirm(choice: ConfirmChoice, key: Int?) {
        val running = loop
        val waiting = _state.value as? TaskState.WaitingConfirmation
        if (running != null && waiting != null && key == ConfirmationNotifier.requestKey(waiting)) {
            scope.launch { inbox.send(RunInput.Confirm(choice)) }
        }
    }

    /**
     * 任务的终态:[execute] 收尾之后才写,不经 [publish]。不论显示的是不是自己,都先把本次的运行编号记到
     * 自己对话名下([TaskService.endedRunIds]),界面收到结束状态时按对话取;显示中时也写 [TaskService.endedRunId]。
     */
    fun end(end: TaskState) = synchronized(service.taskState.shownLock) {
        service.taskState._endedRunIds.value = service.taskState._endedRunIds.value + (conversationId to _runId.value)
        if (service.taskState.shownRun === this) service.taskState._endedRunId.value = _runId.value
        setState(end)
    }

    /** 超出并行上限在排队;放出来开跑时 [dequeue]。 */
    fun queue(position: Int) { setState(TaskState.Held(TaskGate.paused(trPlural(R.plurals.queue_ahead, position)))) }
    fun dequeue() { setState(TaskState.Running(0, TaskService.PREPARING)) }

    /** 本任务的沙箱:一个任务一份(导出额度按任务算),工作区是本对话的。 */
    private val sandbox by lazy { AndroidSandbox(service, SandboxState.get(service), onShared = ::addShared) }

    /** 沙箱导出了一个文件(share_file 或 `mika share`)。 */
    fun addShared(f: SharedFile) = synchronized(service.taskState.shownLock) { _sharedFiles.value = _sharedFiles.value + f }

    fun clearTakeoverStage() { _takeoverStage.value = Takeover.Stage.None }

    /** 此刻要显示的状态;已收尾时为空,不再重画通知。 */
    fun shownState(): TaskState? = synchronized(publishLock) { if (ended) null else _state.value }

    suspend fun execute(priorTurns: List<Turn>): TaskState {
        if (Build.VERSION.SDK_INT < Prerequisites.MIN_SDK) return TaskState.Failed(TaskService.TOO_OLD, FailKind.Environment)
        // 手机一侧(内置助手、无障碍、虚拟屏)第一次 launch_app 时才接上:只用网页、沙箱的任务不需要它们
        var connection: com.wangzan101.mika.cap.phone.shell.ShellBinding? = null
        var phone: PhoneSide? = null
        // 虚拟屏、网页显示等:建好当场归它,收尾时一起放掉;不经「建完再记到变量」,取消落在中间也不会漏
        val resources = ResourceScope()
        var watcher: DeviceStateWatcher? = null
        var mute: AudioMute? = null
        var shellDeath: IBinder.DeathRecipient? = null
        var shellBinder: IBinder? = null
        var record: RunHandle? = null
        var runStore: RunStore? = null
        // 这次运行里经计量的模型请求(含旁路调用),收尾时记进运行记录
        val tally = RunUsageTally()
        // 第一次打开网址时才建;没建过就不用关
        var webStack: WebStack? = null
        // 当前界面(手机/网页)要看分发器;画中画的画面来源随它切换
        var dispatcher: ToolDispatcher? = null
        var mcpEvents: AutoCloseable? = null
        var liveFeed: AutoCloseable? = null
        // 询问 / 自主:开工时取一份,开发者选项里换开关时跟着变;收尾时交还
        val approvalControl = (service.application as MikaApp).approvalControl
        var approval: RunApproval? = null
        // 任务期间的旁路协程(助手连接状态、静音与挪走监视、状态发布),任务结束一并取消
        val side = CoroutineScope(
            scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) +
                CoroutineExceptionHandler { _, e -> Log.w(TAG, "task side job failed", e) },
        )
        sideScope = side
        try {
            val model = env.modelFactory(service, session) ?: return TaskState.Failed(tr(R.string.fail_no_main_model), FailKind.Environment)
            val light = env.lightModelFactory(service, session)
            val vision = env.visionModelFactory(service, session)

            val (knowledge, skills) = withContext(Dispatchers.IO) {
                // 用户对某个 App/站点的补充放在私有目录 personal/apps、personal/sites 下
                val userNotes = KnowledgeStore.loadNotes(File(service.filesDir, "personal"))
                // 自学的 App 知识里改名前写下的工具名换成现在的
                val learnedNotes = legacyNames().let { legacy -> memoryRuntime.appKnowledge().notes().mapValues { legacy.rewrite(it.value) } }
                KnowledgeStore(KnowledgeStore.loadBundled(), userNotes, learnedNotes) to GuideCatalog.loadBundled()
            }
            val metrics = mainScreenMetrics()
            // 助手是否连着;没接上手机之前当作连着(网页、沙箱的步骤不看它)
            val shellUp = MutableStateFlow(true)
            val states = DeviceStateWatcher(service) { id -> phone?.shell?.topTaskId(id) ?: -1 }.also { watcher = it }

            /** 接上手机:连内置助手、等无障碍、备好(到第一次 launch_app 才建的)虚拟屏;缺一样就报 phone_unavailable。 */
            suspend fun connectPhone(): AndroidScreen {
                val conn = connection ?: phoneServices.sharedShell.acquire().also { connection = it }
                val shell = withTimeoutOrNull(SHELL_WAIT_MS) { conn.shell.filterNotNull().first() }
                    ?: run {
                        phoneServices.setupPrompt.request()
                        throw PhoneUnavailable(NO_PHONE_HELPER)
                    }
                // 服务卡在「开着却没运行」、正在自动重启时多等一会儿,修好了接着做,放弃了照常报错
                val a11y = AccessibilityRepair.awaitService(ScreenAccessibility.instance, phoneServices.recovery.accessibilityRepair.phase, A11Y_WAIT_MS)
                    ?: throw PhoneUnavailable(NO_ACCESSIBILITY)
                recovery.beginRun(shell, key)
                // 只有用过手机的任务才跟着 shell 的死活:shell 没了,虚拟屏和上面的 App 一起没了
                shellDeath = IBinder.DeathRecipient {
                    shellDied = true
                    val running = loop
                    if (running != null) running.cancel() else job?.cancel()
                }.also { shell.asBinder().linkToDeath(it, 0) }
                shellBinder = shell.asBinder()
                side.launch { conn.shell.map { it != null }.collect { shellUp.value = it } }
                val agentDisplay = LazyAgentDisplay(metrics.widthPixels, metrics.heightPixels) {
                    suspend fun create() = resources.acquire(Dispatchers.IO, AgentDisplay::release) { AgentDisplay(shell, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi) }
                    try {
                        create()
                    } catch (e: Exception) {
                        if (e is CancellationException || e !is IllegalStateException && e !is RemoteException) throw e
                        if (!parallel || !service.otherRunHasDisplay(this@TaskRun)) throw e
                        // 外壳建不出第二块屏:这次运行期间不再并行;本任务排在后面,等别的任务的屏都放掉再建
                        service.taskState.markSecondDisplayFailed()
                        publish(TaskState.Held(TaskGate.paused(WAITING_DISPLAY)))
                        while (service.otherRunHasDisplay(this@TaskRun)) delay(DISPLAY_RETRY_MS)
                        create()
                    }.also { d ->
                        _displayId.value = d.displayId
                        side.launch { d.frames.collect { if (dispatcher?.surface != WEB) _frames.value = it } }
                    }
                }
                val screen = AndroidScreen(
                    a11y, shell, agentDisplay, { recovery.markClipHeld(it) },
                    clipLock = service.clipLock, checkOtherTasks = parallel,
                    reserve = { pkg -> service.reservePackage(pkg, key) { runCatching { shell.displayOfPackage(pkg) }.getOrDefault(-1) >= 0 } },
                    clipSaved = { original, ours -> recovery.saveClip(original, ours) },
                    handedOver = { handoverRef?.handedOver == true },
                )
                val audio = recovery.audioMute(shell, key).also { mute = it }
                val side0 = PhoneSide(shell, a11y, screen, agentDisplay, audio)
                phone = side0
                watchTarget(side0, states)
                return screen
            }
            val phoneScreen = LazyPhoneScreen(metrics.widthPixels, metrics.heightPixels,
                listApps = { withContext(Dispatchers.IO) { launchableApps(service.packageManager, service.packageName) } }) { connectPhone() }
            // 挪回虚拟屏后等窗口出现,Agent 接下来读屏才读得到
            suspend fun moveTask(taskId: Int, to: Int): Boolean {
                val p = phone ?: return false
                val moved = withContext(Dispatchers.IO) { runCatching { p.shell.moveTask(taskId, to) }.getOrDefault(false) }
                if (moved && to == p.display.current?.displayId) p.screen.awaitContent()
                return moved
            }
            // 挪回去的 App 原本就在虚拟屏上,屏一定已经建好;没有屏就没有可挪回的
            suspend fun moveBackToAgent(taskId: Int): Boolean {
                val id = phone?.display?.current?.displayId ?: return false
                return moveTask(taskId, id)
            }
            val taskGate = TaskGate(states.locked, shellUp) {
                val t = phone?.screen?.target?.value
                t != null && t.taskId >= 0 && moveBackToAgent(t.taskId)
            }
            gate = taskGate
            // 用户在「干活时」关掉了常亮就不拿唤醒锁
            awake = if (FeatureFlags.get(service).keepScreenOn()) KeepAwake(service.wakeLock.lease(), side) else null
            // 记录开不起来就不记,任务照跑
            val sink: RunSink = withContext(Dispatchers.IO) {
                runCatching {
                    val runs = RunStore(service).also { runStore = it }
                    runs.open(goal).also { record = it; _runId.value = it.runId }
                }.onFailure { Log.w(TAG, "run record unavailable, running without it", it) }.getOrNull()
            } ?: RunSink.NONE
            // 装了哪些 App:每个任务读一次,读不到就当没有;参考资料用
            val apps = try { phoneScreen.listApps() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
            val speed = FeatureFlags.get(service).speedFlags()
            // 记忆与个人技能:进程内同一份;总开关关着就不给记忆(不检索、不注入、不提取、没有记忆工具)
            val switches = memoryRuntime.settings().switches.value.let { sw ->
                sw.copy(learnSkills = env.evalHooks.learnSkills ?: sw.learnSkills, autoRemember = env.evalHooks.rememberMemory ?: sw.autoRemember)
            }
            val memory = if (switches.useMemory) withContext(Dispatchers.IO) { memoryRuntime.store() } else null
            val profileSnapshot = if (switches.useMemory) withContext(Dispatchers.IO) {
                runCatching { memoryRuntime.profile().snapshot() }.onFailure { Log.w(TAG, "profile unavailable", it) }.getOrDefault(emptyList())
            } else emptyList()
            val utterances = memory?.let { UserUtterances() }
            // 改名前沉淀的个人技能:里面的旧工具名载入时换成现在的
            val catalog = GuideCatalog.withPersonal(skills, memoryRuntime.skills(), legacyNames()::rewrite)
            // 内置浏览器:每个任务自己的后台网页显示、标签池和脚本引擎,第一次打开网址时才建(只用手机的任务不建);
            // 系统里没有 WebView 时不提供网页工具
            val webInstalled = runCatching { WebViewCompat.getCurrentWebViewPackage(service) != null }.getOrDefault(false)
            val web = if (!webInstalled) null else LazyWebPort {
                val stack = resources.acquire(Dispatchers.Main, WebStack::close) { WebStack.create(
                        service, key, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                        // 下载、上传走本对话的工作区:只是 App 私有目录里的文件夹,不要求沙箱已装好
                        workspace = SandboxDirs(service).let { d -> WebStack.Workspace(d.workspace(conversationId), d.gate, d.tree) },
                    ) }
                webStack = stack
                side.launch {
                    // 网页界面时画中画显示网页显示的画面;换界面时先清掉上一边的画面;页面在用户手上(接管中)时不显示
                    var last: String? = null
                    while (true) {
                        delay(WEB_FRAME_MS)
                        val surface = dispatcher?.surface
                        if (surface != last) {
                            _frames.value = null
                            last = surface
                        }
                        val onWeb = surface == WEB
                        when {
                            onWeb && stack.handedOver -> _frames.value = null
                            onWeb -> withContext(Dispatchers.IO) { stack.host.latestFrame() }?.let { _frames.value = it }
                        }
                        _webOpen.value = onWeb
                    }
                }
                stack.screen
            }
            if (conditionCheck && !webInstalled) return TaskState.Failed(tr(R.string.fail_no_web), FailKind.Environment)
            // 这个对话里用户发来的屏幕(跟着对话存在工作区里)
            val userScreens = (service.application as MikaApp).userScreens.source(conversationId)
            val runSpec = RunSpec(RunId(key), goal, conversationId,
                priorTurns,
                provenance, if (conditionCheck) setOf("web", "sandbox") else null, readOnly = conditionCheck)
            val runApproval = approvalControl.start().also { approval = it }
            val childRunner = if (provenance.origin == RunOrigin.Ui && web != null) ChildAgentRunner(
                create = { childSpec ->
                    val childTools = toolDispatcher(phoneScreen, model.capabilities.vision,
                        web = web, webPacing = { PagePacing.human() }, webSitePace = { SitePace.of(catalog.sitePace(it)) },
                        webIdleScroll = { IdleScroll(Random.Default) }, sandbox = sandbox, workspace = { conversationId }, runSpec = childSpec,
                        conversationId = conversationId, inlineScreen = speed.inlineScreen,
                        adaptiveVision = speed.adaptiveVision)
                    AgentLoop(model, childTools, SystemPrompt.identity(), maxSteps = childSpec.stepBudget ?: AgentLoop.UNLIMITED_STEPS,
                        // 子任务跟着询问 / 自主;询问时它也过关卡,子任务里花钱的一步一律不做
                        confirmations = runApproval.gate(), approvals = runApproval, autonomous = runApproval.startedAutonomous,
                        reviewFinalAnswer = false, flags = speed, outwardPacing = OutwardPacing.human())
                },
                store = (service.application as MikaApp).childRuns,
                ownerAlive = { job?.isActive == true },
            ).also { children = it } else null
            val tools = toolDispatcher(phoneScreen, model.capabilities.vision, catalog, recordFrames = true, vision = vision?.let { VisionService(it, BitmapImageOps) },
                stableRefs = speed.inlineScreen, inlineScreen = speed.inlineScreen,
                adaptiveVision = speed.adaptiveVision,
                deepLinks = speed.deepLinks, memory = memory, profile = if (memory != null) memoryRuntime.profile() else null,
                utterances = utterances, conversationId = conversationId,
                web = web,
                // 同一个网站一页一页看:换页前补足停留,不连着秒开
                webPacing = { PagePacing.human() },
                // 停留时像人一样把页面轻轻滚几下
                webIdleScroll = { IdleScroll(Random.Default) },
                // 上传只认本对话工作区里的普通文件(与沙箱同一套路径规则);技能目录、工作区外一律越界
                workspacePath = web?.let { { p: String -> sandbox.uploadCheck(conversationId, p) } },
                // 工作区 = 对话:同一对话的追问接着用上一轮的文件,不同对话互不可见
                sandbox = sandbox, workspace = { conversationId },
                onSandboxCommand = { sandboxNote(StatusCopy.sandboxCommand(it, sandboxOnline())) },
                onSandboxProgress = { sandboxNote(StatusCopy.sandboxProgress(it, sandboxOnline())) },
                onSandboxPreparing = { sandboxNote(StatusCopy.RUNTIME_PREPARING) },
                onSandboxScript = { sandboxNote(StatusCopy.sandboxScript(sandboxOnline())) },
                imageOps = BitmapImageOps, runSpec = runSpec,
                userScreens = userScreens,
                scheduleTrigger = if (provenance.origin == RunOrigin.Ui) { trigger -> TriggerRuntime.schedule(service, trigger) } else null,
                scheduleAdmin = if (provenance.origin == RunOrigin.Ui) LocalScheduleAdmin(service) else null,
                childHost = childRunner)
            dispatcher = tools
            val agent = AgentLoop(
                model, tools,
                SystemPrompt.identity(),
                // 助手断开、被挪走只在这一步用的是手机时才查
                gate = { w ->
                    if (phoneScreen.acquired && dispatcher?.surface != WEB) phone?.screen?.let { awaitUserPick(it, taskGate, w) }
                    taskGate.pass({ phoneScreen.acquired && dispatcher?.surface != WEB }, w)
                }, knowledge = knowledge, sink = sink, installedApps = { apps },
                // 选渠道用的事实:装了哪些 App、在 Mika 里登录过哪些网站、有没有内置浏览器
                channelFacts = {
                    val registry = WebSites.registry(service)
                    ChannelFacts(
                        installed = apps.map { it.first }.toSet(),
                        loggedInSites = withContext(Dispatchers.IO) { registry.list().filter { it.loggedInByUser }.map { it.host }.toSet() },
                        webAvailable = web != null,
                        challengedSites = withContext(Dispatchers.IO) { registry.challengedSites() },
                    )
                },
                maxSteps = env.evalHooks.maxSteps ?: AgentLoop.UNLIMITED_STEPS,
                reviewFinalAnswer = false,
                inputTimeoutMs = TaskService.INPUT_TIMEOUT_MS,
                // 询问 / 自主:询问时只有花钱的一步先问(有确认关卡和 request_confirmation);自主时连付款也不问。
                // 开工时询问、中途换成自主的,下一步起不再问;开工时自主的,换成询问留给下一个任务
                confirmations = runApproval.gate(),
                approvals = runApproval,
                autonomous = runApproval.startedAutonomous,
                confirmTimeoutMs = TaskService.CONFIRM_TIMEOUT_MS,
                selfPackage = service.packageName,
                flags = speed,
                // 只在 lightSteps 打开、轻量模型能调工具时生效
                router = light?.let { ModelRouter(model, it) },
                intentJudge = if (provenance.origin == RunOrigin.Ui) light ?: model else null,
                // 选路线是辅助判断:用轻量模型,没配用主模型
                routes = if (speed.replay) RouteMatcher(light ?: model, routeStore, knowledge) else null,
                skills = catalog,
                memory = memory,
                interaction = service.interaction(conversationId),
                profileSnapshot = profileSnapshot,
                streamReplies = true,
                userScreens = userScreens,
                // 同一网站、App 里连着联系别人、提交:两次之间隔一段随机的时间
                outwardPacing = OutwardPacing.human(),
            )
            loop = agent
            liveFeed = agent.events.subscribe(liveEvents)
            mcpExternalId?.let { mcpEvents = (service.application as MikaApp).mcpTasks.attach(it, agent.events) }
            kit = LearnKit(switches, memory, utterances, tools, agent, catalog, knowledge, model, light, runStore, agent.flags.replay, priorTurns)
            suspend fun <T> io(fallback: T, call: () -> T): T =
                withContext(Dispatchers.IO) { runCatching(call).getOrDefault(fallback) }
            // 接管期间才订阅主屏的无障碍事件:平时没人订阅,服务不复制事件
            var counter: HandoverActivityCounter? = null
            var counting: Job? = null
            val handover = Takeover(object : TakeoverPort {
                override suspend fun agentTopTask() = phone?.let { p -> p.display.current?.let { d -> io(-1) { p.shell.topTaskId(d.displayId) } } } ?: -1
                override suspend fun targetTask() = phone?.screen?.target?.value?.taskId ?: -1
                override fun targetPackage() = phone?.screen?.target?.value?.packageName
                override suspend fun packageOf(taskId: Int) = phone?.let { p -> io(null) { p.shell.packageOfTask(taskId) } }
                override suspend fun displayOf(taskId: Int) = phone?.let { p -> io(-1) { p.shell.displayOfTask(taskId) } } ?: -1
                override suspend fun moveToMain(taskId: Int) = moveTask(taskId, Display.DEFAULT_DISPLAY)
                override suspend fun moveBack(taskId: Int) = moveBackToAgent(taskId)
                override fun setSound(packageName: String, on: Boolean) {
                    val audio = phone?.audio ?: return
                    if (on) {
                        if (!audio.restore(packageName)) Log.w(TAG, "could not restore sound of $packageName for takeover")
                    } else if (FeatureFlags.get(service).muteTarget() && !audio.mute(packageName)) {
                        muteFailedFor = packageName
                    }
                }
                override fun watchInput(packageName: String) {
                    counting?.cancel()
                    val c = HandoverActivityCounter(packageName).also { counter = it }
                    // 立即订阅:接着发生的第一下操作不能漏
                    val a11y = phone?.a11y ?: return
                    counting = side.launch(start = CoroutineStart.UNDISPATCHED) {
                        a11y.eventsFor(Display.DEFAULT_DISPLAY).collect { e ->
                            c.onEvent(Display.DEFAULT_DISPLAY, e.packageName, e.eventType, e.className, e.eventTime, e.windowId to e.source?.hashCode())
                        }
                    }
                }
                override suspend fun handoverActivity(): HandoverActivity? {
                    val c = counter ?: return null
                    val pm = service.packageManager
                    val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString() }.getOrNull()
                    return HandoverActivity(c.inputCount, stoppedAt(phone?.a11y?.let { mainWindowTitle(it, c.packageName) }, label, c.lastActivity))
                }
                override fun stopWatching() {
                    counting?.cancel()
                    counting = null
                    counter = null
                }
                override suspend fun resume(result: HandBack) { inbox.send(RunInput.HandBack(result)) }
                override fun cancel() { inbox.cancel() }

                // 网页界面:把当前标签(同一个页面)拿到主屏的接管界面;手机界面返回 null,走挪任务
                override suspend fun showWeb(reason: String, key: Int): Boolean? {
                    // 没打开过网页就不会在网页界面上
                    val w = webStack ?: return null
                    if (tools.surface != WEB) return null
                    val message = takeoverMessage(_state.value)
                    return withContext(Dispatchers.Main) {
                        w.showForTakeover(reason, message, key, WebHandover.taskActions(service, key, this@TaskRun.key)) { takeover?.waitKey }
                    }
                }

                override fun webBusy() = WebHandover.loginOpen

                override suspend fun returnWeb(loggedIn: Boolean): HandoverActivity? =
                    webStack?.let { w -> withContext(Dispatchers.Main) { w.returnFromTakeover(loggedIn) } }
            }, side, queue = service.takeoverQueue)
            takeover = handover
            handoverRef = handover

            side.launch {
                agent.state.collect {
                    if (it == TaskState.Idle) return@collect
                    if (it is TaskState.Running && it.preview != null && gate?.movedAwayPending == true) return@collect
                    val previous = _state.value as? TaskState.Running
                    if (it is TaskState.Running && previous != null && previous.preview != it.preview &&
                        previous.copy(preview = it.preview) == it) {
                        // 文字增量只刷新聊天；通知、接管和设备唤醒不需要每个 token 都重跑。
                        synchronized(publishLock) { if (!ended) setState(it) }
                        return@collect
                    }
                    handover.onState(it)
                    publish(it)
                    env.evalHooks.answerFor(it)?.let { answer -> agent.answerInput(answer) }
                    env.evalHooks.decisionFor(it)?.let { approved -> agent.answerConfirmation(approved) }
                }
            }
            // 别的任务的接管进出队时,本任务的接管通知要跟着换(排到最前、或后面多了一个)
            if (parallel) side.launch { service.takeoverQueue.changes.collect { service.refreshNotifications(this@TaskRun) } }
            side.launch {
                combine(handover.stage, handover.problem) { stage, _ -> stage }.collect {
                    _takeoverStage.value = it
                    service.refreshNotifications(this@TaskRun)
                }
            }
            return withContext(tally) { agent.run(runSpec, inbox) }.also { usedGroups = agent.usedGroups.toList() }
        } finally {
            handoffUnconsumed()
            side.cancel()
            _frames.value = null
            _webOpen.value = false
            synchronized(publishLock) { ended = true }
            awake?.close()
            awake = null
            shellDeath?.let { d -> runCatching { shellBinder?.unlinkToDeath(d, 0) } }
            loop = null
            children = null
            mcpEvents?.close()
            liveFeed?.close()
            approval?.let { approvalControl.end(it) }
            gate = null
            takeover = null
            handoverRef = null
            sideScope = null
            withContext(Dispatchers.IO + NonCancellable) {
                // 前面哪步抛错,屏和网页显示也照放(按建的反序)
                try {
                    record?.let { r ->
                        r.event(tally.event(System.currentTimeMillis()))
                        r.close()
                        if (BuildConfig.DEBUG) runStore?.mirrorToExternal(r.runId)
                    }
                    // 恢复抛错也要撤掉「在跑」的登记,否则这份记录永远不归恢复处理
                    try { mute?.restoreAll() } finally { recovery.endRun(key) }
                    watcher?.close()
                } finally {
                    resources.close()
                }
            }
            // 屏释放后、对外报结束前清掉:界面看到任务结束时已经没有屏了
            _displayId.value = -1
            if (connection != null) phoneServices.sharedShell.release()
        }
    }

    /** 手机一侧:用过手机的任务才有。 */
    private class PhoneSide(
        val shell: com.wangzan101.mika.shell.IMikaShell, val a11y: ScreenAccessibility, val screen: AndroidScreen,
        val display: LazyAgentDisplay, val audio: AudioMute,
    )

    @Volatile private var handoverRef: Takeover? = null
    @Volatile private var sideScope: CoroutineScope? = null

    /**
     * 系统在用户主屏上问打开哪一个(见 [AndroidScreen.userPick]):停在这里,胶囊上请用户在屏幕上选一下,选好自动接着做;
     * 选出来的 App 要用户亲手处理的,留在主屏,请用户处理好了点继续。不在等时直接返回。
     */
    private suspend fun awaitUserPick(screen: AndroidScreen, gate: TaskGate, setWaiting: (TaskState?) -> Unit) {
        val pick = screen.userPick.value ?: return
        setWaiting(TaskState.Held(TaskGate.userStep(TaskGate.pickCopy(pick.app))))
        val r = screen.awaitUserPick() as? AndroidScreen.PickResult.NeedsUser ?: return
        gate.pauseForUser(TaskGate.finishCopy(r.app), setWaiting)
        screen.afterUserStep(r)
    }

    /** 目标 App 换了就静音,并盯着它有没有被用户挪到主屏。 */
    private fun watchTarget(p: PhoneSide, states: DeviceStateWatcher) {
        val side = sideScope ?: return
        side.launch(Dispatchers.IO) {
            p.screen.target.filterNotNull().collectLatest { t ->
                // 用户在「干活时」关掉了静音就不碰它的声音
                if (FeatureFlags.get(service).muteTarget() && !p.audio.mute(t.packageName)) {
                    muteFailedFor = t.packageName
                    publish(_state.value)
                }
                // 有目标 App 就说明 launch_app 已经建好了屏
                val displayId = p.display.current?.displayId
                if (t.taskId >= 0 && displayId != null) {
                    states.taskMovedAway(displayId, t.taskId).collect {
                        // 用户点「我来处理」后 App 本来就该在主屏
                        if (handoverRef?.handedOver == true) return@collect
                        gate?.onTaskMovedAway()
                        publish(_state.value) // 立即显示暂停,不等下一个改动屏幕的工具
                    }
                }
            }
        }
    }

    /** 任务收尾后判结局、学东西要用的;[execute] 里填上。 */
    private class LearnKit(
        val switches: MemorySwitches, val memory: MemoryStore?, val utterances: UserUtterances?, val dispatcher: ToolDispatcher,
        val agent: AgentLoop, val catalog: GuideCatalog, val knowledge: KnowledgeStore,
        val model: ChatModel, val light: ChatModel?, val runStore: RunStore?,
        val routesOn: Boolean, val priorTurns: List<Turn>,
    )
    @Volatile private var kit: LearnKit? = null

    /** 停下时正在写的那段字(模型请求已流出的可见文字);收尾前取,收尾后状态就换成终态了。 */
    fun draftText(): String? = (_state.value as? TaskState.Running)?.preview?.takeIf(String::isNotBlank)

    /** 停止后有没有可交代的:做过至少一件事,或停下时已经写出了字。 */
    fun hasStopMaterial(draft: String?): Boolean = synchronized(activityLog) { activityLog.isNotEmpty() } || !draft.isNullOrBlank()

    /**
     * 用户停止之后:按这次已经做了的事写几行交代(见 [StopSummary]),只读运行记录,不再操作任何东西。
     * 轻量模型优先,没配用主模型;没模型、出错、超时就按记录直接拼。没什么可交代的为 null。
     */
    suspend fun stopSummary(draft: String?): String? {
        val k = kit
        val runId = _runId.value
        val recorded = withContext(Dispatchers.IO) { runId?.let { id -> runCatching { k?.runStore?.events(id) }.getOrNull() } }
        val events = recorded?.takeIf { e -> e.any { it is RunEvent.Activity } } ?: synchronized(activityLog) { activityLog.toList() }
        if (!StopSummary.worthSaying(events, draft)) return null
        // 还没开出模型就停下的(准备阶段):按设置现取
        val (main, light) = if (k != null) k.model to k.light else withContext(Dispatchers.IO) {
            runCatching { env.modelFactory(service, session) }.getOrNull() to runCatching { env.lightModelFactory(service, session) }.getOrNull()
        }
        val labels = ToolLabels(knownTools())
        return StopSummary(light ?: main, main).summarize(k?.agent?.taskText ?: goal, events, draft, labels::label) {
            ConfirmationNotifier.appLabel(service, it)
        }
    }

    /**
     * 结果交给用户之后调用(不拖慢、不改交出的回答):回答按用户的要求判结局([OutcomeJudge],轻量模型优先),
     * 记进运行记录和这一轮对话;只有办成的才记路线、给回放的路线记成功。
     * 放弃、失败、取消的不判(结局已经清楚),返回 null;判不成是 [RunOutcome.Unknown]。
     */
    suspend fun settleOutcome(end: TaskState, conversations: ConversationStore): JudgedOutcome? {
        val k = kit ?: return null
        if (end !is TaskState.Succeeded) return null
        val judged = OutcomeJudge(k.light ?: k.model, k.model).judge(k.agent.taskText ?: goal, end.answer, k.priorTurns)
        val runId = _runId.value
        if (runId != null) withContext(Dispatchers.IO) {
            k.runStore?.append(runId, judged.event(System.currentTimeMillis()))
            if (BuildConfig.DEBUG) k.runStore?.mirrorToExternal(runId)
            runCatching { conversations.updateTurn(conversationId, runId) { it.copy(result = judged.outcome.wire) } }
                .onFailure { Log.w(TAG, "could not save run outcome", it) }
        }
        k.agent.noteOutcome(judged.outcome)
        // 只有办成、核查通过的才记成路线;从落盘的运行事件里取。带着用户屏幕的话意思在屏幕上,不按字记路线
        if (k.routesOn && judged.outcome == RunOutcome.Done && end.verified && runId != null && !UserScreenMessage.has(goal)) k.runStore?.let { s ->
            saveRoute(goal, withContext(Dispatchers.IO) { s.events(runId) }, k.dispatcher.index)
        }
        return judged
    }

    /**
     * 结果通知发出之后调用:记下用到的个人技能的结果、提取记忆、起草技能,提示挂到这一轮的结果卡片上并存进对话。
     * 在进程级的作用域里跑,服务退出也不中断。
     */
    suspend fun learnAfter(end: TaskState, outcome: RunOutcome?, conversations: ConversationStore) {
        val k = kit ?: return
        val runId = _runId.value
        val learning = RunLearning(
            k.switches, k.memory, memoryRuntime.skills(), k.catalog,
            extractor = k.memory?.let { MemoryExtractor(k.light ?: k.model) },
            drafter = SkillDrafter(k.model),
            appDrafter = AppKnowledgeDrafter(k.model),
            appDrafts = memoryRuntime.appKnowledge(),
            ids = { UUID.randomUUID().toString() },
            autoApprove = env.evalHooks.autoApproveSkillDrafts,
            yieldToForeground = { while (service.hasActiveRuns()) delay(500) },
        )
        val notes = learning.learn(RunAftermath(
            conversationId, runId, k.agent.taskText ?: goal, end, k.utterances?.all.orEmpty(), k.dispatcher.savedChanges, k.agent.usedMemoryIds,
            k.dispatcher.definitions().map { it.name }.toSet(), k.dispatcher.index,
            events = { withContext(Dispatchers.IO) { runId?.let { k.runStore?.events(it) }.orEmpty() } },
            origin = provenance.origin,
            knowledge = k.knowledge,
            outcome = outcome ?: RunOutcome.Unknown,
        ))
        if (runId == null || notes.memoryNotes.isEmpty() && notes.usedMemory.isEmpty() && notes.skillNotes.isEmpty()) return
        memoryRuntime.publish(notes)
        withContext(Dispatchers.IO) {
            runCatching {
                conversations.updateTurn(conversationId, runId) { it.copy(memoryNotes = notes.memoryNotes, usedMemory = notes.usedMemory, skillNotes = notes.skillNotes) }
            }.onFailure { Log.w(TAG, "could not save memory notes", it) }
        }
    }

    /** 核查通过的成功记成路线;记不成(不该记、写盘失败)不影响任务结局。 */
    private suspend fun saveRoute(goal: String, events: List<RunEvent>, tools: ToolIndex) = withContext(Dispatchers.IO) {
        runCatching {
            val route = RouteRecorder.record(goal, events, System.currentTimeMillis(), tools) ?: return@runCatching
            val versions = route.packages.mapNotNull { p ->
                runCatching { service.packageManager.getPackageInfo(p, 0).versionName }.getOrNull()?.let { p to it }
            }.toMap()
            routeStore.putReplacing(route.copy(appVersions = versions))
        }.onFailure { Log.w(TAG, "route not saved", it) }
    }

    /** 主屏上该包最上面那个窗口的标题;窗口按从上到下排列。 */
    private fun mainWindowTitle(a11y: ScreenAccessibility, packageName: String): String? = runCatching {
        a11y.windowsOn(Display.DEFAULT_DISPLAY).firstNotNullOfOrNull { w ->
            w.title?.toString()?.takeIf { it.isNotBlank() && w.root?.packageName?.toString() == packageName }
        }
    }.getOrNull()

    /** 沙箱这一步的说明写到任务卡片上(当前这一步的 note)。 */
    private fun sandboxNote(text: String) {
        val s = _state.value as? TaskState.Running ?: return
        publish(s.copy(note = text))
    }

    private fun sandboxOnline() = (sandbox.status() as? SandboxStatus.Ready)?.network == true

    private fun publish(state: TaskState) {
        // 被挪走后到 gate 拦下之前,循环可能还在推理:对外先显示暂停
        // 循环自己报的终态不往外发:终态只由 [end] 在记好运行编号之后写,否则界面先收到结束、拿不到编号
        if (!TaskService.loopMayPublish(state)) return
        val shown = if (state is TaskState.Running && gate?.movedAwayPending == true) TaskState.Held(TaskGate.paused(TaskGate.MOVED_AWAY)) else state
        synchronized(publishLock) {
            if (ended) return
            setState(shown)
            awake?.onState(state)
        }
        service.refreshNotifications(this)
    }

    @Suppress("DEPRECATION")
    private fun mainScreenMetrics(): DisplayMetrics = DisplayMetrics().also {
        service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
    }

    /** 网页接管界面顶部的说明:接管原因的话,付款时再带上商品、金额、地址。 */
    private fun takeoverMessage(state: TaskState): String? {
        val w = state as? TaskState.WaitingTakeover ?: return null
        return listOfNotNull(
            w.message.takeIf { it.isNotBlank() },
            w.item?.let { tr(R.string.payment_item, it) },
            w.amount?.let { tr(R.string.payment_amount, it) },
            w.address?.let { tr(R.string.payment_address, it) },
        ).joinToString("\n").ifEmpty { null }
    }

    private companion object {
        /** 网页界面时画中画取画面的间隔。 */
        const val WEB_FRAME_MS = 500L
        const val SHELL_WAIT_MS = 15_000L
        const val DISPLAY_RETRY_MS = 1_000L
        val WAITING_DISPLAY: String get() = tr(R.string.task_waiting_display)
        const val A11Y_WAIT_MS = 5_000L
        val NO_PHONE_HELPER: String get() = tr(R.string.task_no_phone_helper)
        val NO_ACCESSIBILITY: String get() = tr(R.string.task_no_a11y)
        const val TAG = "MikaTask"
    }
}
