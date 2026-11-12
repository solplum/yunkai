package com.wangzan101.mika.capsule

/**
 * 胶囊上的单击和双击(纯逻辑,时钟由调用方给):单击展开卡片,双击把此刻的屏幕发出去。
 * 按下一下([tap])先不算单击:[timeoutMs] 内又来一下就是双击;到时还没有第二下([settle])才是单击。
 */
class DoubleTap(val timeoutMs: Long) {
    enum class Tap {
        /** 第一下:等 [timeoutMs] 再 [settle]。 */
        First,
        Double,
    }

    private var firstAt: Long? = null

    fun tap(now: Long): Tap {
        val first = firstAt
        return if (first != null && now - first <= timeoutMs) {
            firstAt = null
            Tap.Double
        } else {
            firstAt = now
            Tap.First
        }
    }

    /** 第一下之后到时了:还在等第二下就是单击(返回 true,只返回一次);已经成了双击或还没到时返回 false。 */
    fun settle(now: Long): Boolean {
        val first = firstAt ?: return false
        if (now - first < timeoutMs) return false
        firstAt = null
        return true
    }

    /** 不再等(胶囊收起、换成别的样子)。 */
    fun reset() {
        firstAt = null
    }
}
