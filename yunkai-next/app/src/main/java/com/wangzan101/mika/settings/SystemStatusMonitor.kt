package com.wangzan101.mika.settings

import android.app.AppOpsManager
import android.app.NotificationManager
import com.wangzan101.mika.task.TaskService
import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.core.shell.BuiltinShellControl
import com.wangzan101.mika.cap.phone.setup.DeveloperSettings
import com.wangzan101.mika.cap.phone.setup.PairingNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import com.wangzan101.mika.a11y.MikaAccessibilityService

/**
 * 实时汇总开始任务需要的系统状态。内置助手、无障碍、开发者开关有回调就跟着变;
 * 通知权限没有回调,界面回到前台时调 [refresh]。
 */
private const val OP_RESTRICTED_SETTINGS = "android:access_restricted_settings"

class SystemStatusMonitor(context: Context, private val shell: BuiltinShellControl) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val _status = MutableStateFlow(read())
    val status: StateFlow<SystemStatus> = _status.asStateFlow()

    private var scope: CoroutineScope? = null
    private val a11ySetting = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        app.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, a11ySetting,
        )
        ScreenAccessibility.instance.onEach { refresh() }.launchIn(s)
        // 引导页要实时跟着系统开关走:用户在系统设置里打开后,回来就看到下一步
        app.contentResolver.registerContentObserver(DeveloperSettings.developerOptionsUri, false, a11ySetting)
        app.contentResolver.registerContentObserver(DeveloperSettings.wirelessDebuggingUri, false, a11ySetting)
        app.contentResolver.registerContentObserver(DeveloperSettings.usbDebuggingUri, false, a11ySetting)
        shell.status.onEach { refresh() }.launchIn(s)
        refresh()
    }

    fun stop() {
        scope?.cancel()
        scope = null
        app.contentResolver.unregisterContentObserver(a11ySetting)
    }

    fun refresh() {
        _status.value = read()
    }

    private fun read() = SystemStatus(
        sdkInt = Build.VERSION.SDK_INT,
        builtinShell = shell.status.value,
        accessibility = accessibilityState(),
        notificationsAllowed = app.getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
        attentionLowered = channelLowered(TaskService.CHANNEL_ATTENTION),
        resultLowered = channelLowered(TaskService.CHANNEL_RESULT),
        // 先建好频道:配对前就能看出它是不是被关了或调低了,也能直接打开它的设置页
        pairingChannel = PairingNotifier.ensureChannel(app).health,
        attentionImportance = channelImportance(TaskService.CHANNEL_ATTENTION),
        resultImportance = channelImportance(TaskService.CHANNEL_RESULT),
        progressImportance = channelImportance(TaskService.CHANNEL_PROGRESS),
        developerOptions = DeveloperSettings.developerOptions(app.contentResolver),
        wirelessDebugging = DeveloperSettings.wirelessDebugging(app.contentResolver),
        usbDebugging = DeveloperSettings.usbDebugging(app.contentResolver),
        restrictedSettings = restrictedSettings(),
    )

    private fun channelImportance(id: String): Int? =
        app.getSystemService(NotificationManager::class.java).getNotificationChannel(id)?.importance

    /** 频道已建好、但重要程度低于「弹出」(用户或系统调低的,App 改不回来)。 */
    private fun channelLowered(id: String) = app.getSystemService(NotificationManager::class.java).getNotificationChannel(id)
        ?.let { it.importance < NotificationManager.IMPORTANCE_HIGH } ?: false

    /**
     * 系统会不会拦着不让打开无障碍。按能力判断:安卓 13 起有「受限制的设置」这项权限,读得到且已允许就不拦;
     * 读不到(系统没有这项或不让读)时当作可能会拦,只是多给一个入口。
     */
    private fun restrictedSettings(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val mode = runCatching {
            app.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(OP_RESTRICTED_SETTINGS, Process.myUid(), app.packageName)
        }.getOrNull()
        return mode != AppOpsManager.MODE_ALLOWED
    }

    private fun accessibilityState(): AccessibilityState {
        if (ScreenAccessibility.instance.value != null) return AccessibilityState.On
        val enabled = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').mapNotNull { ComponentName.unflattenFromString(it.trim()) }
        val me = ComponentName(app, MikaAccessibilityService::class.java)
        return if (me in enabled) AccessibilityState.EnabledNotRunning else AccessibilityState.Off
    }
}
