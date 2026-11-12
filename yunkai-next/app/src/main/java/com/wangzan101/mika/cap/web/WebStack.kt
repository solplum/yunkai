package com.wangzan101.mika.cap.web

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import android.content.Context
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import android.os.SystemClock
import android.util.Log
import androidx.webkit.WebViewAssetLoader
import com.wangzan101.mika.takeover.HandoverActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import okhttp3.OkHttpClient
import java.io.File
import com.wangzan101.mika.core.workspace.SandboxTree

/**
 * 一个任务的内置浏览器:自己的一块后台网页显示、一池标签、网页端口与隔离脚本引擎。
 * 每个任务一套(并行时各自的当前标签都挂在自己的显示上照常渲染)。在主线程上建和关。
 */
class WebStack private constructor(
    private val context: Context,
    val taskId: String,
    val host: WebHost,
    val pool: WebTabPool,
    workspace: Workspace?,
) {
    /**
     * 本对话的工作区:[dir] 是宿主目录(work/<对话>,不存在就建,不要求沙箱已安装);
     * 下载写进去、上传从里面读,都经 [gate](打开一次、按句柄核实)。
     */
    class Workspace(val dir: File, val gate: WorkspaceFileGate, val tree: SandboxTree)

    // 下载在后台进行;任务结束一并取消,没下完的标 cancelled 并删掉半截文件
    private val transfers = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 下载与上传;没有工作区时为空(两者都报不可用)。 */
    val files: WebFiles? = workspace?.let { w ->
        val downloads = WebDownloads(w.dir, w.gate, w.tree, OkHttpClient(), cookies = { url -> WebTab.cookieManager().getCookie(url) }, scope = transfers)
        WebFiles(context, w.dir, downloads, w.gate, transfers)
    }

    val screen: WebScreen = WebScreen(pool, taskId, host, SystemClock::uptimeMillis, files)

    init {
        files?.let { f -> pool.downloadHandler = { tab, url, ua, cd, mime -> onDownload(f, tab, url, ua, cd, mime) } }
    }

    // 页面触发的下载:http(s) 直接取,blob: 让页面脚本读出来,data: 直接解;别的(如 intent:)不理。文件只保存,不打开。
    // 三种都先问页面链接上给的名字(<a download>):下载监听只带响应头里的名字,blob:/data: 连响应头都没有
    private fun onDownload(f: WebFiles, tab: WebTab, url: String, ua: String, cd: String?, mime: String?) {
        val d = f.downloads
        val pageName: suspend () -> String? = { screen.downloadName(tab, url) }
        when {
            url.startsWith("http://", true) || url.startsWith("https://", true) -> {
                val referer = tab.view.url
                // 自己因 TLS 连不上时改由页面用浏览器的网络栈去取:只给和当前页面同源的地址
                val viaPage: (suspend (Long) -> Pair<ByteArray, String?>?)? = if (!sameOrigin(url, referer)) null else { max ->
                    (screen.readBlob(tab, url, max) as? PageScript.Blob.Ok)?.let { it.bytes to it.mime }
                }
                d.withPageName(pageName) { name -> d.start(url, ua, cd, mime, referer, name, viaPage) }
            }
            url.startsWith("blob:", true) -> d.withPageName(pageName) { name ->
                d.startFrom(url, cd, mime, name) { max ->
                    when (val b = screen.readBlob(tab, url, max)) {
                        is PageScript.Blob.Ok -> b.bytes to b.mime
                        is PageScript.Blob.Failed -> throw WebDownloads.BlobRefused(b.error)
                    }
                }
            }
            url.startsWith("data:", true) -> d.withPageName(pageName) { name -> d.dataUrl(url, cd, name) }
            else -> Log.i(TAG, "download with an unsupported address ignored")
        }
    }

    /** 两个网址同源(协议、主机、端口都一样);有一个没有为 false。 */
    private fun sameOrigin(a: String, b: String?): Boolean {
        fun origin(u: String?) = u?.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}:${it.port}" }
        val x = origin(a) ?: return false
        return x == origin(b)
    }

    /** 正交给用户的标签(接管中);没有为 null。 */
    @Volatile private var handedTab: WebTab? = null

    /** 页面此刻在用户手上(接管中)。 */
    val handedOver: Boolean get() = handedTab != null

    /**
     * 接管:把当前标签锁住,拿到主屏的接管界面里。没有当前标签、或界面打不开时返回 false。
     * [actions] 把界面上的按钮交给任务(处理好了、我自己做完了、取消、先放着);[currentKey] 是任务此刻的等待编号。
     */
    fun showForTakeover(reason: String, message: String?, waitKey: Int, actions: (WebHandover.Action) -> Unit, currentKey: () -> Int?): Boolean {
        val tab = pool.current(taskId) ?: return false
        pool.lock(tab.id)
        val shown = WebHandover.show(context, tab, host, reason, message, waitKey, taskId, actions, currentKey)
        if (shown) handedTab = tab else pool.unlock(tab.id)
        // 人机验证或访问拦截真把任务拦住了(要交给用户):把任务所在的网站记为碰到过拦截
        if (shown && reason in SiteRegistry.CHALLENGE_KINDS) {
            tab.view.url?.takeIf { it.startsWith("http") }?.let(WebUrlPolicy::host)?.let { WebSites.registry(context).challenged(it, reason) }
        }
        return shown
    }

    /** 交回:结束接管界面、标签放回后台并解锁;[loggedIn] 时页面看得出已登录就把网站记为已登录。不在接管中为 null。 */
    fun returnFromTakeover(loggedIn: Boolean): HandoverActivity? {
        val tab = handedTab ?: return null
        handedTab = null
        val r = WebHandover.end(loggedIn, WebSites.registry(context), tab)
        pool.unlock(tab.id)
        return r
    }

    /** 任务结束:还在接管就先收回,再关掉这个任务的标签和显示。 */
    fun close() {
        runCatching { returnFromTakeover(loggedIn = false) }.onFailure { Log.w(TAG, "takeover not returned cleanly", it) }
        runCatching { pool.closeAll(taskId) }
        runCatching { pool.close() }
        runCatching { host.close() }
        runCatching { files?.close() }
        transfers.cancel()
    }

    companion object {
        private const val TAG = "MikaWebStack"

        /** 建一套;系统 WebView 不可用等原因建不起来时抛出,调用方就只给手机界面。主线程调用。 */
        fun create(
            context: Context, taskId: String, widthPx: Int, heightPx: Int, densityDpi: Int,
            assetLoaders: List<WebViewAssetLoader> = emptyList(),
            workspace: Workspace? = null,
        ): WebStack {
            val host = WebHost(context, widthPx, heightPx, densityDpi)
            return try {
                val pool = WebTabPool(context, host, assetLoaders = assetLoaders, registry = WebSites.registry(context))
                WebStack(context.applicationContext, taskId, host, pool, workspace)
            } catch (e: Exception) {
                host.close()
                throw e
            }
        }
    }
}
