package com.wangzan101.mika.cap.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SiteRegistryTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000L

    private fun registry() = SiteRegistry(tmp.root, clock = { now })

    @Test
    fun orderAndPersistence() {
        val r = registry()
        r.visited("a.example.com"); now += 10
        r.visited("WWW.B.example.org"); now += 10
        r.visited("c.example.net"); now += 10
        r.markLoggedIn("a.example.com")
        val hosts = r.list().map { it.host }
        // 登录过的在前,再按最近访问
        assertEquals(listOf("a.example.com", "c.example.net", "b.example.org"), hosts)
        val again = registry().list()
        assertEquals(hosts, again.map { it.host })
        assertTrue(again.first().loggedInByUser)
        assertEquals(1_000L, again.first().lastVisitMs)
    }

    @Test
    fun revisitUpdatesTimeAndRemoveForgets() {
        val r = registry()
        r.visited("a.example.com"); now += 10
        r.visited("b.example.com"); now += 10
        r.visited("a.example.com")
        assertEquals(listOf("a.example.com", "b.example.com"), r.list().map { it.host })
        r.remove("a.example.com")
        assertEquals(listOf("b.example.com"), registry().list().map { it.host })
    }

    @Test
    fun markLoggedInSurvivesRestart() {
        registry().markLoggedIn("shop.example.com")
        val e = registry().list().single()
        assertEquals("shop.example.com", e.host)
        assertTrue(e.loggedInByUser)
    }

    @Test
    fun ignoresBlankAndNonHosts() {
        val r = registry()
        r.visited("")
        r.visited("not a host")
        r.visited("https://x.example.com/path")
        assertEquals(listOf("x.example.com"), r.list().map { it.host })
    }

    @Test
    fun corruptFileMovedAsideNotOverwritten() {
        val f = tmp.root.resolve("sites.json")
        f.writeText("{ not json")
        val r = registry()
        assertTrue(r.list().isEmpty())
        r.visited("a.example.com")
        val aside = tmp.root.listFiles()!!.filter { it.name.startsWith("sites.json.corrupt-") }
        assertEquals(1, aside.size)
        assertEquals("{ not json", aside.single().readText())
        assertEquals(listOf("a.example.com"), registry().list().map { it.host })
    }

    @Test
    fun clearForgetsEverything() {
        val r = registry()
        r.visited("a.example.com")
        r.markLoggedIn("b.example.com")
        r.clear()
        assertTrue(registry().list().isEmpty())
    }

    @Test
    fun loginObservationsFollowThePage() {
        val r = registry()
        assertTrue(r.loginObserved("a.example.com", LoginState.SignedIn))
        assertTrue(registry().list().single().loggedInByUser)
        // 看不出的不动,同样的结果不重写
        assertFalse(r.loginObserved("a.example.com", LoginState.Unknown))
        assertFalse(r.loginObserved("a.example.com", LoginState.SignedIn))
        // 会话过期:看出已退出就去掉
        assertTrue(r.loginObserved("a.example.com", LoginState.SignedOut))
        assertFalse(registry().list().single().loggedInByUser)
        // 没登记过的站点看出已退出不新建
        assertFalse(r.loginObserved("b.example.com", LoginState.SignedOut))
        assertEquals(listOf("a.example.com"), r.list().map { it.host })
    }

    @Test
    fun challengeLifecycle() {
        val r = registry()
        r.markLoggedIn("shop.example.com")
        r.challenged("shop.example.com", "access_blocked")
        // 跨重启保留,登录状态与访问时间不受影响
        val e = registry().list().single()
        assertTrue(e.challenged(now))
        assertEquals("access_blocked", e.challenge)
        assertTrue(e.loggedInByUser)
        now += 10
        r.visited("shop.example.com")
        assertEquals(setOf("shop.example.com"), registry().challengedSites())
        // 登录核实不清掉它
        r.markLoggedIn("shop.example.com")
        assertEquals(setOf("shop.example.com"), r.challengedSites())
        // 过期后不再算
        now += SiteRegistry.CHALLENGE_TTL_MS
        assertTrue(r.challengedSites().isEmpty())
        assertFalse(r.list().single().challenged(now))
        // 再碰到一次重新算;清除该站数据(从登记表去掉)后没有了
        r.challenged("shop.example.com", "captcha")
        assertEquals(setOf("shop.example.com"), r.challengedSites())
        r.remove("shop.example.com")
        assertTrue(registry().challengedSites().isEmpty())
    }

    @Test
    fun repeatedChallengeWithinTheRefreshWindowIsNotRewritten() {
        val r = registry()
        r.challenged("shop.example.com", "captcha")
        val file = tmp.root.resolve("sites.json")
        val first = file.readText()
        now += SiteRegistry.CHALLENGE_REFRESH_MS - 1
        r.challenged("shop.example.com", "captcha")
        assertEquals(first, file.readText())
        // 换了种类、或过了刷新时间才重写
        r.challenged("shop.example.com", "access_blocked")
        assertEquals("access_blocked", registry().list().single().challenge)
        now += SiteRegistry.CHALLENGE_REFRESH_MS
        r.challenged("shop.example.com", "access_blocked")
        assertEquals(now, registry().list().single().challengedAtMs)
        assertEquals(setOf("captcha", "access_blocked"), SiteRegistry.CHALLENGE_KINDS)
    }
}
