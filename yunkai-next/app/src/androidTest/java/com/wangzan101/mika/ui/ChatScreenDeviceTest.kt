// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.ui

import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.DeviceTaskState
import android.app.UiAutomation
import kotlinx.coroutines.runBlocking
import com.wangzan101.mika.cap.sandbox.ShareExporter
import com.wangzan101.mika.cap.sandbox.SandboxState
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.chat.ConversationStore
import androidx.compose.ui.test.onAllNodesWithContentDescription
import android.net.Uri
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import com.wangzan101.mika.ui.viewer.FileViewerActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.task.ScriptedModel
import com.wangzan101.mika.task.TaskService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import com.wangzan101.mika.task.testTasks
import com.wangzan101.mika.task.TaskEnvironment

/**
 * 真机走一遍对话页:脚本模型在虚拟屏上打开设置,检查步骤、小窗(收起、展开、拖动、需要你时自动展开)和结果卡片,
 * 每个阶段截一张图到 App 的外部文件目录 shots/(由开发机 adb pull 出来人工查看)。
 * 需要内置助手在运行、Mika 无障碍服务已开启。
 */
@RunWith(AndroidJUnit4::class)
class ChatScreenDeviceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    // 从界面发起的任务在第一个动作前要另问一次意图;固定答「要操作」,脚本里每一问都是任务循环本身的一步
    private val model = ScriptedModel().apply { intent = "ACT" }
    private val vm get() = (context.applicationContext as MikaApp).chat
    private val anyway = Prerequisites.Check(emptyList())

    @Before
    fun setUp() {
        DeviceSupport.shell()
        DeviceSupport.service()
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        testTasks.modelFactory = { _, _ -> model }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
        File(context.getExternalFilesDir(null), "shots").mkdirs()
        // 界面状态是整个进程共用的:等上一个测试的任务全部收尾,再换成新对话,免得接上它的结束状态
        DeviceTaskState.runs.value.forEach { it.cancel() }
        pollUntil(20_000) { DeviceTaskState.busy.value.takeIf { !it } }
        // 并行开关是持久设置,别的测试(或中途崩掉的)可能留着 true;每个测试自己决定,默认关
        FeatureFlags.get(context).let { f -> f.setSpeed(f.speedFlags().copy(parallelTasks = false)) }
        // 对话页还挂着上一个对话的结束状态时 newConversation 会被当成「忙」而不执行:等它回到空闲,并确认真换成了空对话
        assertNotNull("composer never went idle", pollUntil(20_000) { true.takeIf { !vm.busy() } })
        compose.runOnUiThread { vm.newConversation() }
        compose.waitForIdle()
        assertTrue("not a fresh conversation: ${vm.ui.value.exchanges}", vm.ui.value.exchanges.isEmpty())
    }

    @After
    fun tearDown() {
        DeviceTaskState.runs.value.forEach { it.cancel() }
        TaskService.cancel(context)
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf { !it.active() } }
        pollUntil(20_000) { DeviceTaskState.busy.value.takeIf { !it } }
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
    }

    private fun TaskState.active() = this !is TaskState.Succeeded && this !is TaskState.Failed && this !is TaskState.Cancelled && this != TaskState.Idle

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(400)
        compose.waitForIdle()
        // 默认的 UiAutomation 会压制其他无障碍服务,Mika 的服务会被断开
        val bmp = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
        File(context.getExternalFilesDir(null), "shots/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** dumpsys 里此刻在前台的那个 Activity 那一行。 */
    private fun topResumed(dump: String): String? =
        dump.lineSequence().firstOrNull { it.contains("topResumedActivity") || it.contains("mResumedActivity") }

    /** 前台那个 Activity 是不是本 App 的 [cls](dumpsys 里写成「包名/.相对类名」)。 */
    private fun onTop(cls: Class<*>): Boolean {
        val line = topResumed(sh("dumpsys activity activities")) ?: return false
        val short = "${context.packageName}/" + cls.name.removePrefix(context.packageName)
        return line.contains("$short ") || line.contains("${context.packageName}/${cls.name} ")
    }

    private fun awaitState(predicate: (TaskState) -> Boolean): TaskState =
        pollUntil(15_000) { DeviceTaskState.state.value.takeIf(predicate) } ?: error("state never matched: ${DeviceTaskState.state.value}")

    @Test
    fun runsTaskWithStepsPipAndResult() {
        shot("01-idle")

        compose.typeAndSend("打开设置看看电池")
        if (vm.ui.value.blocked != null) {
            // 这台手机没配模型等:被拦下的提示也截一张,再绕过检查用脚本模型跑
            shot("02-blocked")
            // 绕过检查直接交给界面状态,输入框里的字界面不会替它清掉;任务开始后输入框不可编辑,所以先清
            compose.onNode(hasSetTextAction()).performTextClearance()
            compose.runOnUiThread { vm.dismissBlocked(); assertTrue(vm.send("打开设置看看电池", anyway)) }
        }

        assertEquals(1, model.awaitCall().number)
        model.feed("phone_launch_app", """{"package":"$SETTINGS","reason":"先打开系统设置"}""")
        assertEquals(2, model.awaitCall().number)
        model.feed("phone_observe", """{"reason":"看看设置首页有什么"}""")
        assertEquals(3, model.awaitCall().number)
        assertNotNull(pollUntil(5_000) { DeviceTaskState.frames.value })
        // 在跑时一行此刻的进展,下面竖线列出做过的事:动作用模型自己的话,读取写看了哪里(不写模型给读取的用意)。
        // 此刻在等模型的第 3 次调用,进展行写「正在处理」,最后一步「看了设置」是当前步
        compose.waitUntil(5_000) { compose.onAllNodesWithText("正在处理").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("看了设置").assertExists()
        compose.onNodeWithText(ChatCopy.BACKGROUND_NOTE).assertExists()
        compose.onNodeWithText("先打开系统设置").assertExists()
        compose.onNodeWithText(ChatCopy.placeholder(ComposerMode.Busy, hasHistory = true)).assertExists()
        assertTrue(vm.ui.value.pip.visible)
        shot("03-running-collapsed")

        compose.onNodeWithContentDescription("云开 的虚拟屏").performClick()
        compose.waitForIdle()
        assertTrue(vm.ui.value.pip.expanded)
        shot("04-running-expanded")
        compose.onNodeWithContentDescription("收起").performClick()
        compose.waitForIdle()

        // 小窗平时在右下角(输入栏上方):往左上甩,应吸到左上角
        val d = context.resources.displayMetrics.density
        val pip = compose.onNodeWithContentDescription("云开 的虚拟屏").fetchSemanticsNode().boundsInWindow
        val x = pip.center.x
        val y = pip.center.y
        sh("input swipe ${x.toInt()} ${y.toInt()} ${(x - 250 * d).toInt()} ${(y - 300 * d).toInt()} 120")
        compose.waitForIdle()
        val moved = compose.onNodeWithContentDescription("云开 的虚拟屏").fetchSemanticsNode().boundsInWindow
        assertTrue("pip did not move up-left: $pip -> $moved", moved.center.x < x && moved.center.y < y)
        shot("05-dragged")

        model.feed("hand_to_user", """{"reason":"captcha","message":"设置里弹出了验证(脚本演示),需要你处理。"}""")
        awaitState { it is TaskState.WaitingTakeover }
        compose.waitForIdle()
        assertTrue(vm.ui.value.pip.expanded)
        compose.onNodeWithText("需要你处理:验证码").assertExists()
        compose.onNodeWithText("已暂停").assertExists()
        shot("06-takeover")

        // 展开的小窗和遮罩盖住了对话里的卡片:先收起再点卡片上的按钮
        compose.onNodeWithContentDescription("收起").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("取消任务").performClick()
        awaitState { it == TaskState.Cancelled }
        // 停止后补上的交代(脚本模型不写,按记录直接拼)排在「已停止。」下面
        compose.onNodeWithText("已停止。").assertExists()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("已经停下,停止前做了这些:", substring = true).fetchSemanticsNodes().isNotEmpty() }
        shot("06b-stopped-summary")

        // 紧接着开新任务:服务可能还在收尾上一个,界面要等它收完再交过去
        compose.runOnUiThread { assertTrue(vm.send("设置里电池是多少", anyway)) }
        model.awaitCall() // 脚本模型的调用编号接着上一个任务往下数
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""", say = "打开设置")
        model.awaitCall()
        model.answer("脚本演示:电池信息在「设置 › 电池」里。")
        awaitState { it is TaskState.Succeeded }
        compose.waitForIdle()
        compose.onNodeWithText("来源", substring = true).assertExists()
        // 取消的那一问和办成的这一问各有一个「查看过程」
        assertEquals(2, compose.onAllNodesWithText("查看过程").fetchSemanticsNodes().size)
        shot("07-done")

        // 侧栏:对话页上返回(侧滑)先打开侧栏;五个入口和最近的对话
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("drawer").assertIsDisplayed()
        compose.onNodeWithText("定时任务").assertIsDisplayed()
        compose.onNodeWithText("记忆与技能").assertIsDisplayed()
        compose.onNodeWithText("浏览器").assertIsDisplayed()
        compose.onNodeWithText("最近").assertIsDisplayed()
        shot("08-drawer")
    }

    /** 附件按钮只在沙箱就绪时出现。 */
    @Test
    fun attachButtonOnlyWhenSandboxReady() {
        val ready = SandboxState.get(context).status.value is SandboxStatus.Ready
        compose.waitForIdle()
        val nodes = compose.onAllNodesWithContentDescription("添加附件").fetchSemanticsNodes()
        assertEquals("sandbox ready=$ready", ready, nodes.isNotEmpty())
    }

    /** 文件卡片显示名字和大小;点开文本文件进 App 里的查看页(不再交给系统选 App),看得到文件内容,返回回到对话。 */
    @Test
    fun fileCardOpens() {
        val ws = SandboxDirs(context).workspace("card-device-test").apply { mkdirs() }
        val f = File(ws, "card.txt").apply { writeText("hello") }
        val name = "mika-card-${System.currentTimeMillis()}.txt"
        val card = runBlocking { com.wangzan101.mika.cap.sandbox.AndroidFdSys.gate.openRead(ws, f.name).use { ShareExporter(context).export(it, name) } }
        val store = ConversationStore(context)
        val id = store.newConversation()
        store.append(id, Turn("导出", "好了", "succeeded"), listOf(card))
        try {
            compose.runOnUiThread { vm.openConversation(id) }
            compose.onNodeWithText(card.name).assertExists()
            compose.onNodeWithText(card.name).performClick()
            val shown = pollUntil(5_000) { true.takeIf { onTop(FileViewerActivity::class.java) } }
            assertNotNull("file viewer did not open: ${topResumed(sh("dumpsys activity activities"))}", shown)
            // 查看页是另一个 Activity,但和对话页在同一个进程里,它的界面这里也查得到:名字在顶栏,内容按原样显示
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("viewer.text").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("hello", substring = true, useUnmergedTree = true).assertIsDisplayed()
            shot("file-viewer")
            sh("input keyevent KEYCODE_BACK")
            val back = pollUntil(5_000) { true.takeIf { onTop(MainActivity::class.java) } }
            assertNotNull("back did not return to the chat page", back)
        } finally {
            context.contentResolver.delete(Uri.parse(card.uri), null, null)
            ws.deleteRecursively()
        }
    }

    /** 并行开关打开:两个对话各跑一个任务,菜单上有小圆点,侧栏「进行中」里点另一个对话切过去。 */
    @Test
    fun otherTaskChipSwitches() {
        val flags = FeatureFlags.get(context)
        val prior = flags.speedFlags()
        flags.setSpeed(prior.copy(parallelTasks = true))
        val models = listOf(ScriptedModel().apply { intent = "ACT" }, ScriptedModel().apply { intent = "ACT" })
        // 模型工厂不只任务开工时取:新对话起标题也在后台另取一次,先后不定。按次数轮换会让第一个任务拿到第二个模型;
        // 改成第一个任务真的问过模型之前都给第一个,之后都给第二个(起标题的那一问哪个模型都固定回答)
        val firstStarted = AtomicBoolean(false)
        testTasks.modelFactory = { _, _ -> if (firstStarted.get()) models[1] else models[0] }
        try {
            compose.runOnUiThread { assertTrue(vm.send("第一个任务", anyway)) }
            assertEquals(1, models[0].awaitCall().number)
            firstStarted.set(true)
            val first = vm.ui.value.conversationId
            compose.runOnUiThread {
                vm.newConversation()
                assertTrue(vm.send("第二个任务", anyway))
            }
            assertEquals(1, models[1].awaitCall().number)
            val second = vm.ui.value.conversationId
            assertTrue(first != second)

            compose.onNodeWithContentDescription("菜单").performClick()
            compose.waitForIdle()
            // 「进行中」既是分段标题,也是另一个对话那一行下面的小字:按位置分开认——标题在上,下面是那一行(标题和小字合在一个可点的节点里)
            val inDrawer = hasAnyAncestor(hasTestTag("drawer"))
            val other = compose.onNode(hasText("第一个任务") and hasText("进行中") and inDrawer)
            other.assertIsDisplayed()
            val header = compose.onNode(hasText("进行中") and !hasText("第一个任务") and inDrawer)
            header.assertIsDisplayed()
            val headerTop = header.fetchSemanticsNode().boundsInWindow.top
            val otherTop = other.fetchSemanticsNode().boundsInWindow.top
            assertTrue("running entry not under the 进行中 header: $headerTop vs $otherTop", otherTop > headerTop)
            other.performClick()
            compose.waitForIdle()
            assertEquals(first, vm.ui.value.conversationId)
            assertEquals("第一个任务", vm.ui.value.exchanges.last().goal)
            shot("parallel-drawer")

            models.forEach { it.answer("done") }
            assertNotNull(pollUntil(20_000) { DeviceTaskState.busy.value.takeIf { !it } })
        } finally {
            DeviceTaskState.runs.value.forEach { it.cancel() }
            pollUntil(15_000) { DeviceTaskState.busy.value.takeIf { !it } }
            flags.setSpeed(prior)
        }
    }
}
