// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.task

import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import android.widget.Toast
import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.ConversationTitler
import com.wangzan101.mika.memory.MemoryRuntime
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.confirm.ConfirmationCopy
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.Conversation
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.trigger.ConditionOutcome
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.DeliveryState
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.SendOutcome
import com.wangzan101.mika.core.run.ChannelInteraction
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.takeover.TakeoverActivity
import com.wangzan101.mika.ui.MainActivity
import com.wangzan101.mika.ui.ScreenCopy
import com.wangzan101.mika.ui.StatusCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

/**
 * 这条通知照发、照进通知栏,但不响、不振、不弹横幅:用系统的分组提醒规则(只让组摘要提醒,这条是组里的普通通知,
 * 组里没有摘要),和系统自己的「静音通知」是同一个做法;不换频道、不新建频道。安卓 8 起都有,Mika 支持的系统都能用。
 */
internal fun Notification.Builder.alertQuietly(): Notification.Builder =
    setGroup(QUIET_GROUP).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)

private const val QUIET_GROUP = "com.wangzan101.mika.quiet"

/**
 * 前台服务:在 Agent 虚拟屏上跑任务,单个任务的状态与执行在 [TaskRun],运行中的任务登记在 [TaskRegistry]。
 * 开关 parallelTasks 关着时同一时间只跑一个(M1 行为:跑着时新的 start 被忽略);
 * 打开时不同对话的任务可以同时跑,各占一块虚拟屏,上限见 [ParallelCapability],超出的排队。
 * 每个任务各有一组通知(编号与按钮请求码按 [TaskRun.slot] 分开,按钮带着对话编号),
 * 前台常驻通知是其中一个任务的进度通知,同时有几个任务时注明「n 个任务进行中」。
 * 任务在跑时屏幕常亮([KeepAwake],不改系统设置和亮度);任务期间目标 App 静音(原模式持久化,异常退出后下次启动恢复),
 * AgentLoop 的 gate 由 [TaskGate] 实现:熄屏/锁屏等待解锁,App 被挪到主屏或手机操作助手断开则暂停到用户点「继续」。
 * Agent 请求接管或提问时另发一条醒目的通知,接管流程见 [Takeover]。
 *
 * 前台服务类型用 specialUse:dataSync 在安卓 15 有每日 6 小时上限且语义不符;
 * specialUse 没有运行时限制,只需在清单里写明子类型。
 */
class TaskService : Service() {
    internal val taskState: TaskCenterState get() = (application as com.wangzan101.mika.MikaApp).taskState
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** 最近一次 start 时读到的开关;关着时登记处上限恒为 1。 */
    @Volatile private var parallel = false
    private val center by lazy {
        TaskCenter<TaskRun>(::limit, { it.conversationId }, { it.key }, (application as com.wangzan101.mika.MikaApp).runStore,
            (application as com.wangzan101.mika.MikaApp).resources) { c, g ->
            TaskRun(c, g, this, scope, freeSlot(), parallel)
        }
    }
    private val outbox get() = (application as com.wangzan101.mika.MikaApp).outbox
    private val localChannel by lazy { LocalChannel { _, delivery ->
        if (delivery is Delivery.Ask) {
            center.byConversation(delivery.threadId)?.let(::refreshNotifications)
            SendOutcome.Delivered
        } else {
            val resultId = if (delivery is Delivery.Result && center.byConversation(delivery.threadId)?.parallel == false) ID_RESULT
                else nextResultId()
            notifyDelivery(this, delivery, resultId)
        }
    } }
    /** 前台常驻通知属于哪个任务;收尾后留着,直到换成别的任务。 */
    @Volatile private var foreground: TaskRun? = null
    private val notifyLock = Any()
    @Volatile private var lastStartId = 0

    internal val clipLock get() = center.clipLock
    internal val takeoverQueue get() = center.takeoverQueue
    /** 几个任务共用的屏幕常亮锁。 */
    internal val wakeLock by lazy { SharedWakeLock(ScreenWakeLock(this)) }

    private fun limit(): ParallelLimit {
        if (!parallel) return ParallelLimit(1, null)
        val lowRam = getSystemService(ActivityManager::class.java).isLowRamDevice
        return ParallelCapability.detect(Build.VERSION.SDK_INT, lowRam, FeatureFlags.get(this).parallelMax(), taskState.secondDisplayFailed)
            .also { taskState._parallelLimit.value = it }
    }

    /** 登记处里没人用的最小槽位;只在登记处的锁内调用。 */
    private fun freeSlot(): Int {
        val used = center.runs().map { it.slot }.toSet()
        return generateSequence(0) { it + 1 }.first { it !in used }
    }

    internal fun reservePackage(pkg: String, runKey: String, stillUsed: () -> Boolean) = center.reserve(pkg, runKey, stillUsed)

    /** 这个对话空着就当场开始;还有任务就留着,它收尾时([onRunEnded])交出来。查与留在同一把锁里。 */
    internal fun deferMessage(threadId: String, text: String) {
        if (center.deferOrStart(threadId, text)) start(this, text, threadId, queue = true)
    }

    internal fun interaction(threadId: String) = ChannelInteraction(outbox, localChannel,
        ChannelPeer(LocalChannel.ID, "phone-owner", threadId))

    internal fun otherRunHasDisplay(run: TaskRun) = center.running().any { it !== run && it.displayId.value >= 0 }
    internal fun hasActiveRuns() = center.running().isNotEmpty()

    private fun publishRuns() {
        val r = center.runs()
        taskState._runs.value = r
        taskState._activeConversations.value = r.map { it.conversationId }
    }

    private fun id(base: Int, run: TaskRun) = base + SLOT_STRIDE * run.slot

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
        // 没结束的运行在 App 启动时就处理过了;这里只补一道(进程里只做一次),并把此刻在跑的运行排除在外
        (application as com.wangzan101.mika.MikaApp).runRecovery.recover(center.runs().map { RunId(it.key) }.toSet())
    }

    private fun waitKeyOf(intent: Intent) = intent.getIntExtra(EXTRA_WAIT_KEY, -1).takeIf { it >= 0 }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // 通知按钮带着任务编号,只作用于那个任务(它已结束就不起作用);应用内的按钮作用于显示中的任务
        val run = if (intent?.hasExtra(EXTRA_RUN) == true) intent.getStringExtra(EXTRA_RUN)?.let(center::byKey) else taskState.shownRun
        when (intent?.action) {
            ACTION_START -> begin(intent.getStringExtra(EXTRA_GOAL).orEmpty(), intent.getStringExtra(EXTRA_CONVERSATION),
                allowQueue = intent.getBooleanExtra(EXTRA_QUEUE, false), messageId = intent.getStringExtra(EXTRA_MESSAGE_ID))
            ACTION_START_TRIGGER -> begin(intent.getStringExtra(EXTRA_GOAL).orEmpty(), intent.getStringExtra(EXTRA_CONVERSATION),
                allowQueue = true, provenance = RunProvenance(RunOrigin.Trigger, Attendance.Unattended),
                triggerKey = intent.getStringExtra(EXTRA_TRIGGER_KEY),
                triggerActionGoal = intent.getStringExtra(EXTRA_TRIGGER_ACTION),
                triggerThreadId = intent.getStringExtra(EXTRA_TRIGGER_THREAD))
            ACTION_START_MCP -> begin(intent.getStringExtra(EXTRA_GOAL).orEmpty(), intent.getStringExtra(EXTRA_CONVERSATION),
                allowQueue = true, provenance = RunProvenance(RunOrigin.Mcp,
                    Attendance.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_ATTENDANCE) } ?: Attendance.Unattended),
                mcpExternalId = intent.getStringExtra(EXTRA_MCP_ID))
            ACTION_CANCEL_MCP -> intent.getStringExtra(EXTRA_MCP_ID)?.let { id -> center.runs().firstOrNull { it.mcpExternalId == id }?.cancel() }
            ACTION_CANCEL -> if (run != null && center.cancelIfQueued(run)) cancelQueued(run) else run?.cancel()
            ACTION_RESUME -> run?.resume()
            ACTION_TAKE_OVER -> run?.takeOver(waitKeyOf(intent))
            ACTION_HAND_BACK -> run?.handBack(waitKeyOf(intent))
            ACTION_FINISHED_MYSELF -> run?.finishedMyself(waitKeyOf(intent))
            ACTION_SKIP -> run?.skip(waitKeyOf(intent))
            ACTION_PUT_ASIDE -> run?.putAside(waitKeyOf(intent))
            ACTION_ANSWER -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
                    ?: intent.getStringExtra(EXTRA_ANSWER)
                if (!text.isNullOrBlank()) run?.answer(text.trim(), ::answerTooLate)
            }
            ACTION_APPROVE, ACTION_DECLINE -> {
                val key = if (intent.hasExtra(ConfirmationNotifier.EXTRA_REQUEST)) intent.getIntExtra(ConfirmationNotifier.EXTRA_REQUEST, 0) else null
                run?.confirm(if (intent.action == ACTION_APPROVE) ConfirmChoice.Once else ConfirmChoice.Deny, key)
            }
        }
        if (!taskState._busy.value) stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun begin(newGoal: String, requestedConversation: String?, allowQueue: Boolean = false,
        provenance: RunProvenance = RunProvenance(), triggerKey: String? = null,
        triggerActionGoal: String? = null, triggerThreadId: String? = null, mcpExternalId: String? = null, messageId: String? = null) {
        parallel = FeatureFlags.get(this).speedFlags().parallelTasks
        // 同一对话里正在执行或排队的任务收到新原话时，交给它的 Inbox。
        requestedConversation?.let(center::byConversation)?.let { existing ->
            if (provenance.origin == RunOrigin.Ui) {
                if (messageId != null) existing.steer(newGoal, messageId) else existing.steer(newGoal)
                keepForeground()
                return
            }
        }
        // 插话到的时候那个任务已经收尾:作为这个对话的下一件事,不因服务还在收尾而丢掉
        if (messageId != null && requestedConversation != null) {
            deferMessage(requestedConversation, newGoal)
            keepForeground()
            return
        }
        if (triggerKey != null && center.runs().any { it.triggerWindowKey == triggerKey &&
                it.conditionCheck == (triggerActionGoal != null) }) {
            keepForeground()
            return
        }
        // startForegroundService 之后必须尽快进入前台,哪怕这次请求会被忽略
        if (!allowQueue && !parallel && taskState._busy.value) {
            keepForeground()
            return
        }
        val store = ConversationStore.shared(this)
        val conversationId = requestedConversation ?: store.newConversation()
        when (val r = center.tryStart(conversationId, newGoal, queueInConversation = provenance.origin == RunOrigin.Trigger)) {
            StartResult.AlreadyRunningInConversation -> keepForeground()
            is StartResult.Queued -> {
                nameConversation(store, conversationId, newGoal, provenance)
                r.run.provenance = provenance
                r.run.triggerWindowKey = triggerKey
                r.run.triggerActionGoal = triggerActionGoal
                r.run.triggerThreadId = triggerThreadId
                if (triggerKey != null) center.store.get(com.wangzan101.mika.core.run.RunId(r.run.key))?.let {
                    center.store.save(it.copy(triggerWindowKey = triggerKey))
                }
                r.run.mcpExternalId = mcpExternalId
                taskState._busy.value = true
                taskState._conversationId.value = conversationId
                // 先登记再显示:界面收到它的状态时,任务列表里已经有它(对话、原话)
                publishRuns()
                // 清空成功标准、运行编号,显示排队
                if (center.running().none { it.conversationId == conversationId }) r.run.show()
                r.run.queue(r.position)
                keepForeground()
            }
            is StartResult.Started -> {
                nameConversation(store, conversationId, newGoal, provenance)
                taskState._busy.value = true
                taskState._conversationId.value = conversationId
                // 清空成功标准、运行编号,状态置为「正在准备」
                val run = r.run
                run.provenance = provenance
                run.triggerWindowKey = triggerKey
                run.triggerActionGoal = triggerActionGoal
                run.triggerThreadId = triggerThreadId
                if (triggerKey != null) center.store.get(com.wangzan101.mika.core.run.RunId(run.key))?.let {
                    center.store.save(it.copy(triggerWindowKey = triggerKey))
                }
                run.mcpExternalId = mcpExternalId
                // 先登记再显示:界面收到它的状态时,任务列表里已经有它(对话、原话)
                publishRuns()
                run.show()
                val fg = foreground
                if (fg != null && fg !== run && fg in center.running()) keepForeground()
                else {
                    foreground = run
                    startInForeground(run, progressNotification(run, run.state.value))
                }
                launch(run, store)
            }
        }
    }

    /** 用户在新对话里发的第一句话:后台请模型起一个标题记下(只起一次,起不出来就不记)。 */
    private fun nameConversation(store: ConversationStore, id: String, first: String, provenance: RunProvenance) {
        if (provenance.origin != RunOrigin.Ui) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val env = TaskEnvironment.of(this@TaskService)
                // 起标题也属于这段对话,和对话里的任务用同一个会话号
                val session = ModelSession.of(id)
                ConversationTitler.ensure(store, id, first) {
                    listOfNotNull(env.lightModelFactory(this@TaskService, session), env.modelFactory(this@TaskService, session))
                }
            }.onFailure { if (it is CancellationException) throw it }
        }
    }

    /** 已经在前台(或即将收尾)时再次进入前台:用前台那个任务的进度通知。 */
    private fun keepForeground() {
        val fg = foreground
        if (fg != null) startInForeground(fg, progressNotification(fg, fg.state.value))
        else startInForeground(null, preparingNotification())
    }

    private fun launch(run: TaskRun, store: ConversationStore) {
        run.job = scope.launch {
            center.markRunning(run)
            val result = try {
                run.execute(runCatching { store.turns(run.conversationId) }.getOrDefault(emptyList()))
            } catch (e: CancellationException) {
                TaskState.Cancelled
            } catch (e: RemoteException) {
                run.shellDied = true
                TaskState.Failed(SHELL_DIED, FailKind.Environment)
            } catch (e: Exception) {
                TaskState.Failed(tr(R.string.task_error, e.message ?: e::class.simpleName), FailKind.Environment)
            }
            // shell 进程没了,虚拟屏和上面的 App 一起没了;任务没法接着做
            val end = if (run.shellDied && result !is TaskState.Succeeded) TaskState.Failed(SHELL_DIED, FailKind.Environment) else result
            // 停下了:界面当场显示已停止,交代随后补上(先登记「正在整理」,界面收到终态时就知道还有话要来)
            val draft = if (end == TaskState.Cancelled) run.draftText() else null
            val stopRunId = run.runId.value?.takeIf { end == TaskState.Cancelled && !run.conditionCheck && run.hasStopMaterial(draft) }
            stopRunId?.let { id -> taskState._summarizing.update { it + id } }
            run.end(end)
            if (!run.conditionCheck) recordTurn(store, run.conversationId, run.goal, end, run.runId.value, run.sharedFiles.value, run.joinedSteers(), run.usedGroups)
            stopRunId?.let { summarizeStop(run, it, draft, store) }
            if (run.conditionCheck) {
                val outcome = if (end is TaskState.Succeeded) ConditionOutcome.parse(end.answer)
                    else ConditionOutcome.Error((end as? TaskState.Failed)?.reason ?: tr(R.string.task_condition_interrupted))
                TriggerRuntime.checked(this@TaskService, run.triggerWindowKey.orEmpty(), run.triggerActionGoal.orEmpty(),
                    run.triggerThreadId.orEmpty(), outcome)
                if (outcome is ConditionOutcome.Error) {
                    val failure = TaskState.Failed(tr(R.string.task_check_unfinished, outcome.reason), FailKind.Environment)
                    recordTurn(store, run.conversationId, run.goal, failure, run.runId.value)
                    runCatching { notifyResult(run, failure, "condition:${run.key}") }
                        .onFailure { Log.w(TAG, "condition failure notification failed", it) }
                }
                center.markDelivered(run)
                onRunEnded(run, store)
                return@launch
            }
            val deliveryId = "result:${run.key}"
            center.markResult(run, describe(end).second, deliveryId, successful = end is TaskState.Succeeded)
            run.clearTakeoverStage()
            synchronized(notifyLock) { getSystemService(NotificationManager::class.java).cancel(id(ID_ATTENTION, run)) }
            // 先发结果通知,再在后台学(提取记忆、记技能结果、起草技能);用进程级作用域,服务退出也做完
            var delivered = false
            val app = applicationContext
            runCatching {
                RunLearning.launchAfter(MemoryRuntime.of(this@TaskService).scope, {
                    delivered = notifyResult(run, end, deliveryId) == DeliveryState.Delivered
                    // 定时任务的结果当场按结局记(和以前一样),判下来没办成再改记;不靠服务一直在
                    if (delivered) run.triggerWindowKey?.let { key ->
                        runCatching {
                            if (end is TaskState.Succeeded) TriggerRuntime.delivered(app, key)
                            else TriggerRuntime.actionFailed(app, key, describe(end).second)
                        }.onFailure { Log.w(TAG, "could not record trigger result", it) }
                    }
                }) {
                    // 交出之后:回答按用户的要求判结局(记进运行记录和这一轮对话),只从办成的学
                    val judged = runCatching { run.settleOutcome(end, store) }.onFailure { Log.w(TAG, "outcome judging failed", it) }.getOrNull()
                    // 回答如实说没办成、只办了一部分的,和放弃一样记成没办成;没判成的照交出算
                    val notDone = judged?.outcome == RunOutcome.Partial || judged?.outcome == RunOutcome.NotPossible
                    if (delivered && notDone && end is TaskState.Succeeded) run.triggerWindowKey?.let { key ->
                        runCatching { TriggerRuntime.actionFailed(app, key, judged?.reason?.ifBlank { null } ?: describe(end).second) }
                            .onFailure { Log.w(TAG, "could not downgrade trigger result", it) }
                    }
                    if (run.provenance.origin == RunOrigin.Ui) runCatching { run.learnAfter(end, judged?.outcome, store) }
                        .onFailure { Log.w(TAG, "learning after run failed", it) }
                }
            }.onFailure { Log.w(TAG, "result notification failed", it) }
            if (delivered) center.markDelivered(run)
            run.mcpExternalId?.let { (application as com.wangzan101.mika.MikaApp).mcpTasks.ended(it, end::class.simpleName.orEmpty()) }
            onRunEnded(run, store)
        }
    }

    /**
     * 停止之后写交代,记成这一轮的回答(结局照旧是取消)。用进程级作用域在后台写:不挡着收尾、下一件事和再点停止,服务退出也写完。
     * 写好或写不成都从「正在整理」里拿掉。
     */
    private fun summarizeStop(run: TaskRun, runId: String, draft: String?, store: ConversationStore) {
        MemoryRuntime.of(this).scope.launch {
            try {
                val text = runCatching { run.stopSummary(draft) }.onFailure { Log.w(TAG, "stop summary failed", it) }.getOrNull()
                if (text != null) withContext(Dispatchers.IO) {
                    runCatching { store.updateTurn(run.conversationId, runId) { it.copy(outcome = text) } }
                        .onFailure { Log.w(TAG, "could not save stop summary", it) }
                }
            } finally {
                taskState._summarizing.update { it - runId }
            }
        }
    }

    /** 任务收尾之后:都结束了就退出前台;还有别的任务就把前台交给它们,并放出排队的任务。 */
    private fun onRunEnded(run: TaskRun, store: ConversationStore) {
        val wasShown = taskState.shownRun === run
        val started = center.onEnded(run)
        publishRuns()
        if (wasShown) started.firstOrNull { it.conversationId == run.conversationId }?.show()
        // 对话空出来就开始留给它的话,不等别的对话的任务
        val ready = center.takeReady()
        if (center.runs().isEmpty()) {
            if (ready.isNotEmpty()) {
                ready.forEach { (threadId, message) -> begin(message, threadId, allowQueue = true) }
                return
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            // 先取编号再放开:放开之后才到的新任务会带来更新的编号,这里的 stopSelf 就不会把它停掉
            val id = lastStartId
            taskState._busy.value = false
            stopSelf(id)
            return
        }
        // 先换成「正在准备」:放出来的任务可能正好接过前台,通知不能还写着排队
        started.forEach { it.dequeue() }
        if (foreground === run) center.running().firstOrNull()?.let { next ->
            foreground = next
            startInForeground(next, progressNotification(next, next.state.value))
        }
        synchronized(notifyLock) { getSystemService(NotificationManager::class.java).cancel(id(ID_PROGRESS, run)) }
        started.forEach {
            launch(it, store)
            refreshNotifications(it)
        }
        center.queued().forEachIndexed { i, q -> q.queue(i + 1) }
        center.running().forEach(::refreshNotifications)
        ready.forEach { (threadId, message) -> begin(message, threadId, allowQueue = true) }
    }

    /** 排队中的任务被取消(已移出队伍):没开跑,直接记为已取消。 */
    private fun cancelQueued(run: TaskRun) {
        run.handoffUnconsumed(cancelled = true)
        run.end(TaskState.Cancelled)
        recordTurn(ConversationStore.shared(this), run.conversationId, run.goal, TaskState.Cancelled, null)
        publishRuns()
        center.queued().forEachIndexed { i, q -> q.queue(i + 1) }
    }

    /** 任务结束时记一轮,后续消息可带上它作参考。写盘失败不影响任务结果。 */
    private fun recordTurn(store: ConversationStore, id: String, goal: String, end: TaskState, runId: String?, files: List<SharedFile> = emptyList(),
        steers: List<String> = emptyList(), groups: List<String> = emptyList()) {
        val turn = when (end) {
            // 没核对过的(聊天、回答里没有可核对的值)和核实过的一样记成 succeeded;核对过却没能核实的才记 unverified
            is TaskState.Succeeded -> if (end.doubtful) Turn(goal, end.answer, "unverified", end.note, unconfirmed = end.unconfirmed) else Turn(goal, end.answer, "succeeded")
            is TaskState.Failed -> Turn(goal, end.reason, "failed")
            TaskState.Cancelled -> Turn(goal, Conversation.CANCELLED_OUTCOME, "cancelled")
            else -> return
        }.copy(runId = runId, steers = steers, groups = groups)
        runCatching { store.append(id, turn, files) }.onFailure { Log.w(TAG, "could not save conversation turn", it) }
    }

    /**
     * 按此刻的状态重画通知。多个协程都会调用,串行并在锁内取最新状态:
     * 否则拿着旧状态的一方可能在新状态撤掉通知之后又把它发出来。
     */
    internal fun refreshNotifications(run: TaskRun) {
        synchronized(notifyLock) {
            val shown = run.shownState() ?: return
            if (!shown.isActive() || center.isQueued(run)) return
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(id(ID_PROGRESS, run), progressNotification(run, shown))
            val attention = attentionNotification(run, shown)
            if (attention != null) nm.notify(id(ID_ATTENTION, run), attention) else nm.cancel(id(ID_ATTENTION, run))
        }
    }

    /** 已交还、循环还没往下走的那一小段:不再显示接管按钮。 */
    private fun awaitingUser(run: TaskRun, state: TaskState) =
        state !is TaskState.WaitingTakeover || run.takeoverStage.value != Takeover.Stage.None

    /** 主屏一次只交一个 App:接管排在别的任务后面时先不给接管按钮。 */
    private fun takeoverFirst(run: TaskRun) = run.takeover?.let(takeoverQueue::isFirst) ?: true

    /** 用 [run] 的进度通知编号进前台;还没有任务对象时用第一个槽位的。 */
    private fun startInForeground(run: TaskRun?, n: Notification) {
        val nid = run?.let { id(ID_PROGRESS, it) } ?: ID_PROGRESS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(nid, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(nid, n)
        }
    }

    private fun progressNotification(run: TaskRun, state: TaskState): Notification {
        val (_, text) = describe(state) { ConfirmationNotifier.appLabel(this, it) }
        val warning = run.muteFailedFor?.let { "\n" + tr(R.string.task_may_make_sound) }.orEmpty() +
            (if (state is TaskState.WaitingTakeover && !takeoverFirst(run)) "\n$TAKEOVER_QUEUED" else "")
        val b = Notification.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(R.string.task_notif_title, ScreenCopy.plain(run.goal)))
            .setContentText(text + warning)
            .setStyle(Notification.BigTextStyle().bigText(text + warning))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
        val active = center.running().size
        if (active >= 2) b.setSubText(trPlural(R.plurals.task_active, active))
        when (state) {
            is TaskState.WaitingTakeover ->
                if (awaitingUser(run, state) && takeoverFirst(run)) takeoverActions(run).forEach(b::addAction)
                else b.addAction(action(run, tr(R.string.chat_stop), ACTION_CANCEL, 2))
            is TaskState.WaitingInput -> {
                b.addAction(replyAction(run, state.options))
                b.addAction(action(run, tr(R.string.chat_stop), ACTION_CANCEL, 2))
            }
            else -> {
                if (state is TaskState.Held && state.reason.resumable) b.addAction(action(run, tr(R.string.chat_continue), ACTION_RESUME, 1))
                b.addAction(action(run, tr(R.string.chat_stop), ACTION_CANCEL, 2))
            }
        }
        return b.build()
    }

    /** 忙着却还没有任务对象时(收尾被打断等)也得马上进前台。 */
    private fun preparingNotification(): Notification = Notification.Builder(this, CHANNEL_PROGRESS)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(tr(R.string.app_name))
        .setContentText(PREPARING)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openApp())
        .build()

    private fun answerTooLate() = Handler(Looper.getMainLooper()).post {
        Toast.makeText(this, getString(R.string.input_too_late, INPUT_TIMEOUT_MINUTES), Toast.LENGTH_LONG).show()
    }

    /**
     * 等待接管或回答时另发的醒目通知;其余状态没有。
     * 悬浮胶囊正显示在亮着的屏幕上时照发,但安静地进通知栏(胶囊已经变橙,见 [alertQuietly]);锁屏、胶囊关着或画不出来时照旧弹出。
     */
    private fun attentionNotification(run: TaskRun, state: TaskState): Notification? {
        if (!awaitingUser(run, state)) return null
        val quiet = (application as com.wangzan101.mika.MikaApp).capsule.showing.value
        if (state is TaskState.WaitingConfirmation) {
            return ConfirmationNotifier.build(
                this, CHANNEL_ATTENTION, state, TaskService::class.java, ACTION_APPROVE, ACTION_DECLINE, openApp(),
                codeOffset = SLOT_STRIDE * run.slot, runExtra = EXTRA_RUN to run.key, quiet = quiet,
            )
        }
        if (state is TaskState.WaitingTakeover && !takeoverFirst(run)) return null
        val (title, text) = when (state) {
            is TaskState.WaitingTakeover -> {
                val extra = when (run.takeover?.problem?.value) {
                    Takeover.Problem.MoveToMainFailed -> getString(R.string.takeover_move_to_main_failed)
                    Takeover.Problem.WebBusy -> getString(R.string.takeover_web_busy)
                    Takeover.Problem.MoveBackFailed -> getString(R.string.takeover_move_back_failed)
                    null -> if (run.takeoverStage.value == Takeover.Stage.HandedOver) getString(R.string.takeover_handed_over_hint) else null
                }
                val payment = StatusCopy.paymentLines(state.item, state.amount, state.address)
                val behind = run.takeover?.let(takeoverQueue::behind) ?: 0
                val waiting = if (behind > 0) trPlural(R.plurals.task_behind, behind) else null
                takeoverTitle(state.reason) to listOfNotNull(state.message, payment, extra, waiting).joinToString("\n")
            }
            is TaskState.WaitingInput -> getString(R.string.input_title) to
                "${state.prompt}\n${getString(R.string.no_answer_note, INPUT_TIMEOUT_MINUTES)}"
            else -> return null
        }
        val b = Notification.Builder(this, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true) // 同一次等待里换按钮不再响铃;离开等待时撤掉,下一次会重新提醒
            .setContentIntent(openApp())
        if (quiet) b.alertQuietly()
        when (state) {
            is TaskState.WaitingTakeover -> takeoverActions(run).forEach(b::addAction)
            is TaskState.WaitingInput -> {
                b.addAction(replyAction(run, state.options))
                // 安卓通知最多显示 3 个按钮,所以是「回复」+ 前两个选项;
                // 全部选项都放进回复的快捷选项(RemoteInput choices),多出的选项在那里能选到
                state.options.orEmpty().take(2).forEachIndexed { i, o -> b.addAction(answerAction(run, o, i)) }
            }
            else -> Unit
        }
        return b.build()
    }

    private fun takeoverTitle(reason: String): String {
        val label = when (reason) {
            "captcha" -> R.string.takeover_reason_captcha
            "login" -> R.string.takeover_reason_login
            "access_blocked" -> R.string.takeover_reason_access_blocked
            "payment" -> R.string.takeover_reason_payment
            "decision" -> R.string.takeover_reason_decision
            else -> return getString(R.string.takeover_title)
        }
        return getString(R.string.takeover_title_reason, getString(label))
    }

    private fun takeoverActions(run: TaskRun): List<Notification.Action> {
        val cancel = action(run, getString(R.string.action_cancel_task), ACTION_CANCEL, 2)
        return if (run.takeoverStage.value == Takeover.Stage.HandedOver) listOf(
            takeoverAction(run, getString(R.string.action_hand_back), ACTION_HAND_BACK, 4),
            takeoverAction(run, getString(R.string.action_finished_myself), ACTION_FINISHED_MYSELF, 5),
            cancel,
        ) else listOf(
            takeoverAction(run, getString(R.string.action_take_over), ACTION_TAKE_OVER, 3),
            takeoverAction(run, getString(R.string.action_skip), ACTION_SKIP, 7),
            cancel,
        )
    }

    /** 经由不显示界面的 Activity:从通知启动 Activity 才会收起通知栏。 */
    private fun takeoverAction(run: TaskRun, title: String, action: String, code: Int): Notification.Action {
        val pi = PendingIntent.getActivity(
            this, code + SLOT_STRIDE * run.slot, Intent(this, TakeoverActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_RUN, run.key)
                .apply { run.takeover?.let { putExtra(EXTRA_WAIT_KEY, it.waitKey) } },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, title, pi).build()
    }

    private fun replyAction(run: TaskRun, options: List<String>?): Notification.Action {
        val input = RemoteInput.Builder(KEY_REPLY).setLabel(getString(R.string.reply_hint))
            .apply { if (!options.isNullOrEmpty()) setChoices(options.toTypedArray()) }
            .build()
        // 回复内容要由系统填进 Intent,只能用可变的 PendingIntent(Intent 是显式的)
        val pi = PendingIntent.getService(
            this, 6 + SLOT_STRIDE * run.slot, Intent(this, TaskService::class.java).setAction(ACTION_ANSWER).putExtra(EXTRA_RUN, run.key),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, getString(R.string.action_reply), pi).addRemoteInput(input).build()
    }

    private fun answerAction(run: TaskRun, option: String, index: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, 10 + index + SLOT_STRIDE * run.slot,
            Intent(this, TaskService::class.java).setAction(ACTION_ANSWER).putExtra(EXTRA_ANSWER, option).putExtra(EXTRA_RUN, run.key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, option, pi).build()
    }

    private suspend fun notifyResult(run: TaskRun, end: TaskState, deliveryId: String): DeliveryState {
        val (title, text) = describe(end)
        val peer = ChannelPeer(LocalChannel.ID, "phone-owner", run.conversationId)
        val delivered = outbox.deliver(localChannel, peer, Delivery.Result(deliveryId, run.conversationId, text, title = title))
        if (delivered.state != DeliveryState.Delivered) Log.w(TAG, "result delivery ${delivered.state}: $deliveryId")
        return delivered.state
    }

    private fun action(run: TaskRun, title: String, action: String, code: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, code + SLOT_STRIDE * run.slot, Intent(this, TaskService::class.java).setAction(action).putExtra(EXTRA_RUN, run.key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, title, pi).build()
    }

    private fun openApp(threadId: String? = null) = openApp(this, threadId)

    override fun onDestroy() {
        scope.cancel()
        if (center.runs().isNotEmpty()) {
            // 标中断后当场善后(定时窗口、补送、告知、写进对话):进程还在,不等下次启动;用 App 级作用域,服务没了也做完
            center.store.interruptUnfinished()
            (application as com.wangzan101.mika.MikaApp).runRecovery.settleInterrupted()
            taskState._state.value = TaskState.Failed(tr(R.string.task_interrupted), FailKind.Environment)
            taskState._runs.value = emptyList()
            taskState._activeConversations.value = emptyList()
            taskState.shownRun = null
        }
        taskState._busy.value = false // 收尾被取消时也不能一直挡着新任务
        super.onDestroy()
    }

    companion object {
        /** 建通知频道;重复建没有副作用。App 启动时的恢复也要用,所以不依赖服务实例。 */
        internal fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val names = mapOf(CHANNEL_PROGRESS to tr(R.string.channel_progress), CHANNEL_RESULT to tr(R.string.channel_result), CHANNEL_ATTENTION to tr(R.string.channel_attention))
            // 旧的结果频道建成了较低级别,App 调不高;换新 id,旧的删掉免得设置里多一项
            nm.deleteNotificationChannel(OLD_CHANNEL_RESULT)
            CHANNEL_IMPORTANCE.forEach { (id, importance) -> nm.createNotificationChannel(NotificationChannel(id, names.getValue(id), importance)) }
        }

        internal fun openApp(context: Context, threadId: String? = null): PendingIntent = PendingIntent.getActivity(
            context, threadId?.hashCode() ?: 0, openAppIntent(context, threadId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** 打开 Mika;给了 [threadId] 时直接到那个对话。通知和悬浮胶囊共用。 */
        internal fun openAppIntent(context: Context, threadId: String? = null): Intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (threadId != null) putExtra(MainActivity.EXTRA_OPEN_CONVERSATION, threadId) }

        /** 并行时结果通知的下一个编号(循环使用)。 */
        internal fun nextResultId() = ID_RESULT_FIRST + resultCount.getAndIncrement() % RESULT_IDS

        /** 结果与提示发成通知(服务里的与 App 启动时恢复用的共用);[resultId] 是结果通知的编号。 */
        internal fun notifyDelivery(context: Context, delivery: Delivery, resultId: Int): SendOutcome {
            val nm = context.getSystemService(NotificationManager::class.java)
            when (delivery) {
                is Delivery.Result -> nm.notify(resultId, Notification.Builder(context, CHANNEL_RESULT)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(delivery.title)
                    .setContentText(delivery.text)
                    .setStyle(Notification.BigTextStyle().bigText(delivery.text))
                    .setContentIntent(openApp(context, delivery.threadId))
                    .setAutoCancel(true)
                    .build())
                is Delivery.Notice -> nm.notify(nextResultId(), Notification.Builder(context, CHANNEL_RESULT)
                    .setSmallIcon(R.drawable.ic_notification).setContentTitle(tr(R.string.app_name))
                    .setContentText(delivery.text).setContentIntent(openApp(context, delivery.threadId)).setAutoCancel(true).build())
                is Delivery.Ask, is Delivery.Progress -> {}
            }
            return SendOutcome.Delivered
        }

        /** 追问等回答的时限:交给 AgentLoop,通知和卡片上写的分钟数也从这里来。 */
        const val INPUT_TIMEOUT_MS = AgentLoop.DEFAULT_INPUT_TIMEOUT_MS
        const val INPUT_TIMEOUT_MINUTES = INPUT_TIMEOUT_MS / 60_000
        const val ACTION_START = "com.wangzan101.mika.task.START"
        const val ACTION_START_TRIGGER = "com.wangzan101.mika.task.START_TRIGGER"
        const val ACTION_START_MCP = "com.wangzan101.mika.task.START_MCP"
        const val ACTION_CANCEL_MCP = "com.wangzan101.mika.task.CANCEL_MCP"
        const val ACTION_CANCEL = "com.wangzan101.mika.task.CANCEL"
        const val ACTION_RESUME = "com.wangzan101.mika.task.RESUME"
        const val ACTION_TAKE_OVER = "com.wangzan101.mika.task.TAKE_OVER"
        const val ACTION_HAND_BACK = "com.wangzan101.mika.task.HAND_BACK"
        const val ACTION_FINISHED_MYSELF = "com.wangzan101.mika.task.FINISHED_MYSELF"
        const val ACTION_SKIP = "com.wangzan101.mika.task.SKIP"
        /** 网页接管界面按了系统返回键:先放着,回到等待接管。 */
        const val ACTION_PUT_ASIDE = "com.wangzan101.mika.task.PUT_ASIDE"
        /** 接管通知按钮带的「第几次等待」,见 [Takeover.waitKey]。 */
        const val EXTRA_WAIT_KEY = "com.wangzan101.mika.task.WAIT_KEY"
        const val ACTION_ANSWER = "com.wangzan101.mika.task.ANSWER"
        const val ACTION_APPROVE = "com.wangzan101.mika.task.APPROVE"
        const val ACTION_DECLINE = "com.wangzan101.mika.task.DECLINE"
        private const val EXTRA_GOAL = "goal"
        private const val EXTRA_MESSAGE_ID = "message_id"
        private const val EXTRA_QUEUE = "queue"
        private const val EXTRA_CONVERSATION = "conversation"
        private const val EXTRA_TRIGGER_KEY = "trigger_key"
        private const val EXTRA_TRIGGER_ACTION = "trigger_action"
        private const val EXTRA_TRIGGER_THREAD = "trigger_thread"
        private const val EXTRA_MCP_ID = "mcp_id"
        private const val EXTRA_ATTENDANCE = "attendance"
        private const val EXTRA_ANSWER = "answer"
        /** 通知按钮所属任务的编号([TaskRun.key])。 */
        const val EXTRA_RUN = "com.wangzan101.mika.task.RUN"
        /** 等用户回答确认最多这么久,到时按不同意(不在手机旁时任务不会一直挂着,也绝不自动同意)。 */
        const val CONFIRM_TIMEOUT_MS = 15 * 60_000L
        private const val KEY_REPLY = "reply"
        const val CHANNEL_ATTENTION = "task_attention"
        const val CHANNEL_PROGRESS = "task_progress"
        const val CHANNEL_RESULT = "task_result_v2"
        internal const val OLD_CHANNEL_RESULT = "task_result"
        /** 各通知频道建立时的级别;「需要你」(接管、提问、确认)和结果要弹出,进度保持安静。用户之后调低的,App 改不回来。 */
        internal val CHANNEL_IMPORTANCE = mapOf(
            CHANNEL_PROGRESS to NotificationManager.IMPORTANCE_LOW,
            CHANNEL_RESULT to NotificationManager.IMPORTANCE_HIGH,
            CHANNEL_ATTENTION to NotificationManager.IMPORTANCE_HIGH,
        )
        private const val ID_PROGRESS = 1
        internal const val ID_RESULT = 2
        private const val ID_ATTENTION = 3
        /** 第 n 个槽位的任务通知编号与请求码都加 n × 它;第一个槽位与只跑一个任务时一样。 */
        private const val SLOT_STRIDE = 100
        /** 并行时结果通知的编号段,在槽位编号之外;循环使用,最多同时留这么多条。 */
        private const val ID_RESULT_FIRST = 10_000
        private const val RESULT_IDS = 1_000
        private val resultCount = AtomicInteger()
        private val TAKEOVER_QUEUED: String get() = tr(R.string.task_takeover_queued)
        private const val TAG = "MikaTask"
        val TOO_OLD: String get() = tr(R.string.task_too_old)
        internal val PREPARING: String get() = tr(R.string.runtime_preparing)
        val SHELL_DIED: String get() = tr(R.string.task_shell_died)

        /** 循环的状态里哪些可以由发布写出去。 */
        internal fun loopMayPublish(state: TaskState) = state.isActive()

        /** 开始一个任务;已有任务在跑时忽略。[conversationId] 为空表示新开一个对话(编号见 [conversationId])。 */
        /** [messageId] 给了时这是任务进行中插的话,界面按它跟这句话的去向;[queue] 为 true 时别的任务在跑也排上(留下的话)。 */
        fun start(context: Context, goal: String, conversationId: String? = null, messageId: String? = null, queue: Boolean = false) {
            context.startForegroundService(
                Intent(context, TaskService::class.java).setAction(ACTION_START).putExtra(EXTRA_GOAL, goal)
                    .apply { if (conversationId != null) putExtra(EXTRA_CONVERSATION, conversationId) }
                    .apply { if (messageId != null) putExtra(EXTRA_MESSAGE_ID, messageId) }
                    .apply { if (queue) putExtra(EXTRA_QUEUE, true) },
            )
        }

        fun startTrigger(context: Context, goal: String, threadId: String, windowKey: String, conditionGoal: String?) {
            val checking = conditionGoal != null
            val runGoal = if (checking) "$conditionGoal\n只读检查。最后只输出 JSON：{\"met\":true或false,\"evidence\":\"简短依据\"}。无法检查时报告失败，不要猜测。" else goal
            context.startForegroundService(Intent(context, TaskService::class.java).setAction(ACTION_START_TRIGGER)
                .putExtra(EXTRA_GOAL, runGoal).putExtra(EXTRA_CONVERSATION, threadId)
                .putExtra(EXTRA_TRIGGER_KEY, windowKey).putExtra(EXTRA_TRIGGER_THREAD, threadId)
                .apply { if (checking) putExtra(EXTRA_TRIGGER_ACTION, goal) })
        }

        fun startMcp(context: Context, goal: String, threadId: String, externalId: String, attendance: Attendance) {
            val conversation = java.util.UUID.nameUUIDFromBytes("mcp:$threadId:$externalId".toByteArray(Charsets.UTF_8)).toString()
            context.startForegroundService(Intent(context, TaskService::class.java).setAction(ACTION_START_MCP)
                .putExtra(EXTRA_GOAL, goal).putExtra(EXTRA_CONVERSATION, conversation)
                .putExtra(EXTRA_MCP_ID, externalId).putExtra(EXTRA_ATTENDANCE, attendance.name))
        }

        fun cancelMcp(context: Context, externalId: String) {
            val state = (context.applicationContext as com.wangzan101.mika.MikaApp).taskState
            if (state.runs.value.none { it.mcpExternalId == externalId }) return
            context.startService(Intent(context, TaskService::class.java).setAction(ACTION_CANCEL_MCP)
                .putExtra(EXTRA_MCP_ID, externalId))
        }

        /** [runKey] 见 [send];应用内的按钮带着屏上那个对话的任务编号,不会落到别的任务上。 */
        fun cancel(context: Context, runKey: String? = null) = send(context, ACTION_CANCEL, runKey = runKey)

        /** 用户点了「继续」:被挪走的 App 会被挪回 Mika 的屏幕。 */
        fun resume(context: Context, runKey: String? = null) = send(context, ACTION_RESUME, runKey = runKey)

        /** 「我来处理」:把 App 挪到主屏并恢复它的声音。 */
        fun takeOver(context: Context, waitKey: Int? = null, runKey: String? = null) = send(context, ACTION_TAKE_OVER, waitKey, runKey)

        /** 「处理好了」:挪回虚拟屏、重新静音,Agent 先读屏核实再继续。 */
        fun handBack(context: Context, waitKey: Int? = null, runKey: String? = null) = send(context, ACTION_HAND_BACK, waitKey, runKey)

        /** 「我自己做完了」:挪回虚拟屏,Agent 只核实结果并结束。 */
        fun finishedMyself(context: Context, waitKey: Int? = null, runKey: String? = null) =
            send(context, ACTION_FINISHED_MYSELF, waitKey, runKey)

        /** 「跳过」:不处理这一步,Agent 收到「没有发生交回」后自己换办法或如实汇报。 */
        fun skip(context: Context, waitKey: Int? = null, runKey: String? = null) = send(context, ACTION_SKIP, waitKey, runKey)

        fun putAside(context: Context, waitKey: Int? = null, runKey: String? = null) = send(context, ACTION_PUT_ASIDE, waitKey, runKey)

        /** 回答 Agent 的提问(ask_user)。 */
        fun answer(context: Context, text: String, runKey: String? = null) {
            if (!stateFor(context, runKey).isActive()) return
            context.startService(
                Intent(context, TaskService::class.java).setAction(ACTION_ANSWER).putExtra(EXTRA_ANSWER, text)
                    .apply { if (runKey != null) putExtra(EXTRA_RUN, runKey) },
            )
        }

        /** 同意编号为 [key] 的那次申报([ConfirmationNotifier.requestKey]);已经换成别的申报时不算数。 */
        fun approve(context: Context, key: Int, runKey: String? = null) = confirm(context, ACTION_APPROVE, key, runKey)

        /** 不同意:Agent 不再尝试这个操作,汇报现状后结束。 */
        fun decline(context: Context, key: Int, runKey: String? = null) = confirm(context, ACTION_DECLINE, key, runKey)

        private fun confirm(context: Context, action: String, key: Int, runKey: String?) {
            if (!stateFor(context, runKey).isActive()) return
            context.startService(
                Intent(context, TaskService::class.java).setAction(action).putExtra(ConfirmationNotifier.EXTRA_REQUEST, key)
                    .apply { if (runKey != null) putExtra(EXTRA_RUN, runKey) },
            )
        }

        /** 该任务此刻的状态;任务已不在时为 Idle(动作作废)。[runKey] 为空时是显示中的任务。 */
        private fun stateFor(context: Context, runKey: String?): TaskState {
            val state = (context.applicationContext as com.wangzan101.mika.MikaApp).taskState
            return if (runKey == null) state.state.value else state.runs.value.firstOrNull { it.key == runKey }?.state?.value ?: TaskState.Idle
        }

        /** [runKey] 为空时作用于显示中的任务,否则只作用于该任务。 */
        private fun send(context: Context, action: String, waitKey: Int? = null, runKey: String? = null) {
            if (!stateFor(context, runKey).isActive()) return
            context.startService(
                Intent(context, TaskService::class.java).setAction(action).apply {
                    if (waitKey != null) putExtra(EXTRA_WAIT_KEY, waitKey)
                    if (runKey != null) putExtra(EXTRA_RUN, runKey)
                },
            )
        }

        internal fun TaskState.isActive() = when (this) {
            TaskState.Idle, is TaskState.Succeeded, is TaskState.Failed, TaskState.Cancelled -> false
            else -> true
        }

        /** 通知的标题和正文;[appLabel] 把包名换成 App 名。 */
        internal fun describe(state: TaskState, appLabel: (String) -> String = { it }): Pair<String, String> = when (state) {
            TaskState.Idle -> tr(R.string.app_name) to ""
            is TaskState.Running -> tr(R.string.task_running) to (state.progress?.let(StatusCopy::progress) ?: when {
                state.step == 0 -> state.note
                else -> tr(R.string.task_step_note, state.step, state.note).trimEnd(':', ' ')
            })
            is TaskState.Held -> if (state.reason == TaskGate.LOCKED) tr(R.string.task_wait_unlock) to tr(R.string.task_wait_unlock_body) else tr(R.string.chat_paused) to state.reason.why
            is TaskState.WaitingTakeover -> tr(R.string.takeover_title) to state.message
            is TaskState.WaitingInput -> tr(R.string.input_title) to state.prompt
            is TaskState.WaitingConfirmation -> ConfirmationCopy.title(state.request) to ConfirmationCopy.body(state.request, appLabel(state.request.app))
            is TaskState.Succeeded ->
                if (!state.doubtful) tr(R.string.task_done) to state.answer
                else tr(R.string.chat_unverified) to (state.note?.let { tr(R.string.task_reason, it) + "\n" }.orEmpty() + state.answer)
            is TaskState.Failed -> tr(R.string.task_not_done) to state.reason
            TaskState.Cancelled -> tr(R.string.task_stopped) to tr(R.string.task_stopped_body)
        }
    }
}
