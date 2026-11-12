package com.wangzan101.mika.core.eval

import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.testing.RECORDED_TOOLS
import com.wangzan101.mika.core.verify.RecordedVerifications
import com.wangzan101.mika.core.verify.Verdict
import com.wangzan101.mika.core.verify.Grounding
import com.wangzan101.mika.core.verify.Verifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/**
 * 离线重新打分一次拉回来的评测运行:用现在的题目、核对规则和核查兜底重算,不调模型、不连手机。没设目录时跳过。
 *
 *   MIKA_EVAL_RUN=/path/to/<运行名> ./gradlew :core:test --tests '*RescoreEvalRun*' --rerun
 *
 * 目录里要有 scores.jsonl 和 <题 id>/run/events.jsonl(评测器写出的样子)。每题:
 * - 结局(主数,按现在的结束方式,见 [natural]):第一条最终回答(不带调用、有字的回复;旧记录里是 finish)就结束任务;
 *   按风险核对:回答里的值在那时的账本里对不上,或做过对外的事而当时核查没判 pass,就像运行时一样退回一次,取下一条最终回答;
 *   还对不上算 unverified(标成未确认交出),对上了算 succeeded。第一条最终回答之前就放弃、交给用户、被叫停的照旧。
 *   旧记录里的提醒(没调工具被提醒)、退回到底的核查失败,现在都换成上面这样结束。
 * - 对比:旧规则(只看最后一次核查,用现在的兜底 [Verifier.backstop] 重核)的结局与是否通过。
 * - 核对项:回答、读屏、障碍、追问、记忆按运行记录重算;要在手机上现查的(screen_has、web_has、探针、确认卡片)和期望值来自跑前探针的沿用当时的结果。
 * 证据按证据账本重建([RecordedVerifications.ledgerSources],和运行时核查器同一个挑法),这是报告的主数;
 * 回答的 answer_grounded 核对也用同一份账本([RunFacts.evidence])。
 * 交出回答后判的结局(outcome 事件,见 [com.wangzan101.mika.core.agent.OutcomeJudge])按记录里的算([Scoring.recorded]):
 * 回答如实说没办成、只办了一部分的算如实放弃;只认判的是同一条回答(按现在的结束方式取的回答换了一条就不用它)。旧记录没有这条事件。
 * 相对日期按那次核查的时刻换算(时区取 MIKA_RESCORE_ZONE,没设用本机的)。交给用户接管的单列,不算通过。结果写到 <目录>/rescore.md。
 */
class RescoreEvalRun {
    private val tasksDir = listOf("eval/tasks", "../eval/tasks").map(::File).first { it.isDirectory }

    @Test fun rescore() {
        val dir = (System.getenv("MIKA_EVAL_RUN") ?: System.getProperty("mika.eval.run"))?.let(::File)
        assumeTrue("MIKA_EVAL_RUN not set", dir != null && File(dir, "scores.jsonl").isFile)
        val zone = System.getenv("MIKA_RESCORE_ZONE")?.let(ZoneId::of) ?: ZoneId.systemDefault()
        val tasks = tasksDir.walkTopDown().filter { it.isFile && it.extension == "json" }.map { EvalTasks.parse(it.readText()) }.associateBy { it.id }
        val rows = mutableListOf("| id | 结局 当时→现在 | 判的结局 | 通过 当时→现在 | 交给用户 | 没过的核对项 | 未确认 | 旧规则(核查兜底)时 |", "|---|---|---|---|---|---|---|---|")
        var before = 0
        var live = 0
        var legacyPassed = 0
        var nudged = 0
        var total = 0
        var falseBefore = 0
        var falseNow = 0
        var unverifiedWrong = 0
        var judgedNotDone = 0
        val handed = mutableMapOf<String, Int>()
        File(dir, "scores.jsonl").readLines().filter { it.isNotBlank() }.forEach { line ->
            val old = Json.parseToJsonElement(line) as JsonObject
            val id = old.str("id") ?: return@forEach
            val task = tasks[id] ?: return@forEach
            val events = File(dir, "$id/run/events.jsonl").takeIf { it.isFile }?.readLines()
                ?.mapNotNull { runCatching { RunEvent.decode(it) }.getOrNull() }.orEmpty()
            val oldOutcome = old.str("outcome").orEmpty()
            val oldFailed = (old["failedChecks"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            fun score(outcome: String, answer: String? = null, upto: Int = events.size, judged: String? = null): TaskScore {
                val observed = observed(task, events.subList(0, upto), old, outcome, oldFailed, zone, answer).copy(judged = judged)
                val s = Scoring.score(task, observed)
                // 期望值来自跑前探针({probe:…})的核对项离线算不了,沿用当时的结果
                val failed = s.failedChecks.filterNot { k ->
                    val i = k.substringBefore(':').toIntOrNull() ?: return@filterNot false
                    "{probe:" in task.checks[i].toString() && k !in oldFailed
                }
                return s.copy(passed = failed.isEmpty(), failedChecks = failed)
            }
            val n = natural(task, events, oldOutcome, zone)
            // 判的是交出的那条回答;按现在的结束方式换了一条回答就不用它
            val delivered = events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()?.answerOrReason
            val judged = RunOutcome.recorded(events)?.wire?.takeIf { n == null || n.answer.trim() == delivered?.trim() }
            val score = n?.let { score(it.outcome, answer = it.answer, upto = it.upto, judged = judged) } ?: score(oldOutcome, judged = judged)
            val nowOutcome = n?.outcome ?: oldOutcome
            val oldRule = recheck(task, events, oldOutcome, zone)
            val legacy = score(oldRule)
            val wasPassed = old.str("passed") == "true"
            total++
            if (wasPassed) before++
            if (score.passed) live++
            if (legacy.passed) legacyPassed++
            if (n?.nudged == true) nudged++
            if (old.str("falseSuccess") == "true") falseBefore++
            if (score.falseSuccess) falseNow++
            if (score.failureClass == FailureClass.unverified_wrong) unverifiedWrong++
            val notDone = judged == RunOutcome.Partial.wire || judged == RunOutcome.NotPossible.wire
            if (notDone) judgedNotDone++
            score.handedOver?.let { handed[it] = (handed[it] ?: 0) + 1 }
            val mark = if (wasPassed != score.passed || oldOutcome != nowOutcome || notDone || score.handedOver != null) " **" else ""
            val legacyNote = if (legacy.passed != score.passed || oldRule != nowOutcome) "$oldRule,通过 ${yes(legacy.passed)}" else ""
            rows += "| $id$mark | $oldOutcome → $nowOutcome | ${judged.orEmpty()} | ${yes(wasPassed)} → ${yes(score.passed)} | ${score.handedOver.orEmpty()} | " +
                "${score.failedChecks.joinToString(", ")} | ${n?.unconfirmed.orEmpty().joinToString("、")} | $legacyNote |"
        }
        val report = "# 重新打分 ${dir!!.name}(自然结束:不带调用的回复就是回答)\n\n" +
            "通过:当时 $before/$total → 现在 $live/$total\n\n" +
            "对比:旧规则(只看最后一次核查,现在的兜底重核)$legacyPassed/$total\n\n" +
            "退回过一次的:$nudged\n\n" +
            "假成功:当时 $falseBefore → 现在 $falseNow(判下来没办成、按如实放弃算的 $judgedNotDone);未核实且没过:$unverifiedWrong\n\n" +
            "交给用户(不算通过):" + (handed.entries.joinToString(",") { "${it.key} ${it.value}" }.ifEmpty { "无" }) + "\n\n" +
            rows.joinToString("\n") + "\n"
        File(dir, "rescore.md").writeText(report)
        println(report)
    }

    private fun yes(b: Boolean) = if (b) "是" else "否"

    /** 按现在的结束方式重算的结局:[answer] 是交出的回答,[upto] 是它之后的事件下标(之后的不会发生),[unconfirmed] 是标成未确认的几处。 */
    private class Natural(val outcome: String, val answer: String, val upto: Int, val unconfirmed: List<String>, val nudged: Boolean)

    /**
     * 第一条最终回答就结束任务(见类说明)。那之前已经以别的方式结束(放弃、接管、被叫停、超时)、或根本没有最终回答的,返回 null,结局照旧。
     * 每条回答按那时为止的账本做字面核对([Grounding.check]);做过对外的事([RunFacts.actedOutward])时看同一步录下的核查结论。
     */
    private fun natural(task: EvalTask, events: List<RunEvent>, oldOutcome: String, zone: ZoneId): Natural? {
        if (oldOutcome == "timeout" || oldOutcome == "cancelled" || oldOutcome == "skipped") return null
        val finals = events.indices.filter { RunFacts.isFinalAnswer(events[it]) }
        if (finals.isEmpty()) return null
        // 第一条回答之前任务就以别的方式结束了(接管、确认关卡停下的仍算之后的事):照旧
        val goal = events.filterIsInstance<RunEvent.TaskStarted>().firstOrNull()?.goal ?: task.goal
        val pairs = RecordedVerifications.pairs(events).associate { (v, f) -> f to (events[v] as RunEvent.Verifier) }
        fun check(f: Int): List<String> {
            val r = events[f] as RunEvent.ModelReply
            val answer = RecordedVerifications.answer(events, f) ?: return emptyList()
            val seen = events.subList(0, f)
            val ledger = RunFacts.ledger(seen, RECORDED_TOOLS)
            val today = java.time.Instant.ofEpochMilli(r.atMs).atZone(zone).toLocalDate()
            val gaps = Grounding.check(goal, answer, ledger.forAnswer(goal, answer).map { it.text }, today).unmatched.map { it.text }
            val outward = RunFacts.actedOutward(seen)
            val verdict = pairs[f]?.verdict
            return when {
                gaps.isNotEmpty() -> gaps.distinct()
                outward && verdict != null && verdict != Verdict.Pass.wire -> listOf(RecordedVerifications.arg(pairs[f]!!.detailJson, "reason").orEmpty().ifBlank { verdict })
                else -> emptyList()
            }
        }
        val first = finals.first()
        val missing = check(first)
        if (missing.isEmpty()) return Natural("succeeded", RecordedVerifications.answer(events, first).orEmpty(), first + 1, emptyList(), nudged = false)
        // 退回一次:取下一条最终回答;没有了就按第一条标成未确认交出
        val second = finals.getOrNull(1) ?: return Natural("unverified", RecordedVerifications.answer(events, first).orEmpty(), first + 1, missing, nudged = false)
        val again = check(second)
        return Natural(if (again.isEmpty()) "succeeded" else "unverified", RecordedVerifications.answer(events, second).orEmpty(), second + 1, again, nudged = true)
    }

    /** 旧规则(只留作对比):最后一次核查的是最终回答,当时兜底改判过(或放过)的按现在的兜底重核。 */
    private fun recheck(task: EvalTask, events: List<RunEvent>, oldOutcome: String, zone: ZoneId): String {
        if (oldOutcome != "succeeded" && oldOutcome != "unverified") return oldOutcome
        val (v, f) = RecordedVerifications.pairs(events).lastOrNull() ?: return oldOutcome
        val e = events[v] as RunEvent.Verifier
        val detail = runCatching { Json.parseToJsonElement(e.detailJson) as JsonObject }.getOrNull() ?: return oldOutcome
        val reason = detail.str("reason").orEmpty()
        // 只有核查器判了 pass 的才走兜底:当时是 pass,或者是被兜底改成 cannot_verify 的(含已去掉的「不是同一处」那条规则)
        val modelPassed = e.verdict == Verdict.Pass.wire || reason == Verifier.NOT_IN_READS || reason == OLD_NOT_ON_SCREEN || reason == Verifier.TRUNCATED || reason == OLD_NOT_ONE_SOURCE
        if (!modelPassed) return oldOutcome
        val asPass = Verifier.parse(JsonObject(detail + ("verdict" to JsonPrimitive("pass"))).toString()) ?: return oldOutcome
        if (asPass.verdict != Verdict.Pass) return oldOutcome
        val answer = RecordedVerifications.answer(events, f) ?: return oldOutcome
        val goal = events.filterIsInstance<RunEvent.TaskStarted>().firstOrNull()?.goal ?: task.goal
        val sources = RecordedVerifications.ledgerSources(events, v, f, goal, answer)
        val today = java.time.Instant.ofEpochMilli(e.atMs).atZone(zone).toLocalDate()
        val now = Verifier.backstop(asPass, goal, answer, sources, imageSent = false, today)
        return if (now.verdict == Verdict.Pass) "succeeded" else "unverified"
    }

    private fun observed(
        task: EvalTask, events: List<RunEvent>, old: JsonObject, outcome: String, oldFailed: List<String>, zone: ZoneId,
        given: String? = null,
    ): Observed {
        fun passedThen(i: Int, type: String) = "$i:$type" !in oldFailed
        val screenHas = mutableMapOf<String, Boolean>()
        val webHas = mutableMapOf<String, Boolean>()
        val probes = mutableMapOf<String, String>()
        task.checks.forEachIndexed { i, c ->
            when (c) {
                is Check.ScreenHas -> screenHas[Checks.screenKey(c)] = passedThen(i, "screen_has")
                is Check.WebHas -> webHas[Checks.webKey(c)] = passedThen(i, "web_has")
                is Check.ProbeEquals -> probes[c.probe] = if (passedThen(i, "probe_equals")) c.expected else ""
                else -> Unit
            }
        }
        val card = task.checks.filterIsInstance<Check.ConfirmationShows>().firstOrNull()
            ?.takeIf { c -> passedThen(task.checks.indexOf(c), "confirmation_shows") }
            ?.let { c -> listOfNotNull(c.action?.let { "action" to it }, c.target?.let { "target" to it }, c.content?.let { "content" to it }, c.amount?.let { "amount" to it }).toMap() }
        val answer = given ?: events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()?.answerOrReason?.takeIf { outcome == "succeeded" || outcome == "unverified" || outcome == "failed" }
            ?: old.str("answer").orEmpty()
        val goal = events.filterIsInstance<RunEvent.TaskStarted>().firstOrNull()?.goal ?: task.goal
        val ev = RunFacts.evidence(events, RECORDED_TOOLS, goal, answer)
        val end = events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()?.atMs ?: events.lastOrNull()?.atMs
        return Observed(
            outcome = outcome,
            answer = answer,
            evidence = ev,
            today = end?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() },
            confirmation = card,
            screenHas = screenHas,
            probes = probes,
            steps = events.count { it is RunEvent.ModelReply && it.acted },
            wallMs = old.str("wallMs")?.toLongOrNull() ?: 0,
            inputTokens = 0, outputTokens = 0, cachedTokens = 0,
            failReason = old.str("failReason"),
            goal = goal,
            webHas = webHas,
            memoryUsed = events.filterIsInstance<RunEvent.MemoryUsed>().sumOf { it.ids.size },
            askedUser = events.count { it is RunEvent.ModelReply && it.acted && it.tool == "ask_user" },
            takeoverBarrier = RunFacts.takeoverBarrier(events, RECORDED_TOOLS),
        )
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    /** 旧记录里被已去掉的「值和它说的东西不是同一处」规则否掉的核查理由。 */
    /** 改说法之前记下的「依据找不到」。 */
    private val OLD_NOT_ON_SCREEN = "回答的依据在屏幕、网页或命令输出里都找不到"
    private val OLD_NOT_ONE_SOURCE = "回答里的值和它说的东西不是从同一个页面或 App 读来的"
}
