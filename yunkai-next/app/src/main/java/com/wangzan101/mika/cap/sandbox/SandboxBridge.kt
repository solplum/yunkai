package com.wangzan101.mika.cap.sandbox

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import kotlin.concurrent.thread

private const val NAME_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
private const val MAX_CONNECTIONS = 4
/** 连上来不说话、或说一半就停的客户端,30 秒后断开。 */
private const val CLIENT_TIMEOUT_MS = 30_000
private const val WAKE_DEADLINE_MS = 1_000L
private const val WAKE_RETRY_MS = 50L
private const val TAG = "SandboxBridge"

/**
 * 沙箱命令回调 Mika 的桥:每次运行开一个抽象命名空间的 Unix 套接字(名字随机)和一次性令牌,经环境变量交给命令,
 * 命令结束(含超时、被杀、取消)即关闭。断网过滤只拦 AF_INET/AF_INET6,所以断网时桥照样能用。
 * 只接受与 Mika 同一身份(uid)的连接;协议见 [BridgeProtocol]。
 */
class SandboxBridge(
    private val onProgress: (String) -> Unit,
    private val onShare: suspend (sandboxPath: String, name: String?) -> String,
    private val info: () -> JsonObject = { JsonObject(emptyMap()) },
    private val onPhone: (suspend (String, JsonObject) -> JsonObject)? = null,
) {
    fun open(): Session {
        val rnd = SecureRandom()
        val name = "mika-bridge-" + String(CharArray(16) { NAME_CHARS[rnd.nextInt(NAME_CHARS.length)] })
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(rnd::nextBytes))
        return Session(name, token, LocalServerSocket(name), onProgress, onShare, info, onPhone)
    }

    class Session internal constructor(
        val name: String, val token: String,
        private val server: LocalServerSocket,
        private val onProgress: (String) -> Unit,
        private val onShare: suspend (String, String?) -> String,
        private val info: () -> JsonObject,
        private val onPhone: (suspend (String, JsonObject) -> JsonObject)?,
    ) : Closeable {
        @Volatile private var closed = false
        private val clients = Collections.synchronizedSet(mutableSetOf<LocalSocket>())
        private val gate = ProgressGate()
        /** 进行中的导出都在这里;会话关闭时一起取消,不再往下写。 */
        private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        /** 工具上下文属于同一个任务,来自多个 CLI 连接的手机请求顺序执行。 */
        private val phoneLock = Mutex()
        private val acceptor = thread(isDaemon = true, name = "mika-bridge-accept") { acceptLoop() }

        private fun acceptLoop() {
            while (!closed) {
                val s = try { server.accept() } catch (e: IOException) { break }
                if (closed) { runCatching { s.close() }; break }
                // 同一身份才接;连接太多就拒(一条命令用不着很多)
                val uid = runCatching { s.peerCredentials.uid }.getOrNull()
                if (uid != Process.myUid() || clients.size >= MAX_CONNECTIONS) { runCatching { s.close() }; continue }
                runCatching { s.soTimeout = CLIENT_TIMEOUT_MS }
                clients += s
                thread(isDaemon = true, name = "mika-bridge-conn") { serve(s) }
            }
        }

        private fun serve(s: LocalSocket) {
            try {
                val input = s.inputStream.buffered()
                val output = s.outputStream
                while (!closed) {
                    val reply = when (val line = BridgeProtocol.readLine(input)) {
                        BridgeProtocol.Line.End -> break
                        BridgeProtocol.Line.TooLarge -> BridgeReply("""{"ok":false,"error":"too_large"}""", close = true)
                        is BridgeProtocol.Line.Text -> runBlocking {
                            BridgeProtocol.handle(line.text, token, ::progress, ::share, info, ::phone) { Log.w(TAG, "bridge request failed", it) }
                        }
                    }
                    output.write((reply.json + "\n").toByteArray(Charsets.UTF_8))
                    output.flush()
                    if (reply.close) break
                }
            } catch (_: IOException) {
                // 对端断开或会话关闭
            } catch (_: CancellationException) {
                // 会话关闭时取消了进行中的导出
            } finally {
                clients -= s
                runCatching { s.close() }
            }
        }

        private suspend fun share(path: String, name: String?): String {
            if (closed) throw CancellationException("bridge session closed")
            return work.async { onShare(path, name) }.await()
        }

        private suspend fun phone(tool: String, args: JsonObject): JsonObject {
            if (closed) throw CancellationException("bridge session closed")
            val handler = onPhone ?: return kotlinx.serialization.json.buildJsonObject {
                put("ok", false); put("error", "phone_unavailable")
            }
            return work.async { phoneLock.withLock { handler(tool, args) } }.await()
        }

        private fun progress(text: String): Boolean {
            if (closed || !gate.offer()) return false
            onProgress(text)
            return true
        }

        override fun close() {
            if (closed) return
            closed = true
            work.cancel()
            // accept() 不一定因 close 返回:自己连上去把它叫醒(连不上就重试),到期限为止;再关
            val deadline = System.currentTimeMillis() + WAKE_DEADLINE_MS
            while (acceptor.isAlive && System.currentTimeMillis() < deadline) {
                runCatching { LocalSocket().use { it.connect(LocalSocketAddress(name)) } }
                acceptor.join(WAKE_RETRY_MS)
            }
            runCatching { server.close() }
            synchronized(clients) { clients.toList() }.forEach { c ->
                runCatching { c.shutdownInput() }
                runCatching { c.close() }
            }
        }
    }
}
