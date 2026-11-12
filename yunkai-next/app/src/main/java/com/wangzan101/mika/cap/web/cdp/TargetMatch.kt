package com.wangzan101.mika.cap.web.cdp

import com.wangzan101.mika.core.cap.web.cdp.CdpTargetInfo

/** 标签与 DevTools 目标的对应:先在标签里写下标记,再逐个候选目标求值找它。 */
object TargetMatch {
    /** 页面里放标记的全局变量名;找到后删掉。 */
    const val KEY = "__mikaDevtoolsMark"

    fun markScript(marker: String): String = "window['$KEY']='$marker';1"
    const val READ_SCRIPT = "window['$KEY']||''"
    const val CLEAR_SCRIPT = "delete window['$KEY'];1"

    /**
     * 要逐个试的目标:只要页面、没被别的客户端连着(有 webSocketDebuggerUrl)、不是别的标签已认领的;
     * 网址和标签当前网址相同的排前面(多半就是它)。
     */
    fun candidates(targets: List<CdpTargetInfo>, claimed: Set<String>, tabUrl: String?): List<CdpTargetInfo> =
        targets.filter { it.type == "page" && it.webSocketDebuggerUrl != null && it.id !in claimed }
            .sortedBy { if (!tabUrl.isNullOrEmpty() && it.url == tabUrl) 0 else 1 }

    fun isMarker(value: String?, marker: String): Boolean = marker.isNotEmpty() && value == marker
}
