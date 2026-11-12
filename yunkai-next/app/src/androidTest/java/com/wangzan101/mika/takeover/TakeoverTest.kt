package com.wangzan101.mika.takeover

import org.junit.Rule
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.DeviceTaskState
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.shell.IMikaShell
import com.wangzan101.mika.task.ScriptedModel
import com.wangzan101.mika.task.TaskService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.wangzan101.mika.task.testTasks
import com.wangzan101.mika.task.TaskEnvironment

/**
 * 真机测试:脚本模型在虚拟屏的设置子页面上请求接管;「我来处理」把设置挪到主屏并恢复声音,
 * 「处理好了」挪回虚拟屏、重新静音,Agent 下一轮先 observe。
 * 会短暂占用主屏(专用测试机),结束时把设置从主屏关掉。需要内置助手在运行、Mika 无障碍服务已开启。
 */
@RunWith(AndroidJUnit4::class)
class TakeoverTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var shell: IMikaShell
    private val model = ScriptedModel()
    private var settingsTask = -1
    private lateinit var priorAudio: String

    @Before
    fun setUp() {
        shell = DeviceSupport.shell()
        DeviceSupport.service()
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        priorAudio = shell.getAudioMode(SETTINGS)!!
        testTasks.modelFactory = { _, _ -> model }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
    }

    @After
    fun tearDown() {
        TaskService.cancel(context)
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        // 取消时 App 留在主屏(不从用户眼前抽走);测试机上由测试自己关掉
        if (settingsTask >= 0 && shell.topTaskId(Display.DEFAULT_DISPLAY) == settingsTask) sh("am force-stop $SETTINGS")
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
    }

    private fun TaskState.running() = this !is TaskState.Succeeded && this !is TaskState.Failed && this !is TaskState.Cancelled

    private fun awaitState(predicate: (TaskState) -> Boolean): TaskState =
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf(predicate) } ?: error("state never matched: ${DeviceTaskState.state.value}")

    /**
     * 本 App 通知多了时系统会自动加一条组摘要(没有标题和按钮),它可能沿用同一个频道,要跳过;
     * 之前「排在别的测试后面就拿到空标题」就是拿到了这条摘要。
     */
    private fun attentionRecord() = context.getSystemService(NotificationManager::class.java).activeNotifications
        .firstOrNull { it.notification.channelId == TaskService.CHANNEL_ATTENTION && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }

    private fun attention(): Notification? = attentionRecord()?.notification

    private fun attentionActions(): List<String>? = attention()?.actions?.map { it.title.toString() }

    /**
     * 点通知按钮:按钮应打开不显示界面的 [TakeoverActivity](这样通知栏会收起),由它转给任务服务。
     * 测试进程自己 send 按钮的 PendingIntent 会被后台启动 Activity 的限制拦下(从通知栏点击不受限),
     * 所以这里检查按钮的目标后,用同样的 Intent 直接启动它。
     */
    private fun tapAttentionAction(title: String, action: String) {
        val a = attention()?.actions?.firstOrNull { it.title.toString() == title } ?: error("no action $title: ${attentionActions()}")
        assertTrue(a.actionIntent.isActivity)
        context.startActivity(Intent(context, TakeoverActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun attentionTitle(): String? = attention()?.extras?.getCharSequence("android.title")?.toString()

    @Test
    fun handOverMovesToMainAndBack() {
        TaskService.start(context, "测试接管")
        model.awaitCall()
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        model.awaitCall()
        val displayId = testTasks.agentDisplayId.value
        assertTrue(displayId > 0)
        val appTask = shell.topTaskId(displayId)
        assertTrue(appTask >= 0)
        assertEquals("ignore", pollUntil(5_000) { shell.getAudioMode(SETTINGS)?.takeIf { it == "ignore" } })
        // 打开设置的子页面后在上面请求接管。有的系统把子页面开在另一个任务里(App 的登录、付款页也常这样),
        // 接管挪的应是虚拟屏最上面、用户要处理的那个任务
        sh("am start --display $displayId -a android.settings.DISPLAY_SETTINGS")
        settingsTask = pollUntil(3_000) { shell.topTaskId(displayId).takeIf { it != appTask } } ?: appTask
        android.util.Log.i("TakeoverTest", "app task $appTask, sub-page task $settingsTask")

        model.feed("hand_to_user", """{"reason":"captcha","message":"请完成页面上的验证"}""")
        assertEquals(TaskState.WaitingTakeover("captcha", "请完成页面上的验证"), awaitState { it is TaskState.WaitingTakeover })
        assertEquals("需要你处理:验证码", pollUntil(5_000) { attentionTitle() })
        assertEquals(listOf("我来处理", "跳过", "取消任务"), pollUntil(5_000) { attentionActions() })

        // 「我来处理」:设置到了主屏最上面,能出声;这次挪动是有意的,不算被用户拿走
        tapAttentionAction("我来处理", TaskService.ACTION_TAKE_OVER)
        assertEquals(settingsTask, pollUntil(5_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it == settingsTask } })
        assertEquals(priorAudio, pollUntil(5_000) { shell.getAudioMode(SETTINGS)?.takeIf { it == priorAudio } })
        assertEquals(listOf("处理好了", "我自己做完了", "取消任务"),
            pollUntil(5_000) { attentionActions()?.takeIf { it.size == 3 } })
        Thread.sleep(1_500) // 让「被挪走」的轮询至少跑两轮
        assertTrue(DeviceTaskState.state.value.toString(), DeviceTaskState.state.value is TaskState.WaitingTakeover)
        assertEquals(Takeover.Stage.HandedOver, DeviceTaskState.takeoverStage.value)

        // 「处理好了」(界面上的按钮走同一个入口):回到虚拟屏、重新静音,Agent 收到先核实的提示
        TaskService.handBack(context)
        val history = model.awaitCall().messages
        assertEquals(settingsTask, pollUntil(5_000) { shell.topTaskId(displayId).takeIf { it == settingsTask } })
        assertTrue(shell.topTaskId(Display.DEFAULT_DISPLAY) != settingsTask)
        assertEquals("ignore", shell.getAudioMode(SETTINGS))
        // 交回结果就是 hand_to_user 的工具结果:complete,并要求先核实
        val last = history.last()
        assertEquals(Role.Tool, last.role)
        val text = last.parts.filterIsInstance<Part.ToolResult>().single().content
        assertTrue(text, "\"outcome\":\"complete\"" in text && AgentLoop.HANDBACK_NOTE in text)
        assertEquals(true, pollUntil(3_000) { if (attentionActions() == null) true else null })

        model.feed("phone_observe")
        val afterObserve = model.awaitCall().messages
        val result = afterObserve.last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().single()
        assertTrue(result.content, "\"currentApp\":\"$SETTINGS\"" in result.content)

        // 回答引用刚读到的一个元素名:它真读到过,字面核对对得上,结局是已核实的完成
        val quote = Regex("\"name\":\"([^\"\\\\]{2,40})\"").findAll(result.content).map { it.groupValues[1] }
            .firstOrNull { n -> n.count { it.isLetter() } >= 2 && n.none { it.isDigit() } } ?: error("no element name on screen: ${result.content}")
        val answer = "done,设置里有「$quote」"
        model.answer(answer)
        val end = pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        assertTrue("$end", end is TaskState.Succeeded && end.answer == answer && end.verified)
        // 状态先一步变成结束,恢复声音在收尾里做,等 busy 变回 false 再看
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }
        assertEquals(priorAudio, shell.getAudioMode(SETTINGS))
    }

    /** 打开设置并请求接管,等到通知出现;返回 Agent 的虚拟屏编号。 */
    private fun requestTakeover(): Int {
        TaskService.start(context, "测试接管")
        model.awaitCall()
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        model.awaitCall()
        val displayId = testTasks.agentDisplayId.value
        assertTrue(displayId > 0)
        settingsTask = shell.topTaskId(displayId)
        assertTrue(settingsTask >= 0)
        model.feed("hand_to_user", """{"reason":"login","message":"请在设置里看一下"}""")
        awaitState { it is TaskState.WaitingTakeover }
        pollUntil(5_000) { attentionActions() } ?: error("no takeover notification")
        return displayId
    }

    private fun lastToolResult(history: List<Message>): String {
        val last = history.last()
        assertEquals(Role.Tool, last.role)
        return last.parts.filterIsInstance<Part.ToolResult>().single().content
    }

    /** 主屏上设置窗口里第一个可点、不是开关的条目的中心;点它只会进子页面。 */
    private fun clickableOnMainScreen(): Rect? = DeviceSupport.service().windowsOn(Display.DEFAULT_DISPLAY)
        .mapNotNull { it.root }.firstOrNull { it.packageName?.toString() == SETTINGS }
        ?.let { root ->
            fun find(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (n.isClickable && !n.isCheckable && n.isVisibleToUser && !n.text.isNullOrBlank()) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let { c -> find(c)?.let { return it } }
                return null
            }
            find(root)?.let { Rect().also(it::getBoundsInScreen) }
        }

    @Test
    fun skipButtonPresent() {
        requestTakeover()
        assertEquals(listOf("我来处理", "跳过", "取消任务"), attentionActions())
        tapAttentionAction("跳过", TaskService.ACTION_SKIP)
        val text = lastToolResult(model.awaitCall().messages)
        assertTrue(text, "\"outcome\":\"skipped\"" in text && "\"reason\":\"takeover_skipped\"" in text)
        // 没接手过,App 一直在虚拟屏上
        assertTrue(shell.topTaskId(Display.DEFAULT_DISPLAY) != settingsTask)
    }

    @Test
    fun handBackReportsInputCount() {
        requestTakeover()
        tapAttentionAction("我来处理", TaskService.ACTION_TAKE_OVER)
        assertEquals(settingsTask, pollUntil(5_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it == settingsTask } })
        assertEquals(Takeover.Stage.HandedOver, pollUntil(5_000) { DeviceTaskState.takeoverStage.value.takeIf { it == Takeover.Stage.HandedOver } })

        // 用户在主屏上点该 App 两次:进一个条目,返回,再进一次
        repeat(2) { i ->
            val target = pollUntil(5_000) { clickableOnMainScreen() } ?: error("nothing clickable in settings on the main screen")
            sh("input -d ${Display.DEFAULT_DISPLAY} tap ${target.centerX()} ${target.centerY()}")
            Thread.sleep(1_000)
            if (i == 0) {
                sh("input -d ${Display.DEFAULT_DISPLAY} keyevent KEYCODE_BACK")
                Thread.sleep(1_000)
            }
        }

        TaskService.handBack(context)
        val text = lastToolResult(model.awaitCall().messages)
        assertTrue(text, "\"outcome\":\"complete\"" in text)
        val count = Regex("\"inputCount\":(\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: error("no inputCount: $text")
        assertTrue(text, count >= 2)
        assertTrue(text, "\"stoppedAt\":" in text)
    }
}
