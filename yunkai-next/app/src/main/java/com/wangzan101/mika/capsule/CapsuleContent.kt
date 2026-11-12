// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.capsule

import com.wangzan101.mika.i18n.tr
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.ScreenCopy
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaLogo
import com.wangzan101.mika.ui.theme.MikaColors
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlin.math.ceil

/** 胶囊外面留给阴影的边:浮层窗口按内容大小,阴影画在窗口里面;留得越少,窗口挡住下面 App 的透明边越窄。 */
internal val CapsuleShadowRoom = 8.dp
private val CapsuleHeight = 40.dp
private val BarHeight = 48.dp
private val LogoSize = 30.dp
private const val FADE_MS = 400
/** 截到屏幕时一颗上那一闪:多亮、多久退掉。 */
private const val FLASH_ALPHA = 0.8f
private const val FLASH_MS = 320
/** 一颗展开成胶囊、缩回一颗的时长。 */
private const val MORPH_MS = 260
/** 干活中的圈转一周的时长。 */
private const val SPIN_MS = 1_200
private val CapsuleText = TextStyle(fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
private val BarDetail = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp)
private val CountText = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold)

/** 胶囊上的固定说法。 */
object CapsuleCopy {
    val STOP: String get() = tr(R.string.capsule_stop)
    val EXPAND: String get() = tr(R.string.capsule_expand)
    val TALK: String get() = tr(R.string.capsule_talk)
    val SWAP_SIDE: String get() = tr(R.string.capsule_swap_side)
    val IDLE get() = tr(R.string.app_name)

    /** 读屏时一颗图标的说法:在做什么、要不要你处理、结果;好几件时的件数已在 [CapsuleView.Shown.text] 里。 */
    fun describe(view: CapsuleView.Shown): String = when {
        view.kind == CapsuleKind.Idle -> IDLE
        view.kind.needsYou -> tr(R.string.capsule_desc_needs, view.text)
        view.kind == CapsuleKind.Running -> tr(R.string.capsule_desc_running, view.text)
        view.kind == CapsuleKind.Held || view.kind == CapsuleKind.Cancelled -> tr(R.string.capsule_desc_other, view.text)
        else -> "${tr(R.string.app_name)} ${view.text}"
    }
}

/**
 * 悬浮胶囊,不带状态:平时是一颗 Mika 图标,状态画在外圈和角标上(干活中蓝弧绕着转、等条件灰圈、要你处理整颗变橙、
 * 外圈一呼一吸;好几件时角上写件数;办好了绿勾,没办成、已停止灰色角标;空闲时什么都没有);
 * [CapsuleView.Shown.pill] 时展开成一行字的胶囊(要你处理、刚办完),宽度慢慢变,图标贴着停靠的那一边([side])不动;
 * App 交到你手上时是顶部那一条「你来处理 · 不做了 · 处理好了」。
 * 胶囊只一行字,最宽到屏幕的 60%,超出截断;同一个 [CapsuleView.Shown.widthEpoch] 里只变宽不变窄。
 * 点一颗或胶囊交给 [onTap](展开卡片),读屏时这一下的说法是 [tapLabel];那一条上的两个按钮交给 [onStop]、[onHandBack]。
 * [onSwapSide] 给了时读屏软件多一个「移到屏幕另一边」,代替拖动。[alpha] 只淡底和图标,外圈和角标保持实色。
 * 双击由调用方从两次 [onTap] 认出来;读屏软件的双击就是点按,所以 [onSendScreen] 给了时另有一个「把此刻的屏幕发给 Mika」。
 * [flash] 每加一,一颗上闪一下(截到了屏幕)。
 */
@Composable
fun CapsuleContent(
    view: CapsuleView.Shown,
    onTap: () -> Unit,
    onStop: () -> Unit,
    onHandBack: () -> Unit,
    modifier: Modifier = Modifier,
    side: DockSide = DockSide.Right,
    tapLabel: String = CapsuleCopy.EXPAND,
    onSwapSide: (() -> Unit)? = null,
    alpha: Float = 1f,
    onSendScreen: (() -> Unit)? = null,
    flash: Int = 0,
) {
    // 淡下去、恢复都慢慢来;关了动画时直接换
    val shown = if (MikaTheme.reduceMotion) alpha else animateFloatAsState(alpha, tween(FADE_MS), label = "fade").value
    val a11y = Modifier.semantics {
        customActions = listOfNotNull(
            onSwapSide?.let { f -> CustomAccessibilityAction(CapsuleCopy.SWAP_SIDE) { f(); true } },
            onSendScreen?.let { f -> CustomAccessibilityAction(ScreenCopy.SEND_SCREEN) { f(); true } },
        )
    }
    Box(modifier.padding(CapsuleShadowRoom)) {
        if (view.kind == CapsuleKind.HandedOver) TakeoverBar(view, onStop, onHandBack)
        else Capsule(view, side == DockSide.Right, onTap, tapLabel, a11y, shown, flash)
    }
}

/** 一颗或展开的胶囊:同一块玻璃底,看得见的宽度在两者之间慢慢变(只在画的时候变,见 [Morph]);[end] 时贴右边(图标在右)。 */
@Composable
private fun Capsule(view: CapsuleView.Shown, end: Boolean, onTap: () -> Unit, tapLabel: String, a11y: Modifier, alpha: Float, flash: Int) {
    val c = MikaTheme.colors
    val need = view.kind.needsYou || view.needCount > 0
    // 截到屏幕:白光从亮到无,和截图时屏幕闪一下一个意思;关了动画时不闪
    val glow = remember { Animatable(0f) }
    val still = MikaTheme.reduceMotion
    val morph = rememberMorph(animate = !still)
    LaunchedEffect(flash) {
        if (flash == 0 || still) return@LaunchedEffect
        glow.snapTo(FLASH_ALPHA)
        glow.animateTo(0f, tween(FLASH_MS))
    }
    Box {
        Box(
            Modifier.testTag(if (view.pill) "capsule" else "capsule-ball")
                .morphGlass(morph, end, c, need, alpha)
                .clickable(onClickLabel = tapLabel, role = Role.Button, onClick = onTap)
                .then(if (view.pill) Modifier else Modifier.semantics { contentDescription = CapsuleCopy.describe(view) })
                .then(a11y)
                .morphLayout(morph, end),
        ) {
            if (view.pill) PillRow(view, end, need)
            else Box(Modifier.size(CapsuleHeight), contentAlignment = Alignment.Center) { CapsuleLogo() }
            if (glow.value > 0f) Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Color.White.copy(alpha = glow.value)))
        }
        // 外圈和角标不随底淡下去;缩回时贴着图标那一边,不跟着还没收完的宽度走
        if (!view.pill) Box(Modifier.align(if (end) Alignment.CenterEnd else Alignment.CenterStart).size(CapsuleHeight)) {
            Ring(view.kind, need, Modifier.matchParentSize())
            Badge(view, need, Modifier.align(Alignment.TopEnd).offset(3.dp, (-3).dp))
        }
    }
}

/** 展开的胶囊里那一行:图标、状态标记、字;贴右边时图标在最右,和一颗时的位置一样。 */
@Composable
private fun PillRow(view: CapsuleView.Shown, end: Boolean, need: Boolean) {
    val c = MikaTheme.colors
    val maxWidth = (LocalConfiguration.current.screenWidthDp * 0.6f).dp
    // 同一件事、同一个状态里换句只变宽:记下这一段里最宽的时候
    var widest by remember(view.widthEpoch) { mutableIntStateOf(0) }
    val minWidth = with(LocalDensity.current) { widest.toDp() }
    Row(
        Modifier.widthIn(min = minWidth.coerceAtMost(maxWidth), max = maxWidth)
            .onSizeChanged { if (it.width > widest) widest = it.width }
            .height(CapsuleHeight)
            .padding(start = if (end) 13.dp else 5.dp, end = if (end) 5.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (end) Alignment.End else Alignment.Start),
    ) {
        if (!end) CapsuleLogo()
        Leading(view.kind)
        Text(
            view.text, Modifier.weight(1f, fill = false), style = CapsuleText,
            color = if (need) c.warn else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (end) CapsuleLogo()
    }
}

/** 文字前面的状态标记:要你处理是呼吸点,办好了是勾,没办成是叹号,已停止是停止。 */
@Composable
private fun Leading(kind: CapsuleKind) {
    val c = MikaTheme.colors
    when {
        kind.needsYou -> BreathingDot(c)
        kind == CapsuleKind.Done -> Icon(MikaIcons.Check, null, Modifier.size(16.dp), tint = c.ok)
        kind == CapsuleKind.Failed -> Icon(MikaIcons.Exclaim, null, Modifier.size(16.dp), tint = c.ink3)
        kind == CapsuleKind.Cancelled -> Icon(MikaIcons.Stop, null, Modifier.size(16.dp), tint = c.ink3)
        else -> Unit
    }
}

/** 需要你:橙色圆点,外面一圈淡橙一呼一吸;关了动画时不动。 */
@Composable
private fun BreathingDot(c: MikaColors) {
    val halo = breathe(still = 0f)
    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(14.dp).graphicsLayer { alpha = halo }.background(c.warnSoft, MikaShapes.Pill))
        Box(Modifier.size(8.dp).background(c.warnSolid, MikaShapes.Pill))
    }
}

/** 一呼一吸(0..1 来回);关了动画时停在 [still]。 */
@Composable
private fun breathe(still: Float): Float = if (MikaTheme.reduceMotion) still else rememberInfiniteTransition(label = "breathe").animateFloat(
    0f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "breathe",
).value

/** 一颗图标的外圈:干活中一段蓝弧绕着转(关了动画时是一整圈不动),等条件灰色一整圈,要你处理橙色一整圈一呼一吸;其余没有。 */
@Composable
private fun Ring(kind: CapsuleKind, need: Boolean, modifier: Modifier) {
    val c = MikaTheme.colors
    val color = when {
        need -> c.warnSolid
        kind == CapsuleKind.Running -> c.accent
        kind == CapsuleKind.Held -> c.ink3
        else -> return
    }
    val spin = kind == CapsuleKind.Running && !need && !MikaTheme.reduceMotion
    val angle = if (!spin) 0f else rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing)), label = "spin",
    ).value
    val strength = if (need) 0.45f + 0.55f * breathe(still = 1f) else 1f
    Canvas(modifier.testTag("capsule-ring")) {
        val stroke = 2.dp.toPx()
        val topLeft = Offset(stroke / 2, stroke / 2)
        val box = Size(size.width - stroke, size.height - stroke)
        if (spin) {
            drawArc(c.accentSoft, 0f, 360f, useCenter = false, topLeft = topLeft, size = box, style = Stroke(stroke))
            drawArc(color, angle - 90f, 100f, useCenter = false, topLeft = topLeft, size = box, style = Stroke(stroke, cap = StrokeCap.Round))
        } else {
            drawArc(color.copy(alpha = strength), 0f, 360f, useCenter = false, topLeft = topLeft, size = box, style = Stroke(stroke))
        }
    }
}

/** 一颗图标角上的标:好几件时写件数(要你处理橙、等条件灰、其余蓝);只一件时办好了绿勾,没办成、已停止灰色。 */
@Composable
private fun Badge(view: CapsuleView.Shown, need: Boolean, modifier: Modifier) {
    val c = MikaTheme.colors
    if (view.count >= 2) {
        val fill = when {
            need -> c.warnSolid
            view.kind == CapsuleKind.Held -> c.ink3
            else -> c.accent
        }
        Box(
            modifier.testTag("capsule-count").background(c.surface, MikaShapes.Pill).padding(1.5.dp)
                .sizeIn(minWidth = 15.dp, minHeight = 15.dp).background(fill, MikaShapes.Pill).padding(horizontal = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("${view.count}", style = CountText, color = c.onAccent, maxLines = 1)
        }
        return
    }
    val (icon, tint) = when (view.kind) {
        CapsuleKind.Done -> MikaIcons.Check to c.ok
        CapsuleKind.Failed -> MikaIcons.Exclaim to c.ink3
        CapsuleKind.Cancelled -> MikaIcons.Stop to c.ink3
        else -> return
    }
    Box(
        modifier.testTag("capsule-badge").size(16.dp).background(c.surface, MikaShapes.Pill).padding(1.5.dp)
            .background(tint, MikaShapes.Pill),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(9.dp), tint = c.surface)
    }
}

/**
 * 一颗和胶囊之间宽度慢慢变,只变画出来的那块玻璃,不逐帧改布局:浮层窗口跟着内容大小,布局一变窗口就要跟着重排、换画布,
 * 逐帧变就一卡一卡。[target] 是内容此刻的宽,[width] 是看得见的宽(动画),[hold] 是动画期间布局占的宽——起点和终点里宽的那个,
 * 所以变宽时窗口一下到最宽、变窄时等画完才缩,一次变形窗口至多变一次。第一次出现和关了动画时直接是内容的宽度。
 */
private class Morph {
    val width = Animatable(0f)
    var target by mutableIntStateOf(0)
    var hold by mutableIntStateOf(0)
}

@Composable
private fun rememberMorph(animate: Boolean): Morph {
    val m = remember { Morph() }
    LaunchedEffect(m.target, animate) {
        val t = m.target
        if (t == 0) return@LaunchedEffect
        if (!animate || m.width.value == 0f) m.width.snapTo(t.toFloat())
        else {
            m.hold = maxOf(ceil(m.width.value).toInt(), t)
            m.width.animateTo(t.toFloat(), tween(MORPH_MS))
        }
        m.hold = t
    }
    return m
}

/** 布局占 [Morph.hold] 和内容里宽的那个,内容贴着停靠的那一边摆([end] 时贴右边):向里长、向边收,图标不动。 */
private fun Modifier.morphLayout(m: Morph, end: Boolean): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minWidth = 0))
    if (p.width != m.target) m.target = p.width
    val w = maxOf(m.hold, p.width)
    layout(w, p.height) { p.place(if (end) w - p.width else 0, 0) }
}

/**
 * 玻璃底(同 [glass]),只画看得见的那一段宽、贴着停靠的那一边:阴影、裁切、底色和描边都按它,宽度变时只重画图层。
 * [alpha] 淡底和里面的图标。
 */
private fun Modifier.morphGlass(m: Morph, end: Boolean, c: MikaColors, need: Boolean, alpha: Float): Modifier =
    graphicsLayer {
        this.alpha = alpha
        shadowElevation = 8.dp.toPx()
        ambientShadowColor = c.floatShadow.copy(alpha = 0.25f)
        spotShadowColor = c.floatShadow.copy(alpha = 0.3f)
        shape = EdgePill(m.width.value, end)
        clip = true
    }.drawWithContent {
        val pill = EdgePill(m.width.value, end)
        val o = pill.createOutline(size, layoutDirection, this)
        drawOutline(o, c.surface.copy(alpha = 0.95f))
        if (need) drawOutline(o, c.warnSoft)
        drawContent()
        val stroke = (if (need) 1.dp else 0.5.dp).toPx()
        drawOutline(EdgePill(m.width.value, end, inset = stroke / 2).createOutline(size, layoutDirection, this),
            if (need) c.warnSolid.copy(alpha = 0.5f) else c.glassEdge, style = Stroke(stroke))
    }

/** 宽 [width](0 或超出时按整块)、贴着一边([end] 时右边)的胶囊形;[inset] 往里收(画描边用)。 */
private class EdgePill(private val width: Float, private val end: Boolean, private val inset: Float = 0f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = if (width > 0f) width.coerceAtMost(size.width) else size.width
        val left = if (end) size.width - w else 0f
        val r = size.height / 2 - inset
        return Outline.Rounded(RoundRect(left + inset, inset, left + w - inset, size.height - inset, CornerRadius(r, r)))
    }
}

/** App 在你手上时顶部那一条:「你来处理」和下面一行小字,右边「不做了」「处理好了」。 */
@Composable
private fun TakeoverBar(view: CapsuleView.Shown, onStop: () -> Unit, onHandBack: () -> Unit) {
    val c = MikaTheme.colors
    val maxWidth = (LocalConfiguration.current.screenWidthDp - 32).coerceAtLeast(200).dp
    Row(
        Modifier.testTag("capsule-bar")
            .glass(c, MikaShapes.Pill, need = false)
            .widthIn(max = maxWidth)
            .height(BarHeight)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CapsuleLogo()
        Column(Modifier.weight(1f, fill = false).semantics(mergeDescendants = true) {}) {
            Text(view.text, style = CapsuleText, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            view.detail?.let { Text(it, style = BarDetail, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Box(
            Modifier.clip(MikaShapes.Pill).clickable(role = Role.Button, onClick = onStop)
                .heightIn(min = 36.dp).padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(CapsuleCopy.STOP, style = CapsuleText.copy(fontWeight = FontWeight.Normal), color = c.ink2, maxLines = 1)
        }
        MikaButton(stringResource(R.string.action_hand_back), onHandBack, size = ButtonSize.Small)
    }
}

/** 胶囊上的 Mika 图标(圆形)。 */
@Composable
private fun CapsuleLogo() {
    MikaLogo(LogoSize, corner = LogoSize / 2)
}

/**
 * 浮在别的 App 上的玻璃底:浮层窗口里拿不到下面 App 的画面做模糊,用接近不透明的卡片底、细描边和悬浮阴影;
 * 需要你时叠一层淡橙、描边变橙。
 */
internal fun Modifier.glass(c: MikaColors, shape: Shape, need: Boolean): Modifier =
    shadow(8.dp, shape, clip = false, ambientColor = c.floatShadow.copy(alpha = 0.25f), spotColor = c.floatShadow.copy(alpha = 0.3f))
        .clip(shape)
        .background(c.surface.copy(alpha = 0.95f))
        .then(if (need) Modifier.background(c.warnSoft).border(1.dp, c.warnSolid.copy(alpha = 0.5f), shape) else Modifier.border(0.5.dp, c.glassEdge, shape))
