package com.wangzan101.mika.task

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.run.Channel
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.DeliveryState
import com.wangzan101.mika.core.run.Outbox
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunPhase
import com.wangzan101.mika.core.run.RunStore
import com.wangzan101.mika.core.run.StoredRun
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/** 恢复时要告诉定时触发的几件事;产品里转给 [TriggerRuntime]。 */
interface TriggerOutcomes {
    fun interrupted(key: String, reason: String)
    fun delivered(key: String)
    fun actionFailed(key: String, reason: String)
}

/**
 * 对「中断了的运行」的善后:补送已有的回复、状态不明的不重发、否则告知「运行中断」并写进对话。
 * 两个入口:进程重启后的 [recover](App 启动和任务服务启动都会调,进程里只做一次,后到的什么都不做;
 * App 先于进程里任何服务起来,所以第一次调用时不会有本进程新开的运行被误判),
 * 以及任务服务被销毁、进程还在时的 [settleInterrupted]。**从不从中间续跑**:屏幕状态回不去。
 *
 * 「已告知」不另设字段,看送达账本:没有回复的运行记着 `interrupted:<运行>` 的提示,状态不明或送达失败的记着对应的提示;
 * 有记录就不再处理。回复送达了的运行直接转为 Ended。所以两个入口、多次调用都只告知一次。
 */
class RunRecovery(
    private val store: RunStore,
    private val outbox: Outbox,
    private val channel: Channel,
    private val triggers: TriggerOutcomes,
    private val conversations: ConversationStore,
    private val scope: CoroutineScope,
) {
    private val done = AtomicBoolean(false)
    private val lock = Mutex()

    /** 标记在这里同步做完(之后新开的运行不会被碰);善后在 [scope] 里慢慢做。返回这次标为中断的运行,不是第一次调用则为空。 */
    fun recover(live: Set<RunId> = emptySet()): List<StoredRun> {
        if (!done.compareAndSet(false, true)) return emptyList()
        val interrupted = store.interruptUnfinished(live)
        settleInterrupted()
        return interrupted
    }

    /**
     * 把所有中断了、还没告知过的运行善后一次。任务服务被销毁时当场调(进程还在,用户不必等到下次启动才知道),
     * 启动时的 [recover] 也会扫一遍,接住当场没做完就被杀的。
     */
    fun settleInterrupted() {
        scope.launch {
            lock.withLock {
                store.all().filter { it.phase == RunPhase.Interrupted && !told(it) }
                    .forEach { run -> runCatching { settle(run) } }
            }
        }
    }

    private fun noticeIds(run: StoredRun) =
        if (run.reply != null && run.deliveryId != null) listOf("uncertain:${run.deliveryId}", "failed:${run.deliveryId}")
        else listOf("interrupted:${run.id.value}")

    private fun told(run: StoredRun) = noticeIds(run).any { outbox.get(it) != null }

    private suspend fun settle(run: StoredRun) {
        run.triggerWindowKey?.let { triggers.interrupted(it, "run interrupted before the result was confirmed") }
        val peer = ChannelPeer(LocalChannel.ID, "phone-owner", run.threadId)
        val deliveryId = run.deliveryId
        val reply = run.reply
        if (reply != null && deliveryId != null) {
            val prior = outbox.get(deliveryId)
            val state = when (prior?.state) {
                null, DeliveryState.Pending -> outbox.deliver(channel, peer,
                    prior?.delivery ?: Delivery.Result(deliveryId, run.threadId, reply, restored = true, title = tr(R.string.recovered_result))).state
                DeliveryState.Attempting -> outbox.deliver(channel, peer, prior.delivery).state
                else -> prior.state
            }
            when (state) {
                DeliveryState.Delivered -> finish(run, reply)
                DeliveryState.Failed -> outbox.deliver(channel, peer, Delivery.Notice("failed:$deliveryId", run.threadId,
                    tr(R.string.recover_delivery_failed)))
                else -> outbox.deliver(channel, peer, Delivery.Notice("uncertain:$deliveryId", run.threadId,
                    tr(R.string.recover_maybe_delivered)))
            }
        } else {
            val turnId = "interrupted:${run.id.value}"
            if (conversations.turns(run.threadId).none { it.runId == turnId }) {
                conversations.append(run.threadId, Turn(run.goal, tr(R.string.recover_interrupted_turn), "failed", runId = turnId))
            }
            outbox.deliver(channel, peer, Delivery.Notice(turnId, run.threadId,
                tr(R.string.recover_interrupted, run.goal.take(40))))
        }
    }

    private fun finish(run: StoredRun, reply: String) {
        store.save(run.copy(phase = RunPhase.Ended, roundInProgress = false))
        run.triggerWindowKey?.let { key ->
            if (run.runSucceeded == false) triggers.actionFailed(key, reply) else triggers.delivered(key)
        }
    }
}
