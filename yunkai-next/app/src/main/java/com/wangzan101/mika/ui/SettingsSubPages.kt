package com.wangzan101.mika.ui

import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import com.wangzan101.mika.ui.components.MikaSwitch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.wangzan101.mika.i18n.AppLanguage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.capsule.CapsuleMode
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.settings.ChannelRow
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.settings.Problem
import com.wangzan101.mika.settings.SystemStatus
import com.wangzan101.mika.settings.notificationOverview
import com.wangzan101.mika.task.ParallelLimit
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

// ---------- 手机操作 ----------

/** 设置 › 手机操作:权限与配对的状态,以及重新配对、解除配对。点哪一项的处理方式和从前一样,交给 [onProblem]。 */
@Composable
fun PhoneOpsScreen(
    status: SystemStatus,
    androidRelease: String,
    onProblem: (Problem) -> Unit,
    onOpenNotifications: () -> Unit,
    onRepair: () -> Unit,
    onUnpair: () -> Unit,
    onBack: () -> Unit,
) {
    val check = Prerequisites.check(status, hasMainModel = true)
    var confirmUnpair by remember { mutableStateOf(false) }
    PageScaffold(stringResource(R.string.settings_phone_ops), onBack) {
        GroupLabel(stringResource(R.string.settings_status))
        GroupCard {
            val (a11yText, a11yTone) = StatusCopy.accessibilityValue(status.accessibility)
            ListRow(
                MikaIcons.Hand, stringResource(R.string.settings_a11y), stringResource(R.string.settings_a11y_sub), first = true,
                onClick = check.problems.firstOrNull { it is Problem.Accessibility }?.let { { onProblem(it) } },
            ) { RowValue(a11yText, a11yTone) }
            // 安卓 13 以下手机功能整个不可用,不列这一行(系统版本那一行已说明)
            if (status.sdkInt >= Prerequisites.MIN_SDK) {
                val helper = status.builtinShell
                val (hText, hTone) = PhoneSetupCopy.statusValue(helper)
                val reconnect = PhoneSetupCopy.canReconnect(helper)
                ListRow(
                    MikaIcons.Link, PhoneSetupCopy.ROW_TITLE,
                    if (reconnect && helper is BuiltinShellStatus.Failed) PhoneSetupCopy.failure(helper.reason).title
                    else PhoneSetupCopy.statusLine(helper, status.usbDebugging),
                    onClick = { onProblem(Problem.PhoneHelper(helper)) },
                ) { if (reconnect) RowValue(PhoneSetupCopy.RECONNECT, ValueTone.Link) else RowValue(hText, hTone) }
            }
            val (notifText, notifTone) = StatusCopy.notificationSummary(status.notificationOverview().summary)
            ListRow(MikaIcons.Bell, StatusCopy.NOTIFICATIONS, stringResource(R.string.settings_notif_progress_sub), onClick = onOpenNotifications) {
                RowValue(notifText, notifTone)
                RowChevron()
            }
            val tooOld = status.sdkInt < Prerequisites.MIN_SDK
            val focusWarning = Problem.FocusMayLeak in check.problems
            ListRow(
                MikaIcons.Phone, stringResource(R.string.settings_system_version),
                if (focusWarning) stringResource(R.string.settings_android_focus, androidRelease) else stringResource(R.string.settings_android, androidRelease),
                onClick = when {
                    tooOld -> ({ onProblem(Problem.SystemTooOld) })
                    focusWarning -> ({ onProblem(Problem.FocusMayLeak) })
                    else -> null
                },
            ) {
                when {
                    tooOld -> RowValue(stringResource(R.string.pill_unsupported), ValueTone.Bad)
                    focusWarning -> RowValue(stringResource(R.string.pill_available), ValueTone.Plain)
                    else -> RowValue(stringResource(R.string.settings_meets), ValueTone.Ok)
                }
            }
        }
        if (status.sdkInt >= Prerequisites.MIN_SDK) {
            GroupLabel(stringResource(R.string.phone_channel))
            GroupCard {
                ListRow(MikaIcons.Link, PhoneSetupCopy.REPAIR, PhoneSetupCopy.REPAIR_SUBTITLE, first = true, onClick = onRepair) {
                    RowChevron()
                }
                val paired = status.builtinShell != BuiltinShellStatus.NotPaired
                ListRow(
                    MikaIcons.Close, PhoneSetupCopy.UNPAIR, PhoneSetupCopy.UNPAIR_SUBTITLE,
                    titleColor = if (paired) MikaTheme.colors.bad else MikaTheme.colors.ink3,
                    tone = if (paired) TileTone.Bad else TileTone.Neutral,
                    onClick = if (paired) ({ confirmUnpair = true }) else null,
                )
            }
            FooterNote(PhoneSetupCopy.USAGE_NOTE)
        }
    }
    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text(PhoneSetupCopy.UNPAIR_TITLE) },
            text = { Text(PhoneSetupCopy.UNPAIR_BODY) },
            confirmButton = {
                TextButton({ confirmUnpair = false; onUnpair() }) { Text(PhoneSetupCopy.UNPAIR_CONFIRM, color = MikaTheme.colors.bad) }
            },
            dismissButton = { TextButton({ confirmUnpair = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

// ---------- 通知 ----------

/**
 * 设置 › 通知:只看状态、去系统里改。顶上是 App 通知总开关,下面三个任务通知频道各一行(什么时候发 + 现在会不会弹出),
 * 点哪一行都由 [onFix] 打开对应的系统设置页(总开关没开时先请求权限或打开应用通知页)。
 */
@Composable
fun NotificationsScreen(status: SystemStatus, onFix: (Fix) -> Unit, onBack: () -> Unit) {
    val o = status.notificationOverview()
    PageScaffold(StatusCopy.NOTIFICATIONS, onBack) {
        GroupCard {
            ListRow(
                MikaIcons.Bell, stringResource(R.string.settings_notif_master),
                if (o.appAllowed) StatusCopy.NOTIF_APP_SUBTITLE_ON else StatusCopy.NOTIF_APP_SUBTITLE_OFF,
                first = true, tone = if (o.appAllowed) TileTone.Neutral else TileTone.Bad,
                onClick = if (o.appAllowed) null else ({ onFix(Fix.AllowNotifications) }),
            ) {
                if (o.appAllowed) RowValue(stringResource(R.string.settings_allowed), ValueTone.Ok) else RowValue(StatusCopy.HEADS_UP_ACTION, ValueTone.Link)
            }
        }
        GroupLabel(stringResource(R.string.settings_task_notifs))
        GroupCard {
            ChannelRowItem(StatusCopy.ATTENTION_CHANNEL, StatusCopy.NOTIF_ATTENTION_WHEN, o.attention, Fix.OpenAttentionChannel, onFix, first = true)
            ChannelRowItem(StatusCopy.RESULT_CHANNEL, StatusCopy.NOTIF_RESULT_WHEN, o.result, Fix.OpenResultChannel, onFix)
            ChannelRowItem(StatusCopy.PROGRESS_CHANNEL, StatusCopy.NOTIF_PROGRESS_WHEN, o.progress, Fix.OpenProgressChannel, onFix)
        }
        FooterNote(StatusCopy.NOTIF_FOOTER)
    }
}

@Composable
private fun ChannelRowItem(title: String, whenText: String, row: ChannelRow, fix: Fix, onFix: (Fix) -> Unit, first: Boolean = false) {
    val (text, tone) = StatusCopy.channelLevelValue(row)
    ListRow(
        MikaIcons.Bell, title, whenText, first = first,
        tone = if (row.warn) TileTone.Warn else TileTone.Neutral,
        onClick = { onFix(fix) },
    ) {
        RowValue(text, tone)
        RowChevron()
    }
}

// ---------- 干活时 ----------

/** 并行任务的设置:用户设的上限 [max](1–3),和设备此刻允许的 [limit]。 */
data class ParallelSetting(val max: Int, val limit: ParallelLimit)

/** 设置 › 干活时:屏幕常亮、被操作的 App 静音(默认都开,可以关);[parallel] 不为空(实验开关打开了)时多一行同时进行的任务数。 */
@Composable
fun DuringWorkScreen(
    keepScreenOn: Boolean, onKeepScreenOn: (Boolean) -> Unit,
    muteTarget: Boolean, onMuteTarget: (Boolean) -> Unit,
    parallel: ParallelSetting?, onParallelMax: (Int) -> Unit, onBack: () -> Unit,
) {
    PageScaffold(stringResource(R.string.settings_during_work), onBack) {
        GroupCard {
            ListRow(MikaIcons.Sun, stringResource(R.string.settings_keep_on), stringResource(R.string.settings_keep_on_sub), first = true, onClick = { onKeepScreenOn(!keepScreenOn) }) {
                MikaSwitch(keepScreenOn, onKeepScreenOn, Modifier.testTag("work-keep-screen-on"))
            }
            ListRow(MikaIcons.Mute, stringResource(R.string.settings_mute), stringResource(R.string.settings_mute_sub), onClick = { onMuteTarget(!muteTarget) }) {
                MikaSwitch(muteTarget, onMuteTarget, Modifier.testTag("work-mute-target"))
            }
            parallel?.let { ParallelRow(it, onParallelMax) }
        }
    }
}

/** 同时进行几个任务:设备不支持时灰掉、写明原因,上限固定为 1。 */
@Composable
private fun ParallelRow(p: ParallelSetting, onMax: (Int) -> Unit) {
    val supported = p.limit.reason == null
    ListRow(
        MikaIcons.Phone, stringResource(R.string.settings_parallel),
        subtitle = p.limit.reason ?: stringResource(R.string.settings_parallel_sub),
        titleColor = if (supported) MikaTheme.colors.ink else MikaTheme.colors.ink3,
    ) {
        if (!supported) {
            RowValue(trPlural(R.plurals.settings_count_tasks, 1))
            return@ListRow
        }
        Text(
            "−", Modifier.clickable(enabled = p.max > 1) { onMax(p.max - 1) }.padding(horizontal = 10.dp, vertical = 4.dp),
            fontSize = 18.sp, color = if (p.max > 1) MikaTheme.colors.accent else MikaTheme.colors.ink3,
        )
        RowValue(trPlural(R.plurals.settings_count_tasks, p.max))
        Text(
            "+", Modifier.clickable(enabled = p.max < 3) { onMax(p.max + 1) }.padding(horizontal = 10.dp, vertical = 4.dp),
            fontSize = 18.sp, color = if (p.max < 3) MikaTheme.colors.accent else MikaTheme.colors.ink3,
        )
    }
}

// ---------- 悬浮胶囊 ----------

/** 设置 › 悬浮胶囊的文字。 */
object CapsuleSettingsCopy {
    val TITLE: String get() = tr(R.string.capsule_settings_title)
    val SUBTITLE: String get() = tr(R.string.capsule_settings_sub)
    val FOOTNOTE: String get() = tr(R.string.capsule_settings_foot)

    fun label(mode: CapsuleMode): String = when (mode) {
        CapsuleMode.Off -> tr(R.string.capsule_mode_off)
        CapsuleMode.DuringTasks -> tr(R.string.capsule_mode_tasks)
        CapsuleMode.Always -> tr(R.string.capsule_mode_always)
    }

    fun detail(mode: CapsuleMode): String = when (mode) {
        CapsuleMode.Off -> tr(R.string.capsule_mode_off_detail)
        CapsuleMode.DuringTasks -> tr(R.string.capsule_mode_tasks_detail)
        CapsuleMode.Always -> tr(R.string.capsule_mode_always_detail)
    }
}

/** 设置 › 悬浮胶囊:关闭 / 有任务时显示(默认) / 一直显示,选了当场生效。 */
@Composable
fun CapsuleScreen(mode: CapsuleMode, onMode: (CapsuleMode) -> Unit, onBack: () -> Unit) {
    PageScaffold(CapsuleSettingsCopy.TITLE, onBack) {
        GroupCard {
            CapsuleMode.entries.forEachIndexed { i, m ->
                RadioRow(
                    CapsuleSettingsCopy.label(m), selected = m == mode, onClick = { onMode(m) },
                    subtitle = CapsuleSettingsCopy.detail(m), first = i == 0,
                )
            }
        }
        FooterNote(CapsuleSettingsCopy.FOOTNOTE)
    }
}

/** 设置 › 语言:跟随系统、中文、English;选了马上换,界面跟着重建。 */
@Composable
fun LanguageScreen(language: AppLanguage, onLanguage: (AppLanguage) -> Unit, onBack: () -> Unit) {
    PageScaffold(stringResource(R.string.language_title), onBack) {
        GroupCard {
            AppLanguage.entries.forEachIndexed { i, l ->
                RadioRow(
                    languageLabel(l), selected = l == language, onClick = { if (l != language) onLanguage(l) },
                    subtitle = if (l == AppLanguage.System) stringResource(R.string.language_system_detail) else null, first = i == 0,
                )
            }
        }
    }
}

/** 语言的名字:中文、English 总用它自己的写法,「跟随系统」按当前语言写。 */
@Composable
fun languageLabel(l: AppLanguage): String = stringResource(
    when (l) {
        AppLanguage.System -> R.string.language_system
        AppLanguage.Chinese -> R.string.language_chinese
        AppLanguage.English -> R.string.language_english
    },
)

// ---------- 隐私与数据 ----------

/** 设置 › 网站登录。 */
@Composable
fun WebSitesScreen(state: WebSitesState, onBack: () -> Unit) {
    PageScaffold(stringResource(R.string.settings_web_logins), onBack) { WebSitesSection(state, labeled = false) }
}

// ---------- 高级 ----------

/** 设置 › 文件与计算环境:允许脚本联网、重置运行环境。 */
@Composable
fun SandboxScreen(onBack: () -> Unit) {
    PageScaffold(stringResource(R.string.settings_sandbox), onBack) { AdvancedSettings() }
}

/** 设置 › 记住的操作路线:[count] 条,可以全部清除(先确认);[count] 为空表示还没读出来。 */
@Composable
fun RoutesScreen(count: Int?, onClear: () -> Unit, onBack: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    PageScaffold(stringResource(R.string.settings_routes), onBack) {
        GroupCard {
            ListRow(
                MikaIcons.Route, stringResource(R.string.routes_saved),
                if (count == null) stringResource(R.string.loading) else trPlural(R.plurals.routes_count, count), first = true,
            ) {
                if (count != null && count > 0) Text(
                    stringResource(R.string.routes_clear_all), Modifier.clickable { confirmClear = true }.padding(4.dp),
                    style = MikaType.Value, color = MikaTheme.colors.bad,
                )
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.routes_clear_title)) },
            text = { Text(stringResource(R.string.routes_clear_body)) },
            confirmButton = { TextButton({ confirmClear = false; onClear() }) { Text(stringResource(R.string.clear)) } },
            dismissButton = { TextButton({ confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** 设置 › 恢复与修复:手机操作的几个授权开关。 */
@Composable
fun RecoveryScreen(onBack: () -> Unit) {
    PageScaffold(stringResource(R.string.settings_recovery), onBack) { RecoverySwitches() }
}
