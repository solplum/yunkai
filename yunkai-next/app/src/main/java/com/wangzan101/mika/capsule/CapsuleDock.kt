package com.wangzan101.mika.capsule

import com.wangzan101.mika.cap.phone.a11y.OverlayArea
import kotlin.math.roundToInt

enum class DockSide { Left, Right }

/** 胶囊停在哪:[side] 贴哪一边,[fraction] 是高度在可摆范围里的比例(0 最上,1 最下),按比例记,转屏后照样摆回原处。 */
data class CapsuleDock(val side: DockSide = DockSide.Right, val fraction: Float = 0f)

/**
 * 胶囊窗口能摆的范围,都是窗口坐标(像素,窗口含四周留给阴影的边):停靠时窗口左边在 [left],或右边在 [right];
 * 上下在 [top]..[bottom] 之间(避开状态栏、刘海和底部手势条)。拖动时左右可以一直拖到 [edgeLeft]..[edgeRight]
 * (看得见的胶囊贴到屏幕边)。
 */
data class DockArea(
    val width: Int, val left: Int, val top: Int, val right: Int, val bottom: Int,
    val edgeLeft: Int, val edgeRight: Int,
) {
    /**
     * 输入法弹出时(上边在 [imeTop],屏幕坐标)可摆的范围收到它上面,看得见的部分离它 [gap];[room] 是阴影边。
     * 没弹出([imeTop] 为 null)时不变。
     */
    fun above(imeTop: Int?, gap: Int, room: Int): DockArea =
        if (imeTop == null) this else copy(bottom = minOf(bottom, imeTop - gap + room).coerceAtLeast(top))

    companion object {
        /**
         * 从屏幕和系统栏算出来:停靠时看得见的胶囊离屏幕左右边 [margin]、离状态栏和底部手势条 [gap];
         * [room] 是窗口里留给阴影的那圈边。
         */
        fun of(area: OverlayArea, margin: Int, gap: Int, room: Int) = DockArea(
            width = area.width,
            left = area.insetLeft + margin - room,
            top = area.insetTop + gap - room,
            right = area.width - area.insetRight - margin + room,
            bottom = area.height - area.insetBottom - gap + room,
            edgeLeft = area.insetLeft - room,
            edgeRight = area.width - area.insetRight + room,
        )
    }
}

/** 拖动、贴边、按比例摆高度的算法(纯函数,像素)。 */
object CapsuleDocking {
    /** 高为 [h] 的窗口按 [fraction] 摆时的上边。 */
    fun y(fraction: Float, area: DockArea, h: Int): Int = area.top + (fraction.coerceIn(0f, 1f) * range(area, h)).roundToInt()

    /** 上边在 [y] 时的高度比例。 */
    fun fraction(y: Int, area: DockArea, h: Int): Float {
        val r = range(area, h)
        return if (r == 0) 0f else ((y - area.top).toFloat() / r).coerceIn(0f, 1f)
    }

    /** 高为 [h] 的窗口(卡片比胶囊高)从 [y] 往下放不下时往上挪,不越过状态栏和底部。 */
    fun fitY(y: Int, area: DockArea, h: Int): Int = y.coerceIn(area.top, maxOf(area.top, area.bottom - h))

    /** 拖动中窗口的左边:最多让看得见的胶囊贴到屏幕边。 */
    fun dragX(x: Int, area: DockArea, w: Int): Int = x.coerceIn(area.edgeLeft, maxOf(area.edgeLeft, area.edgeRight - w))

    /** 停靠在 [side] 时窗口的左边。 */
    fun dockedX(side: DockSide, area: DockArea, w: Int): Int = if (side == DockSide.Left) area.left else area.right - w

    /** 停靠在 [side] 时离那条边的距离(窗口按左边或右边对齐时的 x)。 */
    fun edgeOffset(side: DockSide, area: DockArea): Int = if (side == DockSide.Left) area.left else area.width - area.right

    /**
     * 松手:[x] 是窗口左边,[y] 是窗口上边。中线在屏幕左半边贴左边,否则贴右边;高度按比例记下。
     */
    fun release(x: Int, y: Int, w: Int, h: Int, area: DockArea): CapsuleDock {
        val side = if (x + w / 2 < area.width / 2) DockSide.Left else DockSide.Right
        return CapsuleDock(side, fraction(fitY(y, area, h), area, h))
    }

    private fun range(area: DockArea, h: Int) = maxOf(0, area.bottom - area.top - h)
}

/**
 * 展开的卡片摆在哪、多高(纯函数,像素):卡片很宽,左右居中、只上下拖。对话卡片固定 [maxHeight] 高;任务卡片按内容高,
 * 最多也是 [maxHeight],超过的在里面滚。位置按卡片的上边记:上边在可摆范围里的比例([release]),和胶囊分开记;
 * 上边能摆的范围按最高的卡片(窗口高 [full],即 [maxHeight] 加上下阴影边)算,所以高矮不同的卡片按同一个比例上边对齐,
 * 换卡片时上边不动、往下长或缩;往下放不下(底部手势条、输入法)时往上挪到刚好放下。转屏后按比例摆回。
 */
object CardDocking {
    /** 卡片最高占屏幕高度的比例。 */
    const val SHARE = 0.58f
    /** 没记过时卡片上边的比例:最高的卡片摆在可摆范围的正中。 */
    const val DEFAULT_FRACTION = 0.5f

    /**
     * 看得见的卡片最高多高(对话卡片就是这么高):屏幕高 [screenHeight] 的 [SHARE],但不超过 [area] 里放得下的
     * (上下各让出阴影边 [room]);输入法弹出时 [area] 已收到它上面,卡片跟着变矮,中间那段缩,输入框仍看得见。
     */
    fun maxHeight(screenHeight: Int, area: DockArea, room: Int): Int =
        minOf((screenHeight * SHARE).roundToInt(), area.bottom - area.top - 2 * room).coerceAtLeast(0)

    /**
     * 按比例 [fraction] 摆、高为 [h] 的卡片窗口的上边:先在没让输入法的范围 [base] 里按最高的卡片(窗口高 [full])定上边,
     * 再挪进此刻的范围 [area](往下放不下就往上挪)。
     */
    fun y(fraction: Float, base: DockArea, area: DockArea, full: Int, h: Int): Int =
        CapsuleDocking.fitY(base.top + (fraction.coerceIn(0f, 1f) * topRange(base, full)).roundToInt(), area, h)

    /** 拖着标题栏时窗口的上边:手指移了 [dy],上边不出 [base] 里能摆的范围,整张不出 [area]。 */
    fun dragY(startY: Int, dy: Float, base: DockArea, area: DockArea, full: Int, h: Int): Int =
        CapsuleDocking.fitY(minOf(startY + dy.roundToInt(), base.top + topRange(base, full)), area, h)

    /** 松手时记下的上边比例(按没让输入法的范围 [base] 算,输入法收起后还在原处)。 */
    fun release(y: Int, base: DockArea, full: Int): Float {
        val r = topRange(base, full)
        return if (r == 0) 0f else ((y - base.top).toFloat() / r).coerceIn(0f, 1f)
    }

    /** 上边能挪多远:最高的卡片从顶到底。 */
    private fun topRange(base: DockArea, full: Int) = maxOf(0, base.bottom - base.top - full)
}
