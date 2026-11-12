package com.wangzan101.mika.core.uistate

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class ScreenTextTest {
    private fun el(n: Int, name: String = "项$n", clickable: Boolean = true, rid: String? = null) =
        UiElement("@e$n", "button", name, 540, 120, listOf(0, 0, 999, 999), rid, clickable, false, false)

    private fun snap(vararg e: UiElement) = ScreenSnapshot("sid", "pkg", e.toList(), 1080, 2400)

    @Test fun rendersOnlyMeaningfulNodes() {
        val out = ScreenText.render(snap(el(1, "搜索"), el(2, "", clickable = false)))
        assertTrue("@e1" in out); assertFalse("@e2" in out)
    }

    @Test fun lineFormat() {
        assertEquals("@e1 button \"搜索\" (500,50)", ScreenText.render(snap(el(1, "搜索"))))
    }

    @Test fun truncatesToBudget() {
        val s = snap(*(1..500).map { el(it) }.toTypedArray())
        val out = ScreenText.render(s, 1000)
        assertTrue(out.length <= 1000)
        val m = Regex("…还有 (\\d+) 个节点未显示,可滚动或缩小范围$").find(out)!!
        assertEquals(500, out.lines().size - 1 + m.groupValues[1].toInt())
    }

    @Test fun jsonElementsShapeAndCap() {
        val (arr, truncated) = ScreenJson.elements(snap(el(1, rid = "a:id/x"), el(2), el(3, "", clickable = false)), 1)
        assertEquals(1, arr.size); assertEquals(1, truncated)
        val o = arr[0].jsonObject
        assertEquals("@e1", o["ref"]!!.jsonPrimitive.content)
        assertEquals("a:id/x", o["resourceId"]!!.jsonPrimitive.content)
        // 像素 (540,120) 在 1080x2400 上,与 tap 坐标同为 0–999
        assertEquals(500, o["center"]!!.jsonObject["x"]!!.jsonPrimitive.content.toInt())
        assertEquals(50, o["center"]!!.jsonObject["y"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun normalizeClampsAndMaps() {
        assertEquals(0, normalize(0, 1080)); assertEquals(999, normalize(1079, 1080))
        assertEquals(0, normalize(-5, 1080)); assertEquals(999, normalize(5000, 1080))
        assertEquals(500, normalize(540, 1080))
    }

    @Test fun hintTextIsMarkedSoItIsNotMistakenForTypedText() {
        val field = UiElement("@e1", "EditText", "热词示例", 540, 120, listOf(0, 0, 999, 99), null, true, true, false, hint = true)
        val typed = field.copy(ref = "@e2", name = "咖啡", hint = false)
        val (arr, _) = ScreenJson.elements(snap(field, typed), 10)
        assertEquals("true", arr[0].jsonObject["hint"]!!.jsonPrimitive.content)
        assertFalse(arr[1].jsonObject.containsKey("hint"))
        assertTrue(ScreenText.render(snap(field)).contains("hint"))
    }
}
