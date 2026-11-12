package com.wangzan101.mika.cap.web

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import com.wangzan101.mika.core.cap.web.WebUrlPolicy

/**
 * 退出网站:尽力而为。系统接口只能按网址读到 Cookie 的名字,读不到路径、域与 HttpOnly 细节,
 * 所以对这个主机名及各级上级域(到可注册域名为止)读到的每个 Cookie,分别按「只属于主机」和「属于整个域」写一条过期记录,
 * 再清掉这些来源的网页存储。个别网站的登录可能仍在,彻底清除用 [logoutAll]。
 * [cookies]、[storage] 用 mika 网页配置的(见 [WebTab.cookieManager]);主线程调用。
 */
class WebLogin(private val cookies: CookieManager, private val storage: WebStorage, private val registry: SiteRegistry?) {

    /** 返回过期掉的 Cookie 个数(按名字计)。 */
    fun logout(host: String): Int {
        val h = WebUrlPolicy.host("https://${host.trim()}") ?: return 0
        var expired = 0
        for (d in domainsFor(h)) {
            for (scheme in listOf("https", "http")) {
                val url = "$scheme://$d/"
                val names = cookieNames(cookies.getCookie(url))
                for (name in names) expiryLines(name, d).forEach { cookies.setCookie(url, it) }
                if (scheme == "https") expired += names.size
                storage.deleteOrigin("$scheme://$d")
            }
            storage.deleteOrigin("https://www.$d")
        }
        cookies.flush()
        registry?.remove(h)
        return expired
    }

    /**
     * 全部退出并清除网页数据:所有 Cookie(含会话 Cookie)、所有网页存储、[views] 的缓存(缓存是整个网页配置共用的,
     * 给一个视图就清掉全部)、[database] 里记住的 HTTP 认证账号,站点登记表清空。仍是尽力而为。
     */
    fun logoutAll(views: List<WebView> = emptyList(), database: WebViewDatabase? = null) {
        cookies.removeSessionCookies(null)
        cookies.removeAllCookies(null)
        cookies.flush()
        storage.deleteAllData()
        views.forEach { runCatching { it.clearCache(true) } }
        database?.let { runCatching { it.clearHttpAuthUsernamePassword() } }
        registry?.clear()
    }

    companion object {
        /** 主机名和它的各级上级域,到可注册域名为止;认不出可注册域名时只有它自己。 */
        fun domainsFor(host: String): List<String> {
            val top = WebUrlPolicy.registrable(host) ?: return listOf(host)
            val out = mutableListOf<String>()
            var d = host
            while (true) {
                out += d
                if (d == top || !d.contains('.')) break
                d = d.substringAfter('.')
            }
            return out
        }

        /** Cookie 头里的名字,去重,保持顺序。 */
        fun cookieNames(header: String?): List<String> =
            header.orEmpty().split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }.distinct()

        /** 让一个 Cookie 过期的两条写法:只属于主机的,和属于整个域的。 */
        fun expiryLines(name: String, domain: String) = listOf(
            "$name=; Max-Age=0; Path=/",
            "$name=; Max-Age=0; Path=/; Domain=.$domain",
        )
    }
}
