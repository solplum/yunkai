package com.wangzan101.mika.core.cap.web.cdp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** 进程内的一对管道:客户端一端是 [CdpStream],服务端一端给假 DevTools 用。 */
class PipeStream(override val input: InputStream, override val output: OutputStream, private val onClose: () -> Unit) : CdpStream {
    override fun close() = onClose()
}

/**
 * 假 DevTools:每次 [open] 起一个服务线程。`/json/...` 走 HTTP;其余路径做 WebSocket 升级,
 * 之后每条文本消息交给 [onMessage](默认回 `{"echo": params}`)。服务端独立实现帧编解码,不复用客户端代码。
 */
class FakeDevTools(
    private val badAccept: Boolean = false,
    private val httpRoutes: Map<String, String> = emptyMap(),
) : CdpTransport {
    val connections = CopyOnWriteArrayList<Conn>()
    val endpoints = CopyOnWriteArrayList<String>()
    @Volatile var error: Throwable? = null
    var onMessage: (Conn, JsonObject) -> Unit = { c, m ->
        c.reply(m, buildJsonObject { put("echo", m["params"] ?: JsonObject(emptyMap())) })
    }

    override fun open(endpoint: String): CdpStream {
        endpoints += endpoint
        val toServer = Pipe.open()
        val toClient = Pipe.open()
        val conn = Conn(Channels.newInputStream(toServer.source()), Channels.newOutputStream(toClient.sink()))
        connections += conn
        conn.closer = {
            runCatching { toServer.source().close() }
            runCatching { toClient.sink().close() }
        }
        thread(isDaemon = true, name = "fake-devtools") {
            try {
                conn.serve()
            } catch (_: IOException) {
            } catch (t: Throwable) {
                error = t
            } finally {
                conn.drop()
            }
        }
        return PipeStream(Channels.newInputStream(toClient.source()), Channels.newOutputStream(toServer.sink())) {
            runCatching { toClient.source().close() }
            runCatching { toServer.sink().close() }
        }
    }

    inner class Conn(input: InputStream, private val out: OutputStream) {
        private val input = DataInputStream(input)
        internal var closer: () -> Unit = {}
        val received = CopyOnWriteArrayList<JsonObject>()
        val pongs = CopyOnWriteArrayList<String>()
        @Volatile var allMasked = true
        @Volatile var sawMaskedBytesDifferent = false
        @Volatile var clientCloseCode: Int? = null
        @Volatile var requestHeaders: Map<String, String> = emptyMap()
        @Volatile var requestLine = ""

        fun serve() {
            requestLine = Http.readLine(input) ?: return
            requestHeaders = Http.readHeaders(input)
            val path = requestLine.split(' ')[1]
            if (path.startsWith("/json")) {
                val body = httpRoutes[path]
                val bytes = (body ?: "not found").toByteArray()
                val status = if (body != null) "200 OK" else "404 Not Found"
                write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray() + bytes)
                return
            }
            val key = requestHeaders.getValue("sec-websocket-key")
            check(Base64.getDecoder().decode(key).size == 16)
            check(requestHeaders["sec-websocket-version"] == "13")
            val accept = if (badAccept) WebSocketConnection.acceptFor("x$key") else WebSocketConnection.acceptFor(key)
            write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
            frameLoop()
        }

        private fun frameLoop() {
            val buf = java.io.ByteArrayOutputStream()
            while (true) {
                val b0 = input.read()
                if (b0 < 0) return
                val b1 = input.readUnsignedByte()
                val opcode = b0 and 0x0f
                if (b1 and 0x80 == 0) allMasked = false
                val len = when (val l = b1 and 0x7f) {
                    126 -> input.readUnsignedShort().toLong()
                    127 -> input.readLong()
                    else -> l.toLong()
                }
                val mask = ByteArray(4).also { if (b1 and 0x80 != 0) input.readFully(it) }
                val raw = ByteArray(len.toInt()).also { input.readFully(it) }
                val payload = ByteArray(raw.size) { (raw[it].toInt() xor mask[it and 3].toInt()).toByte() }
                if (raw.size >= 8 && !raw.contentEquals(payload)) sawMaskedBytesDifferent = true
                when (opcode) {
                    0x8 -> {
                        if (payload.size >= 2) clientCloseCode = ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
                        runCatching { sendFrame(0x8, payload) }
                        return
                    }
                    0x9 -> sendFrame(0xa, payload)
                    0xa -> pongs += payload.toString(Charsets.UTF_8)
                    else -> {
                        buf.write(payload)
                        if (b0 and 0x80 != 0) {
                            val msg = Json.parseToJsonElement(buf.toString(Charsets.UTF_8)).jsonObject
                            buf.reset()
                            received += msg
                            onMessage(this, msg)
                        }
                    }
                }
            }
        }

        fun reply(request: JsonObject, result: JsonElement) = sendJson(
            buildJsonObject {
                put("id", request.getValue("id"))
                put("result", result)
                request["sessionId"]?.let { put("sessionId", it) }
            },
        )

        fun replyError(request: JsonObject, code: Int, message: String) = sendJson(
            buildJsonObject {
                put("id", request.getValue("id"))
                put("error", buildJsonObject { put("code", JsonPrimitive(code)); put("message", JsonPrimitive(message)) })
            },
        )

        fun event(method: String, params: JsonObject = JsonObject(emptyMap()), sessionId: String? = null) = sendJson(
            buildJsonObject {
                put("method", JsonPrimitive(method))
                put("params", params)
                if (sessionId != null) put("sessionId", JsonPrimitive(sessionId))
            },
        )

        fun sendJson(obj: JsonObject) = sendFrame(0x1, obj.toString().toByteArray())

        @Synchronized
        fun sendFrame(opcode: Int, payload: ByteArray, fin: Boolean = true) {
            val n = payload.size
            val header = java.io.ByteArrayOutputStream()
            header.write((if (fin) 0x80 else 0) or opcode)
            when {
                n <= 125 -> header.write(n)
                n <= 0xffff -> { header.write(126); header.write(n shr 8); header.write(n and 0xff) }
                else -> { header.write(127); for (s in 56 downTo 0 step 8) header.write(((n.toLong() shr s) and 0xff).toInt()) }
            }
            write(header.toByteArray() + payload)
        }

        @Synchronized
        fun write(bytes: ByteArray) {
            out.write(bytes)
            out.flush()
        }

        /** 不发关闭帧直接断。 */
        fun drop() = closer()
    }
}

internal fun JsonObject.idOf(): Int = getValue("id").jsonPrimitive.int
