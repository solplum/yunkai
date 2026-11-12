package com.wangzan101.mika

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * 真机测试里把 Mika 的界面固定成中文(断言写的是中文):安卓 13 起经系统的「应用语言」临时换成中文,测完换回;
 * 要放在最外层(`@get:Rule(order = Int.MIN_VALUE)`),在界面起来之前换好。
 * 安卓 12 及以前没有单个 App 的语言,测试界面跟着系统:请在中文系统上跑。
 */
class ChineseUi : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return base.evaluate()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            if (context.resources.configuration.locales[0].language == "zh") return base.evaluate()
            val lm = context.getSystemService(LocaleManager::class.java)
            val before = lm.applicationLocales
            lm.applicationLocales = LocaleList.forLanguageTags("zh-Hans")
            waitFor { context.resources.configuration.locales[0].language == "zh" }
            try {
                base.evaluate()
            } finally {
                lm.applicationLocales = before
            }
        }
    }

    private fun waitFor(ok: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 5_000
        while (!ok() && SystemClock.uptimeMillis() < until) SystemClock.sleep(50)
    }
}
