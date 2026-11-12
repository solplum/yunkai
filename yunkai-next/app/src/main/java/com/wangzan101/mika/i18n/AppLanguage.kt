package com.wangzan101.mika.i18n

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * App 界面的语言:跟随系统、中文、English。资源里英文是默认(`values/`),中文在 `values-zh/`,
 * 所以「跟随系统」时中文系统显示中文,其他语言的系统显示英文。
 *
 * 安卓 13 起由系统的「应用语言」管(LocaleManager):系统记住选择、套到整个进程、重建界面,
 * 系统设置里的「应用语言」和这里的开关是同一个值。安卓 11、12 没有这项,由这里记在本地,
 * 经 [wrap] 套到界面的 Context、经 [resources] 套到界面以外取文字的地方,换了之后重建当前界面。
 */
enum class AppLanguage(val tag: String?) {
    System(null),
    Chinese("zh-Hans"),
    English("en");

    companion object {
        private const val PREFS = "app_language"
        private const val KEY_TAG = "tag"

        /** 安卓 12 及以前选定的语言;null 表示跟随系统。进程里读一次,换的时候更新。 */
        @Volatile
        private var legacyTag: String? = null
        @Volatile
        private var legacyLoaded = false

        /** 安卓 12 及以前按选定语言建出的资源,跟着语言换。 */
        @Volatile
        private var cached: Pair<String, Resources>? = null

        private val systemManaged get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        fun current(context: Context): AppLanguage = fromTag(
            if (systemManaged) {
                context.getSystemService(LocaleManager::class.java).applicationLocales.takeUnless { it.isEmpty }?.get(0)?.toLanguageTag()
            } else {
                legacy(context)
            },
        )

        /** 语言标签对应哪一项:中文的各种写法都是中文,其他非空的标签是英文。 */
        fun fromTag(tag: String?): AppLanguage = when {
            tag.isNullOrEmpty() -> System
            Locale.forLanguageTag(tag).language == "zh" -> Chinese
            else -> English
        }

        /** 换语言并重建界面。安卓 13 起交给系统(系统自己重建);之前记在本地,重建 [context] 所在的界面。 */
        fun set(context: Context, language: AppLanguage) {
            if (systemManaged) {
                context.getSystemService(LocaleManager::class.java).applicationLocales =
                    language.tag?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
                return
            }
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TAG, language.tag).commit()
            legacyTag = language.tag
            legacyLoaded = true
            cached = null
            applyDefaultLocale(context)
            context.activity()?.recreate()
        }

        /** App 启动时调:安卓 12 及以前选了语言时,把进程的默认 Locale(日期、数字的格式)也换成它。 */
        fun applyDefaultLocale(context: Context) {
            if (systemManaged) return
            val locales = legacyLocales(context) ?: Resources.getSystem().configuration.locales
            Locale.setDefault(locales[0])
        }

        /** 套上 App 语言的 Context:在 Activity 的 attachBaseContext 和非 Activity 建界面的地方用。安卓 13 起原样返回。 */
        fun wrap(base: Context): Context {
            val locales = legacyLocales(base) ?: return base
            return base.createConfigurationContext(Configuration().apply { setLocales(locales) })
        }

        /** 界面以外取文字用的资源:跟着 App 语言。 */
        fun resources(app: Context): Resources {
            val tag = if (systemManaged) null else legacy(app)
            if (tag == null) return app.resources
            cached?.takeIf { it.first == tag }?.let { return it.second }
            val r = app.createConfigurationContext(Configuration().apply { setLocales(LocaleList.forLanguageTags(tag)) }).resources
            cached = tag to r
            return r
        }

        private fun legacyLocales(context: Context): LocaleList? =
            if (systemManaged) null else legacy(context)?.let(LocaleList::forLanguageTags)

        private fun legacy(context: Context): String? {
            if (!legacyLoaded) {
                legacyTag = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, null)
                legacyLoaded = true
            }
            return legacyTag
        }

        private fun Context.activity(): Activity? {
            var c: Context? = this
            while (c is ContextWrapper) {
                if (c is Activity) return c
                c = c.baseContext
            }
            return null
        }
    }
}
