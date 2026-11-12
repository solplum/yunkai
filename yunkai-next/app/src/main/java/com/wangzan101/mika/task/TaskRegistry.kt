package com.wangzan101.mika.task

import com.wangzan101.mika.core.run.MemoryRunStore
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunPhase
import com.wangzan101.mika.core.run.RunStore
import com.wangzan101.mika.core.run.StoredRun

sealed interface StartResult<out R> {
    data class Started<R>(val run: R) : StartResult<R>
    /** 超出并行上限,排在队里第 [position] 个。 */
    data class Queued<R>(val run: R, val position: Int) : StartResult<R>
    /** 这个对话已经有任务(在跑或在排队):照 M1 规则不收,由对话自己排队。 */
    data object AlreadyRunningInConversation : StartResult<Nothing>
}

/**
 * 按对话索引的运行中任务、并行上限与跨任务的全局锁。上限为 1 时(开关关着)就是 M1 的一次一个。
 * [create] 在登记时建任务对象;R 在产品里是 [TaskRun],测试里换成普通对象。
 */
class TaskCenter<R : Any>(
    private val limit: () -> ParallelLimit,
    private val conversationOf: (R) -> String,
    /** 任务的唯一编号(通知按钮带着它);同一对话前后两个任务的编号不同。 */
    private val keyOf: (R) -> String,
    val store: RunStore = MemoryRunStore(),
    val resources: ResourceScheduler = ResourceScheduler(),
    private val create: (conversationId: String, goal: String) -> R,
) {
    private val running = mutableListOf<R>()
    private val queued = mutableListOf<R>()
    private val deferred = ArrayDeque<Pair<String, String>>()
    /** 迁移中的调用方继续从这里拿资源，实际所有权归 [resources]。 */
    val clipLock get() = resources.clipboard
    val takeoverQueue get() = resources.takeoverQueue

    @Synchronized
    fun tryStart(conversationId: String, goal: String, queueInConversation: Boolean = false): StartResult<R> {
        val sameConversation = byConversation(conversationId) != null
        if (sameConversation && !queueInConversation) return StartResult.AlreadyRunningInConversation
        val run = create(conversationId, goal)
        return if (!sameConversation && running.size < limit().max) {
            store.save(StoredRun(RunId(keyOf(run)), conversationId, goal, RunPhase.Preparing))
            running += run
            StartResult.Started(run)
        } else {
            store.save(StoredRun(RunId(keyOf(run)), conversationId, goal, RunPhase.Queued))
            queued += run
            StartResult.Queued(run, queued.size)
        }
    }

    @Synchronized fun runs(): List<R> = running + queued
    @Synchronized fun running(): List<R> = running.toList()
    @Synchronized fun queued(): List<R> = queued.toList()
    @Synchronized fun isQueued(run: R): Boolean = queued.any { it === run }
    @Synchronized fun byConversation(id: String): R? = runs().firstOrNull { conversationOf(it) == id }
    @Synchronized fun byKey(key: String): R? = runs().firstOrNull { keyOf(it) == key }

    /**
     * 任务 [owner] 要在自己的屏上开 [pkg]:没人占、自己占着、或占着的任务已经不在用它([stillUsed] 为假,
     * 比如 App 自己关掉了)时占下并返回 true;否则另一个任务正在用,返回 false。查与占在同一把锁里,两个任务同时开同一个 App 只有一个能成。
     */
    @Synchronized
    fun reserve(pkg: String, owner: String, stillUsed: () -> Boolean): Boolean {
        return resources.reserveApp(pkg, owner, { byKey(it) != null }, stillUsed)
    }

    /** 还在排队就移出队伍并返回 true(调用方记为已取消);已经开跑返回 false,由任务自己取消。 */
    @Synchronized
    fun cancelIfQueued(run: R): Boolean {
        if (queued.none { it === run }) return false
        store.get(RunId(keyOf(run)))?.let { store.save(it.copy(phase = RunPhase.Ended)) }
        return queued.removeAll { it === run }
    }

    /** 任务结束(或排队时被取消);返回因此从队里放出来、该开始跑的任务。 */
    @Synchronized
    fun onEnded(run: R): List<R> {
        if (queued.removeAll { it === run }) return emptyList()
        running.removeAll { it === run }
        resources.releaseOwner(keyOf(run))
        val started = mutableListOf<R>()
        while (queued.isNotEmpty() && running.size < limit().max) {
            val index = queued.indexOfFirst { candidate ->
                running.none { conversationOf(it) == conversationOf(candidate) } &&
                    queued.takeWhile { it !== candidate }.none { conversationOf(it) == conversationOf(candidate) }
            }
            if (index < 0) break
            val next = queued.removeAt(index)
            running += next
            store.get(RunId(keyOf(next)))?.let { store.save(it.copy(phase = RunPhase.Preparing)) }
            started += next
        }
        return started
    }

    @Synchronized fun markRunning(run: R) {
        store.get(RunId(keyOf(run)))?.let { store.save(it.copy(phase = RunPhase.Running, roundInProgress = true)) }
    }

    @Synchronized fun markResult(run: R, reply: String?, deliveryId: String? = null, successful: Boolean? = null) {
        store.get(RunId(keyOf(run)))?.let { store.save(it.copy(phase = RunPhase.Aftercare, reply = reply,
            deliveryId = deliveryId, runSucceeded = successful)) }
    }

    @Synchronized fun markDelivered(run: R) {
        store.get(RunId(keyOf(run)))?.let { store.save(it.copy(phase = RunPhase.Ended, roundInProgress = false)) }
    }

    /**
     * 收尾边界收到、已经不能注入原 Run 的用户原话:这个对话此刻没有任务就返回 true(调用方当场开始);
     * 还有就留下返回 false,等它收尾后由 [takeReady] 交出。查与留在同一把锁里,和 [onEnded] 不会错开。
     */
    @Synchronized fun deferOrStart(threadId: String, text: String): Boolean {
        if (byConversation(threadId) == null) return true
        deferred += threadId to text
        return false
    }

    /** 留下的话里,所在对话已经没有任务的那些(按到的先后),取走。 */
    @Synchronized fun takeReady(): List<Pair<String, String>> {
        val ready = deferred.filter { (threadId, _) -> byConversation(threadId) == null }
        deferred.removeAll { (threadId, _) -> byConversation(threadId) == null }
        return ready
    }
}

/** 迁移期旧名称，现有调用方和测试仍可逐步转到 TaskCenter。 */
typealias TaskRegistry<R> = TaskCenter<R>
