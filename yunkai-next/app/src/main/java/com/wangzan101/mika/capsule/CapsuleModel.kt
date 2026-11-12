package com.wangzan101.mika.capsule

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.confirm.ConfirmationCopy
import com.wangzan101.mika.core.agent.RunProgress
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.ui.Exchange

/** 设置里「悬浮胶囊」的三选一;存成名字,认不出(没设过、旧版本)时是默认的「一直显示」(随时能双击发这一屏)。 */
enum class CapsuleMode {
    Off, DuringTasks, Always;

    companion object {
        val DEFAULT = Always
        fun parse(name: String?): CapsuleMode = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * 一个任务此刻的样子:编号([TaskRun.key])、所在对话、状态、接管走到哪一步;
 * 卡片还要它的目标 [goal] 和这一问一答此刻的样子 [exchange](步骤、要你做的事、结果,和对话页同一套推法)。
 * [queueAhead]/[queueBehind] 是它在接管队里前后各有几件(主屏一次只交一个 App);不在队里或不知道时 [queueAhead] 为 -1,
 * 这时按它开始等接管的先后算。[runId] 是它的运行记录(对话里记下的那一轮带着同一个编号;还没开起记录时为 null)。
 * [onScreen] 是它此刻在操作一块画面:有自己的虚拟屏([com.wangzan101.mika.task.TaskRun.displayId] 不是 -1),
 * 或正在用内置浏览器([com.wangzan101.mika.task.TaskRun.webOpen]);卡片据此选任务卡片还是对话卡片。
 * [steers] 是它进行中插的话和去向([com.wangzan101.mika.task.TaskRun.steers]),对话卡片的来回里接在原话后面。
 */
data class CapsuleRun(
    val key: String,
    val conversationId: String,
    val state: TaskState,
    val stage: Takeover.Stage = Takeover.Stage.None,
    val goal: String = "",
    val exchange: Exchange? = null,
    val queueAhead: Int = -1,
    val queueBehind: Int = 0,
    val runId: String? = null,
    val onScreen: Boolean = false,
    val steers: List<SteerNote> = emptyList(),
)

/**
 * 决定胶囊显示什么的全部输入:各任务([runs],含刚结束、状态已是终态的)、开关、Mika 自己的界面在不在前台、
 * 屏幕亮着且没锁、无障碍服务在运行且浮层画得出来、卡片是不是展开着([cardOpen])。
 */
data class CapsuleInputs(
    val runs: List<CapsuleRun>,
    val mode: CapsuleMode,
    val mikaInForeground: Boolean,
    val screenInteractive: Boolean,
    val serviceAvailable: Boolean,
    val cardOpen: Boolean = false,
)

/** 胶囊的状态,按优先级从高到低排([ordinal] 小的先显示);[Idle] 是「一直显示」时没任务的图标。 */
enum class CapsuleKind {
    HandedOver, NeedTakeover, NeedConfirm, NeedAnswer,
    /** 要你在手机上动一下手(如在系统的选择框里选一个),动完自动接着做。 */
    NeedStep,
    Held, Running, Done, Failed, Cancelled, Idle;

    /** 要你处理(胶囊变橙)。App 已经交到你手上([HandedOver])是顶部那一条,不算在内。 */
    val needsYou: Boolean get() = this == NeedTakeover || this == NeedConfirm || this == NeedAnswer || this == NeedStep

    /** 还没结束(在跑、等你、等条件);[Idle] 不算。 */
    val active: Boolean get() = this != Done && this != Failed && this != Cancelled && this != Idle
}

sealed interface CapsuleView {
    data object Hidden : CapsuleView

    /**
     * 显示胶囊(或「你在处理」那一条):[text] 是那一行字(已拼好「共 N 件」),[detail] 只在 [CapsuleKind.HandedOver] 时有,
     * 是那条下面的小字。[count] 是进行中的任务数,[needCount] 是其中要你处理的件数。
     * [runKey]/[conversationId] 是胶囊说的那件事。平时是一颗 Mika 图标(状态画在圈和角标上),
     * [pill] 时展开成带这一行字的胶囊(有要你处理的、刚办完时)。[widthEpoch] 不变时胶囊只变宽不变窄,
     * 变了(换了状态或换了一件事)重新定宽。
     */
    data class Shown(
        val kind: CapsuleKind,
        val text: String,
        val detail: String? = null,
        val count: Int = 0,
        val needCount: Int = 0,
        val runKey: String? = null,
        val conversationId: String? = null,
        val pill: Boolean = false,
        val widthEpoch: Int = 0,
    ) : CapsuleView
}

/**
 * 胶囊的纯逻辑:每次输入变了(或到了 [nextChangeAt])调 [update],得到此刻该显示什么。规则:
 * - 不显示:开关关着、无障碍服务不可用、Mika 自己的界面在前台、屏幕关着或锁着;
 * - 一次只显示最要紧的那件事,优先级见 [CapsuleKind];好几件事时拼上「共 N 件」,有要你处理的变橙:「1 件要你处理 · 共 2 件」;
 * - 平时是一颗图标:在跑、等条件时只在圈和角标上变,换了一步不展开;
 * - 有要你处理的、而用户还没在卡片里看过它时展开成胶囊,一直停着,直到打开卡片或那件事不再要你;
 *   打开卡片时看到的那几件(同一件事、同一个等待)记为看过,收起后回到一颗(橙色),来了新的才再展开;
 * - 办好了 / 没办成 / 已停止先展开 [PILL_MS],再缩回一颗带着结果角标,共停 [LINGER_MS] 后消失(「一直显示」时回到图标);
 *   在卡片里看过的直接是一颗;卡片展开着时这段时间停表:用户正看着的结果不会被收走,收起卡片后接着走剩下的时间;
 * - 同一件事、同一个状态里换句时每句至少停 [MIN_SENTENCE_MS],这期间来的只留最后一句;换了状态或换了一件事马上换。
 * 卡片按什么先后挑那一件见 [cardRuns]。
 * [reasonLabel] 把接管原因(login 等)换成给人看的名字,认不出为 null。时间由调用方给,测试里用假时钟。
 */
class CapsuleModel(private val reasonLabel: (String) -> String? = { null }) {
    private class Ended(val run: CapsuleRun, val at: Long)

    /** 一件事此刻在等的那一下:同一件、同一个状态、同一个接管阶段算同一个。 */
    private data class Mark(val key: String, val state: TaskState, val stage: Takeover.Stage)

    /** 各任务第一次出现的时刻:同样要紧时按先后排,卡片上显示用时。 */
    private val firstSeen = HashMap<String, Long>()
    private val ended = HashMap<String, Ended>()
    /** 在等接管或已经接手的任务,按开始等的先后:主屏一次只交一个,排在前面的先处理。 */
    private val takeoverSince = LinkedHashMap<String, Long>()
    /** 用户在卡片里看过的要你处理和结果:不再展开成胶囊。 */
    private val seen = HashSet<Mark>()
    /** 卡片展开起的时刻;展开着时结束的任务不往下计时。 */
    private var frozenSince: Long? = null
    private var lastRuns: List<CapsuleRun> = emptyList()

    private var shownIdentity: Triple<CapsuleKind, String?, Boolean>? = null
    private var shownText: String? = null
    private var shownAt = 0L
    private var epoch = 0

    /** 输入不变时输出下一次会变的时刻(等着换的那句话、办完后缩回一颗和消失);没有为 null。 */
    var nextChangeAt: Long? = null
        private set

    fun update(inputs: CapsuleInputs, now: Long): CapsuleView {
        nextChangeAt = null
        lastRuns = inputs.runs
        val keys = inputs.runs.map { it.key }.toSet()
        inputs.runs.forEach { firstSeen.getOrPut(it.key) { now } }
        if (inputs.cardOpen) {
            if (frozenSince == null) frozenSince = now
        } else frozenSince?.let { since ->
            // 收起卡片:展开的这段时间不算,结束的任务接着走剩下的时间
            val paused = now - since
            ended.replaceAll { _, e -> Ended(e.run, e.at + paused) }
            frozenSince = null
        }
        inputs.runs.filter { it.state.ended() }.forEach { ended.getOrPut(it.key) { Ended(it, frozenSince ?: now) } }
        if (frozenSince == null) ended.values.removeAll { now - it.at >= LINGER_MS }
        firstSeen.keys.retainAll(keys + ended.keys)
        val waiting = inputs.runs.filter { it.state is TaskState.WaitingTakeover && it.stage != Takeover.Stage.None }.map { it.key }
        takeoverSince.keys.retainAll(waiting.toSet())
        waiting.forEach { takeoverSince.getOrPut(it) { now } }
        // 看过的只留还在的;卡片展开着时此刻的要你处理和结果都算看过
        val marks = needMarks(inputs.runs) + ended.values.map { it.run.mark() }
        seen.retainAll(marks)
        if (inputs.cardOpen) seen += marks

        if (inputs.mode == CapsuleMode.Off || !inputs.serviceAvailable || inputs.mikaInForeground || !inputs.screenInteractive) {
            // 再出现时直接显示最新的那句,不接着上次的节流
            shownIdentity = null
            shownText = null
            return CapsuleView.Hidden
        }
        val candidate = activeView(inputs.runs) ?: endedView(now) ?: idleView(inputs.mode) ?: run {
            shownIdentity = null
            shownText = null
            return CapsuleView.Hidden
        }
        return throttle(candidate, now)
    }

    private fun needMarks(runs: List<CapsuleRun>): Set<Mark> =
        runs.filter { it.state != TaskState.Idle && !it.state.ended() && describe(it).kind.needsYou }.map { it.mark() }.toSet()

    private fun CapsuleRun.mark() = Mark(key, state, stage)

    private fun activeView(runs: List<CapsuleRun>): CapsuleView.Shown? {
        val active = runs.filter { it.state != TaskState.Idle && !it.state.ended() }
        if (active.isEmpty()) return null
        val described = active.map { it to describe(it) }
        // 同样要紧的几件里先说接管队里排在前面的,再说先开始的那件
        val (top, line) = described.minWith(order)
        val count = active.size
        val needCount = described.count { it.second.kind.needsYou }
        var detail: String? = null
        val text = when {
            line.kind == CapsuleKind.HandedOver -> {
                val behind = spot(top)?.second ?: described.count { it.first !== top && it.second.kind == CapsuleKind.NeedTakeover }
                detail = if (behind > 0) trPlural(R.plurals.capsule_behind, behind)
                    else listOfNotNull(line.reason, tr(R.string.capsule_then_tap_right)).joinToString(" · ")
                line.text
            }
            count >= 2 && needCount > 0 -> tr(R.string.capsule_need_of_total, needCount, count)
            count >= 2 -> tr(R.string.capsule_of_total, line.text, count)
            else -> line.text
        }
        // 有还没在卡片里看过的要你处理:展开成胶囊,一直停着
        val pill = line.kind.needsYou && described.any { (r, l) -> l.kind.needsYou && r.mark() !in seen }
        return CapsuleView.Shown(line.kind, text, detail, count, needCount, top.key, top.conversationId, pill = pill)
    }

    /** 最近结束的那件:先展开成胶囊,再缩回一颗带着结果角标;在卡片里看过的直接是一颗;卡片展开着时停表。 */
    private fun endedView(now: Long): CapsuleView.Shown? {
        val last = ended.values.maxByOrNull { it.at } ?: return null
        val age = (frozenSince ?: now) - last.at
        val pill = age < PILL_MS && last.run.mark() !in seen
        if (frozenSince == null) later(last.at + if (pill) PILL_MS else LINGER_MS)
        val (kind, text) = endedLine(last.run.state)
        return CapsuleView.Shown(kind, text, runKey = last.run.key, conversationId = last.run.conversationId, pill = pill)
    }

    private fun endedLine(s: TaskState): Pair<CapsuleKind, String> = when (s) {
        is TaskState.Succeeded -> CapsuleKind.Done to tr(R.string.capsule_done_tap)
        is TaskState.Failed -> CapsuleKind.Failed to tr(R.string.capsule_failed_tap)
        else -> CapsuleKind.Cancelled to tr(R.string.capsule_stopped)
    }

    /**
     * 卡片从中挑一件的几件事(上一次 [update] 的输入,见 [CapsuleCards.of]):进行中的按要紧程度排(同样要紧的先开始的在前),
     * 后面是还没消失的已结束的(最近结束的在前)。在跑、等条件时带开始的时刻;等接管的带排在它前后的件数。
     */
    fun cardRuns(): List<CardRun> {
        val active = lastRuns.filter { it.state != TaskState.Idle && !it.state.ended() }
            .map { it to describe(it) }
            .sortedWith(order)
        val activeKeys = active.map { it.first.key }.toSet()
        return active.map { (r, line) ->
            val (ahead, behind) = spot(r) ?: (0 to 0)
            val timed = line.kind == CapsuleKind.Running || line.kind == CapsuleKind.Held
            CardRun(r, line.kind, line.text, startedAt = if (timed) firstSeen[r.key] else null, ahead = ahead, behind = behind)
        } + ended.values.filter { it.run.key !in activeKeys }.sortedByDescending { it.at }.map { e ->
            val (kind, text) = endedLine(e.run.state)
            CardRun(e.run, kind, text)
        }
    }

    /** 要紧的在前;同样要紧的,接管队里排在前面的在前,再按开始的先后。 */
    private val order = compareBy<Pair<CapsuleRun, Line>>(
        { it.second.kind.ordinal }, { spot(it.first)?.first ?: Int.MAX_VALUE }, { firstSeen[it.first.key] ?: 0L },
    )

    /** 在接管队里前后各有几件:有真实的队列位置用它,没有时按开始等接管的先后;不在等接管为 null。 */
    private fun spot(r: CapsuleRun): Pair<Int, Int>? {
        if (r.queueAhead >= 0) return r.queueAhead to r.queueBehind
        val queue = takeoverSince.keys.toList()
        val i = queue.indexOf(r.key)
        return if (i < 0) null else i to queue.size - 1 - i
    }

    private fun idleView(mode: CapsuleMode): CapsuleView.Shown? =
        if (mode == CapsuleMode.Always) CapsuleView.Shown(CapsuleKind.Idle, "") else null

    /**
     * 同一件事、同一个状态里换句:离上一句不到 [MIN_SENTENCE_MS] 就先留着上一句,到时候再换成那时最新的。
     * 展开、缩回也算换了一段,胶囊重新定宽。
     */
    private fun throttle(v: CapsuleView.Shown, now: Long): CapsuleView.Shown {
        val identity = Triple(v.kind, v.runKey, v.pill)
        if (identity != shownIdentity) {
            shownIdentity = identity
            shownText = v.text
            shownAt = now
            epoch++
            return v.copy(widthEpoch = epoch)
        }
        if (v.text != shownText) {
            if (now - shownAt >= MIN_SENTENCE_MS) {
                shownText = v.text
                shownAt = now
            } else {
                later(shownAt + MIN_SENTENCE_MS)
            }
        }
        return v.copy(text = shownText ?: v.text, widthEpoch = epoch)
    }

    private fun later(at: Long) {
        nextChangeAt = nextChangeAt?.coerceAtMost(at) ?: at
    }

    private class Line(val kind: CapsuleKind, val text: String, val reason: String? = null)

    private fun describe(r: CapsuleRun): Line = when (val s = r.state) {
        is TaskState.WaitingTakeover -> when (r.stage) {
            Takeover.Stage.HandedOver -> Line(CapsuleKind.HandedOver, HANDED_OVER, reasonLabel(s.reason))
            Takeover.Stage.Waiting -> Line(
                CapsuleKind.NeedTakeover,
                tr(R.string.capsule_needs_you, reasonLabel(s.reason) ?: oneLine(s.message).ifEmpty { tr(R.string.capsule_handle_it) }),
            )
            // 已交还、循环还没往下走的那一小段
            Takeover.Stage.None -> Line(CapsuleKind.Running, CONTINUING)
        }
        is TaskState.WaitingConfirmation -> Line(CapsuleKind.NeedConfirm, tr(R.string.capsule_needs_ok, ConfirmationCopy.actionLabel(s.request.action)))
        is TaskState.WaitingInput -> Line(CapsuleKind.NeedAnswer, tr(R.string.capsule_asks, oneLine(s.prompt)))
        is TaskState.Held -> if (s.reason.needsYou) Line(CapsuleKind.NeedStep, tr(R.string.capsule_needs_you, oneLine(s.reason.why).ifEmpty { tr(R.string.capsule_handle_it) }))
        else Line(CapsuleKind.Held, oneLine(s.reason.why).let { if (it.isEmpty()) tr(R.string.capsule_hold) else tr(R.string.capsule_hold_why, it) })
        is TaskState.Running -> Line(CapsuleKind.Running, runningText(s))
        else -> Line(CapsuleKind.Running, THINKING)
    }

    companion object {
        const val MIN_SENTENCE_MS = 1_500L
        /** 办完后展开成胶囊多久。 */
        const val PILL_MS = 4_000L
        /** 办完后一共停多久(展开的那段加上缩回一颗带角标的那段)。 */
        const val LINGER_MS = 10_000L
        val HANDED_OVER: String get() = tr(R.string.capsule_you_handle)
        val THINKING: String get() = tr(R.string.capsule_thinking)
        val PREPARING: String get() = tr(R.string.runtime_preparing)
        val CONTINUING: String get() = tr(R.string.capsule_continuing)

        /** 此刻在做什么:回放、批量执行写进度;否则先用模型随调用写的话,再用调用里的用意,都没有用固定说法。 */
        fun runningText(s: TaskState.Running): String {
            s.progress?.let { return progressText(it) }
            val said = listOfNotNull(s.narration, s.reason).map(::oneLine).firstOrNull { it.isNotEmpty() }
            return said ?: if (s.step == 0) PREPARING else THINKING
        }

        fun progressText(p: RunProgress): String = when (p) {
            is RunProgress.Replay -> tr(R.string.capsule_replay, p.step, p.total)
            is RunProgress.Batch -> trPlural(R.plurals.capsule_batch, p.steps)
        }

        /** 模型写的字压成一行。 */
        private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()

        private fun TaskState.ended() = this is TaskState.Succeeded || this is TaskState.Failed || this == TaskState.Cancelled
    }
}

/**
 * 胶囊的透明度:一颗图标不碰它 [IDLE_MS] 后淡到 [BALL](圈和角标保持实色,由界面管);
 * 碰到或状态变了马上恢复(调用方从那一刻重新计时)。展开成胶囊时、要你处理时、「你来处理」那一条不淡。
 */
object CapsuleFade {
    const val IDLE_MS = 3_000L
    const val BALL = 0.4f

    /** [idleFor] 是离上次碰到或状态变了过了多久。 */
    fun alpha(kind: CapsuleKind, needCount: Int, pill: Boolean, idleFor: Long): Float = when {
        pill || kind.needsYou || needCount > 0 || kind == CapsuleKind.HandedOver -> 1f
        idleFor < IDLE_MS -> 1f
        else -> BALL
    }
}
