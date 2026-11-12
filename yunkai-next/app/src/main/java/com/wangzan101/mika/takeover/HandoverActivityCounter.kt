package com.wangzan101.mika.takeover

import android.view.Display
import android.view.accessibility.AccessibilityEvent

/** 交回那一刻用户在主屏上做过的事:操作次数和停在哪个页面(取不到为 null)。 */
data class HandoverActivity(val inputCount: Int, val stoppedAt: String?)

/**
 * 接管期间数用户在主屏上对 [packageName] 的点击、输入、滚动次数;一次滑动会连着发很多滚动事件,
 * 间隔不到 [MERGE_MS] 的算同一次;同一个输入框连着的文字变化同理。一串连发最长算 [BURST_CAP_MS],
 * 超过的部分另算一次,免得长时间滑动或打字只记 1 次。顺带记下该包最近一次切到的 Activity 简名,取不到页面标题时用。
 * 事件来自无障碍回调所在的协程,读数来自交回的协程,所以加锁。
 */
class HandoverActivityCounter(val packageName: String) {
    private var count = 0
    private var scrollBurst: Burst? = null
    private var typingBurst: Burst? = null
    private var typingSource: Any? = null
    private var activity: String? = null

    val inputCount: Int @Synchronized get() = count
    val lastActivity: String? @Synchronized get() = activity

    @Synchronized
    fun onEvent(displayId: Int, eventPackage: CharSequence?, eventType: Int, className: CharSequence?, timeMs: Long, source: Any? = null) {
        if (displayId != Display.DEFAULT_DISPLAY || eventPackage?.toString() != packageName) return
        when (eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> count++
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // 换了输入框就是新的一次
                if (typingSource != source) typingBurst = null
                typingSource = source
                typingBurst = advance(typingBurst, timeMs)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> scrollBurst = advance(scrollBurst, timeMs)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                className?.toString()?.substringAfterLast('.')?.takeIf { it.isNotEmpty() }?.let { activity = it }
        }
    }

    private class Burst(val startAt: Long, var lastAt: Long)

    private fun advance(burst: Burst?, timeMs: Long): Burst {
        if (burst != null && timeMs - burst.lastAt < MERGE_MS && timeMs - burst.startAt < BURST_CAP_MS) {
            burst.lastAt = timeMs
            return burst
        }
        count++
        return Burst(timeMs, timeMs)
    }

    companion object {
        const val MERGE_MS = 1_000L
        const val BURST_CAP_MS = 5_000L
    }
}

/**
 * 「停在哪」:窗口标题常常只是 App 名,这时用页面(Activity)简名,两样都有且不同才拼在一起;
 * 标题取不到就用页面简名。
 */
fun stoppedAt(title: String?, appLabel: String?, activity: String?): String? {
    val t = title?.takeIf { it.isNotBlank() }
    if (t == null || t == appLabel) return activity ?: t
    return t
}
