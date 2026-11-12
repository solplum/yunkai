package com.wangzan101.mika.setup

import com.wangzan101.mika.cap.phone.setup.BootRestoreHandler
import com.wangzan101.mika.task.TriggerRuntime
import android.content.Context
import android.content.Intent
import android.app.AlarmManager

/** 开机广播的入口:清单里登记的是这个全名;实现在手机能力里。 */
class BootRestoreReceiver : BootRestoreHandler() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_TIME_CHANGED ||
            intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ||
            intent.action == Intent.ACTION_TIMEZONE_CHANGED) TriggerRuntime.rearm(context)
    }
}
