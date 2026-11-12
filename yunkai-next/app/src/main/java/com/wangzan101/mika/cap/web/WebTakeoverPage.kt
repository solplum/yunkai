package com.wangzan101.mika.cap.web

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.AppLanguage
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.wangzan101.mika.web.WebTakeoverActivity

/**
 * 网页接管界面:主屏全屏,顶部一条「Mika 在等你:<原因>」和按钮,下面是**同一个** WebView(从后台宿主挪过来)。
 * 任务接管:「处理好了」「我自己做完了」「取消任务」;系统返回键 = 先放着(任务仍在等待接管,通知还在)。
 * 设置页登录:只有「完成」,返回键直接关掉。
 * 接管结束([WebHandover.end])时界面自己关掉,视图放回后台宿主。
 */
open class WebTakeoverPage : Activity() {
    private var session: WebHandover.Session? = null
    private lateinit var container: FrameLayout
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ticker = Handler(Looper.getMainLooper())
    private val pageRect = Rect()

    private val recordPage = object : Runnable {
        override fun run() {
            record()
            ticker.postDelayed(this, RECORD_INTERVAL_MS)
        }
    }

    // 安卓 12 及以前套上 App 里选的语言;13 起系统自己套
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = WebHandover.session.value
        if (s == null || s.tab.crashed) {
            finish()
            return
        }
        session = s
        container = FrameLayout(this)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            fitsSystemWindows = true
            addView(header(s), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        // 同一个 WebView:从后台宿主摘下,放进本界面
        val view = s.tab.view
        (view.parent as? ViewGroup)?.removeView(view)
        container.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.requestFocus()
        ticker.post(recordPage)
        // 接管在别处结束了(通知按钮、超时、取消):关掉本界面,视图由 WebHandover 放回
        scope.launch {
            WebHandover.session.collect { now -> if (now !== s) leave() }
        }
    }

    private fun header(s: WebHandover.Session): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(8))
            setBackgroundColor(Color.rgb(242, 242, 247))
        }
        bar.addView(TextView(this).apply {
            text = if (s.loginMode) tr(R.string.web_takeover_login) else tr(R.string.web_takeover_waiting, reasonLabel(s.reason))
            textSize = 15f
            setTextColor(Color.BLACK)
        })
        s.message?.takeIf { it.isNotBlank() }?.let { m ->
            bar.addView(TextView(this).apply {
                text = m
                textSize = 13f
                setTextColor(Color.DKGRAY)
            })
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        fun add(label: String, action: WebHandover.Action) = buttons.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { act(action) }
        })
        if (s.loginMode) {
            add(tr(R.string.done), WebHandover.Action.Done)
        } else {
            add(tr(R.string.action_hand_back), WebHandover.Action.HandBack)
            add(tr(R.string.action_finished_myself), WebHandover.Action.FinishedMyself)
            add(tr(R.string.action_cancel_task), WebHandover.Action.Cancel)
        }
        bar.addView(buttons)
        return bar
    }

    private fun act(action: WebHandover.Action) {
        val s = session ?: return
        record()
        s.onAction(action)
    }

    // 用户对页面的每一下触摸(按下)和按键都记一次;点顶部按钮不算
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val s = session
        if (s != null && ev.actionMasked == MotionEvent.ACTION_DOWN && ::container.isInitialized) {
            container.getGlobalVisibleRect(pageRect)
            if (pageRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) s.inputs++
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val s = session
        if (s != null && event.action == KeyEvent.ACTION_DOWN && event.keyCode != KeyEvent.KEYCODE_BACK && event.repeatCount == 0) s.inputs++
        return super.dispatchKeyEvent(event)
    }

    /** 系统返回键:任务接管 = 先放着;设置页登录 = 关掉。 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val s = session ?: return super.onBackPressed()
        record()
        if (s.loginMode) s.onAction(WebHandover.Action.Cancel) else s.onAction(WebHandover.Action.PutAside)
    }

    private fun record() {
        val s = session ?: return
        s.url = s.tab.view.url ?: s.url
        s.title = s.tab.view.title?.takeIf { it.isNotBlank() } ?: s.title
    }

    private fun leave() {
        if (isFinishing) return
        record()
        finish()
    }

    override fun onDestroy() {
        ticker.removeCallbacks(recordPage)
        scope.cancel()
        // 视图不能随本界面一起销毁:先摘下,由 WebHandover 放回后台宿主
        if (::container.isInitialized) container.removeAllViews()
        // 界面被划掉、而接管还没结束:任务接管按「先放着」处理,设置页登录直接结束
        session?.let { s ->
            if (WebHandover.session.value === s && isFinishing && !isChangingConfigurations) {
                if (s.loginMode) WebHandover.end() else s.onAction(WebHandover.Action.PutAside)
            }
        }
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val RECORD_INTERVAL_MS = 1_000L

        fun intent(context: Context): Intent =
            Intent(context, WebTakeoverActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        private fun reasonLabel(reason: String?) = when (reason) {
            "login" -> tr(R.string.takeover_reason_login)
            "captcha" -> tr(R.string.takeover_reason_captcha)
            "access_blocked" -> tr(R.string.web_reason_security)
            "payment" -> tr(R.string.takeover_reason_payment)
            "decision" -> tr(R.string.takeover_reason_decision)
            else -> tr(R.string.takeover_title)
        }
    }
}
