package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.core.shell.AutoOnBlock
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.core.shell.PermissionResult
import com.wangzan101.mika.core.shell.RecoveryCapability
import com.wangzan101.mika.core.shell.RecoveryConsent
import com.wangzan101.mika.core.shell.RepairOutcome
import com.wangzan101.mika.core.shell.RestoreOutcome
import com.wangzan101.mika.cap.phone.PhoneServices
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.theme.MikaType

/**
 * 设置 › 恢复与修复 里手机操作的几个授权开关的文字(纯函数,便于测试)。和引导页一样说大白话,
 * 每一项都写清楚打开后 Mika 会做什么、怎么关;说明一直写在开关下面。都默认打开,配好手机操作后 Mika 自己补上。
 */
object RecoveryCopy {
    data class SwitchCopy(val title: String, val subtitle: String, val explain: String)

    fun switch(c: RecoveryCapability): SwitchCopy = when (c) {
        RecoveryCapability.Accessibility -> SwitchCopy(
            tr(R.string.rec_a11y),
            tr(R.string.rec_default_on),
            tr(R.string.rec_a11y_body),
        )
        RecoveryCapability.BatteryWhitelist -> SwitchCopy(
            tr(R.string.rec_battery),
            tr(R.string.rec_default_on),
            tr(R.string.rec_battery_body),
        )
        RecoveryCapability.RestoreAfterReboot -> SwitchCopy(
            tr(R.string.rec_boot),
            tr(R.string.rec_default_on),
            tr(R.string.rec_boot_body),
        )
        RecoveryCapability.AutoWirelessDebugging -> SwitchCopy(
            tr(R.string.rec_auto_wireless),
            tr(R.string.rec_default_on),
            tr(R.string.rec_auto_wireless_body),
        )
    }

    val NEED_SETUP: String get() = tr(R.string.rec_need_setup)
    val FAILED: String get() = tr(R.string.rec_failed)
    val WORKING: String get() = tr(R.string.rec_working)

    /** 开关拨动后的一行反馈;成功时不多说。 */
    fun result(r: PermissionResult): String? = when (r) {
        PermissionResult.Done -> null
        PermissionResult.NoRunner -> NEED_SETUP
        is PermissionResult.Failed -> FAILED
    }

    /**
     * 默认打开、还没生效的那一项下面说一句:没配好手机操作就提示先完成设置(配好后自动打开),
     * 自动打开没成功就照实说;生效了或用户关掉了不多说。
     */
    fun pending(c: RecoveryCapability, consent: RecoveryConsent, paired: Boolean, autoFailed: Set<RecoveryCapability>): String? = when {
        !consent.wants(c) || consent.isApplied(c) -> null
        !paired -> NEED_SETUP
        c in autoFailed -> FAILED
        else -> null
    }

    /** 「替我打开无障碍」下面,上次自动重启无障碍服务成功的时间;没成功时不说,界面顶上的提示照旧给手动处理的办法。 */
    fun restarted(o: RepairOutcome?, zone: ZoneId = ZoneId.systemDefault()): String? =
        o?.takeIf { it.restarted }?.let { tr(R.string.rec_restarted_at, HH_MM.format(Instant.ofEpochMilli(it.atMillis).atZone(zone))) }

    private val HH_MM = DateTimeFormatter.ofPattern("HH:mm")

    /** 「自动打开无线调试」下面,替你打开没成功、先停下的原因;没停下不多说。 */
    fun autoOnBlocked(b: AutoOnBlock?): String? = when (b) {
        null -> null
        AutoOnBlock.NotAllowedHere -> tr(R.string.rec_not_allowed_here)
        AutoOnBlock.Refused -> tr(R.string.rec_refused)
        AutoOnBlock.GaveUp -> tr(R.string.rec_gave_up)
    }

    /** 「重启后自动恢复」下面,上次开机的结果;正常恢复了不多说。 */
    fun lastRestore(o: RestoreOutcome?): String? = when (o) {
        null, RestoreOutcome.Restored -> null
        RestoreOutcome.PermissionMissing -> tr(R.string.rec_permission_missing)
        RestoreOutcome.SettingUnavailable -> tr(R.string.rec_setting_unavailable)
        RestoreOutcome.NotRecovered -> tr(R.string.rec_not_recovered)
    }
}

/** 接上真实状态的几个开关:选择存在 [com.wangzan101.mika.settings.RecoverySettings],执行走已配对的连接。 */
@Composable
fun RecoverySwitches() {
    val context = LocalContext.current
    val phone = remember { PhoneServices.of(context) }
    val settings = phone.recoverySettings
    val consent by settings.consent.collectAsState()
    val shell by phone.shellControl.status.collectAsState()
    val autoFailed by phone.recovery.permissions.autoFailed.collectAsState()
    val lastRestore by settings.lastRestore.collectAsState()
    val repaired by phone.recovery.accessibilityRepair.last.collectAsState()
    val autoOnBlocked by phone.shellManager.wirelessAutoOn.blocked.collectAsState()
    var busy by remember { mutableStateOf<RecoveryCapability?>(null) }
    var message by remember { mutableStateOf<Pair<RecoveryCapability, String>?>(null) }
    val notes = RecoveryCapability.entries.mapNotNull { cap ->
        val m = message
        (if (m?.first == cap) m.second else RecoveryCopy.pending(cap, consent, shell != BuiltinShellStatus.NotPaired, autoFailed))
            ?.let { cap to it }
    }.toMap()
    RecoverySection(
        consent = consent, busy = busy, notes = notes, lastRestore = lastRestore, restartNote = RecoveryCopy.restarted(repaired),
        autoOnNote = RecoveryCopy.autoOnBlocked(autoOnBlocked),
        onToggle = { cap, on ->
            busy = cap
            message = null
            // 不跟页面走:用户离开设置页时命令也要跑完,选择才和系统一致
            phone.pairing.scope.launch {
                val r = phone.recovery.permissions.set(cap, on)
                message = RecoveryCopy.result(r)?.let { cap to it }
                busy = null
            }
        },
    )
}

/**
 * 不带状态的几个开关,便于测试。开关显示用户要不要这一项(没亲手关掉就是打开),拨动直接执行,不再确认;
 * [notes] 是某一项下面的一行提示(拨动的结果、要先完成设置、自动打开没成功);
 * [restartNote] 是「替我打开无障碍」下面的一行说明(上次自动重启过无障碍服务);
 * [autoOnNote] 是「自动打开无线调试」下面的一行说明(替你打开没成功、先停下了)。
 */
@Composable
fun RecoverySection(
    consent: RecoveryConsent,
    busy: RecoveryCapability?,
    notes: Map<RecoveryCapability, String>,
    lastRestore: RestoreOutcome?,
    onToggle: (RecoveryCapability, Boolean) -> Unit,
    restartNote: String? = null,
    autoOnNote: String? = null,
) {
    GroupCard {
        RecoveryCapability.entries.forEachIndexed { i, cap ->
            val copy = RecoveryCopy.switch(cap)
            val on = consent.wants(cap)
            val icon = iconOf(cap)
            ListRow(icon, copy.title, if (busy == cap) RecoveryCopy.WORKING else copy.subtitle, first = i == 0) {
                MikaSwitch(on, { want -> onToggle(cap, want) }, Modifier.testTag("recovery-${cap.name}"), enabled = busy == null)
            }
            Note(copy.explain, MikaTheme.colors.ink3)
            notes[cap]?.let { Note(it, MikaTheme.colors.bad) }
            if (cap == RecoveryCapability.Accessibility) restartNote?.let { Note(it, MikaTheme.colors.ink3) }
            if (cap == RecoveryCapability.RestoreAfterReboot && consent.isApplied(cap)) {
                RecoveryCopy.lastRestore(lastRestore)?.let { Note(it, MikaTheme.colors.warn) }
            }
            if (cap == RecoveryCapability.AutoWirelessDebugging && consent.wants(cap)) autoOnNote?.let { Note(it, MikaTheme.colors.warn) }
        }
    }
}

private fun iconOf(c: RecoveryCapability): ImageVector = when (c) {
    RecoveryCapability.Accessibility -> MikaIcons.Hand
    RecoveryCapability.BatteryWhitelist -> MikaIcons.Sun
    RecoveryCapability.RestoreAfterReboot -> MikaIcons.Wrench
    RecoveryCapability.AutoWirelessDebugging -> MikaIcons.Refresh
}

@Composable
private fun Note(text: String, color: Color) {
    Text(text, Modifier.fillMaxWidth().padding(start = 60.dp, end = 16.dp, bottom = 10.dp), style = MikaType.Hint, color = color)
}
