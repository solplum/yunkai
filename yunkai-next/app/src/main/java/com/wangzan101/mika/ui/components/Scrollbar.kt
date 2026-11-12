package com.wangzan101.mika.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.delay

/**
 * 给竖向的懒加载列表画一条细滚动条:贴在右边,长短反映看得见的部分占整个列表的多少,位置反映滚到哪。
 * 列表放得下时不画;滚动时和内容刚变化时出现,停下来一会儿后淡出。画在内容上面,不占布局。
 * 总长按已显示那几项的平均高度估算,各项高度差得不大时足够准。
 */
@Composable
fun Modifier.lazyListScrollbar(
    state: LazyListState,
    color: Color = MikaTheme.colors.ink3,
    width: Dp = 3.dp,
    edge: Dp = 2.dp,
): Modifier {
    val scrolling = state.isScrollInProgress
    val count = state.layoutInfo.totalItemsCount
    var shown by remember { mutableStateOf(true) }
    LaunchedEffect(scrolling, count) {
        if (scrolling) {
            shown = true
        } else {
            delay(IDLE_MS)
            shown = false
        }
    }
    val alpha by animateFloatAsState(if (shown) THUMB_ALPHA else 0f, tween(if (shown) 120 else 400), label = "scrollbar")
    return drawWithContent {
        drawContent()
        if (alpha <= 0f) return@drawWithContent
        val info = state.layoutInfo
        val items = info.visibleItemsInfo
        if (items.isEmpty()) return@drawWithContent
        val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
        val average = items.sumOf { it.size }.toFloat() / items.size
        val total = average * info.totalItemsCount
        if (viewport <= 0f || total <= viewport + 1f) return@drawWithContent
        val w = width.toPx()
        val thumb = (viewport * viewport / total).coerceAtLeast(MIN_THUMB.toPx()).coerceAtMost(viewport)
        val scrolled = state.firstVisibleItemIndex * average + state.firstVisibleItemScrollOffset
        val y = (scrolled / (total - viewport)).coerceIn(0f, 1f) * (viewport - thumb)
        drawRoundRect(
            color, Offset(size.width - w - edge.toPx(), y), Size(w, thumb), CornerRadius(w / 2), alpha = alpha,
        )
    }
}

private const val IDLE_MS = 900L
private const val THUMB_ALPHA = 0.55f
private val MIN_THUMB = 24.dp
