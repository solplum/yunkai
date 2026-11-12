// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural

/** 对话页上按状态算出来的文字;不碰界面,单测直接测。 */
object ChatCopy {
    /** 空对话的问候,按时段。 */
    fun greeting(hour: Int): String = when (hour) {
        in 5..10 -> tr(R.string.chat_greeting_morning)
        in 11..12 -> tr(R.string.chat_greeting_noon)
        in 13..17 -> tr(R.string.chat_greeting_afternoon)
        in 18..22 -> tr(R.string.chat_greeting_evening)
        else -> tr(R.string.chat_greeting_night)
    }

    val EMPTY_PROMPT get() = tr(R.string.chat_empty_prompt)

    /** 空对话里的示例;点一下填进输入框,用户改好了再发。[kind] 决定配哪个线条图标。 */
    enum class ExampleKind { App, Web, File, Schedule, Device }
    data class Example(val kind: ExampleKind, val text: String)

    val examples get() = listOf(
        Example(ExampleKind.App, tr(R.string.chat_example_message)),
        Example(ExampleKind.App, tr(R.string.chat_example_group_summary)),
        Example(ExampleKind.Web, tr(R.string.chat_example_jobs)),
        Example(ExampleKind.App, tr(R.string.chat_example_music)),
        Example(ExampleKind.Device, tr(R.string.chat_example_alarms)),
        Example(ExampleKind.App, tr(R.string.chat_example_creator)),
    )

    /** 对话页顶栏的标题:固定是 App 名;副标题说此刻在做什么。 */
    val APP_TITLE get() = tr(R.string.app_name)

    /** 对话的名字:第一句话(只取第一行,附件那几行不算;只发了屏幕时是「你此刻的屏幕」);空对话为空。侧栏里用它称呼对话。 */
    fun title(exchanges: List<Exchange>): String =
        exchanges.firstOrNull { it.goal.isNotBlank() }?.goal?.let(::plainTitle).orEmpty()

    /**
     * 一句用户的话当标题怎么写:去掉附件那几行(只发了附件时是「照片」「合同.pdf」之类)和屏幕那一行(只发了屏幕时是「你此刻的屏幕」),
     * 只取第一行。
     */
    fun plainTitle(text: String): String =
        ScreenCopy.plain(AttachmentMessage.plain(text)).lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    enum class Tone { Accent, Warn }

    /** 顶栏副标题:正在做什么(强调色)或等你(橙色);没有任务在跑时为 null。[place] 是正在操作的 App 或网站。 */
    fun subtitle(ex: Exchange?, place: String?): Pair<String, Tone>? = when (ex?.status) {
        null -> null
        ExchangeStatus.Working -> (if (place.isNullOrBlank()) tr(R.string.chat_working) else tr(R.string.chat_working_on, place)) to Tone.Accent
        ExchangeStatus.WaitingUnlock -> tr(R.string.chat_wait_unlock) to Tone.Warn
        is ExchangeStatus.NeedsTakeover, is ExchangeStatus.NeedsInput,
        is ExchangeStatus.NeedsConfirmation, is ExchangeStatus.Paused -> tr(R.string.chat_waiting_for_you) to Tone.Warn
        else -> null
    }

    /** 进度那一行:在跑时是此刻的进展,停下来等你时是「已暂停」。 */
    fun progressLine(ex: Exchange): String =
        if (paused(ex.status)) tr(R.string.chat_paused) else liveLine(ex) ?: tr(R.string.chat_working)

    /** 任务停下来等你(要你处理、回答、同意、解锁、点继续)。 */
    fun paused(status: ExchangeStatus): Boolean =
        status is ExchangeStatus.NeedsTakeover || status is ExchangeStatus.NeedsInput ||
            status is ExchangeStatus.NeedsConfirmation || status is ExchangeStatus.Paused || status == ExchangeStatus.WaitingUnlock

    /** 进度行右边的用时:「0:24」「12:05」「1:02:03」。 */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = s % 3600 / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** 在跑时步骤只列最近这几步,前面的收成一行「前面还有 n 步」,点开看全部。 */
    const val VISIBLE_STEPS = 5

    fun earlierSteps(n: Int) = trPlural(R.plurals.chat_earlier_steps, n)

    /** 在后台操作手机时步骤下面那行小字。 */
    val BACKGROUND_NOTE get() = tr(R.string.chat_background_note)

    fun placeholder(mode: ComposerMode, hasHistory: Boolean): String = when (mode) {
        ComposerMode.Busy -> tr(R.string.chat_placeholder_busy)
        ComposerMode.Answer -> tr(R.string.chat_placeholder_answer)
        ComposerMode.Idle -> if (hasHistory) tr(R.string.chat_placeholder_more) else EMPTY_PROMPT
    }
}
