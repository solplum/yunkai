package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.core.agent.RunProgress
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.ChannelLevel
import com.wangzan101.mika.settings.ChannelRow
import com.wangzan101.mika.settings.NotificationSummary
import com.wangzan101.mika.settings.Problem
import com.wangzan101.mika.core.shell.BuiltinShellFailure
import com.wangzan101.mika.core.shell.BuiltinShellFix
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.ui.components.ValueTone

/** 设置页能直接做的修复动作。 */
enum class Fix {
    OpenAccessibility, AllowNotifications, AddModel,
    /** 直接打开「需要你处理」/「任务结果」/「任务进度」频道的设置页,打开「在屏幕上弹出」;打不开退回应用通知总页。 */
    OpenAttentionChannel, OpenResultChannel, OpenProgressChannel,
    /** 「配对」通知频道的设置页:配对码要填在这类通知里。 */
    OpenPairingChannel,
    /** 本 App 的「应用信息」页:系统拦着不让开无障碍时,在这里允许受限制的设置。 */
    OpenAppInfo,
    // 内置助手
    OpenPhoneSetup, RepairPhoneHelper, RetryPhoneHelper, UnpairPhoneHelper,
    OpenAboutPhone, OpenDeveloperOptions, OpenUsbDebugging, OpenWirelessDebugging, OpenWifi,
}

/** 顶部横幅的一行字和右侧链接。 */
data class BannerCopy(val title: String, val detail: String, val link: String)

/** 点开问题时的说明:用大白话讲清楚是什么、怎么办;[fix] 为空表示没有一键修复。 */
data class Explanation(val title: String, val body: String, val action: String?, val fix: Fix?)

object StatusCopy {
    /** 不经模型连做几步时卡片和通知上的说法。 */
    fun progress(p: RunProgress): String = when (p) {
        is RunProgress.Replay -> tr(R.string.progress_replay, p.step, p.total)
        is RunProgress.Batch -> trPlural(R.plurals.progress_batch, p.steps)
    }

    /** 付款接管的商品、金额、地址,一行一项;都没有时为 null。卡片和通知共用。 */
    fun paymentLines(item: String?, amount: String?, address: String?): String? =
        listOfNotNull(
            item?.takeIf { it.isNotBlank() }?.let { tr(R.string.payment_item, it) },
            amount?.takeIf { it.isNotBlank() }?.let { tr(R.string.payment_amount, it) },
            address?.takeIf { it.isNotBlank() }?.let { tr(R.string.payment_address, it) },
        ).joinToString("\n").ifEmpty { null }

    fun accessibilityValue(s: AccessibilityState): Pair<String, ValueTone> = when (s) {
        AccessibilityState.On -> tr(R.string.state_on) to ValueTone.Ok
        AccessibilityState.Off -> tr(R.string.state_off) to ValueTone.Bad
        AccessibilityState.EnabledNotRunning -> tr(R.string.state_not_running) to ValueTone.Bad
    }

    /** 不用说明、点了直接修的问题。 */
    fun directFix(p: Problem): Fix? = when (p) {
        Problem.NotificationsOff -> Fix.AllowNotifications
        is Problem.AttentionLowered -> headsUpFix(p)
        Problem.NoModel -> Fix.AddModel
        // 引导页把每一步都讲清楚了,不再弹说明
        is Problem.PhoneHelper -> when {
            PhoneSetupCopy.canReconnect(p.status) -> Fix.RetryPhoneHelper
            // 这个 Wi-Fi 还没允许无线调试:直接去亲手打开一次(系统会问要不要允许)
            (p.status as? BuiltinShellStatus.Failed)?.fix == BuiltinShellFix.OpenWirelessDebugging -> Fix.OpenWirelessDebugging
            else -> Fix.OpenPhoneSetup
        }
        else -> null
    }

    // ---- 设置 › 通知 ----
    /** 停止之后、交代还没写好时那一行小字(对话页和悬浮卡片一样)。 */
    val STOP_SUMMARIZING: String get() = tr(R.string.stop_summarizing)
    val NOTIFICATIONS: String get() = tr(R.string.notifications_title)
    val PROGRESS_CHANNEL: String get() = tr(R.string.channel_progress)

    fun notificationSummary(s: NotificationSummary): Pair<String, ValueTone> = when (s) {
        NotificationSummary.Normal -> tr(R.string.notif_normal) to ValueTone.Ok
        is NotificationSummary.Lowered -> trPlural(R.plurals.notif_lowered, s.count) to ValueTone.Warn
        NotificationSummary.AllOff -> tr(R.string.state_turned_off) to ValueTone.Bad
    }

    fun channelLevelValue(row: ChannelRow): Pair<String, ValueTone> = when (row.level) {
        ChannelLevel.Off -> tr(R.string.state_turned_off) to if (row.warn) ValueTone.Warn else ValueTone.Plain
        ChannelLevel.HeadsUp -> tr(R.string.notif_heads_up) to ValueTone.Ok
        ChannelLevel.Quiet -> tr(R.string.notif_quiet) to if (row.warn) ValueTone.Warn else ValueTone.Plain
    }

    val NOTIF_APP_SUBTITLE_ON: String get() = tr(R.string.notif_app_on)
    val NOTIF_APP_SUBTITLE_OFF: String get() = tr(R.string.notif_app_off)
    val NOTIF_ATTENTION_WHEN: String get() = tr(R.string.notif_attention_when)
    val NOTIF_RESULT_WHEN: String get() = tr(R.string.notif_result_when)
    val NOTIF_PROGRESS_WHEN: String get() = tr(R.string.notif_progress_when)
    val NOTIF_FOOTER: String get() = tr(R.string.notif_footer)

    val ATTENTION_CHANNEL: String get() = tr(R.string.channel_attention)
    val RESULT_CHANNEL: String get() = tr(R.string.channel_result)
    val HEADS_UP_ACTION: String get() = tr(R.string.heads_up_action)

    /** 被调低的两个频道里先处理「需要你处理」。 */
    fun headsUpFix(p: Problem.AttentionLowered): Fix = if (p.attention) Fix.OpenAttentionChannel else Fix.OpenResultChannel

    private fun headsUpTitle(p: Problem.AttentionLowered) =
        tr(R.string.heads_up_title, if (p.attention) ATTENTION_CHANNEL else RESULT_CHANNEL)

    private fun headsUpBody(p: Problem.AttentionLowered): String {
        val lead = if (p.attention) tr(R.string.heads_up_lead_attention) else tr(R.string.heads_up_lead_result)
        val also = if (p.attention && p.result) tr(R.string.heads_up_also, RESULT_CHANNEL) else ""
        return tr(R.string.heads_up_body, lead, also, HEADS_UP_ACTION)
    }

    private fun headsUpPath(p: Problem.AttentionLowered) =
        tr(R.string.heads_up_path, if (p.attention) ATTENTION_CHANNEL else RESULT_CHANNEL)

    fun banner(p: Problem): BannerCopy = when (p) {
        Problem.SystemTooOld -> BannerCopy(tr(R.string.banner_too_old), tr(R.string.banner_too_old_detail), tr(R.string.learn_more))
        is Problem.PhoneHelper -> when (val st = p.status) {
            BuiltinShellStatus.NotPaired -> BannerCopy(tr(R.string.banner_not_paired), tr(R.string.banner_not_paired_detail), tr(R.string.chat_go_settings))
            BuiltinShellStatus.WirelessDebuggingOff -> BannerCopy(tr(R.string.banner_wireless_off), tr(R.string.banner_wireless_off_detail), tr(R.string.heads_up_action))
            BuiltinShellStatus.Connecting -> BannerCopy(tr(R.string.banner_connecting), tr(R.string.banner_connecting_detail), tr(R.string.view))
            is BuiltinShellStatus.Failed ->
                if (st.reason == BuiltinShellFailure.NoWifi) BannerCopy(tr(R.string.banner_paused), PhoneSetupCopy.NO_WIFI_LINE, tr(R.string.view))
                else if (st.reason == BuiltinShellFailure.WifiNotAllowed) BannerCopy(
                    PhoneSetupCopy.failure(st.reason).title, PhoneSetupCopy.WIFI_NOT_ALLOWED_LINE, tr(R.string.heads_up_action),
                )
                else BannerCopy(
                    tr(R.string.banner_failed), PhoneSetupCopy.failure(st.reason).title,
                    if (PhoneSetupCopy.canReconnect(st)) PhoneSetupCopy.RECONNECT else tr(R.string.fix_it),
                )
            BuiltinShellStatus.Running -> error("not a problem")
        }
        is Problem.Accessibility -> when (p.state) {
            AccessibilityState.Off -> BannerCopy(tr(R.string.banner_a11y_off), tr(R.string.banner_a11y_off_detail), tr(R.string.turn_on_go))
            AccessibilityState.EnabledNotRunning -> BannerCopy(tr(R.string.banner_a11y_not_running), tr(R.string.banner_a11y_not_running_detail), tr(R.string.fix_it))
            AccessibilityState.On -> error("not a problem")
        }
        Problem.NoModel -> BannerCopy(tr(R.string.banner_no_model), tr(R.string.banner_no_model_detail), tr(R.string.add))
        Problem.NotificationsOff -> BannerCopy(tr(R.string.banner_notif_off), tr(R.string.banner_notif_off_detail), tr(R.string.allow))
        is Problem.AttentionLowered -> BannerCopy(headsUpTitle(p), headsUpBody(p) + "\n" + headsUpPath(p), HEADS_UP_ACTION)
        Problem.FocusMayLeak -> BannerCopy(tr(R.string.banner_focus), tr(R.string.banner_focus_detail), tr(R.string.learn_more))
    }

    fun explain(p: Problem, androidRelease: String): Explanation = when (p) {
        Problem.SystemTooOld -> Explanation(
            tr(R.string.explain_too_old),
            tr(R.string.explain_too_old_body, androidRelease),
            null, null,
        )
        is Problem.PhoneHelper -> Explanation(
            PhoneSetupCopy.TITLE, PhoneSetupCopy.INTRO, PhoneSetupCopy.DEGRADED_ACTION, Fix.OpenPhoneSetup,
        )
        is Problem.Accessibility -> when (p.state) {
            AccessibilityState.Off -> Explanation(
                tr(R.string.explain_a11y_off),
                tr(R.string.explain_a11y_off_body),
                tr(R.string.chat_go_settings), Fix.OpenAccessibility,
            )
            AccessibilityState.EnabledNotRunning -> Explanation(
                tr(R.string.banner_a11y_not_running),
                tr(R.string.explain_a11y_not_running_body),
                tr(R.string.chat_go_settings), Fix.OpenAccessibility,
            )
            AccessibilityState.On -> error("not a problem")
        }
        Problem.NoModel -> Explanation(
            tr(R.string.explain_no_model), tr(R.string.explain_no_model_body),
            tr(R.string.add), Fix.AddModel,
        )
        Problem.FocusMayLeak -> Explanation(
            tr(R.string.explain_focus),
            tr(R.string.explain_focus_body, androidRelease),
            null, null,
        )
        is Problem.AttentionLowered -> Explanation(
            headsUpTitle(p), headsUpBody(p) + "\n\n" + headsUpPath(p), HEADS_UP_ACTION, headsUpFix(p),
        )
        Problem.NotificationsOff -> Explanation(
            tr(R.string.explain_notif), tr(R.string.explain_notif_body),
            tr(R.string.allow), Fix.AllowNotifications,
        )
    }

    /**
     * 任务卡片上跑命令这一步的说明:「正在处理文件:<命令前 40 字>」,联网时注明「可联网」。
     * 运行环境对用户不可见,字面上不说沙箱。
     */
    fun sandboxCommand(command: String, network: Boolean): String = sandboxLine(tr(R.string.sandbox_command_prefix), command, 40, network)

    /** 跑技能脚本时:「正在运行脚本」。 */
    fun sandboxScript(network: Boolean): String = tr(R.string.sandbox_script) + if (network) " · $SANDBOX_ONLINE" else ""

    /** 命令里 `mika progress` 报的进度。 */
    fun sandboxProgress(text: String, network: Boolean): String = sandboxLine("", text, 60, network)

    val SANDBOX_ONLINE: String get() = tr(R.string.sandbox_online)

    /** 工具在等运行环境准备好时。 */
    val RUNTIME_PREPARING: String get() = tr(R.string.runtime_preparing)

    private fun sandboxLine(prefix: String, text: String, max: Int, network: Boolean): String {
        // 和 `mika progress` 一样去掉控制字符、零宽与改写方向的字符,卡片上显示的就是实际的命令
        val one = com.wangzan101.mika.cap.sandbox.BridgeProtocol.cleanProgress(text.replace(Regex("\\s+"), " "))
        val cut = if (one.codePointCount(0, one.length) > max) one.substring(0, one.offsetByCodePoints(0, max)) + "…" else one
        return prefix + cut + if (network) " · $SANDBOX_ONLINE" else ""
    }
}
