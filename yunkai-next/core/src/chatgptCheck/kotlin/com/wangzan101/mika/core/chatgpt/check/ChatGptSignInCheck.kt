package com.wangzan101.mika.core.chatgpt.check

import com.wangzan101.mika.core.chatgpt.ChatGptAuth
import com.wangzan101.mika.core.chatgpt.ChatGptSession
import com.wangzan101.mika.core.chatgpt.ChatGptTokens
import com.wangzan101.mika.core.chatgpt.SignInException
import com.wangzan101.mika.core.chatgpt.SignInFailure
import com.wangzan101.mika.core.chatgpt.signInWithChatGpt
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelCatalog
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.ResponsesModel
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * 桌面上把「用 ChatGPT 登录」整条路走一遍:用 App 同一套登录、列模型和 Responses 代码,对着 OpenAI 的真实服务,
 * 好在上手机之前先确认服务端这一侧没问题。用法见 docs/dev/chatgpt-signin-check.md。
 *
 * 本机标识和注册到的 client_id 存在 ~/.mika-dev/chatgpt-check/(目录 0700),多次运行沿用,和 App 一样。
 * 令牌、授权码、ID 令牌一律不打印;默认只在内存里,`--keep` 才写到同一目录的 session.json(0600)。
 *
 * 参数:`--timeout=<秒>` 等回调多久(默认 300);`--model=<slug>` 指定模型;`--keep` 保存登录。
 */
fun main(args: Array<String>) {
    val opts = args.associate { a -> a.removePrefix("--").let { it.substringBefore('=') to it.substringAfter('=', "") } }
    val timeoutSec = opts["timeout"]?.toLongOrNull()?.takeIf { it > 0 } ?: 300L
    val ok = runBlocking { Check(timeoutSec, opts["model"]?.takeIf { it.isNotBlank() }, "keep" in opts).run() }
    exitProcess(if (ok) 0 else 1)
}

private class Check(private val timeoutSec: Long, private val modelArg: String?, private val keep: Boolean) {
    private val dir = File(System.getProperty("user.home"), ".mika-dev/chatgpt-check")
    private val hostFile = File(dir, "host.json")
    private val sessionFile = File(dir, "session.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build()
    private val auth = ChatGptAuth(http)
    private val results = mutableListOf<Pair<String, Boolean>>()

    suspend fun run(): Boolean {
        println("== Mika:用 ChatGPT 登录 · 桌面检查 ==")
        println("工作目录:${dir.absolutePath}(令牌不打印;${if (keep) "--keep:结束时保存登录" else "默认不保存令牌"})")
        prepareDir()
        val (hostId, savedClient) = registration()
        println("本机标识:${if (savedClient == null) "第一次运行,将注册新的 client" else "沿用已注册的 client"}")

        var session = signIn(hostId, savedClient) ?: return summary()
        val tokens = ChatGptTokens(auth, { session }, { session = it })

        val model = listModels(session.accessToken)
        if (model != null) {
            val caps = Capabilities(
                toolCalls = true, vision = model.vision ?: false,
                contextTokens = model.contextTokens ?: 128_000, reasoning = model.reasoning ?: false,
            )
            val responses = ResponsesModel(ChatGptAuth.API_BASE, tokens, model.id, caps, http, chatGptPlan = true)
            simpleCall(responses)
            toolRoundTrip(responses)
        }
        refresh(tokens) { session }
        if (keep) save(session)
        return summary()
    }

    // ---------- 1. 本机标识 ----------

    private fun prepareDir() {
        dir.mkdirs()
        runCatching { Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------")) }
    }

    private fun registration(): Pair<String, String?> {
        val saved = runCatching { json.parseToJsonElement(hostFile.readText()).jsonObject }.getOrNull()
        val host = saved?.get("hostId")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val client = saved?.get("clientId")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        if (host != null) return host to client
        val fresh = ChatGptAuth.newHostId()
        writePrivate(hostFile, buildJsonObject { put("hostId", fresh) }.toString())
        return fresh to null
    }

    private fun rememberClient(hostId: String, clientId: String) {
        writePrivate(hostFile, buildJsonObject { put("hostId", hostId); put("clientId", clientId) }.toString())
        step("注册 client(client_id 已记到 ${hostFile.name})", true)
    }

    // ---------- 2–3. 登录 ----------

    private suspend fun signIn(hostId: String, clientId: String?): ChatGptSession? {
        // 只拿上次的邮箱当登录提示;不带 ID 令牌,打印出来的授权地址里就不会有令牌
        val previous = if (sessionFile.isFile) ChatGptSession.fromJson(sessionFile.readText())?.signedOut() else null
        return try {
            val s = signInWithChatGpt(
                auth, hostId, clientId, previous,
                openBrowser = { url -> openBrowser(url) },
                onRegistered = { rememberClient(hostId, it) },
                page = ::callbackPage,
                timeoutMs = timeoutSec * 1000,
            )
            step("收到回调(state 核对通过)", true)
            step("用授权码换令牌", true)
            step("验 ID 令牌(签名、签发方、受众、过期、nonce)", true)
            step("授予了 ${ChatGptAuth.PLAN_SCOPE}", true)
            println("    授予的权限:${s.scopes.joinToString(" ")}")
            println("    账号:${s.email.ifEmpty { "(ID 令牌里没有邮箱)" }}")
            println("    访问令牌有效期:约 ${(s.expiresAtMs - System.currentTimeMillis()) / 1000} 秒;刷新令牌:${if (s.refreshToken.isNotEmpty()) "有" else "没有"}")
            s
        } catch (e: CancellationException) {
            throw e
        } catch (e: SignInException) {
            step(signInStep(e.reason), false, explain(e))
            null
        } catch (e: Exception) {
            step("登录", false, "${e::class.simpleName}: ${e.message}")
            null
        }
    }

    private fun openBrowser(url: String) {
        println("授权地址(不含令牌):")
        println("    $url")
        val opened = runCatching {
            System.getProperty("os.name").lowercase().contains("mac") &&
                ProcessBuilder("open", url).inheritIO().start().waitFor(10, TimeUnit.SECONDS)
        }.getOrDefault(false)
        step(if (opened) "已在默认浏览器打开授权页" else "没能自动打开浏览器,请手动复制上面的地址打开", opened, null, record = false)
        println("等待回调…(最多 $timeoutSec 秒;Ctrl+C 放弃)")
    }

    private fun callbackPage(params: Map<String, String>): String {
        val text = if (params.containsKey("error")) "已取消登录,可以关掉这个页面。" else "登录完成,可以关掉这个页面,回到终端看结果。"
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>Mika</title></head>" +
            "<body style=\"font-family:system-ui;margin:3em\"><p>$text</p></body></html>"
    }

    private fun signInStep(r: SignInFailure): String = when (r) {
        SignInFailure.Denied, SignInFailure.StateMismatch, SignInFailure.ClientMismatch, SignInFailure.NoClientId -> "核对回调"
        SignInFailure.TimedOut -> "等待回调"
        SignInFailure.Exchange -> "用授权码换令牌"
        SignInFailure.IdToken -> "验 ID 令牌"
        SignInFailure.NoPlanScope -> "授予 ${ChatGptAuth.PLAN_SCOPE}"
        SignInFailure.Network -> "连接 OpenAI 登录服务"
    }

    // ---------- 4. 列模型 ----------

    private suspend fun listModels(accessToken: String): ListedModel? {
        val listed = when (val r = ModelCatalog(http).openAiCompat(ChatGptAuth.API_BASE, accessToken)) {
            is ModelListResult.Ok -> r.models
            is ModelListResult.Failed -> {
                step("列模型(GET /v1/models)", false, r.message)
                return null
            }
        }
        step("列模型(GET /v1/models):${listed.size} 个", listed.isNotEmpty(), if (listed.isEmpty()) "列表是空的" else null)
        listed.forEach { m ->
            val flags = listOfNotNull(
                m.contextTokens?.let { "上下文 $it" }, m.vision?.let { if (it) "看图" else "不看图" },
                m.reasoning?.takeIf { it }?.let { "推理" }, m.toolCalls?.let { if (it) "工具" else "无工具" },
            )
            println("    ${m.id}${m.displayName.takeIf { it.isNotEmpty() && it != m.id }?.let { "  「$it」" }.orEmpty()}" +
                if (flags.isEmpty()) "" else "  (${flags.joinToString(",")})")
        }
        val chosen = if (modelArg != null) {
            listed.firstOrNull { it.id == modelArg } ?: ListedModel(modelArg).also { println("    注意:--model=$modelArg 不在列表里,照样试") }
        } else {
            // 优先挑推理模型:工具调用那一步要顺带验加密思考的回放
            listed.firstOrNull { it.usable && it.reasoning == true && it.toolCalls != false } ?: listed.firstOrNull { it.usable }
        }
        chosen?.let { println("    用模型:${it.id}") }
        return chosen
    }

    // ---------- 5. 一次简单调用 ----------

    private suspend fun simpleCall(model: ResponsesModel) {
        val name = "Responses 调用"
        try {
            val reply = model.complete("", listOf(Message(Role.User, listOf(Part.Text("Reply with the single word: pong")), Author.User)), emptyList())
            val text = reply.text()
            step(name, text.isNotBlank(), if (text.isBlank()) "回答是空的" else null)
            println("    回答:${text.trim().take(200)}")
            println("    ${usage(reply.usage)}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            step(name, false, explain(e))
        }
    }

    // ---------- 6. 工具调用往返 ----------

    private suspend fun toolRoundTrip(model: ResponsesModel) {
        val tool = ToolSpec(
            "get_time", "Returns the current local time as an ISO-8601 string.",
            """{"type":"object","properties":{},"additionalProperties":false}""",
        )
        val system = "You can call tools. When asked for the time, call get_time, then answer with the time it returned."
        val history = mutableListOf(Message(Role.User, listOf(Part.Text("What time is it now? Use the get_time tool.")), Author.User))
        try {
            val first = model.complete(system, history, listOf(tool))
            val call = first.parts.filterIsInstance<Part.ToolCall>().firstOrNull()
            if (call == null) {
                step("工具调用:模型调用 get_time", false, "模型没有调用工具,直接回答了:${first.text().take(120)}")
                return
            }
            step("工具调用:模型调用了 ${call.name}(参数 ${call.argsJson.take(80)})", call.name == tool.name)
            println("    加密思考:${if (call.opaque != null) "有,下一轮原样回放(store:false)" else "没有(模型没给或不是推理模型)"}")
            println("    ${usage(first.usage)}")
            val now = OffsetDateTime.now().withNano(0).toString()
            history += Message(Role.Assistant, first.parts, Author.Mika)
            history += Message(Role.Tool, listOf(Part.ToolResult(call.id, """{"time":"$now"}""", isError = false)), Author.Mika)
            val second = model.complete(system, history, listOf(tool))
            val text = second.text()
            val more = second.parts.any { it is Part.ToolCall }
            step("工具调用:收到 function_call_output 后作答", text.isNotBlank() && !more,
                when { more -> "模型又调用了工具"; text.isBlank() -> "回答是空的"; else -> null })
            println("    回答:${text.trim().take(300)}")
            println("    ${usage(second.usage)}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            step("工具调用往返", false, explain(e))
        }
    }

    // ---------- 7. 换新令牌 ----------

    private suspend fun refresh(tokens: ChatGptTokens, current: () -> ChatGptSession) {
        val before = current()
        try {
            tokens.get(fresh = true)
            val after = current()
            step("用刷新令牌换新令牌", true)
            println("    访问令牌${if (after.accessToken != before.accessToken) "已更新" else "没变"};" +
                "刷新令牌${if (after.refreshToken != before.refreshToken) "已轮换" else "没变"};" +
                "新的有效期约 ${(after.expiresAtMs - System.currentTimeMillis()) / 1000} 秒")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            step("用刷新令牌换新令牌", false, explain(e))
        }
    }

    private fun save(session: ChatGptSession) {
        writePrivate(sessionFile, session.toJson())
        println("已保存登录到 ${sessionFile.absolutePath}(0600)")
    }

    // ---------- 输出 ----------

    private fun step(name: String, ok: Boolean, why: String? = null, record: Boolean = true) {
        println("${if (ok) "✓" else "✗"} $name")
        if (!ok && why != null) println("    原因:$why")
        if (record) results += name to ok
    }

    private fun summary(): Boolean {
        val failed = results.filterNot { it.second }
        println()
        println("== 小结:${results.size - failed.size}/${results.size} 步通过 ==")
        failed.forEach { println("✗ ${it.first}") }
        return failed.isEmpty() && results.isNotEmpty()
    }

    private fun ModelReply.text() = parts.filterIsInstance<Part.Text>().joinToString("") { it.text }

    private fun usage(u: Usage?) = u?.let { "用量:输入 ${it.inputTokens}(缓存 ${it.cachedInputTokens}),输出 ${it.outputTokens}" } ?: "用量:服务端没给"

    private fun writePrivate(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }
}

/** 把已知的错误说成人话。 */
private fun explain(e: Throwable): String = when (e) {
    is SignInException -> when (e.reason) {
        SignInFailure.Denied -> "授权页返回了错误(${e.message?.substringAfter(": ", "")}):" +
            "access_denied 是在授权页点了取消;授权页若写着「所需权限不可用」,说明账号不是 Plus 或 Pro,不能把套餐额度给其他 App"
        SignInFailure.StateMismatch -> "回调里的 state 和这次发起的不一致(不是这次登录的回调)"
        SignInFailure.ClientMismatch -> "回调给出的 client_id 和保存的不一致:删掉 ~/.mika-dev/chatgpt-check/host.json 里的 clientId 后重来"
        SignInFailure.NoClientId -> "第一次注册,回调里却没有给出 client_id"
        SignInFailure.TimedOut -> "等回调超时:浏览器里没有完成登录,或回调没回到 127.0.0.1"
        SignInFailure.Exchange -> "换令牌被拒(${e.message?.substringAfter(": ", "")}):授权码过期或已用过、redirect_uri 或 PKCE 不符"
        SignInFailure.IdToken -> "ID 令牌没通过校验(${e.message?.substringAfter(": ", "")})"
        SignInFailure.NoPlanScope -> "没授予 ${ChatGptAuth.PLAN_SCOPE}:这个账号的套餐不能在其他 App 里用额度(需要 Plus 或 Pro),或授权时没勾选"
        SignInFailure.Network -> "连不上 OpenAI 登录服务(${e.message?.substringAfter(": ", "")})"
    }
    is ModelError.ChatGptNotEligible -> "subscription_sharing_user_not_eligible:这个账号不能在其他 App 里用 ChatGPT 额度(套餐、地区或账号策略不允许)" +
        e.detail.takeIf { it.isNotEmpty() }?.let { ";服务端说:$it" }.orEmpty()
    is ModelError.ChatGptLimitReached -> "usage_limit_exceeded:ChatGPT 套餐给其他 App 的额度到了上限,等恢复后再试(用量见 ${ChatGptAuth.USAGE_URL})"
    is ModelError.SignInExpired -> "刷新令牌已不能用(被注销、过期或被重复使用),要重新登录"
    is ModelError.Auth -> "令牌被拒或客户端配置不对(401/403、client_id 不认)"
    is ModelError.ModelNotFound -> "model_not_found:这个账号用不了这个模型,换一个(--model=…)"
    is ModelError.ContextTooLong -> "context_length_exceeded"
    is ModelError.Network -> "网络连接失败:${e.cause?.message.orEmpty()}"
    is ModelError.Server -> "服务端出错(HTTP ${e.status}),稍后再试"
    is ModelError.BadResponse -> if (e.message.orEmpty().contains("服务端不支持请求里的")) {
        "unsupported_capability:${e.message?.substringAfter("服务端不支持请求里的 ")?.let { "请求里的 `$it` 这一项不被支持" }}"
    } else e.message.orEmpty()
    else -> "${e::class.simpleName}: ${e.message}"
}
