package com.wangzan101.mika.cap.web

import android.content.Context
import android.webkit.WebViewDatabase

/** 站点登记表的设置页操作;登记表是进程里那一份(App 给出,设置页和标签池共用)。 */
object WebSites {
    fun registry(context: Context): SiteRegistry = WebOwner.of(context).webSites

    /** 设置页用的退出操作:mika 网页配置的 Cookie 与网页存储。主线程调用。 */
    fun login(context: Context): WebLogin = WebLogin(WebTab.cookieManager(), WebTab.webStorage(), registry(context))

    /**
     * 设置页的「全部退出并清除网页数据」:没有开着的标签也要清缓存,所以临时开一个同配置的看不见的标签来清,用完即关。主线程调用。
     */
    fun logoutAll(context: Context) {
        val tmp = WebTab(context.applicationContext, "clear", "settings")
        try {
            login(context).logoutAll(listOf(tmp.view), WebViewDatabase.getInstance(context))
        } finally {
            tmp.destroy()
        }
    }
}
