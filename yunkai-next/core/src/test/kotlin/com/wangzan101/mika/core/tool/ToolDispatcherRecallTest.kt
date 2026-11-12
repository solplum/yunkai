package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

/** 每次读屏换一屏:第 n 次的元素叫「第n屏」,截图是 [n]。 */
private class CountingScreen(private val inner: FakeScreen = FakeScreen()) : ScreenPort by inner {
    var n = 0
    var names: (Int) -> String = { "第${it}屏" }
    var protectedAt: Set<Int> = emptySet()
    override suspend fun snapshot(): ScreenSnapshot {
        n++
        val el = UiElement("@e1", "text", names(n), 500, 500, listOf(0, 0, 999, 999), null, false, false, false)
        return ScreenSnapshot("s$n", "com.app$n", listOf(el), 1080, 2400, protected = n in protectedAt)
    }
    override suspend fun screenshotPng() = byteArrayOf(n.toByte())
}

class ToolDispatcherRecallTest {
    private var seq = 0
    private fun call(name: String, args: String = "{}") = Part.ToolCall("r${++seq}", name, args)
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private suspend fun ToolDispatcher.recall(vararg ids: Int) = execute(call("view_past_screens", """{"ids":[${ids.joinToString()}]}"""))
    private fun shots(r: ToolExecution) = r.content["screenshots"]!!.jsonArray.map { it.jsonObject }

    @Test fun idsIncrementPerObservation() = runTest {
        val d = toolDispatcher(CountingScreen())
        val ids = (1..3).map { d.execute(call(if (it == 2) "phone_list_elements" else "phone_observe")).content.s("screenshotId").toInt() }
        assertEquals(listOf(1, 2, 3), ids)
        // 动作不占编号
        d.execute(call("phone_tap", """{"x":1,"y":1}"""))
        assertEquals("4", d.execute(call("phone_observe")).content.s("screenshotId"))
    }

    @Test fun keepsLast20() = runTest {
        val d = toolDispatcher(CountingScreen())
        repeat(25) { d.execute(call("phone_observe")) }
        val r = d.recall(5, 6, 25)
        assertEquals(listOf(6, 25), shots(r).map { it["id"]!!.jsonPrimitive.int })
        assertEquals(listOf(5), r.content["missing"]!!.jsonArray.map { it.jsonPrimitive.int })
    }

    @Test fun missingIdsReported() = runTest {
        val d = toolDispatcher(CountingScreen())
        d.execute(call("phone_observe"))
        val r = d.recall(1, 9)
        assertEquals("false", r.content.s("ok"))
        assertEquals(listOf(9), r.content["missing"]!!.jsonArray.map { it.jsonPrimitive.int })
        // 找不到的历史截图不算手机操作失败
        assertEquals("false", r.content.s("counts_as_failure"))
        assertEquals(1, shots(r).size)
        val all = d.recall(1)
        assertEquals("true", all.content.s("ok")); assertNull(all.content["missing"])
        // 一次都没读过屏(还没开 App):全部找不到,不是异常
        val none = toolDispatcher(CountingScreen()).recall(1)
        assertEquals("false", none.content.s("ok")); assertTrue(shots(none).isEmpty())
    }

    @Test fun textOnlyModelGetsElementsText() = runTest {
        val d = toolDispatcher(CountingScreen(), recordFrames = true)
        d.execute(call("phone_observe"), step = 4); d.execute(call("phone_observe"))
        val r = d.recall(1)
        val s = shots(r).single()
        // step 是循环给的轮次编号(和折叠摘要的「第 N 步」一致)
        assertEquals("1", s.s("id")); assertEquals("com.app1", s.s("app")); assertEquals("4", s.s("step"))
        assertFalse(d.keepsRecallImages())
        assertTrue(s.s("elementsText"), "第1屏" in s.s("elementsText"))
        assertTrue(r.images.isEmpty()); assertNull(r.image)
        // 截到 6000 字
        val long = CountingScreen().apply { names = { "字".repeat(9000) } }
        val dl = toolDispatcher(long)
        dl.execute(call("phone_list_elements"))
        assertTrue(shots(dl.recall(1)).single().s("elementsText").length <= 6000)
    }

    @Test fun visionModelGetsImagesAttached() = runTest {
        val d = toolDispatcher(CountingScreen(), visionEnabled = true)
        repeat(3) { d.execute(call("phone_observe")) }
        val r = d.recall(3, 1)
        assertEquals(2, r.images.size)
        assertArrayEquals(byteArrayOf(3), r.images[0]); assertArrayEquals(byteArrayOf(1), r.images[1])
        assertTrue(shots(r).all { it.s("imageAttached") == "true" && it["elementsText"] == null })
        // 只读元素(phone_list_elements)那次没有截图:给文字
        d.execute(call("phone_list_elements"))
        val t = d.recall(4)
        assertTrue(t.images.isEmpty()); assertTrue("第4屏" in shots(t).single().s("elementsText"))
    }

    @Test fun protectedScreenIsHiddenButPaymentScreenCanBeRecalled() = runTest {
        val s = CountingScreen().apply { protectedAt = setOf(1); names = { if (it == 2) "确认支付 ¥12.00" else "第${it}屏" } }
        val d = toolDispatcher(s, visionEnabled = true, recordFrames = true)
        repeat(3) { d.execute(call("phone_observe")) }
        val r = d.recall(1, 2, 3)
        assertEquals(2, r.images.size); assertArrayEquals(byteArrayOf(3), r.images.last())
        val byId = shots(r).associateBy { it.s("id") }
        assertFalse(byId.getValue("1").containsKey("imageAttached"))
        assertTrue(byId.getValue("2").containsKey("imageAttached"))
    }

    @Test fun badIdsAreBadArgs() = runTest {
        val d = toolDispatcher(CountingScreen())
        d.execute(call("phone_observe"))
        for (a in listOf("[0]", "[-1]", "[1,2,3,4,5,6]", "[]", "[1.5]", "[\"1\"]", "1")) {
            assertEquals(a, "invalid_arguments", d.execute(call("view_past_screens", """{"ids":$a}""")).content.s("error"))
        }
        assertEquals("invalid_arguments", d.execute(call("view_past_screens", "{}")).content.s("error"))
        assertEquals("true", d.execute(call("view_past_screens", """{"ids":[1],"reason":"再看一眼价格"}""")).content.s("ok"))
    }

    @Test fun recallImagesKeptOnlyForVisionModel() = runTest {
        val vision = toolDispatcher(CountingScreen(), visionEnabled = true)
        vision.execute(call("phone_observe"))
        assertTrue(vision.keepsRecallImages())
        val text = toolDispatcher(CountingScreen(), recordFrames = true)
        text.execute(call("phone_observe"))
        assertFalse(text.keepsRecallImages())
    }

    @Test fun alwaysOffered() {
        assertTrue("view_past_screens" in toolDispatcher(FakeScreen()).definitions().map { it.name })
        assertFalse(isWritingTool("view_past_screens", JsonObject(emptyMap())))
    }
}
