package com.wangzan101.mika.task

import org.junit.Rule
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.DeviceTaskState
import android.content.Context
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.verify.Verifier
import com.wangzan101.mika.shell.IMikaShell
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * 真机测试:用脚本模型驱动 TaskService,在虚拟屏上打开系统设置,检验常亮、静音、被挪走暂停与锁屏等待。
 * 需要内置助手在运行、Mika 无障碍服务已开启。
 */
@RunWith(AndroidJUnit4::class)
class TaskServiceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var shell: IMikaShell
    /**
     * 每个任务一个脚本模型:模型的调用编号、核查次数各任务分开数。共用一个时,上一个任务没取走的调用
     * (核查、收尾)会被下一个任务的等待取到,测试就跑到了任务前面。
     */
    private val models = List(MAX_TASKS) { ScriptedModel() }
    private val handed = AtomicInteger()
    private var started = 0
    /** 最近一次 [start] 开始的那个任务的模型(还没开始任务时是第一个任务要用的)。 */
    private val model: ScriptedModel get() = models[maxOf(0, started - 1)]

    private fun start(goal: String) {
        started++
        TaskService.start(context, goal)
    }
    private var settingsTask = -1
    private lateinit var priorAudio: String

    @Before
    fun setUp() {
        shell = DeviceSupport.shell()
        DeviceSupport.service()
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        priorAudio = shell.getAudioMode(SETTINGS)!!
        testTasks.modelFactory = { _, _ -> models[handed.getAndIncrement().coerceAtMost(MAX_TASKS - 1)] }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
    }

    @After
    fun tearDown() {
        TaskService.cancel(context)
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }
        // 测试中途失败时设置可能还留在主屏:只结束这个测试自己挪过去的任务
        if (settingsTask >= 0 && shell.topTaskId(Display.DEFAULT_DISPLAY) == settingsTask) sh("am force-stop $SETTINGS")
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
    }

    private fun TaskState.running() = this !is TaskState.Succeeded && this !is TaskState.Failed && this !is TaskState.Cancelled

    /** 开任务并让它在虚拟屏上打开设置,返回虚拟屏编号;此时模型正等着第二步。 */
    private fun startWithSettingsOpen(): Int {
        start("测试任务")
        assertEquals(1, model.awaitCall().number)
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(2, model.awaitCall().number)
        val displayId = testTasks.agentDisplayId.value
        assertTrue(displayId > 0)
        settingsTask = shell.topTaskId(displayId)
        assertTrue(settingsTask >= 0)
        return displayId
    }

    /** 交出一个不带可核对值的回答,等任务结束:字面核对没有要对的,不另请核查,结局是办成。[appOpened] 只为调用处读起来清楚。 */
    private fun finishAndAwait(@Suppress("UNUSED_PARAMETER") appOpened: Boolean = true) {
        model.answer("done")
        val end = pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        assertTrue("$end", end is TaskState.Succeeded && end.answer == "done" && !end.doubtful)
        // 状态先一步变成结束,恢复声音等收尾做完后 busy 才变回 false
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }
    }

    private fun awaitState(predicate: (TaskState) -> Boolean): TaskState =
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf(predicate) } ?: error("state never matched: ${DeviceTaskState.state.value}")

    private fun mikaScreenLock(): String? =
        sh("dumpsys power").lines().map { it.trim() }.firstOrNull { "'Mika:task'" in it }

    /** 核查走设置里的轻量模型,而不是主模型:回答里的值没读到过时才请它,退回一次后标成未确认。 */
    @Test
    fun verifierUsesLightModel() {
        val lightChecks = AtomicInteger()
        testTasks.lightModelFactory = { _, _ ->
            object : ChatModel {
                override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
                override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                    assertEquals(Verifier.PROMPT, system)
                    lightChecks.incrementAndGet()
                    return ModelReply(listOf(Part.Text(ScriptedModel.CANNOT_VERIFY)), StopReason.EndTurn, null)
                }
            }
        }
        start("测试任务")
        assertEquals(1, model.awaitCall().number)
        model.feed("phone_observe")
        assertEquals(2, model.awaitCall().number)
        model.answer("电量是 9731 毫安时")
        assertEquals(3, model.awaitCall().number)
        model.answer("电量是 9731 毫安时")
        val end = pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        assertTrue("$end", end is TaskState.Succeeded && !end.verified && end.unconfirmed.isNotEmpty())
        assertEquals(2, lightChecks.get())
        assertEquals(0, model.verifierRequests.get())
    }

    /** 产品路径上核查一定接着:回答里有没读到过的值就请核查;核实不了时结局带 verified=false,通知写明原因。 */
    @Test
    fun verifierAlwaysWired() {
        model.verdict = ScriptedModel.CANNOT_VERIFY
        start("测试任务")
        assertEquals(1, model.awaitCall().number)
        model.feed("phone_observe")
        assertEquals(2, model.awaitCall().number)
        model.answer("电量是 9731 毫安时")
        assertEquals(3, model.awaitCall().number)
        model.answer("电量是 9731 毫安时")
        val end = pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } } as TaskState.Succeeded
        assertFalse(end.verified)
        assertEquals(2, model.verifierRequests.get())
        assertEquals("办完了,但没能核实", TaskService.describe(end).first)
    }

    /** 不打开 App 的任务不建虚拟屏:读屏是空屏并提示先 phone_launch_app,写操作失败 no_app_open,没有画面。 */
    @Test
    fun noDisplayUntilLaunchApp() {
        start("你好")
        assertEquals(1, model.awaitCall().number)
        model.feed("phone_observe")
        val second = model.awaitCall()
        assertTrue(second.messages.last().toString(), "Start with phone_launch_app" in second.messages.last().toString())
        model.feed("phone_tap", """{"x":500,"y":500}""")
        val third = model.awaitCall()
        assertTrue(third.messages.last().toString(), "no_app_open" in third.messages.last().toString())
        assertEquals(-1, testTasks.agentDisplayId.value)
        assertEquals(null, DeviceTaskState.frames.value)
        finishAndAwait(appOpened = false)
        assertEquals(-1, testTasks.agentDisplayId.value)

        // 第一次 phone_launch_app 才建屏,而且只建一块。第二个任务有它自己的脚本模型,调用从 1 数起
        start("测试任务")
        assertEquals(1, model.awaitCall().number)
        assertEquals(-1, testTasks.agentDisplayId.value)
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(2, model.awaitCall().number)
        val first = testTasks.agentDisplayId.value
        assertTrue(first > 0)
        settingsTask = shell.topTaskId(first)
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(3, model.awaitCall().number)
        assertEquals(first, testTasks.agentDisplayId.value)
        assertNotNull(pollUntil(5_000) { DeviceTaskState.frames.value })
        finishAndAwait()
        assertEquals(-1, testTasks.agentDisplayId.value)
    }

    @Test
    fun openingSameAppPauses() {
        val displayId = startWithSettingsOpen()
        // 静音在旁路协程里跟着打开的 App 做,稍等
        assertEquals("ignore", pollUntil(5_000) { shell.getAudioMode(SETTINGS)?.takeIf { it == "ignore" } } ?: shell.getAudioMode(SETTINGS))

        // 模拟用户在主屏打开了同一个 App:任务被挪到主屏
        assertTrue(shell.moveTask(settingsTask, Display.DEFAULT_DISPLAY))
        // 模型还在「推理」(没走到 gate)时就应立即显示暂停
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.MOVED_AWAY)), awaitState { it is TaskState.Held })
        model.feed("phone_back")
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.MOVED_AWAY)), awaitState { it is TaskState.Held })
        // 暂停期间不自动挪回
        Thread.sleep(1_500)
        assertEquals(settingsTask, shell.topTaskId(Display.DEFAULT_DISPLAY))

        TaskService.resume(context)
        assertEquals(3, model.awaitCall().number)
        assertEquals(settingsTask, pollUntil(5_000) { shell.topTaskId(displayId).takeIf { it == settingsTask } })
        assertTrue(shell.topTaskId(Display.DEFAULT_DISPLAY) != settingsTask)

        finishAndAwait()
        assertEquals(priorAudio, shell.getAudioMode(SETTINGS))
    }

    /**
     * 只看 dumpsys power,不改任何全局设置:临时关掉「插 USB 常亮」去等真实熄屏超时,
     * 在上次用户操作已久时会让主屏立刻熄灭并上锁(实测踩过),无人值守时不可接受。
     * 锁被系统禁用(例如后台限制)时 dumpsys 会标 DISABLED,这里一并排除。
     */
    @Test
    fun keepsScreenOnDuringTask() {
        startWithSettingsOpen()
        val line = mikaScreenLock()
        assertNotNull("Mika holds no screen wake lock while running", line)
        // 有的系统在类型前加锁对象编号(mLock:<id>),只认类型字样
        assertTrue(line!!, "SCREEN_BRIGHT_WAKE_LOCK" in line)
        assertFalse(line, "DISABLED" in line)
        val summary = sh("dumpsys power").lines().map { it.trim() }.first { it.startsWith("mWakeLockSummary=") }
        // 摘要是位掩码(0x..),AOSP 的 WAKE_LOCK_SCREEN_BRIGHT = 1 shl 1;部分版本打印成文字
        val bits = Regex("0x([0-9a-fA-F]+)").find(summary)?.groupValues?.get(1)?.toInt(16)
        assertTrue(summary, if (bits != null) bits and (1 shl 1) != 0 else "SCREEN_BRIGHT" in summary)

        finishAndAwait()
        val released = pollUntil(3_000) { if (mikaScreenLock() == null) true else null }
        assertTrue("screen wake lock still held after the task: ${mikaScreenLock()}", released == true)
    }

    /**
     * 停止:界面当场是已停止,随后补上一段交代当作这一轮的回答(结局照旧是取消)。交代请轻量模型写,不给工具;
     * 轻量模型出错时换主模型,主模型也写不出就按记录直接拼。
     */
    @Test
    fun stopLeavesASummaryAsTheAnswer() {
        val asked = AtomicInteger()
        val gate = java.util.concurrent.CountDownLatch(1)
        testTasks.lightModelFactory = { _, _ ->
            object : ChatModel {
                override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
                override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                    // 开局判意图的那一问答「要操作」,别的轻量调用只有写交代这一次
                    if (system == INTENT) return ModelReply(listOf(Part.Text("ACT")), StopReason.EndTurn, null)
                    if (system != SUMMARY) throw IllegalStateException("not for this test")
                    assertTrue(tools.isEmpty())
                    asked.incrementAndGet()
                    // 交代先别写完:看得到「正在整理」
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { gate.await(10, java.util.concurrent.TimeUnit.SECONDS) }
                    return ModelReply(listOf(Part.Text("停止前打开了设置,还没看任何页面。")), StopReason.EndTurn, null)
                }
            }
        }
        startWithSettingsOpen()
        val conversation = DeviceTaskState.conversationId.value!!
        TaskService.cancel(context)
        assertEquals(TaskState.Cancelled, awaitState { !it.running() })
        val runId = pollUntil(5_000) { DeviceTaskState.endedRunIds.value[conversation] }!!
        assertTrue(runId in pollUntil(5_000) { DeviceTaskState.summarizing.value.takeIf { runId in it } }.orEmpty())
        val store = com.wangzan101.mika.chat.ConversationStore.shared(context)
        // 先记下「已取消」,停止不等交代
        val placeholder = pollUntil(5_000) { store.turns(conversation).lastOrNull { it.runId == runId } }!!
        assertEquals("cancelled" to "已取消", placeholder.status to placeholder.outcome)
        gate.countDown()
        val turn = pollUntil(15_000) { store.turns(conversation).lastOrNull { it.runId == runId && it.outcome != "已取消" } }!!
        assertEquals("停止前打开了设置,还没看任何页面。", turn.outcome)
        assertEquals("cancelled", turn.status)
        assertEquals(1, asked.get())
        assertNotNull(pollUntil(5_000) { DeviceTaskState.summarizing.value.takeIf { runId !in it } })
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }

        // 轻量模型出错、主模型交白卷:按记录直接拼
        testTasks.lightModelFactory = { _, _ ->
            object : ChatModel {
                override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
                override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
                    if (system == INTENT) ModelReply(listOf(Part.Text("ACT")), StopReason.EndTurn, null) else throw IllegalStateException("down")
            }
        }
        startWithSettingsOpen()
        val second = DeviceTaskState.conversationId.value!!
        val secondRun = pollUntil(5_000) { DeviceTaskState.run(second)?.runId?.value }!!
        TaskService.cancel(context)
        assertEquals(TaskState.Cancelled, awaitState { !it.running() })
        val plain = pollUntil(20_000) { store.turns(second).lastOrNull { it.runId == secondRun && it.outcome != "已取消" } }!!
        assertTrue(plain.outcome, plain.outcome.startsWith("已经停下,停止前做了这些:"))
        assertEquals(1, model.summaryRequests.get())
    }

    // 绝不能在无人值守时启用:它会发 KEYCODE_SLEEP 让手机熄屏上锁,只有人能输入密码解锁。
    // 只在用户本人在场、准备好解锁时手动去掉 @Ignore 运行。
    @Ignore("needs user to unlock: pressing power locks the phone and only a person can unlock it")
    @Test
    fun lockPausesAndUnlockResumes() {
        startWithSettingsOpen()
        sh("input keyevent KEYCODE_SLEEP")
        model.feed("phone_back")
        assertEquals(TaskState.Held(TaskGate.LOCKED), awaitState { it == TaskState.Held(TaskGate.LOCKED) })
        // 由人解锁;解锁后应自动继续,不需要点继续
        val resumed = pollUntil(120_000) { DeviceTaskState.state.value.takeIf { it is TaskState.Running } }
        assertNotNull(resumed)
        assertEquals(3, model.awaitCall().number)
        finishAndAwait()
    }

    private companion object {
        const val MAX_TASKS = 4
        val INTENT by lazy { com.wangzan101.mika.core.prompt.Prompts.role("intent_gate") }
        val SUMMARY by lazy { com.wangzan101.mika.core.prompt.Prompts.role("stop_summary") }
    }
}
