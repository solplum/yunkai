package com.wangzan101.mika.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddressInputTest {
    private fun bing(q: String) = AddressInput.SEARCH_PREFIX + q

    @Test
    fun blankIsNothing() {
        assertNull(AddressInput.resolve(""))
        assertNull(AddressInput.resolve("   "))
    }

    @Test
    fun addressesWithSchemeOpenAsTyped() {
        assertEquals("https://example.com/a?b=1", AddressInput.resolve("  https://example.com/a?b=1 "))
        assertEquals("http://example.com", AddressInput.resolve("http://example.com"))
        assertEquals("HTTPS://Example.com", AddressInput.resolve("HTTPS://Example.com"))
        // 别的协议也原样交出去,由网址规则拦下
        assertEquals("ftp://example.com", AddressInput.resolve("ftp://example.com"))
    }

    @Test
    fun bareDomainsGetHttps() {
        assertEquals("https://example.com", AddressInput.resolve("example.com"))
        assertEquals("https://www.example.co.uk/path?q=1#top", AddressInput.resolve("www.example.co.uk/path?q=1#top"))
        assertEquals("https://sub-1.example.org:8443/x", AddressInput.resolve("sub-1.example.org:8443/x"))
        assertEquals("https://xn--fsq.xn--0zwm56d", AddressInput.resolve("xn--fsq.xn--0zwm56d"))
    }

    @Test
    fun localhostAndIpsGetHttp() {
        assertEquals("http://localhost", AddressInput.resolve("localhost"))
        assertEquals("http://localhost:8080/app", AddressInput.resolve("localhost:8080/app"))
        assertEquals("http://192.168.1.10", AddressInput.resolve("192.168.1.10"))
        assertEquals("http://10.0.0.1:3000/", AddressInput.resolve("10.0.0.1:3000/"))
        assertEquals("http://[::1]:8080/", AddressInput.resolve("[::1]:8080/"))
    }

    @Test
    fun everythingElseIsSearched() {
        assertEquals(bing("hello+world"), AddressInput.resolve("hello world"))
        assertEquals(bing("example.com+login"), AddressInput.resolve("example.com login"))
        assertEquals(bing("kotlin"), AddressInput.resolve("kotlin"))
        assertEquals(bing("%E5%A4%A9%E6%B0%94"), AddressInput.resolve("天气"))
        assertEquals(bing("%E4%B8%AD%E6%96%87.%E7%BD%91%E7%AB%99"), AddressInput.resolve("中文.网站"))
        assertEquals(bing("user%40example.com"), AddressInput.resolve("user@example.com"))
        // 像版本号、小数、越界的 IP、不完整的域名、越界的端口
        assertEquals(bing("1.5"), AddressInput.resolve("1.5"))
        assertEquals(bing("999.1.1.1"), AddressInput.resolve("999.1.1.1"))
        assertEquals(bing("example.c"), AddressInput.resolve("example.c"))
        assertEquals(bing("localhost%3A99999"), AddressInput.resolve("localhost:99999"))
        assertEquals(bing("a%26b%3Dc"), AddressInput.resolve("a&b=c"))
    }

    @Test
    fun searchQueryReadsBackTheWords() {
        assertEquals("天气 预报", AddressInput.searchQuery(AddressInput.search("天气 预报")))
        assertEquals("a&b", AddressInput.searchQuery(AddressInput.search("a&b") + "&form=x"))
        assertEquals(null, AddressInput.searchQuery("https://www.example.com/a/b"))
        assertEquals(null, AddressInput.searchQuery(AddressInput.SEARCH_PREFIX))
    }
}
