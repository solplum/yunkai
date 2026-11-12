package com.wangzan101.mika.task

import android.content.Context
import com.wangzan101.mika.core.trigger.ScheduleAdmin
import com.wangzan101.mika.core.trigger.TriggerDefinition

class LocalScheduleAdmin(private val context: Context) : ScheduleAdmin {
    override fun list(threadId: String): List<TriggerDefinition> = TriggerRuntime.list(context)

    private fun owned(threadId: String, id: String): Boolean = list(threadId).any { it.id == id }

    override fun setEnabled(threadId: String, id: String, enabled: Boolean): Boolean {
        if (!owned(threadId, id)) return false
        TriggerRuntime.setEnabled(context, id, enabled)
        return true
    }

    override fun remove(threadId: String, id: String): Boolean {
        if (!owned(threadId, id)) return false
        TriggerRuntime.remove(context, id)
        return true
    }

    override fun reschedule(threadId: String, id: String, atMs: Long): Boolean {
        if (!owned(threadId, id)) return false
        TriggerRuntime.reschedule(context, id, atMs)
        return true
    }

    override fun runNow(threadId: String, id: String): Boolean {
        if (!owned(threadId, id)) return false
        TriggerRuntime.runNow(context, id)
        return true
    }
}
