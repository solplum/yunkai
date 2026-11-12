package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.browser.AddressInput
import com.wangzan101.mika.browser.BrowserPage
import com.wangzan101.mika.browser.SiteFace
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val BROWSER_DESKTOP_TAG: String get() = tr(R.string.browser_desktop)
val BROWSER_LOGIN_DONE: String get() = tr(R.string.browser_login_done)

internal val MiniShape = RoundedCornerShape(8.dp)
internal val InitialSmall = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold)

/**
 * 网站的方块:有存下的网站图标就画在白底上;没有时是按主机名定的底色加名字的第一个字。
 * 图标文件在后台读,读过的留在进程里的小缓存里(按文件和保存时间)。
 */
@Composable
fun SiteTile(face: SiteFace, size: Dp, shape: Shape, initialStyle: TextStyle) {
    val c = MikaTheme.colors
    val icon = rememberSiteIcon(face)
    if (icon != null) {
        Box(Modifier.size(size).background(Color.White, shape).border(0.5.dp, c.edge, shape).clip(shape), contentAlignment = Alignment.Center) {
            Image(icon, null, Modifier.size(size * 0.62f))
        }
    } else {
        Box(Modifier.size(size).background(Color(face.color), shape), contentAlignment = Alignment.Center) {
            Text(face.initial, style = initialStyle, color = Color.White, maxLines = 1)
        }
    }
}

private object SiteIcons {
    val cache = LruCache<String, ImageBitmap>(64)
}

@Composable
private fun rememberSiteIcon(face: SiteFace): ImageBitmap? {
    val file = face.icon
    val key = file?.let { "${it.path}@${face.iconAtMs}" }
    val icon by produceState(key?.let { SiteIcons.cache.get(it) }, key) {
        if (file == null || key == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull() }
            ?.also { SiteIcons.cache.put(key, it) }
    }
    return icon
}

/**
 * 网页的顶栏:关闭(回起始页)、地址栏、刷新(加载时是停止),下面一条 2dp 的进度条。
 * 地址栏平时只显示锁和网站名(搜索结果页是搜的词),电脑版时带一个「电脑版」小标签;点了展开成完整网址可改,右边「取消」收回。
 */
@Composable
fun BrowserPageBar(
    page: BrowserPage,
    face: SiteFace?,
    onClose: () -> Unit,
    onSubmit: (String) -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
) {
    val c = MikaTheme.colors
    val focus = LocalFocusManager.current
    var editing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(c.bg).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(
            Modifier.fillMaxWidth().height(MikaSize.TopBar).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (editing) {
                AddressEditor(page.url, Modifier.weight(1f).padding(start = 8.dp)) { input ->
                    editing = false
                    focus.clearFocus()
                    onSubmit(input)
                }
                Text(
                    tr(R.string.cancel), Modifier.clip(MikaShapes.Pill).clickable(role = Role.Button) { editing = false; focus.clearFocus() }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    style = MikaType.Body, color = c.accent,
                )
            } else {
                BarIconButton(MikaIcons.Close, tr(R.string.browser_close_page), onClose)
                AddressPill(page, face, Modifier.weight(1f)) { editing = true }
                if (page.loading) BarIconButton(MikaIcons.Stop, tr(R.string.chat_stop), onStop)
                else BarIconButton(MikaIcons.Refresh, tr(R.string.refresh), onReload)
            }
        }
        val progress = page.takeIf { it.loading }?.progress
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (progress != null) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(5, 100) / 100f).background(c.accent).testTag("browser-progress"))
            }
        }
    }
}

@Composable
private fun AddressPill(page: BrowserPage, face: SiteFace?, modifier: Modifier, onClick: () -> Unit) {
    val c = MikaTheme.colors
    val query = AddressInput.searchQuery(page.url)
    val shown = query ?: face?.name ?: page.url
    Row(
        modifier.height(40.dp).clip(MikaShapes.Pill).background(c.fill)
            .clickable(onClickLabel = tr(R.string.browser_edit_url), role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp).testTag("browser-address-pill"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        when {
            query != null -> Icon(MikaIcons.Search, null, Modifier.size(14.dp), tint = c.ink3)
            page.url.startsWith("https://") -> Icon(MikaIcons.Lock, tr(R.string.browser_secure), Modifier.size(14.dp), tint = c.ink3)
        }
        Text(shown, Modifier.weight(1f, fill = false), style = MikaType.Body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (page.desktop) {
            Text(
                BROWSER_DESKTOP_TAG, Modifier.background(c.warnSoft, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                style = TagText, color = c.warn, maxLines = 1,
            )
        }
    }
}

private val TagText = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold)

/** 展开的地址栏:完整网址全选着,键盘上是「前往」。 */
@Composable
private fun AddressEditor(url: String, modifier: Modifier, onGo: (String) -> Unit) {
    val c = MikaTheme.colors
    val focus = remember { FocusRequester() }
    var text by remember { mutableStateOf(TextFieldValue(url, TextRange(0, url.length))) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        modifier.height(40.dp).background(c.surface, MikaShapes.Pill).border(1.5.dp, c.accent, MikaShapes.Pill).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(MikaIcons.Lock, null, Modifier.size(14.dp), tint = c.ink3)
        BasicTextField(
            text, { text = it },
            Modifier.weight(1f).focusRequester(focus).testTag("browser-address"),
            singleLine = true,
            textStyle = MikaType.Body.copy(fontSize = 14.5.sp, color = c.ink),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { onGo(text.text) }),
        )
    }
}

/** 网页下面的工具栏:后退、前进、起始页、分享、更多。 */
@Composable
fun BrowserToolbar(
    page: BrowserPage,
    onBackPage: () -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    onShare: () -> Unit,
    onMore: () -> Unit,
) {
    val c = MikaTheme.colors
    Column(Modifier.fillMaxWidth().background(c.bg).windowInsetsPadding(WindowInsets.navigationBars)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.hair))
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarIconButton(MikaIcons.Back, tr(R.string.back), onBackPage, enabled = page.canBack)
            BarIconButton(MikaIcons.Forward, tr(R.string.browser_forward), onForward, enabled = page.canForward)
            BarIconButton(MikaIcons.Home, tr(R.string.browser_home), onHome)
            BarIconButton(MikaIcons.Share, tr(R.string.share), onShare)
            BarIconButton(MikaIcons.More, tr(R.string.more), onMore)
        }
    }
}

/** 「给 Mika 登录」的横幅:说明这次用的是 Mika 办事时的那一版,登录完点「登录好了」。 */
@Composable
fun LoginBanner(desktop: Boolean, onDone: () -> Unit) {
    val c = MikaTheme.colors
    val text = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(tr(R.string.browser_login_for_mika)) }
        append(tr(R.string.browser_login_for_mika_sub, if (desktop) tr(R.string.browser_desktop) else tr(R.string.browser_mobile)))
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)
            .background(c.accentSoft, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("browser-login-banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text, Modifier.weight(1f), style = MikaType.Caption, color = c.ink)
        MikaButton(BROWSER_LOGIN_DONE, onDone, size = ButtonSize.Small)
    }
}

/**
 * 「更多」面板:顶上是这个网站(图标、名字、主机名),下面刷新、复制链接、在其他浏览器里打开、加入常用网站(已在常用时是从常用移除)、
 * 换成电脑版 / 换回手机版(这个网站下次也这样)。点任何一项先关面板再做。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserMoreSheet(
    face: SiteFace?,
    host: String?,
    favorite: Boolean,
    desktop: Boolean,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    onCopy: () -> Unit,
    onOpenOutside: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleDesktop: () -> Unit,
) {
    val c = MikaTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface, shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp).testTag("browser-more")) {
            if (face != null && host != null) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SiteTile(face, 28.dp, MiniShape, InitialSmall)
                    Column(Modifier.weight(1f)) {
                        Text(face.name, style = MikaType.BodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(host, style = MikaType.Caption, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            fun act(f: () -> Unit): () -> Unit = { onDismiss(); f() }
            ListRow(MikaIcons.Refresh, tr(R.string.refresh), first = true, onClick = act(onReload))
            ListRow(MikaIcons.Copy, tr(R.string.browser_copy_link), onClick = act(onCopy))
            ListRow(MikaIcons.External, tr(R.string.browser_open_outside), onClick = act(onOpenOutside))
            if (host != null) {
                ListRow(MikaIcons.Star, if (favorite) tr(R.string.browser_unfavorite) else tr(R.string.browser_favorite), onClick = act(onToggleFavorite))
                ListRow(
                    if (desktop) MikaIcons.Phone else MikaIcons.Desktop,
                    if (desktop) tr(R.string.browser_to_mobile) else tr(R.string.browser_to_desktop),
                    subtitle = if (desktop) tr(R.string.browser_keep_mobile) else tr(R.string.browser_keep_desktop),
                    onClick = act(onToggleDesktop),
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
