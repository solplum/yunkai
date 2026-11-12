package com.wangzan101.mika.capsule

import com.wangzan101.mika.cap.phone.a11y.OverlayArea
import org.junit.Assert.assertEquals
import org.junit.Test

class CapsuleDockTest {
    // 1000×2000 的屏幕,状态栏 80,底部手势条 40;看得见的胶囊离边 30,离上下 20,阴影边 10
    private val area = DockArea.of(OverlayArea(1000, 2000, insetTop = 80, insetBottom = 40), margin = 30, gap = 20, room = 10)

    @Test fun areaAvoidsBarsAndKeepsMargins() {
        assertEquals(DockArea(1000, left = 20, top = 90, right = 980, bottom = 1950, edgeLeft = -10, edgeRight = 1010), area)
    }

    @Test fun fractionAndYRoundTrip() {
        assertEquals(90, CapsuleDocking.y(0f, area, 100))
        assertEquals(1850, CapsuleDocking.y(1f, area, 100))
        assertEquals(970, CapsuleDocking.y(0.5f, area, 100))
        assertEquals(0.5f, CapsuleDocking.fraction(970, area, 100), 0.001f)
        // 超出范围的夹住
        assertEquals(0f, CapsuleDocking.fraction(-500, area, 100))
        assertEquals(1f, CapsuleDocking.fraction(5000, area, 100))
        assertEquals(90, CapsuleDocking.y(-3f, area, 100))
    }

    @Test fun sameFractionAfterRotation() {
        val landscape = DockArea.of(OverlayArea(2000, 1000, insetLeft = 80, insetTop = 40), margin = 30, gap = 20, room = 10)
        val f = CapsuleDocking.fraction(CapsuleDocking.y(0.25f, area, 100), area, 100)
        val y = CapsuleDocking.y(f, landscape, 100)
        // 横屏:上 50、下 990,范围 840 的四分之一
        assertEquals(50 + 210, y)
        // 刘海在左边:左边停靠时让开它
        assertEquals(100, CapsuleDocking.dockedX(DockSide.Left, landscape, 200))
        assertEquals(1780, CapsuleDocking.dockedX(DockSide.Right, landscape, 200))
    }

    @Test fun tallerWindowMovesUpToFit() {
        // 卡片 600 高,从胶囊的高度往下放不下
        assertEquals(1950 - 600, CapsuleDocking.fitY(CapsuleDocking.y(1f, area, 100), area, 600))
        assertEquals(500, CapsuleDocking.fitY(500, area, 600))
        // 比整个范围还高:贴着顶
        assertEquals(90, CapsuleDocking.fitY(500, area, 5000))
    }

    @Test fun dragStopsAtTheScreenEdge() {
        assertEquals(-10, CapsuleDocking.dragX(-300, area, 200))
        assertEquals(1010 - 200, CapsuleDocking.dragX(5000, area, 200))
        assertEquals(400, CapsuleDocking.dragX(400, area, 200))
    }

    @Test fun releaseSnapsToTheNearestSide() {
        assertEquals(CapsuleDock(DockSide.Left, 0f), CapsuleDocking.release(250, 90, 200, 100, area))
        assertEquals(DockSide.Right, CapsuleDocking.release(450, 500, 200, 100, area).side)
        assertEquals(DockSide.Left, CapsuleDocking.release(380, 500, 200, 100, area).side)
        assertEquals(20, CapsuleDocking.dockedX(DockSide.Left, area, 200))
        assertEquals(780, CapsuleDocking.dockedX(DockSide.Right, area, 200))
        assertEquals(20, CapsuleDocking.edgeOffset(DockSide.Right, area))
        assertEquals(20, CapsuleDocking.edgeOffset(DockSide.Left, area))
    }

    @Test fun keyboardPushesTheRangeUp() {
        // 输入法上边在 1200:看得见的部分离它 20
        val up = area.above(1200, gap = 20, room = 10)
        assertEquals(1190, up.bottom)
        assertEquals(1190 - 600, CapsuleDocking.fitY(CapsuleDocking.y(1f, area, 100), up, 600))
        assertEquals(area, area.above(null, 20, 10))
        // 输入法比可摆范围还高:不越过顶
        assertEquals(area.top, area.above(10, 20, 10).bottom)
    }

    // ---------- 卡片 ----------

    /** 最高的卡片(看得见 1160 高)连同上下阴影边的窗口高。 */
    private val full = 1160 + 20

    @Test fun cardCapIsAShareOfTheScreen() {
        // 2000 高的屏幕:最高 58%,放得下就是它(对话卡片就这么高,任务卡片最多这么高)
        assertEquals(1160, CardDocking.maxHeight(2000, area, room = 10))
        // 输入法弹出(上边 1200):收到它上面,中间那段变矮
        val up = area.above(1200, gap = 20, room = 10)
        assertEquals(1190 - 90 - 20, CardDocking.maxHeight(2000, up, room = 10))
        // 横屏:按横屏的高度算
        val landscape = DockArea.of(OverlayArea(2000, 1000, insetLeft = 80, insetTop = 40), margin = 30, gap = 20, room = 10)
        assertEquals(580, CardDocking.maxHeight(1000, landscape, room = 10))
    }

    @Test fun cardTopFollowsItsFraction() {
        // 上边在 90..(1950 - 最高的卡片) 之间按比例摆:最高的卡片正中、顶、底
        assertEquals(90 + 340, CardDocking.y(0.5f, area, area, full, full))
        assertEquals(90, CardDocking.y(0f, area, area, full, full))
        assertEquals(1950 - full, CardDocking.y(1f, area, area, full, full))
        // 转屏后同一个比例
        val landscape = DockArea.of(OverlayArea(2000, 1000, insetLeft = 80, insetTop = 40), margin = 30, gap = 20, room = 10)
        assertEquals(50 + 170, CardDocking.y(0.5f, landscape, landscape, 600, 600))
    }

    @Test fun shorterTaskCardSharesTheTopWithTheChatCard() {
        // 任务卡片按内容只有 500 高:上边和同一比例的对话卡片一样,往下短一截
        for (f in listOf(0f, 0.3f, 0.5f, 1f)) {
            assertEquals(CardDocking.y(f, area, area, full, full), CardDocking.y(f, area, area, full, 500))
        }
        // 以前按固定高卡片记的比例当作上边的比例读:对话卡片摆回原处
        assertEquals(CapsuleDocking.y(0.7f, area, full), CardDocking.y(0.7f, area, area, full, full))
    }

    @Test fun switchingCardsKeepsTheTopAndShiftsUpOnlyWhenItOverflows() {
        val top = CardDocking.y(1f, area, area, full, 500)
        // 任务卡片换成对话卡片:放得下,上边不动,往下长
        assertEquals(top, CardDocking.y(1f, area, area, full, full))
        // 输入法弹出(上边 1200):短的卡片放得下就不动,对话卡片(收矮到 1100 + 阴影边)往上挪到刚好放下
        val up = area.above(1200, gap = 20, room = 10)
        val shortTop = CardDocking.y(0f, area, up, full, 500)
        assertEquals(90, shortTop)
        assertEquals(90 + 204, CardDocking.y(0.3f, area, up, full, 500))
        assertEquals(1190 - 700, CardDocking.y(1f, area, up, full, 700))
        assertEquals(90, CardDocking.y(0.5f, area, up, full, 1100))
    }

    @Test fun cardDragStaysOnScreenAndRemembersTheTopFraction() {
        assertEquals(90, CardDocking.dragY(430, -1000f, area, area, full, full))
        assertEquals(1950 - full, CardDocking.dragY(430, 2000f, area, area, full, full))
        assertEquals(530, CardDocking.dragY(430, 100f, area, area, full, full))
        // 短的任务卡片往下拖到底:上边停在对话卡片也放得下的地方,记下的比例不出范围
        assertEquals(1950 - full, CardDocking.dragY(430, 2000f, area, area, full, 500))
        assertEquals(1f, CardDocking.release(1950 - full, area, full))
        assertEquals(0.5f, CardDocking.release(430, area, full), 0.001f)
        // 按没让输入法的范围记:输入法收起后还在原处,换成别的高度上边也一样
        assertEquals(430, CardDocking.y(CardDocking.release(430, area, full), area, area, full, full))
        assertEquals(430, CardDocking.y(CardDocking.release(430, area, full), area, area, full, 500))
        // 输入法弹出时拖:整张不出它上面
        val up = area.above(1200, gap = 20, room = 10)
        assertEquals(1190 - 500, CardDocking.dragY(430, 2000f, area, up, full, 500))
    }
}
