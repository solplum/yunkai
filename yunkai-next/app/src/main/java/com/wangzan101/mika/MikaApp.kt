// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
// Modified for Yunkai Next isolated prototype (2026-10-30); see repository-root UPSTREAM.md.
package com.wangzan101.mika

import com.wangzan101.mika.i18n.ResourceTexts
import com.wangzan101.mika.i18n.UiText
import com.wangzan101.mika.i18n.AppLanguage
import android.app.Application
import android.net.Uri
import android.util.Log
import com.wangzan101.mika.chat.ConversationDeleter
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.cap.sandbox.AndroidSandbox
import com.wangzan101.mika.cap.sandbox.Attachments
import com.wangzan101.mika.ui.AttachFiles
import com.wangzan101.mika.cap.sandbox.ShareExporter
import com.wangzan101.mika.cap.web.WebFiles
import com.wangzan101.mika.cap.sandbox.RuntimePreparer
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.SandboxState
import com.wangzan101.mika.cap.sandbox.SkillsSync
import com.wangzan101.mika.capsule.AppForeground
import com.wangzan101.mika.capsule.CapsuleController
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.memory.AppLearningActions
import com.wangzan101.mika.memory.MemoryRuntime
import com.wangzan101.mika.settings.ApprovalControl
import com.wangzan101.mika.settings.ApprovalStore
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.replay.FileRouteStore
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.core.run.Outbox
import com.wangzan101.mika.core.run.RunStore
import com.wangzan101.mika.core.usage.UsageLedger
import com.wangzan101.mika.task.FileOutboxStore
import com.wangzan101.mika.task.FileRunStateStore
import com.wangzan101.mika.task.LocalChannel
import com.wangzan101.mika.task.RunRecovery
import com.wangzan101.mika.task.TaskRecovery
import com.wangzan101.mika.task.TriggerOutcomes
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.task.TaskCenterState
import com.wangzan101.mika.ui.ChatViewModel
import com.wangzan101.mika.ui.TaskActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wangzan101.mika.cap.web.cdp.WebDevtools
import com.wangzan101.mika.cap.web.WebOwner
import com.wangzan101.mika.cap.web.SiteRegistry
import com.wangzan101.mika.cap.sandbox.SandboxOwner
import com.wangzan101.mika.core.workspace.SandboxTree
import com.wangzan101.mika.task.TaskEnvironment
import com.wangzan101.mika.task.ResourceScheduler
import com.wangzan101.mika.task.McpTaskHub
import com.wangzan101.mika.task.FileChildRunStore
import com.wangzan101.mika.task.TriggerRuntime

/**
 * App 容器:进程里共用的服务都在这里建一份,经各自的 Owner 接口交给安卓入口(界面、服务、广播、provider),
 * 其余代码经构造参数拿到。助手进程(HelperMain、ShellService)没有这个对象,不用这里的任何服务。
 */
class MikaApp : Application(), WebOwner, SandboxOwner, TaskEnvironment.Owner, PhoneServices.Owner,
    MemoryRuntime.Owner, ProviderStore.Owner, FileRouteStore.Owner, TaskRecovery.Owner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 手机能力的服务:内置助手、共用的 shell 连接、配对、「去设置」提示。 */
    override val phone: PhoneServices by lazy { PhoneServices(this) }

    /** 网页标签的 DevTools 会话入口:整个进程一份;连不上时经内置助手代连。 */
    override val webDevtools: WebDevtools by lazy { WebDevtools(phone.hub::openDevtoolsSocket) }

    /** 各任务的标签池共用的标签总数上限。 */
    val resources = ResourceScheduler()
    val mcpTasks by lazy { McpTaskHub(this) }
    val childRuns by lazy { FileChildRunStore(java.io.File(filesDir, "children-v3.json")) }
    override val tabBudget get() = resources.tabBudget

    /** 记忆、个人技能、开关与结果卡片提示。 */
    override val memory: MemoryRuntime by lazy { MemoryRuntime(this) }

    /** 本机的 token 用量账本:经模型配置建出的每个模型的每次调用都记在这里。 */
    val usage: UsageLedger by lazy { UsageLedger(java.io.File(filesDir, "usage-v1.json")) }

    /** 模型配置与 Key;建出的模型都套上计量,记到 [usage]。 */
    override val providers: ProviderStore by lazy { ProviderStore.create(this, usage, "Mika/${BuildConfig.VERSION_NAME}") }

    /** 经验路线。 */
    override val routes: FileRouteStore by lazy { FileRouteStore.create(this) }

    /** 异常退出后留下的系统改动的恢复;在跑的任务登记在这里。 */
    override val taskRecovery: TaskRecovery by lazy { TaskRecovery(this, phone.sharedShell) }

    /** 运行账本与送达账本:整个进程一份,任务服务和启动时的恢复共用(同一个文件不能有两份实例)。 */
    val runStore: RunStore by lazy { FileRunStateStore(java.io.File(filesDir, "runs-v3.json")) }
    val outbox: Outbox by lazy { Outbox(FileOutboxStore(java.io.File(filesDir, "outbox-v3.json"))) }

    /** 没结束的运行的善后(标中断、补送、告知):进程里只做一次,App 启动和任务服务启动都调它。 */
    val runRecovery: RunRecovery by lazy {
        val app = this
        RunRecovery(runStore, outbox, LocalChannel { _, delivery ->
            TaskService.ensureChannels(app)
            TaskService.notifyDelivery(app, delivery, TaskService.nextResultId())
        }, object : TriggerOutcomes {
            override fun interrupted(key: String, reason: String) = TriggerRuntime.interrupted(app, key, reason)
            override fun delivered(key: String) = TriggerRuntime.delivered(app, key)
            override fun actionFailed(key: String, reason: String) = TriggerRuntime.actionFailed(app, key, reason)
        }, conversations, scope)
    }

    /** 站点登记表(登录过的网站等):设置页和标签池共用。 */
    override val webSites: SiteRegistry by lazy { SiteRegistry(java.io.File(filesDir, "web")) }

    /** `files/sandbox/` 的树锁:安装、重置、命令、下载、附件都在它的许可里写。 */
    override val sandboxTree = SandboxTree()

    /** 任务从 App 拿的模型来源、评测钩子、屏幕画面的开关。 */
    override val tasks = TaskEnvironment()

    /** 沙箱状态:安装服务更新的,设置页与 Agent 看到的是同一份。 */
    override val sandboxState: SandboxState by lazy { SandboxState(this) }

    /** 对话记录:整个进程一份,任务服务、聊天界面、结果卡片的按钮共用。 */
    val conversations: ConversationStore by lazy { ConversationStore(this) }

    /** 删对话(侧栏长按):连带它的任务、定时任务和工作区。 */
    val conversationDeleter: ConversationDeleter by lazy {
        val app = this
        ConversationDeleter(
            conversations, taskState.activeConversations,
            cancelTasks = { id -> taskState.runs.value.filter { it.conversationId == id }.forEach { TaskService.cancel(app, it.key) } },
            schedulesOf = { id -> TriggerRuntime.list(app).filter { it.threadId == id }.map { it.id } },
            removeSchedule = { TriggerRuntime.remove(app, it) },
            deleteWorkspace = { id ->
                // 工作区里有附件、用户发来的屏幕、任务写的文件;沙箱里的命令碰得到这里,按句柄删、不跟进链接,和重置互斥
                val dirs = SandboxDirs(app)
                dirs.tree.shared { withContext(Dispatchers.IO) { dirs.gate.deleteTree(dirs.work, dirs.workspace(id).name) } }
            },
        )
    }

    /** 用户发来的屏幕(跟着各自的对话存在工作区里)。 */
    val userScreens: com.wangzan101.mika.chat.UserScreenFiles by lazy { com.wangzan101.mika.chat.UserScreenFiles(this) }

    /** 全局的询问 / 自主:整个进程一份,设置页和各任务共用。 */
    val approvals: ApprovalStore by lazy { ApprovalStore(java.io.File(filesDir, "approvals.json")) }

    /** 全局的询问 / 自主和各任务自己的那一份;开发者选项里的「自主模式」经它改。 */
    val approvalControl: ApprovalControl by lazy { ApprovalControl(approvals) }

    /** 各 Run 的队列与展示状态跟 App 进程走，Service 重建时继续使用同一份。 */
    val taskState = TaskCenterState()

    /** Mika 自己的界面此刻看不看得见。 */
    val foreground = AppForeground()

    /** 检查更新:查到的新版本跟进程走,侧栏、设置首页、关于页的红点看同一份。 */
    val updates: com.wangzan101.mika.about.UpdateChecker by lazy {
        com.wangzan101.mika.about.UpdateChecker(java.io.File(filesDir, "update-v1.json"), BuildConfig.VERSION_NAME, enabled = false)
    }

    /** 浮在别的 App 上的悬浮胶囊。 */
    val capsule: CapsuleController by lazy { CapsuleController(this, taskState, foreground, resources.takeoverQueue) }

    /** 对话页的状态跟进程走:界面关了再打开,正在跑的任务的步骤还在。 */
    val chat: ChatViewModel by lazy {
        val app = this
        ChatViewModel(
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            taskState.state,
            taskState.takeoverStage,
            object : TaskActions {
                override fun start(goal: String, conversationId: String) = TaskService.start(app, goal, conversationId)
                override fun steer(conversationId: String, messageId: String, text: String) = TaskService.start(app, text, conversationId, messageId)
                override fun cancel() = TaskService.cancel(app)
                override fun resume() = TaskService.resume(app)
                override fun takeOver() = TaskService.takeOver(app)
                override fun handBack() = TaskService.handBack(app)
                override fun finishedMyself() = TaskService.finishedMyself(app)
                override fun skip() = TaskService.skip(app)
                override fun answer(text: String) = TaskService.answer(app, text)
                override fun approve(key: Int) = TaskService.approve(app, key)
                override fun decline(key: Int) = TaskService.decline(app, key)
                override fun show(conversationId: String) = taskState.show(conversationId)

                // 按下那一刻就换成该对话任务的编号:之后切了对话、或那个任务换了,动作也不会落到别的任务上
                private fun key(conversationId: String) = taskState.run(conversationId)?.key
                override fun cancelIn(conversationId: String) { key(conversationId)?.let { TaskService.cancel(app, it) } }
                override fun resumeIn(conversationId: String) { key(conversationId)?.let { TaskService.resume(app, it) } }
                override fun takeOverIn(conversationId: String) { key(conversationId)?.let { TaskService.takeOver(app, runKey = it) } }
                override fun handBackIn(conversationId: String) { key(conversationId)?.let { TaskService.handBack(app, runKey = it) } }
                override fun finishedMyselfIn(conversationId: String) { key(conversationId)?.let { TaskService.finishedMyself(app, runKey = it) } }
                override fun skipIn(conversationId: String) { key(conversationId)?.let { TaskService.skip(app, runKey = it) } }
                override fun answerIn(conversationId: String, text: String) { key(conversationId)?.let { TaskService.answer(app, text, it) } }
                override fun approveIn(conversationId: String, key: Int) { key(conversationId)?.let { TaskService.approve(app, key, it) } }
                override fun declineIn(conversationId: String, key: Int) { key(conversationId)?.let { TaskService.decline(app, key, it) } }
            },
            conversations,
            taskState.busy,
            appLabel = { ConfirmationNotifier.appLabel(app, it) },
            // 手机的虚拟屏建好了,或在用内置浏览器:画中画都有画面可看
            screenOpen = combine(tasks.agentDisplayId, tasks.webScreenOpen) { d, w -> d >= 0 || w }
                .stateIn(scope, SharingStarted.Eagerly, tasks.agentDisplayId.value >= 0 || tasks.webScreenOpen.value),
            endedRunId = taskState.endedRunId,
            endedRunIds = taskState.endedRunIds,
            parallelFlag = { FeatureFlags.get(app).speedFlags().parallelTasks },
            shownTask = taskState.shownTask,
            activeConversations = taskState.activeConversations,
            runGoal = { taskState.run(it)?.goal },
            runState = { taskState.run(it)?.state },
            runNotes = memory.notes,
            learning = AppLearningActions(app, memory),
            memoryItems = memory.items(),
            // 附件进对话的工作区,不需要运行环境已准备好:设备支持就给附件按钮
            sandboxReady = SandboxState.get(app).status.map { it !is SandboxStatus.Unsupported }
                .stateIn(scope, SharingStarted.Eagerly, SandboxState.get(app).status.value !is SandboxStatus.Unsupported),
            sharedFiles = taskState.sharedFiles,
            activities = taskState.activities,
            steers = taskState.steers,
            summarizing = taskState.summarizing,
            importAttachments = { id, uris ->
                val sandbox = AndroidSandbox(app, SandboxState.get(app))
                val dirs = SandboxDirs(app)
                Attachments.importInto(app, uris.map(Uri::parse), dirs.workspace(id), dirs.gate, dirs.tree, sandbox::freeBytes) { sandbox.workspaceBytes(id) }
            },
            describeAttachments = { uris -> AttachFiles.describe(app, uris) },
            deletionImpact = conversationDeleter::impact,
            removeConversation = conversationDeleter::delete,
        )
    }

    override fun onCreate() {
        super.onCreate()
        // 界面以外的文字(通知、服务、悬浮胶囊)按 App 语言取
        AppLanguage.applyDefaultLocale(this)
        UiText.source = ResourceTexts { AppLanguage.resources(this) }
        registerActivityLifecycleCallbacks(foreground)
        capsule.start()
        // 内置助手的真实现,并开始看着助手(Mika 进程起来后,还活着的助手会自己交接 Binder)
        phone.shellManager.start()
        // 连接一可用就补上默认打开的几项能力
        phone.recovery.start()
        // 上次异常退出时留下的改动(App 被静音)在这里恢复
        scope.launch { taskRecovery.recover() }
        // 上次进程被杀时没结束的运行:现在就标为中断并告知,不等任务服务起来
        runRecovery.recover().let { if (it.isNotEmpty()) Log.w("MikaTask", "${it.size} runs interrupted by process restart") }
        scope.launch(Dispatchers.IO) { runCatching { TriggerRuntime.rearm(this@MikaApp) } }
        // 上次导出写到一半被杀留下的空壳条目
        scope.launch { runCatching { ShareExporter(this@MikaApp).cleanPending() } }
        // 上次进程留下的网页上传副本
        scope.launch { runCatching { WebFiles.clearStale(this@MikaApp, SandboxDirs(this@MikaApp).gate) } }
        // 运行环境没准备好(首次启动、Mika 升级后)就在后台准备,不联网、对用户不可见
        RuntimePreparer.ensure(this)
        // 沙箱就绪(启动时已就绪,或刚装好)就把技能目录核对一次:App 更新带来的脚本马上能用
        scope.launch {
            SandboxState.get(this@MikaApp).status.first { it is SandboxStatus.Ready }
            val state = SandboxState.get(this@MikaApp)
            runCatching { SkillsSync.sync(SandboxDirs(this@MikaApp)) { state.status.value is SandboxStatus.Ready } }
        }
    }
}
