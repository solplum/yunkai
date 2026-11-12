package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.gate.IrreversibleDetector
import com.wangzan101.mika.core.gate.needsCheck
import com.wangzan101.mika.core.knowledge.Link
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

private class LinkScreen(
    var result: ActionResult = ActionResult.Ok(),
    var elements: List<String> = listOf("WLAN"),
    private val inner: FakeScreen = FakeScreen(),
) : ScreenPort by inner {
    val opened = mutableListOf<Triple<String?, String?, String?>>()
    override suspend fun openLink(action: String?, uri: String?, packageName: String?): ActionResult {
        opened += Triple(action, uri, packageName); return result
    }
    private fun snap() = ScreenSnapshot("l1", "com.android.settings",
        elements.mapIndexed { i, n -> UiElement("@e${i + 1}", "text", n, 10, 10 + i * 50, listOf(0, i * 20, 999, i * 20 + 10), null, false, false, false) }, 1080, 2400)
    override suspend fun snapshot() = snap()
    override suspend fun peek() = snap()
}

class OpenLinkTest {
    private fun call(args: String) = Part.ToolCall("c1", "phone_open_link", args)
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private val listed = listOf(
        Link("活动页", "https://example.com/a", "com.example.app"), Link("页面", "someapp://page/1"), Link("订单", "someapp://order/1"),
    )
    private fun d(s: ScreenPort) = toolDispatcher(s, deepLinks = true).also { it.allowLinks(listed) }

    @Test fun rejectsBlockedSchemes() = runTest {
        val s = LinkScreen()
        for (u in listOf("intent://x#Intent;end", "file:///sdcard/a", "content://media/1", "javascript:alert(1)")) {
            val r = d(s).execute(call("""{"uri":"$u","package":"com.x.y"}"""))
            assertEquals(u, "invalid_link", r.content.s("error"))
            assertFalse(r.content["ok"]!!.jsonPrimitive.boolean)
            assertTrue(r.content.containsKey("message")); assertTrue(r.content.containsKey("recoverable"))
        }
        assertTrue(s.opened.isEmpty())
    }

    @Test fun communicationLinksPassPolicy() {
        for (u in listOf("tel:10086", "sms:10086", "smsto:10086", "mailto:a@b.c", "mms:10086", "sip:a@b.c", "geo:0,0")) {
            assertNull(u, LinkPolicy.check(null, u, null))
        }
    }

    @Test fun httpRequiresPackage() = runTest {
        val s = LinkScreen()
        assertEquals("invalid_link", d(s).execute(call("""{"uri":"https://example.com/a"}""")).content.s("error"))
        assertEquals("invalid_link", d(s).execute(call("""{"uri":"http://example.com/a"}""")).content.s("error"))
        assertTrue(s.opened.isEmpty())
        val r = d(s).execute(call("""{"uri":"https://example.com/a","package":"com.example.app"}"""))
        assertTrue(r.content.toString(), r.content["ok"]!!.jsonPrimitive.boolean)
        assertEquals(listOf(Triple(null, "https://example.com/a", "com.example.app")), s.opened)
    }

    @Test fun unknownSettingsActionRejected() = runTest {
        val s = LinkScreen()
        for (a in listOf("android.settings.SOME_VENDOR_PAGE", "android.intent.action.CALL", "android.intent.action.VIEW")) {
            assertEquals(a, "invalid_link", d(s).execute(call("""{"action":"$a"}""")).content.s("error"))
        }
        assertTrue(s.opened.isEmpty())
        val r = d(s).execute(call("""{"action":"android.settings.WIFI_SETTINGS"}"""))
        assertTrue(r.content.toString(), r.content["ok"]!!.jsonPrimitive.boolean)
        assertEquals("com.android.settings", r.content.s("currentApp"))
        assertEquals(listOf(Triple("android.settings.WIFI_SETTINGS", null, null)), s.opened)
    }

    @Test fun everyWhitelistedActionPassesPolicy() {
        for (a in LinkPolicy.SETTINGS_ACTIONS) {
            val uri = if (a == LinkPolicy.APP_DETAILS) "package:com.x.y" else null
            assertNull(a, LinkPolicy.check(a, uri, null))
        }
        assertEquals(14, LinkPolicy.SETTINGS_ACTIONS.size)
    }

    @Test fun appDetailsNeedsPackageUri() = runTest {
        val s = LinkScreen()
        val a = "android.settings.APPLICATION_DETAILS_SETTINGS"
        assertEquals("invalid_link", d(s).execute(call("""{"action":"$a"}""")).content.s("error"))
        assertEquals("invalid_link", d(s).execute(call("""{"action":"$a","uri":"https://x.com"}""")).content.s("error"))
        assertEquals("invalid_link", d(s).execute(call("""{"action":"$a","uri":"package:not a package"}""")).content.s("error"))
        // 其他设置页不带数据
        assertEquals("invalid_link", d(s).execute(call("""{"action":"android.settings.WIFI_SETTINGS","uri":"package:com.x.y"}""")).content.s("error"))
        assertTrue(s.opened.isEmpty())
        val r = d(s).execute(call("""{"action":"$a","uri":"package:com.x.y"}"""))
        assertTrue(r.content.toString(), r.content["ok"]!!.jsonPrimitive.boolean)
    }

    @Test fun needsUriOrAction() = runTest {
        assertEquals("invalid_link", d(LinkScreen()).execute(call("{}")).content.s("error"))
    }

    @Test fun unresolvedMapsToLinkUnresolved() = runTest {
        val s = LinkScreen(ActionResult.Failed("link_unresolved"))
        val r = d(s).execute(call("""{"uri":"someapp://page/1"}"""))
        assertEquals("link_unresolved", r.content.s("error"))
        assertTrue(r.content["recoverable"]!!.jsonPrimitive.boolean)
        assertTrue(r.content.s("message").contains("phone_launch_app"))
        s.result = ActionResult.Failed("app_in_use_by_user")
        assertEquals("app_in_use_by_user", d(s).execute(call("""{"uri":"someapp://page/1"}""")).content.s("error"))
        s.result = ActionResult.Failed("invalid_link")
        assertEquals("invalid_link", d(s).execute(call("""{"uri":"someapp://page/1"}""")).content.s("error"))
    }

    @Test fun portWithoutLinksIsUnsupported() = runTest {
        val r = toolDispatcher(FakeScreen(), deepLinks = true).execute(call("""{"action":"android.settings.SETTINGS"}"""))
        assertEquals("unsupported", r.content.s("error"))
    }

    @Test fun hiddenWhenFlagOff() = runTest {
        val s = LinkScreen()
        val off = toolDispatcher(s)
        assertTrue(off.definitions().none { it.name == "phone_open_link" })
        assertEquals("unknown_tool", off.execute(call("""{"action":"android.settings.SETTINGS"}""")).content.s("error"))
        assertTrue(s.opened.isEmpty())
        val spec = d(s).definitions().single { it.name == "phone_open_link" }
        assertTrue(spec.description.contains("android.settings.WIFI_SETTINGS"))
    }

    @Test fun paymentLinksPassPolicy() {
        for (u in listOf("someapp://pay?order=1", "someapp://cashier/index", "https://m.example.com/checkout?id=1", "someapp://wallet/transfer", "someapp://支付",
            "someapp://wxpay/x", "otherpays://platform/1", "someapp://order/%E6%94%AF%E4%BB%98")) {
            assertNull(u, LinkPolicy.check(null, u, "com.example.app"))
        }
        assertNull(LinkPolicy.check(null, "someapp://display/playlist", null))
    }

    @Test fun landingOnPaymentPageDoesNotRequestTakeover() = runTest {
        val s = LinkScreen(elements = listOf("确认付款", "¥ 25.00", "立即支付"))
        val r = d(s).execute(call("""{"uri":"someapp://order/1"}"""))
        assertTrue(r.content["ok"]!!.jsonPrimitive.boolean)
        assertTrue(r.content["paymentPage"]!!.jsonPrimitive.boolean)
        assertFalse(r.content.toString().contains("hand_to_user"))
    }

    @Test fun onlyListedLinksOpen() = runTest {
        val s = LinkScreen()
        val r = d(s).execute(call("""{"uri":"someapp://other/2"}"""))
        assertEquals("invalid_link", r.content.s("error"))
        assertTrue(r.content.s("message").contains("reference notes"))
        // 包名也要和列出的一致
        assertEquals("invalid_link", d(s).execute(call("""{"uri":"https://example.com/a","package":"com.other.app"}""")).content.s("error"))
        assertEquals("invalid_link", d(s).execute(call("""{"uri":"someapp://page/1","package":"com.other.app"}""")).content.s("error"))
        assertEquals("invalid_link", toolDispatcher(s, deepLinks = true).execute(call("""{"uri":"someapp://page/1"}""")).content.s("error"))
        assertTrue(s.opened.isEmpty())
        assertTrue(d(s).execute(call("""{"uri":"someapp://page/1"}""")).content["ok"]!!.jsonPrimitive.boolean)
        // 设置页不需要列出
        assertTrue(toolDispatcher(s, deepLinks = true).execute(call("""{"action":"android.settings.SETTINGS"}""")).content["ok"]!!.jsonPrimitive.boolean)
    }

    @Test fun countsAsWriteButNotIrreversible() {
        val args = JsonObject(emptyMap())
        assertTrue(isWritingTool("phone_open_link", args))
        assertFalse(IrreversibleDetector.needsCheck("phone_open_link", args))
    }
}
