package com.wangzan101.mika.cap.web

import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.wangzan101.mika.core.cap.web.PageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.coroutines.resume

/** 按编号做点击前的核对:点得到([x],[y] 为顶层视口 CSS 像素)、被别的元素盖住、在视口外、元素已不在。 */
sealed interface HitResult {
    data class Hit(val x: Int, val y: Int) : HitResult
    data class Obscured(val byName: String) : HitResult
    data object Offscreen : HitResult
    data object Gone : HitResult
}

/** 障碍判定用:页面上有没有密码框,跨源框架的完整地址。 */
data class PageFacts(val passwordField: Boolean, val frameUrls: List<String>)

/** 输入框的当前值;密码框只给长度。 */
sealed interface FieldValue {
    data class Text(val value: String) : FieldValue
    data class Password(val length: Int) : FieldValue
    data object NotAField : FieldValue
}

/**
 * 页面脚本 assets/web/page.js 的 App 端:注入、调用、轮询异步结果。模型写的脚本不经这里
 * (读脚本、页面脚本各在自己的隔离世界里跑),这里只调用我们自己的固定代码。
 * 系统支持时在每个文档开始时注入(先于页面自己的脚本,原始入口不会被页面先改掉),否则在加载完成后注入。
 * 页面上只有一个随机名字的桥函数,要用只有这里知道的密钥才能取到接口;页面拿不到任何通往 App 的入口,
 * 结果全部由这里经 evaluateJavascript 取回。主文档换了而脚本不在时,下次调用自动补注入。
 */
/** [useDocumentStart] 为 false 时只在加载完成后注入(系统不支持文档开始注入时就是这样;测试也用它)。 */
class PageScript(private val tab: WebTab, private val useDocumentStart: Boolean = true) {
    /** 页面上桥函数的名字;加载完成后注入时每个文档换一个(连同密钥),文档开始注入时固定用注册时的那一对。 */
    @Volatile internal var bridgeName = newBridgeName()
        private set
    @Volatile private var secret = UUID.randomUUID().toString()
    private val source: String by lazy {
        tab.view.context.assets.open(ASSET).bufferedReader().use { it.readText() }
    }
    private val bootstrap: String get() = "(\n$source\n)(${quote(bridgeName)},${quote(secret)})"
    private var documentStartRegistered = false

    /** 是否走文档开始注入;为 false 时页面脚本可能先于本脚本改动全局对象。 */
    var documentStart: Boolean = false
        private set

    /** 注册文档开始注入(只做一次),并在当前文档里还没有时注入。 */
    suspend fun install() {
        withContext(Dispatchers.Main) {
            if (!documentStartRegistered) {
                documentStartRegistered = true
                if (useDocumentStart && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    WebViewCompat.addDocumentStartJavaScript(tab.view, bootstrap, setOf("*"))
                    documentStart = true
                }
            }
        }
        if (!present()) inject()
    }

    private suspend fun present(): Boolean =
        raw("(function(){var g=window[${quote(bridgeName)}];return typeof g==='function'&&!!g(${quote(secret)});})()") == "true"

    // 加载完成后注入:每个新文档用新的名字和密钥,上一个文档里泄露的名字对新文档没用
    private suspend fun inject() {
        if (!documentStart) {
            bridgeName = newBridgeName()
            secret = UUID.randomUUID().toString()
        }
        raw(bootstrap)
    }

    /**
     * 视口内可见元素的 JSON(PageTree 的输入格式);页面读不到时给空树。
     * 编号表按 [key](快照号)存,页面里保留最近几张;[key] 为 null 时只读不登记。
     */
    suspend fun tree(key: String? = DEFAULT_KEY): String =
        call("tree(${key?.let(::quote) ?: "null"})")?.takeIf { it is JsonObject }?.toString() ?: EMPTY_TREE

    /**
     * 经 DevTools 在页面主环境里求值的表达式:返回编号表 [key](null 为最近一次读屏)里的元素数组,脚本不在时为 null。
     * 只给 DevTools 用(返回的是元素对象),不经 [call]。
     */
    internal fun refNodesExpression(key: String?): String =
        "(function(){var g=window[${quote(bridgeName)}];var m=typeof g==='function'&&g(${quote(secret)});" +
            "return m?m.refNodes(${key?.let(::quote) ?: "null"}):null;})()"

    suspend fun facts(): PageFacts {
        val r = call("facts()") as? JsonObject ?: return PageFacts(false, emptyList())
        val frames = (r["frames"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        return PageFacts(r["password"]?.jsonPrimitive?.booleanOrNull == true, frames)
    }

    suspend fun mutationCount(): Long = (call("mutationCount()") as? JsonPrimitive)?.long ?: 0L

    /** 先把元素滚进视口中央,再给它可见部分的中心(顶层视口 CSS 像素);元素已不在为 null。 */
    suspend fun centerOf(ref: String, key: String = DEFAULT_KEY): Pair<Int, Int>? = pair(call("centerOf(${quote(ref)},${quote(key)})"))

    suspend fun hitTest(ref: String, key: String = DEFAULT_KEY): HitResult {
        val r = call("hitTest(${quote(ref)},${quote(key)})") as? JsonObject ?: return HitResult.Gone
        return when (r["k"]?.jsonPrimitive?.content) {
            "hit" -> HitResult.Hit(r["x"]!!.jsonPrimitive.int, r["y"]!!.jsonPrimitive.int)
            "obscured" -> HitResult.Obscured(r["by"]?.jsonPrimitive?.content.orEmpty())
            "offscreen" -> HitResult.Offscreen
            else -> HitResult.Gone
        }
    }

    /** [ref] 为 null 时读当前焦点所在的输入框。 */
    /** 页面里一个 blob: 网址的内容;读不出、超过 [maxBytes] 时给原因。 */
    sealed interface Blob {
        class Ok(val mime: String?, val bytes: ByteArray) : Blob
        class Failed(val error: String) : Blob
    }

    /**
     * 让页面脚本把 blob: 内容读成 base64,分段取回再解码。页面报的长度与每段内容都按 [BlobChunks] 的上限核;
     * 等结果超时、出错都让页面丢掉这份内容(页面那边另有到期自清)。
     */
    suspend fun readBlob(url: String, maxBytes: Long): Blob {
        val token = nextToken()
        try {
            if (call("startBlob(${quote(token)},${quote(url)},$maxBytes)") == null) return Blob.Failed(BlobChunks.UNREADABLE)
            val meta = poll(token, BLOB_TIMEOUT_MS) as? JsonObject ?: return Blob.Failed(BlobChunks.UNREADABLE)
            if ((meta["ok"] as? JsonPrimitive)?.booleanOrNull != true) return Blob.Failed((meta["error"] as? JsonPrimitive)?.content ?: BlobChunks.UNREADABLE)
            val length = (meta["length"] as? JsonPrimitive)?.intOrNull ?: return Blob.Failed(BlobChunks.UNREADABLE)
            val chunks = BlobChunks(length, maxBytes)
            chunks.refusal()?.let { return Blob.Failed(it) }
            while (!chunks.complete) {
                val part = (call("blobChunk(${quote(token)},${chunks.received})") as? JsonPrimitive)?.content ?: return Blob.Failed(BlobChunks.UNREADABLE)
                if (part.isEmpty()) break
                chunks.add(part)?.let { return Blob.Failed(it) }
            }
            return when (val o = chunks.finish()) {
                is BlobChunks.Outcome.Ok -> Blob.Ok((meta["mime"] as? JsonPrimitive)?.content?.ifBlank { null }, o.bytes)
                is BlobChunks.Outcome.Failed -> Blob.Failed(o.error)
            }
        } finally {
            call("blobDone(${quote(token)})")
        }
    }

    /**
     * 页面在指向 [url] 的链接上给的下载名(`<a download="…">`):点击时页面脚本记下的,或文档里指向它的链接上的。
     * 没有为 null。名字是网页给的,由下载那边按文件名规则清洗。很长的 data: 网址不拿去比。
     */
    suspend fun downloadName(url: String): String? {
        if (url.length > MAX_NAMED_URL_CHARS) return null
        return (call("downloadName(${quote(url)})") as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    /** 主文档响应的 HTTP 状态;拿不到为 null。 */
    suspend fun navStatus(): Int? = (call("navStatus()") as? JsonPrimitive)?.intOrNull

    /** 当前焦点是不是这个元素(或在它这个可编辑区里)。 */
    suspend fun isActive(ref: String, key: String = DEFAULT_KEY): Boolean =
        (call("isActive(${quote(ref)},${quote(key)})") as? JsonPrimitive)?.booleanOrNull == true

    suspend fun fieldValue(ref: String?, key: String = DEFAULT_KEY): FieldValue {
        val r = call("fieldValue(${ref?.let(::quote) ?: "null"},${quote(key)})") as? JsonObject ?: return FieldValue.NotAField
        return when (r["k"]?.jsonPrimitive?.content) {
            "password" -> FieldValue.Password(r["len"]!!.jsonPrimitive.int)
            "text" -> FieldValue.Text(r["v"]?.jsonPrimitive?.content.orEmpty())
            else -> FieldValue.NotAField
        }
    }

    /**
     * 整页可读文字(PageText 的 JSON)。[scroll] 为真时读第一页前先往下滚至多 5 屏触发懒加载,读完复位;
     * 核实结果时用 false,不碰页面的滚动位置。
     */
    suspend fun readPage(maxChars: Int, offset: Int, scroll: Boolean = true): String {
        val token = nextToken()
        val max = maxChars.coerceIn(1, MAX_READ_CHARS)
        if (call("startRead(${quote(token)},$max,${offset.coerceAtLeast(0)},$scroll)") == null) return error("read_failed", "page script unavailable")
        val r = poll(token, READ_TIMEOUT_MS)
        if (r == null) {
            call("discard(${quote(token)})")
            return error("read_failed", "page did not finish reading")
        }
        return r.toString()
    }

    /** web_read_page 的整页读取(分段、可折叠的列表块、质量迹象;格式见 PageDigest),不滚动;读不到为 null。 */
    suspend fun digest(): JsonObject? {
        val token = nextToken()
        if (call("startDigest(${quote(token)},${PageDigest.scriptOptions})") == null) return null
        val r = poll(token, READ_TIMEOUT_MS)
        if (r == null) call("discard(${quote(token)})")
        return (r as? JsonObject)?.takeIf { it["error"] == null }
    }

    /** 正文的(长度, 摘要),判断正文稳定用;读不到为 null。 */
    suspend fun contentSample(): Pair<Int, Int>? = runCatching { pair(call("contentSample()")) }.getOrNull()

    /**
     * 往下滚几屏触发懒加载,滚完才返回;之后必须调 [endLazy] 复位滚动位置。返回令牌;页面脚本不在时为 null。
     */
    suspend fun startLazy(): String? {
        val token = nextToken()
        if (call("startLazy(${quote(token)})") == null) return null
        poll(token, LAZY_TIMEOUT_MS)
        return token
    }

    suspend fun endLazy(token: String) {
        call("endLazy(${quote(token)})")
    }

    /** 滚动位置(CSS 像素);[ref] 为 null 时是页面本身。元素已不在为 null。 */
    suspend fun scrollState(ref: String?, key: String = DEFAULT_KEY): Pair<Int, Int>? =
        pair(call("scrollState(${ref?.let(::quote) ?: "null"},${quote(key)})"))

    private suspend fun poll(token: String, timeoutMs: Long): JsonElement? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val r = call("take(${quote(token)})")
            if (r != null && r !is JsonNull) return r
            if (tab.crashed) return null
            delay(POLL_MS)
        }
        return null
    }

    /** 调用页面脚本的一个方法;脚本不在(主文档换过)时注入后重试一次。取不到结果为 null。 */
    private suspend fun call(expr: String): JsonElement? {
        repeat(2) { attempt ->
            val js = "(function(){var g=window[${quote(bridgeName)}];var m=typeof g==='function'&&g(${quote(secret)});" +
                "if(!m)return {missing:1};return {v:m.$expr};})()"
            val r = raw(js) ?: return null
            if (r == "null") return null
            val o = runCatching { Json.parseToJsonElement(r) }.getOrNull() as? JsonObject ?: return null
            if (o["missing"] == null) return o["v"] ?: JsonNull
            if (attempt == 0) inject()
        }
        return null
    }

    /** evaluateJavascript 的原始回调字符串;超时或标签已作废为 null。 */
    private suspend fun raw(js: String, timeoutMs: Long = CALL_TIMEOUT_MS): String? {
        if (tab.crashed) return null
        return withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { c -> tab.view.evaluateJavascript(js) { c.resume(it) } }
            }
        }
    }

    private fun pair(e: JsonElement?): Pair<Int, Int>? {
        val a = e as? JsonArray ?: return null
        if (a.size != 2) return null
        return a[0].jsonPrimitive.int to a[1].jsonPrimitive.int
    }

    private fun nextToken() = UUID.randomUUID().toString()

    private fun error(code: String, message: String) = buildJsonObject {
        put("ok", false)
        put("error", code)
        put("message", message)
    }.toString()

    private fun quote(s: String) = JsonPrimitive(s).toString()

    companion object {
        private fun newBridgeName() = "_" + UUID.randomUUID().toString().replace("-", "").take(16)
        private const val ASSET = "web/page.js"
        private const val MAX_NAMED_URL_CHARS = 100_000
        /** 不指定快照号时页面脚本用的编号表。 */
        const val DEFAULT_KEY = "default"
        private const val MAX_READ_CHARS = 30_000
        private const val READ_TIMEOUT_MS = 15_000L
        /** 滚几屏、每屏等一会儿,正常两秒内滚完;页面卡住时不为它拖住读页。 */
        private const val LAZY_TIMEOUT_MS = 5_000L
        private const val BLOB_TIMEOUT_MS = 30_000L
        private const val CALL_TIMEOUT_MS = 5_000L
        private const val POLL_MS = 100L
        private const val EMPTY_TREE = """{"els":[],"vw":0,"vh":0,"dpr":1,"trunc":false,"mut":0}"""
    }
}
