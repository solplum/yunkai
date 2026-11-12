package com.wangzan101.mika.cap.web

import org.junit.Assert.assertEquals
import org.junit.Test

class WebLoginPlanTest {
    @Test
    fun domainsGoUpToTheRegistrableLevel() {
        assertEquals(listOf("a.shop.example.com", "shop.example.com", "example.com"), WebLogin.domainsFor("a.shop.example.com"))
        assertEquals(listOf("example.com"), WebLogin.domainsFor("example.com"))
        // 拿不准的后缀不往上走到公共后缀
        assertEquals(listOf("shop.example.co.uk", "example.co.uk"), WebLogin.domainsFor("shop.example.co.uk"))
        assertEquals(listOf("localhost"), WebLogin.domainsFor("localhost"))
    }

    @Test
    fun cookieNamesFromHeader() {
        assertEquals(listOf("sid", "pref", "empty"), WebLogin.cookieNames("sid=abc; pref=1;empty=; =bad; sid=dup"))
        assertEquals(emptyList<String>(), WebLogin.cookieNames(null))
    }

    @Test
    fun expiryLinesCoverHostOnlyAndDomainCookies() {
        assertEquals(
            listOf("sid=; Max-Age=0; Path=/", "sid=; Max-Age=0; Path=/; Domain=.example.com"),
            WebLogin.expiryLines("sid", "example.com"),
        )
    }
}
