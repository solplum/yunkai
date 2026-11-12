package com.wangzan101.mika.takeover

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.tool.HandBack
import com.wangzan101.mika.core.tool.HandBackOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 接管要用到的设备能力与 Agent 动作;由任务服务按当前任务实现。任务编号无效时为 -1。 */
interface TakeoverPort {
    /** Agent 虚拟屏最上面的任务。 */
    suspend fun agentTopTask(): Int
    /** launch_app 打开的那个任务与包。 */
    suspend fun targetTask(): Int
    fun targetPackage(): String?
    suspend fun packageOf(taskId: Int): String?
    /** 任务所在的屏;任务已经不在了(页面办完自己关掉了)为 -1。 */
    suspend fun displayOf(taskId: Int): Int
    suspend fun moveToMain(taskId: Int): Boolean
    suspend fun moveBack(taskId: Int): Boolean
    /** 开或关该 App 的声音。 */
    fun setSound(packageName: String, on: Boolean)
    /** 开始数用户在主屏上对该包的操作;App 交到用户手上时调用。 */
    fun watchInput(packageName: String)
    /** 此刻数到的操作和所在页面;没在数时为 null。 */
    suspend fun handoverActivity(): HandoverActivity?
    fun stopWatching()
    /** 把接管的结局交还 Agent 循环。 */
    suspend fun resume(result: HandBack)
    fun cancel()

    /**
     * 当前界面是网页时,把当前标签(同一个页面)拿到主屏给用户:成功 true,失败 false;不是网页界面为 null,走挪任务的老路。
     * [key] 是这次等待的编号,网页接管界面上的按钮带着它。
     */
    suspend fun showWeb(reason: String, key: Int): Boolean? = null

    /** 把网页标签放回后台,返回接管期间的操作次数和页面标题;[loggedIn] 时把当前网站记为「用户登录过」。 */
    suspend fun returnWeb(loggedIn: Boolean): HandoverActivity? = null

    /** 主屏上的网页界面此刻被占着(设置页的网站登录开着):[showWeb] 失败时据此告诉用户「先把那边弄完」而不是「挪动失败」。 */
    fun webBusy(): Boolean = false
}

/**
 * 主屏一次只交一个 App 给用户:几个任务同时请求接管时按先后排队,只有排在最前的能「我来处理」。
 * 排队的是各任务的 [Takeover];[changes] 每次进出队都变,任务服务据此重画通知。
 * 排在后面的不计等待时限,排到最前时才开始计时(见 [Takeover.reachedFront])。
 */
class TakeoverQueue {
    private val waiting = mutableListOf<Takeover>()
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    @Synchronized fun join(owner: Takeover) {
        if (waiting.none { it === owner }) { waiting += owner; _changes.value++ }
    }

    fun leave(owner: Takeover) {
        val front = synchronized(this) {
            val wasFirst = waiting.firstOrNull() === owner
            if (!waiting.removeAll { it === owner }) return
            _changes.value++
            waiting.firstOrNull()?.takeIf { wasFirst }
        }
        front?.reachedFront()
    }

    /** 不在队里的(没在等接管)也算最前:它不会挡别人,也不被别人挡。 */
    @Synchronized fun isFirst(owner: Any): Boolean = waiting.indexOfFirst { it === owner } <= 0

    /** [owner] 前面还有几个任务在等用户处理;不在队里为 -1。 */
    @Synchronized fun ahead(owner: Any): Int = waiting.indexOfFirst { it === owner }

    /** 排在 [owner] 后面还有几个任务等用户处理。 */
    @Synchronized fun behind(owner: Any): Int {
        val i = waiting.indexOfFirst { it === owner }
        return if (i < 0) 0 else waiting.size - 1 - i
    }
}

/**
 * 接管的状态机:Agent 请求接管 → 等待 →「我来处理」把虚拟屏最上面的任务挪到主屏 →
 * 「处理好了」/「我自己做完了」挪回并交还 Agent;「跳过」不处理直接交还(App 在主屏就先挪回);
 * 任何时候都可「取消任务」,取消时 App 留在原处。
 * 没人点「我来处理」时等 [TAKEOVER_WAIT_TIMEOUT_MS] 后交还超时,App 不挪动;接手之后不再计时。
 * 计时跑在 [scope] 上,[now] 量接手时长;测试换成虚拟时间。
 * 给了 [queue] 时和其他任务的接管排队,排在后面的不能「我来处理」。
 */
class Takeover(
    private val port: TakeoverPort,
    private val scope: CoroutineScope,
    private val queue: TakeoverQueue? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class Stage { None, Waiting, HandedOver }
    enum class Problem { MoveToMainFailed, MoveBackFailed, WebBusy }

    private val _stage = MutableStateFlow(Stage.None)
    val stage: StateFlow<Stage> = _stage.asStateFlow()
    private val _problem = MutableStateFlow<Problem?>(null)
    /** 最近一次挪动失败的原因,下一次成功后清空。 */
    val problem: StateFlow<Problem?> = _problem.asStateFlow()

    /** App 在用户手上(主屏):这时它不在虚拟屏上是有意的,不算「被用户拿走」。 */
    val handedOver: Boolean get() = _stage.value == Stage.HandedOver

    private val mutex = Mutex()
    /** 当前这次等待,和已经交还的那次;同一个状态在循环往下走之前被再次发布时不能重新进入等待。 */
    @Volatile private var current: TaskState.WaitingTakeover? = null
    @Volatile private var answered: TaskState.WaitingTakeover? = null
    /** 第几次等待;通知按钮带着它,点到的若是上一次等待遗留的就不算数。 */
    @Volatile var waitKey = 0
        private set
    /** 交到用户手上的是网页标签(不是挪到主屏的任务)。 */
    private var handedWeb = false
    /** 挪到主屏的任务和它的包。 */
    private var handed = -1
    private var handedPackage: String? = null
    private var handedAt = 0L
    private var timer: Job? = null

    fun onState(state: TaskState) {
        when (state) {
            is TaskState.WaitingTakeover -> if (state !== answered && _stage.value == Stage.None) {
                current = state
                queue?.join(this)
                waitKey++
                _problem.value = null
                _stage.value = Stage.Waiting
                // 排在别的任务的接管后面时先不计时:用户还没空看它
                if (queue == null || queue.isFirst(this)) armTimer(state)
            }
            // 等待接管时循环停在那里,不会发布别的状态;发布了别的就是交还后往下走了,或任务结束了
            else -> {
                stopTimer()
                if (_stage.value == Stage.HandedOver) port.stopWatching()
                current = null
                answered = null
                handedWeb = false
                queue?.leave(this)
                handed = -1
                handedPackage = null
                _stage.value = Stage.None
                _problem.value = null
            }
        }
    }

    /** 「我来处理」。 */
    suspend fun handOver(key: Int? = null): Boolean = mutex.withLock {
        if (_stage.value != Stage.Waiting || !keyMatches(key)) return false
        if (queue != null && !queue.isFirst(this)) return false
        // 先标记再挪:轮询「被挪走」的监视器可能在挪动刚完成时就看到
        _stage.value = Stage.HandedOver
        // 网页界面:把同一个标签拿到眼前,不挪任务、不动声音
        when (port.showWeb(current?.reason ?: "other", waitKey)) {
            true -> {
                handedWeb = true
                stopTimer()
                handedAt = now()
                _problem.value = null
                return true
            }
            false -> {
                _stage.value = Stage.Waiting
                _problem.value = if (port.webBusy()) Problem.WebBusy else Problem.MoveToMainFailed
                return false
            }
            null -> Unit
        }
        // 挪虚拟屏最上面的任务:App 常把登录、付款页开在另一个任务里
        val id = port.agentTopTask().takeIf { it >= 0 } ?: port.targetTask()
        if (id >= 0) {
            if (!port.moveToMain(id)) {
                _stage.value = Stage.Waiting
                _problem.value = Problem.MoveToMainFailed
                return false
            }
            handed = id
            handedPackage = port.packageOf(id) ?: port.targetPackage()
        }
        stopTimer()
        handedAt = now()
        _problem.value = null
        handedPackage?.let {
            port.setSound(it, true)
            port.watchInput(it)
        }
        true
    }

    /** 「处理好了」:Agent 先读屏核实再继续。 */
    suspend fun handBack(key: Int? = null): Boolean = giveBack(HandBack(HandBackOutcome.complete), key)

    /** 「我自己做完了」:Agent 不再操作,只核实结果并结束。 */
    suspend fun finishedMyself(key: Int? = null): Boolean = giveBack(HandBack(HandBackOutcome.complete, userFinished = true), key)

    /** 「跳过」:不处理,告诉 Agent 没有发生交回;App 若在主屏先挪回,挪不回就保持原状。 */
    suspend fun skip(key: Int? = null): Boolean = giveBack(HandBack(HandBackOutcome.skipped), key)

    /**
     * 网页接管界面上按了系统返回键 =「先放着」:标签放回后台,任务回到等待接管(通知还在,可以再点「我来处理」),
     * 等待照常计时。只在已经接手时有效。
     */
    suspend fun putAside(key: Int? = null): Boolean = mutex.withLock {
        if (_stage.value != Stage.HandedOver || !handedWeb || !keyMatches(key)) return false
        port.returnWeb(loggedIn = false)
        handedWeb = false
        _stage.value = Stage.Waiting
        current?.let(::armTimer)
        true
    }

    /** 「取消任务」:App 若在主屏就留在主屏,不从用户眼前抽走;网页标签则放回后台(任务的标签随后关掉)。 */
    suspend fun cancel() = mutex.withLock {
        stopTimer()
        if (handedWeb) {
            port.returnWeb(loggedIn = false)
            handedWeb = false
        }
        if (_stage.value == Stage.HandedOver) port.stopWatching()
        handed = -1
        handedPackage = null
        _stage.value = Stage.None
        _problem.value = null
        queue?.leave(this)
        port.cancel()
    }

    /** 没带 key(应用内按钮)总是认;带了的必须是当前这次等待。 */
    private fun keyMatches(key: Int?) = key == null || key == waitKey

    private suspend fun giveBack(result: HandBack, key: Int?): Boolean = mutex.withLock {
        if (!keyMatches(key)) false else giveBackLocked(result)
    }

    private suspend fun giveBackLocked(given: HandBack): Boolean {
        val from = _stage.value
        if (from == Stage.None) return false
        var result = given
        if (from == Stage.HandedOver && handedWeb) {
            // 交回(完成或我自己做完了)都算用户在这个网站上登录过;跳过则不算
            val done = result.outcome == HandBackOutcome.complete
            val activity = port.returnWeb(loggedIn = done)
            if (done) result = result.copy(durationMs = now() - handedAt, inputCount = activity?.inputCount, stoppedAt = activity?.stoppedAt)
            handedWeb = false
        } else if (from == Stage.HandedOver) {
            // 停在哪一页要在挪回之前看:挪回后它就不在主屏了
            if (result.outcome == HandBackOutcome.complete) {
                val activity = port.handoverActivity()
                result = result.copy(durationMs = now() - handedAt, inputCount = activity?.inputCount, stoppedAt = activity?.stoppedAt)
            }
            // 交出去的任务可能已经办完自己关掉了(不在了就不用挪);launch_app 的任务若还在主屏也一并挪回
            val target = port.targetTask()
            for (id in listOf(handed, target).filter { it >= 0 }.distinct()) {
                if (port.displayOf(id) == MAIN_DISPLAY && !port.moveBack(id)) {
                    _problem.value = Problem.MoveBackFailed
                    return false
                }
            }
            port.stopWatching()
            setOfNotNull(handedPackage, port.targetPackage()).forEach { port.setSound(it, false) }
            handed = -1
            handedPackage = null
        }
        stopTimer()
        _problem.value = null
        _stage.value = Stage.None
        answered = current
        queue?.leave(this)
        port.resume(result)
        return true
    }

    /** 前面的接管处理完,轮到这一个:从现在起计等待时限。 */
    internal fun reachedFront() {
        val waiting = current
        if (_stage.value == Stage.Waiting && waiting != null && timer == null) armTimer(waiting)
    }

    private fun armTimer(state: TaskState.WaitingTakeover) {
        timer?.cancel()
        timer = scope.launch {
            delay(TAKEOVER_WAIT_TIMEOUT_MS)
            mutex.withLock {
                // 挪到主屏失败退回等待时也照常超时;已经接手或换成了下一次等待就不算
                if (_stage.value == Stage.Waiting && current === state) {
                    timer = null // 交还时不能把正在交还的自己取消掉
                    giveBackLocked(HandBack(HandBackOutcome.timeout))
                }
            }
        }
    }

    private fun stopTimer() {
        timer?.cancel()
        timer = null
    }

    companion object {
        private const val MAIN_DISPLAY = 0
        /** 等用户点「我来处理」的时限;接手之后不计时。 */
        const val TAKEOVER_WAIT_TIMEOUT_MS = 15 * 60_000L
    }
}
