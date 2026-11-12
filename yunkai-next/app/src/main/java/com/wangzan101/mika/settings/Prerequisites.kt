package com.wangzan101.mika.settings

import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.core.shell.PairingChannelHealth

enum class AccessibilityState {
    Off,
    /** 系统设置里是开着的,服务却没在运行(App 被强行停止后常见),需要关掉再打开。 */
    EnabledNotRunning,
    On,
}

data class SystemStatus(
    val sdkInt: Int,
    /** 内置助手的状态。 */
    val builtinShell: BuiltinShellStatus,
    val accessibility: AccessibilityState,
    val notificationsAllowed: Boolean,
    /** 「需要你处理」通知频道被用户或系统调到了高优先级以下(不弹出、可能不响)。 */
    val attentionLowered: Boolean = false,
    /** 「任务结果」通知频道同理。 */
    val resultLowered: Boolean = false,
    /** 「配对」通知频道:配对码填在这里,被关掉或调成不弹出时引导页要说。 */
    val pairingChannel: PairingChannelHealth = PairingChannelHealth.Ready,
    /** 三个任务通知频道现在的重要程度;为空表示频道还没建。通知页据此显示。 */
    val attentionImportance: Int? = null,
    val resultImportance: Int? = null,
    val progressImportance: Int? = null,
    /** 系统「开发者选项」开着。 */
    val developerOptions: Boolean = false,
    /** 系统「无线调试」开着。 */
    val wirelessDebugging: Boolean = false,
    /** 系统「USB 调试」开着:开着时 Wi-Fi 断开助手也不停。 */
    val usbDebugging: Boolean = true,
    /** 系统可能拦着不让打开无障碍(安卓 13 起,没从应用商店装的 App 要先允许受限制的设置)。 */
    val restrictedSettings: Boolean = false,
)

fun SystemStatus.notificationOverview() = NotificationOverview.of(
    notificationsAllowed, attentionImportance, resultImportance, progressImportance,
)

sealed interface Problem {
    /** 安卓 13 以下没有 Mika 需要的虚拟屏能力。 */
    data object SystemTooOld : Problem
    /** 内置助手没在运行(没设置、无线调试关着、正在连、出错)。 */
    data class PhoneHelper(val status: BuiltinShellStatus) : Problem
    data class Accessibility(val state: AccessibilityState) : Problem
    data object NoModel : Problem
    /** 不挡任务,只是看不到进度和「需要你」提醒。 */
    data object NotificationsOff : Problem
    /** 「需要你处理」(接管、提问、确认)或「任务结果」的通知被调低了,不弹出,可能看不到;[attention]、[result] 说明是哪个。 */
    data class AttentionLowered(val attention: Boolean, val result: Boolean) : Problem
    /** 不挡任务:安卓 13 的虚拟屏没有独立焦点,后台操作可能让主屏的输入焦点短暂丢失。 */
    data object FocusMayLeak : Problem
}

/** 开始任务前的检查;界面据此提示,任务入口据此拦住。 */
object Prerequisites {
    const val MIN_SDK = 33
    /** 安卓 14 起虚拟屏能有自己的焦点,不再影响主屏。 */
    const val OWN_FOCUS_SDK = 34
    private fun isWarning(p: Problem) = p == Problem.NotificationsOff || p == Problem.FocusMayLeak || p is Problem.AttentionLowered

    data class Check(val problems: List<Problem>) {
        /** 挡住任务的问题。内置助手、无障碍只在操作手机 App 时需要:缺了照样能开始(网页、沙箱的任务照常做),用到手机时再报。 */
        val blockers: List<Problem> get() = problems.filter { !isWarning(it) && !phoneOnly(it) }
        val canStartTask: Boolean get() = blockers.isEmpty()
        /** 能操作手机 App。 */
        val phoneReady: Boolean get() = problems.none(::phoneOnly)
    }

    private fun phoneOnly(p: Problem) = p is Problem.PhoneHelper || p is Problem.Accessibility

    fun check(status: SystemStatus, hasMainModel: Boolean): Check = Check(
        buildList {
            if (status.sdkInt < MIN_SDK) add(Problem.SystemTooOld)
            // 安卓 13 以下手机功能整个不可用,已由 SystemTooOld 说明
            if (status.sdkInt >= MIN_SDK && status.builtinShell != BuiltinShellStatus.Running) {
                add(Problem.PhoneHelper(status.builtinShell))
            }
            if (status.accessibility != AccessibilityState.On) add(Problem.Accessibility(status.accessibility))
            if (!hasMainModel) add(Problem.NoModel)
            if (!status.notificationsAllowed) add(Problem.NotificationsOff)
            else if (status.attentionLowered || status.resultLowered) add(Problem.AttentionLowered(status.attentionLowered, status.resultLowered))
            if (status.sdkInt in MIN_SDK until OWN_FOCUS_SDK) add(Problem.FocusMayLeak)
        },
    )
}
