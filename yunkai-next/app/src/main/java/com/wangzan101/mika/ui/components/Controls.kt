package com.wangzan101.mika.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.ui.theme.MikaMotion
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import androidx.compose.runtime.getValue

// ---------- 开关 ----------

/**
 * 开关:44×26,打开时强调蓝。动画 200ms 减速,系统关了动画时直接到位。
 * [onCheckedChange] 为空时只显示(整行可点时用)。
 */
@Composable
fun MikaSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = MikaTheme.colors
    val reduce = MikaTheme.reduceMotion
    val position by animateFloatAsState(if (checked) 1f else 0f, MikaMotion.switch(reduce), label = "switch")
    val track by animateColorAsState(if (checked) c.accent else c.greySoft, MikaMotion.switch(reduce), label = "switch-track")
    val thumb = c.thumb
    val ring = c.floatShadow.copy(alpha = 0.12f)
    Box(
        modifier
            .then(
                if (onCheckedChange != null) Modifier.minimumInteractiveComponentSize()
                    .toggleable(checked, enabled = enabled, role = Role.Switch, interactionSource = null, indication = null, onValueChange = onCheckedChange)
                else Modifier,
            )
            .size(44.dp, 26.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .drawBehind {
                drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
                val r = 11.dp.toPx()
                val inset = 2.dp.toPx()
                val cx = inset + r + (size.width - 2 * (inset + r)) * position
                val center = Offset(cx, size.height / 2)
                drawCircle(ring, r + 0.6.dp.toPx(), center.copy(y = center.y + 0.5.dp.toPx()))
                drawCircle(thumb, r, center)
            },
    )
}

// ---------- 胶囊 ----------

enum class PillTone { Ok, Warn, Neutral, Accent }

/** 状态胶囊:可用、需要你、已停用;强调色的标明「在用」(例如模型在给哪个用途干活)。 */
@Composable
fun StatusPill(text: String, tone: PillTone = PillTone.Neutral, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    val (bg, fg) = when (tone) {
        PillTone.Ok -> c.okSoft to c.ok
        PillTone.Warn -> c.warnSoft to c.warn
        PillTone.Accent -> c.accentSoft to c.accent
        PillTone.Neutral -> c.greySoft to c.ink2
    }
    Text(
        text, modifier.background(bg, MikaShapes.Pill).padding(horizontal = 9.dp, vertical = 3.dp),
        style = PillText, color = fg, maxLines = 1,
    )
}

private val PillText = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)

// ---------- 按钮 ----------

/** 主按钮(强调蓝实底)、浅色次按钮(白底蓝字)、浅填充按钮、文字按钮。 */
enum class ButtonKind { Primary, Secondary, Tonal, Text }

/** 大:页面底部 52;中:卡片里并排 42;小:行内 32 的胶囊。 */
enum class ButtonSize(val height: Dp, val shape: RoundedCornerShape, val text: TextStyle, val padding: Dp) {
    Large(MikaSize.Button, MikaShapes.Button, TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold), 20.dp),
    Medium(42.dp, RoundedCornerShape(12.dp), TextStyle(fontSize = 14.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold), 14.dp),
    Small(32.dp, RoundedCornerShape(16.dp), TextStyle(fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold), 14.dp),
}

/** 按钮。宽度由 [modifier] 定(铺满、按权重分或包住文字)。 */
@Composable
fun MikaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    size: ButtonSize = ButtonSize.Large,
    enabled: Boolean = true,
) {
    val c = MikaTheme.colors
    val shape = size.shape
    val (bg, fg) = when (kind) {
        ButtonKind.Primary -> c.accent to c.onAccent
        ButtonKind.Secondary -> c.surface to c.accent
        ButtonKind.Tonal -> c.fill to c.ink
        ButtonKind.Text -> Color.Transparent to c.accent
    }
    Box(
        modifier
            .then(if (kind == ButtonKind.Secondary) Modifier.cardSurface(c, shape) else Modifier.background(bg, shape))
            .clip(shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f)
            .height(size.height)
            .padding(horizontal = size.padding),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = size.text, color = fg, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------- 分段控件 ----------

/** 分段控件:浅填充底,选中的一段是白色浮起。[tags] 是各段的测试标签。 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tags: List<String>? = null,
) {
    val c = MikaTheme.colors
    Row(modifier.fillMaxWidth().background(c.fill, SegmentOuter).padding(2.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).height(32.dp)
                    .then(
                        if (on) Modifier.shadow(1.dp, SegmentInner, clip = false, ambientColor = c.cardShadow, spotColor = c.cardShadow)
                            .background(c.surface, SegmentInner)
                        else Modifier,
                    )
                    .clip(SegmentInner)
                    .selectable(on, role = Role.Tab) { onSelect(i) }
                    .then(if (tags != null) Modifier.testTag(tags[i]) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = SegmentText, color = if (on) c.ink else c.ink2, maxLines = 1)
            }
        }
    }
}

private val SegmentOuter = RoundedCornerShape(10.dp)
private val SegmentInner = RoundedCornerShape(8.dp)
private val SegmentText = TextStyle(fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)

// ---------- 顶栏 ----------

/**
 * 顶栏上的圆形图标按钮,40dp。[glass] 为真时是浮在内容上的白色玻璃圆。
 * [badge] 为真时右上角一个强调色小圆点(有别的事在进行);[enabled] 为假时变淡、不可点。
 */
@Composable
fun BarIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    glass: Boolean = false,
    enabled: Boolean = true,
    badge: Boolean = false,
) {
    val c = MikaTheme.colors
    Box(
        Modifier.size(MikaSize.IconButton)
            .then(if (glass) Modifier.floatShadow(c, CircleShape).background(c.surface, CircleShape) else Modifier)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(MikaSize.Icon).alpha(if (enabled) 1f else 0.35f), tint = c.ink)
        if (badge) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 7.dp).size(8.dp)
                    .background(c.bg, CircleShape).padding(1.dp).background(c.accent, CircleShape),
            )
        }
    }
}

/**
 * 顶栏:56 高,左右各一个图标按钮位,标题居中,可带一行副标题。
 * 底色默认是页面底色,内容在它下面滚动时不透字。
 */
@Composable
fun TopBar(
    title: String,
    left: ImageVector? = null,
    onLeft: () -> Unit = {},
    leftLabel: String = "",
    /** 左边按钮上的小圆点,见 [BarIconButton]。 */
    leftBadge: Boolean = false,
    right: (@Composable RowScope.() -> Unit)? = null,
    subtitle: String? = null,
    subtitleColor: Color = Color.Unspecified,
    background: Color = MikaTheme.colors.bg,
) {
    val c = MikaTheme.colors
    Box(Modifier.fillMaxWidth().background(background).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(
            Modifier.fillMaxWidth().height(MikaSize.TopBar).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (left != null) BarIconButton(left, leftLabel, onLeft, badge = leftBadge) else Spacer(Modifier.size(MikaSize.IconButton))
            Column(Modifier.weight(1f).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MikaType.Title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        subtitle, style = MikaType.Hint, fontWeight = FontWeight.Medium,
                        color = if (subtitleColor == Color.Unspecified) c.ink3 else subtitleColor, maxLines = 1,
                    )
                }
            }
            if (right != null) Row(verticalAlignment = Alignment.CenterVertically, content = right)
            else Spacer(Modifier.size(MikaSize.IconButton))
        }
    }
}

// ---------- 选择胶囊 ----------

/** 可选的胶囊(服务商、接口类型):34 高,白卡底;选中时深色实底、反色字。[small] 是行内的小号。 */
@Composable
fun SelectChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, small: Boolean = false) {
    val c = MikaTheme.colors
    val shape = if (small) SmallChipShape else MikaShapes.Pill
    Box(
        modifier
            .then(
                when {
                    selected -> Modifier.background(c.ink, shape)
                    small -> Modifier.background(c.fill, shape)
                    else -> Modifier.cardSurface(c, shape)
                },
            )
            .clip(shape)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .height(if (small) 28.dp else 34.dp)
            .padding(horizontal = if (small) 10.dp else 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = if (small) SmallChipText else ChipText, maxLines = 1, softWrap = false,
            color = if (selected) c.bg else c.ink,
        )
    }
}

private val SmallChipShape = RoundedCornerShape(8.dp)
private val ChipText = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
private val SmallChipText = TextStyle(fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)

// ---------- 小标签 ----------

enum class TagTone { Neutral, Accent }

/** 能力小标签(工具调用、看图、64K):11 / 600,圆角 7。 */
@Composable
fun CapabilityTag(text: String, tone: TagTone = TagTone.Neutral) {
    val c = MikaTheme.colors
    val (bg, fg) = if (tone == TagTone.Accent) c.accentSoft to c.accent else c.greySoft to c.ink2
    Text(text, Modifier.background(bg, TagShape).padding(horizontal = 7.dp, vertical = 2.dp), style = TagText, color = fg, maxLines = 1)
}

private val TagShape = RoundedCornerShape(7.dp)
private val TagText = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold)

// ---------- 分页点 ----------

/** 上手流程底部的分页点:当前一步是 18 宽的强调色短条,其余是 6 的灰点。 */
@Composable
fun ProgressDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        repeat(count) { i ->
            Box(Modifier.size(if (i == current) 18.dp else 6.dp, 6.dp).background(if (i == current) c.accent else c.hair, CircleShape))
        }
    }
}
