package com.wangzan101.mika.browser

import com.wangzan101.mika.cap.web.SiteEntry
import com.wangzan101.mika.cap.web.SiteRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BrowserFavoritesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val registry = listOf(
        SiteEntry("shop.example.com", 5_000, true),
        SiteEntry("passport.example.com", 4_000, false),
        SiteEntry("example.com", 3_000, false),
        SiteEntry("news.example.org", 2_000, false),
    )

    @Test
    fun registrySitesCollapseBySiteAndOpenTheShortestHost() {
        val grid = BrowserFavorites.combine(FavoriteEdits(), registry)
        assertEquals(listOf(FavoriteSite("example.com", true), FavoriteSite("news.example.org", false)), grid)
    }

    @Test
    fun pinnedComeFirstAndHiddenAreLeftOut() {
        val grid = BrowserFavorites.combine(FavoriteEdits(listOf("docs.example.net"), setOf("example.com")), registry)
        assertEquals(listOf(FavoriteSite("docs.example.net", false), FavoriteSite("news.example.org", false)), grid)
    }

    @Test
    fun pinnedSiteTakesLoginStateFromTheRegistry() {
        val grid = BrowserFavorites.combine(FavoriteEdits(listOf("m.example.com")), registry)
        assertEquals(FavoriteSite("m.example.com", true), grid.first())
        assertEquals(2, grid.size)
    }

    @Test
    fun limitCapsTheGrid() {
        val many = (1..20).map { SiteEntry("s$it.example$it.com", it.toLong(), false) }
        assertEquals(BrowserFavorites.LIMIT, BrowserFavorites.combine(FavoriteEdits(), many).size)
        assertEquals(3, BrowserFavorites.combine(FavoriteEdits(), many, limit = 3).size)
    }

    @Test
    fun addAndRemovePersistAndRoundTrip() {
        val f = BrowserFavorites(tmp.root)
        f.add("https://www.docs.example.net/guide")
        f.add("news.example.org")
        assertEquals(listOf("docs.example.net", "news.example.org"), f.edits.value.pinned)
        f.remove("example.com")
        f.remove("news.example.org")
        assertEquals(FavoriteEdits(listOf("docs.example.net"), setOf("example.com", "example.org")), BrowserFavorites(tmp.root).edits.value)
        // 再加回来:不再隐藏
        f.add("shop.example.com")
        assertEquals(setOf("example.org"), f.edits.value.hidden)
        assertTrue(BrowserFavorites.contains(BrowserFavorites.combine(f.edits.value, registry), "example.com"))
    }

    @Test
    fun removingFromFavoritesLeavesTheRegistryAlone() {
        val sites = SiteRegistry(tmp.newFolder("reg"))
        sites.markLoggedIn("shop.example.com")
        val f = BrowserFavorites(tmp.root)
        f.remove("shop.example.com")
        assertTrue(BrowserFavorites.combine(f.edits.value, sites.list()).isEmpty())
        assertEquals(listOf(SiteEntry("shop.example.com", sites.list()[0].lastVisitMs, true)), sites.list())
        assertTrue(BrowserFavorites.loggedIn(sites.list(), "example.com"))
    }

    @Test
    fun loggedInLooksAtTheWholeSite() {
        assertTrue(BrowserFavorites.loggedIn(registry, "www.example.com"))
        assertFalse(BrowserFavorites.loggedIn(registry, "news.example.org"))
        assertFalse(BrowserFavorites.loggedIn(registry, "other.example.net"))
    }

    @Test
    fun corruptFileStartsEmpty() {
        tmp.root.resolve("browser_favorites.json").writeText("[1,")
        assertEquals(FavoriteEdits(), BrowserFavorites(tmp.root).edits.value)
    }
}
