// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.capsule

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.confirm.ConfirmationCopy
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.ui.Exchange
import com.wangzan101.mika.ui.ExchangeStatus
import com.wangzan101.mika.ui.StatusCopy

/**
 * 卡片里的一件事:[kind]/[line] 和胶囊上那件事的说法一致;[startedAt] 在跑、等条件时有(显示用时);
 * [ahead]/[behind] 是接管队里排在它前面、后面还有几件(主屏一次只交一个 App)。
 */
data class CardRun(
    val run: CapsuleRun,
    val kind: CapsuleKind,
    val line: String,
    val startedAt: Long? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
)

/**
 * 卡片此刻显示什么:卡片只说一件事,不是列表(怎么挑见 [CapsuleCards.of])。那件此刻在操作 App 或网页就是任务卡片([Detail]),
 * 否则是那个对话的对话卡片([Chat]);没有任务(开关是「一直显示」)时也是对话卡片,不带任务。
 */
sealed interface CardView {
    /** 任务卡片:小画面、步骤、要你做的事和按钮。 */
    data class Detail(val run: CardRun, val content: CardContent) : CardView

    /**
     * 对话卡片:[conversationId] 那个对话的来回(见 [ChatThread])。带着 [run] 时是那件事所在的对话,[content] 是它此刻的样子
     * (要你回答、同意时按钮固定在对话下面)。不带任务时是空闲卡片:默认接着最近的对话([title] 是它的标题),
     * [fresh] 时是新对话;没有任何对话时 [conversationId] 为 null,只能开新对话。
     */
    data class Chat(
        val conversationId: String?,
        val run: CardRun? = null,
        val content: CardContent? = null,
        val title: String? = null,
        val fresh: Boolean = false,
    ) : CardView {
        /** 卡片里列哪个对话、发出去的话进哪个对话:空闲卡片的新对话时为 null(由调用方新开一个)。 */
        val target: String? get() = if (fresh) null else conversationId
    }
}

/** 卡片底部输入框里的话交给谁:插话给在跑的任务、回答 Mika 的提问、同一个对话接着说、空闲时开始说。 */
enum class CardInputKind { Steer, Answer, Continue, Start }

data class CardInput(val hint: String, val kind: CardInputKind)

/** 详情里按状态放的内容;按钮都转成任务服务现有的动作。 */
sealed interface CardContent {
    /** 干活中:做过的步骤(最多 [CapsuleCards.STEPS] 步),最后一步是此刻在做的;停止在输入框右边。 */
    data class Working(val steps: List<String>) : CardContent

    /**
     * 要你接管:[need] 是那一句「需要你:…」,[body] 是为什么(模型写的话,付款时加上商品、金额、地址);
     * 排在别的接管后面时 [canTakeOver] 为 false,[queueLine] 写明先后。
     */
    data class Takeover(val need: String, val body: String, val queueLine: String?, val canTakeOver: Boolean) : CardContent

    /** 要你同意一次花钱的操作(只能同意这一次):和对话页的确认卡片同一套字和编号 [key]。 */
    data class Confirm(val title: String, val body: String, val key: Int) : CardContent

    /** Mika 问你:给了 [options] 就列成按钮;没有选项的回答在 Mika 里打字(卡片里的输入下一步再做)。 */
    data class Question(val prompt: String, val options: List<String>) : CardContent

    /** 等条件:为什么停;[resumable] 时给「继续」。 */
    data class Held(val why: String, val resumable: Boolean) : CardContent

    /** 办好了 / 没办成 / 已停止:回答在对话卡片的来回里。 */
    data class Result(val kind: CapsuleKind) : CardContent
}

/** 卡片内容的纯逻辑:挑哪一件、任务卡片还是对话卡片,每种状态放什么,步骤取哪几条。 */
object CapsuleCards {
    /** 干活中最多带几步(只为限住内存):卡片里那段和小画面一样高,放不下的旧步骤往上滚着看。 */
    const val STEPS = 50

    val QUEUED_AHEAD: String get() = tr(R.string.card_queued_ahead)

    /**
     * 展开时显示哪一件(只一件,没有列表,也不在卡片里切换;同时有别的事,到 Mika 的对话页看):
     * 先是刚在卡片里发了话的那个对话([follow])的任务(进行中的优先),好让用户在卡片里看到回答;
     * 否则按 [runs] 的先后取第一件——[CapsuleModel.cardRuns] 已排成要你处理(接管、同意、回答)在前,再是进行中的,
     * 最后是刚结束、还没消失的;一件都没有时是 [idle](为 null 就收起)。选出的那件是任务卡片还是对话卡片见 [taskCard]。
     */
    fun of(runs: List<CardRun>, follow: String? = null, idle: CardView.Chat? = null): CardView? {
        val pick = follow?.let { f ->
            runs.firstOrNull { it.run.conversationId == f && it.kind.active } ?: runs.firstOrNull { it.run.conversationId == f }
        } ?: runs.firstOrNull() ?: return idle
        return if (taskCard(pick)) CardView.Detail(pick, content(pick))
            else CardView.Chat(pick.run.conversationId, pick, content(pick))
    }

    /**
     * 按此刻的状态,不按经过:还没结束、此刻在操作 App 或网页([CapsuleRun.onScreen])的是任务卡片;
     * 别的(纯聊天、已经结束的,包括操作过 App、办完回了话的)都是对话卡片。
     */
    fun taskCard(r: CardRun): Boolean = r.kind.active && r.run.onScreen

    val HINT_STEER: String get() = tr(R.string.chat_placeholder_busy)
    val HINT_ANSWER: String get() = tr(R.string.card_hint_answer)
    val HINT_CONTINUE: String get() = tr(R.string.card_hint_continue)
    val HINT_START: String get() = tr(R.string.chat_empty_prompt)

    /** 卡片底部有没有输入框、交给谁:要你接管、要你同意时没有(先处理那件事);空闲卡片是开始说。 */
    fun input(card: CardView): CardInput? = when (card) {
        is CardView.Chat -> card.content?.let(::inputFor) ?: CardInput(HINT_START, CardInputKind.Start).takeIf { card.run == null }
        is CardView.Detail -> inputFor(card.content)
    }

    private fun inputFor(content: CardContent): CardInput? = when (content) {
        is CardContent.Working, is CardContent.Held -> CardInput(HINT_STEER, CardInputKind.Steer)
        is CardContent.Question -> CardInput(HINT_ANSWER, CardInputKind.Answer)
        is CardContent.Result -> CardInput(HINT_CONTINUE, CardInputKind.Continue)
        is CardContent.Takeover, is CardContent.Confirm -> null
    }

    /**
     * 卡片输入框里的话(连同截下的屏幕)进哪个对话:任务卡片、带着任务的对话卡片是那件事所在的对话;空闲卡片是它接着的对话,
     * 选了新对话或还没有任何对话时为 null(由调用方新开一个)。
     */
    fun conversationOf(card: CardView): String? = when (card) {
        is CardView.Detail -> card.run.run.conversationId
        is CardView.Chat -> card.run?.run?.conversationId ?: card.target
    }

    /** 任务还在进行(干活中、Mika 问你、等条件)时输入框右边的停止键停哪件;别的时候为 null。 */
    fun stopKey(card: CardView): String? {
        val (run, content) = when (card) {
            is CardView.Detail -> card.run to card.content
            is CardView.Chat -> (card.run ?: return null) to (card.content ?: return null)
        }
        val live = content is CardContent.Working || content is CardContent.Question || content is CardContent.Held
        return run.run.key.takeIf { live }
    }

    fun content(r: CardRun): CardContent {
        val ex = r.run.exchange
        return when (val s = r.run.state) {
            is TaskState.WaitingTakeover -> CardContent.Takeover(
                need = r.line,
                body = listOfNotNull(s.message.trim().ifEmpty { null }, StatusCopy.paymentLines(s.item, s.amount, s.address)).joinToString("\n"),
                queueLine = when {
                    r.ahead > 0 -> QUEUED_AHEAD
                    r.behind > 0 -> trPlural(R.plurals.capsule_behind, r.behind)
                    else -> null
                },
                canTakeOver = r.ahead == 0 && r.kind == CapsuleKind.NeedTakeover,
            )
            is TaskState.WaitingConfirmation -> (ex?.status as? ExchangeStatus.NeedsConfirmation)
                ?.let { CardContent.Confirm(it.title, it.body, it.key) }
                // 还没推出这一问一答时(刚开始跟)用包名代替 App 名
                ?: CardContent.Confirm(
                    ConfirmationCopy.title(s.request), ConfirmationCopy.detail(s.request, s.request.app),
                    ConfirmationNotifier.requestKey(s),
                )
            is TaskState.WaitingInput -> CardContent.Question(s.prompt.trim(), s.options.orEmpty().filter(String::isNotBlank))
            is TaskState.Held -> CardContent.Held(s.reason.why.trim().ifEmpty { tr(R.string.capsule_hold) }, s.reason.resumable)
            is TaskState.Succeeded -> CardContent.Result(CapsuleKind.Done)
            is TaskState.Failed -> CardContent.Result(CapsuleKind.Failed)
            TaskState.Cancelled -> CardContent.Result(CapsuleKind.Cancelled)
            else -> CardContent.Working(steps(ex, r.line))
        }
    }

    /** 最近 [STEPS] 步(旧的在前):实际做过的事(和对话页的步骤同一套说法),此刻在做的那句和最后一步不同时接在后面。 */
    fun steps(ex: Exchange?, line: String): List<String> {
        val done = ex?.steps.orEmpty().map { it.text }
        val all = if (line.isNotBlank() && done.lastOrNull() != line) done + line else done
        return all.takeLast(STEPS)
    }

    /** 卡片顶上的小字说这件事在什么状态。 */
    fun status(kind: CapsuleKind): String = when (kind) {
        CapsuleKind.HandedOver -> tr(R.string.capsule_handed_over)
        CapsuleKind.NeedTakeover -> tr(R.string.takeover_title)
        CapsuleKind.NeedConfirm -> tr(R.string.chat_wait_approval)
        CapsuleKind.NeedAnswer -> tr(R.string.chat_wait_answer)
        CapsuleKind.NeedStep -> tr(R.string.takeover_title)
        CapsuleKind.Held -> tr(R.string.capsule_hold)
        CapsuleKind.Running -> tr(R.string.capsule_running)
        CapsuleKind.Done -> tr(R.string.capsule_done)
        CapsuleKind.Failed -> tr(R.string.capsule_failed)
        CapsuleKind.Cancelled -> tr(R.string.capsule_stopped)
        CapsuleKind.Idle -> tr(R.string.app_name)
    }

    /** 这种内容旁边放不放虚拟屏小画面(还要那件事真有画面):干活中、要你接管、等条件时放。 */
    fun showsScreen(content: CardContent): Boolean =
        content is CardContent.Working || content is CardContent.Takeover || content is CardContent.Held
}
