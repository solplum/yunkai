package com.wangzan101.mika.task

import android.content.Context
import android.os.PowerManager
import com.wangzan101.mika.core.agent.TaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

interface WakeLockPort {
    fun acquire()
    fun release()
}

/**
 * 任务在跑时屏幕常亮,像播放视频那样:持有屏幕 wake lock,不改任何系统设置、不改亮度。
 * 熄屏后系统会丢弃注入的触摸并随后上锁,所以 Agent 干活期间不能让屏幕自动熄灭。
 *
 * 只在 Running 时持有。等待接管/回答/继续/解锁时屏幕不需要为 Agent 亮着,
 * 再保持 [GRACE_MS](用户多半正要处理,别让屏幕在他眼前熄掉)后释放,任务继续跑时重新持有。
 * 结束、失败、取消立即释放。锁随进程走:Mika 异常退出时系统自动收回,不需要持久化恢复。
 * 用户按电源键照样能熄屏(没带 ACQUIRE_CAUSES_WAKEUP),这时 gate 会让任务等待解锁。
 */
class KeepAwake(private val lock: WakeLockPort, private val scope: CoroutineScope) {
    private var held = false
    private var pendingRelease: Job? = null
    private var closed = false

    @Synchronized
    fun onState(state: TaskState) {
        if (closed) return
        when (state) {
            is TaskState.Running -> {
                pendingRelease?.cancel()
                pendingRelease = null
                if (!held) {
                    lock.acquire()
                    held = true
                }
            }
            TaskState.Idle, is TaskState.Succeeded, is TaskState.Failed, TaskState.Cancelled -> release()
            else -> if (held && pendingRelease == null) {
                pendingRelease = scope.launch {
                    delay(GRACE_MS)
                    synchronized(this@KeepAwake) {
                        pendingRelease = null
                        release()
                    }
                }
            }
        }
    }

    @Synchronized
    fun release() {
        pendingRelease?.cancel()
        pendingRelease = null
        if (held) {
            lock.release()
            held = false
        }
    }

    /** 任务结束:释放并忽略之后到达的任何状态(旁路协程里迟到的发布不能重新拿锁)。 */
    @Synchronized
    fun close() {
        closed = true
        release()
    }

    companion object {
        const val GRACE_MS = 30_000L
    }
}

/**
 * 几个任务共用一把屏幕锁:每个任务拿一份 [lease],第一份持有时获取、最后一份放开时释放。
 * 每份自己记着拿没拿,重复获取或释放不会多算。
 */
class SharedWakeLock(private val lock: WakeLockPort) {
    private var count = 0

    fun lease(): WakeLockPort = object : WakeLockPort {
        private var held = false

        override fun acquire() {
            synchronized(this@SharedWakeLock) {
                if (held) return
                held = true
                if (count++ == 0) lock.acquire()
            }
        }

        override fun release() {
            synchronized(this@SharedWakeLock) {
                if (!held) return
                held = false
                if (--count == 0) lock.release()
            }
        }
    }
}

/**
 * SCREEN_BRIGHT_WAKE_LOCK 已标为弃用(推荐窗口的 FLAG_KEEP_SCREEN_ON),但 Mika 在后台跑任务时没有自己的窗口,
 * 而前台服务持有的这类锁仍然有效。它保持用户当前的亮度,不调亮也不调暗。
 */
class ScreenWakeLock(context: Context) : WakeLockPort {
    @Suppress("DEPRECATION")
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "Mika:task")
        .apply { setReferenceCounted(false) }

    override fun acquire() = lock.acquire(MAX_HOLD_MS)
    override fun release() { if (lock.isHeld) lock.release() }

    private companion object {
        /** 兜底上限,防止哪条路径漏了释放;正常由任务状态释放。 */
        const val MAX_HOLD_MS = 6 * 60 * 60 * 1000L
    }
}
