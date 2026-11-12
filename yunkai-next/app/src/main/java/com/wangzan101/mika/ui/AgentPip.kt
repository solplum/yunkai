package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import android.view.Display
import android.util.DisplayMetrics
import android.hardware.display.DisplayManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.PipPhysics.Corner
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.components.floatShadow

/** 小窗宽度:收起 / 展开。高度按画面(没有画面时按主屏)的长宽比算,整屏完整显示、不裁底部。 */
private val CollapsedWidth = 96.dp
private val ExpandedWidth = 220.dp

/** 主屏的高宽比(按真实像素,含系统栏):虚拟屏按主屏尺寸建,还没有画面时小窗先按它定大小。 */
private fun mainScreenAspect(context: Context): Float {
    val m = DisplayMetrics()
    context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(m)
    return if (m.widthPixels > 0 && m.heightPixels > 0) m.heightPixels.toFloat() / m.widthPixels else DEFAULT_ASPECT
}

private const val DEFAULT_ASPECT = 2.2f
private val Ease = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/**
 * 悬浮画中画:盖在对话上,只显示 Agent 虚拟屏的实时画面,只看不点;上面不放文字和按钮,要你做的事都在对话卡片里。
 * 状态只靠边框光:干活中细绿边,需要你时橙色呼吸光晕(接管开始时由 [ChatViewModel] 自动展开)。
 * 平时停在右下角、输入栏上方,不挡顶栏和最新的消息。
 * 拖动 1:1 跟手;松手按速度投射落点吸到最近的角(临界阻尼弹簧,见 [PipPhysics]);
 * 点一下展开,点 × 或外面收起;需要你时点一下交给 [onAttention](收起并滚到那张卡片)。
 * [reduceMotion] 时(系统关了动画)不做弹簧、缩放和呼吸,直接到位。
 * [topInset]/[bottomInset] 是顶栏和输入框占的像素高度,小窗不会停在它们下面。
 */
@Composable
fun AgentPip(
    pip: PipUi,
    frame: Bitmap?,
    webContent: Boolean,
    reduceMotion: Boolean,
    topInset: Float,
    bottomInset: Float,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onAttention: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fade = if (reduceMotion) tween<Float>(150) else tween(300)
        AnimatedVisibility(pip.visible && pip.expanded, enter = fadeIn(fade), exit = fadeOut(fade)) {
            Box(Modifier.fillMaxSize().background(MikaTheme.colors.floatShadow.copy(alpha = 0.12f)).pointerInput(Unit) { detectTapGestures { onCollapse() } })
        }
        AnimatedVisibility(pip.visible, enter = fadeIn(fade), exit = fadeOut(if (reduceMotion) tween(150) else tween(500))) {
            PipWindow(
                pip, frame, webContent, reduceMotion,
                areaWidth = constraints.maxWidth.toFloat(), areaHeight = constraints.maxHeight.toFloat(),
                topInset = topInset, bottomInset = bottomInset, onExpand = onExpand, onCollapse = onCollapse, onAttention = onAttention,
            )
        }
    }
}

@Composable
private fun PipWindow(
    pip: PipUi,
    frame: Bitmap?,
    webContent: Boolean,
    reduceMotion: Boolean,
    areaWidth: Float,
    areaHeight: Float,
    topInset: Float,
    bottomInset: Float,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onAttention: () -> Unit,
) {
    val density = LocalDensity.current
    val sizeSpec = if (reduceMotion) snap() else tween<androidx.compose.ui.unit.Dp>(350, easing = Ease)
    // 大小跟着画面的长宽比走:手机画面是主屏的比例(不同手机略有长短),桌面网页是横向的,按固定的宽度定高度,
    // 画面整张放得下;放不下时按比例整体缩小
    val context = LocalContext.current
    val screenAspect = remember { mainScreenAspect(context) }
    val landscapeWeb = frame != null && webContent && frame.width > frame.height
    val aspect = frame?.let { it.height.toFloat() / it.width } ?: screenAspect
    val target = run {
        val margin = with(density) { 24.dp.toPx() }
        val availableWidth = with(density) { (areaWidth - margin).coerceAtLeast(1f).toDp() }
        val availableHeight = with(density) { (areaHeight - topInset - bottomInset - margin).coerceAtLeast(1f).toDp() }
        val base = when {
            landscapeWeb -> if (pip.expanded) 320.dp else 220.dp
            else -> if (pip.expanded) ExpandedWidth else CollapsedWidth
        }
        val width = minOf(base, availableWidth, availableHeight / aspect)
        width to width * aspect
    }
    val w by animateDpAsState(target.first, sizeSpec, label = "pipW")
    val h by animateDpAsState(target.second, sizeSpec, label = "pipH")
    val wPx = with(density) { w.toPx() }
    val hPx = with(density) { h.toPx() }
    val bounds = PipPhysics.Bounds(areaWidth, areaHeight, wPx, hPx, with(density) { 12.dp.toPx() }, topInset, bottomInset)
    val currentBounds by rememberUpdatedState(bounds)
    val motionOff by rememberUpdatedState(reduceMotion)
    val expanded by rememberUpdatedState(pip.expanded)
    val expand by rememberUpdatedState(onExpand)
    val needsYou by rememberUpdatedState(pip.mode == PipMode.NeedsYou)
    val attend by rememberUpdatedState(onAttention)

    var corner by rememberSaveable { mutableStateOf(Corner.BottomEnd) }
    // 拖动或弹簧途中的位置;为空表示贴在 corner 上(展开收起时跟着大小一起贴角)
    var free by remember { mutableStateOf<Offset?>(null) }
    val scope = rememberCoroutineScope()
    var settle by remember { mutableStateOf<Job?>(null) }

    /** 从 [start] 以速度 (vx, vy) 弹到角 [c];关了动画时直接到位。 */
    fun springTo(c: Corner, start: Offset, vx: Float, vy: Float) {
        settle?.cancel()
        corner = c
        if (motionOff) {
            free = null
            return
        }
        settle = scope.launch {
            val spring = PipPhysics.Spring()
            var mx = PipPhysics.Motion(start.x, vx)
            var my = PipPhysics.Motion(start.y, vy)
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val dt = (now - last) / 1e9f
                last = now
                val (tx, ty) = currentBounds.position(c)
                mx = spring.step(mx, tx, dt)
                my = spring.step(my, ty, dt)
                free = Offset(mx.position, my.position)
                if (spring.settled(mx, tx) && spring.settled(my, ty)) break
            }
            free = null
        }
    }

    fun release(vx: Float, vy: Float) {
        val start = free ?: return
        springTo(PipPhysics.snapCorner(start.x, start.y, vx, vy, currentBounds), start, vx, vy)
    }

    // 完成时如果停在下方两个角,先挪到同侧上方,免得盖住刚出来的结果卡片
    LaunchedEffect(pip.mode) {
        if (pip.mode != PipMode.Done) return@LaunchedEffect
        val up = when (corner) {
            Corner.BottomStart -> Corner.TopStart
            Corner.BottomEnd -> Corner.TopEnd
            else -> return@LaunchedEffect
        }
        springTo(up, free ?: currentBounds.position(corner).let { (x, y) -> Offset(x, y) }, 0f, 0f)
    }

    val pos = free ?: bounds.position(corner).let { (x, y) -> Offset(x, y) }
    val outer = RoundedCornerShape(if (pip.expanded) 26.dp else 16.dp)
    val inner = RoundedCornerShape(if (pip.expanded) 22.dp else 13.dp)
    val breath = if (pip.mode == PipMode.NeedsYou && !reduceMotion) {
        rememberInfiniteTransition(label = "breathe").animateFloat(
            1f, 0.55f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "breathe",
        ).value
    } else 1f

    Box(Modifier.offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }) {
        if (pip.mode == PipMode.NeedsYou) {
            // 橙色光晕:比窗口大一圈,模糊后透出边缘(安卓 12 以下不模糊,只是一圈淡橙色)
            Box(
                Modifier.offset((-5).dp, (-5).dp).size(w + 10.dp, h + 10.dp).alpha(breath)
                    .blur(10.dp).background(MikaTheme.colors.warnSolid.copy(alpha = 0.55f), outer),
            )
        }
        Box(
            Modifier.size(w, h)
                .alpha(if (pip.mode == PipMode.Done) 0.85f else 1f)
                .floatShadow(MikaTheme.colors, outer)
                .background(MikaTheme.colors.glass, outer)
                .border(0.5.dp, MikaTheme.colors.glassEdge, outer)
                .pointerInput(Unit) {
                    val tracker = VelocityTracker()
                    // 自己数位移而不用 detectDragGestures:越过触摸阈值时把阈值内的那段也补上,
                    // 窗口始终保持按下时的抓取位置,真正 1:1 跟手
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val origin = free ?: currentBounds.position(corner).let { (x, y) -> Offset(x, y) }
                        var total = Offset.Zero
                        var dragging = false
                        tracker.resetTracking()
                        tracker.addPosition(down.uptimeMillis, origin + down.position)
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            total += change.positionChange()
                            if (!dragging && total.getDistance() > viewConfiguration.touchSlop) {
                                dragging = true
                                settle?.cancel()
                            }
                            if (dragging) {
                                change.consume()
                                free = origin + total
                                tracker.addPosition(change.uptimeMillis, origin + down.position + total)
                            }
                        }
                        if (dragging) tracker.calculateVelocity().let { v -> release(v.x, v.y) }
                    }
                }
                .pointerInput(Unit) { detectTapGestures { if (needsYou) attend() else if (!expanded) expand() } }
                .padding(if (pip.expanded) 4.dp else 3.dp),
        ) {
            Box(Modifier.fillMaxSize().clip(inner).background(MikaTheme.colors.surface)) {
                if (frame != null) {
                    val image = remember(frame) { frame.asImageBitmap() }
                    Image(image, stringResource(R.string.pip_screen), Modifier.fillMaxSize(), alignment = Alignment.TopCenter,
                        contentScale = if (webContent) ContentScale.Fit else ContentScale.Crop)
                }
            }
            if (pip.expanded) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp)
                        .floatShadow(MikaTheme.colors, CircleShape).background(MikaTheme.colors.glass, CircleShape).clip(CircleShape)
                        .clickable(onClickLabel = stringResource(R.string.collapse), onClick = onCollapse),
                    contentAlignment = Alignment.Center,
                ) { Icon(MikaIcons.Close, stringResource(R.string.collapse), Modifier.size(12.dp), tint = MikaTheme.colors.ink) }
            }
        }
        // 边框光画在玻璃框外沿
        val glow = when (pip.mode) {
            PipMode.Running -> MikaTheme.colors.ok to 2.dp
            PipMode.NeedsYou -> MikaTheme.colors.warnSolid.copy(alpha = breath) to 2.dp
            PipMode.Done -> null
        }
        if (glow != null) Box(Modifier.size(w, h).border(glow.second, glow.first, outer))
    }
}
