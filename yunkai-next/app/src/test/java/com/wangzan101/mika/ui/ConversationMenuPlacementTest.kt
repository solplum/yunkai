package com.wangzan101.mika.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** 侧栏长按菜单的摆放:下面放得下放下面,放不下放上面,都放不下把那一行往上挪。可用范围 100..1000,行高 50,菜单 150,间隔 8。 */
class ConversationMenuPlacementTest {
    private fun place(rowTop: Float) = placeMenu(rowTop, rowHeight = 50f, menuHeight = 150f, gap = 8f, top = 100f, bottom = 1000f)

    @Test fun roomBelowPutsTheMenuBelow() {
        assertEquals(MenuPlacement(rowTop = 300f, menuTop = 358f, above = false), place(300f))
        // 刚好放得下:792 + 50 + 8 + 150 = 1000
        assertEquals(MenuPlacement(792f, 850f, above = false), place(792f))
    }

    @Test fun nearTheBottomPutsTheMenuAbove() {
        assertEquals(MenuPlacement(rowTop = 800f, menuTop = 642f, above = true), place(800f))
        assertEquals(MenuPlacement(940f, 782f, above = true), place(940f))
    }

    @Test fun aRowPartlyOffTheUsableAreaIsMovedIn() {
        // 滚到状态栏底下的一行先挪到可用范围顶上,菜单接在下面
        assertEquals(MenuPlacement(100f, 158f, above = false), place(60f))
        // 被键盘挡住的一行挪到可用范围底边以上,菜单放上面
        assertEquals(MenuPlacement(950f, 792f, above = true), place(990f))
    }

    @Test fun noRoomEitherSideMovesTheRowUp() {
        // 可用范围只剩 100..400(键盘占了大半屏):上下都放不下,行挪到 400-150-8-50 = 192,菜单在下面
        assertEquals(MenuPlacement(192f, 250f, above = false), placeMenu(200f, 50f, 150f, 8f, top = 100f, bottom = 400f))
        // 再小也不越过顶边
        assertEquals(MenuPlacement(100f, 158f, above = false), placeMenu(150f, 50f, 150f, 8f, top = 100f, bottom = 250f))
    }
}
