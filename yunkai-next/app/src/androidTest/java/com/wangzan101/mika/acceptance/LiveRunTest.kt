// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
// Modified for Yunkai Next isolated prototype (2026-10-30); see repository-root UPSTREAM.md.
package com.wangzan101.mika.acceptance

import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.wangzan101.mika.DeviceTaskState
import android.app.UiAutomation
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.ui.MainActivity
import com.wangzan101.mika.ui.typeAndSend
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import com.wangzan101.mika.task.testTasks
import com.wangzan101.mika.task.TaskEnvironment

/**
 * 真机验收:用设置里配好的真实模型跑一个任务,只在显式传参时运行,平时整套测试里会被跳过。
 *
 * - 配模型(Key 只经参数传入,写进 App 自己的加密存储,不落别处):
 *   `-e liveKey <key> -e liveBaseUrl <url> -e liveModel <model> [-e liveId <id>]`,运行 [configureProvider]。
 * - 切主模型:`-e liveMain <id>`,同样运行 [configureProvider]。
 * - 跑任务:`-e liveGoal <目标> -e liveRun <记录目录名> [-e liveTimeoutSec 600] [-e liveOnTakeover cancel|handback]
 *   [-e liveMoveAwayAtStep N] [-e liveCancelAtStep N]`,运行 [runGoal]。经由真实界面:在输入框里写目标、点发送。
 *   liveCancelAtStep:到第 N 步时像点停止键一样停掉任务,再等停止后的交代记进对话(写进 result.txt 的 summary)。
 *   liveMoveAwayAtStep:到第 N 步时把虚拟屏上的 App 挪到主屏(模拟用户在主屏打开了同一个 App),
 *   记下暂停状态和通知,几秒后像点通知上的「继续」一样恢复。
 *
 * 记录写到 App 外部文件目录 acceptance/<liveRun>/:log.txt(每步模型说的话、工具调用与结果摘要)、
 * result.txt(模型、目标、结果、步数、用时、最终回答)、虚拟屏每步画面与主屏截图。
 */
@RunWith(AndroidJUnit4::class)
class LiveRunTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args: Bundle = InstrumentationRegistry.getArguments()

    @After
    fun tearDown() {
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
    }

    @Test
    fun configureProvider() {
        val key = args.getString("liveKey")
        val main = args.getString("liveMain")
        assumeTrue(key != null || main != null)
        val store = ProviderStore.get(context)
        var saved: String? = null
        if (key != null) {
            val model = args.getString("liveModel") ?: error("liveModel missing")
            val id = args.getString("liveId") ?: model
            val baseUrl = args.getString("liveBaseUrl") ?: ProviderPresets.all.first().baseUrl
            // 能力按地址对得上的模板取(和在设置里选模板一样,含提示词缓存的方式)
            val preset = ProviderPresets.forBaseUrl(baseUrl) ?: ProviderPresets.all.first()
            store.save(
                ProviderConfig(
                    id, ProviderConfig.Kind.OpenAiCompat, baseUrl,
                    // 看图主模型、看图定位已验证:按参数打开(定位验证只在有实测证据时给)
                    model, preset.capabilities.copy(vision = args.getString("liveVision") == "true",
                        groundingVerified = args.getString("liveGroundingVerified") == "true"), name = id,
                ),
                key,
            )
            check(store.apiKey(id) == key) { "key not stored" }
            saved = id
        }
        // 配置要有用途在用,否则会被当作没人用清掉:没指定主模型时就用刚存的这条
        (main ?: saved)?.let { check(store.choose(ModelRole.Main, RoleChoice.Model(it))) { "cannot assign $it" } }
        // 看图主模型:看图用主模型
        if (args.getString("liveVision") == "true") store.choose(ModelRole.Vision, RoleChoice.SameAsMain)
    }

    @Test
    fun runGoal() {
        val goal = args.getString("liveGoal")
        assumeTrue(goal != null)
        val run = args.getString("liveRun") ?: "run"
        val timeoutMs = (args.getString("liveTimeoutSec")?.toLong() ?: 600) * 1000
        val onTakeover = args.getString("liveOnTakeover") ?: "cancel"
        val moveAwayAt = args.getString("liveMoveAwayAtStep")?.toInt() ?: -1
        val cancelAt = args.getString("liveCancelAtStep")?.toInt() ?: -1
        var cancelled = false
        val dir = File(context.getExternalFilesDir(null), "acceptance/$run").apply { deleteRecursively(); mkdirs() }
        val log = File(dir, "log.txt")
        fun note(s: String) = log.appendText("[${System.currentTimeMillis()}] $s\n")

        DeviceSupport.shell()
        DeviceSupport.service()
        val store = ProviderStore.get(context)
        val cfg = store.configFor(ModelRole.Main) ?: error("no main model configured")
        val recorder = Recorder(::note)
        testTasks.modelFactory = { ctx, s -> TaskEnvironment.defaultModelFactory(ctx, s)?.let { recorder.wrap(it) } }
        note("model=${cfg.model} goal=$goal")

        compose.typeAndSend(goal!!)
        // 发出去就收起键盘:对话页下面的后台屏小画面才露得出来,和真人发完就收键盘一样
        compose.runOnUiThread {
            val w = compose.activity.window
            compose.activity.currentFocus?.clearFocus()
            WindowCompat.getInsetsController(w, w.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        val chat = (context.applicationContext as MikaApp).chat
        chat.ui.value.blocked?.let { error("blocked by prerequisites: $it") }
        // 发出去了,对话里就有这一问;没有就立刻报,不白等到超时
        check(chat.ui.value.exchanges.any { it.goal == goal.trim() }) {
            "send did not go out: composer=${chat.ui.value.composer} exchanges=${chat.ui.value.exchanges.size} task=${DeviceTaskState.state.value}"
        }
        val t0 = System.currentTimeMillis()

        var lastStep = -1
        var takeovers = 0
        var movedAway = false
        var pausesHandled = 0
        var end: TaskState? = null
        var last: TaskState? = null
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            val s = DeviceTaskState.state.value
            if (s != last) {
                note("state $s")
                last = s
            }
            if (s is TaskState.Running && s.step != lastStep) {
                lastStep = s.step
                frame(dir, "step-%02d".format(s.step))
            }
            if (moveAwayAt > 0 && !movedAway && s is TaskState.Running && s.step >= moveAwayAt) {
                val shell = DeviceSupport.shell()
                val task = shell.topTaskId(testTasks.agentDisplayId.value)
                movedAway = true
                note("moving task $task (${shell.packageOfTask(task)}) to the main screen: ${task >= 0 && shell.moveTask(task, 0)}")
            }
            if (cancelAt > 0 && !cancelled && s is TaskState.Running && s.step >= cancelAt) {
                cancelled = true
                note("stopping at step ${s.step}")
                compose.runOnUiThread { (context.applicationContext as MikaApp).chat.cancel() }
            }
            if (s is TaskState.Held && s.reason.resumable && pausesHandled == 0) {
                pausesHandled++
                Thread.sleep(3000)
                val shell = DeviceSupport.shell()
                note("paused; still paused after 3s: ${DeviceTaskState.state.value}; main top task=${shell.topTaskId(0)} pkg=${shell.packageOfTask(shell.topTaskId(0))}")
                note("notifications: " + mikaNotifications())
                mainShot(dir, "paused-main")
                TaskService.resume(context)
                note("resume requested")
            }
            if (s is TaskState.WaitingTakeover && takeovers == 0) {
                takeovers++
                Thread.sleep(800)
                frame(dir, "takeover-agent")
                mainShot(dir, "takeover-main")
                note("takeover requested; action=$onTakeover")
                if (onTakeover == "handback") TaskService.handBack(context) else TaskService.cancel(context)
            }
            if (s is TaskState.Succeeded || s is TaskState.Failed || s == TaskState.Cancelled) {
                end = s
                note("chat status at end: ${chat.ui.value.exchanges.lastOrNull()?.status}")
                break
            }
            // 界面测试的时钟只在测试推它时才走:等的时候一起往前推,对话页的步骤、计时、小画面才会照常刷新(录演示要用)
            compose.mainClock.advanceTimeBy(250)
            Thread.sleep(250)
        }
        val wall = (System.currentTimeMillis() - t0) / 1000.0
        if (end == null) {
            note("timeout, cancelling")
            TaskService.cancel(context)
        }
        Thread.sleep(1500)
        compose.waitForIdle()
        mainShot(dir, "end-main")
        // 停止后的交代:等它当作这一轮的回答出现在对话页
        val summary = if (end == TaskState.Cancelled) DeviceSupport.pollUntil(30_000) {
            chat.ui.value.exchanges.lastOrNull()?.status?.let { it as? com.wangzan101.mika.ui.ExchangeStatus.Cancelled }?.summary
        }.also {
            note("stop summary: $it")
            compose.waitForIdle()
            mainShot(dir, "stopped-summary")
        } else null
        val outcome = when (end) {
            is TaskState.Succeeded -> "succeeded"
            is TaskState.Failed -> "failed"
            TaskState.Cancelled -> if (takeovers > 0) "takeover(cancelled)" else "cancelled"
            else -> "timeout"
        }
        val answer = when (end) {
            is TaskState.Succeeded -> end.answer
            is TaskState.Failed -> end.reason
            else -> ""
        }
        File(dir, "result.txt").writeText(
            "model=${cfg.model}\ngoal=$goal\noutcome=$outcome\nsteps=${recorder.calls}\nmaxStep=$lastStep\n" +
                "takeovers=$takeovers\npauses=$pausesHandled\nwallSec=$wall\ninputTokens=${recorder.inTokens}\noutputTokens=${recorder.outTokens}\n" +
                "answer=$answer\n" + (summary?.let { "summary=$it\n" } ?: ""),
        )
    }

    /** Mika 当前通知的标题、正文和按钮(从 dumpsys notification 里摘)。 */
    private fun mikaNotifications(): String = sh("dumpsys notification --noredact")
        .split("NotificationRecord(").drop(1).filter { "pkg=${context.packageName} " in it }
        .joinToString(" || ") { rec ->
            rec.lines().map { it.trim() }
                .filter { it.startsWith("android.title=") || it.startsWith("android.text=") || it.startsWith("[") && "\"" in it }
                .joinToString(" | ")
        }.take(1500)

    private fun frame(dir: File, name: String) {
        val bmp = DeviceTaskState.frames.value ?: return
        runCatching { File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    private fun mainShot(dir: File, name: String) {
        val bmp = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot() ?: return
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 80, it) }
    }

    /** 只记录,不改动:真实模型的每次回复和上一步的工具结果写进日志。 */
    private class Recorder(private val note: (String) -> Unit) {
        @Volatile var calls = 0
        @Volatile var inTokens = 0
        @Volatile var outTokens = 0

        fun wrap(inner: ChatModel) = object : ChatModel {
            override val capabilities = inner.capabilities
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                messages.lastOrNull()?.parts?.filterIsInstance<Part.ToolResult>()?.forEach {
                    note("  result${if (it.isError) " ERROR" else ""}: ${it.content.take(400).replace('\n', ' ')}")
                }
                messages.lastOrNull()?.parts?.filterIsInstance<Part.Text>()?.forEach {
                    note("  user/tool text: ${it.text.take(300).replace('\n', ' ')}")
                }
                val t = System.currentTimeMillis()
                val reply = try {
                    inner.complete(system, messages, tools)
                } catch (e: Exception) {
                    note("model error: $e")
                    throw e
                }
                calls++
                reply.usage?.let { inTokens += it.inputTokens; outTokens += it.outputTokens }
                note("call#$calls ${System.currentTimeMillis() - t}ms stop=${reply.stopReason}")
                reply.parts.forEach { p ->
                    when (p) {
                        is Part.Text -> note("  say: ${p.text.take(500).replace('\n', ' ')}")
                        is Part.ToolCall -> note("  tool: ${p.name} ${p.argsJson.take(300)}")
                        is Part.Thinking -> note("  think: ${p.text.take(200).replace('\n', ' ')}")
                        else -> Unit
                    }
                }
                return reply
            }
        }
    }
}
