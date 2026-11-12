package com.wangzan101.mika.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * 浏览器页用手机版还是电脑版网页:
 * - 用户自己浏览默认手机版;在「更多」里换成电脑版的网站记在 [UserSiteModes],下次也用电脑版;
 * - 「给 Mika 登录」(从常用网站打开还没登录的网站)时整个标签照 Mika 办事时的身份走([agentMobile]),
 *   登录留下的 Cookie 和 Mika 办事时看到的网站是同一版,Mika 那边也是登录的。
 * Agent 自己的标签不看这里,按站点记住的那份照旧(默认电脑版)。
 */
object BrowserIdentity {
    /** 返回 true 用手机版。 */
    fun mobile(loginForMika: Boolean, agentMobile: Boolean, userDesktop: Boolean): Boolean =
        if (loginForMika) agentMobile else !userDesktop

    /** 从常用网站打开:还没登录的走「给 Mika 登录」。 */
    fun loginFlow(site: FavoriteSite): Boolean = !site.loggedIn
}

/**
 * 用户在浏览器页里换成电脑版的网站(默认手机版)。按主机名记,去掉开头的 www.;子域名跟着上级走
 * (记了 example.com,m.example.com 也用电脑版)。和 Agent 的那份分开存,互不影响。
 * 存在 App 私有目录的一个 JSON 文件里,坏了就从空表开始。
 */
class UserSiteModes(dir: File) {
    private val file = File(dir, FILE)
    private val desktop = LinkedHashSet<String>()

    init {
        runCatching {
            if (file.isFile) {
                (Json.parseToJsonElement(file.readText()) as? JsonArray)?.forEach { e ->
                    (e as? JsonPrimitive)?.contentOrNull?.let(::key)?.let { desktop += it }
                }
            }
        }
    }

    @Synchronized
    fun isDesktop(host: String?): Boolean {
        var h = host?.let(::key) ?: return false
        while (true) {
            if (h in desktop) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }

    /** 记下这个网站用哪一版;换回手机版时连上级域名的记录一起去掉,否则还会跟着上级走。 */
    @Synchronized
    fun set(host: String, desktop: Boolean) {
        val k = key(host) ?: return
        if (desktop) this.desktop += k
        else this.desktop.removeAll { k == it || k.endsWith(".$it") }
        save()
    }

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JsonArray(desktop.map(::JsonPrimitive)).toString())
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    private companion object {
        const val FILE = "browser_site_modes.json"

        fun key(host: String): String? =
            host.trim().trimEnd('.').lowercase().removePrefix("www.").takeIf { it.isNotEmpty() && ' ' !in it && '/' !in it }
    }
}
