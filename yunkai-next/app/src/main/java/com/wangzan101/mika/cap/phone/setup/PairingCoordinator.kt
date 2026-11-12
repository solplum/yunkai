package com.wangzan101.mika.cap.phone.setup

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.core.shell.BuiltinShellControl
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.core.shell.PairingCode
import com.wangzan101.mika.core.shell.PhoneSetupFlow
import com.wangzan101.mika.ui.PhoneSetupCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一次配对的全过程:找配对窗口、无障碍开着时自己读配对码([PairingCodeReader])、
 * 读不到就弹带输入框的通知、把码交给内置助手 [control]、显示结果。
 * 跟着进程走,不跟界面(用户在系统设置里看配对码时 Mika 的界面已经不在前台):由 [com.wangzan101.mika.cap.phone.PhoneServices] 建一份。
 */
class PairingCoordinator(context: Context, private val control: BuiltinShellControl) {
    private val app = context.applicationContext
    /** 配对、重试、解除配对这类动作也放这里跑:界面关了也要做完。 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watch: Job? = null
    /** 通知上此刻显示的;再次提醒前对一下,已经换成别的(比如刚填了码)就不再弹旧的。 */
    @Volatile private var shown: PairingNotice? = null
    private val _repairing = MutableStateFlow(false)
    /** 用户点了「重新配对」且新的配对还没开始:助手即便在运行也要等新配对。 */
    val repairing: StateFlow<Boolean> = _repairing

    fun startRepair() { _repairing.value = true }

    fun cancelRepair() { _repairing.value = false }

    /** 开始一次配对:状态一变成「正在配对」就结束重新配对的等待,免得被运行中的旧状态抢先判成已完成。 */
    suspend fun pair(code: String, port: Int?) = coroutineScope {
        val job = launch(start = CoroutineStart.UNDISPATCHED) { control.startPairing(code, port) }
        _repairing.value = false
        job.join()
    }

    @Synchronized
    fun begin() {
        control.startDiscovery()
        if (watch?.isActive == true) return
        watch = scope.launch {
            val reader = launch { PairingCodeReader.run(app, control, this@PairingCoordinator) }
            withTimeoutOrNull(WINDOW_MS) {
                combine(control.status, control.pairingDiscovery, _repairing) { s, d, r ->
                    PairingNotice.of(PhoneSetupFlow.effectiveStatus(s, r), d) to (s == BuiltinShellStatus.Running && !r)
                }
                    .distinctUntilChanged()
                    .transformWhile { emit(it.first); !it.second }
                    // 状态一变就丢掉还没到点的弹出和再次提醒
                    .collectLatest { notice -> notice?.let { post(it) } }
            } ?: cancelNotice()
            reader.cancel()
            control.stopDiscovery()
        }
    }

    /** 发出 [notice];没人理就隔一阵再弹,次数有上限。 */
    private suspend fun post(notice: PairingNotice) {
        delay(PairingNotice.delayBefore(notice, autoRead = ScreenAccessibility.instance.value != null))
        show(notice)
        var sent = 0
        while (true) {
            delay(PairingNotice.realertAfter(notice, sent) ?: return)
            if (shown != notice) return
            show(notice)
            sent++
        }
    }

    private fun show(notice: PairingNotice) {
        shown = notice
        PairingNotifier.show(app, notice)
    }

    private fun cancelNotice() {
        shown = null
        PairingNotifier.cancel(app)
    }

    @Synchronized
    fun end() {
        watch?.cancel()
        watch = null
        _repairing.value = false
        control.stopDiscovery()
        cancelNotice()
    }

    /** 通知或引导页里填的码。不是 6 位数字就原地提示重填、不去试;对的话马上换成「正在配对」,免得输入框一直转圈。 */
    fun submit(raw: String, port: Int?): Boolean {
        val code = PairingCode.normalize(raw)
        if (code == null) {
            show(PairingNotice.EnterCode(port, PhoneSetupCopy.NOTIFY_CODE_INVALID))
            return false
        }
        begin()
        show(PairingNotice.Working)
        scope.launch { pair(code, port) }
        return true
    }

    private companion object {
        /** 配对窗口开着的时间有限;超过这么久没配上就不再找,下次进引导再开始。 */
        const val WINDOW_MS = 10 * 60_000L
    }
}

/** 通知输入框的落点。 */
open class PairingCodeInput : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PairingNotifier.KEY_CODE)?.toString() ?: return
        val port = intent.getIntExtra(PairingNotifier.EXTRA_PORT, -1).takeIf { it > 0 }
        PhoneServices.of(context).pairing.submit(code, port)
    }
}
