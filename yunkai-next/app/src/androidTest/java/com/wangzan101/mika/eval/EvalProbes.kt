package com.wangzan101.mika.eval

import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.core.eval.APP_CAPABILITIES
import com.wangzan101.mika.core.eval.AppCapabilities
import com.wangzan101.mika.core.eval.PROBE_ALLOWLIST
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import com.wangzan101.mika.cap.web.PageScript
import com.wangzan101.mika.cap.web.WebHost
import com.wangzan101.mika.cap.web.WebSites
import com.wangzan101.mika.cap.web.WebTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * 评测器自己对设备的只读查看:白名单探针、前置条件、任务结束后在虚拟屏上开 App 找字。
 * [sh] 以 shell 身份执行命令;[loggedIn] 是人工标记「已登录」的包名列表文件(一行一个包名)。
 */
class EvalProbes(private val sh: (String) -> String, private val loggedIn: File) {

    /** 只执行 [PROBE_ALLOWLIST] 里的命令;任务文件写不进任意命令。 */
    fun run(name: String): String {
        val cmd = PROBE_ALLOWLIST[name] ?: error("probe not in allowlist: $name")
        return sh(cmd).trim()
    }

    /**
     * 这台手机上能处理某类事的 App(见 [APP_CAPABILITIES]):按标准 Intent 查,默认处理的排在前面。
     * 命令只由能力表拼出,任务文件写不进去。没有标准 Intent 的能力返回空。
     */
    fun appCandidates(capability: String): List<String> = APP_CAPABILITIES[capability].orEmpty()
        .flatMap { i -> AppCapabilities.commands(i).flatMap { cmd -> AppCapabilities.packagesIn(runCatching { sh(cmd) }.getOrDefault("")) } }
        .distinct()

    /**
     * `installed:<包名>`、`app:<能力>`、`signedIn:<包名或网站>`(旧写法 `login:<包名>`、`site:<主机名>`)。
     * 登录态:网站只认站点登记表里「用户在 Mika 里登录过」的(在设置「网站登录」里登录过);App 只认人工标记([loggedIn]),
     * 评测器不替用户判断 App 的登录态(没有对所有 App 都可靠的探法)。没登录的题跳过,原因写「未登录」。
     */
    fun requirementMet(req: String): Pair<Boolean, String> {
        val kind = req.substringBefore(':')
        val pkg = req.substringAfter(':', "")
        when (kind) {
            "site" -> return siteSignedIn(pkg)?.let { if (it) true to "" else false to "未登录:$pkg 没有在设置「网站登录」里登录过" }
                ?: (false to "前置条件写法不对:$req")
            "signedIn" -> return if (siteSignedIn(pkg) == true || appMarked(pkg)) true to ""
                else false to "未登录:$pkg(网站要在设置「网站登录」里登录过,App 要标记在 ${loggedIn.name} 里)"
            "app" -> return if (appCandidates(pkg).isNotEmpty()) true to "" else false to "这台手机上没有能处理「$pkg」的 App"
        }
        if (!PACKAGE.matches(pkg)) return false to "前置条件写法不对:$req"
        return when (kind) {
            "installed" ->
                if (sh("pm path $pkg").contains("package:")) true to "" else false to "没装 $pkg"
            "login" -> if (appMarked(pkg)) true to "" else false to "未登录:$pkg 没有标记为已登录(${loggedIn.name})"
            else -> false to "不认识的前置条件:$req"
        }
    }

    /** 人工标记为已登录的 App(一行一个包名)。 */
    private fun appMarked(pkg: String) = runCatching { loggedIn.readLines().map { it.trim() } }.getOrDefault(emptyList()).contains(pkg)

    /** 网站在设置「网站登录」里登录过:同一个可注册域名才算(认不出可注册域名时要主机名完全相同);写法不是网站时为 null。 */
    private fun siteSignedIn(site: String): Boolean? {
        val host = WebUrlPolicy.host("https://$site") ?: return null
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val want = WebUrlPolicy.registrable(host) ?: host
        return WebSites.registry(ctx).list().any { it.loggedInByUser && (WebUrlPolicy.registrable(it.host) ?: it.host) == want }
    }

    /** 在一块新虚拟屏上冷启动 [pkg](或用它打开深链接),读屏直到看到 [text] 或超时。 */
    suspend fun screenHas(pkg: String, text: String, deepLink: String?): Boolean {
        require(PACKAGE.matches(pkg)) { "bad package: $pkg" }
        val shell = DeviceSupport.shell()
        val metrics = DisplayMetrics().also {
            InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
        }
        val display = AgentDisplay(shell, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        try {
            val screen = AndroidScreen(DeviceSupport.service(), shell, DeviceSupport.ready(display))
            if (deepLink == null) {
                if (screen.openApp(pkg, cold = true) !is ActionResult.Ok) return false
            } else {
                // 链接只当数据传给 am,不能带引号拼出别的命令
                require('\'' !in deepLink && '\n' !in deepLink) { "bad deep link" }
                sh("am start --display ${display.displayId} -a android.intent.action.VIEW -d '$deepLink' -p $pkg")
            }
            val want = text.replace(WS, "")
            val end = System.currentTimeMillis() + SCREEN_WAIT_MS
            while (true) {
                val seen = screen.snapshot().elements.joinToString("") { it.name }.replace(WS, "")
                if (want in seen) return true
                if (System.currentTimeMillis() > end) return false
                delay(500)
            }
        } finally {
            display.release()
        }
    }

    /**
     * 在一个新的后台网页标签里打开 [url],读整页文字(至多 [WEB_PAGES] 段),找 [text](忽略空白)。
     * 用 mika 网页配置,登录状态和 Agent 用的一样。
     */
    suspend fun webHas(url: String, text: String): Boolean {
        require(WebUrlPolicy.checkNavigable(url) == null) { "bad url: $url" }
        val instr = InstrumentationRegistry.getInstrumentation()
        val ctx = instr.targetContext
        val metrics = DisplayMetrics().also {
            ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
        }
        lateinit var host: WebHost
        lateinit var tab: WebTab
        withContext(Dispatchers.Main) {
            host = WebHost(ctx, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
            tab = WebTab(ctx, "probe", "eval")
            host.attach(tab)
        }
        try {
            tab.load(url)
            val page = PageScript(tab)
            page.install()
            val want = text.replace(WS, "")
            var offset = 0
            repeat(WEB_PAGES) {
                val o = runCatching { Json.parseToJsonElement(page.readPage(30_000, offset)).jsonObject }.getOrNull() ?: return false
                val got = (o["text"] as? JsonPrimitive)?.content.orEmpty().replace(WS, "")
                if (want in got) return true
                offset = (o["nextOffset"] as? JsonPrimitive)?.intOrNull ?: return false
            }
            return false
        } finally {
            withContext(Dispatchers.Main + NonCancellable) {
                tab.destroy()
                host.close()
            }
        }
    }

    private companion object {
        const val WEB_PAGES = 5
        val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
        val WS = Regex("\\s+")
        const val SCREEN_WAIT_MS = 10_000L
    }
}
