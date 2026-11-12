package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.core.assembly.legacyNames
import com.wangzan101.mika.core.tool.LegacyNames
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.DeleteImpact
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.cap.sandbox.ImportResult
import com.wangzan101.mika.confirm.ConfirmationCopy
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.core.agent.Conversation
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.task.TaskGate
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.memory.MemoryItem
import com.wangzan101.mika.core.memory.MemoryNote
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.skills.SkillNote
import com.wangzan101.mika.memory.RunNotes
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.settings.Problem
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState
import com.wangzan101.mika.task.unconsumed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.assembly.knownTools

/** 界面对任务服务能做的事;真机上由 TaskService 实现,测试里换成记录调用。 */
interface TaskActions {
    fun start(goal: String, conversationId: String)
    /** 任务进行中在它的对话里插的话;[messageId] 用来跟这句话的去向。 */
    fun steer(conversationId: String, messageId: String, text: String) = start(text, conversationId)
    fun cancel()
    fun resume()
    fun takeOver()
    fun handBack()
    fun finishedMyself()
    fun skip()
    fun answer(text: String)
    /** [key] 是卡片所属的那次申报。 */
    fun approve(key: Int)
    fun decline(key: Int)
    /** 并行任务时让服务显示这个对话的任务(只管显示:对外的状态流跟着它)。 */
    fun show(conversationId: String) {}

    // 并行任务时应用内的按钮带着屏上的对话,只作用于该对话的任务;它已经没有任务就作废。默认同不带对话的版本。
    fun cancelIn(conversationId: String) = cancel()
    fun resumeIn(conversationId: String) = resume()
    fun takeOverIn(conversationId: String) = takeOver()
    fun handBackIn(conversationId: String) = handBack()
    fun finishedMyselfIn(conversationId: String) = finishedMyself()
    fun skipIn(conversationId: String) = skip()
    fun answerIn(conversationId: String, text: String) = answer(text)
    fun approveIn(conversationId: String, key: Int) = approve(key)
    fun declineIn(conversationId: String, key: Int) = decline(key)
}

enum class StepState { Ok, Run, Wait, Dim }

/**
 * 执行过程里的一行,来自实际做过的事(运行事件里的活动):[title] 是做了什么(读取类合并成「看了微信的聊天列表」),
 * [reason] 是模型给那一步写的用意(没写时为空),[subtitle] 是没成功时的说明,平时为空。
 */
data class StepRow(val step: Int, val title: String, val subtitle: String, val state: StepState, val reason: String = "") {
    /** 这一步在界面上的说法:模型自己的话,没有时用工具的说法。 */
    val text: String get() = reason.ifEmpty { title }
}

sealed interface ExchangeStatus {
    data object Working : ExchangeStatus
    data object WaitingUnlock : ExchangeStatus
    /** 付款接管时 [item]/[amount]/[address] 单独列在卡片上。 */
    data class NeedsTakeover(
        val reason: String, val message: String,
        val item: String? = null, val amount: String? = null, val address: String? = null,
    ) : ExchangeStatus
    data class NeedsInput(val prompt: String, val options: List<String>?) : ExchangeStatus
    /**
     * 等用户同意一次要花钱的操作(只能同意这一次);[title]「要你同意:<动作>」,[body] 一行一个字段(App、对象、完整内容、金额)。
     * [key] 是这次申报的编号,按钮带着它,点在旧卡片上不算数。
     */
    data class NeedsConfirmation(val title: String, val body: String, val key: Int) : ExchangeStatus
    data class Paused(val why: String) : ExchangeStatus
    /**
     * [verified] 为 false 表示办完了但没能核实,[note] 是原因;这时不打绿勾。[unconfirmed] 是回答里核对后仍没找到出处的几处,
     * 卡片上逐条列出。[reviewed] 为 false 是什么也没核对(聊天、回答里没有可核对的值):不打任何标。[evidence] 只有旧记录里模型自己写的「依据:」段才有;新的回答来源从运行记录里取(见 [evidenceSource])。
     */
    data class Done(
        val answer: String, val evidence: String?, val verified: Boolean = true, val note: String? = null,
        val unconfirmed: List<String> = emptyList(), val reviewed: Boolean = true,
    ) : ExchangeStatus {
        val unverifiedLine: String? get() = when {
            unconfirmed.isNotEmpty() -> tr(R.string.chat_unconfirmed, unconfirmed.joinToString(tr(R.string.list_sep)))
            verified || !reviewed -> null
            else -> note?.let { tr(R.string.chat_unverified_because, it) } ?: tr(R.string.chat_unverified)
        }
    }
    data class Failed(val reason: String) : ExchangeStatus
    /**
     * 用户停止了。[summary] 是停止后补上的交代(做过什么、哪些没做完),当作这一轮的回答;还没写好、没什么可交代时为 null。
     * [summarizing] 时交代还在写(「正在整理已做的事…」)。
     */
    data class Cancelled(val summary: String? = null, val summarizing: Boolean = false) : ExchangeStatus
}

/**
 * 一问一答:用户的一句话、执行过程、此刻的状态或结果。[live] 表示是这次打开 App 后跑的任务(带步骤)。
 * [runId] 是任务结束后可以查看的运行记录;在跑或没有记录时为 null。
 */
data class Exchange(
    val goal: String,
    val steps: List<StepRow>,
    val status: ExchangeStatus,
    val live: Boolean,
    val runId: String? = null,
    /** 模型请求在途时只显示状态，不把等待伪装成一次执行步骤。 */
    val waitingForModel: Boolean = false,
    /** 模型这次请求已经给出的可见文字；完成前可能成为进度说明或答复。 */
    val draft: String? = null,
    /** 结果卡片下的记忆提示(记住了 / 更新了 / 忘掉了 / 要我以后都这样做吗)。 */
    val memoryNotes: List<MemoryNote> = emptyList(),
    /** 这次用到的记忆条目编号(代码记录的,不是模型自述)。 */
    val usedMemory: List<String> = emptyList(),
    /** 结果卡片下的技能提示(学会了 / 最近不灵先停用了)。 */
    val skillNotes: List<SkillNote> = emptyList(),
    /** 这一轮导出到「下载/Mika」的文件,显示成文件卡片。 */
    val files: List<SharedFile> = emptyList(),
    /** 这次打开 App 后开始跟的时刻(毫秒);不知道时为 0。 */
    val startedAt: Long = 0L,
    /** 结束时算出的用时;没结束或不知道开始时刻时为 null。 */
    val elapsedMs: Long? = null,
    /** 到此刻实际做过的事(运行事件);[steps] 由它推出。 */
    val activities: List<RunEvent.Activity> = emptyList(),
    /** 模型给各步写的话(进度说明或调用用意),按步号。 */
    val notes: Map<Int, String> = emptyMap(),
    /** 此刻在做什么:模型这一步的话,或不经模型连做几步时的进度;没有时为空。 */
    val line: String = "",
    /** 任务进行中用户在这里插的话和它们的去向。 */
    val steers: List<SteerNote> = emptyList(),
)

/** 在跑时的一行进展:等你时说等什么;模型请求在途时只给中性状态;否则是这一步的话,没有就是最近做的事。 */
fun liveLine(ex: Exchange): String? {
    when (ex.status) {
        is ExchangeStatus.NeedsTakeover -> return tr(R.string.takeover_title)
        is ExchangeStatus.NeedsInput -> return tr(R.string.chat_wait_answer)
        is ExchangeStatus.NeedsConfirmation -> return tr(R.string.chat_wait_approval)
        else -> Unit
    }
    if (ex.waitingForModel) return tr(R.string.chat_working)
    return ex.line.ifEmpty { null } ?: ex.steps.lastOrNull()?.text
}

/** 插话下面那行小字;还没有去向时不写。 */
fun steerLabel(state: SteerState): String? = when (state) {
    SteerState.Pending -> null
    SteerState.Received -> tr(R.string.steer_received)
    SteerState.Joined -> tr(R.string.steer_joined)
    SteerState.Next -> tr(R.string.steer_next)
    SteerState.Cancelled -> tr(R.string.steer_cancelled)
}

/** 结束后收起的那一行:「做了 12 步 · 1 分 20 秒」;不知道用时就只说步数。 */
fun stepsSummary(ex: Exchange): String =
    trPlural(R.plurals.chat_steps_done, ex.steps.size) + ex.elapsedMs?.let { " · " + RunTimelineViewModel.duration(it) }.orEmpty()

/** 结果卡片上记忆与技能提示的按钮;真机上由记忆存储与个人技能实现(读写在后台线程),测试里换成记录调用。 */
interface LearningActions {
    suspend fun undo(token: String): Boolean
    suspend fun approve(pendingId: String): Boolean
    /** 撤销令牌已失效(重启过)时「不要」直接删掉那条待批准的做法。 */
    suspend fun forget(memoryId: String): Boolean
    fun viewSkill(draftId: String)
    suspend fun restoreSkill(name: String)
    suspend fun deleteSkill(name: String)
    /** 提示改了(撤销、批准后去掉那一行):存进对话并通知界面。 */
    suspend fun save(notes: RunNotes)
}

/**
 * 选好、等着随下一句话发出去的附件:内容地址([uri])、名字和大小;发送时才复制进对话工作区。
 * [photo] 的在输入框上方是缩略图,其余是文件小卡片。
 */
data class PendingAttachment(val uri: String, val name: String, val size: Long, val photo: Boolean = AttachmentMessage.isPhoto(name))

enum class PipMode { Running, NeedsYou, Done }

/** 画中画只放画面:状态只靠边框光(绿色在干活、橙色呼吸要你处理),要你做的事在对话卡片里。 */
data class PipUi(
    val visible: Boolean = false,
    val mode: PipMode = PipMode.Running,
    val expanded: Boolean = false,
)

enum class ComposerMode {
    /** 没有任务,发出去就是新任务。 */
    Idle,
    /** 任务在跑:发出去的话交给这个任务,停止是另一个按钮。 */
    Busy,
    /** Agent 在等回答,发出去就是回答。 */
    Answer,
}

data class ChatUi(
    val conversationId: String? = null,
    val exchanges: List<Exchange> = emptyList(),
    /** 上次发送被拦下时缺的条件;为空表示没被拦。 */
    val blocked: List<Problem>? = null,
    val pip: PipUi = PipUi(),
    val composer: ComposerMode = ComposerMode.Idle,
    val takeoverStage: Takeover.Stage = Takeover.Stage.None,
    /** 沙箱就绪时输入框旁才有附件按钮。 */
    val attachEnabled: Boolean = false,
    val attachments: List<PendingAttachment> = emptyList(),
    /** 正在把附件复制进工作区(发送时)。 */
    val importing: Boolean = false,
    /** 没收下的附件(太大或读不了);为空不提示。 */
    val rejectedAttachments: List<String> = emptyList(),
    /** 超过一次能带的个数([AttachTray.LIMIT])没加上的个数;为 0 不提示。 */
    val overLimit: Int = 0,
) {
    /** 任务此刻正在后台的虚拟屏上操作(输入框上方那行提示):开过屏、在干活,不在等你或等解锁。 */
    val operatingPhone: Boolean
        get() = pip.visible && pip.mode == PipMode.Running && exchanges.lastOrNull()?.status == ExchangeStatus.Working
}

/**
 * 聊天界面的状态。步骤列表来自运行事件里实际做过的事(活动);进展那一行是模型这一步的话,
 * 等待模型时仅显示状态。任务在跑时输入框发的话交给这个任务(插话)。
 * 整个进程只用一个实例,界面关掉再打开时正在跑的任务的步骤还在。
 */
class ChatViewModel(
    private val scope: CoroutineScope,
    taskState: StateFlow<TaskState>,
    private val takeoverStage: StateFlow<Takeover.Stage>,
    private val actions: TaskActions,
    private val store: ConversationStore,
    /** 服务还在收尾上一个任务;这时开始的新任务要等它收完再交给服务,否则会被忽略。 */
    private val serviceBusy: StateFlow<Boolean> = MutableStateFlow(false),
    /** 包名换成 App 显示名(确认卡片用)。 */
    private val appLabel: (String) -> String = { it },
    /**
     * 当前任务有没有虚拟屏。屏到第一次打开 App 才建,没建过屏的任务(纯对话、不用手机就能答)不出画中画,
     * 问答和确认都在聊天卡片里。
     */
    private val screenOpen: StateFlow<Boolean> = MutableStateFlow(false),
    /**
     * 刚结束的那个任务的运行记录编号,服务在报结束之前写好;收到结束状态时挂到那一问一答上,
     * 卡片上就有「查看过程」。之后它再变也不改已结束的那条。
     */
    private val endedRunId: StateFlow<String?> = MutableStateFlow(null),
    /** 每个对话最近结束的任务的运行记录编号;给了就按卡片所在的对话取,不用上面那个(它只跟显示中的任务)。 */
    private val endedRunIds: StateFlow<Map<String, String?>>? = null,
    /** 开关 parallelTasks:打开时不同对话的任务可以同时跑,界面只跟着屏上这个对话的任务。界面没在跟任务时重读。 */
    private val parallelFlag: () -> Boolean = { false },
    /** 服务显示中的任务:(对话, 状态),两者一起写;并行时用它代替 [taskState],别的对话的状态不进来。 */
    private val shownTask: StateFlow<Pair<String, TaskState>?> = MutableStateFlow(null),
    /** 正在跑或排队的任务所在的对话。 */
    private val activeConversations: StateFlow<List<String>> = MutableStateFlow(emptyList()),
    /** 该对话正在跑的任务的原话;切过去接着显示时用。 */
    private val runGoal: (String) -> String? = { null },
    /** 该对话正在跑的任务自己的状态(不论服务此刻显示的是谁);切走后靠它接着记那一问一答的步骤。 */
    private val runState: (String) -> StateFlow<TaskState>? = { null },
    /** 任务结束后补上的记忆与技能提示,按运行编号。 */
    private val runNotes: StateFlow<Map<String, RunNotes>> = MutableStateFlow(emptyMap()),
    private val learning: LearningActions? = null,
    /** 此刻的记忆条目(从加密存储读出);提示的文字和「用到」的内容从这里现取,对话文件里不存内容。 */
    private val memoryItems: StateFlow<List<MemoryItem>> = MutableStateFlow(emptyList()),
    /** 沙箱是否就绪。 */
    private val sandboxReady: StateFlow<Boolean> = MutableStateFlow(false),
    /** 当前任务导出的文件;任务开始时由服务清空。 */
    private val sharedFiles: StateFlow<List<SharedFile>> = MutableStateFlow(emptyList()),
    /** 把选中的文件(内容 URI 字符串)复制进对话工作区的 inbox/。 */
    private val importAttachments: suspend (conversationId: String, uris: List<String>) -> ImportResult = { _, _ -> ImportResult(emptyList(), emptyList()) },
    /** 读出选中文件(内容 URI 字符串)的名字、大小和是不是照片;读盘的活由调用方放到后台线程。 */
    private val describeAttachments: suspend (uris: List<String>) -> List<PendingAttachment> = { uris -> uris.map { PendingAttachment(it, it.substringAfterLast('/'), 0L) } },
    /** 步骤行里工具的说法。 */
    private val toolLabels: ToolLabels = ToolLabels(knownTools()),
    /** 显示中的任务实际做过的事(运行事件里的活动)。 */
    private val activities: StateFlow<List<RunEvent.Activity>> = MutableStateFlow(emptyList()),
    /** 显示中的任务收到的插话和去向。 */
    private val steers: StateFlow<List<SteerNote>> = MutableStateFlow(emptyList()),
    /** 停止之后正在写交代的那几次运行(运行编号);那一轮下面显示「正在整理已做的事…」。 */
    private val summarizing: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
    /** 算用时的时钟(毫秒)。 */
    private val clock: () -> Long = System::currentTimeMillis,
    /** 删掉一个对话会连带什么(见 `ConversationDeleter.impact`)。 */
    private val deletionImpact: (String) -> DeleteImpact = { DeleteImpact(runningTask = false, schedules = 0) },
    /** 删掉一个对话和属于它的一切(见 `ConversationDeleter.delete`)。 */
    private val removeConversation: suspend (String) -> Unit = {},
) {
    private val _ui = MutableStateFlow(ChatUi())
    val ui: StateFlow<ChatUi> = _ui.asStateFlow()

    /** 删掉了(或正在删)的对话:侧栏当场不再列出,不等删完。 */
    private val _deleted = MutableStateFlow<Set<String>>(emptySet())
    val deleted: StateFlow<Set<String>> = _deleted.asStateFlow()
    /** 还没删完的。 */
    private val deleting = mutableSetOf<String>()

    /** 屏上这个对话以外、有任务在跑或排队的对话(顶部「另 n 个任务进行中」)。 */
    val otherRunning: StateFlow<List<String>> = combine(activeConversations, _ui.map { it.conversationId }.distinctUntilChanged()) { active, current ->
        active.filter { it != current }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * 并行时切走的、任务还在跑的对话:那一问一答(原话、标准、步骤、文件)和开没开过屏都留着,
     * 后台按它自己任务的状态接着更新步骤,切回来原样接上。任务结束时丢掉(那一轮由服务存进对话)。
     */
    private class Parked(val exchange: Exchange, val screenSeen: Boolean)
    private val parked = mutableMapOf<String, Parked>()
    private val parkedJobs = mutableMapOf<String, Job>()

    /** 把屏上这个对话正在跟的那一问一答停到一边(换到别的对话之前调用)。 */
    private fun parkLive() {
        val ui = _ui.value
        val id = ui.conversationId ?: return
        val live = ui.exchanges.getOrNull(liveIndex) ?: return
        if (!live.status.active() || id !in activeConversations.value) return
        parked[id] = Parked(live, screenSeen)
        parkedJobs.remove(id)?.cancel()
        val flow = runState(id) ?: return
        parkedJobs[id] = scope.launch {
            flow.collect { s ->
                if (!s.active()) {
                    if (s != TaskState.Idle) { parked.remove(id); parkedJobs.remove(id)?.cancel() }
                    return@collect
                }
                parked[id]?.let { p -> parked[id] = Parked(reduce(p.exchange, s, appLabel, toolLabels::label), p.screenSeen) }
            }
        }
    }

    private fun unpark(id: String): Parked? {
        parkedJobs.remove(id)?.cancel()
        return parked.remove(id)
    }

    /** 开关在没有任务时重读,开始任务、换对话时也重读([refreshParallel]):这里在跟的任务中途改开关不改这一轮的行为。 */
    private var parallelOn = parallelFlag()
    private fun parallel(): Boolean {
        if (liveIndex < 0 && activeConversations.value.isEmpty()) parallelOn = parallelFlag()
        return parallelOn
    }

    /**
     * 开始任务、换对话时重读开关(这个界面没在跟任务时):设置里刚改的开关不用重建界面就生效,
     * 和服务每次开始任务时重读一致。别的对话的任务照旧跑,它们在服务里按各自开始时的开关。
     */
    private fun refreshParallel() {
        if (liveIndex < 0) parallelOn = parallelFlag()
    }

    /** 正在跟踪的任务在 exchanges 里的位置;-1 表示没有。 */
    private var liveIndex = -1
    private var lingerJob: Job? = null
    /** 排在服务收尾后面、还没交给服务的开始;在这期间点停止就直接作废它。 */
    private var pendingStart: Job? = null
    /** 正在跟踪的任务开过屏;一旦开过,到任务结束(含完成后停留的那几秒)都显示画中画。 */
    private var screenSeen = false

    init {
        // 开关关着时也按状态带的对话认:别的对话的任务(悬浮胶囊、定时任务开始的)不接到屏上这个对话里
        scope.launch { taskState.collect { s -> if (!parallel()) shownTask.value?.let { onShown(it.first, s) } ?: onState(s) } }
        scope.launch { shownTask.collect { t -> if (t != null && parallel()) onShown(t.first, t.second) } }
        // 不论是谁存进屏上这个对话的(悬浮胶囊、定时任务、这里),都按存下的那份补上
        scope.launch {
            store.changes.collect { id ->
                if (id == _ui.value.conversationId) syncSaved(id)
                // 删掉的对话又有人往里存了(比如悬浮胶囊接着在那个对话里说):重新列出来
                if (id in _deleted.value && id !in deleting && store.turns(id).isNotEmpty()) _deleted.update { it - id }
            }
        }
        // 并行时只收屏上这个对话的任务的接管进度
        scope.launch {
            takeoverStage.collect { s -> if (ownTask()) _ui.update { it.copy(takeoverStage = s) } }
        }
        // 只认任务进行中新列出的标准:订阅时的旧值可能是上一个任务的
        // 有没有屏和状态一样是每个任务各自的:并行时只收屏上这个对话的任务的
        scope.launch { screenOpen.collect { if (it && ownTask()) onScreenOpened() } }
        scope.launch { sandboxReady.collect { r -> _ui.update { it.copy(attachEnabled = r) } } }
        scope.launch { sharedFiles.collect { if (ownTask()) onSharedFiles(it) } }
        scope.launch { runNotes.collect { notes -> _ui.update { ui -> ui.copy(exchanges = ui.exchanges.map { withNotes(it, notes) }) } } }
        scope.launch { activities.collect { if (ownTask()) onActivities(it) } }
        scope.launch { steers.collect { if (ownTask()) onSteers(it) } }
        // 任务登记进任务列表(原话可以取了)可能晚于它的第一个状态:那时接上的那一问一答补上原话
        scope.launch { activeConversations.collect { fillGoal() } }
        scope.launch { summarizing.collect { set -> _ui.update { ui -> ui.copy(exchanges = ui.exchanges.map { withSummarizing(it, set) }) } } }
    }

    /** 停止的那一轮:交代还在写时标上「正在整理」;写好了、写不成就去掉。 */
    private fun withSummarizing(ex: Exchange, set: Set<String> = summarizing.value): Exchange {
        val st = ex.status as? ExchangeStatus.Cancelled ?: return ex
        val on = st.summary == null && ex.runId != null && ex.runId in set
        return if (st.summarizing == on) ex else ex.copy(status = st.copy(summarizing = on))
    }

    private fun onActivities(list: List<RunEvent.Activity>) {
        if (liveIndex < 0) return
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            if (liveIndex in exchanges.indices) {
                val ex = exchanges[liveIndex].copy(activities = list)
                exchanges[liveIndex] = ex.copy(steps = rows(ex, toolLabels::label, appLabel))
            }
            ui.copy(exchanges = exchanges)
        }
    }

    /**
     * 插话的去向按编号改到它所在的那一问一答上(任务收尾后它可能已经不是在跟的那条)。
     * 别处(悬浮胶囊)插给正在跟的任务的话,这里还没有的,挂到正在跟的那一问一答下面。
     */
    private fun onSteers(list: List<SteerNote>) {
        val byId = list.associateBy { it.id }
        _ui.update { ui ->
            val shown = ui.exchanges.flatMap { ex -> ex.steers.map { it.id } }.toSet()
            val added = list.filter { it.id !in shown }
            ui.copy(exchanges = ui.exchanges.mapIndexed { i, ex ->
                val updated = if (ex.steers.none { it.id in byId }) ex
                    else ex.copy(steers = ex.steers.map { n -> byId[n.id]?.let { n.copy(state = it.state) } ?: n })
                if (i == liveIndex && added.isNotEmpty() && ex.status.active()) updated.copy(steers = updated.steers + added) else updated
            })
        }
    }

    /** 服务对外的那几项(接管进度、有没有屏、导出的文件)此刻是不是屏上这个对话的任务的:并行时看显示中的是谁。 */
    private fun ownTask() = !parallel() || shownTask.value?.first == _ui.value.conversationId

    private fun withNotes(ex: Exchange, notes: Map<String, RunNotes>): Exchange {
        val n = ex.runId?.let(notes::get) ?: return ex
        return ex.copy(memoryNotes = n.memoryNotes, usedMemory = n.usedMemory, skillNotes = n.skillNotes)
    }

    /** 提示这一行的文字,内容从此刻的记忆里取;那条已经删掉时写「一条已删除的记忆」。 */
    fun noteText(note: MemoryNote): String = note.text(memoryItems.value.find { it.id == note.memoryId })

    /** 撤销这条记忆改动;令牌已失效(重启过)时只把「撤销」去掉。 */
    fun undoNote(runId: String, note: MemoryNote) {
        val token = note.undoToken ?: return
        scope.launch {
            val ok = learning?.undo(token) ?: false
            editNotes(runId) { ex -> ex.copy(memoryNotes = if (ok) ex.memoryNotes - note else ex.memoryNotes.map { if (it == note) it.copy(undoToken = null) else it }) }
        }
    }

    /** 做法:「好」批准。 */
    fun approveNote(runId: String, note: MemoryNote) {
        val id = note.pendingId ?: return
        scope.launch {
            learning?.approve(id)
            editNotes(runId) { ex -> ex.copy(memoryNotes = ex.memoryNotes - note) }
        }
    }

    /** 做法:「不要」撤销这次新增;令牌没了就直接删掉那条。 */
    fun rejectNote(runId: String, note: MemoryNote) {
        scope.launch {
            val undone = note.undoToken?.let { learning?.undo(it) } ?: false
            if (!undone) note.pendingId?.let { learning?.forget(it) }
            editNotes(runId) { ex -> ex.copy(memoryNotes = ex.memoryNotes - note) }
        }
    }

    /** 「用到 n 条记忆」点开时的内容;已删掉的不列。 */
    fun usedMemoryTexts(ids: List<String>): List<String> {
        val byId = memoryItems.value.associateBy { it.id }
        return ids.mapNotNull { byId[it]?.content }
    }

    fun viewSkill(note: SkillNote) { note.draftId?.let { learning?.viewSkill(it) } }

    fun restoreSkill(runId: String, note: SkillNote) {
        val name = note.suspended ?: return
        scope.launch {
            learning?.restoreSkill(name)
            editNotes(runId) { ex -> ex.copy(skillNotes = ex.skillNotes - note) }
        }
    }

    fun deleteSkill(runId: String, note: SkillNote) {
        val name = note.suspended ?: return
        scope.launch {
            learning?.deleteSkill(name)
            editNotes(runId) { ex -> ex.copy(skillNotes = ex.skillNotes - note) }
        }
    }

    private suspend fun editNotes(runId: String, change: (Exchange) -> Exchange) {
        var saved: Exchange? = null
        _ui.update { ui ->
            ui.copy(exchanges = ui.exchanges.map { if (it.runId == runId) change(it).also { e -> saved = e } else it })
        }
        val ex = saved ?: return
        val conversation = _ui.value.conversationId ?: return
        learning?.save(RunNotes(conversation, runId, ex.memoryNotes, ex.usedMemory, ex.skillNotes))
    }

    private fun onSharedFiles(files: List<SharedFile>) {
        if (liveIndex < 0) return
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            if (liveIndex in exchanges.indices) exchanges[liveIndex] = exchanges[liveIndex].copy(files = files)
            ui.copy(exchanges = exchanges)
        }
    }

    private fun canAttach(ui: ChatUi) = ui.attachEnabled && ui.composer == ComposerMode.Idle && !ui.importing

    /** 从相机、相册、文件选择器拿到的文件:没有任务在跑、沙箱就绪时才收;读出名字和大小后挂在输入框上方,随下一句话发出去。 */
    fun attach(uris: List<String>) {
        if (uris.isEmpty() || !canAttach(_ui.value)) return
        scope.launch {
            val items = try { describeAttachments(uris) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
            _ui.update { ui ->
                if (!canAttach(ui)) return@update ui
                val (all, dropped) = AttachTray.add(ui.attachments, items)
                ui.copy(attachments = all, overLimit = dropped, rejectedAttachments = emptyList())
            }
        }
    }

    /** 面板里最近的照片点一下:选上或取消;已经满了返回 false。 */
    fun toggleAttachment(item: PendingAttachment): Boolean {
        val ui = _ui.value
        if (!canAttach(ui)) return true
        val t = AttachTray.toggle(ui.attachments, item)
        _ui.update { it.copy(attachments = t.items) }
        return !t.full
    }

    fun removeAttachment(uri: String) = _ui.update { ui -> ui.copy(attachments = ui.attachments.filterNot { it.uri == uri }) }

    fun dismissRejected() = _ui.update { it.copy(rejectedAttachments = emptyList(), overLimit = 0) }

    private fun onScreenOpened() {
        if (liveIndex < 0 || screenSeen) return
        screenSeen = true
        _ui.update { ui ->
            val live = ui.exchanges.getOrNull(liveIndex)
            if (live != null && live.status.active()) ui.copy(pip = ui.pip.copy(visible = true)) else ui
        }
    }

    /** 没开过屏时画中画不显示(其余字段照算,开屏那一刻直接用)。 */
    private fun gated(p: PipUi) = if (screenSeen) p else p.copy(visible = false, expanded = false)


    /** 发送输入框里的话。返回 false 表示没发出去(条件不满足,或任务在跑)。 */
    fun send(text: String, check: Prerequisites.Check): Boolean {
        refreshParallel()
        val ui = _ui.value
        if (ui.importing) return false
        // 附件只跟新任务走;回答问题时不带
        if (ui.composer == ComposerMode.Idle && ui.attachments.isNotEmpty()) return sendWithAttachments(text.trim(), ui.attachments, check)
        val goal = text.trim()
        if (goal.isEmpty()) return false
        when (ui.composer) {
            ComposerMode.Answer -> {
                act({ actions.answer(goal) }) { actions.answerIn(it, goal) }
                return true
            }
            ComposerMode.Busy -> return steer(goal, check)
            ComposerMode.Idle -> Unit
        }
        if (!check.canStartTask) {
            _ui.update { it.copy(blocked = check.blockers) }
            return false
        }
        begin(goal, ui.conversationId ?: store.newConversation())
        return true
    }

    /**
     * 带着附件开新任务:先把附件复制进对话工作区的 inbox/(期间输入框上方显示正在添加),
     * 再把每个附件写成一行「附件:<路径>(<大小>)」接在话后面开始任务。没收下的列在输入框上方;话和附件都没有了就不发。
     * 复制期间换了对话或开始了别的任务就不发了(复制进去的文件留在原对话的工作区里)。
     */
    private fun sendWithAttachments(text: String, items: List<PendingAttachment>, check: Prerequisites.Check): Boolean {
        if (!check.canStartTask) {
            _ui.update { it.copy(blocked = check.blockers) }
            return false
        }
        val id = _ui.value.conversationId ?: store.newConversation()
        _ui.update { it.copy(conversationId = id, importing = true, rejectedAttachments = emptyList(), overLimit = 0) }
        scope.launch {
            val r = try { importAttachments(id, items.map { it.uri }) } catch (e: CancellationException) { throw e } catch (e: Exception) { ImportResult(emptyList(), items.map { it.name }) }
            val goal = withAttachments(text, r.added)
            _ui.update { it.copy(importing = false, attachments = emptyList(), rejectedAttachments = r.rejected) }
            val now = _ui.value
            if (goal.isEmpty() || now.conversationId != id || now.composer != ComposerMode.Idle) return@launch
            begin(goal, id)
        }
        return true
    }

    /** 在对话 [id] 里开始新任务 [goal]:界面上加一问一答,交给服务(并行关着、服务还忙时排在它收尾后面)。 */
    private fun begin(goal: String, id: String) {
        lingerJob?.cancel()
        // 上一个任务收尾时先撤屏再报结束,这里读到的就是新任务开始前的样子;
        // 并行时那是别的对话的任务的屏,新任务还没有屏
        // 新消息开始时不能继承上一次运行的屏；真正建好本次的屏后由 screenOpen 打开小窗。
        screenSeen = false
        _ui.update {
            liveIndex = it.exchanges.size
            it.copy(
                conversationId = id,
                exchanges = it.exchanges + Exchange(goal, emptyList(), ExchangeStatus.Working, live = true, startedAt = clock()),
                blocked = null,
                attachments = emptyList(),
                pip = gated(PipUi(visible = true)),
                composer = ComposerMode.Busy,
            )
        }
        // 并行时服务忙着(别的对话的任务在跑)也直接交给它
        if (serviceBusy.value && !parallel()) {
            pendingStart = scope.launch {
                serviceBusy.first { !it }
                pendingStart = null
                actions.start(goal, id)
                // 等着期间插的话:原话的任务先开始,再交给它
                held.toList().also { held.clear() }.forEach { n -> actions.steer(id, n.id, n.text) }
            }
        } else {
            actions.start(goal, id)
        }
    }

    /**
     * 任务在跑时发的话:挂在正在跟的那一问一答下面,交给这个任务;它的去向由服务报回来([steers])。
     * 它可能成为下一件事,所以和开新任务过同一道检查。任务还没交给服务([pendingStart])时先留着,开始之后再发。
     */
    private fun steer(text: String, check: Prerequisites.Check): Boolean {
        val id = _ui.value.conversationId ?: return false
        if (liveIndex !in _ui.value.exchanges.indices) return false
        if (!check.canStartTask) {
            _ui.update { it.copy(blocked = check.blockers) }
            return false
        }
        val note = SteerNote(java.util.UUID.randomUUID().toString(), text, SteerState.Pending)
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            exchanges[liveIndex] = exchanges[liveIndex].let { it.copy(steers = it.steers + note) }
            ui.copy(exchanges = exchanges)
        }
        if (pendingStart?.isActive == true) held += note else actions.steer(id, note.id, text)
        return true
    }

    /** 原话的任务还没交给服务时插的话。 */
    private val held = mutableListOf<SteerNote>()

    /**
     * 只收屏上这个对话的任务的状态;界面刚起来还没有对话时接上正在跑的那个(连同那个对话存下的轮次)。
     * 服务换去显示别的对话的任务(别处刚开始了一个)时,这里正在跟的任务还在跑就让服务显示回来,不让它停在半路。
     */
    private fun onShown(conversationId: String, s: TaskState) {
        // 删掉的对话的任务正在停下:不接到屏上
        if (conversationId in _deleted.value) return
        val current = _ui.value.conversationId
        when {
            current == null -> {
                if (!s.active()) return
                _ui.update { it.copy(conversationId = conversationId, exchanges = saved(conversationId)) }
            }
            current != conversationId -> {
                val live = _ui.value.exchanges.getOrNull(liveIndex)
                if (live != null && live.status.active() && current in activeConversations.value && pendingStart?.isActive != true) actions.show(current)
                return
            }
        }
        onState(s)
    }

    /**
     * 切到某个对话(并行时任务在跑也能切):载入它的历史;它有任务在跑就让服务显示那个任务,并接上它此刻的状态。
     * 开关关着时同 [openConversation]。
     */
    fun switchTo(conversationId: String) {
        refreshParallel()
        if (!parallel()) return openConversation(conversationId)
        lingerJob?.cancel()
        if (conversationId != _ui.value.conversationId) parkLive()
        val past = saved(conversationId)
        liveIndex = -1
        // 没有任务的对话不带别的任务的接管进度
        _ui.update { ChatUi(conversationId = conversationId, exchanges = past, takeoverStage = Takeover.Stage.None) }
        if (conversationId !in activeConversations.value) { unpark(conversationId); return }
        follow(conversationId, unpark(conversationId))
    }

    /**
     * 接上屏上这个对话正在跑的任务(不论从哪里开始的:这里、悬浮胶囊、定时任务)。没在跟时先摆好那一问一答
     * (切走时停着的 [p],或按任务的原话新起一条),再让服务显示它,并按服务此刻对外的各项补齐。
     */
    private fun follow(conversationId: String, p: Parked? = null) {
        val fresh = liveIndex < 0
        if (fresh) {
            // 先把这个对话的那一问一答摆好,再让服务显示它:服务一换显示,状态可能当场就送进来(onShown),
            // 那时这一问一答必须已经在,不然会另起一条没有原话的
            val live = p?.exchange ?: Exchange(runGoal(conversationId).orEmpty(), emptyList(), ExchangeStatus.Working, live = true, startedAt = clock())
            screenSeen = p?.screenSeen ?: false
            _ui.update {
                liveIndex = it.exchanges.size
                it.copy(exchanges = it.exchanges + live, composer = ComposerMode.Busy, pip = gated(PipUi(visible = true)))
            }
        }
        actions.show(conversationId)
        val shown = shownTask.value?.takeIf { it.first == conversationId } ?: return
        // 正在跟的那一问一答不拿服务里已收尾的状态去结束它(那可能是上一个任务的)
        if (!fresh && !shown.second.active()) return
        // 服务对外的这几项此刻都是这个任务的了:按它们补齐(状态流只在变化时送,切过来不一定再送一次)
        if (!screenSeen && screenOpen.value) screenSeen = true
        _ui.update { it.copy(takeoverStage = takeoverStage.value) }
        onSharedFiles(sharedFiles.value)
        onActivities(activities.value)
        onSteers(steers.value)
        onState(shown.second)
    }

    /**
     * 对话页重新看得见时(回到前台、从别的页面回来):别处存进屏上这个对话的轮次补上;
     * 这个对话有任务在跑(从悬浮胶囊、定时任务开始的)而这里没在跟,或服务显示的换成了别的任务,就接上它。
     */
    fun refresh() {
        refreshParallel()
        val id = _ui.value.conversationId ?: return
        syncSaved(id)
        if (id !in activeConversations.value || pendingStart?.isActive == true) return
        // 开关关着、这里没在跟任务时换对话要等任务结束:那时屏上这个对话的任务就是服务里唯一在跑的
        follow(id, if (parallel()) unpark(id) else null)
    }

    /** 这个对话存下的轮次,连同各轮导出的文件和此刻的记忆与技能提示。 */
    private fun saved(conversationId: String): List<Exchange> =
        runCatching { store.records(conversationId) }.getOrDefault(emptyList()).map { (t, files) -> withSummarizing(withNotes(fromTurn(t).copy(files = files), runNotes.value)) }

    /**
     * 存储里这个对话变了(别处跑完存进一轮、改了某一轮):屏上按存下的那份重排。这里跟过的那几轮按运行编号认
     * (没有编号的按原话),用这里的那份(带步骤和用时),不重复;还没存进去的接在后面;正在跟的那一问一答留在最后。
     */
    private fun syncSaved(conversationId: String) {
        val stored = saved(conversationId)
        _ui.update { ui ->
            if (ui.conversationId != conversationId) return@update ui
            val live = ui.exchanges.getOrNull(liveIndex)
            val tracked = ui.exchanges.filterIndexed { i, ex -> ex.live && i != liveIndex }.toMutableList()
            // 从最近的往前配:同一句原话说过好几次时,配上的是最近那次
            val merged = stored.asReversed().map { s ->
                val mine = tracked.lastOrNull { if (s.runId != null) it.runId == s.runId else it.runId == null && it.goal == s.goal }
                    ?: return@map s
                tracked.remove(mine)
                // 停止后补上的交代记在存下的那份里
                val m = if (mine.status is ExchangeStatus.Cancelled && s.status is ExchangeStatus.Cancelled && s.status.summary != null) mine.copy(status = s.status) else mine
                if (m.goal.isEmpty()) m.copy(goal = s.goal) else m
            }.asReversed()
            val exchanges = merged + tracked + listOfNotNull(live)
            if (exchanges == ui.exchanges) return@update ui
            liveIndex = if (live != null) exchanges.lastIndex else -1
            ui.copy(exchanges = exchanges)
        }
    }

    fun dismissBlocked() = _ui.update { it.copy(blocked = null) }

    /** 停止:任务连同还没交到它手里的插话一起作废(插话不再当成下一件事)。 */
    fun cancel() {
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            if (liveIndex in exchanges.indices) exchanges[liveIndex] = exchanges[liveIndex].let { ex ->
                ex.copy(steers = ex.steers.map { if (it.state.unconsumed) it.copy(state = SteerState.Cancelled) else it })
            }
            ui.copy(exchanges = exchanges)
        }
        val pending = pendingStart
        if (pending == null || !pending.isActive) {
            act({ actions.cancel() }) { actions.cancelIn(it) }
            return
        }
        // 任务还没交给服务:作废这次开始;服务里正在收尾的是上一个任务,不去碰它
        pending.cancel()
        pendingStart = null
        held.clear()
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            if (liveIndex in exchanges.indices) exchanges[liveIndex] = exchanges[liveIndex].copy(status = ExchangeStatus.Cancelled())
            ui.copy(exchanges = exchanges, pip = PipUi(), composer = ComposerMode.Idle)
        }
        liveIndex = -1
    }
    fun resume() = act({ actions.resume() }) { actions.resumeIn(it) }
    fun takeOver() = act({ actions.takeOver() }) { actions.takeOverIn(it) }
    fun handBack() = act({ actions.handBack() }) { actions.handBackIn(it) }
    fun finishedMyself() = act({ actions.finishedMyself() }) { actions.finishedMyselfIn(it) }
    fun skip() = act({ actions.skip() }) { actions.skipIn(it) }
    fun answer(text: String) = act({ actions.answer(text) }) { actions.answerIn(it, text) }
    fun approve(key: Int) = act({ actions.approve(key) }) { actions.approveIn(it, key) }
    fun decline(key: Int) = act({ actions.decline(key) }) { actions.declineIn(it, key) }

    /**
     * 应用内的按钮:开关关着时作用于服务显示中的任务(M1);并行时带上按下那一刻屏上的对话,只作用于它的任务,
     * 这个对话没有任务就不发。
     */
    private inline fun act(serial: () -> Unit, scoped: (String) -> Unit) {
        if (!parallel()) return serial()
        val id = _ui.value.conversationId ?: return
        if (id in activeConversations.value) scoped(id)
    }

    fun expandPip() = _ui.update { if (it.pip.visible) it.copy(pip = it.pip.copy(expanded = true)) else it }
    /** 要你处理时点画中画:收起它,由界面滚到要你处理的那张卡片。 */
    fun attendPip() = collapsePip()
    fun collapsePip() = _ui.update { it.copy(pip = it.pip.copy(expanded = false)) }

    /** 切到某个对话;任务在跑时不切。 */
    fun openConversation(id: String) {
        refreshParallel()
        if (parallel()) return switchTo(id)
        if (busy()) return
        val past = saved(id)
        liveIndex = -1
        _ui.update { ChatUi(conversationId = id, exchanges = past, takeoverStage = it.takeoverStage, attachEnabled = it.attachEnabled) }
        // 这个对话的任务是别处开始的(悬浮胶囊、定时任务):打开就接上它
        if (id in activeConversations.value) follow(id)
    }

    fun newConversation() {
        refreshParallel()
        if (parallel()) {
            // 别的任务可能还在跑:先给新对话编号,它的状态就不会被当成这个对话的
            lingerJob?.cancel()
            parkLive()
            liveIndex = -1
            _ui.update { ChatUi(conversationId = store.newConversation(), takeoverStage = Takeover.Stage.None, attachEnabled = it.attachEnabled) }
            return
        }
        if (busy()) return
        liveIndex = -1
        _ui.update { ChatUi(takeoverStage = it.takeoverStage, attachEnabled = it.attachEnabled) }
    }

    /** 删掉 [id] 会连带什么:侧栏的确认弹窗据此写明会停掉任务、删掉定时任务。 */
    fun deleteImpact(id: String): DeleteImpact = deletionImpact(id)

    /**
     * 删掉对话 [id](用户在侧栏确认过):当场从侧栏拿掉;是屏上这个对话就换成一个新的空对话。
     * 停任务、删定时任务、删文件在后台做(见 `ConversationDeleter`)。
     */
    fun deleteConversation(id: String) {
        if (!deleting.add(id)) return
        _deleted.update { it + id }
        if (id == _ui.value.conversationId) startOver(id) else unpark(id)
        scope.launch {
            try { removeConversation(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { } finally { deleting -= id }
        }
    }

    /** 用户在侧栏给对话 [id] 改名:换成 [title](去掉两头空白;空的不改)。之后起好的标题不会再盖掉它。 */
    fun renameConversation(id: String, title: String) {
        if (id in _deleted.value) return
        runCatching { store.rename(id, title) }
    }

    /** 侧栏里置顶或取消置顶对话 [id]。 */
    fun setPinned(id: String, pinned: Boolean) {
        if (id in _deleted.value) return
        runCatching { store.setPinned(id, pinned, clock()) }
    }

    /** 屏上的对话被删了:不再跟它的任务(还没交给服务的开始直接作废),换成新的空对话。 */
    private fun startOver(id: String) {
        lingerJob?.cancel()
        pendingStart?.cancel()
        pendingStart = null
        held.clear()
        unpark(id)
        liveIndex = -1
        screenSeen = false
        val fresh = if (parallel()) store.newConversation() else null
        _ui.update { ChatUi(conversationId = fresh, takeoverStage = Takeover.Stage.None, attachEnabled = it.attachEnabled) }
    }

    fun busy() = _ui.value.composer != ComposerMode.Idle

    /** 正在操作的 App 或网站(最近一件有对象的事);顶栏副标题「正在操作 …」用。没有时为 null。 */
    fun livePlace(ex: Exchange): String? =
        ex.activities.lastOrNull { !it.target.isNullOrBlank() }?.target?.let { where(it, appLabel) }

    /** 该对话正在跑的任务的原话;侧栏「进行中」里还没存进对话列表的那条用它当标题。 */
    fun runningGoal(conversationId: String): String? = runGoal(conversationId)

    /** 能不能换到别的对话:并行时随时能换,否则要等这个对话的任务结束。 */
    fun canSwitch() = parallel() || !busy()

    private fun onState(s: TaskState) {
        if (liveIndex < 0) {
            // 任务不是从这里开始的(比如界面重建前就在跑):接着显示,只是没有用户那句话
            if (!s.active()) return
            screenSeen = screenOpen.value
            _ui.update {
                liveIndex = it.exchanges.size
                // 原话、文件按对话取:这条状态属于屏上这个对话的任务
                val goal = it.conversationId?.let(runGoal).orEmpty()
                val files = if (ownTask()) sharedFiles.value else emptyList()
                val ex = Exchange(goal, emptyList(), ExchangeStatus.Working, live = true, files = files, startedAt = clock())
                it.copy(exchanges = it.exchanges + ex)
            }
        }
        _ui.update { ui ->
            val old = ui.exchanges[liveIndex]
            val ex = reduce(withGoal(old, ui.conversationId), s, appLabel, toolLabels::label).let {
                if (it.status.active() || !old.status.active()) it
                // 收尾时还没交到任务手里的插话:服务把它当成这个对话的下一件事
                else withSummarizing(withNotes(it.copy(runId = endedRunFor(ui.conversationId), elapsedMs = elapsedOf(old),
                    steers = it.steers.map { n -> if (n.state.unconsumed) n.copy(state = SteerState.Next) else n }), runNotes.value))
            }
            val exchanges = ui.exchanges.toMutableList().also { it[liveIndex] = ex }
            ui.copy(
                exchanges = exchanges,
                pip = gated(pipFor(ui.pip, old.status, ex)),
                composer = when {
                    ex.status is ExchangeStatus.NeedsInput -> ComposerMode.Answer
                    ex.status.active() -> ComposerMode.Busy
                    else -> ComposerMode.Idle
                },
            )
        }
        if (!s.active() && s != TaskState.Idle) {
            liveIndex = -1
            // 这一轮可能已先一步存进对话、补到了屏上:按运行编号并成一条
            _ui.value.conversationId?.let(::syncSaved)
            if (s is TaskState.Succeeded) {
                lingerJob = scope.launch {
                    delay(DONE_LINGER_MS)
                    _ui.update { it.copy(pip = it.pip.copy(visible = false, expanded = false)) }
                }
            }
        }
    }

    /** 正在跟的那一问一答还没有原话(接上别处开始的任务时它还没登记):按屏上这个对话的任务补上。 */
    private fun fillGoal() = _ui.update { ui ->
        val ex = ui.exchanges.getOrNull(liveIndex) ?: return@update ui
        val filled = withGoal(ex, ui.conversationId)
        if (filled === ex) ui else ui.copy(exchanges = ui.exchanges.toMutableList().also { it[liveIndex] = filled })
    }

    private fun withGoal(ex: Exchange, conversationId: String?): Exchange {
        if (ex.goal.isNotEmpty() || !ex.status.active()) return ex
        return conversationId?.let(runGoal)?.takeIf(String::isNotEmpty)?.let { ex.copy(goal = it) } ?: ex
    }

    private fun elapsedOf(ex: Exchange): Long? = ex.startedAt.takeIf { it > 0 }?.let { (clock() - it).coerceAtLeast(0) }

    private fun endedRunFor(conversationId: String?): String? {
        // 没有对话编号(接上界面重建前就在跑的任务)时只能用显示中的那个
        val byConversation = endedRunIds ?: return endedRunId.value
        return if (conversationId == null) endedRunId.value else byConversation.value[conversationId]
    }

    private fun pipFor(old: PipUi, before: ExchangeStatus, ex: Exchange): PipUi {
        val st = ex.status
        return when (st) {
            is ExchangeStatus.Done -> PipUi(true, PipMode.Done, false)
            is ExchangeStatus.Failed, is ExchangeStatus.Cancelled -> PipUi()
            else -> {
                val needs = st.needsUser()
                // 只有接管刚开始时自动展开(提问、暂停要让输入框和卡片按钮留在眼前);接管结束时收起;
                // 同一次接管里用户收起后不再弹开
                val takeover = st is ExchangeStatus.NeedsTakeover
                val wasTakeover = before is ExchangeStatus.NeedsTakeover
                val expanded = when {
                    takeover && !wasTakeover -> true
                    !takeover && wasTakeover -> false
                    else -> old.expanded
                }
                PipUi(true, if (needs) PipMode.NeedsYou else PipMode.Running, expanded)
            }
        }
    }

    companion object {
        const val DONE_LINGER_MS = 3_000L


        private fun TaskState.active() = when (this) {
            TaskState.Idle, is TaskState.Succeeded, is TaskState.Failed, TaskState.Cancelled -> false
            else -> true
        }

        private fun ExchangeStatus.active() = when (this) {
            is ExchangeStatus.Done, is ExchangeStatus.Failed, is ExchangeStatus.Cancelled -> false
            else -> true
        }

        private fun ExchangeStatus.needsUser() =
            this is ExchangeStatus.NeedsTakeover || this is ExchangeStatus.NeedsInput || this is ExchangeStatus.Paused ||
                this is ExchangeStatus.NeedsConfirmation

        internal fun reduce(ex: Exchange, s: TaskState, appLabel: (String) -> String = { it }, toolLabel: (String) -> String = { it }): Exchange =
            state(ex, s, appLabel).let { it.copy(steps = rows(it, toolLabel, appLabel)) }

        private fun state(ex: Exchange, s: TaskState, appLabel: (String) -> String): Exchange = when (s) {
            TaskState.Idle -> ex
            is TaskState.Running -> {
                val progress = s.progress?.let(StatusCopy::progress)
                // 「在做什么」先用模型随调用写的话,没有再用调用里的用意
                val said = (s.narration ?: s.reason)?.takeIf(String::isNotBlank)
                val waiting = progress == null && s.tool == null && said == null
                ex.copy(
                    status = ExchangeStatus.Working, waitingForModel = waiting, draft = s.preview,
                    line = if (waiting) "" else progress ?: said.orEmpty(),
                    notes = if (s.step > 0 && said != null) ex.notes + (s.step to said) else ex.notes,
                )
            }
            // 等能力的前置条件:锁屏时自动继续,其余要你点继续
            is TaskState.Held -> ex.copy(
                status = if (s.reason == TaskGate.LOCKED) ExchangeStatus.WaitingUnlock else ExchangeStatus.Paused(s.reason.why),
                waitingForModel = false, draft = null,
            )
            is TaskState.WaitingTakeover ->
                ex.copy(status = ExchangeStatus.NeedsTakeover(s.reason, s.message, s.item, s.amount, s.address), waitingForModel = false, draft = null)
            is TaskState.WaitingInput -> ex.copy(status = ExchangeStatus.NeedsInput(s.prompt, s.options), waitingForModel = false, draft = null)
            is TaskState.WaitingConfirmation -> ex.copy(
                waitingForModel = false,
                draft = null,
                status = ExchangeStatus.NeedsConfirmation(
                    ConfirmationCopy.title(s.request), ConfirmationCopy.detail(s.request, appLabel(s.request.app)),
                    ConfirmationNotifier.requestKey(s),
                ),
            )
            // 回答原样显示(不再有模型写的依据段);来源由运行记录里代码记下的那份给出
            is TaskState.Succeeded -> ex.copy(status = ExchangeStatus.Done(s.answer.trim(), null, s.verified, s.note, s.unconfirmed, s.reviewed),
                waitingForModel = false, draft = null, line = "")
            is TaskState.Failed -> ex.copy(status = ExchangeStatus.Failed(s.reason), waitingForModel = false, draft = null, line = "")
            TaskState.Cancelled -> ex.copy(status = ExchangeStatus.Cancelled(), waitingForModel = false, draft = null, line = "")
        }

        /**
         * 步骤列表:每件实际做过的事一行。读取类只在知道读的是哪里时出现,连着读同一处合成一行(「看了微信的聊天列表」);
         * 动作写「点击 · 微信」,那一步模型写了话就用它的话;没成功只说没成功,不显示错误码。
         * 最后一行的状态跟着这一问一答:在跑、等你、结束。
         */
        internal fun rows(ex: Exchange, toolLabel: (String) -> String, appLabel: (String) -> String): List<StepRow> {
            val out = mutableListOf<StepRow>()
            val said = mutableSetOf<Int>()
            for (a in ex.activities) {
                val where = a.target?.let { where(it, appLabel) }
                if (a.kind == "observe") {
                    // 读成功了却不知道读的是哪里:不列;没读成的照样列出来
                    if (where == null && !a.failed) continue
                    val text = if (where == null) toolLabel(a.label)
                        else a.title?.takeIf(String::isNotBlank)?.let { tr(R.string.step_viewed_part, where, it) } ?: tr(R.string.step_viewed, where)
                    val subtitle = if (a.failed) tr(R.string.step_failed) else ""
                    val last = out.lastOrNull()
                    if (last?.title != text || last.subtitle != subtitle) out += StepRow(a.step, text, subtitle, StepState.Ok)
                } else {
                    val reason = if (said.add(a.step)) ex.notes[a.step].orEmpty() else ""
                    val title = listOfNotNull(toolLabel(a.label), where).joinToString(" · ")
                    out += StepRow(a.step, title, if (a.failed) tr(R.string.step_failed) else "", StepState.Ok, reason)
                }
            }
            if (out.isEmpty()) return out
            val last = when (ex.status) {
                ExchangeStatus.Working -> StepState.Run
                is ExchangeStatus.Done -> StepState.Ok
                is ExchangeStatus.Failed, is ExchangeStatus.Cancelled -> StepState.Dim
                else -> StepState.Wait
            }
            out[out.lastIndex] = out.last().copy(state = last)
            return out
        }

        /** 活动对象给人看的说法:包名换成 App 名,「网站 域名」只留域名。 */
        private fun where(target: String, appLabel: (String) -> String): String? = when {
            target.isBlank() -> null
            target.startsWith(SITE_PREFIX) -> target.removePrefix(SITE_PREFIX)
            com.wangzan101.mika.core.tool.PACKAGE_NAME_RE.matches(target) -> appLabel(target)
            else -> target
        }

        private fun fromTurn(t: Turn): Exchange {
            val status = when (t.status) {
                "succeeded" -> answerParts(t.outcome).let { (a, e) -> ExchangeStatus.Done(a, e) }
                "unverified" -> answerParts(t.outcome).let { (a, e) -> ExchangeStatus.Done(a, e, verified = false, note = t.note, unconfirmed = t.unconfirmed) }
                "cancelled" -> ExchangeStatus.Cancelled(Conversation.stopSummary(t))
                else -> ExchangeStatus.Failed(t.outcome)
            }
            return Exchange(t.userText, emptyList(), status, live = false, runId = t.runId,
                memoryNotes = t.memoryNotes, usedMemory = t.usedMemory, skillNotes = t.skillNotes,
                steers = t.steers.mapIndexed { i, text -> SteerNote("turn-$i", text, SteerState.Joined) })
        }


        /** 消息末尾每个附件一行:「附件:/workspace/inbox/<名字>(<大小>)」;[files] 是复制进工作区后的 (路径, 字节数)。 */
        internal fun withAttachments(text: String, files: List<Pair<String, Long>>): String =
            AttachmentMessage.compose(text, files.map { (p, n) -> p to sizeLabel(n) })

        fun sizeLabel(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
            else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }
}

/**
 * 结果卡片上的回答和依据文字,只从这里取。新的回答不带依据段(来源由代码从证据账本里找,见 [evidenceSource]);
 * 旧的运行记录、对话文件里模型自己写在末尾的「依据:」段照旧拆出来。[evidence] 给了就直接用。
 */
fun answerParts(answer: String, evidence: String? = null): Pair<String, String?> =
    evidence?.trim()?.takeIf { it.isNotEmpty() }?.let { answer.trim() to it } ?: splitEvidence(answer)

/**
 * 回答的来源:所在的 App 或网站([place],不知道时为 null)、相关的画面编号,和逐条的说明([items],「第 3 步 · 12306 · 车次查询」)。
 * 从运行记录里取:有代码记下的来源(sources 事件)就用它;旧记录用最后存下画面的那一步。
 */
data class EvidenceSource(val place: String?, val screenshots: List<Int>, val items: List<String> = emptyList())

/** 见 [EvidenceSource];[appLabel] 把包名换成 App 名,网站和网址显示域名。 */
fun evidenceSource(events: List<RunEvent>, appLabel: (String) -> String = { it }): EvidenceSource {
    events.filterIsInstance<RunEvent.Sources>().lastOrNull()?.items?.takeIf { it.isNotEmpty() }?.let { list ->
        fun where(p: String?) = p?.let { placeLabel(it, appLabel) }
        val items = list.map { s -> listOfNotNull(tr(R.string.step_number, s.step), where(s.place), s.title).joinToString(" · ") }
        return EvidenceSource(list.asReversed().firstNotNullOfOrNull { where(it.place) }, list.flatMap { it.mediaIds }.distinct(), items)
    }
    val shots = events.filterIsInstance<RunEvent.Media>()
    val lastStep = shots.maxOfOrNull { it.step } ?: return EvidenceSource(null, emptyList())
    val final = shots.filter { it.step == lastStep }
    val place = shots.lastOrNull { it.place != null }?.place?.let { if (it.startsWith(SITE_PREFIX)) it.removePrefix(SITE_PREFIX) else appLabel(it) }
    return EvidenceSource(place, final.map { it.mediaId }.distinct())
}

/** 运行记录里网页那一屏的「地方」写成「网站 <域名>」。 */
private const val SITE_PREFIX = "网站 "

/** 来源里的地方:「网站 域名」和网址显示域名,包名换成 App 名,别的(命令、文件)原样。 */
private fun placeLabel(place: String, appLabel: (String) -> String): String = when {
    place.startsWith(SITE_PREFIX) -> place.removePrefix(SITE_PREFIX)
    place.startsWith("http://") || place.startsWith("https://") -> runCatching { java.net.URI(place).host }.getOrNull() ?: place
    com.wangzan101.mika.core.tool.PACKAGE_NAME_RE.matches(place) -> appLabel(place)
    else -> place
}

/** 「来源」那一行:「来源 · 12306 · 2 张截图」;没有地方、没有截图时省掉那一段。 */
fun sourceLabel(source: EvidenceSource?): String = listOfNotNull(
    tr(R.string.source_label), source?.place, source?.screenshots?.size?.takeIf { it > 0 }?.let { trPlural(R.plurals.screenshots, it) },
).joinToString(" · ")

/**
 * 把答案拆成「结论」和「依据」。旧的运行记录、对话文件里,回答的最后一行以「依据:」开头;
 * 模型不一定照做,所以也认「证据 / 来源」和句末接着写的情况,之后的都算依据。
 * 认不出来时整段都是结论,回答下面不放「来源」。
 */
fun splitEvidence(answer: String): Pair<String, String?> {
    val m = EVIDENCE.find(answer) ?: return answer.trim() to null
    val evidence = answer.substring(m.range.last + 1).trim()
    val main = answer.substring(0, m.range.first + m.groupValues[1].length).trim()
    if (evidence.isEmpty() || main.isEmpty()) return answer.trim() to null
    return main to evidence
}

// 段首或句末标点之后,可带 markdown 的 **、#、- 等前缀
private val EVIDENCE = Regex("""(^|\n|[。.!！?？]\s*)[\s*#>\-]*\**(?:依据|证据|来源)\**\s*[:：]\**""")

/**
 * 工具在界面上的说法:工具声明里的 label(中文)按界面语言换成对应的资源;没有 label 的原样显示工具名。
 * 改名前的运行记录里的旧名按 [legacy] 认成现在的工具。
 */
class ToolLabels(private val tools: ToolIndex, private val legacy: LegacyNames = legacyNames()) {
    fun label(tool: String): String {
        val declared = (tools[tool] ?: legacy.resolve(tool)?.let { tools[it] })?.label ?: return tool
        return TOOL_LABELS[declared]?.let { tr(it) } ?: declared
    }

    companion object {
        /** 工具声明里的 label → 界面文字;中文资源与 label 一字不差(ToolLabelsTest 核对每个工具都有)。 */
        val TOOL_LABELS: Map<String, Int> = mapOf(
            "查看已装的 App" to R.string.tool_list_apps,
            "打开 App" to R.string.tool_open_app,
            "分享文字到 App" to R.string.tool_share_text,
            "设闹钟" to R.string.tool_set_alarm,
            "设计时器" to R.string.tool_set_timer,
            "查看闹钟" to R.string.tool_show_alarms,
            "添加日程" to R.string.tool_add_calendar_event,
            "拨滚轮" to R.string.tool_set_wheel,
            "滚动查找" to R.string.tool_scroll_find,
            "等待界面条件" to R.string.tool_wait_condition,
            "等待界面事件" to R.string.tool_wait_event,
            "提取界面数据" to R.string.tool_extract,
            "检查弹窗" to R.string.tool_check_dialog,
            "多点手势" to R.string.tool_gesture,
            "系统命令" to R.string.tool_shell,
            "查找界面控件" to R.string.tool_find_element,
            "采集多屏内容" to R.string.tool_collect,
            "连续操作手机" to R.string.tool_phone_run,
            "处理文件" to R.string.tool_process_files,
            "运行自带脚本" to R.string.tool_run_script,
            "读文件" to R.string.tool_read_file,
            "写文件" to R.string.tool_write_file,
            "搜索文件" to R.string.tool_search_files,
            "导出文件" to R.string.tool_export_file,
            "打开网页" to R.string.tool_open_page,
            "切换网页标签" to R.string.tool_switch_tab,
            "切换网页版本" to R.string.tool_switch_version,
            "读网页" to R.string.tool_read_page,
            "查看下载" to R.string.tool_downloads,
            "查看网页收到的数据" to R.string.tool_network,
            "等网页变化" to R.string.tool_wait_page,
            "读取网页数据" to R.string.tool_read_script,
            "运行站点命令" to R.string.tool_site_command,
            "上传文件" to R.string.tool_upload,
            "遇到障碍" to R.string.tool_obstacle,
            "查找 App/网站知识" to R.string.tool_find_knowledge,
            "查找技能" to R.string.tool_find_skill,
            "查看 App/网站知识" to R.string.tool_read_knowledge,
            "查看技能" to R.string.tool_read_skill,
            "说明没办成的原因" to R.string.tool_explain_failure,
            "问你一个问题" to R.string.tool_ask_user,
            "请你接手" to R.string.tool_takeover,
            "请你同意" to R.string.tool_confirm,
            "等待" to R.string.tool_wait,
            "检查屏幕" to R.string.tool_diagnose,
            "返回" to R.string.tool_back,
            "按键" to R.string.tool_press_key,
            "看屏幕" to R.string.tool_observe,
            "读取界面元素" to R.string.tool_list_elements,
            "截图" to R.string.tool_screenshot,
            "滚动" to R.string.tool_scroll,
            "滑动" to R.string.tool_swipe,
            "点击" to R.string.tool_tap,
            "输入文字" to R.string.tool_type,
            "清空输入框" to R.string.tool_clear,
        )
    }
}
