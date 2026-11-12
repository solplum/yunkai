package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.uistate.ScreenDiagnosis
import com.wangzan101.mika.core.uistate.ScreenFacts
import com.wangzan101.mika.core.uistate.ScreenState
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

class WebBarrierTest {
    private fun facts(
        texts: List<String> = listOf("商品列表", "加入购物车"),
        status: Int? = 200,
        frames: List<String> = emptyList(),
        host: String = "shop.example.com",
        password: Boolean = false,
    ) = WebFacts(
        ScreenFacts("shop.example.com", true, texts.size, false, emptyList(), texts, password),
        status, frames, host,
    )

    @Test fun status429WithTextIsRiskControl() {
        val c = WebBarrier.classify(facts(listOf("Too Many Requests", "请稍后再试"), 429))
        assertEquals(ScreenState.NEEDS_HUMAN, c.state); assertTrue(c.needsHuman)
        assertEquals("access_blocked", c.takeoverReason)
        assertEquals("access_blocked", WebBarrier.classify(facts(listOf("访问过于频繁,请稍后"), 503)).takeoverReason)
        assertEquals("access_blocked", WebBarrier.classify(facts(listOf("Access blocked"), 403)).takeoverReason)
    }

    @Test fun status403AloneIsNotRiskControl() {
        val c = WebBarrier.classify(facts(listOf("Forbidden"), 403))
        assertEquals(ScreenState.NORMAL, c.state); assertFalse(c.needsHuman)
        // 有那类字但状态正常也不算
        assertEquals(ScreenState.NORMAL, WebBarrier.classify(facts(listOf("账号异常处理指南"), 200)).state)
    }

    @Test fun captchaFrameHostIsCaptcha() {
        for (u in listOf("https://www.google.com/recaptcha/api2/anchor?k=x", "https://www.recaptcha.net/recaptcha/api2/bframe",
                "https://newassets.hcaptcha.com/captcha/v1/x", "https://challenges.cloudflare.com/cdn-cgi/challenge-platform/x",
                "https://static.geetest.com/x", "https://client-api.arkoselabs.com/fc/x")) {
            val c = WebBarrier.classify(facts(frames = listOf("https://ads.example.net/slot", u)))
            assertEquals(u, "captcha", c.takeoverReason); assertTrue(c.needsHuman)
        }
        // google.com 上别的框架(地图、视频)不算
        assertEquals(ScreenState.NORMAL, WebBarrier.classify(facts(frames = listOf("https://www.google.com/maps/embed?pb=1"))).state)
        assertEquals(ScreenState.NORMAL, WebBarrier.classify(facts(frames = listOf("https://player.video.example.org/v/1"))).state)
        // 同源框架不算(只看跨源的)
        assertEquals(ScreenState.NORMAL, WebBarrier.classify(facts(frames = listOf("https://hcaptcha.com/x"), host = "www.hcaptcha.com")).state)
        assertEquals("captcha", WebBarrier.classify(facts(listOf("请完成安全验证"))).takeoverReason)
    }

    @Test fun unsupportedBrowserNeedsNoHuman() {
        for (t in listOf("请在浏览器中打开", "当前浏览器不支持", "请下载 App 继续", "请下载App继续使用", "Your browser is not supported")) {
            val c = WebBarrier.classify(facts(listOf(t)))
            assertEquals(t, ScreenState.NORMAL, c.state); assertFalse(c.needsHuman)
            assertEquals(ScreenDiagnosis.UNSUPPORTED_BROWSER, c.takeoverReason)
            assertTrue(c.cause!!.contains("switch route"))
        }
        // 只是提到浏览器或下载 App,不算拒绝
        for (t in listOf("Open in browser", "下载 App 领红包", "请下载 App")) assertEquals(t, null, WebBarrier.classify(facts(listOf(t))).takeoverReason)
    }

    @Test fun fallsBackToScreenDiagnosisForPasswordField() {
        val c = WebBarrier.classify(facts(listOf("登录", "用户名"), password = true))
        assertEquals(ScreenState.NEEDS_HUMAN, c.state); assertEquals("login", c.takeoverReason)
    }

    @Test fun screenDiagnosisNeedsHumanWinsFirst() {
        // 429 限流页上同时有密码框:先按同一套规则判成登录
        val c = WebBarrier.classify(facts(listOf("Too many requests"), 429, password = true))
        assertEquals("login", c.takeoverReason)
        assertEquals("captcha", WebBarrier.classify(facts(listOf("请完成安全验证", "请在浏览器中打开"))).takeoverReason)
    }

    @Test fun normalPageNormal() {
        val c = WebBarrier.classify(facts())
        assertEquals(ScreenState.NORMAL, c.state); assertFalse(c.needsHuman); assertNull(c.takeoverReason)
        assertEquals("shop.example.com", c.currentApp)
    }

    @Test fun riskControlInterstitialNeedsHuman() {
        for (t in listOf(
            listOf("安全提示", "当前网络环境存在异常，请稍后再试"),
            listOf("访问异常", "返回首页"),
            listOf("您的操作过于频繁"),
            listOf("安全提示", "请完成下方验证后继续访问"),
            listOf("Security check", "We need to verify this request"),
            listOf("Our systems have detected unusual traffic from your computer network"),
        )) {
            val c = WebBarrier.classify(facts(t))
            assertEquals("$t", ScreenState.NEEDS_HUMAN, c.state); assertTrue(c.needsHuman)
            assertTrue("$t", c.takeoverReason == "access_blocked" || c.takeoverReason == "captcha")
        }
        assertEquals("access_blocked", WebBarrier.classify(facts(listOf("安全提示", "访问存在异常"))).takeoverReason)
        // 只有标题、没有异常一类的字:不算
        assertEquals(ScreenState.NORMAL, WebBarrier.classify(facts(listOf("安全提示", "请勿向陌生人转账"))).state)
        // 字很多的页面(讲这类问题的文章)不算
        val article = listOf("网络环境异常怎么办") + List(60) { "第 $it 段正文,讲解常见原因和处理办法。" }
        assertEquals(ScreenState.NORMAL, WebBarrier.classify(facts(article)).state)
    }

    @Test fun apiRiskReadsTopLevelMessage() {
        assertEquals("您的环境存在异常", WebBarrier.apiRisk("""{"code":37,"message":"您的环境存在异常","zpData":{}}"""))
        assertEquals("Too Many Requests", WebBarrier.apiRisk("""{"error":"Too Many Requests"}"""))
        assertEquals("访问过于频繁", WebBarrier.apiRisk("""{"ret":1,"msg":"访问过于频繁"}"""))
        // 成功的、不是 JSON 的、只在数据里提到的:都不算
        assertNull(WebBarrier.apiRisk("""{"code":0,"message":"Success","data":{"list":[]}}"""))
        assertNull(WebBarrier.apiRisk("""{"code":0,"data":{"title":"访问异常排查指南"}}"""))
        assertNull(WebBarrier.apiRisk("<html>访问异常</html>"))
        assertNull(WebBarrier.apiRisk("[1,2]"))
        assertNull(WebBarrier.apiRisk(null))
    }

    @Test fun checkScreenHintsSwitchingRoute() = runTest {
        val web = FakeWebPort().apply { check = WebBarrier.classify(facts(listOf("请在浏览器中打开"))) }
        val t = toolDispatcher(FakeScreen(), web = web)
        t.execute(Part.ToolCall("c1", "web_navigate", """{"url":"https://shop.example.com/"}"""))
        val r = t.execute(Part.ToolCall("c2", "web_diagnose", "{}")).content
        assertEquals("unsupported_browser", r["takeoverReason"]!!.jsonPrimitive.content)
        assertEquals("false", r["needsHuman"]!!.jsonPrimitive.content)
        assertEquals("This site refuses this embedded browser. Switch route once: open the app if it is installed, otherwise end_not_done.", r["hint"]!!.jsonPrimitive.content)
    }
}
