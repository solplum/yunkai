package com.wangzan101.mika.task

import com.wangzan101.mika.DeviceTaskState
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.shell.IMikaShell
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 真机测试:开关 parallelTasks 打开时,两个对话的任务同时跑,各占一块虚拟屏。
 * 需要安卓 14+、内置助手在运行、Mika 无障碍服务已开启。脚本模型按开始顺序分给两个任务。
 */
@RunWith(AndroidJUnit4::class)
class ParallelDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var shell: IMikaShell
    private val models = listOf(ScriptedModel(), ScriptedModel())
    private val handedOut = AtomicInteger()
    private lateinit var priorFlags: SpeedFlags

    @Before
    fun setUp() {
        assumeTrue("parallel displays need Android 14+", Build.VERSION.SDK_INT >= ParallelCapability.MIN_SDK)
        shell = DeviceSupport.shell()
        DeviceSupport.service()
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val flags = FeatureFlags.get(context)
        // 杀进程测试的第二次运行:第一次被杀时没跑收尾,开关还开着;要还原成第一次开始前记下的,不是此刻的
        priorFlags = killRecord.takeIf { it.exists() }?.let { runCatching { SpeedFlags.parse(JSONObject(it.readText()).getString(FLAGS_KEY)) }.getOrNull() }
            ?: flags.speedFlags()
        flags.setSpeed(priorFlags.copy(parallelTasks = true))
        testTasks.modelFactory = { _, _ -> models[handedOut.getAndIncrement().coerceAtMost(1)] }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
    }

    @After
    fun tearDown() {
        if (!::priorFlags.isInitialized) return
        DeviceTaskState.runs.value.forEach { it.cancel() }
        pollUntil(15_000) { DeviceTaskState.busy.value.takeIf { !it } }
        FeatureFlags.get(context).setSpeed(priorFlags)
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
    }

    private fun clockPackage(): String =
        context.packageManager.resolveActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS), 0)?.activityInfo?.packageName
            ?: error("no clock app handles ACTION_SHOW_ALARMS")

    /** 开一个新对话的任务,等它的模型第一次被调到;返回对话编号。 */
    private fun startTask(goal: String, model: ScriptedModel): String {
        val before = DeviceTaskState.conversationId.value
        TaskService.start(context, goal)
        assertEquals(1, model.awaitCall().number)
        return pollUntil(5_000) { DeviceTaskState.conversationId.value?.takeIf { it != before } } ?: error("task did not start")
    }

    private fun displayOf(conversationId: String): Int =
        pollUntil(10_000) { DeviceTaskState.run(conversationId)?.displayId?.value?.takeIf { it >= 0 } } ?: -1

    private fun finish(model: ScriptedModel) = model.answer("done")

    private fun ended(conversationId: String): TaskState? {
        val run = DeviceTaskState.run(conversationId) ?: return TaskState.Cancelled
        return run.state.value.takeIf { it is TaskState.Succeeded || it is TaskState.Failed || it is TaskState.Cancelled }
    }

    /** 两个任务同时跑在两块屏上,都能做完;期间用户的主屏不受影响。 */
    @Test
    fun twoTasksRunOnTwoDisplays() {
        val mainBefore = DeviceSupport.mainScreenState(shell)
        val (a, b) = models
        val first = startTask("设置版本号", a)
        val second = startTask("在时钟里新建一个带标记的闹钟", b)
        assertEquals(2, DeviceTaskState.runs.value.size)

        a.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        b.feed("phone_launch_app", """{"package":"${clockPackage()}"}""")
        assertEquals(2, a.awaitCall().number)
        assertEquals(2, b.awaitCall().number)
        val displayA = displayOf(first)
        val displayB = displayOf(second)
        assertTrue(displayA > 0 && displayB > 0)
        assertNotEquals(displayA, displayB)
        assertTrue(shell.topTaskId(displayA) >= 0)
        assertTrue(shell.topTaskId(displayB) >= 0)
        // 两块屏各自操作,主屏的焦点与最上面的任务都不变
        a.feed("phone_back")
        b.feed("phone_back")
        assertEquals(3, a.awaitCall().number)
        assertEquals(3, b.awaitCall().number)
        assertEquals(mainBefore, DeviceSupport.mainScreenState(shell))

        finish(a)
        finish(b)
        assertNotNull(pollUntil(20_000) { ended(first) })
        assertNotNull(pollUntil(20_000) { ended(second) })
        assertNotNull(pollUntil(15_000) { DeviceTaskState.busy.value.takeIf { !it } })
        assertFalse("Mika display left behind", "mika-agent" in sh("dumpsys display"))
    }

    /** 两个任务都要开设置:后开的那个拿到 app_in_use_by_other_task,不把设置从第一个任务手里抢走。 */
    @Test
    fun sameAppSecondTaskGetsConflictError() {
        val (a, b) = models
        val first = startTask("设置版本号", a)
        a.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(2, a.awaitCall().number)
        val displayA = displayOf(first)
        val settingsTask = shell.topTaskId(displayA)

        startTask("设置里看存储", b)
        b.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        val result = b.awaitCall().messages.last().toString()
        assertTrue(result, "app_in_use_by_other_task" in result)
        assertEquals(settingsTask, shell.topTaskId(displayA))

        finish(a)
        finish(b)
        assertNotNull(pollUntil(15_000) { DeviceTaskState.busy.value.takeIf { !it } })
    }

    /**
     * Review Focus 5:两个任务进行中 Mika 进程被杀,下次启动两个包的 PLAY_AUDIO 都恢复,Mika 的虚拟屏都不在了。
     * 测试跑在 Mika 进程里,杀进程会连测试一起结束,所以分两次运行,中间由外面杀进程:
     * 第一次布置好、在 files/parallel-kill-test.json 留下记录(两个包原来的 PLAY_AUDIO 和开始前的开关),然后等着;
     * 看到记录出现后在开发机上跑 `adb shell am force-stop <Mika 包名>`(这次报崩溃)。
     * 第二次(进程重新拉起,启动时做恢复)按记录检查,并把开关还原成记录里的(第一次被杀时没跑收尾)。
     * 注意:force-stop 会把 Mika 从已开启的无障碍服务里去掉(系统行为),第二次运行前要重新开启。
     */
    private val killRecord get() = File(context.filesDir, "parallel-kill-test.json")

    @Test
    fun killDuringTwoTasksRecovers() {
        val record = killRecord
        if (record.exists()) {
            val saved = JSONObject(record.readText())
            record.delete()
            try {
                for (pkg in saved.keys()) {
                    if (pkg == FLAGS_KEY) continue
                    assertEquals(pkg, saved.getString(pkg), pollUntil(60_000) { shell.getAudioMode(pkg)?.takeIf { it == saved.getString(pkg) } })
                }
                assertFalse("Mika display left behind", "mika-agent" in sh("dumpsys display"))
            } finally {
                // 收尾里也会还原;这里先还原,断言失败时开关也不留着开
                FeatureFlags.get(context).setSpeed(priorFlags)
            }
            return
        }
        val clock = clockPackage()
        val prior = JSONObject().put(SETTINGS, shell.getAudioMode(SETTINGS)).put(clock, shell.getAudioMode(clock))
            .put(FLAGS_KEY, priorFlags.describe())
        val (a, b) = models
        startTask("设置版本号", a)
        startTask("在时钟里新建一个带标记的闹钟", b)
        a.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        b.feed("phone_launch_app", """{"package":"$clock"}""")
        assertEquals(2, a.awaitCall().number)
        assertEquals(2, b.awaitCall().number)
        assertEquals("ignore", pollUntil(5_000) { shell.getAudioMode(SETTINGS)?.takeIf { it == "ignore" } })
        assertEquals("ignore", pollUntil(5_000) { shell.getAudioMode(clock)?.takeIf { it == "ignore" } })
        record.writeText(prior.toString())
        // 进程内放到后台的自杀命令不会执行(随测试的 shell 会话一起结束),只能由外面结束:
        // 看到记录文件出现后在开发机上跑 adb shell am force-stop <包名>。等不到就清掉记录、按失败收尾
        Thread.sleep(KILL_WAIT_MS)
        record.delete()
        error("Mika was not killed: run `adb shell am force-stop ${context.packageName}` after ${record.name} appears, then rerun this test")
    }

    private companion object {
        const val FLAGS_KEY = "speed_flags"
        const val KILL_WAIT_MS = 120_000L
    }
}
