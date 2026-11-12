package com.wangzan101.mika.core.cap.web

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class NetworkLedgerTest {
    private val l = NetworkLedger(quietMs = 500, staleMs = 10_000, maxCaptured = 3, maxBodyBytes = 100, maxTotalBodyBytes = 250)

    private fun sent(id: String, url: String, type: String = "Fetch", method: String = "GET", redirect: Int? = null) = buildJsonObject {
        put("requestId", id); put("type", type)
        putJsonObject("request") { put("url", url); put("method", method) }
        if (redirect != null) putJsonObject("redirectResponse") { put("status", redirect); put("url", "x") }
    }

    private fun resp(id: String, status: Int = 200, mime: String = "application/json", type: String = "Fetch", diskCache: Boolean = false) = buildJsonObject {
        put("requestId", id); put("type", type)
        putJsonObject("response") { put("url", "u"); put("status", status); put("mimeType", mime); put("fromDiskCache", diskCache) }
    }

    private fun id(id: String) = buildJsonObject { put("requestId", id) }

    private fun finished(id: String, encoded: Long = 10) = buildJsonObject { put("requestId", id); put("encodedDataLength", encoded) }
    private fun data(id: String, n: Int) = buildJsonObject { put("requestId", id); put("dataLength", n); put("encodedDataLength", n) }
    private fun failed(id: String, err: String = "net::ERR_FAILED", canceled: Boolean = false) =
        buildJsonObject { put("requestId", id); put("errorText", err); put("canceled", canceled) }

    private fun fetch(id: String, url: String, now: Long, body: Int = 20, mime: String = "application/json") {
        l.onEvent("Network.requestWillBeSent", sent(id, url), now)
        l.onEvent("Network.responseReceived", resp(id, mime = mime), now)
        l.onEvent("Network.dataReceived", data(id, body), now)
        l.onEvent("Network.loadingFinished", finished(id), now)
    }

    @Test fun idleOnlyAfterQuietWindowWithNothingInFlight() {
        assertTrue(l.isIdle(0))
        l.onEvent("Network.requestWillBeSent", sent("1", "https://a.test/api"), 100)
        assertEquals(1, l.inFlight(200))
        assertFalse(l.isIdle(2000))
        l.onEvent("Network.loadingFinished", finished("1"), 1000)
        assertEquals(0, l.inFlight(1000))
        assertFalse(l.isIdle(1400))
        assertTrue(l.isIdle(1500))
    }

    @Test fun failuresEndTheRequestAndAreRecorded() {
        l.onEvent("Network.requestWillBeSent", sent("1", "https://a.test/api", method = "POST"), 0)
        l.onEvent("Network.loadingFailed", failed("1", "net::ERR_CONNECTION_REFUSED"), 50)
        assertEquals(0, l.inFlight(60))
        val c = l.get("1")!!
        assertEquals(CapturedResponse.State.FAILED, c.state)
        assertEquals("net::ERR_CONNECTION_REFUSED", c.error)
        assertEquals("POST", c.method)
        l.onEvent("Network.requestWillBeSent", sent("2", "https://a.test/b"), 0)
        l.onEvent("Network.loadingFailed", failed("2", "", canceled = true), 50)
        assertEquals("canceled", l.get("2")!!.error)
    }

    @Test fun redirectKeepsOneRequestAndEndsAtFinalUrl() {
        l.onEvent("Network.requestWillBeSent", sent("1", "https://a.test/old"), 0)
        l.onEvent("Network.requestWillBeSent", sent("1", "https://a.test/new", redirect = 302), 100)
        assertEquals(1, l.inFlight(150))
        l.onEvent("Network.responseReceived", resp("1"), 200)
        l.onEvent("Network.loadingFinished", finished("1"), 250)
        assertEquals(0, l.inFlight(300))
        assertEquals(1, l.list().size)
        assertEquals("https://a.test/new", l.get("1")!!.url)
        assertEquals(200, l.get("1")!!.status)
    }

    @Test fun cachedResponsesAreMarked() {
        l.onEvent("Network.requestWillBeSent", sent("1", "https://a.test/c"), 0)
        l.onEvent("Network.requestServedFromCache", id("1"), 1)
        l.onEvent("Network.responseReceived", resp("1"), 2)
        l.onEvent("Network.loadingFinished", finished("1", 0), 3)
        assertTrue(l.get("1")!!.fromCache)
        l.onEvent("Network.requestWillBeSent", sent("2", "https://a.test/d"), 0)
        l.onEvent("Network.responseReceived", resp("2", diskCache = true), 2)
        assertTrue(l.get("2")!!.fromCache)
    }

    @Test fun longLivedRequestsDoNotBlockIdle() {
        l.onEvent("Network.requestWillBeSent", sent("es", "https://a.test/stream", type = "EventSource"), 0)
        l.onEvent("Network.requestWillBeSent", sent("m", "https://a.test/v.mp4", type = "Media"), 0)
        l.onEvent("Network.requestWillBeSent", sent("b", "https://a.test/beacon", type = "Ping"), 0)
        assertEquals(0, l.inFlight(10))
        // 响应是事件流的请求也算长连接
        l.onEvent("Network.requestWillBeSent", sent("sse", "https://a.test/sse"), 0)
        assertEquals(1, l.inFlight(10))
        l.onEvent("Network.responseReceived", resp("sse", mime = "text/event-stream"), 100)
        assertEquals(0, l.inFlight(110))
        // 长轮询:超过 staleMs 还没回的不算在途
        l.onEvent("Network.requestWillBeSent", sent("poll", "https://a.test/poll"), 1000)
        assertEquals(1, l.inFlight(5000))
        assertEquals(0, l.inFlight(11_001))
        assertTrue(l.isIdle(11_001))
    }

    @Test fun onlyXhrAndFetchAreCaptured() {
        l.onEvent("Network.requestWillBeSent", sent("doc", "https://a.test/", type = "Document"), 0)
        l.onEvent("Network.requestWillBeSent", sent("img", "https://a.test/a.png", type = "Image"), 0)
        assertEquals(2, l.inFlight(1))
        fetch("x", "https://a.test/api", 5)
        assertEquals(listOf("x"), l.list().map { it.id })
        l.onEvent("Network.requestWillBeSent", sent("x2", "https://a.test/xhr", type = "XHR"), 6)
        assertEquals(listOf("x", "x2"), l.list().map { it.id })
    }

    @Test fun ringEvictsOldest() {
        for (i in 1..5) fetch("$i", "https://a.test/$i", i.toLong())
        assertEquals(listOf("3", "4", "5"), l.list().map { it.id })
        assertNull(l.get("1"))
    }

    @Test fun filtersByUrlMethodAndSinceLastAction() {
        fetch("1", "https://a.test/api/user", 0)
        l.onEvent("Network.requestWillBeSent", sent("2", "https://a.test/api/cart", method = "POST"), 1)
        l.markAction()
        fetch("3", "https://a.test/api/cart", 2)
        assertEquals(listOf("2", "3"), l.list(urlContains = "cart").map { it.id })
        assertEquals(listOf("2"), l.list(method = "post").map { it.id })
        assertEquals(listOf("3"), l.list(sinceLastAction = true).map { it.id })
    }

    @Test fun bodiesOnlyForFinishedTextWithinCap() {
        fetch("1", "https://a.test/j", 0, body = 20)
        assertEquals(NetworkLedger.BodyFetch("1", null), l.bodyToFetch("1"))
        fetch("2", "https://a.test/img", 0, mime = "image/png")
        assertNull(l.bodyToFetch("2"))
        assertEquals("not_text", l.get("2")!!.bodyNote)
        fetch("3", "https://a.test/big", 0, body = 500)
        assertNull(l.bodyToFetch("3"))
        assertEquals("too_large", l.get("3")!!.bodyNote)
        l.onEvent("Network.requestWillBeSent", sent("4", "https://a.test/p"), 0)
        assertNull(l.bodyToFetch("4"))
        assertEquals("not_finished", l.get("4")!!.bodyNote)
    }

    @Test fun storesDecodedBodyAndCapsTotal() {
        val l = NetworkLedger(maxCaptured = 10, maxBodyBytes = 100, maxTotalBodyBytes = 250)
        for (i in 1..4) {
            l.onEvent("Network.requestWillBeSent", sent("$i", "https://a.test/$i"), 0)
            l.onEvent("Network.responseReceived", resp("$i"), 0)
            l.onEvent("Network.loadingFinished", finished("$i"), 0)
        }
        l.putBody("1", Base64.getEncoder().encodeToString("{\"a\":\"中\"}".toByteArray()), base64Encoded = true)
        assertEquals("{\"a\":\"中\"}", l.get("1")!!.body)
        l.putBody("2", "x".repeat(150), base64Encoded = false)
        assertEquals(100, l.get("2")!!.body!!.length)
        assertTrue(l.get("2")!!.bodyTruncated)
        l.putBody("3", "y".repeat(90), base64Encoded = false)
        l.putBody("4", "z".repeat(90), base64Encoded = false)
        // 总量超了:最早取到的正文先丢,丢了还能再取
        assertNull(l.get("1")!!.body)
        assertEquals(NetworkLedger.BodyFetch("1", null), l.bodyToFetch("1"))
        assertTrue(l.totalBodyBytes() <= 250)
        assertEquals(90, l.get("4")!!.body!!.length)
    }

    @Test fun childSessionsAreKeptApart() {
        l.onEvent("Network.requestWillBeSent", sent("1", "https://a.test/main"), 0)
        l.onEvent("Network.requestWillBeSent", sent("1", "https://b.test/frame"), 0, sessionId = "S")
        assertEquals(2, l.inFlight(1))
        l.onEvent("Network.responseReceived", resp("1"), 2, sessionId = "S")
        l.onEvent("Network.loadingFinished", finished("1"), 2, sessionId = "S")
        assertEquals(1, l.inFlight(3))
        assertEquals(NetworkLedger.BodyFetch("1", "S"), l.bodyToFetch("S:1"))
    }

    @Test fun unknownOrMalformedEventsAreIgnored() {
        l.onEvent("Network.loadingFinished", finished("nope"), 0)
        l.onEvent("Network.requestWillBeSent", buildJsonObject { put("x", 1) }, 0)
        l.onEvent("Page.frameNavigated", buildJsonObject { }, 0)
        assertEquals(0, l.inFlight(1))
        assertTrue(l.list().isEmpty())
    }
}
