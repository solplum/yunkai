package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wangzan101.mika.browser.BrowserFavorites
import com.wangzan101.mika.browser.SiteLooks
import com.wangzan101.mika.browser.StartPageModel
import com.wangzan101.mika.browser.UserBrowser
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import com.wangzan101.mika.core.uistate.PageDialog
import com.wangzan101.mika.ui.components.PageBackground

/**
 * 浏览器页(侧栏「浏览器」):没有打开的网页时是起始页([BrowserStart]:搜索框、常用网站、最近打开),
 * 打开了是网页([BrowserPageBar] + 网页 + [BrowserToolbar],「更多」是 [BrowserMoreSheet])。
 * 系统返回键先在网页里后退,退不了回到起始页,起始页上再按回到进来的地方。
 * 网页视图放在进程里([UserBrowser]),转屏时接着用;离开这一页(不是转屏、也没有别处的浏览器页)时关掉。
 */
@Composable
fun BrowserScreen(browser: UserBrowser, onBack: () -> Unit) {
    val context = LocalContext.current
    val page by browser.page.collectAsState()
    val dialog by browser.dialog.collectAsState()
    val notice by browser.notice.collectAsState()
    val registry by browser.sites.entries.collectAsState()
    val recentPages by browser.recent.entries.collectAsState()
    val edits by browser.favorites.edits.collectAsState()
    val looks by browser.looks.entries.collectAsState()
    val activity = remember(context) { context.findActivity() }
    var moreOpen by rememberSaveable { mutableStateOf(false) }

    val face = remember(looks) { { host: String -> SiteLooks.faceOf(host, looks, browser.looks::iconFile) } }
    val favorites = remember(edits, registry) { BrowserFavorites.combine(edits, registry) }
    val recent = remember(recentPages, looks) { StartPageModel.recentRows(recentPages) { face(it).name } }
    val toast: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }

    // 这一处浏览器页:进来时登记,回到前台时把网页视图要过来(界面有两个实例时挂在看得见的那个上),
    // 离开时交还;最后一处离开(不是转屏)才关网页
    val place = remember(browser, activity) { browser.newHost(activity ?: context.applicationContext) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(browser, place, lifecycle) {
        browser.openHost(place)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) browser.hostStarted(place) }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            browser.closeHost(place, keepPage = activity?.isChangingConfigurations == true)
        }
    }
    BackHandler(enabled = page != null) { browser.systemBack() }
    LaunchedEffect(notice) {
        notice?.let {
            toast(it)
            browser.noticeShown()
        }
    }

    PageBackground {
        val p = page
        if (p == null) {
            LaunchedEffect(Unit) { moreOpen = false }
            BrowserStart(
                sites = favorites, recent = recent, face = face,
                onBack = onBack,
                onSubmit = { input -> browser.submit(input)?.let(toast) },
                onOpenUrl = { url -> browser.open(url)?.let(toast) },
                onOpenSite = { site -> browser.openFavorite(site)?.let(toast) },
                onRemoveSite = { site ->
                    browser.favorites.remove(site.host)
                    toast(tr(R.string.browser_removed, face(site.host).name))
                },
                onAddHint = { toast(BROWSER_ADD_HINT) },
                onClearRecent = browser.recent::clear,
            )
        } else {
            val host = WebUrlPolicy.host(p.url)
            val loginBanner = p.loginFor != null && !BrowserFavorites.loggedIn(registry, p.loginFor)
            Column(Modifier.fillMaxSize()) {
                BrowserPageBar(
                    p, host?.let(face),
                    onClose = browser::closePage,
                    onSubmit = { input -> browser.submit(input)?.let(toast) },
                    onReload = browser::reload, onStop = browser::stop,
                )
                if (loginBanner) LoginBanner(desktop = p.desktop, onDone = browser::confirmLogin)
                // 网页视图由 UserBrowser 放进这个容器(换了标签也放进同一个)
                AndroidView(
                    factory = { ctx -> FrameLayout(ctx).also { browser.setContainer(place, it) } },
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("browser-page"),
                    onRelease = { browser.setContainer(place, null) },
                )
                BrowserToolbar(
                    p, onBackPage = browser::back, onForward = browser::forward, onHome = browser::closePage,
                    onShare = { share(context, p.url, p.title)?.let(toast) },
                    onMore = { moreOpen = true },
                )
            }
            if (moreOpen) {
                val favorite = host != null && BrowserFavorites.contains(favorites, host)
                BrowserMoreSheet(
                    face = host?.let(face), host = host, favorite = favorite, desktop = p.desktop,
                    onDismiss = { moreOpen = false },
                    onReload = browser::reload,
                    onCopy = {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(p.title.ifBlank { p.url }, p.url))
                        toast(tr(R.string.browser_link_copied))
                    },
                    onOpenOutside = { openOutside(context, p.url)?.let(toast) },
                    onToggleFavorite = {
                        if (host == null) return@BrowserMoreSheet
                        if (favorite) {
                            browser.favorites.remove(host)
                            toast(tr(R.string.browser_unfavorited))
                        } else {
                            browser.favorites.add(host)
                            toast(tr(R.string.browser_favorited))
                        }
                    },
                    onToggleDesktop = {
                        browser.setDesktop(!p.desktop)
                        toast(if (p.desktop) tr(R.string.browser_switched_mobile) else tr(R.string.browser_switched_desktop))
                    },
                )
            }
        }
    }

    dialog?.let { d -> PageDialogBox(d, browser::answerDialog) }
}

/** 系统分享面板:网址,标题作主题。打不开时返回原因。 */
private fun share(context: Context, url: String, title: String): String? {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
    if (title.isNotBlank()) send.putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TITLE, title)
    return try {
        context.startActivity(Intent.createChooser(send, null))
        null
    } catch (e: ActivityNotFoundException) {
        tr(R.string.browser_no_share_app)
    }
}

/** 交给系统默认的浏览器打开。打不开时返回原因。 */
private fun openOutside(context: Context, url: String): String? = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    null
} catch (e: ActivityNotFoundException) {
    tr(R.string.browser_no_other_browser)
}

/** 页面弹框:提示只有「好」;确认和离开页面有「取消」;输入框弹框带一个输入框。 */
@Composable
private fun PageDialogBox(d: PageDialog, answer: (Boolean, String?) -> Unit) {
    var input by remember(d) { mutableStateOf(d.defaultText.orEmpty()) }
    val leave = d.kind == PageDialog.BEFORE_UNLOAD
    AlertDialog(
        onDismissRequest = { answer(false, null) },
        title = { Text(if (leave) tr(R.string.browser_leave_q) else tr(R.string.browser_page_says)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (d.message.isNotBlank()) Text(d.message)
                if (leave && d.message.isBlank()) Text(tr(R.string.browser_leave_body))
                if (d.kind == PageDialog.PROMPT) TextField(input, { input = it }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton({ answer(true, if (d.kind == PageDialog.PROMPT) input else null) }) { Text(if (leave) tr(R.string.browser_leave) else tr(R.string.ok_yes)) }
        },
        dismissButton = if (d.kind == PageDialog.ALERT) null else ({ TextButton({ answer(false, null) }) { Text(tr(R.string.cancel)) } }),
    )
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
