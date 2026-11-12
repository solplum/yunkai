package com.wangzan101.mika.core.trigger

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.cap.web.FakeWebPort
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTaskTest {
    @Test fun userCanListAndPauseOwnPlanFromConversation() = runBlocking {
        var paused = false
        val plan = TriggerDefinition("plan-1", "conversation", "天气提醒", 1000, reminderText = "带伞")
        val admin = object : ScheduleAdmin {
            override fun list(threadId: String) = if (threadId == "conversation") listOf(plan) else emptyList()
            override fun setEnabled(threadId: String, id: String, enabled: Boolean): Boolean {
                paused = threadId == "conversation" && id == "plan-1" && !enabled
                return paused
            }
            override fun remove(threadId: String, id: String) = false
            override fun reschedule(threadId: String, id: String, atMs: Long) = false
            override fun runNow(threadId: String, id: String) = false
        }
        val tools = toolDispatcher(FakeScreen(), conversationId = "conversation", scheduleAdmin = admin)
        val listed = tools.execute(Part.ToolCall("l", "schedule_task", """{"op":"list"}"""), ActionPass())
        assertTrue(listed.content.toString().contains("plan-1"))
        val changed = tools.execute(Part.ToolCall("p", "schedule_task",
            """{"op":"pause","triggerId":"plan-1"}"""), ActionPass())
        assertEquals(true, changed.content["changed"]?.toString()?.toBoolean())
        assertTrue(paused)
    }

    @Test fun userRunCanPersistExplicitFutureTask() = runBlocking {
        var saved: TriggerDefinition? = null
        val tools = toolDispatcher(FakeScreen(), conversationId = "conversation", scheduleTrigger = { saved = it; Scheduled(true) })
        assertTrue(tools.definitions().any { it.name == "schedule_task" })
        val result = tools.execute(Part.ToolCall("s", "schedule_task",
            """{"goal":"明天查天气","at":"2026-10-26T09:00:00+08:00","intervalMinutes":1440}"""), ActionPass())
        assertEquals(true, result.content["scheduled"]?.toString()?.toBoolean())
        assertEquals("conversation", saved?.threadId)
        assertEquals(86_400_000L, saved?.intervalMs)
    }

    // 没有准时闹钟权限:照样存下,但结果里明说,模型据此告诉用户,不等到点才悄悄失败
    @Test fun missingExactAlarmPermissionIsReportedAtCreation() = runBlocking {
        var saved: TriggerDefinition? = null
        val tools = toolDispatcher(FakeScreen(), conversationId = "conversation", scheduleTrigger = { saved = it; Scheduled(false, userNotified = true) })
        val result = tools.execute(Part.ToolCall("s", "schedule_task",
            """{"goal":"明天查天气","at":"2026-10-26T09:00:00+08:00"}"""), ActionPass())
        assertEquals(true, result.content["scheduled"]?.toString()?.toBoolean())
        assertEquals(false, result.content["exactAlarm"]?.toString()?.toBoolean())
        assertEquals(true, result.content["userNotified"]?.toString()?.toBoolean())
        assertTrue(result.content["message"].toString().contains("alarms & reminders"))
        assertTrue(saved != null)

        // 这次没发出提示(提示过了或通知被关):结果照实说
        val silent = toolDispatcher(FakeScreen(), conversationId = "conversation", scheduleTrigger = { Scheduled(false) })
            .execute(Part.ToolCall("s", "schedule_task", """{"goal":"明天查天气","at":"2026-10-26T09:00:00+08:00"}"""), ActionPass())
        assertEquals(false, silent.content["userNotified"]?.toString()?.toBoolean())
        assertTrue(silent.content["message"].toString().contains("has not been shown"))

        val granted = toolDispatcher(FakeScreen(), conversationId = "conversation", scheduleTrigger = { Scheduled(true) })
            .execute(Part.ToolCall("s", "schedule_task", """{"goal":"明天查天气","at":"2026-10-26T09:00:00+08:00"}"""), ActionPass())
        assertTrue(granted.content["exactAlarm"] == null)
    }

    @Test fun fixedReminderAndLocalClockRepeatAreSavedWithoutModelWork() = runBlocking {
        var saved: TriggerDefinition? = null
        val tools = toolDispatcher(FakeScreen(), conversationId = "conversation", scheduleTrigger = { saved = it; Scheduled(true) })
        val result = tools.execute(Part.ToolCall("s", "schedule_task",
            """{"goal":"提醒喝水","reminder":"该喝水了","repeatAt":"09:00","repeatZone":"Asia/Shanghai","weekdays":["MON","FRI"]}"""), ActionPass())
        assertEquals(true, result.content["scheduled"]?.toString()?.toBoolean())
        assertEquals("该喝水了", saved?.reminderText)
        assertEquals("09:00", saved?.calendarTime)
        assertEquals(listOf(1, 5), saved?.calendarDays)
        assertTrue(saved!!.nextAtMs > System.currentTimeMillis())
    }

    @Test fun unattendedRunCannotAskOrCreateAnotherSchedule() = runBlocking {
        val spec = RunSpec(RunId("trigger"), "check", "thread", provenance = RunProvenance(RunOrigin.Trigger, Attendance.Unattended))
        val tools = toolDispatcher(FakeScreen(), runSpec = spec, scheduleTrigger = { Scheduled(true) })
        val names = tools.definitions().map { it.name }
        assertFalse("schedule_task" in names)
        assertFalse("ask_user" in names)
        assertFalse("request_confirmation" in names)
        assertFalse("hand_to_user" in names)
        val result = tools.execute(Part.ToolCall("s", "schedule_task", "{}"), ActionPass())
        assertEquals("unknown_tool", result.content["error"]?.toString()?.trim('"'))
    }

    @Test fun conditionRunOnlyOffersReadToolsAndWebNavigation() {
        val spec = RunSpec(RunId("condition"), "check", "thread",
            provenance = RunProvenance(RunOrigin.Trigger, Attendance.Unattended),
            capabilities = setOf("web", "sandbox"), readOnly = true)
        val tools = toolDispatcher(FakeScreen(), web = FakeWebPort(), runSpec = spec, scheduleTrigger = { Scheduled(true) })
        val names = tools.definitions().map { it.name }
        assertTrue("web_read_page" in names)
        assertTrue("web_navigate" in names)
        assertFalse(names.any { it.startsWith("phone_") || it == "run_command" || it == "write_file" || it == "web_interact" })
    }
}
