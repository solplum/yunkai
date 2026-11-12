package com.wangzan101.mika.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow

/**
 * 画中画小窗的手势物理,单位是像素和秒,与界面无关以便测试。
 * 松手时按速度投射落点(像列表惯性滚动减速到停),再找最近的角;
 * 归位用临界阻尼弹簧(阻尼比 1、响应 0.4 秒):不回弹,并能从当前速度接着走,随时可打断。
 */
object PipPhysics {
    enum class Corner { TopStart, TopEnd, BottomStart, BottomEnd }

    /**
     * 小窗可停的区域:[width]×[height] 是整块画面,[pipWidth]×[pipHeight] 是小窗当前大小;
     * [top]/[bottom] 是要让开的顶栏和输入框高度,[margin] 是离边的距离。
     */
    data class Bounds(
        val width: Float,
        val height: Float,
        val pipWidth: Float,
        val pipHeight: Float,
        val margin: Float,
        val top: Float,
        val bottom: Float,
    ) {
        /** 地方不够(窗口太窄或太矮,比如键盘弹出)时宁可靠左上,也不钻到顶栏下面。 */
        fun position(c: Corner): Pair<Float, Float> {
            val left = margin
            val right = maxOf(left, width - pipWidth - margin)
            val upper = top + margin
            val lower = maxOf(upper, height - pipHeight - bottom - margin)
            return when (c) {
                Corner.TopStart -> left to upper
                Corner.TopEnd -> right to upper
                Corner.BottomStart -> left to lower
                Corner.BottomEnd -> right to lower
            }
        }
    }

    private const val DECELERATION = 0.998f

    /** 以 [velocity](像素/秒)甩出后,按每毫秒衰减到 0.998 倍的减速,最终还能滑多远。 */
    fun project(velocity: Float): Float = (velocity / 1000f) * DECELERATION / (1f - DECELERATION)

    /** 从 ([x],[y]) 以速度 ([vx],[vy]) 松手后应吸附的角。 */
    fun snapCorner(x: Float, y: Float, vx: Float, vy: Float, bounds: Bounds): Corner {
        val px = x + project(vx)
        val py = y + project(vy)
        return Corner.entries.minBy { c ->
            val (cx, cy) = bounds.position(c)
            hypot(cx - px, cy - py)
        }
    }

    /** 一维弹簧的状态:位置与速度。 */
    data class Motion(val position: Float, val velocity: Float)

    class Spring(response: Float = 0.4f, dampingRatio: Float = 1f) {
        private val stiffness = (2 * PI / response).pow(2).toFloat()
        private val damping = (4 * PI * dampingRatio / response).toFloat()

        /** 前进 [dt] 秒(半隐式欧拉;单帧最长按 32 毫秒算,掉帧时不至于发散)。 */
        fun step(m: Motion, target: Float, dt: Float): Motion {
            val t = dt.coerceIn(0f, MAX_DT)
            val v = m.velocity + (-stiffness * (m.position - target) - damping * m.velocity) * t
            return Motion(m.position + v * t, v)
        }

        fun settled(m: Motion, target: Float) = abs(m.position - target) < REST_DISTANCE && abs(m.velocity) < REST_VELOCITY
    }

    private const val MAX_DT = 0.032f
    private const val REST_DISTANCE = 0.5f
    private const val REST_VELOCITY = 5f
}
