package com.wangzan101.mika.core.cap.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUrlPolicyTest {
    @Test fun onlyHttpAndHttps() {
        assertNull(WebUrlPolicy.checkNavigable("https://example.com/a?b=1"))
        assertNull(WebUrlPolicy.checkNavigable("http://example.com"))
        assertNull(WebUrlPolicy.checkNavigable("HTTPS://Example.com"))
        for (u in listOf(
            "file:///sdcard/a.html", "content://x/y", "javascript:alert(1)", "data:text/html,hi",
            "intent://x#Intent;package=com.example.app;end", "weixin://dl/scan", "", "example.com", "https://",
        )) assertNotNull(u, WebUrlPolicy.checkNavigable(u))
    }

    @Test fun intentUrlYieldsPackage() {
        val l = WebUrlPolicy.external("intent://x#Intent;package=com.example.app;end")!!
        assertEquals("com.example.app", l.packageName)
        assertEquals("intent://x#Intent;package=com.example.app;end", l.url)
        assertNull(WebUrlPolicy.external("intent://x#Intent;scheme=foo;end")!!.packageName)
    }

    @Test fun marketLinkIsExternal() {
        assertEquals("com.example.app", WebUrlPolicy.external("market://details?id=com.example.app")!!.packageName)
        assertEquals("com.example.app", WebUrlPolicy.external("https://play.google.com/store/apps/details?id=com.example.app&hl=zh")!!.packageName)
        val custom = WebUrlPolicy.external("weixin://dl/scan")!!
        assertNull(custom.packageName)
        assertNull(WebUrlPolicy.external("https://example.com/details?id=x"))
        assertNull(WebUrlPolicy.external("javascript:void(0)"))
        assertNull(WebUrlPolicy.external("about:blank"))
    }

    @Test fun sameSiteIgnoresWwwAndSubdomain() {
        assertEquals("example.com", WebUrlPolicy.host("https://WWW.Example.com:8443/p"))
        assertNull(WebUrlPolicy.host("not a url"))
        assertTrue(WebUrlPolicy.sameSite("https://www.example.com/a", "https://example.com/b"))
        assertTrue(WebUrlPolicy.sameSite("https://m.shop.example.com", "https://passport.example.com/login"))
        assertTrue(WebUrlPolicy.sameSite("https://a.example.com.cn", "https://b.example.com.cn"))
        assertFalse(WebUrlPolicy.sameSite("https://foo.com.cn", "https://bar.com.cn"))
        assertFalse(WebUrlPolicy.sameSite("https://example.com", "https://example.org"))
        assertFalse(WebUrlPolicy.sameSite("https://example.com", "not a url"))
    }

    @Test fun unknownSuffixFailsTowardDifferent() {
        assertFalse(WebUrlPolicy.sameSite("https://a.foo.xy.zz", "https://b.bar.xy.zz"))
        assertFalse(WebUrlPolicy.sameSite("https://shop.abc.de", "https://news.abc.de"))
        assertTrue(WebUrlPolicy.sameSite("https://www.example.de", "https://shop.example.de"))
        assertTrue(WebUrlPolicy.sameSite("https://news.bbc.co.uk", "https://www.bbc.co.uk"))
        assertTrue(WebUrlPolicy.sameSite("https://abc.de/x", "https://www.abc.de/y"))
        assertFalse(WebUrlPolicy.sameSite("https://10.0.0.1", "https://10.0.0.2"))
        assertTrue(WebUrlPolicy.sameSite("http://10.0.0.1:80/a", "https://10.0.0.1/b"))
    }

    @Test fun hostingPlatformUsersAreDifferentSites() {
        assertFalse(WebUrlPolicy.sameSite("https://alice.github.io", "https://bob.github.io"))
        assertTrue(WebUrlPolicy.sameSite("https://docs.alice.github.io", "https://alice.github.io/p"))
        assertFalse(WebUrlPolicy.sameSite("https://a.vercel.app", "https://b.vercel.app"))
        assertFalse(WebUrlPolicy.sameSite("https://github.io", "https://alice.github.io"))
    }

    @Test fun sameOriginComparesSchemeHostPort() {
        assertTrue(WebUrlPolicy.sameOrigin("https://example.com/a", "https://example.com:443/b"))
        assertFalse(WebUrlPolicy.sameOrigin("http://example.com", "https://example.com"))
        assertFalse(WebUrlPolicy.sameOrigin("https://example.com", "https://example.com:8443"))
        assertFalse(WebUrlPolicy.sameOrigin("https://www.example.com", "https://example.com"))
        assertTrue(WebUrlPolicy.sameSite("http://example.com", "https://example.com:8443"))
    }

    @Test fun packageNamesValidated() {
        assertNull(WebUrlPolicy.external("intent://x#Intent;package=evil pkg;end")!!.packageName)
        assertNull(WebUrlPolicy.external("market://details?id=1bad.pkg")!!.packageName)
        assertNull(WebUrlPolicy.external("market://details?id=nodot")!!.packageName)
        assertEquals("com.example.app", WebUrlPolicy.external("market://details?id=com%2Eexample.app")!!.packageName)
    }

    @Test fun storeWebPagesNotNavigable() {
        assertNotNull(WebUrlPolicy.checkNavigable("https://play.google.com/store/apps/details?id=com.example.app"))
        assertNotNull(WebUrlPolicy.external("https://apps.apple.com/cn/app/id123"))
        assertNull(WebUrlPolicy.checkNavigable("https://play.google.com/about"))
    }

    @Test fun schemelessHostWithPortGetsSchemeMessage() {
        assertEquals("网址要带 http:// 或 https://", WebUrlPolicy.checkNavigable("example.com:8080/a"))
        assertEquals("网址要带 http:// 或 https://", WebUrlPolicy.checkNavigable("localhost:3000"))
        assertNull(WebUrlPolicy.external("example.com:8080"))
        assertNotNull(WebUrlPolicy.checkNavigable("ｈttp://example.com"))
        assertTrue(WebUrlPolicy.checkNavigable("weixin://dl/scan")!!.contains("在手机上打开它"))
        assertTrue(WebUrlPolicy.checkNavigable("file:///a")!!.contains("http/https"))
    }

    @Test fun idnHostsAsAscii() {
        assertEquals("xn--fsqu00a.xn--fiqs8s", WebUrlPolicy.host("https://例子.中国/路径"))
        assertTrue(WebUrlPolicy.sameSite("https://www.例子.中国", "https://xn--fsqu00a.xn--fiqs8s"))
        assertNull(WebUrlPolicy.checkNavigable("https://例子.中国/"))
    }
}
