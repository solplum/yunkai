package com.wangzan101.mika.cap.web

import android.content.Context
import com.wangzan101.mika.cap.web.cdp.WebDevtools

/** 网页在进程里的那一份 DevTools 会话入口、站点登记表与标签总数上限:由 App 给出,设置页和各任务共用。 */
interface WebOwner {
    val webDevtools: WebDevtools
    val webSites: SiteRegistry
    /** 各任务的标签池共用的标签总数上限。 */
    val tabBudget: TabBudget

    companion object {
        fun of(context: Context): WebOwner = context.applicationContext as? WebOwner
            ?: error("the Application (${context.applicationContext.javaClass.name}) must implement WebOwner to give the browser's shared state")
    }
}
