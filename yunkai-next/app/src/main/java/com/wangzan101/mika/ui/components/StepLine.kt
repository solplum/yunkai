package com.wangzan101.mika.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.theme.MikaColors
import com.wangzan101.mika.ui.theme.MikaTheme

/** 竖线步骤上一个点的状态:完成是灰点,当前是带光圈的蓝点,暂停是橙点,出错是红点。 */
enum class StepState { Done, Current, Paused, Failed }

private fun StepState.color(c: MikaColors) = when (this) {
    StepState.Done -> c.ink3
    StepState.Current -> c.accent
    StepState.Paused -> c.warnSolid
    StepState.Failed -> c.bad
}

private fun StepState.halo(c: MikaColors) = when (this) {
    StepState.Done -> null
    StepState.Current -> c.accentSoft
    StepState.Paused -> c.warnSoft
    StepState.Failed -> c.badSoft
}

/**
 * 竖线步骤里的一步:左边一条 1.5dp 的细线穿过各步,点画在 [dotY] 高度(对齐第一行文字的中线);
 * 第一步的线从点开始、最后一步的线到点为止,步骤之间线是连续的。内容放在右边,可以有多行。
 */
@Composable
fun StepItem(
    state: StepState,
    first: Boolean,
    last: Boolean,
    modifier: Modifier = Modifier,
    dotY: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = MikaTheme.colors
    Row(modifier.height(IntrinsicSize.Min)) {
        Box(
            Modifier.width(GutterWidth).fillMaxHeight().drawBehind {
                val x = size.width / 2
                val y = dotY.toPx()
                val top = if (first) y else 0f
                val bottom = if (last) y else size.height
                drawLine(c.hair, Offset(x, top), Offset(x, bottom), strokeWidth = 1.5.dp.toPx())
                state.halo(c)?.let { drawCircle(it, 7.dp.toPx(), Offset(x, y)) }
                drawCircle(state.color(c), 4.dp.toPx(), Offset(x, y))
            },
        )
        Column(Modifier.weight(1f), content = content)
    }
}

private val GutterWidth = 20.dp
