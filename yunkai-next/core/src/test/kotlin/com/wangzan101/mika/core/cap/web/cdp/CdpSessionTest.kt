package com.wangzan101.mika.core.cap.web.cdp

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64
import kotlin.concurrent.thread

class CdpSessionTest {
    private fun test(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    private suspend fun session(server: FakeDevTools, maxMessageBytes: Int = WebSocketConnection.DEFAULT_MAX_MESSAGE_BYTES) =
        CdpSession.connect(server, "webview_devtools_remote_1", "/devtools/page/A", maxMessageBytes = maxMessageBytes)

    private inline fun <reified T : Throwable> assertFails(block: () -> Unit): T {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return t
            throw AssertionError("expected ${T::class.simpleName}, got $t", t)
        }
        fail("expected ${T::class.simpleName}")
        throw IllegalStateException()
    }

    @Test
    fun handshakeAndRoundTrip() = test {
        val server = FakeDevTools()
        val s = session(server)
        val r = s.send("Browser.getVersion", buildJsonObject { put("a", 1) })
        assertEquals(1, r.getValue("echo").jsonObject.getValue("a").jsonPrimitive.int)
        val conn = server.connections.single()
        assertEquals("GET /devtools/page/A HTTP/1.1", conn.requestLine)
        assertEquals("localhost", conn.requestHeaders["host"])
        assertEquals("webview_devtools_remote_1", server.endpoints.single())
        s.close()
    }

    @Test
    fun badAcceptRejected() = test {
        val server = FakeDevTools(badAccept = true)
        val e = assertFails<WebSocketException> { session(server) }
        assertTrue(e.message!!.contains("Accept"))
    }

    @Test
    fun clientFramesAreMasked() = test {
        val server = FakeDevTools()
        val s = session(server)
        s.send("Runtime.evaluate", buildJsonObject { put("expression", "1 + 1 is two") })
        val conn = server.connections.single()
        assertTrue(conn.allMasked)
        assertTrue(conn.sawMaskedBytesDifferent)
        s.close()
    }

    @Test
    fun fragmentedMessageWithInterleavedPing() = test {
        val server = FakeDevTools()
        server.onMessage = { c, m ->
            val bytes = """{"id":${m.idOf()},"result":{"value":"hello fragmented world"}}""".toByteArray()
            c.sendFrame(0x1, bytes.copyOfRange(0, 10), fin = false)
            c.sendFrame(0x9, "p1".toByteArray())
            c.sendFrame(0x0, bytes.copyOfRange(10, 20), fin = false)
            c.sendFrame(0x0, bytes.copyOfRange(20, bytes.size), fin = true)
        }
        val s = session(server)
        val r = s.send("X.y")
        assertEquals("hello fragmented world", r.getValue("value").jsonPrimitive.content)
        val conn = server.connections.single()
        withTimeout(5_000) { while (conn.pongs.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals(listOf("p1"), conn.pongs.toList())
        s.close()
    }

    @Test
    fun tenMegabyteMessagesBothWays() = test {
        val big = "x".repeat(10 * 1024 * 1024)
        val server = FakeDevTools()
        server.onMessage = { c, m ->
            val got = m.getValue("params").jsonObject.getValue("blob").jsonPrimitive.content
            c.reply(m, buildJsonObject { put("len", got.length); put("blob", big) })
        }
        val s = session(server)
        val r = s.send("Big.call", buildJsonObject { put("blob", big) })
        assertEquals(big.length, r.getValue("len").jsonPrimitive.int)
        assertEquals(big.length, r.getValue("blob").jsonPrimitive.content.length)
        s.close()
    }

    @Test
    fun oversizedMessageClosesConnection() = test {
        val server = FakeDevTools()
        server.onMessage = { c, m -> c.reply(m, buildJsonObject { put("blob", "y".repeat(100_000)) }) }
        val s = session(server, maxMessageBytes = 50_000)
        val e = assertFails<CdpConnectionClosedException> { s.send("Big.call") }
        assertTrue(e.message, e.message!!.contains("larger than"))
        assertTrue(s.closed.value)
    }

    @Test
    fun concurrentSendsCorrelate() = test {
        val server = FakeDevTools()
        val held = ArrayList<JsonObject>()
        server.onMessage = { c, m ->
            // 攒够一批后倒序回复
            synchronized(held) {
                held += m
                if (held.size == 50) {
                    held.reversed().forEach { c.reply(it, buildJsonObject { put("n", it.getValue("params").jsonObject.getValue("n")) }) }
                    held.clear()
                }
            }
        }
        val s = session(server)
        val results = (0 until 50).map { n -> async { s.send("Echo.n", buildJsonObject { put("n", n) }) } }.awaitAll()
        results.forEachIndexed { n, r -> assertEquals(n, r.getValue("n").jsonPrimitive.int) }
        s.close()
    }

    @Test
    fun timeoutLeavesSessionUsable() = test {
        val server = FakeDevTools()
        server.onMessage = { c, m -> if (m.getValue("method").jsonPrimitive.content != "Hang.forever") c.reply(m, JsonObject(emptyMap())) }
        val s = session(server)
        val e = assertFails<CdpTimeoutException> { s.send("Hang.forever", timeoutMs = 200) }
        assertEquals("Hang.forever", e.method)
        s.send("Page.enable")
        assertFalse(s.closed.value)
        s.close()
    }

    @Test
    fun errorResponseBecomesCdpException() = test {
        val server = FakeDevTools()
        server.onMessage = { c, m -> c.replyError(m, -32601, "'Foo.bar' wasn't found") }
        val s = session(server)
        val e = assertFails<CdpException> { s.send("Foo.bar") }
        assertEquals(-32601, e.code)
        assertEquals("'Foo.bar' wasn't found", e.errorMessage)
        assertEquals("Foo.bar", e.method)
        s.close()
    }

    @Test
    fun eventsDeliveredInOrder() = test {
        val server = FakeDevTools()
        server.onMessage = { c, m ->
            repeat(200) { c.event("Network.requestWillBeSent", buildJsonObject { put("i", it) }) }
            c.reply(m, JsonObject(emptyMap()))
        }
        val s = session(server)
        val collected = async(start = CoroutineStart.UNDISPATCHED) { s.events.take(200).toList() }
        s.enableNetwork()
        val events = collected.await()
        assertEquals((0 until 200).toList(), events.map { it.params.getValue("i").jsonPrimitive.int })
        assertTrue(events.all { it.method == "Network.requestWillBeSent" && it.sessionId == null })
        s.close()
    }

    @Test
    fun flattenedSessionRoutesBySessionId() = test {
        val server = FakeDevTools()
        server.onMessage = { c, m ->
            val sid = m["sessionId"]?.jsonPrimitive?.content
            if (sid != null) c.event("Runtime.consoleAPICalled", buildJsonObject { put("from", sid) }, sessionId = sid)
            c.reply(m, buildJsonObject { put("sid", JsonPrimitive(sid)) })
        }
        val s = session(server)
        val child = async(start = CoroutineStart.UNDISPATCHED) { s.events.filter { it.sessionId == "S1" }.first() }
        val r = s.send("Runtime.enable", sessionId = "S1")
        assertEquals("S1", r.getValue("sid").jsonPrimitive.content)
        assertEquals("S1", child.await().params.getValue("from").jsonPrimitive.content)
        assertEquals("S1", server.connections.single().received.single()["sessionId"]!!.jsonPrimitive.content)
        assertNull(s.send("Browser.getVersion")["sid"]!!.jsonPrimitive.let { if (it.isString) it.content else null })
        s.close()
    }

    @Test
    fun serverCloseFailsPendingCalls() = test {
        val server = FakeDevTools()
        server.onMessage = { c, _ -> c.sendFrame(0x8, byteArrayOf(0x03, 0xe9.toByte()) + "going away".toByteArray()) }
        val s = session(server)
        val e = assertFails<CdpConnectionClosedException> { s.send("Page.navigate") }
        assertTrue(e.message, e.message!!.contains("1001") && e.message!!.contains("going away"))
        assertTrue(s.closed.value)
        assertFails<CdpConnectionClosedException> { s.send("Page.reload") }
    }

    @Test
    fun droppedConnectionFailsAllPending() = test {
        val server = FakeDevTools()
        server.onMessage = { c, _ -> if (c.received.size == 3) thread { Thread.sleep(50); c.drop() } }
        val s = session(server)
        val calls = (0 until 3).map { async { runCatching { s.send("Slow.call") } } }
        val results = calls.awaitAll()
        assertTrue(results.all { it.exceptionOrNull() is CdpConnectionClosedException })
        s.closed.first { it }
    }

    @Test
    fun clientCloseSendsCloseFrame() = test {
        val server = FakeDevTools()
        val s = session(server)
        s.send("Page.enable")
        s.close()
        assertTrue(s.closed.value)
        val conn = server.connections.single()
        withTimeout(5_000) { while (conn.clientCloseCode == null) kotlinx.coroutines.delay(10) }
        assertEquals(1000, conn.clientCloseCode)
        assertFails<CdpConnectionClosedException> { s.send("Page.enable") }
    }

    @Test
    fun typedHelpers() = test {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        val server = FakeDevTools()
        server.onMessage = { c, m ->
            when (m.getValue("method").jsonPrimitive.content) {
                "Runtime.evaluate" -> {
                    val expr = m.getValue("params").jsonObject.getValue("expression").jsonPrimitive.content
                    if (expr == "boom") {
                        c.reply(m, buildJsonObject {
                            put("result", buildJsonObject { put("type", "object") })
                            put("exceptionDetails", buildJsonObject { put("text", "Uncaught"); put("exception", buildJsonObject { put("description", "Error: boom") }) })
                        })
                    } else {
                        c.reply(m, buildJsonObject { put("result", buildJsonObject { put("type", "number"); put("value", 2) }) })
                    }
                }
                "DOM.getDocument" -> c.reply(m, buildJsonObject { put("root", buildJsonObject { put("nodeId", 1) }) })
                "Accessibility.getFullAXTree" -> c.reply(m, kotlinx.serialization.json.Json.parseToJsonElement("""{"nodes":[{"nodeId":"1"},{"nodeId":"2"}]}"""))
                "Page.captureScreenshot" -> c.reply(m, buildJsonObject { put("data", Base64.getEncoder().encodeToString(png)) })
                else -> c.reply(m, JsonObject(emptyMap()))
            }
        }
        val s = session(server)
        assertEquals(2, s.evaluate("1+1").getValue("value").jsonPrimitive.int)
        val e = assertFails<CdpEvaluationException> { s.evaluate("boom") }
        assertEquals("Error: boom", e.message)
        assertEquals(1, s.getDocument().getValue("nodeId").jsonPrimitive.int)
        assertEquals(2, s.getFullAXTree().size)
        assertArrayEquals(png, s.captureScreenshot())
        s.click(10.0, 20.0)
        s.enableNetwork()
        val sent = server.connections.single().received
        val mouse = sent.filter { it.getValue("method").jsonPrimitive.content == "Input.dispatchMouseEvent" }
        assertEquals(listOf("mousePressed", "mouseReleased"), mouse.map { it.getValue("params").jsonObject.getValue("type").jsonPrimitive.content })
        assertEquals("left", mouse[0].getValue("params").jsonObject.getValue("button").jsonPrimitive.content)
        s.close()
    }
}
