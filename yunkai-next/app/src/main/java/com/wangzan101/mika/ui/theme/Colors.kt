package com.wangzan101.mika.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 界面用到的全部颜色。页面一律从 [MikaTheme.colors] 取,不写死色值。
 * 实色只用在强调(主按钮、选中、开关、用户消息)和状态(成功、需要你、出错);图标块一律中性。
 */
@Immutable
class MikaColors(
    /** 页面背景。 */
    val bg: Color,
    /** 卡片、分组。 */
    val surface: Color,
    /** 浅填充:次按钮、分段控件底、代码块底。 */
    val fill: Color,
    /** 文字三级:正文、次要、辅助。 */
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    /** 分隔线。 */
    val hair: Color,
    /** 卡片描边。 */
    val edge: Color,
    val accent: Color,
    val accentSoft: Color,
    /** 强调色上的文字和图标。 */
    val onAccent: Color,
    val ok: Color,
    val okSoft: Color,
    /** 「需要你」:[warn] 用于文字,[warnSolid] 用于圆点、描边。 */
    val warn: Color,
    val warnSolid: Color,
    val warnSoft: Color,
    val bad: Color,
    val badSoft: Color,
    /** 图标块的底。 */
    val neutralSoft: Color,
    /** Mika 回答的底:比页面背景深一点点的灰,和用户的蓝色气泡区分开。 */
    val answer: Color,
    /** 灰色胶囊、关着的开关。 */
    val greySoft: Color,
    /** 悬浮玻璃(输入栏、浮在内容上的按钮)的底和描边。 */
    val glass: Color,
    val glassEdge: Color,
    /** 卡片阴影;深色下卡片只靠描边,为透明。 */
    val cardShadow: Color,
    /** 悬浮元素的阴影。 */
    val floatShadow: Color,
    /** 开关的圆钮。 */
    val thumb: Color,
    /**
     * 图表里分类的颜色(例如每家服务商一种),按固定的先后取;超出的归到 [seriesOther]。
     * 只给图形上色,文字一律用 ink 三级。
     */
    val series: List<Color>,
    val seriesOther: Color,
    /** 图表的网格线。 */
    val grid: Color,
    val isDark: Boolean,
)

val LightColors = MikaColors(
    bg = Color(0xFFF7F7F8),
    surface = Color(0xFFFFFFFF),
    fill = Color(0x0A000000),
    ink = Color(0xFF1C1C1E),
    ink2 = Color(0xFF5F6068),
    ink3 = Color(0xFF9A9BA3),
    hair = Color(0x12000000),
    edge = Color(0x0D000000),
    accent = Color(0xFF0A7CFF),
    accentSoft = Color(0x1A0A7CFF),
    onAccent = Color(0xFFFFFFFF),
    ok = Color(0xFF1F9D55),
    okSoft = Color(0x1F1F9D55),
    warn = Color(0xFFB86E00),
    warnSolid = Color(0xFFFF9F0A),
    warnSoft = Color(0x1FFF9F0A),
    bad = Color(0xFFE5483D),
    badSoft = Color(0x1AE5483D),
    neutralSoft = Color(0x0B000000),
    answer = Color(0x09000000),
    greySoft = Color(0x1F767680),
    glass = Color(0xB8FFFFFF),
    glassEdge = Color(0x0F000000),
    cardShadow = Color(0xFF000000),
    floatShadow = Color(0xFF000000),
    thumb = Color(0xFFFFFFFF),
    series = listOf(Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A)),
    seriesOther = Color(0xFFB4B5BC),
    grid = Color(0x0F000000),
    isDark = false,
)

val DarkColors = MikaColors(
    bg = Color(0xFF0E0E10),
    surface = Color(0xFF1A1A1D),
    fill = Color(0x0FFFFFFF),
    ink = Color(0xFFF2F2F4),
    ink2 = Color(0xFFA4A5AD),
    ink3 = Color(0xFF6C6D75),
    hair = Color(0x14FFFFFF),
    edge = Color(0x0FFFFFFF),
    accent = Color(0xFF3D97FF),
    accentSoft = Color(0x293D97FF),
    onAccent = Color(0xFFFFFFFF),
    ok = Color(0xFF3CCF7A),
    okSoft = Color(0x243CCF7A),
    warn = Color(0xFFFFB340),
    warnSolid = Color(0xFFFF9F0A),
    warnSoft = Color(0x29FF9F0A),
    bad = Color(0xFFFF6961),
    badSoft = Color(0x24FF6961),
    neutralSoft = Color(0x12FFFFFF),
    answer = Color(0x0DFFFFFF),
    greySoft = Color(0x38767680),
    glass = Color(0xB81A1A1D),
    glassEdge = Color(0x14FFFFFF),
    cardShadow = Color.Transparent,
    floatShadow = Color(0xFF000000),
    thumb = Color(0xFFFFFFFF),
    series = listOf(Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70)),
    seriesOther = Color(0xFF5A5B62),
    grid = Color(0x12FFFFFF),
    isDark = true,
)

/** 没有包在 [MikaTheme] 里时(比如界面测试)按浅色。 */
val LocalMikaColors = staticCompositionLocalOf { LightColors }
