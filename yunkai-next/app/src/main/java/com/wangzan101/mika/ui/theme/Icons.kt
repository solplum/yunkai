package com.wangzan101.mika.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes

/**
 * 自己画的线条图标:24 视窗、默认显示 22dp、线宽 1.7、圆头圆角。颜色由 Icon 的 tint 给。
 * 都是 object 里的常量,只建一次;个别要在小尺寸下看清的(勾、发送、箭头、关闭)线宽加粗一点。
 */
object MikaIcons {
    private fun stroke(name: String, vararg paths: String, width: Float = MikaSize.IconStroke): ImageVector =
        ImageVector.Builder(name, MikaSize.Icon, MikaSize.Icon, 24f, 24f).apply {
            paths.forEach {
                addPath(
                    addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private fun filled(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, MikaSize.Icon, MikaSize.Icon, 24f, 24f).apply {
            paths.forEach { addPath(addPathNodes(it), fill = SolidColor(Color.Black)) }
        }.build()

    val Menu = stroke("menu", "M4 7 h16 M4 12 h16 M4 17 h10")
    /** 新对话:一支笔。 */
    val New = stroke("new", "M12 20 h8", "M16.5 3.5 a2.1 2.1 0 0 1 3 3 L7 19 l-4 1 l1 -4 z")
    /** 改名:一支斜放的笔,笔头后面一道箍(和「新对话」不同,下面不带横线)。 */
    val Edit = stroke("edit", "M16.5 3.5 a2.1 2.1 0 0 1 3 3 L7 19 l-4 1 l1 -4 z", "M14.5 5.5 l4 4")
    val Back = stroke("back", "M15 5 l-7 7 l7 7")
    val Forward = stroke("forward", "M9 5 l7 7 l-7 7")
    val Chevron = stroke("chevron", "M9 6 l6 6 l-6 6", width = 2f)
    val Plus = stroke("plus", "M12 5 v14 M5 12 h14")
    /** 发送。 */
    val Up = stroke("up", "M12 19 V5 M6 11 l6 -6 l6 6", width = 2.2f)
    val Check = stroke("check", "M5 12.5 l4.5 4.5 L19 7.5", width = 2.2f)
    val Close = stroke("close", "M6 6 l12 12 M18 6 L6 18", width = 2f)
    val Search = stroke("search", "M4 11 a7 7 0 1 0 14 0 a7 7 0 1 0 -14 0", "M20 20 l-4 -4")
    /** 刷新:差一点闭合的圆,右上角一个箭头。 */
    val Refresh = stroke("refresh", "M19.5 12 a7.5 7.5 0 1 1 -2.2 -5.3", "M19.5 4.5 v4 h-4")
    val Trash = stroke("trash", "M4 7 h16 M9 7 V4.5 h6 V7", "M6.5 7 l1 13 h9 l1 -13")
    /** 轻量模型:一片叶子。 */
    val Leaf = stroke("leaf", "M5 19 c0 -8 5 -13 14 -14 c0 9 -5 14 -13 14", "M5 19 l7 -7")
    val Apps = stroke(
        "apps",
        "M5.8 4 h2.9 a1.8 1.8 0 0 1 1.8 1.8 v2.9 a1.8 1.8 0 0 1 -1.8 1.8 h-2.9 a1.8 1.8 0 0 1 -1.8 -1.8 v-2.9 a1.8 1.8 0 0 1 1.8 -1.8 z",
        "M15.3 4 h2.9 a1.8 1.8 0 0 1 1.8 1.8 v2.9 a1.8 1.8 0 0 1 -1.8 1.8 h-2.9 a1.8 1.8 0 0 1 -1.8 -1.8 v-2.9 a1.8 1.8 0 0 1 1.8 -1.8 z",
        "M5.8 13.5 h2.9 a1.8 1.8 0 0 1 1.8 1.8 v2.9 a1.8 1.8 0 0 1 -1.8 1.8 h-2.9 a1.8 1.8 0 0 1 -1.8 -1.8 v-2.9 a1.8 1.8 0 0 1 1.8 -1.8 z",
        "M15.3 13.5 h2.9 a1.8 1.8 0 0 1 1.8 1.8 v2.9 a1.8 1.8 0 0 1 -1.8 1.8 h-2.9 a1.8 1.8 0 0 1 -1.8 -1.8 v-2.9 a1.8 1.8 0 0 1 1.8 -1.8 z",
    )
    val Globe = stroke(
        "globe", "M3 12 a9 9 0 1 0 18 0 a9 9 0 1 0 -18 0",
        "M3 12 h18 M12 3 c2.5 2.6 3.8 5.6 3.8 9 s-1.3 6.4 -3.8 9 c-2.5 -2.6 -3.8 -5.6 -3.8 -9 s1.3 -6.4 3.8 -9 z",
    )
    val Doc = stroke(
        "doc", "M14 3 H7 a2 2 0 0 0 -2 2 v14 a2 2 0 0 0 2 2 h10 a2 2 0 0 0 2 -2 V8 z", "M14 3 v5 h5 M9 13 h6 M9 17 h4",
    )
    /** 柱状图:Token 用量。 */
    val Chart = stroke("chart", "M4 20 h16", "M7 16 v-4 M12 16 V6 M17 16 v-7")
    val Clock = stroke("clock", "M3 12 a9 9 0 1 0 18 0 a9 9 0 1 0 -18 0", "M12 7 v5 l3 2")
    val Alarm = stroke(
        "alarm", "M5 13 a7 7 0 1 0 14 0 a7 7 0 1 0 -14 0", "M12 10 v3 l2 1.5 M5 4 L2.5 6.5 M19 4 l2.5 2.5",
    )
    val Message = stroke("message", "M21 12 a8 8 0 0 1 -11.6 7.1 L4 20 l1 -4.6 A8 8 0 1 1 21 12 z")
    val Heart = stroke(
        "heart", "M12 20.5 s-8 -4.6 -8 -10.4 A4.6 4.6 0 0 1 12 7 a4.6 4.6 0 0 1 8 3.1 c0 5.8 -8 10.4 -8 10.4 z",
    )
    val Gear = stroke(
        "gear", "M9 12 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0",
        "M19.4 15 a1.7 1.7 0 0 0 0.3 1.8 l0.1 0.1 a2 2 0 1 1 -2.8 2.8 l-0.1 -0.1 a1.7 1.7 0 0 0 -1.8 -0.3 " +
            "a1.7 1.7 0 0 0 -1 1.5 V21 a2 2 0 1 1 -4 0 v-0.1 a1.7 1.7 0 0 0 -1.1 -1.6 a1.7 1.7 0 0 0 -1.8 0.3 " +
            "l-0.1 0.1 a2 2 0 1 1 -2.8 -2.8 l0.1 -0.1 a1.7 1.7 0 0 0 0.3 -1.8 a1.7 1.7 0 0 0 -1.5 -1 H3 " +
            "a2 2 0 1 1 0 -4 h0.1 a1.7 1.7 0 0 0 1.6 -1.1 a1.7 1.7 0 0 0 -0.3 -1.8 l-0.1 -0.1 a2 2 0 1 1 2.8 -2.8 " +
            "l0.1 0.1 a1.7 1.7 0 0 0 1.8 0.3 H9 a1.7 1.7 0 0 0 1 -1.5 V3 a2 2 0 1 1 4 0 v0.1 a1.7 1.7 0 0 0 1 1.5 " +
            "a1.7 1.7 0 0 0 1.8 -0.3 l0.1 -0.1 a2 2 0 1 1 2.8 2.8 l-0.1 0.1 a1.7 1.7 0 0 0 -0.3 1.8 V9 " +
            "a1.7 1.7 0 0 0 1.5 1 H21 a2 2 0 1 1 0 4 h-0.1 a1.7 1.7 0 0 0 -1.5 1 z",
    )
    /** 星光:模型。 */
    val Spark = stroke(
        "spark", "M12 3 l1.9 5.6 L19.5 10.5 L13.9 12.4 L12 18 l-1.9 -5.6 L4.5 10.5 l5.6 -1.9 z",
        "M19 17 l0.7 2 l2 0.7 l-2 0.7 L19 22.4 l-0.7 -2 l-2 -0.7 l2 -0.7 z",
    )
    /** 相机:机身加镜头(用系统相机拍照)。 */
    val PhotoCamera = stroke(
        "photo-camera", "M4 8 h3 l2 -2.5 h6 l2 2.5 h3 v11 H4 z", "M8.4 13 a3.6 3.6 0 1 0 7.2 0 a3.6 3.6 0 1 0 -7.2 0",
    )
    /** 照片:相框里一座山、一个太阳。 */
    val Photo = stroke(
        "photo",
        "M6 4.5 h12 a2.5 2.5 0 0 1 2.5 2.5 v10 a2.5 2.5 0 0 1 -2.5 2.5 h-12 a2.5 2.5 0 0 1 -2.5 -2.5 v-10 a2.5 2.5 0 0 1 2.5 -2.5 z",
        "M7.4 10 a1.6 1.6 0 1 0 3.2 0 a1.6 1.6 0 1 0 -3.2 0", "M20.5 16 l-5 -5 l-8 8.5",
    )
    /** 截屏:一块屏,上面一条,中间一个圆(截下此刻的屏幕)。 */
    val Camera = stroke(
        "camera",
        "M6 4 h12 a3 3 0 0 1 3 3 v10 a3 3 0 0 1 -3 3 h-12 a3 3 0 0 1 -3 -3 v-10 a3 3 0 0 1 3 -3 z",
        "M3 9 h18 M8 4 v5",
        "M9.4 14.5 a2.6 2.6 0 1 0 5.2 0 a2.6 2.6 0 1 0 -5.2 0",
    )
    val Phone = stroke(
        "phone",
        "M9 2.5 h6 a2.5 2.5 0 0 1 2.5 2.5 v14 a2.5 2.5 0 0 1 -2.5 2.5 h-6 a2.5 2.5 0 0 1 -2.5 -2.5 v-14 a2.5 2.5 0 0 1 2.5 -2.5 z",
        "M11 18.5 h2",
    )
    val Moon = stroke("moon", "M20 14.5 A8 8 0 0 1 9.5 4 a8 8 0 1 0 10.5 10.5 z")
    val Bell = stroke("bell", "M18 16 V11 a6 6 0 1 0 -12 0 v5 l-2 2 h16 z M10 20.5 a2 2 0 0 0 4 0")
    val Key = stroke("key", "M4 15 a4 4 0 1 0 8 0 a4 4 0 1 0 -8 0", "M10.8 12.2 L20 3 M17 6 l3 3 M14.5 8.5 l2 2")
    val Terminal = stroke(
        "terminal",
        "M5.5 4 h13 a2.5 2.5 0 0 1 2.5 2.5 v11 a2.5 2.5 0 0 1 -2.5 2.5 h-13 a2.5 2.5 0 0 1 -2.5 -2.5 v-11 a2.5 2.5 0 0 1 2.5 -2.5 z",
        "M7 9 l3 3 l-3 3 M13 15 h4",
    )
    /** 路线:记住的操作路线。 */
    val Route = stroke(
        "route", "M3.5 18 a2.5 2.5 0 1 0 5 0 a2.5 2.5 0 1 0 -5 0", "M15.5 6 a2.5 2.5 0 1 0 5 0 a2.5 2.5 0 1 0 -5 0",
        "M8.5 18 H15 a3 3 0 0 0 0 -6 H9 a3 3 0 0 1 0 -6 h6.5",
    )
    /** 扳手:恢复与修复。 */
    val Wrench = stroke(
        "wrench", "M14.7 6.3 a4 4 0 0 0 -5.4 5.4 L3.5 17.5 l3 3 l5.8 -5.8 a4 4 0 0 0 5.4 -5.4 l-2.6 2.6 l-2.4 -0.6 l-0.6 -2.4 z",
    )
    val Code = stroke("code", "M8 7 l-5 5 l5 5 M16 7 l5 5 l-5 5")
    val Info = stroke("info", "M3 12 a9 9 0 1 0 18 0 a9 9 0 1 0 -18 0", "M12 11 v5.5 M12 7.8 v0.2")

    // 关于页:更新记录、反馈问题、聊天(加作者微信)、X、说明文档
    val Lines = stroke("lines", "M9 7 h11 M9 12 h11 M9 17 h11", "M4.5 7 h0.01 M4.5 12 h0.01 M4.5 17 h0.01", width = 2f)
    val Bug = stroke(
        "bug", "M8 9 h8 v6 a4 4 0 0 1 -8 0 z",
        "M9 9 a3 3 0 0 1 6 0 M4 13 h4 M16 13 h4 M5 8 l3 2 M19 8 l-3 2 M5 19 l3 -2 M19 19 l-3 -2",
    )
    val Chats = stroke(
        "chats", "M9.5 5 C5.9 5 3 7.4 3 10.4 c0 1.7 0.9 3.2 2.4 4.2 L5 17 l2.6 -1.4 c0.6 0.2 1.2 0.3 1.9 0.3",
        "M21 14.5 c0 -2.6 -2.5 -4.7 -5.6 -4.7 s-5.6 2.1 -5.6 4.7 s2.5 4.7 5.6 4.7 c0.6 0 1.2 -0.1 1.7 -0.2 L19.5 20 l-0.4 -2 c1.2 -0.9 1.9 -2.1 1.9 -3.5 z",
    )
    val XMark = stroke("x-mark", "M4.5 4.5 l15 15 M19.5 4.5 l-6.2 6.9 M10.7 13.6 L4.5 19.5")
    val Book = stroke("book", "M5 4.5 h6 a2 2 0 0 1 2 2 V20 a1.5 1.5 0 0 0 -1.5 -1.5 H5 z", "M19 4.5 h-6 M19 4.5 v14 h-5.5")
    /** 回形针:附件。 */
    val Paperclip = stroke(
        "paperclip", "M21 11.5 l-8.5 8.5 a5.4 5.4 0 0 1 -7.6 -7.6 L13.4 4 a3.6 3.6 0 0 1 5.1 5.1 L10 17.6 a1.8 1.8 0 0 1 -2.6 -2.6 L15.3 7",
    )
    val Eye = stroke("eye", "M2 12 s3.6 -7 10 -7 s10 7 10 7 s-3.6 7 -10 7 S2 12 2 12 z", "M9 12 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0")
    val Link = stroke(
        "link",
        "M10 14 a4.5 4.5 0 0 0 6.4 0 l3 -3 a4.5 4.5 0 0 0 -6.4 -6.4 L12 5.6 M14 10 a4.5 4.5 0 0 0 -6.4 0 l-3 3 a4.5 4.5 0 0 0 6.4 6.4 l1 -1",
    )
    /** 分享:向上的箭头出盒子。 */
    val Share = stroke("share", "M12 15 V3.5 M7.5 8 L12 3.5 L16.5 8", "M8 11 H6 v9 h12 v-9 h-2")
    /** 用其他应用打开:斜向外的箭头出方框。 */
    val External = stroke("external", "M14 4 h6 v6 M20 4 l-9 9", "M18 14 v5 a1 1 0 0 1 -1 1 H5 a1 1 0 0 1 -1 -1 V7 a1 1 0 0 1 1 -1 h5")
    /** 房子:浏览器的起始页。 */
    val Home = stroke("home", "M4 11 l8 -6.5 l8 6.5 M6.5 9.5 V19 h11 V9.5")
    /** 三个点:更多。 */
    val More = stroke(
        "more", "M4.7 12 a1.3 1.3 0 1 0 2.6 0 a1.3 1.3 0 1 0 -2.6 0", "M10.7 12 a1.3 1.3 0 1 0 2.6 0 a1.3 1.3 0 1 0 -2.6 0",
        "M16.7 12 a1.3 1.3 0 1 0 2.6 0 a1.3 1.3 0 1 0 -2.6 0",
    )
    /** 锁:加密连接的网页。 */
    val Lock = stroke(
        "lock", "M7.5 10.5 h9 a2 2 0 0 1 2 2 v5 a2 2 0 0 1 -2 2 h-9 a2 2 0 0 1 -2 -2 v-5 a2 2 0 0 1 2 -2 z",
        "M8.5 10.5 V8 a3.5 3.5 0 0 1 7 0 v2.5", width = 2.2f,
    )
    val Copy = stroke(
        "copy", "M10 8 h7 a2 2 0 0 1 2 2 v7 a2 2 0 0 1 -2 2 h-7 a2 2 0 0 1 -2 -2 v-7 a2 2 0 0 1 2 -2 z", "M5 15 V6 a1 1 0 0 1 1 -1 h9",
    )
    /** 图钉:置顶了的对话。 */
    val Pin = stroke("pin", "M9 3.5 h6 M10 3.5 v5.5 l-3 3.5 h10 l-3 -3.5 V3.5", "M12 12.5 V20.5")
    /** 星:常用网站。 */
    val Star = stroke("star", "M12 4.5 l2.3 4.7 l5.2 0.8 l-3.8 3.6 l0.9 5.1 L12 16.3 l-4.6 2.4 l0.9 -5.1 L4.5 10 l5.2 -0.8 z")
    /** 显示器:电脑版网页。 */
    val Desktop = stroke("desktop", "M5 5 h14 a1.5 1.5 0 0 1 1.5 1.5 v8 a1.5 1.5 0 0 1 -1.5 1.5 H5 a1.5 1.5 0 0 1 -1.5 -1.5 v-8 A1.5 1.5 0 0 1 5 5 z", "M9 20 h6 M12 16 v4")
    val Shield = stroke("shield", "M12 3 l7.5 3 v5.5 c0 4.6 -3.2 8.4 -7.5 9.5 c-4.3 -1.1 -7.5 -4.9 -7.5 -9.5 V6 z")
    /** 手:无障碍。 */
    val Hand = stroke(
        "hand",
        "M8.4 12 V5.4 a1.44 1.44 0 0 1 2.88 0 V10.8 m0 -5.4 v-1.2 a1.44 1.44 0 0 1 2.88 0 V10.8 m0 -4.2 a1.44 1.44 0 0 1 2.88 0 " +
            "V14.4 a6 6 0 0 1 -6 6 h-0.6 a5.4 5.4 0 0 1 -4.32 -2.16 L4.2 15 a1.56 1.56 0 0 1 2.4 -1.92 L8.4 14.4",
    )
    /** 太阳:屏幕常亮、省电。 */
    val Sun = stroke(
        "sun", "M8.4 12 a3.6 3.6 0 1 0 7.2 0 a3.6 3.6 0 1 0 -7.2 0",
        "M12 3 v1.8 M12 19.2 v1.8 M3 12 H4.8 M19.2 12 h1.8 M5.64 5.64 l1.2 1.2 M17.16 17.16 l1.2 1.2 M5.64 18.36 l1.2 -1.2 M17.16 6.84 l1.2 -1.2",
    )
    val Mute = stroke("mute", "M4.2 9.6 h3.6 l4.8 -3.6 v12 l-4.8 -3.6 h-3.6 z", "M16.8 9.6 l4.8 4.8 M21.6 9.6 l-4.8 4.8")

    /** 实心:停止。 */
    val Stop = filled(
        "stop",
        "M8.28 6 h7.44 a2.28 2.28 0 0 1 2.28 2.28 v7.44 a2.28 2.28 0 0 1 -2.28 2.28 H8.28 A2.28 2.28 0 0 1 6 15.72 V8.28 A2.28 2.28 0 0 1 8.28 6 z",
    )
    /** 实心:感叹号。 */
    val Exclaim = filled(
        "exclaim", "M12 4.5 a1.5 1.5 0 0 1 1.5 1.5 v7.5 a1.5 1.5 0 0 1 -3 0 V6 A1.5 1.5 0 0 1 12 4.5 z",
        "M10.2 18.72 a1.8 1.8 0 1 0 3.6 0 a1.8 1.8 0 1 0 -3.6 0",
    )
}
