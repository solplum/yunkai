package com.wangzan101.mika.cap.phone.setup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wangzan101.mika.cap.phone.PhoneServices
import kotlinx.coroutines.launch

/**
 * 开机广播。只有用户打开了「重启后自动恢复」、并且 Mika 还拿着修改系统设置的授权时才动手,
 * 其余情况什么都不做(判断在 [BuiltinShellRecovery.restoreAfterBoot])。
 */
open class BootRestoreHandler : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val phone = PhoneServices.of(context)
        phone.pairing.scope.launch {
            try {
                runCatching { phone.recovery.restoreAfterBoot() }
            } finally {
                pending.finish()
            }
        }
    }
}
