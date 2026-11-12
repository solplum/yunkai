package com.wangzan101.mika.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager

/** 一块收键盘区域里登记的输入区:在哪儿、当前有没有焦点。 */
private class KeyboardKeeper {
    val bounds = HashMap<Any, Rect>()
    val focused = HashSet<Any>()
    var area: LayoutCoordinates? = null
    fun contains(at: Offset) = bounds.values.any { it.contains(at) }
}

private val LocalKeyboardKeeper = staticCompositionLocalOf<KeyboardKeeper?> { null }

/**
 * 在输入区外面点一下或拖动,就清掉焦点、收起键盘。手势本身不拦,外面的按钮照常点得到、列表照常能滑。
 * 只在登记过的输入区([keepsKeyboard])有焦点时才动手,平时不影响选中文字之类的操作。
 * 对话框是单独的窗口,里面有输入框的话要再包一层。
 */
@Composable
fun KeyboardDismissArea(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val keeper = remember { KeyboardKeeper() }
    val focus = LocalFocusManager.current
    Box(
        modifier.onGloballyPositioned { keeper.area = it }.pointerInput(keeper) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (keeper.focused.isEmpty()) return@awaitEachGesture
                val at = keeper.area?.takeIf { it.isAttached }?.localToRoot(down.position) ?: return@awaitEachGesture
                if (keeper.contains(at)) return@awaitEachGesture
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        focus.clearFocus()
                        break
                    }
                }
            }
        },
    ) {
        CompositionLocalProvider(LocalKeyboardKeeper provides keeper, content = content)
    }
}

/** 标出一块输入区(输入框,或连同旁边按钮的整条输入栏):在里面点不收键盘。 */
@Composable
fun Modifier.keepsKeyboard(): Modifier {
    val keeper = LocalKeyboardKeeper.current ?: return this
    val key = remember { Any() }
    DisposableEffect(keeper) {
        onDispose { keeper.bounds.remove(key); keeper.focused.remove(key) }
    }
    return this
        .onGloballyPositioned { keeper.bounds[key] = it.boundsInRoot() }
        .onFocusChanged { if (it.hasFocus) keeper.focused.add(key) else keeper.focused.remove(key) }
}
