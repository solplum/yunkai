package com.wangzan101.mika.task

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * AgentLoop 在每个会改动屏幕的工具之前调用 [pass]。按先后检查:
 * 1. 手机操作助手不可用 → 暂停,等它恢复且用户点「继续」;
 * 2. 任务被挪到主屏(用户在主屏打开了这个 App)→ 暂停,用户点「继续」后才把它挪回虚拟屏;
 *    Mika 不自己判断用户是否用完,避免在用户眼前把 App 抽走;
 * 3. 屏幕熄灭或锁屏 → 等待解锁,亮屏并解锁后自动继续。
 * 前两条只在这一步用的是手机(phone 为 true)时才查:网页、沙箱上的步骤不需要助手,也不会被挪到主屏。
 * 不碰界面的步骤(模型推理、读屏)不经过这里,照常进行。
 */
class TaskGate(
    private val locked: StateFlow<Boolean>,
    private val shellAvailable: StateFlow<Boolean>,
    private val moveBack: suspend () -> Boolean,
) {
    private val movedAway = MutableStateFlow(false)
    private val resumes = MutableStateFlow(0)
    /** 发现被挪走那一刻的点击计数:之后点的「继续」都算数,哪怕 Agent 还没走到 gate。 */
    @Volatile private var resumesAtMove = 0

    fun onTaskMovedAway() {
        if (!movedAway.value) resumesAtMove = resumes.value
        movedAway.value = true
    }

    /** 已发现被挪走、还没挪回:任务服务据此立即显示暂停,不必等到下一个改动屏幕的工具。 */
    val movedAwayPending: Boolean get() = movedAway.value

    /** 用户点了「继续」。只对点击之后才开始的、或正在进行的暂停有效。 */
    fun resume() { resumes.update { it + 1 } }

    suspend fun pass(phone: () -> Boolean = { true }, setWaiting: (TaskState?) -> Unit) {
        while (true) {
            val onPhone = phone()
            when {
                onPhone && !shellAvailable.value -> {
                    pauseUntilResume(SHELL_DOWN, setWaiting)
                }
                onPhone && movedAway.value -> {
                    pauseUntilResume(MOVED_AWAY, setWaiting, seen = resumesAtMove)
                    while (shellAvailable.value && !moveBack()) pauseUntilResume(MOVE_BACK_FAILED, setWaiting)
                    if (shellAvailable.value) movedAway.value = false
                }
                locked.value -> {
                    setWaiting(TaskState.Held(LOCKED))
                    locked.first { !it }
                }
                else -> {
                    setWaiting(null)
                    return
                }
            }
        }
    }

    /** 要用户在手机上亲手处理一步,处理好了点「继续」(界面上算「要你处理」)。 */
    suspend fun pauseForUser(why: String, setWaiting: (TaskState?) -> Unit) {
        val seen = resumes.value
        setWaiting(TaskState.Held(GateReason("paused", why, resumable = true, needsYou = true)))
        resumes.first { it != seen }
    }

    /** [seen]:从这个点击计数之后的「继续」才算;默认取当前值(先记下再显示暂停,显示后立刻点的也算)。 */
    private suspend fun pauseUntilResume(why: String, setWaiting: (TaskState?) -> Unit, seen: Int = resumes.value) {
        setWaiting(TaskState.Held(paused(why)))
        resumes.first { it != seen }
    }

    companion object {
        /** 锁屏或熄屏:解锁后自动继续(运行记录里记成 waiting_unlock)。 */
        val LOCKED = GateReason("waiting_unlock")

        /** 暂停,用户点「继续」后才接着做(运行记录里记成 paused,带上原因)。 */
        fun paused(why: String) = GateReason("paused", why, resumable = true)

        /** 等用户在手机上动一下手(比如在系统的选择框里选一个),动完自动继续(运行记录里记成 waiting_user)。 */
        fun userStep(why: String) = GateReason("waiting_user", why, needsYou = true)

        /** 系统在用户屏上问打开 [app] 的哪一个时请用户选的那句;名字读不到时不说名字。 */
        fun pickCopy(app: String?) = app?.let { tr(R.string.gate_pick_app, it) } ?: tr(R.string.gate_pick_system)

        /** 选出来的 [app] 要用户亲手处理时的那句。 */
        fun finishCopy(app: String?) = app?.let { tr(R.string.gate_finish_app, it) } ?: tr(R.string.gate_finish_unknown)

        val MOVED_AWAY: String get() = tr(R.string.gate_moved_away)
        val MOVE_BACK_FAILED: String get() = tr(R.string.gate_move_back_failed)
        val SHELL_DOWN: String get() = tr(R.string.gate_shell_down)
    }
}
