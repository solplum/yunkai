package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.uistate.KeyCombo
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenCheck
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.WebInfo
import com.wangzan101.mika.core.uistate.PageDialog

/** 假网页端口:记录调用,结果可按测试改。读屏编号 w<tab>-<n>,和手机的永不相同。 */
class FakeWebPort : WebPort {
    val calls = mutableListOf<String>()
    var url = "https://shop.example.com/cart"
    var title = "购物车"
    var tabId = "t1"
    /** 当前标签的版本(siteMode 的回答)。 */
    var mode: String? = "desktop"
    override suspend fun siteMode(): String? = mode
    var elements: List<UiElement> = listOf(
        UiElement("@e1", "button", "搜索", 540, 120, listOf(0, 0, 999, 100), "#go", true, false, false),
        UiElement("@e2", "file", "选择文件", 540, 600, listOf(0, 200, 999, 300), "#pick", true, false, false),
    )
    var result: ActionResult = ActionResult.Ok(changed = true)
    var externalLink: ExternalLink? = null
    var crash = false
    var page = PageText(url, title, "正文", listOf("下一页" to "https://shop.example.com/2"), emptyList(), false, null)
    var eval: EvalJs = EvalJs.Ok("42", false)
    var tabList = listOf(TabInfo("t1", url, title, true), TabInfo("t2", "https://example.org/", "其他", false))
    var downloadList = listOf(DownloadInfo("/workspace/downloads/a.pdf", 1234, "application/pdf", "done", null))
    var reads = 0
    var check: ScreenCheck? = null
    /** 当前标签正交给用户:读页、执行脚本、打开网址、切换标签都报 handed_over。 */
    var handedOver = false
    var downloadsAvailable = true
    /** null:不支持切换手机版/桌面版(用接口的默认实现)。 */
    var siteModeHost: String? = null
    /** 有没有 DevTools:没有时网络记录与等网络空闲报不可用。 */
    var devtools = false
    var captured: List<CapturedResponse> = emptyList()
    var waitOutcome = WaitOutcome(true, 120, url)
    /** 页面弹出、等回话的弹框;读屏里带上。 */
    var dialog: PageDialog? = null
    override suspend fun answerDialog(accept: Boolean, text: String?): ActionResult {
        if (dialog == null) return ActionResult.Failed(NO_DIALOG)
        calls += "dialog $accept ${text ?: "-"}"
        dialog = null
        return result
    }
    /** 停留时轻轻滚一下的回答:false 表示这会儿不便滚。 */
    var idleOk = true
    /** 轻轻滚一下时页面的变化(测试按需换掉元素)。 */
    var onIdle: () -> Unit = {}
    override suspend fun idleScroll(fraction: Double): Boolean { calls += "idle $fraction"; if (idleOk) onIdle(); return idleOk }
    override suspend fun doubleTapRef(ref: String, snapshotId: String): ActionResult { calls += "doubleTapRef $ref $snapshotId"; return result }

    private fun read() = ScreenSnapshot("w$tabId-${++reads}", null, elements, 1080, 2400, web = WebInfo(url, title, tabId, 200, dialog = dialog))
    override suspend fun snapshot() = read().also { calls += "snapshot" }
    // 和真实端口一样:看一眼不登记编号,不算一次读屏
    override suspend fun peek() = ScreenSnapshot("w$tabId-peek", null, elements, 1080, 2400, web = WebInfo(url, title, tabId, 200, dialog = dialog))
    override suspend fun screenshotPng(): ByteArray? = null
    override suspend fun tapRef(ref: String, snapshotId: String): ActionResult { calls += "tapRef $ref $snapshotId"; return result }
    override suspend fun tapXY(x: Int, y: Int): ActionResult { calls += "tapXY $x $y"; return result }
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult { calls += "type $ref $text"; return result }
    override suspend fun clearText(ref: String?, snapshotId: String?): ActionResult { calls += "clear $ref"; return result }
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult { calls += "scroll"; return result }
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ActionResult { calls += "swipe"; return result }
    override suspend fun back(): ActionResult { calls += "back"; return result }
    override suspend fun pressKey(keyCode: Int): ActionResult { calls += "key $keyCode"; return result }
    override suspend fun openApp(packageName: String, cold: Boolean): ActionResult { calls += "open $packageName"; return result }
    override suspend fun waitStable(timeoutMs: Long) = true
    override suspend fun listApps(): List<Pair<String, String>> = emptyList()
    override suspend fun checkScreen() = check

    override suspend fun navigate(url: String, newTab: Boolean): NavResult {
        calls += "navigate $url $newTab"
        if (crash) throw WebTabCrashed(tabId)
        if (handedOver) throw WebTabHandedOver(tabId)
        if (externalLink == null) this.url = url
        return NavResult(TabInfo(tabId, this.url, title, true), true, 200, newTab, externalLink)
    }
    override suspend fun readPage(maxChars: Int, offset: Int, scrollLazy: Boolean): PageText {
        if (handedOver) throw WebTabHandedOver(tabId)
        calls += "readPage $maxChars $offset" + if (scrollLazy) "" else " noscroll"; return page
    }
    /** web_read_page 的质量信号;后台读网址时 [readUrlError] 不为空就当打不开。 */
    var quality: ReadQuality? = null
    var readUrlError: String? = null
    override suspend fun read(req: PageRead): PageText {
        if (req.url == null) return readPage(req.maxChars, req.offset, req.lazy).copy(quality = quality)
        calls += "readUrl ${req.url}" + if (req.unfold) " unfold" else ""
        return page.copy(url = req.url, quality = quality, netError = readUrlError)
    }
    /** 最近一次读脚本收到的参数(站点命令的 args)。 */
    var evalArgs: kotlinx.serialization.json.JsonObject? = null
    override suspend fun evaluate(script: String, timeoutMs: Long, args: kotlinx.serialization.json.JsonObject?): EvalJs {
        if (handedOver) throw WebTabHandedOver(tabId)
        evalArgs = args
        calls += "eval ${script.length}"; return eval
    }
    override suspend fun tabs() = tabList
    override suspend fun switchTab(tabId: String): TabInfo? {
        calls += "switch $tabId"
        if (handedOver) throw WebTabHandedOver(tabId)
        return tabList.firstOrNull { it.tabId == tabId }?.also { this.tabId = it.tabId }
    }
    override suspend fun closeTab(tabId: String): Boolean { calls += "close $tabId"; return tabList.any { it.tabId == tabId } }
    override suspend fun downloads(): List<DownloadInfo> {
        if (!downloadsAvailable) throw UnsupportedOperationException("downloads")
        return downloadList
    }
    override suspend fun upload(ref: String, snapshotId: String, paths: List<String>): ActionResult {
        calls += "upload $ref ${paths.joinToString(",")}"; return result
    }
    override suspend fun historyBack(): ActionResult { calls += "historyBack"; return result }
    override suspend fun setSiteMode(mobile: Boolean): SiteModeResult? {
        val host = siteModeHost ?: return super.setSiteMode(mobile)
        calls += "siteMode ${if (mobile) "mobile" else "desktop"}"
        return SiteModeResult(host, mobile, reloaded = true)
    }

    override suspend fun hover(at: WebPoint): ActionResult { calls += "hover $at"; return result }
    override suspend fun drag(from: WebPoint, to: WebPoint, steps: Int): ActionResult { calls += "drag $from $to $steps"; return result }
    override suspend fun selectOption(ref: String, snapshotId: String, option: String): ActionResult { calls += "select $ref $snapshotId $option"; return result }
    override suspend fun pressCombo(combo: KeyCombo): ActionResult { calls += "combo $combo"; return result }
    override suspend fun captures(urlContains: String?, method: String?, sinceLastAction: Boolean): List<CapturedResponse> {
        if (!devtools) throw DevtoolsUnavailable()
        calls += "captures $urlContains $method $sinceLastAction"; return captured
    }
    override suspend fun capture(id: String): CapturedResponse? {
        if (!devtools) throw DevtoolsUnavailable()
        calls += "capture $id"; return captured.firstOrNull { it.id == id }
    }
    override suspend fun waitFor(kind: WaitKind, value: String?, timeoutMs: Long): WaitOutcome {
        calls += "wait ${kind.wire} $value $timeoutMs"
        if (kind == WaitKind.NETWORK_IDLE && !devtools) throw DevtoolsUnavailable()
        return waitOutcome
    }
}
