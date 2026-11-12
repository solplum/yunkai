package com.wangzan101.mika.ui

import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.DeviceTaskState
import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.task.ScriptedModel
import com.wangzan101.mika.task.TaskService
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.wangzan101.mika.task.testTasks
import com.wangzan101.mika.task.TaskEnvironment

/**
 * 真机:脚本模型跑一个 3 步任务(打开设置、看屏幕、交结果),在结果卡片上点「查看过程」,
 * 时间线上应有 3 行,点有画面的那行能看到当时的缩略图。需要内置助手在运行、Mika 无障碍服务已开启。
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class RunTimelineDeviceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val model = ScriptedModel()
    private val vm get() = (context.applicationContext as MikaApp).chat

    @Before
    fun setUp() {
        DeviceSupport.shell()
        DeviceSupport.service()
        testTasks.modelFactory = { _, _ -> model }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
        compose.runOnUiThread { vm.newConversation() }
    }

    @After
    fun tearDown() {
        TaskService.cancel(context)
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf { it is TaskState.Succeeded || it is TaskState.Failed || it == TaskState.Cancelled || it == TaskState.Idle } }
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
    }

    @Test
    fun opensFromTaskCard() {
        compose.runOnUiThread { assertTrue(vm.send("设置首页有什么", Prerequisites.Check(emptyList()))) }
        model.awaitCall()
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""", say = "先打开系统设置")
        model.awaitCall()
        model.feed("phone_observe", say = "看看设置首页")
        model.awaitCall()
        model.answer("脚本演示:设置首页有网络、电池等。")
        assertNotNull(pollUntil(15_000) { DeviceTaskState.state.value as? TaskState.Succeeded })
        assertNotNull(pollUntil(15_000) { DeviceTaskState.busy.value.takeIf { !it } })

        compose.waitUntil(10_000) { compose.onAllNodesWithText("查看过程").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("查看过程").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("第 1 步").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("共 3 步").assertExists()
        for (n in 1..3) compose.onNodeWithText("第 $n 步").assertExists()
        compose.onNodeWithText("第 4 步").assertDoesNotExist()
        compose.onNodeWithText("办成了").assertExists()

        compose.onAllNodesWithText("看当时的画面")[0].performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasContentDescription("的画面", substring = true)).fetchSemanticsNodes().size == 1
        }
        compose.onAllNodes(hasText("收起画面")).fetchSemanticsNodes().let { assertTrue(it.size == 1) }
    }
}
