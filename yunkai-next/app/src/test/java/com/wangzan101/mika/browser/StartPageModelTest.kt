package com.wangzan101.mika.browser

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class StartPageModelTest {
    @get:Rule val texts = ChineseTexts()

    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private val name: (String) -> String = { if (it == "shop.example.com") "购物" else SiteNames.fallback(it) }

    @Test
    fun recentRowsShowSearchesAndPages() {
        val rows = StartPageModel.recentRows(
            listOf(
                RecentPage(AddressInput.search("周末去哪玩"), "周末去哪玩 - 搜索", 3_000),
                RecentPage("https://shop.example.com/item/1", "清新薄荷牙膏", 2_000),
                RecentPage("https://news.example.org/a", "", 1_000),
            ),
            name,
        )
        assertEquals(listOf("搜索：周末去哪玩", "清新薄荷牙膏", "example"), rows.map { it.title })
        assertEquals(listOf("必应", "购物", "example"), rows.map { it.subtitle })
        assertEquals(listOf(true, false, false), rows.map { it.search })
        assertEquals(listOf(null, "shop.example.com", "news.example.org"), rows.map { it.host })
    }

    @Test
    fun recentRowsKeepOnlyTheNewestOfTheSameAddress() {
        val rows = StartPageModel.recentRows(
            listOf(
                RecentPage("https://a.example.com/x#top", "旧", 1_000),
                RecentPage("https://b.example.com/", "B", 2_000),
                RecentPage("https://a.example.com/x", "新", 3_000),
            ),
            name,
        )
        assertEquals(listOf("新", "B"), rows.map { it.title })
        assertEquals(3_000L, rows[0].openedAtMs)
    }

    @Test
    fun suggestionsStartWithSearchThenMatchingRecent() {
        val recent = StartPageModel.recentRows(
            listOf(
                RecentPage(AddressInput.search("周末去哪玩"), "", 3_000),
                RecentPage("https://news.example.org/weekend", "周末亲子活动推荐", 2_000),
                RecentPage("https://shop.example.com/", "购物首页", 1_000),
            ),
            name,
        )
        val s = StartPageModel.suggestions("周末", recent)
        assertEquals(listOf("搜索「周末」", "搜索：周末去哪玩", "周末亲子活动推荐"), s.map { it.title })
        assertEquals(listOf("必应", "最近打开", "最近打开"), s.map { it.subtitle })
        assertTrue(s[0].search)
        assertEquals(AddressInput.search("周末"), s[0].url)
    }

    @Test
    fun suggestionsMatchAddressIgnoringCase() {
        val recent = StartPageModel.recentRows(listOf(RecentPage("https://Shop.example.com/cart", "购物车", 1_000)), name)
        assertEquals(listOf("搜索「SHOP」", "购物车"),StartPageModel.suggestions("SHOP", recent).map { it.title })
    }

    @Test
    fun addressInputOpensDirectly() {
        val s = StartPageModel.suggestions("example.com", emptyList())
        assertEquals(1, s.size)
        assertEquals("https://example.com", s[0].url)
        assertEquals("打开网址", s[0].subtitle)
        assertEquals(false, s[0].search)
        assertEquals(emptyList<Suggestion>(), StartPageModel.suggestions("  ", emptyList()))
    }

    @Test
    fun suggestionsCapRecentHits() {
        val recent = StartPageModel.recentRows((1..9).map { RecentPage("https://s$it.example.com/", "页面 $it", it.toLong()) }, name)
        assertEquals(1 + StartPageModel.MAX_RECENT_SUGGESTIONS, StartPageModel.suggestions("页面", recent).size)
    }

    @Test
    fun relativeTime() {
        val now = at(2026, 10, 28, 11, 30)
        fun rel(t: Long) = StartPageModel.relativeTime(t, now, zone)
        assertEquals("刚刚", rel(now - 20_000))
        assertEquals("10 分钟前", rel(now - 10 * 60_000))
        assertEquals("今天 09:05", rel(at(2026, 10, 28, 9, 5)))
        assertEquals("今天 00:10", rel(at(2026, 10, 28, 0, 10)))
        assertEquals("昨天", rel(at(2026, 10, 27, 23, 50)))
        assertEquals("10 月 21 日", rel(at(2026, 10, 21, 8, 0)))
        assertEquals("2025 年 12 月 31 日", rel(at(2025, 12, 31, 8, 0)))
    }
}
