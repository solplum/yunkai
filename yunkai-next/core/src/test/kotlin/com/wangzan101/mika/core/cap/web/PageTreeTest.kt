package com.wangzan101.mika.core.cap.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import com.wangzan101.mika.core.uistate.WebInfo

class PageTreeTest {
    private val info = WebInfo("https://example.com/", "示例", "t1", 200)
    private val vw = 1082
    private val vh = 2402

    private fun page(els: String, trunc: Boolean = false) =
        """{"els":[$els],"vw":412,"vh":915,"dpr":2.625,"trunc":$trunc,"mut":17}"""

    @Test fun mapsRolesNamesAndFlags() {
        val s = PageTree.parse(
            page(
                """{"r":"button","n":"搜索","x":120,"y":40,"w":80,"h":32,"id":"go","c":1,"e":0,"s":0},
                   {"r":"textbox","n":"关键词","x":10,"y":40,"w":100,"h":32,"c":0,"e":1,"s":0},
                   {"r":"text","n":"列表","x":0,"y":100,"w":412,"h":500,"c":0,"e":0,"s":1}"""
            ), vw, vh, "w1-1", info,
        )
        assertEquals("w1-1", s.snapshotId)
        assertEquals(info, s.web)
        assertNull(s.packageName)
        val (b, t, l) = s.elements
        assertEquals("button", b.role); assertEquals("搜索", b.name); assertEquals("#go", b.resourceId)
        assertTrue(b.clickable); assertFalse(b.editable); assertFalse(b.scrollable)
        assertEquals("textbox", t.role); assertNull(t.resourceId)
        assertFalse(t.clickable); assertTrue(t.editable)
        assertEquals("text", l.role); assertTrue(l.scrollable)
    }

    @Test fun passwordFieldsAreMarkedSecret() {
        val s = PageTree.parse(page("""{"r":"textbox","n":"","x":0,"y":0,"w":20,"h":20,"e":1,"pw":1},{"r":"textbox","n":"","x":0,"y":30,"w":20,"h":20,"e":1}"""),
            vw, vh, "w1-9", info)
        assertEquals(listOf(true, false), s.elements.map { it.secret })
    }

    @Test fun cssToDevicePixelsAndNormalizedBounds() {
        // 中心 (206,457) CSS 像素
        val s = PageTree.parse(page("""{"r":"button","n":"中","x":196,"y":447,"w":20,"h":20,"c":1}"""), vw, vh, "w1-2", info)
        assertEquals(vw, s.width); assertEquals(vh, s.height)
        val e = s.elements.single()
        assertTrue("cx=${e.centerX}", abs(e.centerX - 540) <= 2)
        assertTrue("cy=${e.centerY}", abs(e.centerY - 1200) <= 2)
        val midX = (e.bounds[0] + e.bounds[2]) / 2
        val midY = (e.bounds[1] + e.bounds[3]) / 2
        assertTrue("midX=$midX", abs(midX - 500) <= 3)
        assertTrue("midY=$midY", abs(midY - 500) <= 3)
        e.bounds.forEach { assertTrue(it in 0..999) }
    }

    @Test fun refsInOrder() {
        val els = (1..5).joinToString(",") { """{"r":"link","n":"链$it","x":0,"y":${it * 40},"w":100,"h":30,"c":1}""" }
        val s = PageTree.parse(page(els), vw, vh, "w1-3", info)
        assertEquals((1..5).map { "@e$it" }, s.elements.map { it.ref })
        assertEquals((1..5).map { "链$it" }, s.elements.map { it.name })
    }

    @Test fun truncatedFlagCarried() {
        val one = """{"r":"text","n":"a","x":0,"y":0,"w":10,"h":10}"""
        assertTrue(PageTree.parse(page(one, trunc = true), vw, vh, "w1-4", info).web!!.truncated)
        assertFalse(PageTree.parse(page(one), vw, vh, "w1-5", info).web!!.truncated)
    }

    @Test fun malformedJsonGivesEmptySnapshotNotCrash() {
        for (bad in listOf("", "null", "not json", "{\"els\":5}", "[1,2]", """{"els":[{"r":1,"x":"a"}, 3]}""")) {
            val s = PageTree.parse(bad, vw, vh, "w1-6", info)
            assertEquals("w1-6", s.snapshotId)
            assertEquals(info.url, s.web!!.url)
            assertEquals(vw, s.width)
            assertTrue(bad, s.elements.size <= 1)
        }
        assertTrue(PageTree.parse("garbage", vw, vh, "w1-7", info).elements.isEmpty())
    }

    @Test fun hugeAndInfiniteCoordinatesStayInViewport() {
        val s = PageTree.parse(page(
            """{"r":"button","n":"大","x":1e12,"y":-1e12,"w":1e400,"h":5,"c":1},
               {"r":"button","n":"负","x":-1e400,"y":1e400,"w":10,"h":10,"c":1}"""
        ), vw, vh, "w1-8", info)
        assertEquals(2, s.elements.size)
        for (e in s.elements) {
            assertTrue("cx=${e.centerX}", e.centerX in 0 until vw)
            assertTrue("cy=${e.centerY}", e.centerY in 0 until vh)
            e.bounds.forEach { assertTrue(it in 0..999) }
        }
    }

    @Test fun scalePrefersViewportOverDpr() {
        // dpr 写错(页面缩放过)时按实际视口宽度换算
        val json = """{"els":[{"r":"button","n":"中","x":196,"y":447,"w":20,"h":20}],"vw":412,"vh":915,"dpr":1,"trunc":false}"""
        val e = PageTree.parse(json, vw, vh, "w1-9", info).elements.single()
        assertTrue("cx=${e.centerX}", abs(e.centerX - 541) <= 2)
        // 没有 vw 时退回 dpr
        val noVw = """{"els":[{"r":"button","n":"中","x":196,"y":447,"w":20,"h":20}],"dpr":2.625}"""
        assertTrue(abs(PageTree.parse(noVw, vw, vh, "w1-10", info).elements.single().centerX - 541) <= 2)
    }

    @Test fun badSlotsKeepRefsAlignedWithPageArray() {
        val s = PageTree.parse(page("""{"r":"link","n":"一"}, null, 3, {"r":"link","n":"四"}"""), vw, vh, "w1-11", info)
        assertEquals(listOf("@e1" to "一", "@e4" to "四"), s.elements.map { it.ref to it.name })
    }

    @Test fun focusAndPlaceholderFlags() {
        val s = PageTree.parse(page(
            """{"r":"textbox","n":"搜索商品","x":0,"y":0,"w":100,"h":30,"e":1,"f":1,"ph":1},
               {"r":"textbox","n":"已填","x":0,"y":40,"w":100,"h":30,"e":1},
               {"r":"file","n":"选择文件","x":0,"y":80,"w":100,"h":30,"c":1}"""
        ), vw, vh, "w1-12", info)
        val (a, b, f) = s.elements
        assertTrue(a.focused); assertTrue(a.hint)
        assertFalse(b.focused); assertFalse(b.hint)
        assertEquals("file", f.role)
    }

    @Test fun declaredKeyShortcuts() {
        val s = PageTree.parse(page(
            """{"r":"button","n":"删除","x":0,"y":0,"w":100,"h":30,"c":1,"k":"Control+Shift+D  Delete Alt+d"},
               {"r":"button","n":"无","x":0,"y":40,"w":100,"h":30,"c":1}"""
        ), vw, vh, "w1-13", info)
        assertEquals(listOf("Control+Shift+D", "Delete", "Alt+d"), s.elements[0].keyShortcuts)
        assertTrue(s.elements[1].keyShortcuts.isEmpty())
    }
}
