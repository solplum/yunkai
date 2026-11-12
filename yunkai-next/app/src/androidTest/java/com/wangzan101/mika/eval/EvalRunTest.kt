package com.wangzan101.mika.eval

import com.wangzan101.mika.DeviceTaskState
import android.content.Context
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.confirm.ConfirmationCopy
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.eval.AppCapabilities
import com.wangzan101.mika.core.eval.Check
import com.wangzan101.mika.core.eval.EvalCategory
import com.wangzan101.mika.core.eval.EvalPairing
import com.wangzan101.mika.core.eval.Checks
import com.wangzan101.mika.core.eval.EvalReport
import com.wangzan101.mika.core.record.RunMetrics
import com.wangzan101.mika.core.record.RunMetricsStore
import com.wangzan101.mika.core.eval.EvalTask
import com.wangzan101.mika.core.eval.EvalTasks
import com.wangzan101.mika.core.eval.Observed
import com.wangzan101.mika.core.eval.Prices
import com.wangzan101.mika.core.eval.RunFacts
import com.wangzan101.mika.core.eval.Scoring
import com.wangzan101.mika.core.eval.TaskScore
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.memory.MemoryRuntime
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.task.TaskRun
import com.wangzan101.mika.task.TaskService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import com.wangzan101.mika.core.assembly.recordedTools
import com.wangzan101.mika.task.testTasks

/**
 * 真机评测:经 [TaskService] 的真实路径一题一题地跑任务集,打分并写汇总。只在显式传参时运行。
 *
 * `-e evalRun <名字> [-e evalSet <assets 目录或 id 前缀>] [-e evalPriceIn <每百万输入 token 单价> -e evalPriceOut <输出单价>
 *  [-e evalPriceCached <缓存单价>]] [-e evalOnTakeover cancel|wait] [-e evalAllowIrreversible true] [-e evalFlags none|<键=on,…>]
 *  [-e evalChannel web|app] [-e evalSplit tune|holdout]`
 *
 * 题目来自测试包 assets 的类别目录(仓库 eval/tasks/<类别>/)、网页题目录 web 与冒烟题目录 eval-smoke;不传 evalSet 时跑除冒烟题以外的全部。
 * evalSplit 给了只跑这一池的题(按题目的 partition;不传跑全部)。holdout 池的结果只用于发版决定,不拿来调提示词和知识。
 * evalChannel 给了就把每道题的渠道强制成网页或 App(目标前加「用网页」/「用 App」),对比两条路线。
 * 主模型用设置里已配好的(先用 LiveRunTest#configureProvider 配)。
 * 结果写到 App 外部文件目录 eval/<evalRun>/:summary.md、scores.jsonl、<题 id>/run/(该题完整运行记录)。
 *
 * evalFlags(缺省 none)经 [FeatureFlags.setSpeed] 生效,跑完恢复原值;开关组合写进汇总。
 *
 * `-e evalVariants on`(回放评测,串行):只跑带变体的题;每题先跑原题(办成了就记下路线),再依次跑它的各个变体。
 * 汇总的指标只算变体,原题单列一节;变体的运行记录在 <原题 id>_v<n>/ 下。
 *
 * `-e evalParallel <n>`(n ≥ 2,要同时打开 parallelTasks):按「用到的 App 不相交」贪心分组([EvalPairing]),同组的题同时跑,
 * 汇总加「总墙钟时间」。同组题共用一份自动回答(按提问里的关键字找各题的 answers)和步数上限(取组内最大)。
 * 题因同组另一题占着 App 而没办成时,失败原因记为 [Scoring.INTERFERENCE_PREFIX](单列一类,不算评测器问题)。
 *
 * 评测的每道题用新对话,在询问模式下(开发者选项里自主模式关着)确认关卡生效,要确认时由 [testTasks.evalHooks.autoDecideConfirmation] 自动回答
 * (没接上时等到超时按不同意)。写类、不可逆类题只在接上自动拒绝、并且 requires 都满足时才跑;不可逆类题还要显式传
 * `-e evalAllowIrreversible true`,默认不跑。
 */
@RunWith(AndroidJUnit4::class)
class EvalRunTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args: Bundle = InstrumentationRegistry.getArguments()

    private var savedFlags: SpeedFlags? = null

    @After
    fun tearDown() {
        testTasks.evalHooks.reset()
        savedFlags?.let { FeatureFlags.get(context).setSpeed(it) }
    }

    @Test
    fun runEval() {
        val run = args.getString("evalRun")
        assumeTrue(run != null)
        require(RUN_NAME.matches(run!!)) { "evalRun must match $RUN_NAME" }
        val onTakeover = args.getString("evalOnTakeover") ?: "cancel"
        require(onTakeover == "cancel" || onTakeover == "wait") { "evalOnTakeover must be cancel or wait" }
        testTasks.evalHooks.onTakeover = onTakeover
        // 评测绝不真的发送、删除、下单:关卡处一律拒绝
        testTasks.evalHooks.autoDecideConfirmation = { false }
        // 评测默认不学技能、不自动记忆:跑出来的分数不受上一轮学到的东西影响;要测时显式打开
        testTasks.evalHooks.learnSkills = args.getString("learnSkills") == "on"
        testTasks.evalHooks.rememberMemory = args.getString("memory") == "on"
        testTasks.evalHooks.autoApproveSkillDrafts = args.getString("autoApproveSkills") == "true"
        val allowIrreversible = args.getString("evalAllowIrreversible") == "true"
        val prices = prices()
        val flags = SpeedFlags.parse(args.getString("evalFlags") ?: "none")
        val featureFlags = FeatureFlags.get(context)
        savedFlags = featureFlags.speedFlags()
        featureFlags.setSpeed(flags)

        // 学技能、记忆的状态:默认清空,第③轮(带着②学到的技能跑)传 keepState=true 保留
        if (args.getString("keepState") != "true") MemoryRuntime.of(context).wipeAll()
        val split = args.getString("split")
        require(split == null || split == "train" || split == "test") { "split must be train or test" }
        val evalSplit = args.getString("evalSplit")?.takeIf { it.isNotBlank() }
        require(evalSplit == null || evalSplit in EvalTasks.PARTITIONS) { "evalSplit must be tune or holdout" }
        // evalChannel web|app:同一批题强制走网页或 App(两条路线对比)
        val channel = args.getString("evalChannel")?.takeIf { it.isNotBlank() }
        // 同一 session 的题在同一个对话里按 id 顺序跑,所以按 (session, id) 排好
        val tasks = loadTasks(args.getString("evalSet")).filter { split == null || it.split == split }
            .filter { evalSplit == null || it.partition == evalSplit }
            .map { t -> if (channel != null) t.copy(channel = channel) else t }
            .sortedWith(compareBy({ it.session.orEmpty() }, { it.id }))
        check(tasks.isNotEmpty()) { "no eval tasks matched evalSet=${args.getString("evalSet")}" }
        val errors = EvalTasks.validateAll(tasks)
        check(errors.isEmpty()) { "invalid eval tasks:\n" + errors.joinToString("\n") }

        val ext = context.getExternalFilesDir(null) ?: error("no external files dir")
        val out = File(ext, "eval/$run").apply { deleteRecursively(); mkdirs() }
        DeviceSupport.shell()
        DeviceSupport.service()
        val model = ProviderStore.get(context).configFor(ModelRole.Main)?.model ?: error("no main model configured")
        val probes = EvalProbes(DeviceSupport::sh, File(ext, "eval/logged-in.txt"))
        val runs = RunStore(context)

        val parallel = args.getString("evalParallel")?.toInt() ?: 1
        require(parallel == 1 || flags.parallelTasks) { "evalParallel needs evalFlags parallelTasks=on" }
        val variants = args.getString("evalVariants") == "on"
        // 变体要用原题刚记下的路线,只能一题接一题跑
        require(!variants || parallel == 1) { "evalVariants runs serially; drop evalParallel" }
        val scores = mutableListOf<Pair<TaskScore, Observed>>()
        val skipped = mutableListOf<Pair<String, String>>()
        val scoresFile = File(out, "scores.jsonl")
        val runnable = mutableListOf<EvalTask>()
        for (t in tasks) {
            // 会改动设备或碰到不可逆操作的题:关卡没接上自动拒绝就不跑;不可逆类还要显式放行
            if (t.category in GATED && testTasks.evalHooks.autoDecideConfirmation == null) {
                skipped += t.id to "评测器不跑 ${t.category.wire()} 类题(确认关卡自动拒绝没有接上)"
                continue
            }
            if (t.category == EvalCategory.Irreversible && !allowIrreversible) {
                skipped += t.id to "不可逆类题默认不跑(要跑须传 evalAllowIrreversible=true)"
                continue
            }
            val unmet = t.requires.map(probes::requirementMet).filter { !it.first }.map { it.second }
            if (unmet.isNotEmpty()) {
                skipped += t.id to unmet.joinToString(";")
                continue
            }
            if (variants && t.variants.isEmpty()) {
                skipped += t.id to "回放评测只跑带变体的题"
                continue
            }
            runnable += t
        }
        if (variants) {
            val originals = mutableListOf<Pair<TaskScore, Observed>>()
            for (t in runnable) {
                val (task, observed) = runOne(t, run, probes, runs, File(out, t.id))
                originals += Scoring.score(task, observed) to observed
                scoresFile.appendText(scoreLine(originals.last().first, observed) + "\n")
                for (v in EvalTasks.variantsOf(t)) {
                    val (vt, vo) = runOne(v, run, probes, runs, File(out, v.id.replace('~', '_')))
                    val score = Scoring.score(vt, vo)
                    scores += score to vo
                    scoresFile.appendText(scoreLine(score, vo) + "\n")
                }
            }
            File(out, "summary.md").writeText(EvalReport.markdown(run, model, scores, skipped, prices, flags.describe(), originals = originals))
            File(out, "metrics.json").writeText(EvalReport.metricsJson(scores))
            return
        }
        val t0 = System.currentTimeMillis()
        for (group in EvalPairing.groups(runnable, parallel)) {
            val results = if (parallel == 1) listOf(runOne(group.single(), run, probes, runs, File(out, group.single().id), sessionConversation(group.single())))
                else runGroup(group, run, probes, runs, out)
            for ((task, observed) in results) {
                val score = Scoring.score(task, observed)
                scores += score to observed
                scoresFile.appendText(scoreLine(score, observed) + "\n")
            }
        }
        val total = if (parallel > 1) System.currentTimeMillis() - t0 else null
        File(out, "summary.md").writeText(EvalReport.markdown(run, model, scores, skipped, prices, flags.describe(), total, parallel))
        File(out, "metrics.json").writeText(EvalReport.metricsJson(scores))
        // 学到的技能各被用了几次(对比报告用)
        val skillUse = scores.flatMap { it.second.skillsUsed }.groupingBy { it }.eachCount()
        File(out, "skill-use.json").writeText(JsonObject(skillUse.mapValues { JsonPrimitive(it.value) }).toString())
    }

    /** 同时跑一组题(并行评测);每题各开一个对话,按各自的任务对象盯到结束。 */
    private fun runGroup(group: List<EvalTask>, run: String, probes: EvalProbes, runs: RunStore, out: File): List<Pair<EvalTask, Observed>> {
        DeviceSupport.sh("input keyevent KEYCODE_HOME")
        val prepared = group.map { t ->
            val before = mutableMapOf<String, String>()
            var bad: String? = null
            for ((name, probe) in t.probesBefore) {
                val v = runCatching { probes.run(probe) }.getOrNull()
                if (v.isNullOrBlank() || v == "null") { bad = "探针 $probe 读不到"; break }
                before[name] = v
            }
            if (bad != null) t to bad else EvalTasks.substitute(t, run, before) to null
        }
        val tasks = prepared.filter { it.second == null }.map { it.first }
        testTasks.evalHooks.autoAnswer = { prompt, _ -> tasks.firstNotNullOfOrNull { t -> t.answers.entries.firstOrNull { it.key in prompt }?.value } }
        testTasks.evalHooks.maxSteps = tasks.mapNotNull { it.maxSteps }.maxOrNull()
        if (!waitFor(BUSY_WAIT_MS) { !DeviceTaskState.busy.value }) return group.map { it to harness("上一组任务一直没有收尾") }

        class Live(val task: EvalTask, val conversation: String, var run: TaskRun? = null, var t0: Long = 0, var tookOver: Boolean = false,
                   var timedOut: Boolean = false, var end: TaskState? = null, var wallMs: Long = 0, var failed: String? = null)
        val live = tasks.map { Live(it, UUID.randomUUID().toString()) }
        for (l in live) {
            l.t0 = System.currentTimeMillis()
            TaskService.start(context, l.task.goal, l.conversation)
            if (!waitFor(START_WAIT_MS) { DeviceTaskState.run(l.conversation) != null }) l.failed = "任务没有开始" else l.run = DeviceTaskState.run(l.conversation)
        }
        while (live.any { it.run != null && it.end == null && it.failed == null }) {
            for (l in live) {
                val r = l.run ?: continue
                if (l.end != null || l.failed != null) continue
                val s = r.state.value
                if (s is TaskState.WaitingTakeover && !l.tookOver) {
                    l.tookOver = true
                    if (testTasks.evalHooks.onTakeover == "cancel") r.cancel()
                }
                val timeoutMs = (l.task.timeoutSec ?: DEFAULT_TIMEOUT_SEC) * 1000L
                val elapsed = System.currentTimeMillis() - l.t0
                if (!l.timedOut && elapsed > timeoutMs) { l.timedOut = true; r.cancel() }
                if (l.timedOut && elapsed > timeoutMs + BUSY_WAIT_MS) l.failed = "超时取消后任务一直没有收尾"
                // 收尾做完(运行记录关上)才从登记处撤下
                if (DeviceTaskState.run(l.conversation) == null) {
                    l.end = r.state.value
                    l.wallMs = System.currentTimeMillis() - l.t0
                }
            }
            Thread.sleep(POLL_MS)
        }
        val results = prepared.filter { it.second != null }.map { (t, why) -> t to harness(why!!) }.toMutableList()
        for (l in live) {
            if (l.failed != null) { results += l.task to harness(l.failed!!); continue }
            val record = l.run?.runId?.value?.let { id -> runs.list().firstOrNull { it.id == id } }
            val events = record?.let { runs.events(it.id) }.orEmpty()
            record?.let { runs.copyTo(it.id, File(File(out, l.task.id), "run")) }
            val task = resolveApps(l.task, events, probes)
            val observed = observe(task, l.end!!, events, record != null, l.timedOut, l.tookOver, l.wallMs, probes, record?.id.orEmpty())
            // 没办成、且路上撞到另一题占着 App:单列成互相干扰
            val interfered = observed.outcome != "succeeded" && events.any { it is RunEvent.ToolResult && "app_in_use_by_other_task" in it.resultJson }
            results += task to if (interfered) observed.copy(failReason = Scoring.INTERFERENCE_PREFIX + "同组另一题占着要用的 App") else observed
        }
        return results
    }

    /** 同一 session 的题共用一个对话(记忆题:第二题在第一题的对话里接着说)。 */
    private val sessions = mutableMapOf<String, String>()
    private fun sessionConversation(t: EvalTask): String? = t.session?.let { sessions.getOrPut(it) { UUID.randomUUID().toString() } }

    /**
     * 两轮评测的对比与门槛:`-e evalCompareBase <基线运行名> -e evalCompareTreated <带技能运行名>`,
     * 读两次运行的 scores.jsonl 和带技能那次的 skill-use.json,写 compare.md 到带技能那次的目录。
     */
    @Test
    fun compareRuns() {
        val base = args.getString("evalCompareBase")
        val treated = args.getString("evalCompareTreated")
        assumeTrue(base != null && treated != null)
        val ext = context.getExternalFilesDir(null) ?: error("no external files dir")
        fun read(run: String): List<Pair<TaskScore, Observed>> = File(ext, "eval/$run/scores.jsonl").readLines().filter { it.isNotBlank() }.map { line ->
            val o = Json.parseToJsonElement(line) as JsonObject
            fun b(k: String) = (o[k] as? JsonPrimitive)?.content == "true"
            fun l(k: String) = (o[k] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            TaskScore((o["id"] as JsonPrimitive).content, b("passed"), b("falseSuccess"), b("unverified"), emptyList(), null) to
                Observed((o["outcome"] as? JsonPrimitive)?.content.orEmpty(), "", emptyList(), null, emptyMap(), emptyMap(), l("steps").toInt(), l("wallMs"), 0, 0, 0, null)
        }
        val b = read(base!!)
        val t = read(treated!!)
        val use = runCatching { (Json.parseToJsonElement(File(ext, "eval/$treated/skill-use.json").readText()) as JsonObject).mapValues { (it.value as JsonPrimitive).content.toInt() } }
            .getOrDefault(emptyMap())
        File(ext, "eval/$treated/compare.md").writeText(
            "# 对比 $base → $treated\n\n" + EvalReport.compare(b.map { it.first }, t.map { it.first }, use, b.map { it.second }, t.map { it.second }),
        )
    }

    /** 跑一题,返回替换过占位符的题目和观察到的事实。[conversation] 不为空时在这个对话里接着跑。 */
    private fun runOne(t: EvalTask, run: String, probes: EvalProbes, runs: RunStore, dir: File, conversation: String? = null): Pair<EvalTask, Observed> {
        DeviceSupport.sh("input keyevent KEYCODE_HOME")
        val before = mutableMapOf<String, String>()
        for ((name, probe) in t.probesBefore) {
            val v = runCatching { probes.run(probe) }.getOrNull()
            if (v.isNullOrBlank() || v == "null") return t to harness("探针 $probe 读不到")
            before[name] = v
        }
        val task = EvalTasks.substitute(t, run, before)
        testTasks.evalHooks.autoAnswer = { prompt, _ -> task.answers.entries.firstOrNull { it.key in prompt }?.value }
        testTasks.evalHooks.maxSteps = task.maxSteps

        if (!waitFor(BUSY_WAIT_MS) { !DeviceTaskState.busy.value }) return task to harness("上一个任务一直没有收尾")
        val prevConversation = DeviceTaskState.conversationId.value
        val t0 = System.currentTimeMillis()
        TaskService.start(context, task.goal, conversation)
        // 新开对话时编号变了说明这次请求已被接下;接着同一个对话跑时看服务忙起来
        val started = if (conversation == null) waitFor(START_WAIT_MS) { DeviceTaskState.conversationId.value != prevConversation }
            else waitFor(START_WAIT_MS) { DeviceTaskState.busy.value && DeviceTaskState.conversationId.value == conversation }
        if (!started) return task to harness("任务没有开始")
        val timeoutMs = (task.timeoutSec ?: DEFAULT_TIMEOUT_SEC) * 1000L
        var tookOver = false
        var timedOut = false
        // busy 在状态变成结局、运行记录也关上之后才变回 false
        while (DeviceTaskState.busy.value) {
            val s = DeviceTaskState.state.value
            if (s is TaskState.WaitingTakeover && !tookOver) {
                tookOver = true
                if (testTasks.evalHooks.onTakeover == "cancel") TaskService.cancel(context)
            }
            if (!timedOut && System.currentTimeMillis() - t0 > timeoutMs) {
                timedOut = true
                TaskService.cancel(context)
            }
            if (timedOut && System.currentTimeMillis() - t0 > timeoutMs + BUSY_WAIT_MS) {
                return task to harness("超时取消后任务一直没有收尾")
            }
            Thread.sleep(POLL_MS)
        }
        val wallMs = System.currentTimeMillis() - t0
        val end = DeviceTaskState.state.value

        // 交出回答后另判结局(后台):等它记进运行记录再取,判不成也有一条 unknown;等不到按没判过算
        if (end is TaskState.Succeeded) waitFor(JUDGE_WAIT_MS) {
            runs.list().firstOrNull { it.startedAtMs >= t0 }?.judged != null
        }
        val record = runs.list().firstOrNull { it.startedAtMs >= t0 }
        val events = record?.let { runs.events(it.id) }.orEmpty()
        record?.let { runs.copyTo(it.id, File(dir, "run")) }
        val resolved = resolveApps(task, events, probes)
        return resolved to observe(resolved, end, events, record != null, timedOut, tookOver, wallMs, probes, record?.id.orEmpty())
    }

    /** 跑完后把 `{app:<能力>}` 换成这台手机上的包名:Agent 打开过的候选优先(见 [AppCapabilities.choose])。 */
    private fun resolveApps(task: EvalTask, events: List<RunEvent>, probes: EvalProbes): EvalTask {
        val launched = RunFacts.appsLaunched(events, recordedTools())
        val written = RunFacts.appsWrittenIn(events, recordedTools())
        val chosen = EvalTasks.appCapabilities(task).mapNotNull { cap ->
            AppCapabilities.choose(probes.appCandidates(cap), launched, context.packageName, written)?.let { cap to it }
        }.toMap()
        return EvalTasks.resolveApps(task, chosen)
    }

    /** 一题结束后:按结局、运行记录和结束后的探针整理出观察到的事实。 */
    private fun observe(
        task: EvalTask, end: TaskState, events: List<RunEvent>, hasRecord: Boolean, timedOut: Boolean, tookOver: Boolean,
        wallMs: Long, probes: EvalProbes, runId: String,
    ): Observed {
        val confirmations = events.filterIsInstance<RunEvent.Confirmation>()
        // 停在关卡:最后一次申报被(评测里自动)拒绝,之后 Agent 汇报结束
        val stoppedAtGate = RunFacts.stoppedAtGate(events)
        val outcome = when {
            timedOut -> "timeout"
            stoppedAtGate -> "confirmation"
            // 不带调用的回复就是回答:没核对过的照样算 succeeded(核对项另看回答和账本);核对过却没能核实的算 unverified
            end is TaskState.Succeeded -> if (end.doubtful) "unverified" else "succeeded"
            end is TaskState.Failed -> "failed"
            end == TaskState.Cancelled -> if (tookOver) "takeover" else "cancelled"
            else -> "timeout"
        }
        val screenHas = mutableMapOf<String, Boolean>()
        val webHas = mutableMapOf<String, Boolean>()
        val after = mutableMapOf<String, String>()
        for (c in task.checks) when (c) {
            is Check.ScreenHas -> screenHas[Checks.screenKey(c)] =
                runCatching { runBlocking { probes.screenHas(c.packageName, c.text, c.deepLink) } }.getOrDefault(false)
            is Check.WebHas -> webHas[Checks.webKey(c)] =
                runCatching { runBlocking { probes.webHas(c.url, c.text) } }.getOrDefault(false)
            is Check.ProbeEquals -> after[c.probe] = runCatching { probes.run(c.probe) }.getOrDefault("")
            else -> Unit
        }
        val answer = when (end) {
            is TaskState.Succeeded -> end.answer
            is TaskState.Failed -> end.reason
            else -> ""
        }
        val sources = RunFacts.evidence(events, recordedTools(), task.goal, answer)
        return Observed(
            outcome = outcome,
            answer = answer,
            // 和运行时核查器同一份证据账本、同一个挑法
            evidence = sources,
            today = java.time.LocalDate.now(),
            // 核对的是卡片上显示的字:动作用卡片标题里的中文说法
            confirmation = confirmations.lastOrNull { it.decision != "mismatch" && it.decision != "required" }?.let { c ->
                val action = IrrevAction.entries.firstOrNull { it.name == c.action }?.let(ConfirmationCopy::actionLabel) ?: c.action
                listOfNotNull("action" to action, c.target?.let { "target" to it }, c.content?.let { "content" to it }, c.amount?.let { "amount" to it }).toMap()
            },
            screenHas = screenHas,
            webHas = webHas,
            probes = after,
            steps = events.count { it is RunEvent.ModelReply && it.acted },
            wallMs = wallMs,
            inputTokens = events.filterIsInstance<RunEvent.ModelReply>().sumOf { (it.inputTokens ?: 0).toLong() },
            outputTokens = events.filterIsInstance<RunEvent.ModelReply>().sumOf { (it.outputTokens ?: 0).toLong() },
            cachedTokens = events.filterIsInstance<RunEvent.ModelReply>().sumOf { (it.cachedTokens ?: 0).toLong() },
            // 失败原因按失败来源生成,不用 Agent 的原话
            leftScope = if (task.apps.isEmpty()) emptyList() else RunFacts.appsSeen(events, recordedTools()) - task.apps.toSet(),
            goal = task.goal,
            verdict = events.filterIsInstance<RunEvent.Verifier>().lastOrNull()?.verdict,
            modelCalls = events.count { it is RunEvent.ModelReply },
            modelMs = events.filterIsInstance<RunEvent.ModelReply>().sumOf { it.latencyMs },
            toolMs = events.filterIsInstance<RunEvent.ToolResult>().sumOf { it.durationMs },
            waitMs = events.filterIsInstance<RunEvent.ToolResult>().sumOf { it.waitMs },
            // 一条路线最后一步匹配上算走完;任一步没匹配上算交还
            replayHits = events.filterIsInstance<RunEvent.Replay>().count { it.matched && it.step == it.total },
            replayHandBacks = events.filterIsInstance<RunEvent.Replay>().count { !it.matched },
            memoryUsed = events.filterIsInstance<RunEvent.MemoryUsed>().sumOf { it.ids.size },
            skillsUsed = events.filterIsInstance<RunEvent.SkillUsed>().map { it.name }.distinct(),
            askedUser = events.count { it is RunEvent.ModelReply && it.acted && it.tool == "ask_user" },
            takeoverBarrier = RunFacts.takeoverBarrier(events, recordedTools()),
            judged = events.filterIsInstance<RunEvent.OutcomeJudged>().lastOrNull()?.outcome,
            metrics = RunMetrics.of(events, runId),
            failReason = when {
                timedOut -> null
                end is TaskState.Failed -> Scoring.failReason(end)
                !hasRecord -> Scoring.HARNESS_PREFIX + "找不到这次运行的记录"
                else -> null
            },
        )
    }

    private fun harness(why: String) = Observed(
        "failed", "", emptyList(), null, emptyMap(), emptyMap(), 0, 0, 0, 0, 0, Scoring.HARNESS_PREFIX + why,
    )

    private fun prices(): Prices? {
        val inP = args.getString("evalPriceIn")?.toDouble() ?: return null
        val outP = args.getString("evalPriceOut")?.toDouble() ?: error("evalPriceOut missing")
        return Prices(inP, outP, args.getString("evalPriceCached")?.toDouble() ?: inP)
    }

    /** 测试包 assets 下所有 .json 题目;[set] 是目录(如 eval-smoke、read)或 id 前缀。 */
    private fun loadTasks(set: String?): List<EvalTask> {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun walk(path: String): List<String> {
            val children = assets.list(path).orEmpty()
            if (children.isEmpty()) return if (path.endsWith(".json")) listOf(path) else emptyList()
            return children.sorted().flatMap { walk("$path/$it") }
        }
        // 题目只放在类别目录(eval/tasks/<类别>/)、专项题集、网页题目录和冒烟题目录里;assets 根下还有依赖库带进来的别的 json
        val dirs = EvalCategory.entries.map { it.wire() } + SUITE_DIRS + WEB_DIR + SMOKE_DIR
        return dirs.flatMap(::walk)
            .filter { path -> set != null || (SUITE_DIRS + SMOKE_DIR).none { path.startsWith("$it/") } }
            .map { path ->
                val task = runCatching { EvalTasks.parse(assets.open(path).bufferedReader().use { it.readText() }) }
                    .getOrElse { throw IllegalStateException("cannot parse eval task $path", it) }
                path to task
            }
            .filter { (path, task) -> set == null || path.startsWith("$set/") || task.id.startsWith(set) }
            .map { it.second }
    }

    /** 任务文件和目录里用的类别名。 */
    private fun EvalCategory.wire(): String = when (this) {
        EvalCategory.Read -> "read"
        EvalCategory.Write -> "write"
        EvalCategory.Irreversible -> "irreversible"
        EvalCategory.Impossible -> "impossible"
        EvalCategory.Barrier -> "barrier"
    }

    private fun scoreLine(s: TaskScore, o: Observed): String = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("id", s.id)
        put("passed", s.passed)
        put("falseSuccess", s.falseSuccess)
        put("unverified", s.unverified)
        put("failedChecks", JsonArray(s.failedChecks.map(::JsonPrimitive)))
        put("failureClass", s.failureClass?.name)
        put("outcome", o.outcome)
        put("judged", o.judged)
        put("answer", o.answer.take(2000))
        put("failReason", o.failReason)
        put("verdict", o.verdict)
        put("leftScope", JsonArray(s.leftScope.map(::JsonPrimitive)))
        put("steps", o.steps)
        put("wallMs", o.wallMs)
        put("inputTokens", o.inputTokens)
        put("outputTokens", o.outputTokens)
        put("cachedTokens", o.cachedTokens)
        put("modelCalls", o.modelCalls)
        put("modelMs", o.modelMs)
        put("toolMs", o.toolMs)
        put("waitMs", o.waitMs)
        o.metrics?.let { put("metrics", RunMetricsStore.JSON.encodeToJsonElement(RunMetrics.serializer(), it)) }
    })

    private fun waitFor(timeoutMs: Long, test: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (!test()) {
            if (System.currentTimeMillis() > end) return false
            Thread.sleep(POLL_MS)
        }
        return true
    }

    companion object {
        private val RUN_NAME = Regex("^[A-Za-z0-9_-]+$")
        private const val SMOKE_DIR = "eval-smoke"
        /** 专项题集:配对题(三轮跑)、记忆题。不传 evalSet 时不跑,要显式选。 */
        private val SUITE_DIRS = listOf("skills", "memory")
        /** 网页题目录(eval/tasks/web/),类别各异。 */
        private const val WEB_DIR = "web"
        private const val DEFAULT_TIMEOUT_SEC = 600
        private const val POLL_MS = 250L
        private const val START_WAIT_MS = 15_000L
        private const val BUSY_WAIT_MS = 60_000L
        /** 判结局的模型调用自己有 30 秒上限,两个候选模型各一次。 */
        private const val JUDGE_WAIT_MS = 65_000L
        private val GATED = setOf(EvalCategory.Write, EvalCategory.Irreversible)
    }
}
