package com.wangzan101.mika.cap.web.cdp

import android.util.Log
import com.wangzan101.mika.core.cap.web.cdp.CdpSession
import com.wangzan101.mika.core.cap.web.CapturedResponse
import com.wangzan101.mika.core.cap.web.NetworkLedger
import com.wangzan101.mika.cap.web.WebTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.WeakHashMap

/**
 * 一个标签的网络记录:第一次用到(动作后的自动等待、web_capture)时才打开 Network,
 * 订阅这个标签会话的 `Network.*` 事件喂给 [NetworkLedger]。会话断了或标签关了就停,下次用到时按新会话重建。
 * 只有一个会话的事件进来,所以不必再按标签筛;子会话(sessionId 非空)的请求按会话分开记。
 */
class NetworkTracker private constructor(
    private val session: CdpSession,
    private val clock: () -> Long,
) {
    private val ledger = NetworkLedger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bodyLock = Mutex()
    @Volatile var enabled = false
        private set

    // 先订阅再发 Network.enable:事件流不重放
    private suspend fun start(): Boolean {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.events.collect { e ->
                if (e.method.startsWith(NETWORK)) synchronized(ledger) { ledger.onEvent(e.method, e.params, clock(), e.sessionId) }
            }
        }
        scope.launch { session.closed.first { it }; close() }
        enabled = try {
            // 渲染进程替 getResponseBody 留的正文也设上限:单个与账本收的上限一致(更大的反正不取);
            // 总量给账本的两倍,图片脚本等小资源也占这块缓冲,留点余地让接口响应不被挤掉
            session.send(
                "Network.enable",
                buildJsonObject {
                    put("maxResourceBufferSize", ledger.maxBodyBytes)
                    put("maxTotalBufferSize", ledger.maxTotalBodyBytes * 2)
                },
                timeoutMs = CALL_TIMEOUT_MS,
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Network.enable failed: ${e.message}")
            false
        }
        if (!enabled) scope.cancel()
        return enabled
    }

    val alive: Boolean get() = enabled && !session.closed.value

    fun markAction() = synchronized(ledger) { ledger.markAction() }

    fun idle(): Boolean = synchronized(ledger) { ledger.isIdle(clock()) }

    /** 本次动作发出的请求还在途(或刚结束);会话断了当作不忙。 */
    fun actionBusy(): Boolean = alive && synchronized(ledger) { ledger.actionBusy(clock()) }

    /** 等到网络空闲(无在途请求且安静 [NetworkLedger.quietMs]),至多 [capMs];到时还不空闲返回 false。 */
    suspend fun awaitIdle(capMs: Long): Boolean {
        val deadline = clock() + capMs
        while (true) {
            if (!alive) return true
            if (idle()) return true
            if (clock() >= deadline) return false
            delay(POLL_MS)
        }
    }

    fun list(urlContains: String?, method: String?, sinceLastAction: Boolean): List<CapturedResponse> =
        synchronized(ledger) { ledger.list(urlContains, method, sinceLastAction) }

    /** 取一个响应;该有正文还没取时用 `Network.getResponseBody` 取回(取不到就不带正文)。 */
    suspend fun get(id: String): CapturedResponse? = bodyLock.withLock {
        val fetch = synchronized(ledger) { ledger.bodyToFetch(id) }
        if (fetch != null && alive) {
            try {
                val r = session.send(
                    "Network.getResponseBody",
                    buildJsonObject { put("requestId", fetch.requestId) },
                    fetch.sessionId,
                    CALL_TIMEOUT_MS,
                )
                val body = (r["body"] as? JsonPrimitive)?.content
                val b64 = (r["base64Encoded"] as? JsonPrimitive)?.boolean == true
                if (body != null) synchronized(ledger) { ledger.putBody(id, body, b64) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 页面已丢掉这个响应(导航后常见)、或调用超时:照常返回,不带正文
                Log.i(TAG, "getResponseBody $id failed: ${e.message}")
            }
        }
        synchronized(ledger) { ledger.get(id) }
    }

    fun close() {
        if (!scope.isActive) return
        val s = session
        if (enabled && !s.closed.value) {
            CoroutineScope(Dispatchers.IO).launch { runCatching { s.send("Network.disable", timeoutMs = CALL_TIMEOUT_MS) } }
        }
        enabled = false
        scope.cancel()
    }

    companion object {
        private const val TAG = "MikaNetTrack"
        private const val NETWORK = "Network."
        private const val POLL_MS = 100L
        private const val CALL_TIMEOUT_MS = 3_000L

        private val trackers = WeakHashMap<WebTab, NetworkTracker>()
        private val creating = Mutex()

        /**
         * 这个标签的网络记录,第一次调用时打开 Network。没有 DevTools 会话、或打开失败时为 null(调用方走老路)。
         * 会话换了(重连)或标签已关时按当前会话重建。
         */
        suspend fun of(tab: WebTab, clock: () -> Long): NetworkTracker? {
            if (tab.destroyed || tab.crashed) {
                release(tab)
                return null
            }
            val session = tab.devtools.session(tab) ?: return null
            return creating.withLock {
                synchronized(trackers) { trackers[tab] }?.takeIf { it.session === session && it.alive }?.let { return@withLock it }
                synchronized(trackers) { trackers.remove(tab) }?.close()
                val t = NetworkTracker(session, clock)
                if (!t.start()) return@withLock null
                synchronized(trackers) { trackers[tab] = t }
                t
            }
        }

        /** 标签关了:停掉它的记录。 */
        fun release(tab: WebTab) {
            synchronized(trackers) { trackers.remove(tab) }?.close()
        }
    }
}
