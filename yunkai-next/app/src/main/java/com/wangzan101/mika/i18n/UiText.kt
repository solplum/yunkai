package com.wangzan101.mika.i18n

import android.content.res.Resources
import com.wangzan101.mika.R
import java.util.Locale
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * 界面文字从哪里取。App 里是按 App 语言取的安卓资源([ResourceTexts]);JVM 单测里换成直接读资源文件的实现。
 * Compose 里能用 stringResource 的地方照常用;这里给界面以外算文字的代码(状态文案、通知、服务、悬浮胶囊的数据)用。
 */
interface UiTexts {
    fun string(@StringRes id: Int, args: Array<out Any?>): String
    fun plural(@PluralsRes id: Int, count: Int, args: Array<out Any?>): String
}

/** 进程里此刻用的 [UiTexts]:App 启动时装上。 */
object UiText {
    @Volatile
    var source: UiTexts? = null

    internal fun require(): UiTexts = source ?: error("UiText.source is not installed")
}

/** 取一条界面文字;有 [args] 时按格式填进去(和 Resources.getString 一样)。 */
fun tr(@StringRes id: Int, vararg args: Any?): String = UiText.require().string(id, args)

/** 取一条随数量变化的界面文字;不给 [args] 时把 [count] 当唯一的参数填进去。 */
fun trPlural(@PluralsRes id: Int, count: Int, vararg args: Any?): String = UiText.require().plural(id, count, args)

/** 安卓资源实现;[resources] 每次取,换了语言就跟着换。 */
class ResourceTexts(private val resources: () -> Resources) : UiTexts {
    override fun string(id: Int, args: Array<out Any?>): String =
        if (args.isEmpty()) resources().getString(id) else resources().getString(id, *args)

    override fun plural(id: Int, count: Int, args: Array<out Any?>): String =
        resources().getQuantityString(id, count, *(if (args.isEmpty()) arrayOf(count) else args))
}

/** 界面语言对应的 Locale:星期、月份的名字按它写。 */
fun uiLocale(): Locale = Locale.forLanguageTag(tr(R.string.ui_locale))
