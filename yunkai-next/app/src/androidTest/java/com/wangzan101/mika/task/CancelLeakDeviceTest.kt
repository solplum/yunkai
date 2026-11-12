package com.wangzan101.mika.task

import com.wangzan101.mika.DeviceTaskState
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * 真机测试:任务刚开始建虚拟屏或网页显示就被取消,反复 20 次,收尾后系统里不留 Mika 的显示。
 * 取消的时机错开,落在建屏前后的不同位置。需要内置助手在运行、Mika 无障碍服务已开启。
 */
@RunWith(AndroidJUnit4::class)
class CancelLeakDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val models = List(ROUNDS) { ScriptedModel() }
    private val handed = AtomicInteger()

    @Before
    fun setUp() {
        DeviceSupport.shell()
        DeviceSupport.service()
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        testTasks.modelFactory = { _, _ -> models[handed.getAndIncrement().coerceAtMost(ROUNDS - 1)] }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
    }

    @After
    fun tearDown() {
        TaskService.cancel(context)
        pollUntil(15_000) { DeviceTaskState.busy.value.takeIf { !it } }
        sh("am force-stop $SETTINGS")
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
    }

    @Test
    fun quickCancelLeavesNoDisplay() {
        repeat(ROUNDS) { i ->
            val model = models[i]
            TaskService.start(context, "测试任务 $i")
            assertEquals(1, model.awaitCall().number)
            // 轮流建虚拟屏、网页显示
            if (i % 2 == 0) model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
            else model.feed("web_navigate", """{"url":"https://example.com/"}""")
            Thread.sleep((i / 2 % 5) * 40L)
            TaskService.cancel(context)
            assertNotNull("round $i did not end", pollUntil(15_000) { DeviceTaskState.busy.value.takeIf { !it } })
        }
        val displays = sh("dumpsys display")
        assertFalse("Mika agent display left behind", "mika-agent" in displays)
        assertFalse("Mika web display left behind", "mika-web" in displays)
    }

    private companion object {
        const val ROUNDS = 20
    }
}
