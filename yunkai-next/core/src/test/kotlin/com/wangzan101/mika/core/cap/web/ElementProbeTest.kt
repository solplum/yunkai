package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.cap.web.cdp.CdpSession
import com.wangzan101.mika.core.cap.web.cdp.FakeDevTools
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import com.wangzan101.mika.core.uistate.WebInfo

class ElementProbeTest {
    private val info = WebInfo("https://a.test/", "t", "t1", null)

    // 主文档:按钮(29)、跨源框架占位(8,里面是帧 X);跨源框架里有一个按钮(79)
    private val page = PageTree.parse(
        """{"els":[{"r":"button","n":"","x":10,"y":10,"w":100,"h":40,"c":1},
            {"r":"text","n":"展开","x":10,"y":60,"w":100,"h":20},
            {"r":"frame","n":"内嵌页面:x.test","x":0,"y":100,"w":300,"h":100}],
            "vw":400,"vh":800,"dpr":2,"trunc":false,"mut":0}""",
        800, 1600, "w1-1", info,
    )

    private fun json(s: String): JsonElement = Json.parseToJsonElement(s)

    /** 一个按方法名回话的假 DevTools;[routes] 返回 null 表示不回(模拟挂住)。 */
    private fun server(routes: (String, JsonObject) -> String?): FakeDevTools {
        val s = FakeDevTools()
        s.onMessage = { c, m ->
            val method = m.getValue("method").jsonPrimitive.content
            val params = m["params"]?.jsonObject ?: JsonObject(emptyMap())
            routes(method, params)?.let { c.reply(m, json(it)) }
        }
        return s
    }

    private val axMain = """{"nodes":[
        {"nodeId":"1","ignored":false,"role":{"value":"RootWebArea"},"childIds":["2","3","4"]},
        {"nodeId":"2","ignored":false,"role":{"value":"button"},"name":{"value":"关闭"},"backendDOMNodeId":29,"parentId":"1"},
        {"nodeId":"3","ignored":false,"role":{"value":"generic"},"backendDOMNodeId":31,"parentId":"1"},
        {"nodeId":"4","ignored":false,"role":{"value":"Iframe"},"backendDOMNodeId":8,"parentId":"1"}]}"""
    private val axCross = """{"nodes":[
        {"nodeId":"10","ignored":false,"role":{"value":"RootWebArea"},"childIds":["11"]},
        {"nodeId":"11","ignored":false,"role":{"value":"button"},"name":{"value":"CrossChild"},"backendDOMNodeId":79,"parentId":"10","childIds":["12"],
         "properties":[{"name":"focusable","value":{"type":"booleanOrUndefined","value":true}}]},
        {"nodeId":"12","ignored":false,"role":{"value":"StaticText"},"name":{"value":"CrossChild"},"backendDOMNodeId":80,"parentId":"11"}]}"""

    private fun quad(l: Int, t: Int, r: Int, b: Int) = "[$l,$t,$r,$t,$r,$b,$l,$b]"

    private fun routes(crossBox: String = quad(10, 110, 130, 150), hitAt: (Int, Int) -> Int? = { _, _ -> 80 }, listenerOn: String = "o1"): (String, JsonObject) -> String? = { method, p ->
        when (method) {
            "Runtime.evaluate" -> """{"result":{"type":"object","subtype":"array","objectId":"arr"}}"""
            "Runtime.getProperties" -> """{"result":[
                {"name":"0","value":{"type":"object","subtype":"node","objectId":"o0"}},
                {"name":"1","value":{"type":"object","subtype":"node","objectId":"o1"}},
                {"name":"2","value":{"type":"object","subtype":"node","objectId":"o2"}},
                {"name":"length","value":{"type":"number","value":3}}]}"""
            "DOM.describeNode" -> when (p.getValue("objectId").jsonPrimitive.content) {
                "o0" -> """{"node":{"backendNodeId":29,"nodeName":"BUTTON"}}"""
                "o1" -> """{"node":{"backendNodeId":31,"nodeName":"DIV"}}"""
                else -> """{"node":{"backendNodeId":8,"nodeName":"IFRAME","frameId":"X"}}"""
            }
            "Page.getFrameTree" -> """{"frameTree":{"frame":{"id":"M"},"childFrames":[{"frame":{"id":"X","parentId":"M"}}]}}"""
            "Accessibility.getFullAXTree" -> if (p.getValue("frameId").jsonPrimitive.content == "X") axCross else axMain
            "DOM.getBoxModel" -> when (p.getValue("backendNodeId").jsonPrimitive.int) {
                79 -> """{"model":{"border":$crossBox,"content":$crossBox}}"""
                8 -> """{"model":{"border":${quad(0, 100, 300, 200)},"content":${quad(2, 102, 298, 198)}}}"""
                else -> null
            }
            "DOM.getNodeForLocation" -> hitAt(p.getValue("x").jsonPrimitive.int, p.getValue("y").jsonPrimitive.int)
                ?.let { """{"backendNodeId":$it}""" } ?: """{"backendNodeId":1}"""
            "DOMDebugger.getEventListeners" -> if (p.getValue("objectId").jsonPrimitive.content == listenerOn) {
                """{"listeners":[{"type":"click","useCapture":false}]}"""
            } else """{"listeners":[]}"""
            "DOM.scrollIntoViewIfNeeded", "Runtime.releaseObjectGroup" -> "{}"
            else -> "{}"
        }
    }

    private fun <T> withSession(server: FakeDevTools, block: suspend (CdpSession) -> T): T = runBlocking {
        withTimeout(20_000) {
            val s = CdpSession.connect(server, "x", "/devtools/page/A")
            try { block(s) } finally { s.close() }
        }
    }

    @Test fun fusesCrossOriginFrameAndListeners() {
        val server = server(routes())
        val out = withSession(server) { ElementProbe(it).enrich(page, "refs()", 2.0) }
        assertTrue("$out", out is ElementProbe.Outcome.Fused)
        val f = out as ElementProbe.Outcome.Fused
        val r = f.result
        assertEquals(listOf("关闭", "展开", "内嵌页面:x.test", "CrossChild"), r.elements.map { it.name })
        assertEquals(listOf("button", "button", "frame", "button"), r.elements.map { it.role })
        val cross = r.elements[3]
        assertTrue(abs(cross.centerX - 140) <= 2 && abs(cross.centerY - 260) <= 2)
        assertEquals(79, r.backendIds["@e4"])
        assertNull(r.pageRefs["@e4"])
        val anchor = f.anchors.getValue("@e4")
        assertEquals(ElementProbe.Anchor(79, setOf(79, 80), 8), anchor)
        val conn = server.connections.single()
        val methods = conn.received.map { it.getValue("method").jsonPrimitive.content }
        assertTrue("不取整棵 DOM", "DOM.getDocument" !in methods)
        assertTrue("释放对象组", "Runtime.releaseObjectGroup" in methods)
        // 命中检测点在框与占位可见部分相交处的中心
        val hit = conn.received.first { it.getValue("method").jsonPrimitive.content == "DOM.getNodeForLocation" }.getValue("params").jsonObject
        assertEquals(70, hit.getValue("x").jsonPrimitive.int)
        assertEquals(130, hit.getValue("y").jsonPrimitive.int)
    }

    @Test fun frameLocalBoxesAreShiftedByTheFrameContentOrigin() {
        // 框按框架内坐标给出(10,10..130,50):顶层坐标处命中不到,加上框架内容区左上角(2,102)后命中
        val server = server(routes(crossBox = quad(10, 10, 130, 50), hitAt = { x, y -> if (y > 100) 80 else 1 }))
        val out = withSession(server) { ElementProbe(it).enrich(page, "refs()", 2.0) } as ElementProbe.Outcome.Fused
        val cross = out.result.elements.single { it.name == "CrossChild" }
        // CSS (12..132, 112..152) → 视图中心 (144, 264)
        assertTrue("${cross.centerX},${cross.centerY}", abs(cross.centerX - 144) <= 2 && abs(cross.centerY - 264) <= 2)
    }

    @Test fun hangingCallGivesUpWithinTheBudget() {
        val base = routes()
        val server = server { m, p -> if (m == "Accessibility.getFullAXTree") null else base(m, p) }
        val t0 = System.nanoTime()
        val out = withSession(server) { ElementProbe(it, timeoutMs = 400).enrich(page, "refs()", 2.0) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("$out", out is ElementProbe.Outcome.Skipped)
        assertTrue((out as ElementProbe.Outcome.Skipped).reason.contains("timeout"))
        assertTrue("took ${ms}ms", ms < 3_000)
    }

    @Test fun refTableOfWrongSizeIsSkipped() {
        val base = routes()
        val server = server { m, p ->
            if (m == "Runtime.getProperties") """{"result":[{"name":"0","value":{"type":"object","subtype":"node","objectId":"o0"}}]}""" else base(m, p)
        }
        val out = withSession(server) { ElementProbe(it).enrich(page, "refs()", 2.0) }
        assertTrue(out is ElementProbe.Outcome.Skipped)
    }

    @Test fun missingPageScriptIsSkipped() {
        val base = routes()
        val server = server { m, p -> if (m == "Runtime.evaluate") """{"result":{"type":"object","subtype":"null","value":null}}""" else base(m, p) }
        val out = withSession(server) { ElementProbe(it).enrich(page, "refs()", 2.0) }
        assertTrue(out is ElementProbe.Outcome.Skipped)
    }

    @Test fun axErrorsStillGiveAMatchedList() {
        val server = FakeDevTools()
        val base = routes()
        server.onMessage = { c, m ->
            val method = m.getValue("method").jsonPrimitive.content
            if (method == "Accessibility.getFullAXTree") c.replyError(m, -32000, "Frame with the given id was not found.")
            else base(method, m["params"]?.jsonObject ?: JsonObject(emptyMap()))?.let { c.reply(m, json(it)) }
        }
        val out = withSession(server) { ElementProbe(it).enrich(page, "refs()", 2.0) } as ElementProbe.Outcome.Fused
        assertEquals(3, out.result.elements.size)
        assertEquals(mapOf("@e1" to 29, "@e2" to 31, "@e3" to 8), out.result.backendIds)
    }

    @Test fun locateScrollsThenPlaces() {
        val server = server(routes())
        val p = withSession(server) { ElementProbe(it).locate(ElementProbe.Anchor(79, setOf(79, 80), 8)) }
        assertEquals(ElementFusion.Box(10.0, 110.0, 130.0, 150.0), p!!.box)
        assertEquals(true, p.onTop)
        assertEquals(70.0 to 130.0, p.x to p.y)
        val methods = server.connections.single().received.map { it.getValue("method").jsonPrimitive.content }
        assertEquals("DOM.scrollIntoViewIfNeeded", methods.first())
    }

    @Test fun locateTapsTheVisiblePartInsideTheFrame() {
        // 按钮一半伸出框架内容区(2..298):点取露在框架里那部分的中心
        val server = server(routes(crossBox = quad(250, 110, 400, 150)))
        val p = withSession(server) { ElementProbe(it).locate(ElementProbe.Anchor(79, setOf(79, 80), 8)) }!!
        assertEquals(274.0 to 130.0, p.x to p.y)
    }

    @Test fun locateGoneNodeIsNull() {
        val server = FakeDevTools()
        server.onMessage = { c, m -> c.replyError(m, -32000, "No node with given id found") }
        assertNull(withSession(server) { ElementProbe(it).locate(ElementProbe.Anchor(5, setOf(5), null)) })
    }

    @Test fun axTreeOrderFollowsChildIds() {
        val nodes = Json.parseToJsonElement(
            """[{"nodeId":"3","role":{"value":"button"},"parentId":"1","backendDOMNodeId":3},
                {"nodeId":"1","role":{"value":"RootWebArea"},"childIds":["2","3"]},
                {"nodeId":"2","role":{"value":"link"},"name":{"value":"a"},"parentId":"1","childIds":["4"],
                 "properties":[{"name":"disabled","value":{"type":"boolean","value":true}},{"name":"checked","value":{"type":"tristate","value":"false"}},
                               {"name":"editable","value":{"type":"token","value":"plaintext"}}]},
                {"nodeId":"4","role":{"value":"StaticText"},"parentId":"2","ignored":true}]""",
        ).let { (it as kotlinx.serialization.json.JsonArray).map { e -> e.jsonObject } }
        val ax = ElementProbe.parseAxTree(nodes, "F")
        assertEquals(listOf("1", "2", "4", "3"), ax.sortedBy { it.order }.map { it.id })
        val link = ax.single { it.id == "2" }
        assertEquals(setOf("disabled", "editable"), link.states)
        assertEquals("F", link.frameId)
        assertTrue(ax.single { it.id == "4" }.ignored)
        assertEquals("1", ax.single { it.id == "3" }.parent)
    }

    @Test fun frameTreeIsPreorder() {
        val t = Json.parseToJsonElement(
            """{"frame":{"id":"M"},"childFrames":[{"frame":{"id":"A","parentId":"M"},"childFrames":[{"frame":{"id":"A1","parentId":"A"}}]},{"frame":{"id":"B","parentId":"M"}}]}""",
        ).jsonObject
        assertEquals(
            listOf(ElementFusion.Frame("M", null), ElementFusion.Frame("A", "M"), ElementFusion.Frame("A1", "A"), ElementFusion.Frame("B", "M")),
            ElementProbe.parseFrameTree(t),
        )
    }
}
