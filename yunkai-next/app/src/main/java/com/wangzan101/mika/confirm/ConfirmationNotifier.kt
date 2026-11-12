package com.wangzan101.mika.confirm

import com.wangzan101.mika.i18n.tr
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.wangzan101.mika.R
import com.wangzan101.mika.task.alertQuietly
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.risk.IrrevAction

/**
 * 确认卡片和通知上的文字。字段都是模型写的:只当纯文本用(不解析任何标记);
 * 换行、行分隔符压成空格,去掉控制字符、零宽字符和文字方向控制符;
 * 字段里的「·」和冒号换成别的符号,拼不出假的「· 金额:」一类字段。
 */
object ConfirmationCopy {
    /** 收起时那一行里内容显示的字数。 */
    const val CONTENT_MAX = 60
    /** 展开的卡片和通知里内容最多显示的字数(和申报时的上限一致)。 */
    const val DETAIL_MAX = 200
    /** 确认卡片上的说明:只有花钱的操作要你同意,每次只同意这一次。 */
    val NOTE: String get() = tr(R.string.confirm_note)

    fun actionLabel(action: IrrevAction): String = when (action) {
        IrrevAction.send -> tr(R.string.chat_send)
        IrrevAction.pay -> tr(R.string.confirm_pay)
        IrrevAction.delete -> tr(R.string.confirm_delete)
        IrrevAction.order -> tr(R.string.confirm_order)
        IrrevAction.submit -> tr(R.string.confirm_submit)
        IrrevAction.other -> tr(R.string.confirm_other)
    }

    fun title(r: ConfirmationRequest) = tr(R.string.confirm_title, actionLabel(r.action))

    /** 收起的一行:「在 <App 名> · 对象:<对谁> · 内容:<前 60 字> · 金额:<多少>」;空的字段不写。 */
    fun body(r: ConfirmationRequest, appName: String): String = fields(r, appName, CONTENT_MAX).joinToString(" · ")

    /** 展开的卡片和通知大字:一行一个字段,内容显示完整(最多 200 字)。 */
    fun detail(r: ConfirmationRequest, appName: String): String = fields(r, appName, DETAIL_MAX).joinToString("\n")

    private fun fields(r: ConfirmationRequest, appName: String, contentMax: Int): List<String> {
        val content = plain(r.content).let { if (it.length > contentMax) it.take(contentMax) + "…" else it }
        return listOfNotNull(
            tr(R.string.confirm_in_app, plain(appName)),
            plain(r.target).takeIf { it.isNotEmpty() }?.let { tr(R.string.confirm_target, it) },
            content.takeIf { it.isNotEmpty() }?.let { tr(R.string.confirm_content, it) },
            r.amount?.let(::plain)?.takeIf { it.isNotEmpty() }?.let { tr(R.string.payment_amount, it) },
        )
    }

    private val INVISIBLE = Regex("[\\u061C\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2069\\uFEFF]")
    private val SPACES = Regex("[\\s\\u2028\\u2029\\u0085\\u00A0\\u3000]+")

    private fun plain(s: String) = s.replace(INVISIBLE, "").replace(SPACES, " ")
        .filterNot { Character.isISOControl(it) }
        .replace('·', '/').replace(':', '∶').replace('：', '∶')
        .trim()
}

/** 确认关卡的醒目通知:和接管同一个高优先级频道,按钮「同意」「不同意」。 */
object ConfirmationNotifier {
    /** 通知按钮和卡片按钮带上它,服务据此丢掉点在旧通知、旧卡片上的回答。 */
    const val EXTRA_REQUEST = "confirmation_request"

    /** App 的显示名;读不到(没装、包不可见)时用包名。 */
    fun appLabel(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString().ifBlank { packageName }
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }

    /** 每次申报一个编号:循环每次申报都新建一个等待状态,同一进程里的服务和界面拿到的是同一个对象。 */
    fun requestKey(state: TaskState.WaitingConfirmation): Int = System.identityHashCode(state)

    /** 按钮 Intent 的 data:每次申报、每个按钮各不相同,系统不会把旧通知上的按钮换成新申报的。 */
    fun intentData(key: Int, approve: Boolean) = "mika-confirm://request/$key/" + if (approve) "approve" else "decline"

    fun build(
        context: Context, channel: String, state: TaskState.WaitingConfirmation, service: Class<*>, approveAction: String, declineAction: String,
        contentIntent: PendingIntent,
        /** 几个任务同时等确认时各用一段请求码,按钮再带上 [runExtra] 标明是哪个任务的。 */
        codeOffset: Int = 0,
        runExtra: Pair<String, String>? = null,
        /** 照发但不响、不弹横幅(悬浮胶囊正在显示时),见 [alertQuietly]。 */
        quiet: Boolean = false,
    ): Notification {
        val r = state.request
        val key = requestKey(state)
        val app = appLabel(context, r.app)
        fun button(title: Int, action: String, approve: Boolean, code: Int): Notification.Action {
            val pi = PendingIntent.getService(
                context, code + codeOffset,
                Intent(context, service).setAction(action).setData(Uri.parse(intentData(key, approve))).putExtra(EXTRA_REQUEST, key)
                    .apply { runExtra?.let { (k, v) -> putExtra(k, v) } },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
            )
            return Notification.Action.Builder(null, context.getString(title), pi).apply {
                // 锁屏上点「同意」要先解锁
                if (approve && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAuthenticationRequired(true)
            }.build()
        }
        // 锁屏上只露出要同意哪一类操作,对象和内容解锁后才看得到
        val public = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ConfirmationCopy.title(r))
            .setContentText(context.getString(R.string.confirmation_locked_hint))
            .build()
        return Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ConfirmationCopy.title(r))
            .setContentText(ConfirmationCopy.body(r, app))
            .setStyle(Notification.BigTextStyle().bigText(ConfirmationCopy.detail(r, app)))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(button(R.string.action_approve, approveAction, true, 20))
            .addAction(button(R.string.action_decline, declineAction, false, 21))
            .apply { if (quiet) alertQuietly() }
            .build()
    }
}
