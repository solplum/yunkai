package com.wangzan101.mika.cap.web.cdp

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebViewCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.wangzan101.mika.core.cap.web.cdp.CdpDiscovery
import com.wangzan101.mika.core.cap.web.cdp.CdpSession
import com.wangzan101.mika.core.cap.web.cdp.CdpTransport
import com.wangzan101.mika.core.cap.web.cdp.evaluate
import com.wangzan101.mika.core.shell.DevtoolsSocket
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import android.os.ParcelFileDescriptor
import com.wangzan101.mika.cap.web.WebHost
import com.wangzan101.mika.cap.web.WebTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.wangzan101.mika.cap.web.WebOwner

/**
 * 每个网页标签的 DevTools 会话入口。连不上(系统不支持、被拦、标签已关)时为 null,
 * 调用方退回注入脚本的做法。所有标签默认桌面版,这是新的基线,不依赖会话:没有会话时 WebView 设置里的
 * 桌面 UA、UA-CH(支持时)和宽视口照样生效,只是少了仿真那一层(视口宽度、navigator.platform)。
 *
 * 连法按顺序试、记住结果:直连本进程的 `webview_devtools_remote_<pid>`;被拒就经 [relay] 代连(内置助手,shell 身份;由装配层给);都不行就没有。
 * 进程里通常一份(App 给出,见 [of]);测试可以自己造一份,换会话来源、连法都只影响自己。
 * 每个标签第一次用时写下标记、逐个目标找到它并记住 targetId,之后一直用这一条会话;断了重连一次,再不行这个标签降级(过一阵再试)。
 *
 * 桌面版网页也在这里:标签一建好就把 WebView 设置换成桌面 UA(连不上 DevTools 时的退路)并在文档开始注入身份补丁,
 * 会话连上后再用仿真把 UA-CH、视口、触摸与指针一并换成桌面(见 [DesktopProfile]);按站点记住的手机版站点都换回默认。
 */
class WebDevtools(private val relay: ((String) -> ParcelFileDescriptor?)?) {
    enum class Route { Unknown, Direct, Relay, None }

    /** 测试用:只走直连 / 只走代连 / 当作连不上;null 为自动。改了之后调 [resetRoute]。 */
    @Volatile var forceRoute: Route? = null

    private val _route = MutableStateFlow(Route.Unknown)
    /** 最近一次检测到的连法;Unknown 是还没测过。 */
    val route: StateFlow<Route> = _route.asStateFlow()

    private val _available = MutableStateFlow(false)
    /** 现在能用 DevTools(诊断与界面用)。 */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    @Volatile var provider: (WebTab) -> CdpSession? = this::cached

    /** 已经连好的会话;还没连就在后台开始连并先返回 null。不阻塞,任意线程可调。 */
    fun session(tab: WebTab): CdpSession? =
        // 换了来源的会话(测试自备的等)也要按这个标签的视口仿真换算 Input 坐标
        provider(tab)?.also { s -> links[tab]?.let { s.inputScale = it.inputScale } }

    /** 等会话连好(至多 [timeoutMs]);连不上为 null。不要在持有主线程等待的地方调。 */
    suspend fun awaitSession(tab: WebTab, timeoutMs: Long = ATTACH_TIMEOUT_MS): CdpSession? {
        val link = links[tab] ?: return null
        return bounded(timeoutMs) { attach(link) }
    }

    /** 这个标签现在用的是手机版网页。 */
    fun isMobile(tab: WebTab): Boolean = links[tab]?.mobile ?: false

    /** 这个标签的 DevTools 目标 id;没连上过为 null。 */
    fun targetId(tab: WebTab): String? = links[tab]?.targetId

    // ---------- 标签生命周期(WebTab 调) ----------

    /** 标签刚建好(主线程):打开调试接口,换成桌面版的 WebView 设置,关掉 X-Requested-With 头。 */
    fun prepare(tab: WebTab, context: Context) {
        val st = tab.view.settings
        init(context, st)
        val link = Link(tab)
        links[tab] = link
        // 视图换了大小(换了宿主显示、进出接管界面):桌面版的视口缩放跟着重算
        tab.view.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) onViewResized(link)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            runCatching { WebSettingsCompat.setRequestedWithHeaderOriginAllowList(st, emptySet()) }
        }
        applySettings(st, mobile = false)
        applyIdentityScript(link)
    }

    /** 主文档加载前(主线程,可挂起):按目标网站选好手机版或桌面版,并尽量在第一次导航前连好会话、设好仿真。 */
    suspend fun beforeLoad(tab: WebTab, url: String) {
        val link = links[tab] ?: return
        val mobile = wantsMobile(link, url) ?: false
        val changed = link.mobile != mobile
        if (changed) switchMode(link, mobile)
        // 连接与仿真在后台做,这里至多等一小会儿:到点就照常加载(这一页先按 WebView 设置的退路走,连上后再换)
        bounded(BEFORE_LOAD_WAIT_MS) {
            val s = attach(link)
            if (s != null && link.configured != link.mobile) link.lock.withLock { configure(link, s) }
            s
        }
    }

    // 在独立的协程里做,调用方等到上限就走,不会被里面的阻塞读写拖住
    private suspend fun <T> bounded(timeoutMs: Long, block: suspend () -> T?): T? {
        val job = scope.async { block() }
        return withTimeoutOrNull(timeoutMs) { job.await() }
    }

    /**
     * 主文档开始加载(主线程):点链接、跳转到了另一种版本的网站时换过来,并重新加载一次。
     * 不是网页(about:blank 等)的不动。
     */
    fun onPageStarted(tab: WebTab, url: String?) {
        val link = links[tab] ?: return
        if (url == null || !url.startsWith("http")) return
        link.loadedBeforeAttach = true
        val mobile = wantsMobile(link, url) ?: return
        if (link.mobile == mobile) return
        switchMode(link, mobile)
        scope.launch {
            link.session?.takeIf { !it.closed.value }?.let { runCatching { configure(link, it) } }
            withContext(Dispatchers.Main) { if (!tab.destroyed) tab.view.reload() }
        }
    }

    /**
     * 这个标签自己定每个网站用哪一版([policy] 收主机名,返回 true 为手机版),不再看按站点记住的那份(Agent 的);
     * 设为 null 换回那份。只影响这一个标签,下一次主文档加载时生效(当前页由调用方重新加载)。主线程调用。
     */
    fun setModePolicy(tab: WebTab, policy: ((String?) -> Boolean)?) {
        links[tab]?.policy = policy
    }

    /**
     * 这个标签由用户亲手看、亲手点(浏览器页里的标签):桌面版也不加视口仿真,页面能双指缩放(见 [DesktopProfile.viewportCall])。
     * 下一次配置会话时生效。主线程调用。
     */
    fun setHandOperated(tab: WebTab) {
        links[tab]?.handOperated = true
    }

    /** Agent 的标签打开 [host] 时用不用手机版(按站点记住的那份;还没有任何标签建好时按默认的桌面版)。 */
    fun agentWantsMobile(host: String?): Boolean = modes?.isMobile(host) ?: false

    // 标签自己的规则优先;没有就看按站点记住的那份,还没读进来为 null
    private fun wantsMobile(link: Link, url: String): Boolean? {
        val host = WebUrlPolicy.host(url)
        link.policy?.let { return it(host) }
        return modes?.isMobile(host)
    }

    /** 这个视图该挂在桌面视口大小的宿主显示上(所在标签用桌面版)。不认识的视图为 false。 */
    fun wantsDesktopHost(view: WebView): Boolean = links.values.firstOrNull { it.tab.view === view }?.let { !it.mobile } ?: false

    // 主线程:两层设置换过来,挂在宿主上的视图换到对应大小的显示
    private fun switchMode(link: Link, mobile: Boolean) {
        link.mobile = mobile
        applySettings(link.tab.view.settings, mobile)
        applyIdentityScript(link)
        WebHost.followMode(link.tab)
    }

    /**
     * 主线程:桌面版在每个文档(含框架)开始时注入身份补丁([DesktopProfile.identityScript]),手机版撤掉。
     * 系统不支持文档开始注入时由会话上的 Page.addScriptToEvaluateOnNewDocument 代替(见 [configure])。
     */
    private fun applyIdentityScript(link: Link) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        if (link.mobile) {
            link.identityScript?.let { runCatching { it.remove() } }
            link.identityScript = null
        } else if (link.identityScript == null) {
            link.identityScript = runCatching { WebViewCompat.addDocumentStartJavaScript(link.tab.view, DesktopProfile.identityScript(), setOf("*")) }
                .onFailure { Log.w(TAG, "identity script on tab ${link.tab.id}: ${it.message}") }.getOrNull()
        }
    }

    private fun onViewResized(link: Link) {
        if (link.mobile) return
        val s = link.session?.takeIf { !it.closed.value } ?: return
        scope.launch {
            link.lock.withLock {
                if (link.session === s && !link.mobile) runCatching { configure(link, s) }.onFailure { Log.w(TAG, "re-fit tab ${link.tab.id}: ${it.message}") }
            }
        }
    }

    /** 标签关了:断开会话、忘掉它。 */
    fun release(tab: WebTab) {
        val link = links.remove(tab) ?: return
        link.session?.let { s -> scope.launch { s.close() } }
        link.session = null
    }

    /** 记下站点用手机版还是桌面版;当前页要由调用方重新加载(加载前 [beforeLoad] 会换过来)。 */
    fun setSiteMode(host: String, mobile: Boolean) {
        modes?.set(host, mobile)
    }

    /** 测试用:换回真实的会话来源(别的测试换过 [provider] 没换回时)。 */
    fun resetProvider() {
        provider = this::cached
    }

    /** 测试用:忘掉检测到的连法,下次重新检测。 */
    fun resetRoute() {
        path = null
        directFailed = false
        _route.value = Route.Unknown
        _available.value = false
    }

    // ---------- 内部 ----------

    private class Link(val tab: WebTab) {
        val lock = Mutex()
        @Volatile var session: CdpSession? = null
        @Volatile var targetId: String? = null
        @Volatile var mobile = false
        /** 这个标签自己的版本规则(见 [setModePolicy]);null 时按站点记住的那份。 */
        @Volatile var policy: ((String?) -> Boolean)? = null
        /** 用户亲手操作的标签(见 [setHandOperated]):不加视口仿真。 */
        @Volatile var handOperated = false
        /** 会话上已设好的版本;null 是还没设。 */
        @Volatile var configured: Boolean? = null
        @Volatile var degradedAt = 0L
        @Volatile var loadedBeforeAttach = false
        @Volatile var everAttached = false
        /** 当前仿真下 Input 域坐标与 CSS 像素之比(见 [CdpSession.inputScale])。 */
        @Volatile var inputScale = 1.0
        /** 不加视口仿真时页面的 devicePixelRatio(WebView 每 CSS 像素占几个视图像素),及量它时视图所在的显示。 */
        @Volatile var baseDpr: Double? = null
        @Volatile var baseDprDisplay = -1
        /** 这条会话上已经设了视口仿真;设后页面读到的 devicePixelRatio。 */
        @Volatile var metricsSet = false
        @Volatile var emulatedDpr: Double? = null
        /** 文档开始注入的桌面身份补丁(主线程动);手机版时为 null。 */
        var identityScript: ScriptHandler? = null
        /** 系统不支持文档开始注入时,在这条会话上登记的同一份补丁的编号。 */
        @Volatile var sessionScriptId: String? = null
    }

    /** 一种连法。 */
    private class Path(val route: Route, val transport: CdpTransport)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val links = ConcurrentHashMap<WebTab, Link>()
    private val pathLock = Mutex()
    @Volatile private var path: Path? = null
    @Volatile private var directFailed = false
    @Volatile private var relayFailedAt = 0L
    @Volatile private var modes: SiteModes? = null
    @Volatile private var desktop: JsonObject? = null
    @Volatile private var desktopUa: String? = null
    @Volatile private var desktopMetadata: UserAgentMetadata? = null
    @Volatile private var defaultMetadata: UserAgentMetadata? = null
    @Volatile private var debuggingOn = false

    private val socketName: String get() = DevtoolsSocket.name(Process.myPid())

    @Synchronized
    private fun init(context: Context, st: WebSettings) {
        if (!debuggingOn) {
            WebView.setWebContentsDebuggingEnabled(true)
            debuggingOn = true
        }
        if (modes == null) modes = SiteModes(File(context.applicationContext.filesDir, "web"))
        if (desktopUa != null) return
        val defaultUa = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("")
        val (major, full) = DesktopProfile.version(defaultUa) ?: ("120" to "120.0.0.0")
        val metadataOk = WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)
        // 第一次改设置之前取内核自己的 UA-CH,手机版站点换回它
        val probe = if (metadataOk) runCatching { WebSettingsCompat.getUserAgentMetadata(st) }.getOrNull() else null
        val engine = probe?.brandVersionList?.map { Brand(it.brand, it.majorVersion, it.fullVersion) }
        val brands = DesktopProfile.brands(engine, major, full)
        defaultMetadata = probe
        desktopMetadata = if (metadataOk) {
            UserAgentMetadata.Builder()
                .setBrandVersionList(brands.map { UserAgentMetadata.BrandVersion.Builder().setBrand(it.brand).setMajorVersion(it.major).setFullVersion(it.full).build() })
                .setFullVersion(full).setPlatform(DesktopProfile.PLATFORM).setPlatformVersion("").setArchitecture("x86")
                .setModel("").setMobile(false).setBitness(64).setWow64(false).build()
        } else null
        desktop = DesktopProfile.userAgentOverride(major, full, brands)
        desktopUa = DesktopProfile.userAgent(major)
    }

    // 退路这一层:UA、UA-CH(支持时)、宽视口。仿真连上后以仿真为准
    private fun applySettings(st: WebSettings, mobile: Boolean) {
        st.userAgentString = if (mobile) null else desktopUa
        st.useWideViewPort = !mobile
        st.loadWithOverviewMode = !mobile
        val meta = if (mobile) defaultMetadata else desktopMetadata
        if (meta != null && WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            runCatching { WebSettingsCompat.setUserAgentMetadata(st, meta) }
        }
    }

    private fun cached(tab: WebTab): CdpSession? {
        val link = links[tab] ?: return null
        link.session?.takeIf { !it.closed.value }?.let { return it }
        if (!tab.destroyed && !link.lock.isLocked && retryAllowed(link)) scope.launch { attach(link) }
        return null
    }

    private fun retryAllowed(link: Link) =
        link.degradedAt == 0L || SystemClock.elapsedRealtime() - link.degradedAt >= DEGRADED_RETRY_MS

    private suspend fun attach(link: Link): CdpSession? = link.lock.withLock {
        link.session?.takeIf { !it.closed.value }?.let { return it }
        val tab = link.tab
        if (tab.destroyed || tab.crashed || links[tab] !== link || !retryAllowed(link)) return null
        val p = path() ?: return null
        val s = try {
            link.targetId?.let { reopen(p, it) } ?: find(p, link)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "attach tab ${tab.id}: ${e.message}")
            null
        }
        if (s == null) {
            link.degradedAt = SystemClock.elapsedRealtime()
            // 代连这条路可能已经断了(助手没了):下次重新检测
            if (p.route == Route.Relay) invalidate(p)
            return null
        }
        link.session = s
        link.degradedAt = 0L
        link.configured = null
        // 新会话上没有仿真(断开时 WebView 已撤销)
        link.metricsSet = false
        link.sessionScriptId = null
        runCatching { configure(link, s) }.onFailure { Log.w(TAG, "emulation on tab ${tab.id}: ${it.message}") }
        if (!link.everAttached && link.loadedBeforeAttach && !link.mobile) {
            // 页面在仿真设好之前就加载了(弹出的标签等):按桌面版重新加载一次
            withContext(Dispatchers.Main) { if (!tab.destroyed) tab.view.reload() }
        }
        link.everAttached = true
        watch(link, s)
        s
    }

    private suspend fun configure(link: Link, s: CdpSession) {
        val mobile = link.mobile
        if (mobile) {
            // 空 UA 即撤销覆盖,回到 WebView 设置里的(已换回默认)
            s.send("Emulation.setUserAgentOverride", buildJsonObject { put("userAgent", "") }, timeoutMs = CALL_TIMEOUT_MS)
            s.send("Emulation.clearDeviceMetricsOverride", timeoutMs = CALL_TIMEOUT_MS)
            link.metricsSet = false
            s.inputScale = 1.0
            link.inputScale = 1.0
            DesktopProfile.clearMediaCall().let { (m, p) -> runCatching { s.send(m, p, timeoutMs = CALL_TIMEOUT_MS) } }
            link.sessionScriptId?.let { id ->
                runCatching { s.send("Page.removeScriptToEvaluateOnNewDocument", buildJsonObject { put("identifier", id) }, timeoutMs = CALL_TIMEOUT_MS) }
                link.sessionScriptId = null
            }
        } else {
            // 还没排版时按宿主默认的桌面视口尺寸
            val (size, display) = withContext(Dispatchers.Main) {
                val v = link.tab.view
                (if (v.width > 0 && v.height > 0) v.width to v.height else DesktopProfile.HOST_WIDTH to DesktopProfile.HOST_HEIGHT) to (v.display?.displayId ?: -1)
            }
            val (w, h) = size
            desktop?.let { s.send("Emulation.setUserAgentOverride", it, timeoutMs = CALL_TIMEOUT_MS) }
            // 触摸、指针与悬停:每条单独发,内核不认的那条不影响别的
            for ((m, p) in DesktopProfile.emulationCalls()) {
                runCatching { s.send(m, p, timeoutMs = CALL_TIMEOUT_MS) }.onFailure { Log.i(TAG, "$m not applied on tab ${link.tab.id}: ${it.message}") }
            }
            if (link.sessionScriptId == null && !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                link.sessionScriptId = runCatching {
                    s.send("Page.addScriptToEvaluateOnNewDocument", buildJsonObject { put("source", DesktopProfile.identityScript()) }, timeoutMs = CALL_TIMEOUT_MS)["identifier"]
                        ?.jsonPrimitive?.contentOrNull
                }.getOrNull()
            }
            val hand = link.handOperated
            val fit = if (hand) null else DesktopProfile.fitScale(w, baseDpr(link, s, display).toFloat())
            DesktopProfile.viewportCall(hand, w, h, fit).let { (m, p) -> s.send(m, p, timeoutMs = CALL_TIMEOUT_MS) }
            link.metricsSet = !hand
            link.emulatedDpr = if (hand) null else readDpr(s)
            // 缩放后 Input 域的坐标按缩放前的视图(DIP)解释:调用方照旧给 CSS 像素,发送时乘上
            s.inputScale = fit ?: 1.0
            link.inputScale = s.inputScale
            Log.i(TAG, if (hand) "tab ${link.tab.id} desktop identity without viewport emulation (hand-operated)" else "tab ${link.tab.id} desktop viewport fitted to ${w}x$h view px (scale $fit)")
        }
        link.configured = mobile
    }

    /**
     * 不加仿真时 WebView 每 CSS 像素占几个视图像素。它取决于 WebView 认的显示密度,视图换了显示可能就变,
     * 所以按显示记;要重量时先撤掉仿真,等页面读到的值离开仿真时的值再取。
     */
    private suspend fun baseDpr(link: Link, s: CdpSession, display: Int): Double {
        link.baseDpr?.takeIf { link.baseDprDisplay == display }?.let { return it }
        var v = if (link.metricsSet) {
            s.send("Emulation.clearDeviceMetricsOverride", timeoutMs = CALL_TIMEOUT_MS)
            link.metricsSet = false
            var cur: Double? = null
            val until = SystemClock.elapsedRealtime() + DPR_SETTLE_MS
            while (SystemClock.elapsedRealtime() < until) {
                cur = readDpr(s)
                if (cur != null && cur != link.emulatedDpr) break
                delay(DPR_POLL_MS)
            }
            cur
        } else readDpr(s)
        if (v == null || v <= 0) v = link.baseDpr ?: withContext(Dispatchers.Main) { link.tab.view.resources.displayMetrics.density.toDouble() }
        link.baseDpr = v
        link.baseDprDisplay = display
        return v
    }

    private suspend fun readDpr(s: CdpSession): Double? = runCatching {
        s.evaluate("devicePixelRatio", timeoutMs = CALL_TIMEOUT_MS)["value"]?.jsonPrimitive?.contentOrNull?.toDouble()
    }.getOrNull()

    private fun watch(link: Link, s: CdpSession) {
        scope.launch {
            s.closed.first { it }
            val tab = link.tab
            if (links[tab] !== link || tab.destroyed || tab.crashed || link.session !== s) return@launch
            link.session = null
            // 每次断线各重连一次;连不上这个标签降级,过一阵再试
            delay(RECONNECT_BACKOFF_MS)
            if (attach(link) == null) Log.w(TAG, "tab ${tab.id} lost its devtools session and could not reconnect: degraded")
        }
    }

    /** 已知 targetId:直接连回去(标签与目标的对应跨导航不变)。目标没了为 null。 */
    private suspend fun reopen(p: Path, targetId: String): CdpSession? {
        val t = CdpDiscovery.list(p.transport, socketName, timeoutMs = DISCOVERY_TIMEOUT_MS).firstOrNull { it.id == targetId } ?: return null
        val ws = t.webSocketDebuggerUrl ?: return null
        return connect(p, ws)
    }

    private suspend fun find(p: Path, link: Link): CdpSession? {
        val tab = link.tab
        val marker = UUID.randomUUID().toString()
        if (!mark(tab, marker)) return null
        val claimed = links.values.mapNotNull { l -> l.targetId.takeIf { l !== link } }.toSet()
        val tabUrl = withContext(Dispatchers.Main) { tab.view.url }
        val candidates = TargetMatch.candidates(CdpDiscovery.list(p.transport, socketName, timeoutMs = DISCOVERY_TIMEOUT_MS), claimed, tabUrl)
        for (t in candidates) {
            val s = connect(p, t.webSocketDebuggerUrl!!) ?: continue
            val v = runCatching {
                s.evaluate(TargetMatch.READ_SCRIPT, timeoutMs = MARK_TIMEOUT_MS)["value"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            if (TargetMatch.isMarker(v, marker)) {
                runCatching { s.evaluate(TargetMatch.CLEAR_SCRIPT, timeoutMs = MARK_TIMEOUT_MS) }
                link.targetId = t.id
                return s
            }
            s.close()
        }
        Log.w(TAG, "no devtools target carries tab ${tab.id}'s marker (${candidates.size} candidates)")
        return null
    }

    // 握手有上限(到点关流),卡住的握手不会一直占着标签的锁
    private suspend fun connect(p: Path, webSocketUrl: String): CdpSession? =
        try {
            CdpSession.connect(p.transport, socketName, CdpDiscovery.webSocketPath(webSocketUrl), handshakeTimeoutMs = CONNECT_TIMEOUT_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "devtools connect via ${p.route}: ${e.message}")
            null
        }

    /** 在标签当前文档里写下标记(主线程执行),等它执行完。 */
    private suspend fun mark(tab: WebTab, marker: String): Boolean {
        val done = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            if (tab.destroyed) done.complete(false)
            else tab.view.evaluateJavascript(TargetMatch.markScript(marker)) { done.complete(true) }
        }
        return withTimeoutOrNull(MARK_TIMEOUT_MS) { done.await() } ?: false
    }

    private suspend fun path(): Path? = pathLock.withLock {
        val forced = forceRoute
        if (forced == Route.None) return publish(null, Route.None)
        path?.let { if (forced == null || it.route == forced) return it }
        if (forced == Route.Direct || (forced == null && !directFailed)) {
            val direct = DirectTransport()
            if (probe(direct)) return publish(Path(Route.Direct, direct), Route.Direct)
            if (forced == null) {
                directFailed = true
                Log.i(TAG, "direct devtools connection refused; trying the phone helper")
            }
        }
        if (forced == Route.Relay || forced == null) {
            if (forced == null && SystemClock.elapsedRealtime() - relayFailedAt < RELAY_RETRY_MS && relayFailedAt != 0L) return publish(null, Route.None)
            relay?.let { open ->
                val t = RelayTransport(open)
                if (probe(t)) return publish(Path(Route.Relay, t), Route.Relay)
                relayFailedAt = SystemClock.elapsedRealtime()
            }
        }
        publish(null, Route.None)
    }

    private fun publish(p: Path?, r: Route): Path? {
        path = p
        _route.value = r
        _available.value = p != null
        return p
    }

    private suspend fun invalidate(p: Path) = pathLock.withLock {
        if (path === p) publish(null, Route.Unknown)
    }

    private suspend fun probe(t: CdpTransport): Boolean =
        runCatching { CdpDiscovery.version(t, socketName, timeoutMs = PROBE_TIMEOUT_MS) }.isSuccess

    companion object {
        private const val TAG = "MikaDevtools"
        private const val ATTACH_TIMEOUT_MS = 3_000L
        /** 第一次导航前等会话的上限:连不上也不能把打开网页拖太久。 */
        private const val BEFORE_LOAD_WAIT_MS = 2_500L
        private const val PROBE_TIMEOUT_MS = 2_000L
        private const val DISCOVERY_TIMEOUT_MS = 3_000L
        private const val CONNECT_TIMEOUT_MS = 2_000L
        private const val MARK_TIMEOUT_MS = 2_000L
        private const val CALL_TIMEOUT_MS = 3_000L
        private const val RECONNECT_BACKOFF_MS = 500L
        private const val DPR_SETTLE_MS = 500L
        private const val DPR_POLL_MS = 50L
        /** 降级的标签过这么久再试一次(助手可能重新起来了)。 */
        private const val DEGRADED_RETRY_MS = 60_000L
        /** 代连检测失败后过这么久才再测(助手可能还没起来)。 */
        private const val RELAY_RETRY_MS = 15_000L

        /** 进程里那一份(App 给出)。 */
        fun of(context: Context): WebDevtools = WebOwner.of(context).webDevtools
    }
}
