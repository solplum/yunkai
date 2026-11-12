package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.DeviceTaskState
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.task.ScriptedModel
import com.wangzan101.mika.task.TaskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.UUID
import com.wangzan101.mika.task.testTasks
import com.wangzan101.mika.task.TaskEnvironment

/**
 * 沙箱接进任务:脚本模型在任务里写文件、跑命令、跑技能脚本。需要沙箱已装好(设置 → 沙箱 → 安装),没装就跳过。
 * 真模型的一次(-e liveSandbox 1,用已配置的模型)记录它有没有用 run_command。
 */
@RunWith(AndroidJUnit4::class)
class SandboxTaskDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val model = ScriptedModel()
    private val states = Collections.synchronizedList(mutableListOf<TaskState>())
    private val watch = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun setUp() {
        assumeTrue("sandbox not installed", SandboxState.get(context).status.value is SandboxStatus.Ready)
        DeviceSupport.shell()
        DeviceSupport.service()
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        testTasks.modelFactory = { _, _ -> model }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
        watch.launch { DeviceTaskState.state.collect { states += it } }
    }

    @After fun tearDown() {
        watch.cancel()
        TaskService.cancel(context)
        pollUntil(15_000) { DeviceTaskState.busy.value.takeIf { !it } }
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
    }

    private fun lastToolResult(call: ScriptedModel.Call): String =
        call.messages.last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().first().content

    private fun awaitEnd(timeoutMs: Long = 60_000): TaskState? =
        pollUntil(timeoutMs) { DeviceTaskState.state.value.takeIf { it is TaskState.Succeeded || it is TaskState.Failed || it is TaskState.Cancelled } }

    @Test fun scriptedModelRunsCommandInTask() {
        TaskService.start(context, "把 1 到 10 加起来", "sandbox-task-${UUID.randomUUID()}")
        model.awaitCall()
        model.feed("write_file", """{"path":"sum.py","content":"print(sum(range(1, 11)))\n"}""")
        model.awaitCall()
        model.feed("run_command", """{"command":"python3 sum.py"}""")
        val after = model.awaitCall()
        val result = lastToolResult(after)
        assertTrue(result, result.contains("\"output\":\"55\\n\""))
        model.answer("55")
        val end = awaitEnd()
        assertTrue("$end", end is TaskState.Succeeded && "55" in end.answer)
        // 任务卡片上显示过沙箱这一步
        assertTrue(states.toString(), states.any { it is TaskState.Running && it.note.startsWith("正在处理文件:python3 sum.py") })
    }

    @Test fun scriptArgsWithSpacesReachScript() {
        // 测试技能经「打包的那份」加进去:清单和脚本核对都以打包的为准,直接写进沙箱目录的会被换回去
        SkillsSync.extraForTests = mapOf(
            "skills/mika-device-test/scripts/manifest.json" to """[{"file":"scripts/echo.py","runnable":true}]""".toByteArray(),
            "skills/mika-device-test/scripts/echo.py" to "import sys\nfor a in sys.argv[1:]:\n    print(a)\n".toByteArray(),
        )
        try {
            TaskService.start(context, "回显参数", "sandbox-args-${UUID.randomUUID()}")
            model.awaitCall()
            model.feed("run_bundled_script", """{"source":"skills/mika-device-test","script":"scripts/echo.py","args":["报表 (1).csv","a;rm -rf /","\"q\""]}""")
            val result = lastToolResult(model.awaitCall())
            assertTrue(result, result.contains("\"exitCode\":0"))
            assertTrue(result, result.contains("报表 (1).csv\\na;rm -rf /\\n\\\"q\\\"\\n"))
            model.answer("好了")
            assertNotNull(awaitEnd())
        } finally {
            SkillsSync.extraForTests = emptyMap()
            runBlocking { SkillsSync.sync(SandboxDirs(context)) }
        }
    }

    /** 真模型(已在 App 里配好的那个):算 1 到 100 的平方和,记下有没有用沙箱。只在 -e liveSandbox 1 时跑。 */
    @Test fun liveModelUsesSandboxForArithmetic() {
        assumeTrue("pass -e liveSandbox 1 to run", args.getString("liveSandbox") == "1")
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        TaskService.start(context, "把 1 到 100 的平方和算出来", "sandbox-live-${UUID.randomUUID()}")
        val end = awaitEnd(300_000)
        val tools = states.mapNotNull { (it as? TaskState.Running)?.tool }.distinct()
        Log.i(TAG, "live sandbox run: end=$end tools=$tools usedRunCommand=${"run_command" in tools}")
        assertTrue("$end", end is TaskState.Succeeded && "338350" in end.answer)
        assertEquals(true, tools.isNotEmpty())
    }

    private companion object {
        const val TAG = "SandboxTaskDeviceTest"
    }
}
