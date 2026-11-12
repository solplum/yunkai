package com.wangzan101.mika.cap.web.cdp

import com.wangzan101.mika.core.cap.web.cdp.CdpTargetInfo
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WebCdpPureTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun siteModesPersistAndInherit() {
        val dir = tmp.newFolder("web")
        val m = SiteModes(dir)
        assertFalse(m.isMobile("example.com"))
        m.set("www.Example.com", mobile = true)
        assertTrue(m.isMobile("example.com"))
        assertTrue(m.isMobile("m.example.com"))
        assertTrue(m.isMobile("www.example.com."))
        assertFalse(m.isMobile("notexample.com"))
        assertFalse(m.isMobile(null))
        // 换个实例读回来
        val again = SiteModes(dir)
        assertEquals(listOf("example.com"), again.mobileSites())
        // 子域名改回桌面版:连上级记录一起去掉
        again.set("shop.example.com", mobile = false)
        assertFalse(again.isMobile("example.com"))
        assertFalse(SiteModes(dir).isMobile("example.com"))
    }

    @Test fun siteModesSurviveCorruptFile() {
        val dir = tmp.newFolder("bad")
        File(dir, "site_modes.json").writeText("{not json")
        val m = SiteModes(dir)
        assertFalse(m.isMobile("example.com"))
        m.set("example.com", true)
        assertTrue(SiteModes(dir).isMobile("example.com"))
    }

    @Test fun candidatesSkipClaimedAttachedAndNonPages() {
        fun t(id: String, url: String, type: String = "page", ws: Boolean = true) =
            CdpTargetInfo(id, type, url, webSocketDebuggerUrl = if (ws) "ws://localhost/devtools/page/$id" else null)
        val list = listOf(
            t("a", "https://x.test/1"), t("b", "https://x.test/2"), t("c", "https://x.test/2", ws = false),
            t("d", "https://x.test/2", type = "service_worker"), t("e", "https://x.test/2"),
        )
        assertEquals(listOf("b", "a"), TargetMatch.candidates(list, claimed = setOf("e"), tabUrl = "https://x.test/2").map { it.id })
        assertEquals(listOf("a", "b", "e"), TargetMatch.candidates(list, emptySet(), tabUrl = null).map { it.id })
        assertTrue(TargetMatch.isMarker("m1", "m1"))
        assertFalse(TargetMatch.isMarker("", ""))
        assertFalse(TargetMatch.isMarker(null, "m1"))
        assertTrue("'m1'" in TargetMatch.markScript("m1"))
    }

    @Test fun desktopIdentityIsConsistent() {
        val ua = "Mozilla/5.0 (Linux; Android 13; Pixel Build/X; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/152.0.7977.64 Mobile Safari/537.36"
        assertEquals("152" to "152.0.7977.64", DesktopProfile.version(ua))
        assertNull(DesktopProfile.version("no engine here"))
        val engine = listOf(Brand("Chromium", "152", "152.0.7977.64"), Brand("Not?A_Brand", "99", "99.0.0.0"), Brand("Android WebView", "152", "152.0.7977.64"))
        val brands = DesktopProfile.brands(engine, "152", "152.0.7977.64")
        assertEquals(listOf("Chromium", "Not?A_Brand"), brands.map { it.brand })
        assertEquals("Chromium", DesktopProfile.brands(null, "152", "152.0.7977.64").first().brand)
        val o = DesktopProfile.userAgentOverride("152", "152.0.7977.64", brands)
        assertEquals("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36", o["userAgent"]!!.jsonPrimitive.content)
        assertEquals("Linux x86_64", o["platform"]!!.jsonPrimitive.content)
        val meta = o["userAgentMetadata"]!!.jsonObject
        assertFalse(meta["mobile"]!!.jsonPrimitive.boolean)
        assertEquals("Linux", meta["platform"]!!.jsonPrimitive.content)
        assertEquals("152", meta["brands"]!!.jsonArray[0].jsonObject["version"]!!.jsonPrimitive.content)
        assertFalse("acceptLanguage" in o)
    }

    @Test fun metricsFillTheViewWidth() {
        val m = DesktopProfile.metricsOverride(720, 1520, DesktopProfile.fitScale(720, 1.75f))
        assertEquals(1280, m["width"]!!.jsonPrimitive.int)
        assertEquals(2702, m["height"]!!.jsonPrimitive.int)
        assertFalse(m["mobile"]!!.jsonPrimitive.boolean)
        assertEquals(1, m["deviceScaleFactor"]!!.jsonPrimitive.int)
        // 1280 CSS 像素 × 1.75 × scale = 720 视图像素
        assertEquals(720.0, 1280 * 1.75 * m["scale"]!!.jsonPrimitive.double, 1e-6)
        assertEquals(800, DesktopProfile.metricsOverride(0, 0, null)["height"]!!.jsonPrimitive.int)
        assertNull(DesktopProfile.fitScale(0, 1.75f))
    }

    @Test fun handOperatedTabsKeepNativeZoom() {
        val scale = DesktopProfile.fitScale(720, 1.75f)
        // Agent 的标签:仿真把整页缩进视图
        val (agent, args) = DesktopProfile.viewportCall(false, 720, 1520, scale)
        assertEquals("Emulation.setDeviceMetricsOverride", agent)
        assertEquals(DesktopProfile.metricsOverride(720, 1520, scale), args)
        // 用户亲手操作的标签:撤销视口仿真,交给 WebView 的宽视口与总览模式,能双指缩放
        val (hand, none) = DesktopProfile.viewportCall(true, 720, 1520, scale)
        assertEquals("Emulation.clearDeviceMetricsOverride", hand)
        assertTrue(none.isEmpty())
    }

    @Test fun desktopScreenIsTheMaximizedWindow() {
        val m = DesktopProfile.metricsOverride(1280, 800, null)
        assertEquals(1280, m["screenWidth"]!!.jsonPrimitive.int)
        // 屏幕高 = 视口高 + 窗口外框,和补丁里的 outerHeight 一致
        assertEquals(800 + DesktopProfile.WINDOW_CHROME_PX, m["screenHeight"]!!.jsonPrimitive.int)
        assertEquals("landscapePrimary", m["screenOrientation"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("portraitPrimary", DesktopProfile.metricsOverride(720, 1520, null)["screenOrientation"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun desktopEmulationTurnsOffTouchAndReportsAMouse() {
        val calls = DesktopProfile.emulationCalls().toMap()
        assertFalse(calls.getValue("Emulation.setTouchEmulationEnabled")["enabled"]!!.jsonPrimitive.boolean)
        assertFalse(calls.getValue("Emulation.setEmitTouchEventsForMouse")["enabled"]!!.jsonPrimitive.boolean)
        val media = calls.getValue("Emulation.setEmulatedMedia")["features"]!!.jsonArray.associate {
            it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["value"]!!.jsonPrimitive.content
        }
        assertEquals(mapOf("pointer" to "fine", "any-pointer" to "fine", "hover" to "hover", "any-hover" to "hover"), media)
        val (m, p) = DesktopProfile.clearMediaCall()
        assertEquals("Emulation.setEmulatedMedia", m)
        assertTrue(p["features"]!!.jsonArray.isEmpty())
    }

    @Test fun identityScriptCarriesTheProfile() {
        val js = DesktopProfile.identityScript()
        assertFalse(js.contains("__"))
        assertTrue(js.contains("'${DesktopProfile.NAVIGATOR_PLATFORM}'"))
        assertTrue(js.contains("innerHeight + ${DesktopProfile.WINDOW_CHROME_PX}"))
        for (k in listOf("maxTouchPoints", "ontouchstart", "matchMedia", "outerWidth", "createEvent", "webdriver")) assertTrue(k, js.contains(k))
        // 不碰 UA、语言、时区
        for (k in listOf("userAgent", "languages", "timeZone", "Intl")) assertFalse(k, js.contains(k))
    }
}
