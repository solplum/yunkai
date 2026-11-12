package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.core.workspace.SandboxError
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** 一行回复;[close] 为 true 时回完就断开连接。 */
class BridgeReply(val json: String, val close: Boolean = false)

/**
 * 沙箱里 `mika` 命令与 Mika 之间的协议:一行一个 JSON 请求 `{"token","method","params"}`,一行一个 JSON 回复。
 * 手机方法只转给当前任务的动作入口;命令进程不能直接接触虚拟屏。
 */
object BridgeProtocol {
    const val MAX_REQUEST = 64 * 1024
    const val MAX_PROGRESS = 300

    sealed interface Line {
        class Text(val text: String) : Line
        data object TooLarge : Line
        data object End : Line
    }

    /** 读一行(不含换行);超过 [max] 字节就不再读下去。末尾没有换行的半行算结束。 */
    fun readLine(input: InputStream, max: Int = MAX_REQUEST): Line {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return Line.End
            if (b == '\n'.code) return Line.Text(buf.toString(Charsets.UTF_8.name()))
            if (buf.size() >= max) return Line.TooLarge
            buf.write(b)
        }
    }

    /**
     * [progress] 返回 false 表示这次被限流丢掉了;[share] 收到沙箱路径与可选的保存名,失败抛 [SandboxError];
     * [info] 给出 Mika 版本、是否联网等。令牌不对、请求不是 JSON 对象或太大:回一个错误并断开。
     */
    suspend fun handle(
        line: String, token: String,
        progress: (String) -> Boolean,
        share: suspend (String, String?) -> String,
        info: () -> JsonObject,
        phone: (suspend (String, JsonObject) -> JsonObject)? = null,
        /** 意外的失败交给 Mika 自己记日志;回给沙箱的只有错误码。 */
        onError: (Throwable) -> Unit = {},
    ): BridgeReply {
        if (line.toByteArray(Charsets.UTF_8).size > MAX_REQUEST) return error("too_large", close = true)
        val req = try { Json.parseToJsonElement(line) as? JsonObject } catch (e: SerializationException) { null }
            ?: return error("bad_request", close = true)
        val given = (req["token"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (given == null || !MessageDigest.isEqual(given.toByteArray(), token.toByteArray())) return error("bad_token", close = true)
        val params = req["params"] as? JsonObject ?: JsonObject(emptyMap())
        fun str(k: String) = (params[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when ((req["method"] as? JsonPrimitive)?.content) {
            "progress" -> {
                val text = str("text")?.let(::cleanProgress)?.takeIf { it.isNotEmpty() } ?: return error("bad_params")
                ok { put("shown", progress(text)) }
            }
            "share" -> {
                val path = str("path") ?: return error("bad_params")
                try {
                    val saved = share(path, str("name"))
                    ok { put("savedAs", saved) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SandboxError) {
                    error(e.code, e.message)
                } catch (e: Exception) {
                    onError(e)
                    error("share_failed")
                }
            }
            "info" -> BridgeReply(JsonObject(mapOf("ok" to JsonPrimitive(true)) + info()).toString())
            "phone" -> {
                val tool = str("tool")?.takeIf { it.length in 1..80 } ?: return error("bad_params")
                val args = params["args"] as? JsonObject ?: return error("bad_params")
                val handler = phone ?: return error("phone_unavailable")
                try {
                    BridgeReply(handler(tool, args).toString())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(e)
                    error("phone_failed")
                }
            }
            else -> error("unknown_method")
        }
    }

    /**
     * 任务卡片上的一行字:去掉控制字符、格式字符(零宽字符、改写方向的字符等,能让显示的字和实际的字对不上)
     * 和行/段分隔符,按码点截到 [MAX_PROGRESS] 个(不劈开代理对)。
     */
    internal fun cleanProgress(s: String): String {
        val out = StringBuilder()
        var count = 0
        var i = 0
        while (i < s.length && count < MAX_PROGRESS) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT.toInt() || cp == 0x2028 || cp == 0x2029) continue
            out.appendCodePoint(cp)
            count++
        }
        return out.toString().trim()
    }

    private fun ok(b: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = BridgeReply(buildJsonObject { put("ok", true); b() }.toString())

    private fun error(code: String, message: String? = null, close: Boolean = false) = BridgeReply(buildJsonObject {
        put("ok", false); put("error", code); message?.let { put("message", it) }
    }.toString(), close)
}

/** 每 [intervalMs] 最多放过一次进度,多余的丢弃。 */
class ProgressGate(private val intervalMs: Long = 1_000, private val now: () -> Long = System::currentTimeMillis) {
    private var last = Long.MIN_VALUE

    @Synchronized fun offer(): Boolean {
        val t = now()
        if (last != Long.MIN_VALUE && t - last < intervalMs) return false
        last = t
        return true
    }
}
