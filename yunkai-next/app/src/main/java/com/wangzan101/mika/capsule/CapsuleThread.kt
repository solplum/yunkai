package com.wangzan101.mika.capsule

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.agent.Conversation
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.tool.PACKAGE_NAME_RE
import com.wangzan101.mika.ui.StatusCopy
import com.wangzan101.mika.ui.answerParts
import com.wangzan101.mika.ui.steerLabel

/**
 * 对话卡片里的一条。[key] 在同一个对话里不变(有运行记录编号时按它,没有时按这一轮在对话里的位置):
 * 往上加载更早的几轮、正在进行的那一轮记进对话后,列表都据此停在原处。
 */
sealed interface ThreadItem {
    val key: String

    /** 你说的话(原话和任务进行中插的话):右边的小气泡;[note] 是插的话下面那行去向(「已收到，下一步处理」),没有为 null。 */
    data class User(override val key: String, val text: String, val note: String? = null) : ThreadItem

    /** 一行小字:操作过 App 或网页的那一轮收成的一行(「✓ 微信 · 办好了」),或纯聊天没办成、停了、还在想时那一行。 */
    data class Note(override val key: String, val text: String, val kind: CapsuleKind) : ThreadItem

    /** Mika 的回答全文(原样的 Markdown,卡片里排版);[streaming] 时还在往下写。 */
    data class Answer(override val key: String, val text: String, val streaming: Boolean = false) : ThreadItem
}

/** 对话卡片的来回,旧的在上、新的在下;[hasEarlier] 时往上还有更早的几轮没加载;[title] 是对话的标题(第一句话)。 */
data class ChatThread(val items: List<ThreadItem>, val hasEarlier: Boolean = false, val title: String? = null) {
    /** 打开时停在哪一条:最新的那句你说的话放在可见区域顶上,从这一问一答读起;没有你说的话时从头。 */
    val anchor: Int get() = items.indexOfLast { it is ThreadItem.User }.coerceAtLeast(0)
}

/** 已经记进对话的一轮,连同它操作过的 App 和网站([places],从运行记录里读;纯聊天、记录已清掉时为空)。 */
data class PastTurn(val turn: Turn, val places: List<String> = emptyList())

/**
 * 对话卡片的来回怎么排(纯逻辑):已经记进对话的几轮(和对话页同一份 [com.wangzan101.mika.chat.ConversationStore])在前,
 * 还没记进去的、这个对话里此刻的任务接在后面。操作过 App 的那一轮只留一行和回答,不放步骤、画面和来源。
 * 先只排最近 [PAGE] 轮,滚到顶再往前多排 [PAGE] 轮。
 */
object CapsuleThreads {
    const val PAGE = 10

    /**
     * [past] 是这个对话记下的全部轮次,排最后 [shown] 轮;[live] 是还没记进去的任务(见 [liveTail]),
     * [livePlaces] 给出它们操作过的 App 和网站;[summarizing] 是停止后交代还在写的那几次运行(运行编号)。
     */
    fun build(
        past: List<PastTurn>, shown: Int, live: List<CardRun>, summarizing: Set<String> = emptySet(),
        livePlaces: (CardRun) -> List<String> = { emptyList() },
    ): ChatThread {
        val from = (past.size - shown).coerceAtLeast(0)
        val items = (from until past.size).flatMap { i -> pastItems(i, past[i], summarizing) } +
            live.flatMap { liveItems(it, livePlaces(it), summarizing) }
        val title = past.firstOrNull()?.turn?.userText ?: live.firstOrNull()?.run?.goal
        return ChatThread(items, hasEarlier = from > 0, title = title?.takeIf(String::isNotBlank))
    }

    /**
     * 卡片里这个对话([conversationId])还没记进对话的任务:记进去的一轮带着同一个运行记录编号([recorded] 里有),就用记下的那份;
     * 没开起记录就结束的(排队中被取消)只看记下的那份。结束了的在前(先结束的在上),进行中的在后。
     */
    fun liveTail(runs: List<CardRun>, conversationId: String, recorded: Set<String>): List<CardRun> {
        val mine = runs.filter { r ->
            r.run.conversationId == conversationId && (r.run.runId?.let { it !in recorded } ?: r.kind.active)
        }
        val (active, ended) = mine.partition { it.kind.active }
        return ended.reversed() + active
    }

    /** 记下的一轮:你说的话(和插的话)、操作过 App 时那一行、回答或没办成的原因;停了的是停止后补上的交代。 */
    fun pastItems(index: Int, t: PastTurn, summarizing: Set<String> = emptySet()): List<ThreadItem> {
        val turn = t.turn
        val base = turn.runId?.let { "r$it" } ?: "p$index"
        val kind = when (turn.status) {
            "succeeded", "unverified" -> CapsuleKind.Done
            "cancelled" -> CapsuleKind.Cancelled
            else -> CapsuleKind.Failed
        }
        val answer = when (kind) {
            CapsuleKind.Done -> answerParts(turn.outcome).first
            CapsuleKind.Failed -> turn.outcome
            else -> Conversation.stopSummary(turn).orEmpty()
        }
        val users = (listOf(turn.userText) + turn.steers).filter(String::isNotBlank)
            .mapIndexed { i, text -> ThreadItem.User("$base-u$i", text) }
        return users + ended(base, kind, t.places, answer, pending = kind == CapsuleKind.Cancelled && turn.runId in summarizing)
    }

    /** 还没记进对话的一件:在进行时回答跟着写出来,等你回答、同意时是那一问,结束了和记下的一样。 */
    fun liveItems(r: CardRun, places: List<String>, summarizing: Set<String> = emptySet()): List<ThreadItem> {
        val run = r.run
        val base = run.runId?.let { "r$it" } ?: "l${run.key}"
        // 原话,再是进行中插的话(带着去向,和对话页同一套说法)
        val user = listOfNotNull(run.goal.takeIf(String::isNotBlank)?.let { ThreadItem.User("$base-u0", it) }) +
            run.steers.map { ThreadItem.User("$base-s${it.id}", it.text, steerLabel(it.state)) }
        val rest = when (val s = run.state) {
            is TaskState.Succeeded -> ended(base, CapsuleKind.Done, places, s.answer)
            is TaskState.Failed -> ended(base, CapsuleKind.Failed, places, s.reason)
            TaskState.Cancelled -> ended(base, CapsuleKind.Cancelled, places, "", pending = run.runId in summarizing)
            else -> {
                val note = if (places.isNotEmpty()) ThreadItem.Note("$base-n", appLine(places, r.kind), r.kind) else null
                val answer = when (val c = CapsuleCards.content(r)) {
                    is CardContent.Question -> ThreadItem.Answer("$base-a", c.prompt)
                    is CardContent.Confirm -> ThreadItem.Answer("$base-a", c.title + "\n" + c.body)
                    is CardContent.Takeover -> ThreadItem.Answer("$base-a", listOf(c.need, c.body).filter(String::isNotBlank).joinToString("\n"))
                    is CardContent.Held -> ThreadItem.Note("$base-a", c.why, r.kind)
                    else -> run.exchange?.draft?.takeIf(String::isNotBlank)
                        ?.let { ThreadItem.Answer("$base-a", it, streaming = true) }
                        // 还没写出字:说此刻在做什么(在 App 里时那一行已经说了)
                        ?: ThreadItem.Note("$base-a", r.line, r.kind).takeIf { note == null && r.line.isNotBlank() }
                }
                listOfNotNull(note, answer)
            }
        }
        return user + rest
    }

    /**
     * 结束的一轮:操作过 App 时那一行(纯聊天只在没办成、停了时有一行),再是回答全文。
     * [pending] 时停止后的交代还在写:那一行下面一行「正在整理已做的事…」。
     */
    private fun ended(base: String, kind: CapsuleKind, places: List<String>, answer: String, pending: Boolean = false): List<ThreadItem> {
        val line = when {
            places.isNotEmpty() -> appLine(places, kind)
            kind == CapsuleKind.Failed -> CapsuleCards.status(kind)
            kind == CapsuleKind.Cancelled -> CapsuleCards.status(kind)
            else -> null
        }
        val text = answer.trim()
        return listOfNotNull(
            line?.let { ThreadItem.Note("$base-n", it, kind) },
            if (pending && text.isEmpty()) ThreadItem.Note("$base-s", StatusCopy.STOP_SUMMARIZING, kind) else null,
            text.takeIf(String::isNotEmpty)?.let { ThreadItem.Answer("$base-a", it) },
        )
    }

    /** 操作过 App 的那一轮收成的一行:「✓ 微信、淘宝 · 办好了」「12306 · 没办成」;最多列两处。 */
    fun appLine(places: List<String>, kind: CapsuleKind): String {
        val where = places.take(2).joinToString(tr(R.string.list_sep)) + if (places.size > 2) tr(R.string.and_more) else ""
        val mark = if (kind == CapsuleKind.Done) "✓ " else ""
        return "$mark$where · ${CapsuleCards.status(kind)}"
    }

    /** 这一轮实际操作过的 App 和网站(活动的对象是包名或「网站 域名」的),按先后去重;包名换成 App 名。 */
    fun places(acts: List<RunEvent.Activity>, appLabel: (String) -> String): List<String> =
        acts.mapNotNull { a ->
            val t = a.target?.trim().orEmpty()
            when {
                t.startsWith(SITE_PREFIX) -> t.removePrefix(SITE_PREFIX).trim().ifEmpty { null }
                PACKAGE_NAME_RE.matches(t) -> appLabel(t)
                else -> null
            }
        }.distinct()

    /** 运行记录里网页那一屏的对象写成「网站 <域名>」。 */
    private const val SITE_PREFIX = "网站 "
}
