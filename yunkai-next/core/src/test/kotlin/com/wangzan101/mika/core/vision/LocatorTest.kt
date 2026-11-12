package com.wangzan101.mika.core.vision

import com.wangzan101.mika.core.uistate.ScreenFamily
import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

internal val FRAME_A: ByteArray = JvmTestImageOps.encode(100, 200) { x, _ -> if (x < 50) 30 else 200 }
internal val FRAME_B: ByteArray = JvmTestImageOps.encode(100, 200) { _, y -> if (y < 100) 30 else 200 }
internal val BLACK: ByteArray = JvmTestImageOps.encode(100, 200) { _, _ -> 0 }

internal fun uiEl(ref: String, name: String, bounds: List<Int>, res: String? = null) =
    UiElement(ref, "button", name, 0, 0, bounds, res, true, false, false)

/** 截图按 [shots] 依次给,给完后一直给最后一张;[peek] 返回 [elements]。 */
internal class LocScreen(
    var elements: List<UiElement> = emptyList(),
    vararg shots: ByteArray?,
    var secure: Boolean = false,
    var hasWindow: Boolean = true,
) : ScreenPort {
    var peekFails = false
    var width = 1080
    var height = 2400
    private val queue = ArrayDeque(shots.toList().ifEmpty { listOf(FRAME_A) })
    var peeks = 0
    val taps = mutableListOf<String>()
    fun show(vararg next: ByteArray?) { queue.clear(); queue.addAll(next.toList()) }
    private fun read(id: String) = ScreenSnapshot(id, if (hasWindow) "com.android.settings" else null, elements, width, height, hasWindow = hasWindow)
    override suspend fun snapshot() = read("s")
    override suspend fun peek(): ScreenSnapshot {
        if (peekFails) throw IllegalStateException("unreadable")
        peeks++; return read("p$peeks")
    }
    override suspend fun screenshotPng(): ByteArray? = if (queue.size > 1) queue.removeFirst() else queue.firstOrNull()
    override suspend fun isProtected() = secure
    override suspend fun tapRef(ref: String, snapshotId: String): ActionResult { taps += "ref $ref"; return ActionResult.Ok(changed = true) }
    override suspend fun tapXY(x: Int, y: Int): ActionResult { taps += "$x,$y"; return ActionResult.Ok(changed = true) }
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = ActionResult.Ok()
    override suspend fun clearText(ref: String?, snapshotId: String?) = ActionResult.Ok()
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = ActionResult.Ok()
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = ActionResult.Ok()
    override suspend fun back(): ActionResult { taps += "phone_back"; return ActionResult.Ok(changed = true) }
    override suspend fun pressKey(keyCode: Int) = ActionResult.Ok()
    override suspend fun openApp(packageName: String, cold: Boolean) = ActionResult.Ok()
    override suspend fun waitStable(timeoutMs: Long) = true
    override suspend fun listApps() = listOf("com.android.settings" to "设置")
}

internal fun says(vararg replies: Any) = FakeModel(*replies.map { if (it is String) ModelReply(listOf(Part.Text(it)), StopReason.EndTurn, null) else it }.toTypedArray())

class LocatorTest {
    private val wlan = uiEl("@e1", "WLAN", listOf(0, 100, 999, 160))
    private fun locator(screen: ScreenPort, model: FakeModel, registry: LocateRegistry = LocateRegistry()) =
        Locator(screen, VisionService(model, JvmTestImageOps), registry, ScreenFamily("phone", "p"))

    private fun found(r: LocateResult) = r as? LocateResult.Found ?: throw AssertionError("expected Found, got $r")
    private fun notFound(r: LocateResult) = r as? LocateResult.NotFound ?: throw AssertionError("expected NotFound, got $r")

    @Test fun treePathUniqueExactMatch() = runTest {
        // 嵌套、同中心的同名元素算一个
        val inner = uiEl("@e2", "wlan", listOf(10, 110, 989, 150))
        val m = says()
        val reg = LocateRegistry()
        val r = found(locator(LocScreen(listOf(wlan, inner, uiEl("@e3", "WLAN 设置说明", listOf(0, 300, 999, 360)))), m, reg).locate("WLAN 那一行", " W LAN "))
        assertEquals("elements", r.source)
        assertEquals(500 to 130, r.x to r.y)
        assertArrayEquals(intArrayOf(0, 100, 999, 160), r.box)
        assertEquals("@v1", r.ref); assertTrue(r.snapshotId.startsWith("loc_"))
        assertTrue("没有走视觉", m.requests.isEmpty())
        val (p, hash) = reg.get(r.ref, r.snapshotId)!!
        assertEquals(Point(500, 130), p); assertEquals(JvmTestImageOps.aHash(FRAME_A), hash)
        // 资源 ID 也认
        val byId = found(locator(LocScreen(listOf(uiEl("@e9", "", listOf(0, 0, 100, 100), res = "com.x:id/Wifi_Switch"))), says()).locate("开关", "com.x:id/wifi_switch"))
        assertEquals(50 to 50, byId.x to byId.y)
    }

    @Test fun treePathAmbiguousFallsToVision() = runTest {
        val twice = listOf(wlan, uiEl("@e2", "WLAN", listOf(0, 500, 999, 560)))
        val m = says("""{"bbox_2d":[0,100,1000,161],"seen":"WLAN"}""", "是")
        val r = found(locator(LocScreen(twice), m).locate("顶上那个 WLAN", "WLAN"))
        assertEquals("vision", r.source)
        assertEquals("WLAN", r.seen)
        assertEquals(2, m.requests.size)
        // 第一次问:截图 2 + 描述和原文
        val text = (m.requests[0].single().parts[0] as Part.Text).text
        assertTrue(text, "顶上那个 WLAN" in text && "\"WLAN\"" in text)
    }

    @Test fun treePathSkippedWhenScreenMoved() = runTest {
        val s = LocScreen(listOf(wlan), FRAME_A, FRAME_B)
        val m = says("""{"bbox_2d":[0,100,1000,161],"seen":"WLAN"}""", "yes")
        val r = found(locator(s, m).locate("WLAN", "WLAN"))
        assertEquals("vision", r.source)
    }

    @Test fun rejectsMalformedBoxes() = runTest {
        for (reply in listOf(
            """{"bbox_2d":[0.1,0.2,0.3,0.4],"seen":"x"}""",
            """{"bbox_2d":[0,0,100,100]} {"bbox_2d":[200,200,300,300]}""",
            """{"bbox_2d":[300,0,100,100]}""",
            """{"bbox_2d":[0,0,1001,100]}""",
            "我在屏幕上没有看到它",
            "null",
            "```json\nnull\n```",
            // 只有一个 bbox_2d 键,但给了两组四个数
            """{"bbox_2d":[0,0,100,100],"other":[200,200,300,300]}""",
        )) {
            val m = says(reply, "是")
            val r = notFound(locator(LocScreen(), m).locate("某个图标", null))
            assertEquals(reply, "not_found", r.reason)
            assertEquals(reply, 1, m.requests.size)
        }
    }

    @Test fun scalesTo999() = runTest {
        val r = found(locator(LocScreen(), says("```json\n{\"bbox_2d\": [500, 0, 1000, 1000]}\n```", "Yes.")).locate("右半边", null))
        assertArrayEquals(intArrayOf(500, 0, 999, 999), r.box)
        assertEquals(750 to 500, r.x to r.y)
        assertEquals(null, r.seen)
        // 没核对过名字:提醒先看 seen
        assertNotNull(r.note)
    }

    @Test fun nameMismatchOnSeen() = runTest {
        val r = notFound(locator(LocScreen(), says("""{"bbox_2d":[0,100,1000,161],"seen":"蓝牙"}""")).locate("WLAN", "WLAN"))
        assertEquals("name_mismatch", r.reason)
        assertTrue(r.message, "蓝牙" in r.message)
        // 互相包含不算对不上
        found(locator(LocScreen(), says("""{"bbox_2d":[0,100,1000,161],"seen":"WLAN 已连接"}""", "是")).locate("WLAN", "WLAN"))
    }

    @Test fun nameMismatchOnElementAtCenter() = runTest {
        val bt = uiEl("@e1", "蓝牙", listOf(0, 100, 999, 160))
        val r = notFound(locator(LocScreen(listOf(bt)), says("""{"bbox_2d":[0,100,1000,161],"seen":"WLAN"}""")).locate("WLAN", "WLAN"))
        assertEquals("name_mismatch", r.reason)
        assertTrue(r.message, "蓝牙" in r.message)
        // 面积超过半屏的容器不算「那一点上的元素」
        val root = uiEl("@e1", "设置", listOf(0, 0, 999, 999))
        found(locator(LocScreen(listOf(root)), says("""{"bbox_2d":[0,100,1000,161],"seen":"WLAN"}""", "是")).locate("WLAN", "WLAN"))
    }

    @Test fun notConfirmedUnlessYes() = runTest {
        val box = """{"bbox_2d":[0,100,1000,161],"seen":"WLAN"}"""
        for (answer in listOf("否", "不是", "可能是", "No", "yesterday")) {
            val r = notFound(locator(LocScreen(), says(box, answer)).locate("WLAN", null))
            assertEquals(answer, "not_confirmed", r.reason)
        }
        for (answer in listOf("是", "是的", "Yes", " yes, it is")) {
            found(locator(LocScreen(), says(box, answer)).locate("WLAN", null))
        }
        // 复看用的是画了红框的图,问的是 target
        val m = says(box, "是")
        found(locator(LocScreen(), m).locate("WLAN 开关", null))
        val confirm = m.requests[1].single()
        assertTrue(m.system!!, "\"WLAN 开关\"" in m.system!!)
        val framed = Base64.getDecoder().decode((confirm.parts.last() as Part.Image).pngBase64)
        assertTrue(JvmTestImageOps.decode(framed).px.any { (it.toInt() and 0xFF) == 255 })
    }

    @Test fun protectedContent() = runTest {
        for (s in listOf(LocScreen(secure = true), LocScreen(emptyList(), BLACK, secure = true))) {
            val m = says()
            val r = notFound(locator(s, m).locate("WLAN", "WLAN"))
            assertEquals("protected_content", r.reason)
            assertTrue("受保护的画面不交给视觉模型", m.requests.isEmpty())
            try {
                locator(s, m).describe("看看")
                fail("expected protected")
            } catch (e: ScreenUnavailable) {
                assertEquals("protected_content", e.reason)
            }
            assertTrue(m.requests.isEmpty())
        }
        // 还没开 App(虚拟屏没建):截不到图,说清楚,不是出错
        val none = LocScreen(emptyList(), null, hasWindow = false)
        assertEquals("no_app_open", notFound(locator(none, says()).locate("WLAN", null)).reason)
        try {
            locator(none, says()).describe("看看"); fail("expected no_app_open")
        } catch (e: ScreenUnavailable) {
            assertEquals("no_app_open", e.reason)
        }
    }

    @Test fun paymentPageCanBeSentToVision() = runTest {
        val pages = listOf(
            listOf(uiEl("@e1", "确认支付", listOf(0, 800, 999, 900))),
            listOf(uiEl("@e1", "实付 ¥39.90", listOf(0, 600, 600, 660)), uiEl("@e2", "支付方式", listOf(0, 700, 999, 760))),
        )
        for (els in pages) {
            assertNull(locator(LocScreen(els), says()).visionBlocker())
        }
        // 商品页上的价格不算付款页
        found(locator(LocScreen(listOf(uiEl("@e1", "¥39.90", listOf(0, 600, 600, 660)), uiEl("@e2", "立即购买", listOf(0, 900, 999, 999)))),
            says("""{"bbox_2d":[0,900,1000,1000]}""", "是")).locate("购买按钮", null))
        // 读不到屏就无从确认,不传
        val m = says()
        val r = notFound(locator(LocScreen().apply { peekFails = true }, m).locate("图标", null))
        assertEquals("vision_unavailable", r.reason)
        assertTrue(m.requests.isEmpty())
    }

    @Test fun treeMatchNeedsOnScreenElement() = runTest {
        // 屏外的元素被压到边上、面积为 0:不算命中,改走视觉
        val m = says("""{"bbox_2d":[0,100,1000,161],"seen":"WLAN"}""", "是")
        val r = found(locator(LocScreen(listOf(uiEl("@e1", "WLAN", listOf(0, 999, 999, 999)))), m).locate("WLAN", "WLAN"))
        assertEquals("vision", r.source)
        // 恰好半屏的元素不算「那一点上的元素」
        val half = uiEl("@e1", "蓝牙", listOf(0, 0, 998, 500))
        found(locator(LocScreen(listOf(half)), says("""{"bbox_2d":[0,100,1000,161],"seen":"WLAN"}""", "是")).locate("WLAN", "WLAN"))
    }

    @Test fun visionErrorIsVisionFailed() = runTest {
        assertEquals("vision_unavailable", notFound(locator(LocScreen(), says(ModelError.Network())).locate("WLAN", null)).reason)
        val box = """{"bbox_2d":[0,100,1000,161],"seen":"WLAN"}"""
        assertEquals("vision_unavailable", notFound(locator(LocScreen(), says(box, ModelError.Server(500))).locate("WLAN", null)).reason)
    }

    @Test fun describeSendsTaskAndScreenshot() = runTest {
        val m = says("[屏幕] 设置首页")
        assertEquals("[屏幕] 设置首页", locator(LocScreen(), m).describe("打开 WLAN"))
        val msg = m.requests.single().single()
        assertEquals("我现在想做的事:打开 WLAN", (msg.parts[0] as Part.Text).text)
        assertTrue(msg.parts[1] is Part.Image)
        assertTrue(m.system!!, "[可交互元素]" in m.system!!)
    }

    /** 定位准度没验证过的看图模型。 */
    private fun unverified(vararg replies: String) = FakeModel(
        *replies.map { ModelReply(listOf(Part.Text(it)), StopReason.EndTurn, null) }.toTypedArray(), groundingVerified = false)

    /** 树够用的一屏:八个有名字的按钮,都不在要找的框里。 */
    private fun adequate() = LocScreen((1..8).map { uiEl("@e$it", "设置项$it", listOf(0, 300 + it * 70, 999, 360 + it * 70)) })

    @Test fun unverifiedVisionBoxNeedsSecondConsistentLook() = runTest {
        val box = """{"bbox_2d":[0,100,1000,161],"seen":"WLAN"}"""
        // 再框一次、看到的一致、两次复看都确认:交出去
        val m = unverified(box, "是", """{"bbox_2d":[10,105,990,158],"seen":"WLAN"}""", "是")
        val r = found(locator(adequate(), m).locate("WLAN 那一行", null))
        assertFalse(r.unverified)
        assertEquals("vision", r.source); assertEquals(4, m.requests.size)
        // 点按第一次的框算
        assertEquals(500 to 131, r.x to r.y)
        // 第二次看到的字不一样、框到了别处、第二次复看不确认、第一次就没给 seen:都不交出去,提示用 phone_describe
        for (second in listOf(
            listOf("""{"bbox_2d":[0,100,1000,161],"seen":"蓝牙"}""", "是"),
            listOf("""{"bbox_2d":[0,500,1000,561],"seen":"WLAN"}""", "是"),
            listOf(box, "否"),
            listOf("null"),
        )) {
            val bad = notFound(locator(adequate(), unverified(box, "是", *second.toTypedArray())).locate("WLAN 那一行", null))
            assertEquals("$second", "unverified_grounding", bad.reason)
            assertTrue(bad.message, "phone_describe" in bad.message)
        }
        val noSeen = notFound(locator(adequate(), unverified("""{"bbox_2d":[0,100,1000,161]}""", "是", box, "是")).locate("WLAN 那一行", null))
        assertEquals("unverified_grounding", noSeen.reason)
    }

    @Test fun unusableTreeLetsAnUnbackedVisionBoxThroughMarkedUnverified() = runTest {
        // 树里什么都没有(App 自己画界面):没东西可对照,一次框加一次复看就交出去,标明没佐证
        val m = unverified("""{"bbox_2d":[0,880,1000,940],"seen":"输入框"}""", "是")
        val r = found(locator(LocScreen(), m).locate("底部的输入框", null))
        assertTrue(r.unverified)
        assertEquals(2, m.requests.size)
        assertTrue(r.note!!, "no_effect" in r.note!!)
        // 复看不确认的照旧不交
        assertEquals("not_confirmed", notFound(locator(LocScreen(), unverified("""{"bbox_2d":[0,880,1000,940]}""", "否")).locate("输入框", null)).reason)
    }

    @Test fun partialTreeNeedsBackingOnlyWhenTheTargetIsInIt() = runTest {
        // 控件大多没名字:缺了一块
        val blank = (1..4).map { UiElement("@b$it", "button", "", 0, 0, listOf(0, 500 + it * 80, 200, 560 + it * 80), null, true, false, false) }
        val partial = blank + uiEl("@e9", "发送消息", listOf(800, 900, 999, 960))
        val box = """{"bbox_2d":[0,100,1000,161],"seen":"发送"}"""
        // 要找的字在树里(只是框没落在它上面):照旧要再看一次
        assertEquals("unverified_grounding", notFound(locator(LocScreen(partial), unverified(box, "是", "null")).locate("发送按钮", "发送")).reason)
        // 要找的不在树里:没东西可对照
        val r = found(locator(LocScreen(partial), unverified("""{"bbox_2d":[0,100,1000,161],"seen":"表情"}""", "是")).locate("表情按钮", "表情"))
        assertTrue(r.unverified)
    }

    @Test fun unverifiedVisionBoxBackedByTreeLabel() = runTest {
        // 框中心那个元素的名字就是要找的字:一次复看就够(控件树里有两个同名,不走精确匹配)
        val dup = uiEl("@e2", "WLAN", listOf(0, 600, 999, 660))
        val m = unverified("""{"bbox_2d":[0,100,1000,161],"seen":"WLAN"}""", "是")
        val r = found(locator(LocScreen(listOf(wlan, dup)), m).locate("WLAN", "WLAN"))
        assertEquals("vision", r.source); assertEquals(2, m.requests.size)
        // 控件树精确匹配照旧不问看图模型
        val tree = unverified()
        assertEquals("elements", found(locator(LocScreen(listOf(wlan)), tree).locate("WLAN", "WLAN")).source)
        assertEquals(0, tree.requests.size)
    }
}
