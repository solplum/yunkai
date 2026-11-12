package com.wangzan101.mika.cap.sandbox

import android.content.Context
import android.util.Log
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 在后台准备运行环境:App 启动时、Mika 升级后、重置后、任务要用而还没准备好时。
 * 对用户不可见,只在准备期间有一条低优先级的中性通知([SandboxForeground])。不联网。
 */
object RuntimePreparer {
    private const val TAG = "RuntimePreparer"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    /** 这个进程里准备失败过:App 启动时不再自动重试,任务要用时([ensure] 的 retryFailed)才再试。 */
    @Volatile private var failed = false

    /** 正在进行的准备;没有为 null。 */
    val current: Job? get() = job?.takeIf { it.isActive }

    /** 需要准备就开始,返回这次准备(已在准备就返回那一个);不需要返回 null。 */
    @Synchronized
    fun ensure(context: Context, retryFailed: Boolean = false): Job? {
        current?.let { return it }
        val app = context.applicationContext
        val state = SandboxState.get(app)
        if (!needsWork(state.status.value, state.installedVersion, BundledRuntime.VERSION, skipBroken = failed && !retryFailed)) return null
        val j = scope.launch {
            try {
                RootfsInstaller.create(app).install { _, _ -> }
                failed = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                Log.w(TAG, "runtime preparation failed", e)
            }
        }
        job = j
        // 前台服务让进程在准备期间不被回收,并显示中性通知;后台时系统不让启动前台服务,就只在进程里准备
        try {
            SandboxForeground.start(app)
        } catch (e: Exception) {
            Log.i(TAG, "preparing without the foreground notification: $e")
        }
        return j
    }

    /** 要不要准备:没装、装了旧版本(Mika 升级了)、出过错(除非 [skipBroken])。不支持或正在进行的不动。 */
    internal fun needsWork(status: SandboxStatus, installed: String?, bundled: String, skipBroken: Boolean): Boolean = when (status) {
        is SandboxStatus.Unsupported, is SandboxStatus.Installing -> false
        is SandboxStatus.Ready -> installed != bundled
        SandboxStatus.NotInstalled -> true
        is SandboxStatus.Broken -> !skipBroken
    }
}
