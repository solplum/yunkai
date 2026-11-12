package com.wangzan101.mika.core.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

fun entry(name: String, display: String = name, packages: List<String> = emptyList(), domains: List<String> = emptyList(), body: String? = "正文$name") =
    KnowledgeEntry(name, display, Surface.Android, packages, domains, emptyList(), null, "", body)

class ChannelAdvisorTest {
    private val xhs = entry("xhs", "小红书", listOf("com.xingin.xhs"), listOf("www.xiaohongshu.com"))
    private val skills = testCatalog(appMd("xhs", listOf("com.xingin.xhs"), sites = listOf("xiaohongshu.com")), siteMd("xiaohongshu.com", apps = listOf("xhs")))

    @Test fun bothRoutesListed() {
        assertEquals(
            "可用渠道:小红书 — App:已安装(com.xingin.xhs),有 App 做法;网页:xiaohongshu.com,有网页做法",
            ChannelAdvisor.line(listOf(xhs), skills, ChannelFacts(setOf("com.xingin.xhs"), emptySet(), true)),
        )
        // 没有网页做法时只给域名
        assertEquals(
            "可用渠道:小红书 — App:已安装(com.xingin.xhs);网页:xiaohongshu.com",
            ChannelAdvisor.line(listOf(xhs), testCatalog(), ChannelFacts(setOf("com.xingin.xhs"), emptySet(), true)),
        )
    }

    @Test fun appEntryBringsItsLinkedSite() {
        // App 条目只带包名,关联的站点名就是主域名;另一份站点条目归到同一个服务下
        val app = entry("xhs", "小红书", listOf("com.xingin.xhs")).copy(related = listOf("xiaohongshu.com"))
        val site = entry("xiaohongshu.com", "小红书", domains = listOf("xiaohongshu.com")).copy(surface = Surface.Web, related = listOf("xhs"))
        assertEquals(
            "可用渠道:小红书 — App:已安装(com.xingin.xhs),有 App 做法;网页:xiaohongshu.com,有网页做法",
            ChannelAdvisor.line(listOf(app, site), skills, ChannelFacts(setOf("com.xingin.xhs"), emptySet(), true)),
        )
        assertEquals("可用渠道:小红书 — 网页:xiaohongshu.com,有网页做法",
            ChannelAdvisor.line(listOf(site.copy(related = emptyList())), skills, ChannelFacts(emptySet(), emptySet(), true)))
    }

    @Test fun notInstalledSaysSo() {
        val l = ChannelAdvisor.line(listOf(xhs), skills, ChannelFacts(emptySet(), emptySet(), true))!!
        assertTrue(l, "App:未安装" in l)
        assertFalse(l, "已安装" in l)
    }

    @Test fun webUnavailableOmitsWeb() {
        assertEquals("可用渠道:小红书 — App:已安装(com.xingin.xhs),有 App 做法",
            ChannelAdvisor.line(listOf(xhs), skills, ChannelFacts(setOf("com.xingin.xhs"), emptySet(), false)))
        // 只有网页的条目,浏览器不可用时没有可说的
        assertNull(ChannelAdvisor.line(listOf(entry("web", domains = listOf("example.com"))), skills, ChannelFacts(emptySet(), emptySet(), false)))
    }

    @Test fun loggedInMarked() {
        val l = ChannelAdvisor.line(listOf(xhs), skills, ChannelFacts(emptySet(), setOf("m.xiaohongshu.com"), true))!!
        assertTrue(l, l.endsWith("网页:xiaohongshu.com,有网页做法,在 Mika 里登录过(m.xiaohongshu.com)"))
        val other = ChannelAdvisor.line(listOf(xhs), skills, ChannelFacts(emptySet(), setOf("example.com"), true))!!
        assertFalse(other, "登录过" in other)
    }

    @Test fun challengedMarked() {
        val l = ChannelAdvisor.line(listOf(xhs), skills, ChannelFacts(emptySet(), emptySet(), true, challengedSites = setOf("www.xiaohongshu.com")))!!
        assertTrue(l, l.endsWith("网页:xiaohongshu.com,有网页做法,最近在这里碰到过验证或拦截(www.xiaohongshu.com),用户可在设置里清除该站数据"))
        val other = ChannelAdvisor.line(listOf(xhs), skills, ChannelFacts(emptySet(), emptySet(), true, challengedSites = setOf("example.com")))!!
        assertFalse(other, "拦截" in other)
    }

    @Test fun cappedToFirstChannels() {
        val many = (1..6).map { entry("s$it", "站$it", domains = listOf("s$it.example.com")) }
        val l = ChannelAdvisor.line(many, skills, ChannelFacts(emptySet(), emptySet(), true))!!
        assertEquals(3, l.split(" | ").size)
        assertTrue(l, l.startsWith("可用渠道:站1 — ")); assertFalse(l, "站4" in l)
    }

    @Test fun noEntriesNoLine() {
        assertNull(ChannelAdvisor.line(emptyList(), skills, ChannelFacts(emptySet(), emptySet(), true)))
        assertNull(ChannelAdvisor.line(listOf(entry("bare")), skills, ChannelFacts(emptySet(), emptySet(), true)))
    }
}
