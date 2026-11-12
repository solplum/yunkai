package com.wangzan101.mika.cap.phone.setup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.provider.Settings
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.core.shell.A11yObservation
import com.wangzan101.mika.core.shell.AccessibilityList
import com.wangzan101.mika.core.shell.AccessibilityRepair
import com.wangzan101.mika.core.shell.BootRestore
import com.wangzan101.mika.core.shell.BuiltinShellControl
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.core.shell.PermissionCommands
import com.wangzan101.mika.core.shell.PermissionResult
import com.wangzan101.mika.core.shell.RecoveryCapability
import com.wangzan101.mika.core.shell.RecoveryInputs
import com.wangzan101.mika.core.shell.RecoveryPermissions
import com.wangzan101.mika.core.shell.RecoveryPolicy
import com.wangzan101.mika.core.shell.RestoreOutcome
import com.wangzan101.mika.core.shell.ShellCommandRunner
import com.wangzan101.mika.settings.RecoverySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 内置助手的自动恢复和那几项能力。跑 shell 命令用已配对的连接 [runner](没配对时不给);
 * 能力与开机结果记在 [settings];[control] 是内置助手本身。由 [com.wangzan101.mika.cap.phone.PhoneServices] 建一份,
 * App 启动时由 [start] 开始看着连接。
 */
class BuiltinShellRecovery(
    context: Context,
    private val runner: ShellCommandRunner,
    private val settings: RecoverySettings,
    private val control: BuiltinShellControl,
) {
    private val app = context.applicationContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    val permissions: RecoveryPermissions by lazy {
        RecoveryPermissions({ runner.takeIf { control.status.value != BuiltinShellStatus.NotPaired } }, settings, app.packageName)
    }

    /**
     * 无障碍服务没在运行时自动打开它;结果([AccessibilityRepair.last])给设置页说一句。
     * App 自己有修改系统设置的授权(「重启后自动恢复」给的)就直接写,不必等助手;没有才经助手。
     */
    val accessibilityRepair = AccessibilityRepair(
        restart = { gap -> if (canWriteSecure()) restartDirectly(gap) else permissions.restartAccessibility(gap) },
        consent = { settings.consent.value },
        direct = ::canWriteSecure,
    )

    private fun canWriteSecure() =
        app.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** 和 [RecoveryPermissions.restartAccessibility] 同样的步骤,只是由 App 自己写系统设置:没有就加上,有却没绑定就去掉再加回。 */
    private suspend fun restartDirectly(gapMs: Long): PermissionResult = withContext(Dispatchers.IO) {
        val cr = app.contentResolver
        val me = PermissionCommands.accessibilityService(app.packageName)
        fun read() = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        fun write(list: String) {
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.ifEmpty { null })
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, if (list.isEmpty()) 0 else 1)
        }
        try {
            val current = read()
            if (AccessibilityList.contains(current, me)) {
                write(AccessibilityList.without(current, me))
                withContext(NonCancellable) {
                    delay(gapMs)
                    write(AccessibilityList.with(read(), me))
                }
            } else {
                write(AccessibilityList.with(current, me))
            }
            PermissionResult.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PermissionResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 助手每次变成运行中(刚配对完、掉线后重起、App 启动时本来就配过对、开机自动恢复连上),就把默认打开、
     * 用户没关掉、还没生效的几项补上。这几条路最后都落到同一个状态,所以只在这里看,不在各处分别调。
     * 同时看着无障碍服务:没在运行、又有办法写系统设置(App 自己有授权或助手在运行)时,自动打开它。
     */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch { permissions.applyWhenRunning(control.status) }
        scope.launch { accessibilityRepair.watch(serviceObservations(), control.status) }
    }

    /** 系统列表里有没有 Mika(跟着系统设置的变化)和服务实例是否连着(跟着连上、断开)。 */
    private fun serviceObservations(): StateFlow<A11yObservation> {
        val cr = app.contentResolver
        val me = PermissionCommands.accessibilityService(app.packageName)
        fun enabled() = AccessibilityList.contains(Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), me)
        val listChanges = callbackFlow {
            val observer = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) { trySend(Unit) }
            }
            cr.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer)
            send(Unit)
            awaitClose { cr.unregisterContentObserver(observer) }
        }
        return combine(listChanges, ScreenAccessibility.instance) { _, service -> A11yObservation(enabled(), service != null) }
            .stateIn(scope, SharingStarted.Eagerly, A11yObservation(enabled(), ScreenAccessibility.instance.value != null))
    }

    /** 解除配对:先趁连接还在收回授权,再删配对。 */
    suspend fun unpair() {
        val a11yApplied = settings.consent.value.isApplied(RecoveryCapability.Accessibility)
        val result = permissions.revokeAll()
        // 连接已经不在时,无障碍服务至少能自己关掉;电池白名单和修改系统设置的授权只能靠连接收回
        if (result != PermissionResult.Done && a11yApplied) {
            runCatching { ScreenAccessibility.instance.value?.disableSelf() }
        }
        control.unpair()
    }

    /** 开机后按已生效的「重启后自动恢复」重新打开无线调试并重起助手;不需要动手时返回 null。 */
    suspend fun restoreAfterBoot(): RestoreOutcome? {
        val holds = app.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        when (BootRestore.gate(Build.VERSION.SDK_INT, settings.consent.value, holds)) {
            BootRestore.Gate.Skip -> return null
            BootRestore.Gate.PermissionMissing -> return RestoreOutcome.PermissionMissing.also(settings::recordRestore)
            BootRestore.Gate.Act -> Unit
        }
        val cr = app.contentResolver
        // 能力检测:写入被拒(抛异常/返回 false)或写了读回来不是 1,都当这台手机不支持
        val written = DeveloperSettings.turnOnWirelessDebugging(cr) && DeveloperSettings.wirelessDebugging(cr)
        if (!written) return RestoreOutcome.SettingUnavailable.also(settings::recordRestore)

        val outcome = withTimeoutOrNull(BOOT_BUDGET_MS) {
            RecoveryPolicy.recover(
                inputs = {
                    RecoveryInputs(
                        paired = control.status.value != BuiltinShellStatus.NotPaired,
                        // 不在信任的 Wi-Fi 上时系统会把开关拨回去:每轮重读,拨回去就停
                        wirelessDebugging = DeveloperSettings.wirelessDebugging(cr),
                        status = control.status.value,
                    )
                },
                attempt = { control.retry() },
            )
        }
        val result = if (outcome?.recovered == true) RestoreOutcome.Restored else RestoreOutcome.NotRecovered
        return result.also(settings::recordRestore)
    }

    private companion object {
        /** 开机广播 goAsync 之后约有一分钟;留余量。 */
        const val BOOT_BUDGET_MS = 50_000L
    }
}
