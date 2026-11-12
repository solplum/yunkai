package com.wangzan101.mika.cap.web

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import com.wangzan101.mika.takeover.HandoverActivity
import com.wangzan101.mika.task.TaskService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 网页接管:把**同一个** WebView 从后台宿主挪到主屏的 [WebTakeoverPage] 里给用户操作,用完放回。
 * 页面状态(表单、滚动、登录流程里的一次性令牌)原样保留。进程里同一时间至多一次接管。
 *
 * 两种用法:
 * - 任务接管([show]):界面上的按钮经 [Session.onAction] 交给任务服务(处理好了 / 我自己做完了 / 取消任务 / 返回键先放着);
 *   交回时由接管状态机调用 [end],拿到接管期间的操作次数与页面标题,视图放回 [Session.home]。
 * - 设置页登录([showLogin]):新开一个看得见的标签,只有「完成」;完成时看一眼页面,没看出仍未登录就把该网站记为已登录。
 * 任务接管交回时要页面上看得出已登录([LoginProbe])才记,看不出不记(用户处理的可能只是验证码)。
 */
object WebHandover {
    enum class Action { HandBack, FinishedMyself, Cancel, PutAside, Done }

    /**
     * 一次接管。[home] 为 null 时没有后台宿主(设置页登录),结束时销毁标签。[reason] 为 null 是设置页登录模式。
     * [message] 是给用户看的一句说明(含付款信息时一并带上)。
     */
    class Session internal constructor(
        val tab: WebTab,
        val home: WebHost?,
        val reason: String?,
        val message: String?,
        val waitKey: Int,
        val onAction: (Action) -> Unit,
    ) {
        /** 用户在接管界面里对页面的触摸与按键次数。 */
        @Volatile var inputs: Int = 0
            internal set

        /** 接管界面在主线程上随时记下的页面地址与标题:结束时可能在别的线程上,读不了 WebView。 */
        @Volatile var url: String? = null
            internal set
        @Volatile var title: String? = null
            internal set
        val loginMode: Boolean get() = reason == null
    }

    private val main = Handler(Looper.getMainLooper())
    /** 设置页登录点「完成」后等页面回话的上限。 */
    private const val CONFIRM_WAIT_MS = 1_500L

    /** 设置页的网站登录正开着:任务的网页接管要等它关掉。 */
    val loginOpen: Boolean get() = _session.value?.loginMode == true
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    /**
     * 任务接管:打开接管界面,标签挪过去。已有一次接管在进行、标签不属于 [taskId] 或已不能用时返回 false。
     * [currentKey] 给出任务此刻的等待编号:按钮按下时它已经变了(这次等待已过去)就不动作。
     */
    fun show(
        context: Context, tab: WebTab, home: WebHost, reason: String, message: String?, waitKey: Int, taskId: String,
        onAction: (Action) -> Unit = taskActions(context.applicationContext, waitKey, null),
        currentKey: (() -> Int?)? = null,
    ): Boolean {
        if (_session.value != null || tab.crashed || tab.destroyed || home.closed || tab.ownerTaskId != taskId) return false
        val guarded: (Action) -> Unit = { a ->
            val now = currentKey?.invoke()
            // 「取消任务」不论哪一次等待都作数
            if (a != Action.Cancel && currentKey != null && now != waitKey) android.util.Log.i("MikaWebHandover", "ignored $a for a past wait ($waitKey, now $now)")
            else onAction(a)
        }
        _session.value = Session(tab, home, reason, message, waitKey, guarded)
        context.startActivity(WebTakeoverPage.intent(context))
        return true
    }

    /** 设置页「打开登录」:在主屏打开一个看得见的标签,用户自己登录,点「完成」结束。主线程调用。 */
    fun showLogin(context: Context, host: String, onDone: (String) -> Unit = { WebSites.registry(context).markLoggedIn(it) }): Boolean {
        if (_session.value != null) return false
        val url = "https://${host.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')}/"
        if (WebUrlPolicy.checkNavigable(url) != null) return false
        val tab = WebTab(context.applicationContext, "login", "settings")
        tab.view.loadUrl(url)
        _session.value = Session(tab, null, null, null, -1) { action ->
            if (action != Action.Done) {
                end()
                return@Session
            }
            // 按钮在主线程上按下:用户说完成了,页面上没看出仍未登录就记下;读完再关标签
            val fallback = (tab.view.url ?: _session.value?.url)?.let(WebUrlPolicy::host)
            val decided = java.util.concurrent.atomic.AtomicBoolean(false)
            fun finish(host: String?, state: LoginState) {
                if (!decided.compareAndSet(false, true)) return
                if (host != null && state != LoginState.SignedOut) onDone(host)
                end()
            }
            LoginProbe.probe(tab) { host, state -> finish(host, state) }
            main.postDelayed({ finish(fallback, LoginState.Unknown) }, CONFIRM_WAIT_MS)
        }
        context.startActivity(WebTakeoverPage.intent(context))
        return true
    }

    /**
     * 结束这次接管:界面关掉,视图放回后台宿主(设置页登录则销毁标签)。返回接管期间的操作次数和页面标题;
     * 没有接管在进行时为 null。[loggedIn](用户交回了)时视图放回后看一眼页面,看得出已登录才在 [registry] 里记上。任意线程可调。
     */
    fun end(loggedIn: Boolean = false, registry: SiteRegistry? = null, tab: WebTab? = null): HandoverActivity? {
        val s = _session.value ?: return null
        // [tab] 给了就只结束这个标签的接管(并行时别的任务的不动);同一次只结束一回
        if (tab != null && s.tab !== tab) return null
        if (!_session.compareAndSet(s, null)) return null
        main.post {
            restore(s)
            if (loggedIn && registry != null) LoginProbe.probe(s.tab) { host, state ->
                if (state == LoginState.SignedIn) registry.markLoggedIn(host)
                else android.util.Log.i("MikaWebHandover", "takeover on $host handed back; sign-in not confirmed ($state), not recorded")
            }
        }
        return HandoverActivity(s.inputs, s.title?.takeIf { it.isNotBlank() })
    }

    /** 视图放回后台宿主;没有宿主(设置页登录)就销毁。 */
    internal fun restore(s: Session) {
        if (s.tab.destroyed) return
        val home = s.home
        if (home == null) {
            s.tab.destroy()
            return
        }
        // 标签崩溃了或宿主已关(任务已结束):不往回挂,留给标签池去收拾
        if (s.tab.crashed || home.closed) {
            (s.tab.view.parent as? android.view.ViewGroup)?.removeView(s.tab.view)
            return
        }
        home.attach(s.tab)
    }

    /** 任务接管的按钮 → 任务服务的动作,带着这次等待的编号和任务编号(多个任务同时跑时只作用于这一个)。 */
    fun taskActions(context: Context, waitKey: Int, runKey: String?): (Action) -> Unit = { action ->
        when (action) {
            Action.HandBack, Action.Done -> TaskService.handBack(context, waitKey, runKey)
            Action.FinishedMyself -> TaskService.finishedMyself(context, waitKey, runKey)
            Action.Cancel -> TaskService.cancel(context, runKey)
            Action.PutAside -> TaskService.putAside(context, waitKey, runKey)
        }
    }
}
