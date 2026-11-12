package com.wangzan101.mika.task

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.view.Display
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * 设备状态:主屏是否不可操作(熄屏或锁屏),以及 Agent 的 App 任务是否被用户挪到了主屏。
 * 熄屏后系统会丢弃注入的触摸,所以熄屏也按「锁屏」处理。
 */
class DeviceStateWatcher(
    context: Context,
    private val topTaskId: (displayId: Int) -> Int,
) {
    private val appContext = context.applicationContext
    private val power = appContext.getSystemService(PowerManager::class.java)
    private val keyguard = appContext.getSystemService(KeyguardManager::class.java)

    private val _locked = MutableStateFlow(readLocked())
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    // 只用广播当作「该重新看一眼」的信号,状态本身每次都从系统读,不靠广播顺序推断
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            _locked.value = readLocked()
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        _locked.value = readLocked() // 注册前后之间的变化
    }

    private fun readLocked() = isLocked(power.isInteractive, keyguard.isKeyguardLocked)

    /**
     * 轮询两块屏最上面的任务:[taskId] 不在虚拟屏最上面、却在主屏最上面,就是被用户挪走了。
     * 每次「挪走」只发一次,挪回来后再被挪走会再发。查询失败(shell 断开)时跳过这一轮。
     */
    fun taskMovedAway(displayId: Int, taskId: Int): Flow<Unit> = flow {
        var away = false
        while (true) {
            val now = runCatching {
                isMovedAway(topTaskId(displayId), topTaskId(Display.DEFAULT_DISPLAY), taskId)
            }.getOrNull()
            if (now != null) {
                if (now && !away) emit(Unit)
                away = now
            }
            delay(POLL_MS)
        }
    }

    fun close() {
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    companion object {
        const val POLL_MS = 500L

        internal fun isLocked(interactive: Boolean, keyguardLocked: Boolean) = !interactive || keyguardLocked

        internal fun isMovedAway(agentTop: Int, mainTop: Int, taskId: Int) =
            taskId >= 0 && agentTop != taskId && mainTop == taskId
    }
}
