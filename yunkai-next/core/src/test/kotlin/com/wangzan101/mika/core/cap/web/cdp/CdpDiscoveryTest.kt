package com.wangzan101.mika.core.cap.web.cdp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CdpDiscoveryTest {
    private val version = """
        {
           "Android-Package": "com.wangzan101.mika",
           "Browser": "Chrome/120.0.6099.230",
           "Protocol-Version": "1.3",
           "User-Agent": "Mozilla/5.0 (Linux; Android 14; wv)",
           "V8-Version": "12.0.267.17",
           "WebKit-Version": "537.36",
           "webSocketDebuggerUrl": "ws://localhost/devtools/browser"
        }
    """.trimIndent()

    private val list = """
        [ {
           "description": "{\"attached\":true,\"visible\":true}",
           "devtoolsFrontendUrl": "https://example.invalid/inspector.html?ws=localhost/devtools/page/AB12",
           "id": "AB12",
           "title": "Example 页面",
           "type": "page",
           "url": "https://example.com/",
           "webSocketDebuggerUrl": "ws://localhost/devtools/page/AB12"
        }, {
           "id": "CD34",
           "title": "",
           "type": "iframe",
           "url": "about:blank"
        } ]
    """.trimIndent()

    @Test
    fun parsesVersionAndList() {
        val v = CdpDiscovery.parseVersion(version)
        assertEquals("Chrome/120.0.6099.230", v.browser)
        assertEquals("1.3", v.protocolVersion)
        assertEquals("ws://localhost/devtools/browser", v.webSocketDebuggerUrl)

        val targets = CdpDiscovery.parseList(list)
        assertEquals(listOf("AB12", "CD34"), targets.map { it.id })
        assertEquals("Example 页面", targets[0].title)
        assertEquals("page", targets[0].type)
        assertNull(targets[1].webSocketDebuggerUrl)
        assertEquals("/devtools/page/AB12", CdpDiscovery.webSocketPath(targets[0].webSocketDebuggerUrl!!))
        assertEquals("/", CdpDiscovery.webSocketPath("ws://localhost"))
    }

    @Test
    fun fetchesOverTransport() = runBlocking {
        val server = FakeDevTools(httpRoutes = mapOf("/json/version" to version, "/json/list" to list))
        assertEquals("1.3", CdpDiscovery.version(server, "ep").protocolVersion)
        assertEquals(2, CdpDiscovery.list(server, "ep").size)
        assertEquals("GET /json/list HTTP/1.1", server.connections.last().requestLine)
        assertEquals("localhost", server.connections.last().requestHeaders["host"])
        val e = runCatching { CdpDiscovery.get(server, "ep", "/json/nope") }.exceptionOrNull()
        assertTrue(e is IOException && e.message!!.contains("404"))
    }

    @Test
    fun chunkedBody() {
        val body = "4\r\nabcd\r\n6\r\nefghij\r\n0\r\n\r\n".byteInputStream()
        assertEquals("abcdefghij", Http.readChunked(body).toString(Charsets.UTF_8))
    }
}
