// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.capsule

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.confirm.ConfirmationCopy
import com.wangzan101.mika.core.userscreen.CapturedScreen
import com.wangzan101.mika.ui.MarkdownText
import com.wangzan101.mika.ui.ScreenCopy
import com.wangzan101.mika.ui.ScreenThumb
import com.wangzan101.mika.ui.UserMessage
import androidx.compose.ui.graphics.ImageBitmap
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaLogo
import com.wangzan101.mika.ui.components.StepItem
import com.wangzan101.mika.ui.components.StepState
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaMotion
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.flow.first

private val CardShape = RoundedCornerShape(22.dp)
private val ScreenShape = RoundedCornerShape(14.dp)
private val BubbleShape = RoundedCornerShape(16.dp, 16.dp, 5.dp, 16.dp)
private val CardWidth = 322.dp
private val ScreenWidth = 104.dp
private val ZoomWidth = 300.dp
private val Title = TextStyle(fontSize = 14.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
private val Sub = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
private val Body = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp)
private val Strong = Body.copy(fontWeight = FontWeight.SemiBold)
private val Step = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)

/** 卡片上的固定说法(按钮、链接、读屏描述)。 */
object CardCopy {
    val STOP: String get() = tr(R.string.chat_stop)
    val TAKE_OVER: String get() = tr(R.string.action_take_over)
    val RESUME: String get() = tr(R.string.chat_continue)
    val APPROVE_ONCE_ONLY: String get() = tr(R.string.card_approve_once)
    val DECLINE: String get() = tr(R.string.action_decline)
    val SEE_PROCESS: String get() = tr(R.string.card_see_process)
    val BACK_TO_MIKA: String get() = tr(R.string.card_back_to_mika)
    val SEND: String get() = tr(R.string.chat_send)
    val NEW_CHAT: String get() = tr(R.string.chat_new)
    val CONTINUE_LAST: String get() = tr(R.string.card_continue_last)
    val COLLAPSE: String get() = tr(R.string.collapse)
    val SCREEN: String get() = tr(R.string.card_screen)
    val SCREEN_LABEL: String get() = tr(R.string.card_screen_label)
    val ZOOMED: String get() = tr(R.string.card_zoomed)
    val ZOOM_HINT: String get() = tr(R.string.card_zoom_hint)
}

/** 卡片上的按钮;都转成任务服务现有的动作(见 [CapsuleController])。[runKey] 是按下时那件事的编号。 */
interface CardActions {
    fun collapse()
    fun stop(runKey: String)
    fun takeOver(runKey: String)
    fun resume(runKey: String)
    fun answer(runKey: String, text: String)
    fun approve(runKey: String, requestKey: Int)
    fun decline(runKey: String, requestKey: Int)
    fun openMika(conversationId: String)
    fun zoom(on: Boolean)
    /** 点了底部的输入框:这时浮层才改成能接收键盘。 */
    fun openInput()
    fun draft(text: String)
    /** 发出输入框里的话(交给谁见 [CapsuleCards.input])。 */
    fun send()
    /** 空闲卡片:[fresh] 为 true 开新对话,false 接着最近的对话。 */
    fun freshChat(fresh: Boolean)
    /** 对话卡片滚到了顶:往前多加载几轮。 */
    fun loadEarlier()
    /** 输入框左边的截屏按钮:卡片先藏起来,截下用户此刻的屏幕,挂在输入框上面。 */
    fun captureScreen()
    /** 附件条上的 ×:不要这张截图。 */
    fun dropScreen()
}

/** 截下来、挂在输入框上面等着发的一屏:[thumb] 是缩略图(不让截图的页面没有)。 */
data class PendingScreen(val shot: CapturedScreen, val thumb: ImageBitmap?)

/**
 * 卡片底部输入框此刻的样子:[open] 时是能打字的框(浮层能接收键盘),否则是一行提示,点了才打开;
 * [screen] 是截下来挂在上面的那一屏(随下一句发出,只发它也行)。
 */
data class CardInputUi(val open: Boolean = false, val draft: String = "", val screen: PendingScreen? = null) {
    /** 写了字或挂着一屏:可以发。 */
    val canSend: Boolean get() = draft.isNotBlank() || screen != null
}

/**
 * 点开胶囊的卡片,不带状态,只说一件事(挑哪件见 [CapsuleCards.of])。
 * 任务卡片([CardView.Detail])左边是虚拟屏小画面 [preview](只有那件事有画面时才有),右边按状态放步骤、要你做的事和按钮;
 * 对话卡片([CardView.Chat])是那个对话的来回 [thread](还在读时为 null,中间先空着)。
 * 对话卡片固定 [height] 高(看得见的卡片,不含阴影边;不给时按屏幕高度的 [CardDocking.SHARE]),不随内容变;任务卡片
 * 按内容高、最多 [height],下面不留空——任务卡片中间那段取小画面(按画面的宽高比)和右边文字里高的那个。干活中的步骤最多和小画面
 * 一样高(还没有画面时按主屏的宽高比算,画面来了卡片不跳),步骤再多卡片也不变高:最新一步在底下,旧的往上滚着看;别的内容放不下就在里面滚,
 * 要你点的按钮和输入框固定在下面。高度变了(换卡片、内容变了)时一下到位:浮层窗口跟着内容大小,只变一次,不逐帧改窗口;上边不动(见 [CardDocking])。
 * 展开、收起的动效在外面套([OverlayMotion])。
 * [onHandle] 报标题栏在窗口里的位置(像素):按着它能拖卡片。
 * [zoomed] 时整张换成放大的画面,点一下回来。[elapsed] 把开始时刻换成用时(每秒更新)。
 */
@Composable
fun CapsuleCard(
    card: CardView,
    preview: Bitmap?,
    zoomed: Boolean,
    actions: CardActions,
    elapsed: @Composable (Long?) -> String?,
    modifier: Modifier = Modifier,
    input: CardInputUi = CardInputUi(),
    thread: ChatThread? = null,
    height: Dp = Dp.Unspecified,
    onHandle: (Rect) -> Unit = {},
) {
    val field = CapsuleCards.input(card)
    // 任务还在进行(干活中、Mika 问你、等条件)时,停止和对话页一样放在输入框右边,不另占一个按钮
    val stopKey = CapsuleCards.stopKey(card)
    val h = if (height == Dp.Unspecified) (LocalConfiguration.current.screenHeightDp * CardDocking.SHARE).dp else height
    val report by rememberUpdatedState(onHandle)
    val handle = Modifier.testTag("capsule-header").onGloballyPositioned { c ->
        val b = c.boundsInWindow()
        report(Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt()))
    }
    val inputRow: @Composable () -> Unit = {
        field?.let { InputRow(it, input, actions, stop = stopKey?.let { k -> { actions.stop(k) } }) }
    }
    Box(modifier.padding(CapsuleShadowRoom)) {
        when {
            zoomed && preview != null -> Zoomed(preview, actions)
            card is CardView.Detail -> Detail(card, preview, actions, elapsed, h, handle, inputRow)
            card is CardView.Chat -> ChatCard(card, thread, actions, elapsed, h, handle, inputRow)
        }
    }
}

/**
 * 浮层里的东西出现、收起的动效(卡片展开收起,收起后一颗回到边上):浮层窗口一下就是最后的大小和位置,里面的东西只靠图层的
 * 缩放和透明度从 [origin] 那边长出来(按此刻的大小给出方位,例如卡片从胶囊那边),真开始出来时调 [onOpened];
 * [open] 变成 false 时缩回那边、淡掉,做完调 [onClosed]。
 * 只动图层,不改布局,也就不逐帧改窗口。关了动画时直接到位,收起时马上调 [onClosed]。
 */
@Composable
fun OverlayMotion(
    open: Boolean, origin: (Size) -> TransformOrigin, onClosed: () -> Unit, onOpened: () -> Unit = {}, content: @Composable () -> Unit,
) {
    val still = MikaTheme.reduceMotion
    val shown = remember { Animatable(if (still) 1f else 0f) }
    val closed by rememberUpdatedState(onClosed)
    val opened by rememberUpdatedState(onOpened)
    val from by rememberUpdatedState(origin)
    LaunchedEffect(open, still) {
        if (open) {
            if (still) {
                shown.snapTo(1f)
                opened()
            } else {
                // 刚出来那一帧要排版、窗口换大小,最慢:等它画完再起步,动画的头几帧不被它吃掉
                if (shown.value == 0f) repeat(SETTLE_FRAMES) { withFrameNanos { } }
                opened()
                shown.animateTo(1f, tween(CARD_IN_MS, easing = MikaMotion.Decelerate))
            }
        } else {
            if (!still) {
                shown.animateTo(0f, tween(CARD_OUT_MS, easing = FastOutLinearInEasing))
                // 让完全看不见的那一帧先画出来,再换内容、挪窗口,不留一帧半透明的卡片
                withFrameNanos { }
            }
            closed()
        }
    }
    Box(
        Modifier.graphicsLayer {
            val v = shown.value
            val scale = CARD_FROM_SCALE + (1f - CARD_FROM_SCALE) * v
            scaleX = scale
            scaleY = scale
            // 透明度走得比缩放快:一出来就看得清,收起时最后才淡完
            alpha = (v * 2f).coerceAtMost(1f)
            transformOrigin = from(size)
        },
    ) { content() }
}

private const val CARD_IN_MS = 240
private const val CARD_OUT_MS = 180
private const val CARD_FROM_SCALE = 0.6f
private const val SETTLE_FRAMES = 2

/**
 * 底部输入框:平时是一行提示(浮层不抢键盘),点了打开成能打字的框并弹出输入法;回车或右边的按钮发出。
 * 有 [stop] 时右边一直有黑色的停止键(同对话页输入栏),写了字再多一个发送。
 */
@Composable
private fun InputRow(field: CardInput, ui: CardInputUi, actions: CardActions, stop: (() -> Unit)? = null) {
    ui.screen?.let { ScreenChip(it, actions::dropScreen) }
    Row(Modifier.padding(top = 10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CameraKey(actions::captureScreen)
        Box(Modifier.weight(1f)) { InputField(field, ui, actions, stop) }
    }
}

/** 输入框左边的截屏按钮:圆钮里一块屏。 */
@Composable
private fun CameraKey(onClick: () -> Unit) {
    val c = MikaTheme.colors
    Box(
        Modifier.testTag("capsule-camera").size(38.dp).clip(MikaShapes.Pill).background(c.ink.copy(alpha = 0.05f))
            .clickable(role = Role.Button, onClickLabel = ScreenCopy.CAMERA, onClick = onClick)
            .semantics { contentDescription = ScreenCopy.CAMERA },
        contentAlignment = Alignment.Center,
    ) {
        Icon(MikaIcons.Camera, null, Modifier.size(18.dp), tint = c.ink2)
    }
}

/** 输入框上面那一条:缩略图、「你此刻的屏幕」、App · 时刻,右边 × 不要它;不让截图的页面写「没截到」。 */
@Composable
private fun ScreenChip(p: PendingScreen, onRemove: () -> Unit) {
    val c = MikaTheme.colors
    val s = p.shot.screen
    Row(
        Modifier.testTag("capsule-attachment").padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(c.ink.copy(alpha = 0.05f)).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (s.blocked) Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) { Icon(MikaIcons.Lock, null, Modifier.size(16.dp), tint = c.ink2) }
        else ScreenThumb(p.thumb, 34.dp)
        Column(Modifier.weight(1f)) {
            Text(if (s.blocked) ScreenCopy.BLOCKED else ScreenCopy.TAG, style = Strong.copy(fontSize = 12.5.sp), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ScreenCopy.detail(s), style = Sub, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.testTag("capsule-attachment-remove").size(26.dp).clip(MikaShapes.Pill)
                .clickable(role = Role.Button, onClickLabel = ScreenCopy.REMOVE, onClick = onRemove)
                .semantics { contentDescription = ScreenCopy.REMOVE },
            contentAlignment = Alignment.Center,
        ) {
            Icon(MikaIcons.Close, null, Modifier.size(15.dp), tint = c.ink3)
        }
    }
}

/** 发送键:有东西可发时是蓝底,否则灰的点不了。 */
@Composable
private fun SendKey(enabled: Boolean, onSend: () -> Unit, start: Dp = 0.dp) {
    val c = MikaTheme.colors
    Box(
        Modifier.testTag("capsule-send").padding(start = start).size(KeySize).clip(MikaShapes.Pill)
            .background(if (enabled) c.accent else c.ink3.copy(alpha = 0.3f))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = CardCopy.SEND, onClick = onSend)
            .semantics { contentDescription = CardCopy.SEND },
        contentAlignment = Alignment.Center,
    ) {
        Icon(MikaIcons.Up, null, Modifier.size(17.dp), tint = Color.White)
    }
}

@Composable
private fun InputField(field: CardInput, ui: CardInputUi, actions: CardActions, stop: (() -> Unit)?) {
    val c = MikaTheme.colors
    val shape = RoundedCornerShape(14.dp)
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    if (!ui.open) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 42.dp).clip(shape).background(c.ink.copy(alpha = 0.05f))
                .padding(end = if (stop != null || ui.screen != null) 4.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.testTag("capsule-input").weight(1f).heightIn(min = 42.dp)
                    .clickable(role = Role.Button, onClickLabel = field.hint, onClick = actions::openInput).padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(ui.draft.ifEmpty { field.hint }, style = Body, color = if (ui.draft.isEmpty()) c.ink3 else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            stop?.let { StopKey(it) }
            // 挂着一屏时不用打开输入框也能直接发
            if (ui.screen != null) SendKey(true, actions::send, start = if (stop != null) 4.dp else 0.dp)
        }
        return
    }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 42.dp).clip(shape).background(c.ink.copy(alpha = 0.05f))
            .border(1.dp, c.accent.copy(alpha = 0.5f), shape).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
            if (ui.draft.isEmpty()) Text(field.hint, style = Body, color = c.ink3, maxLines = 1)
            BasicTextField(
                ui.draft, actions::draft,
                Modifier.testTag("capsule-field").fillMaxWidth().focusRequester(focus).semantics { contentDescription = field.hint },
                textStyle = Body.copy(color = c.ink), maxLines = 4, cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (ui.canSend) actions.send() }),
            )
        }
        stop?.let { StopKey(it) }
        if (stop == null || ui.canSend) SendKey(ui.canSend, actions::send, start = if (stop != null) 4.dp else 0.dp)
    }
}

/** 输入框右边的停止键:黑底圆钮里一个方块,同对话页输入栏。 */
@Composable
private fun StopKey(onStop: () -> Unit) {
    val c = MikaTheme.colors
    Box(
        Modifier.testTag("capsule-stop").size(KeySize).clip(MikaShapes.Pill).background(c.ink)
            .clickable(role = Role.Button, onClickLabel = CardCopy.STOP, onClick = onStop)
            .semantics { contentDescription = CardCopy.STOP },
        contentAlignment = Alignment.Center,
    ) {
        Icon(MikaIcons.Stop, null, Modifier.size(20.dp), tint = c.bg)
    }
}

private val KeySize = 34.dp

private const val TITLE_MAX = 12

@Composable
private fun cardWidth() = minOf(CardWidth, (LocalConfiguration.current.screenWidthDp - 24).coerceAtLeast(240).dp)

/** 卡片的底:[fixed] 时就是 [height] 高(对话卡片),否则按内容高、最多 [height](任务卡片)。 */
@Composable
private fun CardFrame(height: Dp, fixed: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val c = MikaTheme.colors
    Column(
        Modifier.testTag("capsule-card").width(cardWidth()).then(if (fixed) Modifier.height(height) else Modifier.heightIn(max = height))
            .glass(c, CardShape, need = false).padding(12.dp),
        content = content,
    )
}

/** 标题栏(按着它能拖卡片,见 [CapsuleCard] 的 onHandle):左边 Mika 图标,中间标题和状态,右边收起。 */
@Composable
private fun Header(title: String, sub: String, actions: CardActions, handle: Modifier) {
    val c = MikaTheme.colors
    Row(handle.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MikaLogo(28.dp, corner = 14.dp)
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(title, style = Title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = Sub, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(MikaIcons.Close, CardCopy.COLLAPSE, actions::collapse)
    }
}

/** 空闲卡片的标题栏:「接着「…」聊 · 新对话」或「新对话 · 接着上次聊」;没有任何对话时只有「新对话」。 */
@Composable
private fun IdleHeader(card: CardView.Chat, actions: CardActions, handle: Modifier) {
    val c = MikaTheme.colors
    val continuing = !card.fresh && card.conversationId != null
    Row(handle.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MikaLogo(28.dp, corner = 14.dp)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (continuing) tr(R.string.card_continuing, card.title.orEmpty().let(ScreenCopy::plain).let { if (it.length > TITLE_MAX) it.take(TITLE_MAX) + "…" else it }) else CardCopy.NEW_CHAT,
                Modifier.weight(1f, fill = false), style = Title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (card.conversationId != null) {
                Text(" · ", style = Sub, color = c.ink3)
                TextLink(if (continuing) CardCopy.NEW_CHAT else CardCopy.CONTINUE_LAST) { actions.freshChat(continuing) }
            }
        }
        IconButton(MikaIcons.Close, CardCopy.COLLAPSE, actions::collapse)
    }
}

@Composable
private fun IconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(MikaShapes.Pill).clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = MikaTheme.colors.ink3)
    }
}

/** 任务卡片:此刻在操作 App 或网页的那件事;按内容高,中间那段取左边小画面和右边文字里高的那个(步骤最多和小画面一样高)。 */
@Composable
private fun Detail(
    card: CardView.Detail, preview: Bitmap?, actions: CardActions, elapsed: @Composable (Long?) -> String?,
    height: Dp, handle: Modifier, input: @Composable () -> Unit,
) {
    val c = MikaTheme.colors
    val r = card.run
    val sub = listOfNotNull(CapsuleCards.status(r.kind), elapsed(r.startedAt)).joinToString(" · ")
    CardFrame(height, fixed = false) {
        Header(ScreenCopy.plain(r.run.goal).ifBlank { tr(R.string.app_name) }, sub, actions, handle)
        val screen = preview?.takeIf { CapsuleCards.showsScreen(card.content) }
        // 步骤那段的高度上限 = 小画面的高;还没有画面时按主屏的宽高比算同样的高,画面来了不跳
        val config = LocalConfiguration.current
        val stepsMax = ScreenWidth / (screen?.let(::ratio) ?: ratio(config.screenWidthDp, config.screenHeightDp))
        Row(Modifier.padding(top = 10.dp).weight(1f, fill = false).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (screen != null) MiniScreen(screen) { actions.zoom(true) }
            Column(Modifier.weight(1f)) {
                val content = card.content
                // 干活中的步骤单独排(自己滚、最多和小画面一样高),没有要你点的按钮
                if (content is CardContent.Working) Steps(content.steps, stepsMax) else {
                    // 文字放不下就在这一段里滚;要你点的按钮放在下面,不跟着滚走
                    Column(
                        Modifier.testTag("capsule-scroll").weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        when (content) {
                            // 干活中在上面单独排
                            is CardContent.Working -> Unit
                            is CardContent.Takeover -> {
                                Text(content.need, style = Strong, color = c.warn)
                                if (content.body.isNotEmpty()) Text(content.body, style = Body, color = c.ink2)
                                content.queueLine?.let { Text(it, style = Sub, color = c.ink3) }
                            }
                            is CardContent.Confirm -> {
                                Text(content.title, style = Strong, color = c.warn)
                                Text(content.body, style = Body, color = c.ink2)
                                Text(ConfirmationCopy.NOTE, style = Sub, color = c.ink3)
                            }
                            is CardContent.Question -> Text(content.prompt, style = Body, color = c.ink)
                            is CardContent.Held -> Text(content.why, style = Body, color = c.ink2)
                            // 结束了的是对话卡片,不在这里
                            is CardContent.Result -> Unit
                        }
                    }
                    Pinned(content, r.run.key, actions)
                }
            }
        }
        input()
        Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
            TextLink(CardCopy.SEE_PROCESS) { actions.openMika(r.run.conversationId) }
        }
    }
}

/** 要你点的按钮(接管、同意、选项、继续),固定在卡片中间那段的下面,不跟着滚。 */
@Composable
private fun Pinned(content: CardContent?, key: String, actions: CardActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (content) {
            is CardContent.Takeover -> {
                Spacer(Modifier.height(0.dp))
                if (content.canTakeOver) Action(CardCopy.TAKE_OVER, ButtonKind.Primary) { actions.takeOver(key) }
                Action(CardCopy.STOP, ButtonKind.Tonal) { actions.stop(key) }
            }
            is CardContent.Confirm -> {
                Spacer(Modifier.height(0.dp))
                Action(CardCopy.APPROVE_ONCE_ONLY, ButtonKind.Primary) { actions.approve(key, content.key) }
                Action(CardCopy.DECLINE, ButtonKind.Tonal) { actions.decline(key, content.key) }
            }
            is CardContent.Question -> if (content.options.isNotEmpty()) {
                Spacer(Modifier.height(0.dp))
                content.options.forEach { o -> Action(o, ButtonKind.Tonal) { actions.answer(key, o) } }
            }
            is CardContent.Held -> if (content.resumable) {
                Spacer(Modifier.height(0.dp))
                Action(CardCopy.RESUME, ButtonKind.Primary) { actions.resume(key) }
            }
            else -> Unit
        }
    }
}

/**
 * 对话卡片:那个对话的来回在中间滚;要你回答、同意时按钮固定在来回下面,再下面是输入框和「回到 Mika」。
 * 不带任务时是空闲卡片(标题栏能切新对话)。
 */
@Composable
private fun ChatCard(
    card: CardView.Chat, thread: ChatThread?, actions: CardActions, elapsed: @Composable (Long?) -> String?,
    height: Dp, handle: Modifier, input: @Composable () -> Unit,
) {
    val r = card.run
    CardFrame(height) {
        if (r == null) IdleHeader(card, actions, handle)
        else Header(
            ScreenCopy.plain(thread?.title ?: r.run.goal).ifBlank { tr(R.string.app_name) },
            listOfNotNull(CapsuleCards.status(r.kind), elapsed(r.startedAt)).joinToString(" · "),
            actions, handle,
        )
        Box(Modifier.padding(top = 8.dp).weight(1f).fillMaxWidth()) {
            // 换了对话(空闲卡片切到新对话)时从那个对话的最新一问重新读起
            if (thread != null) key(card.target) { ThreadList(thread, card.conversationId, actions) }
        }
        if (r != null) Pinned(card.content, r.run.key, actions)
        input()
        card.target?.let { id ->
            Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
                TextLink(CardCopy.BACK_TO_MIKA) { actions.openMika(id) }
            }
        }
    }
}

/**
 * 对话的来回(旧的在上):打开时最新那句你说的话停在顶上;滚到顶往前多加载几轮,列表按每条的 key 停在原处;
 * 往下长的字只在你本来就在底部时跟着往下(同对话页);来了新的一问时滚到它。
 */
@Composable
private fun ThreadList(thread: ChatThread, conversationId: String?, actions: CardActions) {
    val c = MikaTheme.colors
    val state = rememberLazyListState(initialFirstVisibleItemIndex = thread.anchor)
    var following by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > 0 }
        following = !state.canScrollForward
        var wasScrolling = false
        snapshotFlow { state.isScrollInProgress to !state.canScrollForward }.collect { (scrolling, atEnd) ->
            if (scrolling || wasScrolling) following = atEnd
            wasScrolling = scrolling
        }
    }
    val anchorKey = thread.items.getOrNull(thread.anchor)?.key
    var lastAnchor by remember { mutableStateOf(anchorKey) }
    LaunchedEffect(anchorKey) {
        if (anchorKey == lastAnchor) return@LaunchedEffect
        lastAnchor = anchorKey
        state.scrollToItem(thread.anchor)
    }
    // 回答按 Markdown 排版(流式时在后台解析),排好了高度才变:按条数和最后一条排出来的高度跟,不按字数
    LaunchedEffect(state) {
        snapshotFlow {
            val info = state.layoutInfo
            info.totalItemsCount to info.visibleItemsInfo.lastOrNull()?.takeIf { it.index == info.totalItemsCount - 1 }?.size
        }.collect { (n, _) ->
            if (following && !state.isScrollInProgress && n > 0) state.scrollToItem(n - 1, END_OFFSET)
        }
    }
    if (thread.hasEarlier) LaunchedEffect(thread.items.size) {
        snapshotFlow { state.firstVisibleItemIndex == 0 }.first { it }
        actions.loadEarlier()
    }
    LazyColumn(Modifier.testTag("capsule-thread").fillMaxWidth().fillMaxHeight(), state = state) {
        items(thread.items, key = { it.key }) { item ->
            when (item) {
                // 带着屏幕的那句:缩略图和「你此刻的屏幕 · App · 时刻」,附言另起一个气泡
                is ThreadItem.User -> BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp), contentAlignment = Alignment.CenterEnd) {
                    val maxWidth = maxWidth
                    Column(horizontalAlignment = Alignment.End) {
                        UserMessage(conversationId, item.text, ThreadThumbWidth) { text ->
                            Text(
                                text,
                                Modifier.widthIn(max = maxWidth * 0.8f).background(c.accent, BubbleShape).padding(horizontal = 11.dp, vertical = 7.dp),
                                style = Body, color = c.onAccent,
                            )
                        }
                        // 插的话的去向(已收到、已加入、下一件事)
                        item.note?.let { Text(it, Modifier.padding(top = 2.dp, end = 4.dp), style = Sub, color = c.ink3) }
                    }
                }
                is ThreadItem.Note -> Text(
                    item.text, Modifier.padding(bottom = 4.dp), style = Sub,
                    color = if (item.kind.needsYou) c.warn else c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                // 表格、代码块在自己那块里左右滑,不撑宽卡片;链接另开任务打开,浮层里也能点
                is ThreadItem.Answer -> MarkdownText(item.text, Body.copy(color = c.ink), Modifier.fillMaxWidth().padding(bottom = 8.dp), streaming = item.streaming)
            }
        }
    }
}

/** 卡片来回里截图缩略图的宽。 */
private val ThreadThumbWidth = 78.dp

/** 跟到底部时滚到最后一条再往下这么多像素,由列表夹到真正的底。 */
private const val END_OFFSET = 1_000_000

/**
 * 干活中的步骤(旧的在上):按内容高、最多 [max] 高。放不下时最新一步留在底下,旧的在上面截掉、顶上淡出,往上滚看更早的;
 * 来了新的一步时,本来就在底部才跟着往下,往上翻着看时不拽回去(同 [ThreadList])。
 */
@Composable
private fun Steps(steps: List<String>, max: Dp) {
    val c = MikaTheme.colors
    // 倒着排:第 0 项是最新一步,贴在底下
    val state = rememberLazyListState()
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(state) {
        var wasScrolling = false
        snapshotFlow { state.isScrollInProgress to !state.canScrollBackward }.collect { (scrolling, atEnd) ->
            if (scrolling || wasScrolling) following = atEnd
            wasScrolling = scrolling
        }
    }
    LaunchedEffect(steps.size) {
        if (following && !state.isScrollInProgress) state.scrollToItem(0)
    }
    val fade = with(LocalDensity.current) { FadeEdge.toPx() }
    LazyColumn(
        Modifier.testTag("capsule-steps").fillMaxWidth().heightIn(max = max)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                // 上面还有更早的步骤:顶上淡出
                if (state.canScrollForward) drawRect(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, endY = fade), blendMode = BlendMode.DstIn)
            },
        state = state,
        reverseLayout = true,
    ) {
        // 按从旧到新的序号作 key:往上翻着看时来了新的一步,看着的那几步留在原处
        items(steps.size, key = { steps.lastIndex - it }) { j ->
            val i = steps.lastIndex - j
            val current = j == 0
            StepItem(if (current) StepState.Current else StepState.Done, first = i == 0, last = current, dotY = 11.dp) {
                Text(
                    steps[i], Modifier.padding(vertical = 2.dp), style = if (current) Step.copy(fontWeight = FontWeight.Medium) else Step,
                    color = if (current) c.ink else c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val FadeEdge = 28.dp

@Composable
private fun Action(text: String, kind: ButtonKind, onClick: () -> Unit) {
    MikaButton(text, onClick, Modifier.fillMaxWidth(), kind = kind, size = ButtonSize.Medium)
}

@Composable
private fun TextLink(text: String, onClick: () -> Unit) {
    Text(
        text, Modifier.clip(MikaShapes.Pill).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp),
        style = Sub.copy(fontSize = 13.sp), color = MikaTheme.colors.accent,
    )
}

/** 虚拟屏小画面,点一下放大。 */
@Composable
private fun MiniScreen(frame: Bitmap, onZoom: () -> Unit) {
    val c = MikaTheme.colors
    val image = remember(frame) { frame.asImageBitmap() }
    Box(
        Modifier.testTag("capsule-screen").width(ScreenWidth).aspectRatio(ratio(frame)).clip(ScreenShape)
            .border(1.dp, c.glassEdge, ScreenShape).background(c.surface)
            .clickable(role = Role.Image, onClickLabel = CardCopy.SCREEN, onClick = onZoom)
            .semantics { contentDescription = CardCopy.SCREEN },
    ) {
        Image(image, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
        Text(
            CardCopy.SCREEN_LABEL, Modifier.align(Alignment.BottomStart).padding(6.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
            style = TextStyle(fontSize = 10.sp, lineHeight = 14.sp), color = Color.White,
        )
    }
}

/** 放大的画面:点一下回到卡片。 */
@Composable
private fun Zoomed(frame: Bitmap, actions: CardActions) {
    val image = remember(frame) { frame.asImageBitmap() }
    val width = minOf(ZoomWidth, (LocalConfiguration.current.screenWidthDp * 0.72f).dp)
    Box(
        Modifier.testTag("capsule-zoom").width(width).aspectRatio(ratio(frame)).glass(MikaTheme.colors, CardShape, need = false)
            .clickable(role = Role.Image, onClickLabel = CardCopy.ZOOMED) { actions.zoom(false) }
            .semantics { contentDescription = CardCopy.ZOOMED },
    ) {
        Image(image, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
        Text(
            CardCopy.ZOOM_HINT, Modifier.align(Alignment.BottomCenter).padding(10.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            style = Sub, color = Color.White,
        )
    }
}

/** 画面的宽高比,夹在合理范围里(防止异常尺寸撑坏卡片)。 */
private fun ratio(b: Bitmap): Float = ratio(b.width, b.height)

private fun ratio(width: Int, height: Int): Float = if (height <= 0) 0.45f else (width.toFloat() / height).coerceIn(0.3f, 1.8f)
