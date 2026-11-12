package com.wangzan101.mika.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey

/**
 * 界面测试的几个步骤,不依赖前一个测试留下的状态:每个测试的活动是新开的,但上一个测试的窗口(活动、弹窗)
 * 退场需要时间。点之前先等这一个测试要点的东西真的出现在屏上、只有一个,点的就不会是旧窗口里的或还没摆好的。
 */
internal fun ComposeContentTestRule.awaitOne(text: String, inDialog: Boolean = false, timeoutMs: Long = 5_000): SemanticsNodeInteraction {
    val matcher = if (inDialog) hasText(text) and hasAnyAncestor(isDialog()) else hasText(text)
    waitUntil(timeoutMs) { onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
    return onNode(matcher, useUnmergedTree = true)
}

/** 弹窗里的按钮:等弹窗出现再点。 */
internal fun ComposeContentTestRule.clickInDialog(text: String) {
    awaitOne(text, inDialog = true).performClick()
    waitForIdle()
}

/** 懒列表里先按稳定的分组 key 跳转，再把组内目标行移进视口；不会划过中间开关。 */
internal fun ComposeContentTestRule.scrollToItem(key: Any, text: String) {
    onNode(hasScrollAction()).performScrollToKey(key)
    waitForIdle()
    awaitOne(text).performScrollTo()
    waitForIdle()
}

internal fun ComposeContentTestRule.scrollAndClick(key: Any, text: String) {
    scrollToItem(key, text)
    awaitOne(text).performClick()
    waitForIdle()
}

/**
 * 在对话页输入框里写下 [text] 再点发送。发送走按钮自己的点击动作,不按屏幕坐标点:写字时输入框拿到焦点、输入法开始弹起,
 * 输入栏跟着键盘往上走;按坐标点的那一下取的是动画途中的位置,常落在输入栏外面——没点到发送,
 * 还被当成「在输入区外点了一下」收起了键盘,字留在框里,任务没开始。
 */
internal fun ComposeContentTestRule.typeAndSend(text: String) {
    onNode(hasSetTextAction()).performTextInput(text)
    onNodeWithContentDescription("发送").performSemanticsAction(SemanticsActions.OnClick)
    waitForIdle()
}
