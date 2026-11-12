package com.wangzan101.mika.settings

import android.content.Context
import android.content.SharedPreferences
import com.wangzan101.mika.core.shell.RecoveryCapability
import com.wangzan101.mika.core.shell.RecoveryConsent
import com.wangzan101.mika.core.shell.RecoveryConsentStore
import com.wangzan101.mika.core.shell.RestoreOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「手机操作」那几项能力的记录,以及上次开机自动恢复的结果。开机广播和设置页在同一进程里看同一份。
 *
 * 每项两个布尔值:原来的键(如 `accessibility`)记「已生效」,含义和以前一样(以前只在命令成功后写 true);
 * `<键>_declined` 记「用户亲手关掉」。旧版本只有前一个键:写着 false 的分不出是没动过还是关掉过,
 * 一律当没动过——新键不存在就是没关掉过,所以升级不用另做迁移,这几项会在连接可用时按默认打开。
 */
class RecoverySettings(private val prefs: SharedPreferences) : RecoveryConsentStore {
    private val _consent = MutableStateFlow(read())
    override val consent: StateFlow<RecoveryConsent> = _consent.asStateFlow()

    private val _lastRestore = MutableStateFlow(readOutcome())
    val lastRestore: StateFlow<RestoreOutcome?> = _lastRestore.asStateFlow()

    @Synchronized override fun save(consent: RecoveryConsent) {
        val restore = RecoveryCapability.RestoreAfterReboot
        val restoreChanged = consent.isApplied(restore) != _consent.value.isApplied(restore)
        prefs.edit().apply {
            for ((c, key) in KEYS) {
                putBoolean(key, consent.isApplied(c))
                putBoolean(key + DECLINED, !consent.wants(c))
            }
            if (restoreChanged) remove(LAST_RESTORE)
        }.commit()
        _consent.value = consent
        // 重新打开或关掉后,旧的开机结果不再说明问题
        if (restoreChanged) _lastRestore.value = null
    }

    fun recordRestore(outcome: RestoreOutcome) {
        prefs.edit().putString(LAST_RESTORE, outcome.name).commit()
        _lastRestore.value = outcome
    }

    private fun read() = RecoveryConsent(
        applied = KEYS.filter { (_, key) -> prefs.getBoolean(key, false) }.keys,
        declined = KEYS.filter { (_, key) -> prefs.getBoolean(key + DECLINED, false) }.keys,
    )

    private fun readOutcome(): RestoreOutcome? =
        prefs.getString(LAST_RESTORE, null)?.let { n -> RestoreOutcome.entries.firstOrNull { it.name == n } }

    companion object {
        private const val PREFS = "builtin_shell_recovery"
        private val KEYS = mapOf(
            RecoveryCapability.Accessibility to "accessibility",
            RecoveryCapability.BatteryWhitelist to "battery_whitelist",
            RecoveryCapability.RestoreAfterReboot to "restore_after_reboot",
            RecoveryCapability.AutoWirelessDebugging to "auto_wireless_debugging",
        )
        private const val DECLINED = "_declined"
        private const val LAST_RESTORE = "last_restore"

        /** 产品里的那份(由 [com.wangzan101.mika.cap.phone.PhoneServices] 建)读写的偏好文件。 */
        fun prefs(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
