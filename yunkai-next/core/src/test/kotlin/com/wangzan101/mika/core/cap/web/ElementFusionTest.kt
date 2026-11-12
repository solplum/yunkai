package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.cap.web.ElementFusion.AxNode
import com.wangzan101.mika.core.cap.web.ElementFusion.Box
import com.wangzan101.mika.core.cap.web.ElementFusion.Frame
import com.wangzan101.mika.core.cap.web.ElementFusion.Input
import com.wangzan101.mika.core.cap.web.ElementFusion.Origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import com.wangzan101.mika.core.uistate.WebInfo

class ElementFusionTest {
    private val info = WebInfo("https://example.com/", "示例", "t1", 200)
    // 视图 800x1600,页面 400x800 CSS 像素:倍数 2
    private val w = 800
    private val h = 1600
    private val scale = 2.0

    private fun page(vararg els: String): List<UiElement> =
        PageTree.parse("""{"els":[${els.joinToString(",")}],"vw":400,"vh":800,"dpr":2,"trunc":false,"mut":0}""", w, h, "w1-1", info).elements

    private fun el(r: String, n: String, x: Int, y: Int, ww: Int = 100, hh: Int = 30, c: Int = 0, e: Int = 0) =
        """{"r":"$r","n":"$n","x":$x,"y":$y,"w":$ww,"h":$hh,"c":$c,"e":$e,"s":0}"""

    private var order = 0
    private fun ax(
        id: String, backendId: Int?, role: String, name: String = "", parent: String? = "root", frame: String = MAIN,
        ignored: Boolean = false, states: Set<String> = emptySet(),
    ) = AxNode(id, parent, frame, order++, backendId, role, name, ignored, states)

    private fun root(frame: String = MAIN, id: String = "root") = ax(id, null, "RootWebArea", parent = null, frame = frame)

    private fun input(
        page: List<UiElement>, ids: List<Int?>, ax: List<AxNode> = emptyList(), owners: Map<Int, String> = emptyMap(),
        frames: List<Frame> = listOf(Frame(MAIN, null)), boxes: Map<Int, Box> = emptyMap(), onTop: Map<Int, Boolean> = emptyMap(),
        listeners: Set<Int> = emptySet(), budget: Int = ElementFusion.DEFAULT_BUDGET,
    ) = Input(page, ids, owners, frames, ax, boxes, onTop, listeners, scale, w, h, budget)

    private fun near(expected: Int, actual: Int, tol: Int = 3) = assertTrue("expected ~$expected got $actual", abs(expected - actual) <= tol)

    @Test fun withoutDevtoolsDataTheListIsUnchanged() {
        val p = page(el("button", "搜索", 10, 10, c = 1), el("text", "正文", 10, 60))
        val r = ElementFusion.fuse(input(p, listOf(null, null)))
        assertEquals(p, r.elements)
        assertEquals(mapOf("@e1" to "@e1", "@e2" to "@e2"), r.pageRefs)
        assertTrue(r.backendIds.isEmpty())
        assertEquals(0, r.omitted)
        assertEquals(Origin.PAGE, r.origins["@e1"])
    }

    @Test fun nameRoleAndStatesComeFromAxWhenBothKnowTheElement() {
        val p = page(el("button", "", 10, 10, c = 1), el("link", "首页", 10, 60, c = 1), el("button", "提交", 10, 110, c = 1))
        val a = listOf(
            root(),
            ax("1", 11, "button", "关闭对话框", states = setOf("focused")),
            ax("2", 12, "link", "  回到\n首页  "),
            ax("3", 13, "button", "提交", states = setOf("disabled")),
        )
        val r = ElementFusion.fuse(input(p, listOf(11, 12, 13), a))
        val (close, home, submit) = r.elements
        assertEquals("关闭对话框", close.name)
        assertTrue(close.focused)
        assertEquals("回到 首页", home.name)
        assertFalse("AX 说禁用", submit.clickable)
        // 位置仍是页面脚本的
        assertEquals(p[0].centerX, close.centerX); assertEquals(p[0].bounds, close.bounds)
        assertEquals(Origin.BOTH, r.origins["@e1"])
        assertEquals(mapOf("@e1" to 11, "@e2" to 12, "@e3" to 13), r.backendIds)
    }

    @Test fun fieldsKeepThePageNameThatCarriesTheValue() {
        val p = page(el("textbox", "邮箱：a@b.c", 10, 10, e = 1), el("textbox", "已填 6 位", 10, 60, e = 1), el("textbox", "", 10, 110, e = 1))
        val a = listOf(root(), ax("1", 1, "textbox", "邮箱"), ax("2", 2, "textbox", "密码"), ax("3", 3, "searchbox", "搜索站内"))
        val r = ElementFusion.fuse(input(p, listOf(1, 2, 3), a))
        assertEquals(listOf("邮箱：a@b.c", "已填 6 位", "搜索站内"), r.elements.map { it.name })
        assertEquals(listOf("textbox", "textbox", "textbox"), r.elements.map { it.role })
        assertTrue(r.elements.all { it.editable })
    }

    @Test fun readonlyFromAxStopsEditing() {
        val p = page(el("textbox", "日期", 10, 10, e = 1))
        val r = ElementFusion.fuse(input(p, listOf(1), listOf(root(), ax("1", 1, "textbox", "日期", states = setOf("readonly")))))
        assertFalse(r.elements.single().editable)
    }

    @Test fun ignoredAxNodesAreNotTrusted() {
        val p = page(el("button", "菜单", 10, 10, c = 1))
        val a = listOf(root(), ax("1", 5, "generic", "别的名字", ignored = true, states = setOf("disabled")))
        val e = ElementFusion.fuse(input(p, listOf(5), a)).elements.single()
        assertEquals("菜单", e.name)
        assertEquals("button", e.role)
        assertTrue(e.clickable)
    }

    @Test fun conflictingRoles() {
        val p = page(
            el("button", "监听器 div", 10, 10, c = 1),
            el("file", "选择文件", 10, 60, c = 1),
            el("button", "设置", 10, 110, c = 1),
            el("text", "第一章", 10, 160),
            el("image", "头像", 10, 210),
            el("frame", "内嵌页面:x.test", 10, 260),
        )
        val a = listOf(
            root(),
            ax("1", 1, "generic"),
            ax("2", 2, "button", "选择文件"),
            ax("3", 3, "menuitem", "设置"),
            ax("4", 4, "heading", "第一章"),
            ax("5", 5, "link", "头像"),
            ax("6", 6, "Iframe"),
        )
        val r = ElementFusion.fuse(input(p, listOf(1, 2, 3, 4, 5, 6), a))
        assertEquals(listOf("button", "file", "menuitem", "heading", "link", "frame"), r.elements.map { it.role })
        assertTrue("变成链接就可点", r.elements[4].clickable)
        assertEquals("内嵌页面:x.test", r.elements[5].name)
    }

    @Test fun crossOriginFrameElementsAreAddedAfterThePlaceholder() {
        val p = page(el("text", "上面", 10, 10), el("frame", "内嵌页面:pay.test", 20, 100, 300, 80), el("text", "下面", 10, 200))
        val a = listOf(
            root(), ax("t1", 1, "StaticText", "上面"), ax("f", 2, "Iframe"), ax("t2", 3, "StaticText", "下面"),
            root(XF, "xroot"),
            ax("xb", 79, "button", "CrossChild", parent = "xroot", frame = XF),
            ax("xt", 80, "StaticText", "CrossChild", parent = "xb", frame = XF),
            ax("xh", 81, "button", "Hidden", parent = "xroot", frame = XF, ignored = true),
            ax("xx", 82, "generic", "", parent = "xroot", frame = XF),
        )
        val inp = input(
            p, listOf(1, 2, 3), a, owners = mapOf(2 to XF), frames = listOf(Frame(MAIN, null), Frame(XF, MAIN)),
            boxes = mapOf(79 to Box(30.0, 110.0, 150.0, 150.0)), onTop = mapOf(79 to true),
        )
        val c = ElementFusion.candidates(inp)
        assertEquals(listOf(79), c.map { it.node.backendId })
        assertEquals(1, c.single().placeholder)
        assertEquals(2, c.single().ownerId)
        val r = ElementFusion.fuse(inp)
        assertEquals(listOf("上面", "内嵌页面:pay.test", "CrossChild", "下面"), r.elements.map { it.name })
        assertEquals((1..4).map { "@e$it" }, r.elements.map { it.ref })
        val b = r.elements[2]
        assertEquals("button", b.role)
        assertTrue(b.clickable)
        near(180, b.centerX); near(260, b.centerY)
        assertEquals(Origin.AX, r.origins["@e3"])
        assertNull("页面脚本的表里没有它", r.pageRefs["@e3"])
        assertEquals("@e3", r.pageRefs["@e4"])
        assertEquals(79, r.backendIds["@e3"])
        assertEquals(mapOf("@e3" to 2), r.frameOwners)
    }

    @Test fun crossOriginBoxesAreClippedToThePlaceholder() {
        val p = page(el("frame", "内嵌页面:x.test", 0, 100, 200, 100))
        val a = listOf(
            root(), ax("f", 2, "Iframe"), root(XF, "xr"),
            ax("half", 10, "link", "半截", parent = "xr", frame = XF),
            ax("out", 11, "link", "框外", parent = "xr", frame = XF),
        )
        val r = ElementFusion.fuse(
            input(
                p, listOf(2), a, owners = mapOf(2 to XF), frames = listOf(Frame(MAIN, null), Frame(XF, MAIN)),
                boxes = mapOf(10 to Box(150.0, 180.0, 250.0, 220.0), 11 to Box(0.0, 300.0, 50.0, 320.0)),
            ),
        )
        assertEquals(listOf("内嵌页面:x.test", "半截"), r.elements.map { it.name })
        val half = r.elements[1]
        // 可见部分 CSS (150..200, 180..200) → 视图 (300..400, 360..400)
        near(350, half.centerX); near(380, half.centerY)
    }

    @Test fun crossOriginElementCoveredInsideTheFrameIsDropped() {
        val p = page(el("frame", "内嵌页面:x.test", 0, 100, 200, 100))
        val a = listOf(root(), ax("f", 2, "Iframe"), root(XF, "xr"), ax("b", 10, "button", "付款", parent = "xr", frame = XF))
        val r = ElementFusion.fuse(
            input(p, listOf(2), a, owners = mapOf(2 to XF), frames = listOf(Frame(MAIN, null), Frame(XF, MAIN)),
                boxes = mapOf(10 to Box(10.0, 110.0, 60.0, 130.0)), onTop = mapOf(10 to false)),
        )
        assertEquals(1, r.elements.size)
    }

    @Test fun framesNestedInsideACrossOriginFrameCount() {
        val p = page(el("frame", "内嵌页面:x.test", 0, 100, 200, 200))
        val a = listOf(root(), ax("f", 2, "Iframe"), root(XF, "xr"), root(XF2, "yr"), ax("b", 20, "checkbox", "同意", parent = "yr", frame = XF2))
        val r = ElementFusion.fuse(
            input(p, listOf(2), a, owners = mapOf(2 to XF), frames = listOf(Frame(MAIN, null), Frame(XF, MAIN), Frame(XF2, XF)),
                boxes = mapOf(20 to Box(10.0, 150.0, 30.0, 170.0))),
        )
        assertEquals(listOf("frame", "checkbox"), r.elements.map { it.role })
    }

    @Test fun readableDocumentNeedsAHitTestBeforeAddingAxOnlyNodes() {
        val p = page(el("text", "标题", 10, 10), el("text", "尾", 10, 300))
        val a = listOf(root(), ax("t", 1, "StaticText", "标题"), ax("closed", 7, "button", "封闭影子里的按钮"), ax("t2", 2, "StaticText", "尾"))
        val boxes = mapOf(7 to Box(10.0, 100.0, 110.0, 140.0))
        val noHit = ElementFusion.fuse(input(p, listOf(1, 2), a, boxes = boxes))
        assertEquals(2, noHit.elements.size)
        val covered = ElementFusion.fuse(input(p, listOf(1, 2), a, boxes = boxes, onTop = mapOf(7 to false)))
        assertEquals(2, covered.elements.size)
        val ok = ElementFusion.fuse(input(p, listOf(1, 2), a, boxes = boxes, onTop = mapOf(7 to true)))
        assertEquals(listOf("标题", "封闭影子里的按钮", "尾"), ok.elements.map { it.name })
        assertEquals(mapOf("@e1" to "@e1", "@e3" to "@e2"), ok.pageRefs)
        assertTrue(ok.frameOwners.isEmpty())
    }

    @Test fun axOnlyNodesInsideAListedInteractiveElementAreNotCandidates() {
        val p = page(el("link", "商品", 10, 10, 200, 100, c = 1))
        val a = listOf(root(), ax("l", 1, "link", "商品"), ax("inner", 2, "button", "收藏", parent = "l"))
        val inp = input(p, listOf(1), a, boxes = mapOf(2 to Box(10.0, 10.0, 40.0, 40.0)), onTop = mapOf(2 to true))
        assertTrue(ElementFusion.candidates(inp).isEmpty())
        assertEquals(1, ElementFusion.fuse(inp).elements.size)
    }

    @Test fun axOnlyDuplicateOfAnUnmatchedPageElementIsDropped() {
        // 页面脚本的元素没换到节点号,无障碍树里同一个按钮就别再列一遍
        val p = page(el("button", "登录", 10, 10, 100, 40, c = 1))
        val a = listOf(root(), ax("b", 9, "button", "登录"))
        val r = ElementFusion.fuse(input(p, listOf(null), a, boxes = mapOf(9 to Box(10.0, 10.0, 110.0, 50.0)), onTop = mapOf(9 to true)))
        assertEquals(1, r.elements.size)
    }

    @Test fun nestedSameNameAxOnlyNodesListedOnce() {
        val p = page(el("frame", "内嵌页面:x.test", 0, 0, 300, 300))
        val a = listOf(
            root(), ax("f", 2, "Iframe"), root(XF, "xr"),
            ax("link", 30, "link", "继续", parent = "xr", frame = XF),
            ax("btn", 31, "button", "继续", parent = "link", frame = XF),
            ax("twin", 32, "link", "继续", parent = "xr", frame = XF),
        )
        val r = ElementFusion.fuse(
            input(p, listOf(2), a, owners = mapOf(2 to XF), frames = listOf(Frame(MAIN, null), Frame(XF, MAIN)),
                boxes = mapOf(30 to Box(10.0, 10.0, 110.0, 40.0), 31 to Box(12.0, 12.0, 108.0, 38.0), 32 to Box(10.0, 10.0, 110.0, 41.0))),
        )
        assertEquals(listOf("frame", "link"), r.elements.map { it.role })
    }

    @Test fun clickListenersPromotePlainText() {
        val p = page(el("text", "展开全部", 10, 10), el("text", "普通段落", 10, 60), el("button", "已可点", 10, 110, c = 1))
        val a = listOf(root(), ax("1", 1, "generic"), ax("2", 2, "paragraph"), ax("3", 3, "generic"))
        val inp = input(p, listOf(1, 2, 3), a, listeners = setOf(1))
        assertEquals(listOf(0, 1), ElementFusion.listenerProbes(inp))
        val r = ElementFusion.fuse(inp)
        assertEquals("button", r.elements[0].role)
        assertTrue(r.elements[0].clickable)
        assertEquals("text", r.elements[1].role)
        assertFalse(r.elements[1].clickable)
    }

    @Test fun budgetDropsLeastUsefulFirstAndCountsThem() {
        val p = page(
            el("text", "", 10, 0), el("button", "甲", 10, 40, c = 1), el("text", "说明一", 10, 80),
            el("button", "乙", 10, 120, c = 1), el("text", "说明二", 10, 160), el("link", "丙", 10, 200, c = 1),
        )
        val r = ElementFusion.fuse(input(p, List(6) { null }, budget = 4))
        assertEquals(listOf("甲", "说明一", "乙", "丙"), r.elements.map { it.name })
        assertEquals((1..4).map { "@e$it" }, r.elements.map { it.ref })
        assertEquals(mapOf("@e1" to "@e2", "@e2" to "@e3", "@e3" to "@e4", "@e4" to "@e6"), r.pageRefs)
        assertEquals(2, r.omitted)
    }

    @Test fun budgetKeepsCrossOriginButtonsOverText() {
        val texts = (0 until 5).map { el("text", "文$it", 10, 300 + it * 30) }
        val p = page(el("frame", "内嵌页面:x.test", 0, 0, 300, 200), *texts.toTypedArray())
        val a = listOf(root(), ax("f", 2, "Iframe"), root(XF, "xr"), ax("b", 10, "button", "支付", parent = "xr", frame = XF))
        val r = ElementFusion.fuse(
            input(p, listOf(2) + List(5) { null }, a, owners = mapOf(2 to XF), frames = listOf(Frame(MAIN, null), Frame(XF, MAIN)),
                boxes = mapOf(10 to Box(10.0, 10.0, 60.0, 40.0)), budget = 4),
        )
        assertEquals(listOf("内嵌页面:x.test", "支付", "文0", "文1"), r.elements.map { it.name })
        assertEquals(3, r.omitted)
    }

    @Test fun emptyPageStillGetsCrossOriginNothing() {
        // 页面脚本一个元素都没列:没有占位就没有跨源框架可补,读得到的文档里没命中检测也不补
        val a = listOf(root(), ax("b", 1, "button", "x"))
        val r = ElementFusion.fuse(input(emptyList(), emptyList(), a, boxes = mapOf(1 to Box(0.0, 0.0, 10.0, 10.0))))
        assertTrue(r.elements.isEmpty())
    }

    @Test fun roleMapping() {
        assertEquals("textbox", ElementFusion.role("searchbox"))
        assertEquals("image", ElementFusion.role("img"))
        assertEquals("combobox", ElementFusion.role("PopUpButton"))
        assertEquals("button", ElementFusion.role("DisclosureTriangle"))
        assertEquals("frame", ElementFusion.role("Iframe"))
        assertNull(ElementFusion.role("generic"))
        assertNull(ElementFusion.role("StaticText"))
        assertNull(ElementFusion.role("LabelText"))
    }

    @Test fun subtreeIdsCoverDescendants() {
        val a = listOf(root(), ax("b", 5, "button", "x"), ax("s", 6, "StaticText", "x", parent = "b"), ax("o", 7, "button", "y"))
        assertEquals(setOf(5, 6), ElementFusion.subtreeIds(a, a[1]))
    }

    private companion object {
        const val MAIN = "MAIN"
        const val XF = "XFRAME"
        const val XF2 = "XFRAME2"
    }
}
