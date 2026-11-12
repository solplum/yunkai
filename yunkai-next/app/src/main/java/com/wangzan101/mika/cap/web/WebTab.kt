package com.wangzan101.mika.cap.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.wangzan101.mika.core.cap.web.DialogReply
import com.wangzan101.mika.core.cap.web.ExternalLink
import com.wangzan101.mika.core.cap.web.PageDialogs
import com.wangzan101.mika.core.uistate.PageDialog
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import com.wangzan101.mika.cap.web.cdp.WebDevtools
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一个网页标签:一个 WebView 加它的加载状态。默认打开桌面版网页,按站点可换回手机版(见 [WebDevtools])。
 * 必须在主线程创建;[assetLoaders] 只给本地夹具这类 App 自带的网页用(每个域名一个)。
 * 系统支持多配置时用名为 mika 的独立网页配置(Cookie、存储与系统里别的 WebView 分开)。
 * 页面的权限申请(定位、相机、麦克风、MIDI、受保护媒体)一律静默拒绝;跳 App 的链接不跳;下载暂不接收。
 * 页面弹框(alert/confirm/prompt、离开页面确认)不弹系统对话框,记进 [dialogs] 等 Agent(或用户自己用的浏览器页)回话。
 */
@SuppressLint("SetJavaScriptEnabled")
class WebTab(
    context: Context,
    val id: String,
    val ownerTaskId: String,
    private val assetLoaders: List<WebViewAssetLoader> = emptyList(),
    /** 这个标签的 DevTools 会话入口;默认是进程里那一份。 */
    val devtools: WebDevtools = WebDevtools.of(context),
) {
    /** 标签池接收标签上发生的事。都在主线程回调。 */
    interface Listener {
        fun onPageFinished(tab: WebTab) {}
        fun onCrashed(tab: WebTab) {}
        fun onExternalLink(tab: WebTab, link: ExternalLink) {}
        /** 页面要开新窗口;返回 true 表示已接下(把新标签的 WebView 交给 [resultMsg])。 */
        fun onCreateWindow(tab: WebTab, isUserGesture: Boolean, resultMsg: Message): Boolean = false
        /** 弹出的新标签第一次要去别的网站:标签池关掉它。 */
        fun onPopupRefused(tab: WebTab, url: String) {}
        /** 页面触发了下载(只保存,不打开、不安装)。 */
        fun onDownload(tab: WebTab, url: String, userAgent: String, contentDisposition: String?, mime: String?) {}
        /** 页面地址、标题或加载进度变了(给看得见的标签刷新地址栏和进度条)。 */
        fun onPageChanged(tab: WebTab) {}
        /** 页面弹了框,记在 [dialogs] 里等回话。 */
        fun onDialog(tab: WebTab) {}
        /** 收到了当前页面的网站图标。 */
        fun onIcon(tab: WebTab, icon: Bitmap) {}
    }

    /**
     * web_upload 先放在这里的、要交给页面文件选择框的文件(工作区文件的 content:// 地址);
     * 选择框弹出时取走一次。没有待交的文件时选择框直接取消。只在主线程读写。
     */
    var pendingUpload: List<Uri>? = null

    /** 页面弹了选择框、却没有待交的文件(被取消)的次数。 */
    var choosersRefused: Int = 0
        private set

    val view: WebView = WebView(context)

    /** 这个标签上等回话的页面弹框。 */
    val dialogs = PageDialogs()

    var listener: Listener? = null

    /** 由页面弹出时为打开它的页面地址。弹出的标签终生只能在这个网站里导航。 */
    var popupOpener: String? = null

    // 弹出的标签已经在同站页面上开始加载过:之后再要去别的网站只拦下那次导航,不再关标签
    private var popupCommitted = false

    /** 主文档是否正在加载。只在主线程读写。 */
    var loading: Boolean = false
        private set

    /** 主文档的加载进度(0–100)。只在主线程读写。 */
    var progress: Int = 0
        private set

    /** 最近一次 [load] 是否等到了加载完成(含出错页面);超时为 false。 */
    var lastLoadFinished: Boolean = false
        private set

    /** 最近一次主文档的 HTTP 错误状态(4xx/5xx);拿不到或没出错为 null。 */
    var httpErrorStatus: Int? = null
        private set

    /** 最近一次主文档没打开时浏览器给的错误码(如 net::ERR_NAME_NOT_RESOLVED);打开了或没出错为 null。 */
    var mainFrameError: String? = null
        private set

    /** 最近一次 [load] 期间页面变成了下载(主文档是个文件)。 */
    var loadBecameDownload: Boolean = false
        private set

    /** 最近一次被拦下的跳 App 链接;[load] 开始时清空。 */
    var lastExternalLink: ExternalLink? = null
        private set

    /** [destroy] 之后为 true。 */
    @Volatile var destroyed: Boolean = false
        private set

    /** 渲染进程崩溃或被系统回收后为 true,该标签不能再用,由上层重新打开。 */
    @Volatile var crashed: Boolean = false
        private set

    private var pending: CompletableDeferred<Boolean>? = null
    // 发起新加载后,要等到它自己的 onPageStarted 才认 onPageFinished,上一次导航迟到的完成回调不算数
    private var armed = false
    private var mainFrameFailed = false
    private var mainUrl: String? = null

    init {
        // 配置要在 WebView 做任何别的事之前设定
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            ProfileStore.getInstance().getOrCreateProfile(PROFILE)
            WebViewCompat.setProfile(view, PROFILE)
        }
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            @Suppress("DEPRECATION")
            saveFormData = false
            setGeolocationEnabled(false)
            // 新窗口经 onCreateWindow 交给标签池;脚本不能不经用户操作就自己开窗口
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
        }
        devtools.prepare(this, context)
        // 第三方 Cookie 关闭(裁定);后续确有站点需要再单独定
        cookieManager().setAcceptThirdPartyCookies(view, false)
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoaders.firstNotNullOfOrNull { it.shouldInterceptRequest(request.url) }

            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (request.isForMainFrame && refusePopup(url)) return true
                // 跳 App 的链接(intent:、自定义协议、应用商店)不跳;只有主文档的才告诉标签池(子框架里的静默拦下)
                WebUrlPolicy.external(url)?.let { link ->
                    if (request.isForMainFrame) {
                        lastExternalLink = link
                        listener?.onExternalLink(this@WebTab, link)
                    }
                    return true
                }
                return false
            }

            override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                // 弹出窗口的第一次导航不一定经过 shouldOverrideUrlLoading:这里再核对一次
                if (url != null && refusePopup(url)) {
                    v.stopLoading()
                    return
                }
                loading = true
                armed = true
                mainFrameFailed = false
                mainFrameError = null
                httpErrorStatus = null
                mainUrl = url
                devtools.onPageStarted(this@WebTab, url)
                listener?.onPageChanged(this@WebTab)
            }

            override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
                listener?.onPageChanged(this@WebTab)
            }

            // 页面开始显示时再核对一次弹出窗口的网站(前两处都可能被绕过时的最后一道)
            override fun onPageCommitVisible(v: WebView, url: String?) {
                if (url != null && refusePopup(url)) v.stopLoading()
            }

            override fun onPageFinished(v: WebView, url: String?) {
                if (!armed) return
                loading = false
                finish(!mainFrameFailed)
                listener?.onPageFinished(this@WebTab)
            }

            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    mainFrameFailed = true
                    mainFrameError = error.description?.toString()?.takeIf { it.isNotBlank() } ?: "net::error ${error.errorCode}"
                }
            }

            override fun onReceivedHttpError(v: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) {
                    httpErrorStatus = response.statusCode
                    mainFrameFailed = true
                }
            }

            // 证书有问题一律不继续
            override fun onReceivedSslError(v: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                if (error.url == mainUrl) {
                    mainFrameFailed = true
                    mainFrameError = "certificate_error ${error.primaryError}"
                }
            }

            // 返回 true 才不会连带整个 App 被杀;标签作废,由标签池销毁
            override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "renderer gone for tab $id crashed=${detail.didCrash()}")
                crashed = true
                loading = false
                (view.parent as? ViewGroup)?.removeView(view)
                finish(false)
                listener?.onCrashed(this@WebTab)
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(v: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                if (resultMsg == null) return false
                return listener?.onCreateWindow(this@WebTab, isUserGesture, resultMsg) ?: false
            }

            override fun onProgressChanged(v: WebView, newProgress: Int) {
                progress = newProgress
                listener?.onPageChanged(this@WebTab)
            }

            override fun onReceivedTitle(v: WebView, title: String?) {
                listener?.onPageChanged(this@WebTab)
            }

            override fun onReceivedIcon(v: WebView, icon: Bitmap?) {
                if (icon != null) listener?.onIcon(this@WebTab, icon)
            }

            // 页面弹框:记下就返回(返回 true 表示自己处理,系统不弹框),不占界面线程;回话前页面脚本停着
            override fun onJsAlert(v: WebView, url: String?, message: String?, result: JsResult): Boolean =
                held(PageDialog.ALERT, message, null, result)

            override fun onJsConfirm(v: WebView, url: String?, message: String?, result: JsResult): Boolean =
                held(PageDialog.CONFIRM, message, null, result)

            override fun onJsPrompt(v: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean =
                held(PageDialog.PROMPT, message, defaultValue, result)

            override fun onJsBeforeUnload(v: WebView, url: String?, message: String?, result: JsResult): Boolean =
                held(PageDialog.BEFORE_UNLOAD, message, null, result)

            // 用户看不见这个标签,弹框授权等于替用户点同意:一律拒绝
            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                callback?.invoke(origin, false, false)
            }

            // 文件选择框:只交 web_upload 事先放好的工作区文件,取走即清;没有就取消,不弹系统选择器
            override fun onShowFileChooser(v: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                val files = pendingUpload
                pendingUpload = null
                if (files.isNullOrEmpty()) {
                    choosersRefused++
                    callback.onReceiveValue(null)
                    return true
                }
                val picked = if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) files else files.take(1)
                callback.onReceiveValue(picked.toTypedArray())
                return true
            }
        }
        // 下载暂不接收,由下载模块接手
        // 下载交给标签池(落到工作区);文件不打开、不安装
        view.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val l = listener
            if (l == null) Log.i(TAG, "download ignored on tab $id: ${url.take(80)}")
            else l.onDownload(this, url, userAgent.orEmpty(), contentDisposition, mimetype)
            // 正在等加载的那次导航变成了下载:页面不会再加载,不用等到超时
            if (pending != null) {
                loadBecameDownload = true
                loading = false
                finish(false)
            }
        }
    }

    /** 记下页面弹框;回话在主线程交给浏览器(JsResult 只能回一次)。 */
    private fun held(kind: String, message: String?, defaultText: String?, result: JsResult): Boolean {
        val text = message.orEmpty().take(PageDialog.MAX_MESSAGE)
        dialogs.open(PageDialog(kind, text, defaultText?.take(PageDialog.MAX_MESSAGE)), object : DialogReply {
            override fun accept(text: String?) {
                view.post { if (result is JsPromptResult && text != null) result.confirm(text) else result.confirm() }
            }
            override fun dismiss() {
                view.post { result.cancel() }
            }
        })
        Log.i(TAG, "page dialog $kind held on tab $id")
        listener?.onDialog(this)
        // 加载中弹框:页面停在这里了,加载等待不再等到超时
        if (pending != null) finish(true)
        return true
    }

    /**
     * 弹出的标签要导航到和打开它的页面不同的网站(about:blank 除外):拦下。还没在同站页面上加载过时交标签池关掉它,
     * 之后只拦这次导航。
     */
    private fun refusePopup(url: String): Boolean {
        val opener = popupOpener ?: return false
        if (url == "about:blank" || WebUrlPolicy.sameSite(opener, url)) {
            if (url != "about:blank") popupCommitted = true
            return false
        }
        if (!popupCommitted) listener?.onPopupRefused(this, url)
        else Log.i(TAG, "popup tab $id kept on its site, refused ${url.take(80)}")
        return true
    }

    private fun finish(ok: Boolean) {
        pending?.complete(ok)
        pending = null
        armed = false
    }

    /** 打开网址并等主文档加载完;超时、主文档出错或 HTTP 4xx/5xx 返回 false。只接受 http/https 网址。 */
    suspend fun load(url: String, timeoutMs: Long = 20_000): Boolean = withContext(Dispatchers.Main) {
        if (crashed) return@withContext false
        WebUrlPolicy.checkNavigable(url)?.let {
            Log.w(TAG, "refused to load a non-web address on tab $id: $it")
            lastLoadFinished = false
            return@withContext false
        }
        // 第一次导航前把桌面版仿真设好(连不上时至多等一小会儿,照常打开)
        devtools.beforeLoad(this@WebTab, url)
        if (crashed || destroyed) return@withContext false
        finish(false)
        lastExternalLink = null
        lastLoadFinished = false
        mainFrameError = null
        loadBecameDownload = false
        val done = CompletableDeferred<Boolean>()
        pending = done
        loading = true
        view.loadUrl(url)
        val ok = withTimeoutOrNull(timeoutMs) { done.await() }
        lastLoadFinished = ok != null
        if (ok == null) {
            if (pending === done) finish(false)
            view.stopLoading()
            loading = false
        }
        ok ?: false
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        dialogs.close()
        devtools.release(this)
        listener = null
        finish(false)
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.destroy()
    }

    companion object {
        private const val TAG = "MikaWebTab"
        private const val PROFILE = "mika"

        /**
         * 网页登录数据所在的 Cookie 库:支持多配置时是 mika 配置的,否则是默认的。主线程调用。
         * 不支持多配置时它和本 App 进程里任何别的 WebView 共用:App 里不得再有别的 WebView 处理网站登录,否则登录态会混在一起。
         */
        fun cookieManager(): CookieManager =
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                ProfileStore.getInstance().getOrCreateProfile(PROFILE).cookieManager
            } else {
                CookieManager.getInstance()
            }

        /** 和 [cookieManager] 同一个配置的网页存储(localStorage、IndexedDB 等)。主线程调用。 */
        fun webStorage(): WebStorage =
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                ProfileStore.getInstance().getOrCreateProfile(PROFILE).webStorage
            } else {
                WebStorage.getInstance()
            }
    }
}
