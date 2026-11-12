package com.wangzan101.mika.about

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.setup.DeveloperSettings
import com.wangzan101.mika.cap.phone.setup.PairingChannel
import com.wangzan101.mika.cap.sandbox.SandboxState
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.shell.PairingDiscovery
import com.wangzan101.mika.core.shell.PermissionCommands
import com.wangzan101.mika.core.shell.SetupInputs
import com.wangzan101.mika.core.shell.SetupStep
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.SystemStatus
import com.wangzan101.mika.task.TaskService

/** 引导页的输入:由系统状态加上引导页自己记的几项(用户确认过的、跳过的、回头重做的)组成。引导页和诊断信息共用这一份。 */
fun SystemStatus.setupInputs(
    discovery: PairingDiscovery,
    repair: Boolean,
    accessibilitySkipped: Boolean = false,
    confirmed: Set<SetupStep> = emptySet(),
    revisit: SetupStep? = null,
) = SetupInputs(
    developerOptions = developerOptions, wirelessDebugging = wirelessDebugging,
    status = builtinShell, discovery = discovery, notificationsAllowed = notificationsAllowed,
    pairingChannel = pairingChannel, repair = repair,
    usbDebugging = usbDebugging, accessibility = accessibility == AccessibilityState.On,
    accessibilitySkipped = accessibilitySkipped, restrictedSettings = restrictedSettings,
    confirmed = confirmed, revisit = revisit,
)

/**
 * 从 App 里各处现成的状态收一份 [DiagnosticsSnapshot]:系统状态、内置助手、自动恢复、模型配置、运行记录、检查更新。
 * 要读盘(运行记录),在后台线程调。[setup] 是引导页此刻的输入;不在引导页时按系统状态现建一份(没有用户确认过的步骤)。
 */
object DiagnosticsCollector {
    private const val RECENT_RUNS = 3

    fun collect(context: Context, status: SystemStatus, setup: SetupInputs? = null): DiagnosticsSnapshot {
        val app = context.applicationContext as MikaApp
        val phone = PhoneServices.of(app)
        val inputs = setup ?: status.setupInputs(phone.shellControl.pairingDiscovery.value, phone.pairing.repairing.value)
        return DiagnosticsSnapshot(
            generatedAtMs = System.currentTimeMillis(),
            app = DiagnosticsSnapshot.App(BuildConfig.VERSION_NAME, BuildConfig.GIT_HASH, installer(app), language(app)),
            device = DiagnosticsSnapshot.Device(
                Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList(),
                sandbox(SandboxState.get(app).status.value),
            ),
            setup = inputs,
            phone = DiagnosticsSnapshot.Phone(
                accessibility = status.accessibility,
                wirelessDebuggingSetting = runCatching {
                    Settings.Global.getString(app.contentResolver, DeveloperSettings.ADB_WIFI_ENABLED)?.let { it == "1" }
                }.getOrNull(),
                connect = phone.shellManager.lastConnectCandidates,
                pairingChannels = PairingChannel.IDS.mapNotNull { id -> channel(app, id)?.let { id to it } }.toMap(),
                attentionChannelImportance = channel(app, TaskService.CHANNEL_ATTENTION),
                wifiConnected = wifi(app),
                lastError = phone.shellManager.engine.lastError,
            ),
            recovery = DiagnosticsSnapshot.Recovery(
                consent = phone.recoverySettings.consent.value,
                writeSecureSettings = app.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED,
                ignoringBatteryOptimizations = runCatching {
                    app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)
                }.getOrNull(),
                lastRestore = phone.recoverySettings.lastRestore.value?.name,
            ),
            models = models(app),
            runs = runs(app),
            update = app.updates.state.value.let { u ->
                DiagnosticsSnapshot.Update(u.status.name, u.latest?.version, u.update?.version)
            },
        )
    }

    private fun installer(context: Context): String? = runCatching {
        context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
    }.getOrNull()

    private fun language(context: Context): String {
        val shown = context.resources.configuration.locales.takeUnless { it.isEmpty }?.get(0)?.toLanguageTag() ?: "?"
        return "${AppLanguage.current(context).name} ($shown)"
    }

    private fun sandbox(s: SandboxStatus): String = when (s) {
        is SandboxStatus.Unsupported -> "Unsupported: ${s.reason}"
        is SandboxStatus.Installing -> "Installing ${s.percent}%"
        is SandboxStatus.Ready -> "Ready"
        is SandboxStatus.Broken -> "Broken: ${s.reason}"
        SandboxStatus.NotInstalled -> "NotInstalled"
    }

    private fun channel(context: Context, id: String): Int? =
        runCatching { context.getSystemService(NotificationManager::class.java).getNotificationChannel(id)?.importance }.getOrNull()

    private fun wifi(context: Context): Boolean? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }.getOrNull()

    private fun models(app: MikaApp): DiagnosticsSnapshot.Models {
        val store = app.providers
        fun role(r: ModelRole) = store.configFor(r)?.let { DiagnosticsSnapshot.RoleModel(it.model, it.baseUrl) }
        val chatGpt = store.chatGptProvider()
        return DiagnosticsSnapshot.Models(
            providers = store.providers.value.map { DiagnosticsSnapshot.Provider(it.kind.name, it.baseUrl, it.models?.size) },
            main = role(ModelRole.Main),
            vision = role(ModelRole.Vision),
            light = role(ModelRole.Light),
            chatGptSignedIn = chatGpt != null && store.hasKey(chatGpt.id),
        )
    }

    /** 最近几次运行:结局和步数取小结,模型取概要;目标、回答、失败原话都不取。 */
    private fun runs(app: MikaApp): List<DiagnosticsSnapshot.Run> = runCatching {
        val store = RunStore(app)
        val models = store.list().take(RECENT_RUNS * 2).associate { it.id to it.model }
        store.metrics.recent(RECENT_RUNS).reversed().map { m ->
            DiagnosticsSnapshot.Run(m.startedAtMs, m.outcome, m.failKind, m.judged, m.steps, models[m.runId])
        }
    }.getOrDefault(emptyList())
}
