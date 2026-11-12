package com.wangzan101.mika.confirm

import org.junit.Rule
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.DeviceTaskState
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.shell.IMikaShell
import com.wangzan101.mika.task.ScriptedModel
import com.wangzan101.mika.task.TaskService
import android.util.Log
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.wangzan101.mika.task.testTasks
import com.wangzan101.mika.task.TaskEnvironment

/**
 * 真机测试:脚本模型在虚拟屏的设置里申报一次花钱的操作(只是申报,从不真的去做;只有花钱要同意,申报都带金额),
 * 检查高优先级通知的标题、正文和「同意」「不同意」按钮,以及不同意、同意、评测自动拒绝三条路。
 * 需要内置助手在运行、Mika 无障碍服务已开启。
 */
@RunWith(AndroidJUnit4::class)
class ConfirmationDeviceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var shell: IMikaShell
    // 界面发起的任务在第一个工具调用前另问一次意图(没配轻量模型时问的就是这个模型):固定答 ACT,不占脚本
    private val model = ScriptedModel().apply { intent = "ACT" }
    private var appTask = -1

    @Before
    fun setUp() {
        shell = DeviceSupport.shell()
        DeviceSupport.service()
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        testTasks.modelFactory = { _, _ -> model }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
    }

    @After
    fun tearDown() {
        testTasks.evalHooks.reset()
        TaskService.cancel(context)
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }
        if (appTask >= 0 && shell.topTaskId(Display.DEFAULT_DISPLAY) == appTask) sh("am force-stop $SETTINGS")
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
    }

    private fun TaskState.running() = this !is TaskState.Succeeded && this !is TaskState.Failed && this !is TaskState.Cancelled

    private fun awaitState(predicate: (TaskState) -> Boolean): TaskState =
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf(predicate) } ?: error("state never matched: ${DeviceTaskState.state.value}")

    private fun attention(): Notification? = context.getSystemService(NotificationManager::class.java).activeNotifications
        .firstOrNull { it.notification.channelId == TaskService.CHANNEL_ATTENTION && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
        ?.notification

    private fun lastToolResult(call: ScriptedModel.Call): String =
        call.messages.last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().single().content

    /** 在虚拟屏上打开设置,然后申报「下单」;返回时任务正等用户同意。 */
    private fun startAndRequest() {
        TaskService.start(context, "测试确认关卡")
        assertEquals(1, model.awaitCall().number)
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(2, model.awaitCall().number)
        appTask = shell.topTaskId(testTasks.agentDisplayId.value)
        model.feed("request_confirmation", """{"action":"order","target":"测试项","content":"买测试项\n第二行","amount":"¥25","app":"$SETTINGS"}""")
        awaitState { it is TaskState.WaitingConfirmation }
    }

    @Test
    fun notificationHasApproveDecline() {
        startAndRequest()
        val n = pollUntil(5_000) { attention()?.takeIf { it.actions?.size == 2 } } ?: error("no confirmation notification")
        assertEquals("要你同意:下单", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(text, text.startsWith("在 ") && "对象:测试项" in text && "内容:买测试项 第二行" in text && "金额:¥25" in text)
        assertEquals(listOf("同意", "不同意"), n.actions.map { it.title.toString() })
        // 和接管同一个频道。建频道时是高优先级;用户或系统之后可能调低(App 改不回来),这里只要求不低于默认,并记下实际级别
        assertEquals(TaskService.CHANNEL_ATTENTION, n.channelId)
        val importance = context.getSystemService(NotificationManager::class.java).getNotificationChannel(TaskService.CHANNEL_ATTENTION).importance
        Log.i("ConfirmationDeviceTest", "attention channel importance $importance")
        assertTrue("importance $importance", importance >= NotificationManager.IMPORTANCE_DEFAULT)
        // 锁屏上只露出动作,不露对象和内容
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        // 通用提示里本来就有「对象」两个字,要查的是具体的对象和内容没露出来
        val publicText = n.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(publicText, "测试项" !in publicText && "第二行" !in publicText)
        // 系统通知列表里也看得到这两个按钮
        val dump = sh("dumpsys notification --noredact")
        assertTrue("buttons not in dumpsys notification", "同意" in dump && "不同意" in dump)

        // 点在旧通知上的回答(申报对不上)不算数
        context.startService(Intent(context, TaskService::class.java).setAction(TaskService.ACTION_APPROVE)
            .putExtra(ConfirmationNotifier.EXTRA_REQUEST, 12345))
        Thread.sleep(1_000)
        assertTrue(DeviceTaskState.state.value.toString(), DeviceTaskState.state.value is TaskState.WaitingConfirmation)

        TaskService.decline(context, ConfirmationNotifier.requestKey(DeviceTaskState.state.value as TaskState.WaitingConfirmation))
        val result = lastToolResult(model.awaitCall())
        assertTrue(result, "confirmation_declined" in result)
        assertEquals(true, pollUntil(3_000) { if (attention() == null) true else null })
        model.feed("end_not_done", """{"message":"用户没有同意下单,没有做这一步"}""")
        val end = pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        assertTrue(end.toString(), end is TaskState.Failed && "用户没有同意下单" in end.reason)
    }

    @Test
    fun approveButtonAnswersTheLoop() {
        startAndRequest()
        val n = pollUntil(5_000) { attention()?.takeIf { it.actions?.size == 2 } } ?: error("no confirmation notification")
        // 按通知上的「同意」按钮本身发出的 Intent
        n.actions.first { it.title.toString() == "同意" }.actionIntent.send()
        val result = lastToolResult(model.awaitCall())
        assertTrue(result, "\"approved\":true" in result)
        assertTrue(DeviceTaskState.state.value.toString(), DeviceTaskState.state.value !is TaskState.WaitingConfirmation)
        // 同意之后不真的去做:直接结束
        model.feed("end_not_done", """{"message":"测试到此为止"}""")
        pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
    }

    @Test
    fun evalAutoDeclineSkipsTheUser() {
        testTasks.evalHooks.autoDecideConfirmation = { false }
        TaskService.start(context, "测试确认关卡")
        assertEquals(1, model.awaitCall().number)
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(2, model.awaitCall().number)
        appTask = shell.topTaskId(testTasks.agentDisplayId.value)
        model.feed("request_confirmation", """{"action":"send","target":"测试","content":"转账","amount":"¥25","app":"$SETTINGS"}""")
        val result = lastToolResult(model.awaitCall())
        assertTrue(result, "confirmation_declined" in result)
        model.feed("end_not_done", """{"message":"没有同意"}""")
        val end = pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        assertTrue(end.toString(), end is TaskState.Failed)
    }

    /** 第二次申报之后,点第一条通知自己的「同意」按钮不算数。 */
    @Test
    fun firstNotificationCannotApproveSecondRequest() {
        startAndRequest()
        val first = pollUntil(5_000) { attention()?.takeIf { it.actions?.size == 2 } } ?: error("no confirmation notification")
        val firstApprove = first.actions.first { it.title.toString() == "同意" }.actionIntent
        TaskService.decline(context, ConfirmationNotifier.requestKey(DeviceTaskState.state.value as TaskState.WaitingConfirmation))
        assertTrue(lastToolResult(model.awaitCall()).contains("confirmation_declined"))
        // 换一类动作再申报一次(同类同 App 被拒过就不会再问)
        model.feed("request_confirmation", """{"action":"submit","target":"测试项","content":"提交测试项","amount":"¥25","app":"$SETTINGS"}""")
        awaitState { it is TaskState.WaitingConfirmation && it.request.action.name == "submit" }
        pollUntil(5_000) { attention()?.takeIf { it.extras.getCharSequence(Notification.EXTRA_TITLE).toString() == "要你同意:提交" } }
        runCatching { firstApprove.send() } // 旧按钮可能已被系统作废,作废也算通过
        Thread.sleep(1_500)
        assertTrue(DeviceTaskState.state.value.toString(), DeviceTaskState.state.value is TaskState.WaitingConfirmation)
    }
}

