// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.capsule

import com.wangzan101.mika.ChineseUi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapsuleContentTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val clicks = mutableListOf<String>()

    private fun show(view: CapsuleView.Shown) {
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) {
                CapsuleContent(view, onTap = { clicks += "open" }, onStop = { clicks += "stop" }, onHandBack = { clicks += "back" })
            }
        }
    }

    @Test fun runningIsABallWithRingThatSaysWhatItDoesAndOpensOnTap() {
        show(CapsuleView.Shown(CapsuleKind.Running, "在搜牙膏", count = 1, runKey = "a", conversationId = "c"))
        compose.onNodeWithContentDescription("云开 在干活：在搜牙膏，点开看详情").assertIsDisplayed()
        compose.onNodeWithTag("capsule-ring", useUnmergedTree = true).assertIsDisplayed()
        // 一颗上不写那句话
        compose.onNodeWithText("在搜牙膏").assertDoesNotExist()
        compose.onNodeWithTag("capsule-ball").performClick()
        assertEquals(listOf("open"), clicks)
    }

    @Test fun eachBallStateShowsItsMarks() {
        var view by mutableStateOf(CapsuleView.Shown(CapsuleKind.Running, "在搜", count = 3))
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) { CapsuleContent(view, {}, {}, {}) }
        }
        // 好几件:角上写件数
        compose.onNodeWithTag("capsule-count", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("3", useUnmergedTree = true).assertIsDisplayed()
        // 等条件:灰圈,没有件数
        view = CapsuleView.Shown(CapsuleKind.Held, "先等等：排队中", count = 1)
        compose.onNodeWithTag("capsule-ring", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("capsule-count", useUnmergedTree = true).assertDoesNotExist()
        // 看过的要你处理:还是一颗,橙圈
        view = CapsuleView.Shown(CapsuleKind.NeedConfirm, "要你同意：发送", count = 1, needCount = 1)
        compose.onNodeWithContentDescription("要你同意：发送，点开处理").assertIsDisplayed()
        compose.onNodeWithTag("capsule-ring", useUnmergedTree = true).assertIsDisplayed()
        // 办好了、没办成、已停止:角标,没有圈
        for (kind in listOf(CapsuleKind.Done, CapsuleKind.Failed, CapsuleKind.Cancelled)) {
            view = CapsuleView.Shown(kind, "结果")
            compose.onNodeWithTag("capsule-badge", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("capsule-ring", useUnmergedTree = true).assertDoesNotExist()
        }
        // 空闲:只有图标
        view = CapsuleView.Shown(CapsuleKind.Idle, "")
        compose.onNodeWithContentDescription(CapsuleCopy.IDLE).assertIsDisplayed()
        compose.onNodeWithTag("capsule-badge", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("capsule-ring", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun pillShowsItsLineAndOpensOnTap() {
        var view by mutableStateOf(CapsuleView.Shown(CapsuleKind.NeedTakeover, "需要你：付款", pill = true))
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) { CapsuleContent(view, { clicks += "open" }, {}, {}) }
        }
        for (v in listOf(
            CapsuleView.Shown(CapsuleKind.NeedTakeover, "需要你：付款", pill = true),
            CapsuleView.Shown(CapsuleKind.NeedConfirm, "要你同意：发送", pill = true),
            CapsuleView.Shown(CapsuleKind.NeedAnswer, "Mika 问你：要哪个？", pill = true),
            CapsuleView.Shown(CapsuleKind.Done, "办好了 · 点开看结果", pill = true),
            CapsuleView.Shown(CapsuleKind.Failed, "没办成 · 点开看原因", pill = true),
            CapsuleView.Shown(CapsuleKind.Cancelled, "已停止", pill = true),
            CapsuleView.Shown(CapsuleKind.NeedAnswer, "1 件要你处理 · 共 2 件", count = 2, needCount = 1, pill = true),
        )) {
            view = v
            compose.onNodeWithText(v.text).assertIsDisplayed()
            compose.onNodeWithTag("capsule-ring", useUnmergedTree = true).assertDoesNotExist()
        }
        compose.onNodeWithTag("capsule").performClick()
        assertEquals(listOf("open"), clicks)
    }

    /** 展开、缩回:同一块底换宽度,缩回后是一颗那么宽。 */
    @Test fun pillShrinksBackToBall() {
        var view by mutableStateOf(CapsuleView.Shown(CapsuleKind.Done, "办好了 · 点开看结果", pill = true))
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) { CapsuleContent(view, {}, {}, {}, side = DockSide.Left) }
        }
        val wide = compose.onNodeWithTag("capsule").getBoundsInRoot().width
        view = view.copy(pill = false)
        compose.waitForIdle()
        val ball = compose.onNodeWithTag("capsule-ball").getBoundsInRoot()
        assert(ball.width < wide) { "ball should be narrower: ${ball.width} vs $wide" }
        assertEquals(ball.height.value, ball.width.value, 1f)
        compose.onNodeWithTag("capsule-badge", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun handedOverBarHasStopAndHandBack() {
        show(CapsuleView.Shown(CapsuleKind.HandedOver, CapsuleModel.HANDED_OVER, detail = "付款 · 弄好后点右边", runKey = "a"))
        compose.onNodeWithTag("capsule-bar").assertIsDisplayed()
        compose.onNodeWithText("付款 · 弄好后点右边").assertIsDisplayed()
        compose.onNodeWithText(CapsuleCopy.STOP).performClick()
        compose.onNodeWithText("处理好了").performClick()
        assertEquals(listOf("stop", "back"), clicks)
    }

    /** 同一段([CapsuleView.Shown.widthEpoch] 不变)里换成短句,胶囊不变窄;换了一段才重新定宽。 */
    @Test fun widthOnlyGrowsWithinEpoch() {
        var view by mutableStateOf(CapsuleView.Shown(CapsuleKind.NeedAnswer, "Mika 问你：要三家店里哪一家的", pill = true, widthEpoch = 1))
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) { CapsuleContent(view, {}, {}, {}) }
        }
        val wide = compose.onNodeWithTag("capsule").getBoundsInRoot().width
        view = view.copy(text = "短")
        compose.onNodeWithTag("capsule").assertWidthIsAtLeast(wide)
        view = view.copy(widthEpoch = 2)
        compose.waitForIdle()
        val narrow = compose.onNodeWithTag("capsule").getBoundsInRoot().width
        assert(narrow < wide) { "new epoch should re-fit: $narrow vs $wide" }
    }

    /** 一颗淡下去时外圈和角标照样在;读屏时能「移到屏幕另一边」。 */
    @Test fun fadedBallKeepsMarksAndOffersSwapSide() {
        var swapped = 0
        compose.setContent {
            MikaTheme(dark = false, reduceMotion = true) {
                CapsuleContent(
                    CapsuleView.Shown(CapsuleKind.Running, "在搜", count = 2), {}, {}, {},
                    alpha = CapsuleFade.BALL, onSwapSide = { swapped++ },
                )
            }
        }
        compose.onNodeWithTag("capsule-ring", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("capsule-count", useUnmergedTree = true).assertIsDisplayed()
        val actions = compose.onNodeWithTag("capsule-ball").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { actions.first { it.label == CapsuleCopy.SWAP_SIDE }.action() }
        assertEquals(1, swapped)
    }
}
