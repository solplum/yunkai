package com.wangzan101.mika.cap.web.cdp

import com.wangzan101.mika.core.cap.web.cdp.CdpException
import com.wangzan101.mika.core.cap.web.cdp.CdpSession
import com.wangzan101.mika.core.cap.web.cdp.CdpTimeoutException
import com.wangzan101.mika.core.cap.web.cdp.mainFrameNavigated
import com.wangzan101.mika.core.cap.web.DEVTOOLS_UNAVAILABLE
import com.wangzan101.mika.core.cap.web.PageScriptProtocol
import com.wangzan101.mika.core.cap.web.ScriptAbort
import com.wangzan101.mika.core.cap.web.ScriptBridge
import com.wangzan101.mika.core.cap.web.ScriptCall
import com.wangzan101.mika.core.cap.web.ScriptRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 在一个标签的主框架里建隔离世界跑页面脚本(web_script)。
 * 发件箱只在这个隔离世界里,页面自己的脚本看不到;模型代码在里面拿不到任何页面对象(见 [PageScriptProtocol]),
 * 脚本的每个 `mika.*` 调用放进发件箱,由一直挂着的取件求值([PageScriptProtocol.TAKE])交回来,一次一个交给 [ScriptBridge](Mika 的动作路径),结果用 `Runtime.evaluate` 在同一个世界里回传。
 * 兜底:脚本发起的网络请求、没有 mika 动作在做时主框架的跳转(后者先 `Page.stopLoading`)都让脚本以错误结束。
 * 整页跳走会销毁隔离世界,脚本就停在那里(返回 [ScriptRun.Navigated])。
 * 时限只算脚本自己的时间:mika 动作在做(含等锁屏关卡)时不走表;到时限先等手上的动作做完再收尾。
 * [preflight] 每个 mika 调用之前查一次标签(接管中、崩溃),不为 null 就结束脚本。
 */
class PageScriptRunner(
    private val session: CdpSession,
    private val sessionId: String? = null,
    private val preflight: suspend () -> ScriptRun.Failed? = { null },
) {

    suspend fun run(code: String, timeoutMs: Long, bridge: ScriptBridge): ScriptRun = coroutineScope {
        val world = PageScriptProtocol.WORLD_PREFIX + UUID.randomUUID()
        val outcome = CompletableDeferred<ScriptRun>()
        val calls = Channel<ScriptCall>(Channel.UNLIMITED)
        // 拿着它时有一个动作在做;收尾前要等它做完,动作不能做到一半被打断
        val busy = Mutex()
        val context = AtomicInteger(-1)
        val gone = AtomicBoolean(false)
        val mainFrame = AtomicReference<String?>(null)
        val reader = AtomicInteger(-1)

        // 先订阅再建世界:事件流不重放
        launch(start = CoroutineStart.UNDISPATCHED) {
            session.events.collect { e ->
                if (e.sessionId != sessionId) return@collect
                val p = e.params
                when (e.method) {
                    "Runtime.executionContextDestroyed" -> if (p.int("executionContextId") == context.get()) gone.set(true)
                    "Runtime.executionContextsCleared" -> gone.set(true)
                    // 不开 Runtime 时没有上下文销毁的事件:主框架换了文档,世界就没了
                    "Page.frameNavigated" -> if (mainFrameNavigated(p, mainFrame.get())) gone.set(true)
                    "Network.requestWillBeSent" -> if (context.get() >= 0 && PageScriptProtocol.requestFromScript(p)) {
                        outcome.complete(ScriptRun.Failed(BLOCKED_NETWORK, "the script sent a network request (${p.requestUrl()}); scripts may only read the page"))
                    }
                    "Page.frameRequestedNavigation" -> {
                        val frame = mainFrame.get() ?: return@collect
                        if (context.get() >= 0 && !outcome.isCompleted && PageScriptProtocol.navigationOutsideAction(p, frame, busy.isLocked)) {
                            launch { runCatching { session.send("Page.stopLoading", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS) } }
                            outcome.complete(ScriptRun.Failed(BLOCKED_NAVIGATION, "the page tried to navigate to ${p.str("url").orEmpty()} while no mika action was running; it was stopped. Use mika.navigate or mika.tap to move between pages"))
                        }
                    }
                }
            }
        }

        try {
            val contextId = try {
                setUp(world).let { w -> mainFrame.set(w.frameId); reader.set(w.reader); context.set(w.script); w.script }
            } catch (e: CdpException) {
                return@coroutineScope ScriptRun.Failed(DEVTOOLS_UNAVAILABLE, "could not prepare the page for scripts: ${e.errorMessage}")
            } catch (e: CdpTimeoutException) {
                return@coroutineScope ScriptRun.Failed(DEVTOOLS_UNAVAILABLE, "the page did not answer in time")
            }

            // 一直挂着一个取件:脚本的 mika 调用一条条交回来;收尾(__mikaEnd)交回 null,世界没了求值失败,都就此停
            launch {
                while (!outcome.isCompleted) {
                    val r = try {
                        session.send("Runtime.evaluate", buildJsonObject {
                            put("expression", PageScriptProtocol.TAKE); put("contextId", contextId)
                            put("awaitPromise", true); put("returnByValue", true)
                        }, sessionId, Long.MAX_VALUE)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        break
                    }
                    val payload = ((r["result"] as? JsonObject)?.get("value") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: break
                    val call = PageScriptProtocol.parseCall(payload) ?: continue
                    if (call.op == "log") call.args.str("text")?.let(bridge::log) else calls.trySend(call)
                }
            }

            launch {
                outcome.complete(try {
                    // 不设上限:时限由下面的表管(动作在做时不走表)
                    val r = session.send("Runtime.evaluate", buildJsonObject {
                        put("expression", PageScriptProtocol.run(code)); put("contextId", contextId)
                        put("awaitPromise", true); put("returnByValue", true)
                    }, sessionId, Long.MAX_VALUE)
                    val details = r["exceptionDetails"] as? JsonObject
                    when {
                        gone.get() -> ScriptRun.Navigated
                        details != null -> PageScriptProtocol.error(details).let { (m, line) -> ScriptRun.Failed("script_error", m, line) }
                        else -> ScriptRun.Done((r["result"] as? JsonObject)?.get("value")?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content)
                    }
                } catch (e: CdpException) {
                    // 世界随整页跳转没了,脚本的 Promise 也就没了
                    if (gone.get() || CONTEXT_GONE.containsMatchIn(e.errorMessage)) ScriptRun.Navigated else ScriptRun.Failed("script_error", e.errorMessage)
                })
            }

            launch {
                for (c in calls) {
                    val stop = busy.withLock {
                        if (outcome.isCompleted) return@withLock true
                        preflight()?.let { outcome.complete(it); return@withLock true }
                        val result = try {
                            bridge.call(c.op, c.args).let { r -> (r["read"] as? JsonObject)?.let { readPage(reader.get(), it) } ?: r }
                        } catch (a: ScriptAbort) {
                            outcome.complete(ScriptRun.Failed(a.error, a.message.orEmpty()))
                            return@withLock true
                        }
                        if (gone.get()) { outcome.complete(ScriptRun.Navigated); return@withLock true }
                        try {
                            session.send("Runtime.evaluate", buildJsonObject {
                                put("expression", PageScriptProtocol.settle(c.id, result)); put("contextId", contextId)
                            }, sessionId, STEP_TIMEOUT_MS)
                            false
                        } catch (e: CdpException) {
                            outcome.complete(ScriptRun.Navigated)
                            true
                        } catch (e: CdpTimeoutException) {
                            outcome.complete(ScriptRun.Failed("timeout", "the page stopped answering"))
                            true
                        }
                    }
                    if (stop) break
                }
            }

            // 只在没有动作在做时走表
            launch {
                var left = timeoutMs
                while (left > 0) {
                    delay(TICK_MS)
                    if (!busy.isLocked) left -= TICK_MS
                }
                outcome.complete(ScriptRun.Failed("timeout", "the script did not finish within $timeoutMs ms"))
            }

            val result = outcome.await()
            busy.withLock { }
            result
        } finally {
            withContext(NonCancellable) {
                if (context.get() >= 0 && !gone.get()) {
                    val ended = try {
                        session.send("Runtime.evaluate", buildJsonObject { put("expression", PageScriptProtocol.END); put("contextId", context.get()) }, sessionId, STEP_TIMEOUT_MS)
                        true
                    } catch (e: CdpTimeoutException) {
                        false
                    } catch (e: Exception) {
                        true
                    }
                    // 收尾都等不到回应:脚本在跑同步死循环,占着页面。只在这时打断(没在跑时打断会误伤页面下一段脚本)
                    if (!ended) runCatching { session.send("Runtime.terminateExecution", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS) }
                }
            }
            coroutineContext.cancelChildren()
        }
    }

    private class Worlds(val frameId: String, val script: Int, val reader: Int)

    /** 在读取世界里照校验过的参数读 DOM,只拿回纯数据。 */
    private suspend fun readPage(ctx: Int, spec: JsonObject): JsonObject {
        val r = session.send("Runtime.evaluate", buildJsonObject {
            put("expression", PageScriptProtocol.read(spec)); put("contextId", ctx); put("returnByValue", true)
        }, sessionId, STEP_TIMEOUT_MS)
        val v = (r["result"] as? JsonObject)?.get("value") as? JsonObject
        if (r["exceptionDetails"] != null || v == null) return buildJsonObject { put("ok", false); put("error", "read_failed"); put("message", "the page could not be read") }
        if (v.toString().length > READ_RESULT_MAX_CHARS) return buildJsonObject {
            put("ok", false); put("error", "too_large"); put("message", "the result is too large; ask for fewer items (limit), fewer fields or a smaller maxChars")
        }
        return v
    }

    /**
     * 建两个隔离世界:脚本世界(发件箱、`mika`、跑模型代码,拿不到页面对象)和读取世界(只有 Mika 固定的读取器)。
     */
    private suspend fun setUp(world: String): Worlds {
        // 不开 Runtime:开着它时页面能察觉到调试器(有的网站因此一直重载);隔离世界和求值都不需要它
        // 兜底要看的事件:跳转请求、网络请求(已开着的再开一次无妨)
        session.send("Page.enable", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS)
        session.send("Network.enable", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS)
        val frameId = session.send("Page.getFrameTree", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS)["frameTree"]
            ?.jsonObject?.get("frame")?.jsonObject?.get("id")?.jsonPrimitive?.content
            ?: throw CdpException(0, "no main frame", "Page.getFrameTree")
        val ctx = session.send("Page.createIsolatedWorld", buildJsonObject {
            put("frameId", frameId); put("worldName", world)
            // 协议里的参数名就是这么拼的
            put("grantUniveralAccess", false)
        }, sessionId, STEP_TIMEOUT_MS)["executionContextId"]?.jsonPrimitive?.intOrNull
            ?: throw CdpException(0, "no execution context", "Page.createIsolatedWorld")
        install(ctx, PageScriptProtocol.PRELUDE)
        val readerCtx = session.send("Page.createIsolatedWorld", buildJsonObject {
            put("frameId", frameId); put("worldName", PageScriptProtocol.READER_WORLD_PREFIX + world.removePrefix(PageScriptProtocol.WORLD_PREFIX))
            put("grantUniveralAccess", false)
        }, sessionId, STEP_TIMEOUT_MS)["executionContextId"]?.jsonPrimitive?.intOrNull
            ?: throw CdpException(0, "no execution context", "Page.createIsolatedWorld")
        install(readerCtx, PageScriptProtocol.READER)
        return Worlds(frameId, ctx, readerCtx)
    }

    /** 装不上就不跑模型代码。 */
    private suspend fun install(ctx: Int, source: String) {
        val r = session.send("Runtime.evaluate", buildJsonObject {
            put("expression", source); put("contextId", ctx); put("returnByValue", true)
        }, sessionId, STEP_TIMEOUT_MS)
        (r["exceptionDetails"] as? JsonObject)?.let { throw CdpException(0, PageScriptProtocol.error(it).first, "Runtime.evaluate") }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.requestUrl() = ((this["request"] as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull?.take(200).orEmpty()

    private companion object {
        const val STEP_TIMEOUT_MS = 3_000L
        const val TICK_MS = 100L
        const val READ_RESULT_MAX_CHARS = 200_000
        const val BLOCKED_NETWORK = "blocked_network"
        const val BLOCKED_NAVIGATION = "blocked_navigation"
        val CONTEXT_GONE = Regex("context|destroyed|Inspected target navigated", RegexOption.IGNORE_CASE)
    }
}
