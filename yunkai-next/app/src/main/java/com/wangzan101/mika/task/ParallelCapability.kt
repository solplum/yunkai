package com.wangzan101.mika.task

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr

/** 此刻最多能同时跑几个任务;[reason] 说明为什么只能一个(设置页显示),没有限制时为空。 */
data class ParallelLimit(val max: Int, val reason: String?)

/**
 * 按设备能力决定并行几个任务:每块虚拟屏要有独立焦点(安卓 14+);低内存设备不并行;
 * 第二块屏这次运行里建失败过就不再尝试;其余按用户设定的上限,限定在 1–3。
 */
object ParallelCapability {
    const val MIN_SDK = 34
    const val DEFAULT_MAX = 2
    private const val MAX = 3
    val SECOND_DISPLAY_FAILED: String get() = tr(R.string.parallel_second_display_failed)

    fun detect(sdk: Int, lowRam: Boolean, userMax: Int, secondDisplayFailed: Boolean): ParallelLimit = when {
        sdk < MIN_SDK -> ParallelLimit(1, tr(R.string.parallel_needs_14))
        lowRam -> ParallelLimit(1, tr(R.string.parallel_low_ram))
        secondDisplayFailed -> ParallelLimit(1, SECOND_DISPLAY_FAILED)
        else -> ParallelLimit(userMax.coerceIn(1, MAX), null)
    }
}
