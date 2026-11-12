// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.core.cap.web.cdp

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 阻塞读写都有上限:对端不回、不读时到点关流,调用方不会一直卡住。 */
class CdpDeadlineTest {
    /**
     * 对端:回完握手(可选)之后什么都不发;[blockWrites] 时握手之后的写一直卡住。读写都只在 close 之后才返回(抛错)。
     */
    private class StalledPeer(
        private val answerHandshake: Boolean,
        private val blockWrites: Boolean = false,
        holdReaderAfterClose: Boolean = false,
    ) : CdpStream {
        val closed = CountDownLatch(1)
        val releaseReader = CountDownLatch(if (holdReaderAfterClose) 1 else 0)
        val readerExited = CountDownLatch(1)
        private val request = ByteArrayOutputStream()
        @Volatile private var response: ByteArray? = null
        private var pos = 0

        override val input = object : InputStream() {
            // A live stream returns available bytes; InputStream's default bulk read would
            // wait to fill the entire buffered handshake request before returning.
            override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                bytes[off] = read().toByte()
                var count = 1
                val r = response
                while (count < len && r != null && pos < r.size) bytes[off + count++] = r[pos++]
                return count
            }
            override fun read(): Int {
                while (true) {
                    val r = response
                    if (r != null && pos < r.size) return r[pos++].toInt() and 0xff
                    if (closed.await(10, TimeUnit.MILLISECONDS)) {
                        check(releaseReader.await(10, TimeUnit.SECONDS)) { "test reader gate was not released" }
                        readerExited.countDown()
                        throw IOException("closed")
                    }
                }
            }
        }

        override val output = object : OutputStream() {
            override fun write(b: Int) {
                if (response != null && blockWrites) {
                    closed.await()
                    throw IOException("closed")
                }
                request.write(b)
                val text = request.toString(Charsets.US_ASCII)
                if (answerHandshake && response == null && text.endsWith("\r\n\r\n")) {
                    val key = Regex("Sec-WebSocket-Key: (\\S+)").find(text)!!.groupValues[1]
                    response = ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: ${WebSocketConnection.acceptFor(key)}\r\n\r\n").toByteArray(Charsets.US_ASCII)
                }
            }
        }

        override fun close() = closed.countDown()
    }

    private fun timed(block: () -> Unit): Long {
        val t = System.nanoTime()
        block()
        return (System.nanoTime() - t) / 1_000_000
    }

    @Test fun stalledHandshakeTimesOutAndClosesTheStream() = runBlocking {
        val peer = StalledPeer(answerHandshake = false)
        var error: Throwable? = null
        val ms = timed { runBlocking { error = runCatching { CdpSession.connect({ peer }, "x", "/devtools/page/A", handshakeTimeoutMs = 200) }.exceptionOrNull() } }
        assertTrue("$error", error is CdpTimeoutException)
        assertTrue("took $ms ms", ms < 2_000)
        assertTrue(peer.closed.count == 0L)
    }

    @Test fun stalledDiscoveryTimesOut() = runBlocking {
        val peer = StalledPeer(answerHandshake = false)
        val e = runCatching { CdpDiscovery.list({ peer }, "x", timeoutMs = 200) }.exceptionOrNull()
        assertTrue("$e", e is CdpTimeoutException)
        assertTrue(peer.closed.count == 0L)
    }

    @Test fun cancelledCallerClosesTheStalledStream() = runBlocking {
        val peer = StalledPeer(answerHandshake = false)
        val ms = timed { runBlocking { assertNull(withTimeoutOrNull(150) { CdpSession.connect({ peer }, "x", "/p", handshakeTimeoutMs = 60_000) }) } }
        assertTrue("took $ms ms", ms < 2_000)
        assertTrue(peer.closed.await(2, TimeUnit.SECONDS))
    }

    // Yunkai regression: aborting a stream does not guarantee its reader has returned yet.
    @Test fun failedWritePublishesClosedBeforeDelayedReaderExits() = runBlocking {
        val peer = StalledPeer(answerHandshake = true, blockWrites = true, holdReaderAfterClose = true)
        val s = CdpSession.connect({ peer }, "x", "/devtools/page/A", handshakeTimeoutMs = 2_000)
        try {
            val error = runCatching { s.send("Runtime.evaluate", timeoutMs = 200) }.exceptionOrNull()
            assertTrue("$error", error is CdpConnectionClosedException)
            assertTrue("reader must still be held", peer.readerExited.count == 1L)
            assertTrue("write failure must publish terminal state without help from reader", s.closed.value)
            val subsequent = runCatching { s.send("Page.enable", timeoutMs = 200) }.exceptionOrNull()
            assertTrue("$subsequent", subsequent is CdpConnectionClosedException)
            s.close() // Repeated shutdown must remain safe while the reader is still blocked.
            assertTrue(s.closed.value)
        } finally {
            peer.close()
            peer.releaseReader.countDown()
            assertTrue("reader did not exit", peer.readerExited.await(2, TimeUnit.SECONDS))
        }
    }

    @Test fun writeThatNeverDrainsIsBounded() = runBlocking {
        val peer = StalledPeer(answerHandshake = true, blockWrites = true)
        val s = CdpSession.connect({ peer }, "x", "/devtools/page/A", handshakeTimeoutMs = 2_000)
        var error: Throwable? = null
        val ms = timed { runBlocking { error = runCatching { s.send("Runtime.evaluate", timeoutMs = 200) }.exceptionOrNull() } }
        assertTrue("$error", error is IOException)
        assertTrue("took $ms ms", ms < 2_000)
        assertTrue(s.closed.value)
    }
}
