package com.wangzan101.mika.cap.web

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * 网页标签的真实输入:触摸经 dispatchTouchEvent、文字经输入连接 commitText、按键经 dispatchKeyEvent,
 * 页面收到的都是可信事件(isTrusted),和人手操作一样。不用脚本点击或脚本赋值代替。
 * 输入连接直接拿 WebView 的,不经系统输入法:主屏上不会弹键盘。坐标是 WebView 视图像素。
 */
class WebInput(private val tab: WebTab) {
    private val view get() = tab.view

    suspend fun tap(xPx: Int, yPx: Int) {
        val down = SystemClock.uptimeMillis()
        touch(down, down, MotionEvent.ACTION_DOWN, xPx.toFloat(), yPx.toFloat())
        delay(TAP_HOLD_MS)
        touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, xPx.toFloat(), yPx.toFloat())
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300) {
        val steps = max(2, (durationMs / MOVE_INTERVAL_MS).toInt())
        val down = SystemClock.uptimeMillis()
        touch(down, down, MotionEvent.ACTION_DOWN, x1.toFloat(), y1.toFloat())
        for (i in 1..steps) {
            delay(durationMs / steps)
            val f = i.toFloat() / steps
            touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * f, y1 + (y2 - y1) * f)
        }
        touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x2.toFloat(), y2.toFloat())
    }

    /** 把整段文字交给当前获得焦点的网页输入框;拿不到输入连接(没有可编辑的焦点)返回 false。 */
    suspend fun commitText(text: String): Boolean = withConnection { it.commitText(text, 1) }

    suspend fun key(keyCode: Int) = key(keyCode, 0)

    /** 全选(Ctrl+A)后删除;有的编辑器不认组合键,再用输入连接全选并清掉。 */
    suspend fun selectAllAndDelete() {
        key(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        key(KeyEvent.KEYCODE_DEL, 0)
    }

    /** 输入连接里全选再写空串:组合键没清干净时的退路。 */
    suspend fun selectAllAndCommitEmpty(): Boolean = withConnection {
        it.performContextMenuAction(android.R.id.selectAll)
        it.commitText("", 1)
    }

    /** [meta] 是 KeyEvent 的修饰状态(META_CTRL_ON 一类),组合键用。 */
    suspend fun key(keyCode: Int, meta: Int) {
        val down = SystemClock.uptimeMillis()
        withContext(Dispatchers.Main) {
            view.dispatchKeyEvent(keyEvent(down, down, KeyEvent.ACTION_DOWN, keyCode, meta))
            view.dispatchKeyEvent(keyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, meta))
        }
    }

    private fun keyEvent(down: Long, at: Long, action: Int, code: Int, meta: Int) =
        KeyEvent(down, at, action, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)

    // 标明是手指:不设工具类型时页面看到的 pointerType 是空串,真手指是 touch
    private suspend fun touch(down: Long, at: Long, action: Int, x: Float, y: Float) = withContext(Dispatchers.Main) {
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
        val e = MotionEvent.obtain(down, at, action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try {
            view.dispatchTouchEvent(e)
        } finally {
            e.recycle()
        }
    }

    // 浏览器内核的输入连接有自己的线程:有 handler 就到那个线程上调,否则在主线程调
    private suspend fun withConnection(block: (InputConnection) -> Boolean): Boolean {
        val ic = withContext(Dispatchers.Main) {
            if (!view.hasFocus()) view.requestFocus()
            view.onCreateInputConnection(EditorInfo())
        } ?: return false
        val handler = ic.handler
        if (handler == null || handler.looper == Looper.getMainLooper()) return withContext(Dispatchers.Main) { block(ic) }
        return suspendCancellableCoroutine { c -> handler.post { c.resume(runCatching { block(ic) }.getOrDefault(false)) } }
    }

    private companion object {
        const val TAP_HOLD_MS = 50L
        const val MOVE_INTERVAL_MS = 16L
    }
}
