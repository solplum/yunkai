package com.wangzan101.mika.cap.web

import com.wangzan101.mika.core.workspace.SandboxTree
import android.util.Log
import com.wangzan101.mika.core.workspace.GatedFile
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import com.wangzan101.mika.core.cap.web.DownloadAddressPolicy
import com.wangzan101.mika.core.cap.web.DownloadInfo
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 网页下载:文件落在本对话工作区的 downloads/ 下(沙箱里看到的是 /workspace/downloads/<名字>)。
 * 工作区只是 App 私有目录里的一个文件夹,下载不依赖沙箱。下载的文件只保存,不打开、不安装(APK 也一样)。
 *
 * - 名字取 Content-Disposition(优先 filename*),否则取页面在链接上给的名字(`<a download>`,由页面脚本问来),再否则取网址最后一段
 *   (blob:/data: 的网址不算);去掉路径、控制字符与双向控制符、开头的点,
 *   至多 80 字、按字节截短(保留扩展名),空名用 download,重名加「 (2)」。名字是网页给的,交给模型时标明是网页数据。
 * - 落盘经 [gate]:在 downloads/ 里新建(从不覆盖已有的名字),按句柄核实后在同一个句柄上写;失败只删这个文件本身。
 *   downloads/ 被换成链接(沙箱里的命令做得到)时拒绝,标 unsafe_path。
 * - 请求带上该网址的 Cookie(每次跳转都按跳转后的地址取)、UA 与来源页;只取 http/https。
 *   每一跳都过 [addressRefusal](与 web_navigate 同一条规则,另外不许本机与局域网),跳到不许的地址标 blocked_redirect;
 *   域名解析到内网也拒绝([dns])。
 * - 单文件上限 [maxBytes],超出中止、删掉半截文件、标 too_large;blob:/data: 的内容上限 [maxBlobBytes]。
 * - 一个任务至多 [maxFiles] 个文件、共 [maxTotalBytes](download_limit);一次下载(含所有跳转)至多 [timeoutMs](timed_out)。
 */
class WebDownloads(
    private val workspaceDir: File,
    private val gate: WorkspaceFileGate,
    /** 工作区所在那棵树的锁:下载落盘在它的许可里写。 */
    private val tree: SandboxTree,
    http: OkHttpClient,
    private val cookies: (String) -> String?,
    private val scope: CoroutineScope,
    private val maxBytes: Long = 200L shl 20,
    private val maxBlobBytes: Long = 20L shl 20,
    private val maxFiles: Int = 50,
    private val maxTotalBytes: Long = 1L shl 30,
    private val timeoutMs: Long = 10 * 60_000L,
    /** 不能取的原因(null = 能取);测试里放行本机的测试服务器。 */
    private val addressRefusal: (String) -> String? = DownloadAddressPolicy::refusal,
    dns: Dns = DownloadAddressPolicy.guardedDns(),
) {
    private class Entry(val file: String, val mime: String?) {
        @Volatile var bytes = 0L
        @Volatile var status = RUNNING
        @Volatile var error: String? = null
        /** 写入用的句柄(已过关口核实);结束(完成或失败)时关掉。 */
        var gated: GatedFile? = null
        /** 占着任务额度(一个文件 + [charged] 个已写的字节)。 */
        var counted = false
        var charged = 0L
    }

    private val entries = mutableListOf<Entry>()
    /** 额度:占着的文件数与已写字节数,都在 synchronized(entries) 里改。 */
    private var files = 0
    private var totalBytes = 0L

    private val client = http.newBuilder()
        // 跳转自己一跳一跳走,每一跳都查地址
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(dns)
        // 跳转后的每一次请求都按它自己的地址带 Cookie
        .addNetworkInterceptor { chain ->
            val req = chain.request()
            val c = cookies(req.url.toString())
            chain.proceed(if (c.isNullOrEmpty()) req else req.newBuilder().header("Cookie", c).build())
        }
        .build()

    /** 正在问页面要名字、还没登记的下载。 */
    private val naming = AtomicInteger()

    /**
     * 页面给下载起的名字(`<a download>`)只在页面里,下载监听拿不到:先用 [pageName] 问页面,问到(或问不到)后交给 [then] 登记。
     * 问名字的这一会儿也算下载已开始,[awaitNamed] 等它们登记完。[then] 在写 `files/sandbox/` 的许可里跑(见 SandboxTree)。
     */
    fun withPageName(pageName: suspend () -> String?, then: (String?) -> Unit) {
        naming.incrementAndGet()
        scope.launch {
            try {
                val name = try { pageName() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                tree.shared { then(name) }
            } finally {
                naming.decrementAndGet()
            }
        }
    }

    /** 等正在问名字的下载都登记上,至多 [capMs]。 */
    suspend fun awaitNamed(capMs: Long) {
        withTimeoutOrNull(capMs) { while (naming.get() > 0) delay(NAMING_POLL_MS) }
    }

    /**
     * 开始下载 [url];立即返回沙箱路径,状态先是 running。[suggested] 是页面给的名字。
     * [viaPage]:自己因 TLS / 握手一类的错误连不上时(还没写进任何字节)改由页面用浏览器自己的网络栈去取(至多给的字节数);
     * 取到为 (内容, 类型),取不到为 null。不给就不换。调用方只在网址和页面同源时给它(页面那边也只取同源、不跟随跳转);
     * 换路前再过一遍地址规则。
     */
    fun start(
        url: String, ua: String, contentDisposition: String?, mime: String?, referer: String?, suggested: String? = null,
        viaPage: (suspend (Long) -> Pair<ByteArray, String?>?)? = null,
    ): String {
        val name = fileName(contentDisposition, url, mime, suggested)
        if (WebUrlPolicy.checkNavigable(url) != null || !(url.startsWith("http://", true) || url.startsWith("https://", true))) {
            return failed(name, mime, "not_a_web_address")
        }
        if (addressRefusal(url) != null) return failed(name, mime, BLOCKED_ADDRESS)
        val entry = reserve(name, mime)
        if (entry.status == FAILED) return entry.file
        scope.launch {
            val unreached = try {
                tree.shared { fetch(url, ua, referer, entry) }
            } catch (e: CancellationException) {
                fail(entry, "cancelled")
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "download failed", e)
                networkError(e)
            }
            if (unreached != null) fallBack(entry, url, unreached, viaPage)
        }
        return entry.file
    }

    /**
     * 自己没连上([reason] 带着真实的出错类型):是 TLS / 握手一类、给了 [viaPage]、地址仍过得了规则时改由页面去取,
     * 取到就存下;否则照原因记失败。
     */
    private suspend fun fallBack(entry: Entry, url: String, reason: String, viaPage: (suspend (Long) -> Pair<ByteArray, String?>?)?) {
        val usable = viaPage?.takeIf { reason.startsWith(TLS_ERROR) && addressRefusal(url) == null }
        val got = usable?.let { read ->
            try { read(maxBlobBytes) } catch (e: CancellationException) { fail(entry, "cancelled"); throw e } catch (e: Exception) { null }
        }
        if (got == null) return fail(entry, reason)
        if (got.first.size > maxBlobBytes) return fail(entry, TOO_LARGE)
        tree.shared { write(entry, got.first) }
    }

    /** 页面脚本读出来的 blob: 内容,或解出来的 data: 内容,直接存下。 */
    fun save(sourceUrl: String, contentDisposition: String?, mime: String?, bytes: ByteArray, suggested: String? = null): String {
        val name = localName(sourceUrl, contentDisposition, mime, suggested)
        if (bytes.size > maxBlobBytes) return failed(name, mime, TOO_LARGE)
        val entry = reserve(name, mime)
        if (entry.status == FAILED) return entry.file
        write(entry, bytes)
        return entry.file
    }

    /**
     * blob: 下载:先登记(running),再由 [read] 让页面把内容读出来(至多 [maxBlobBytes]);读不出给原因。
     * [read] 返回 (内容, 页面报的类型) 或失败原因。
     */
    fun startFrom(
        sourceUrl: String, contentDisposition: String?, mime: String?, suggested: String? = null,
        read: suspend (Long) -> Pair<ByteArray, String?>?,
    ): String {
        val name = localName(sourceUrl, contentDisposition, mime, suggested)
        val entry = reserve(name, mime)
        if (entry.status == FAILED) return entry.file
        scope.launch {
            try {
                val got = read(maxBlobBytes) ?: return@launch fail(entry, "unreadable")
                if (got.first.size > maxBlobBytes) return@launch fail(entry, TOO_LARGE)
                tree.shared { write(entry, got.first) }
            } catch (e: CancellationException) {
                fail(entry, "cancelled")
                throw e
            } catch (e: BlobRefused) {
                fail(entry, e.reason)
            } catch (e: Exception) {
                Log.w(TAG, "blob download failed", e)
                fail(entry, "unreadable")
            }
        }
        return entry.file
    }

    /** [startFrom] 的读取方报的明确原因(如 too_large)。 */
    class BlobRefused(val reason: String) : Exception(reason)

    private fun localName(sourceUrl: String, contentDisposition: String?, mime: String?, suggested: String?) =
        fileName(contentDisposition, sourceUrl.takeUnless { it.startsWith("blob:", true) || it.startsWith("data:", true) }.orEmpty(), mime, suggested)

    private fun write(entry: Entry, bytes: ByteArray) {
        try {
            if (!charge(entry, bytes.size.toLong())) return fail(entry, DOWNLOAD_LIMIT)
            entry.gated!!.write(bytes)
            entry.bytes = bytes.size.toLong()
            done(entry)
        } catch (e: IOException) {
            fail(entry, "write_failed")
        }
    }

    /**
     * data: 网址:按 base64 或百分号编码解出来存下;解不出来记失败。
     * 解码前先按长度判断:base64 每 4 个字是 3 个字节,百分号编码每个字节至少 1 个字,超出上限的不解。
     */
    fun dataUrl(url: String, contentDisposition: String?, suggested: String? = null): String {
        val comma = url.indexOf(',')
        val header = if (comma < 0) "" else url.substring(0, comma)
        val mime = header.removePrefix("data:").substringBefore(';').ifBlank { null }
        val base64 = header.endsWith(";base64", true)
        val payloadLength = if (comma < 0) 0L else (url.length - comma - 1).toLong()
        val limit = if (base64) maxBlobBytes * 4 / 3 + 8 else maxBlobBytes * 3
        if (payloadLength > limit) return failed(fileName(contentDisposition, "", mime, suggested), mime, TOO_LARGE)
        val payload = if (comma < 0) "" else url.substring(comma + 1)
        val bytes = runCatching {
            if (base64) Base64.getDecoder().decode(payload) else URLDecoder.decode(payload, "UTF-8").toByteArray()
        }.getOrNull() ?: return failed(fileName(contentDisposition, "", mime, suggested), mime, "bad_data_url")
        return save(url, contentDisposition, mime, bytes, suggested)
    }

    fun list(): List<DownloadInfo> = synchronized(entries) {
        entries.map { DownloadInfo(it.file, it.bytes, it.mime, it.status, it.error) }
    }

    /** 这次之后新开始的下载;用来在触发下载的那次动作结果里提一句。 */
    fun count(): Int = synchronized(entries) { entries.size }

    fun since(count: Int): List<DownloadInfo> = list().drop(count)

    /**
     * 一跳一跳地取:每一跳先查地址;整个下载(含跳转与读正文)不超过 [timeoutMs]。
     * 连不上(还没写进任何字节)时不记失败,返回原因交给调用方换路;其余情况自己记完,返回 null。
     */
    private fun fetch(url: String, ua: String, referer: String?, entry: Entry): String? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var current = url.toHttpUrlOrNull() ?: return fail(entry, "not_a_web_address").let { null }
        var hops = 0
        while (true) {
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) return fail(entry, TIMED_OUT).let { null }
            val req = Request.Builder().url(current).header("User-Agent", ua).apply { referer?.let { header("Referer", it) } }.build()
            val call = client.newCall(req)
            call.timeout().timeout(left, TimeUnit.MILLISECONDS)
            val resp = try {
                call.execute()
            } catch (e: IOException) {
                val reason = failureOf(e, hops, deadline)
                // TLS / 握手一类的错误、而且还在第一跳:交给调用方决定要不要改由页面去取;地址被拒、超时、跳转后的错误照实记
                if (reason.startsWith(TLS_ERROR) && hops == 0) return reason
                return fail(entry, reason).let { null }
            }
            try {
                if (resp.isRedirect) {
                    val next = resp.header("Location")?.let { resp.request.url.resolve(it) } ?: return fail(entry, "http_${resp.code}").let { null }
                    if (++hops > MAX_REDIRECTS) return fail(entry, "too_many_redirects").let { null }
                    if (addressRefusal(next.toString()) != null) return fail(entry, BLOCKED_REDIRECT).let { null }
                    current = next
                    continue
                }
                if (!resp.isSuccessful) return fail(entry, "http_${resp.code}").let { null }
                val body = resp.body ?: return fail(entry, "empty_response").let { null }
                if (body.contentLength() > maxBytes) return fail(entry, TOO_LARGE).let { null }
                val out = entry.gated ?: return null
                val input = body.byteStream()
                val buf = ByteArray(BUFFER)
                var total = 0L
                while (true) {
                    val n = try { input.read(buf) } catch (e: IOException) { return fail(entry, failureOf(e, hops, deadline)).let { null } }
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) return fail(entry, TOO_LARGE).let { null }
                    if (!charge(entry, n.toLong())) return fail(entry, DOWNLOAD_LIMIT).let { null }
                    out.write(if (n == buf.size) buf else buf.copyOf(n))
                    entry.bytes = total
                    if (System.nanoTime() > deadline) return fail(entry, TIMED_OUT).let { null }
                }
                return done(entry).let { null }
            } finally {
                resp.close()
            }
        }
    }

    private fun failureOf(e: IOException, hops: Int, deadline: Long): String = when {
        generateSequence<Throwable>(e) { it.cause }.any { it is DownloadAddressPolicy.BlockedAddress } -> if (hops == 0) BLOCKED_ADDRESS else BLOCKED_REDIRECT
        System.nanoTime() >= deadline || e is java.io.InterruptedIOException -> TIMED_OUT
        else -> networkError(e)
    }

    /**
     * 网络错误带上真实的出错类型(最里层的原因),例如 tls_error (SSLHandshakeException: …)、network_error (UnknownHostException: …);
     * 证书、握手一类(SSLException)单独叫 tls_error。
     */
    private fun networkError(e: Throwable): String {
        val chain = generateSequence(e) { it.cause }.toList()
        val root = chain.last()
        val detail = root.message?.lineSequence()?.firstOrNull()?.take(MAX_ERROR_DETAIL)?.let { ": $it" }.orEmpty()
        val kind = if (chain.any { it is javax.net.ssl.SSLException }) TLS_ERROR else NETWORK_ERROR
        return "$kind (${root.javaClass.simpleName}$detail)"
    }

    /**
     * 占一个名字和一份额度:经关口在 downloads/ 里新建空文件(不跟随链接、不覆盖已有的),句柄留着写。
     * 额度不够或目录不安全时返回一条已失败的记录。
     */
    private fun reserve(name: String, mime: String?): Entry = synchronized(entries) {
        if (files >= maxFiles) return failedEntry(name, mime, DOWNLOAD_LIMIT)
        // 关口拒绝(downloads/ 被换成链接等)或建不了:记 unsafe_path
        val opened = try {
            workspaceDir.mkdirs()
            gate.createNumbered(workspaceDir, DIR, name, MAX_SAME_NAME)
        } catch (e: SandboxError) {
            null
        } catch (e: IOException) {
            null
        } ?: return failedEntry(name, mime, UNSAFE_PATH)
        files++
        Entry("$SANDBOX_DIR/${opened.first}", mime).also {
            it.gated = opened.second
            it.counted = true
            entries += it
        }
    }

    /** 记下 [n] 个刚写的字节;任务总量超了为 false。 */
    private fun charge(entry: Entry, n: Long): Boolean = synchronized(entries) {
        if (totalBytes + n > maxTotalBytes) return false
        totalBytes += n
        entry.charged += n
        true
    }

    private fun done(entry: Entry) = synchronized(entry) {
        if (entry.status != RUNNING) return
        entry.status = DONE
        entry.gated?.close()
        entry.gated = null
    }

    private fun failed(name: String, mime: String?, error: String): String = failedEntry(name, mime, error).file

    private fun failedEntry(name: String, mime: String?, error: String): Entry =
        Entry("$SANDBOX_DIR/$name", mime).also { it.status = FAILED; it.error = error; synchronized(entries) { entries += it } }

    /** 失败:删掉这个下载自己建的文件(句柄核实过的那个,名字被换成别的就不动),退回额度。 */
    private fun fail(entry: Entry, error: String) {
        synchronized(entry) {
            if (entry.status != RUNNING) return
            entry.status = FAILED
            entry.error = error
            entry.gated?.let { g -> runCatching { g.delete() }; g.close() }
            entry.gated = null
        }
        synchronized(entries) {
            if (entry.counted) {
                entry.counted = false
                files--
                totalBytes -= entry.charged
            }
        }
    }

    companion object {
        const val RUNNING = "running"
        const val DONE = "done"
        const val FAILED = "failed"
        const val TOO_LARGE = "too_large"
        const val UNSAFE_PATH = "unsafe_path"
        const val BLOCKED_ADDRESS = "blocked_address"
        const val BLOCKED_REDIRECT = "blocked_redirect"
        const val DOWNLOAD_LIMIT = "download_limit"
        const val TIMED_OUT = "timed_out"
        const val NETWORK_ERROR = "network_error"
        const val TLS_ERROR = "tls_error"
        private const val MAX_ERROR_DETAIL = 120
        private const val MAX_REDIRECTS = 10
        /** 名字是网页给的,交给模型前限长。 */
        const val MAX_NAME_CHARS = 80
        private const val TAG = "MikaDownloads"
        private const val DIR = "downloads"
        private const val SANDBOX_DIR = "/workspace/downloads"
        private const val BUFFER = 64 * 1024
        private const val MAX_SAME_NAME = 999
        private const val MAX_NAME_BYTES = 200
        private const val MAX_EXT_BYTES = 20
        private const val FALLBACK = "download"
        private const val NAMING_POLL_MS = 20L

        private val FILENAME_STAR = Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE)
        private val FILENAME_QUOTED = Regex("""filename\s*=\s*"((?:[^"\\]|\\.)*)"""", RegexOption.IGNORE_CASE)
        private val FILENAME_TOKEN = Regex("""filename\s*=\s*([^;\s]+)""", RegexOption.IGNORE_CASE)
        /** 控制字符、双向控制符与零宽字符:能把「exe」显示成「pdf」之类。 */
        private val UNSAFE_CHARS = Regex("[\\u0000-\\u001f\\u007f-\\u009f\\u061c\\u200b-\\u200f\\u202a-\\u202e\\u2066-\\u2069\\ufeff]")
        private val MIME_EXT = mapOf(
            "application/pdf" to "pdf", "text/csv" to "csv", "text/plain" to "txt", "application/json" to "json",
            "application/zip" to "zip", "image/png" to "png", "image/jpeg" to "jpg", "image/gif" to "gif", "image/webp" to "webp",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
            "application/vnd.ms-excel" to "xls", "application/msword" to "doc", "text/html" to "html",
        )

        /** 下载的文件名:见类说明。[suggested] 是页面给的名字(`<a download>`),排在响应头之后、网址之前。 */
        fun fileName(contentDisposition: String?, url: String, mime: String?, suggested: String? = null): String {
            val fromHeader = contentDisposition?.let { cd ->
                FILENAME_STAR.find(cd)?.let { m ->
                    val charset = m.groupValues[1].ifBlank { "UTF-8" }
                    runCatching { URLDecoder.decode(m.groupValues[2].trim().replace("+", "%2B"), charset) }.getOrNull()
                } ?: FILENAME_QUOTED.find(cd)?.groupValues?.get(1)?.replace(Regex("""\\(.)"""), "$1")
                    ?: FILENAME_TOKEN.find(cd)?.groupValues?.get(1)
            }
            val fromUrl = runCatching { URI(url).rawPath }.getOrNull()?.substringAfterLast('/')
                ?.let { runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrNull() }
            var name = clean(fromHeader ?: "").ifEmpty { clean(suggested ?: "") }.ifEmpty { clean(fromUrl ?: "") }.ifEmpty { FALLBACK }
            if (!name.contains('.')) MIME_EXT[mime?.substringBefore(';')?.trim()?.lowercase()]?.let { name = "$name.$it" }
            return fitBytes(name)
        }

        private fun clean(raw: String): String {
            // 只留最后一段:正斜杠、反斜杠都算分隔符
            val last = raw.substringAfterLast('/').substringAfterLast('\\')
            return last.replace(UNSAFE_CHARS, "").trim().trimStart('.').trim().takeUnless { it == "." || it == ".." }.orEmpty()
        }

        private fun fitBytes(name: String): String {
            fun chars(t: String) = t.codePointCount(0, t.length)
            if (name.toByteArray().size <= MAX_NAME_BYTES && chars(name) <= MAX_NAME_CHARS) return name
            val ext = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.toByteArray().size <= MAX_EXT_BYTES }?.let { ".$it" }.orEmpty()
            var base = name.removeSuffix(ext)
            // 按码点截,不劈开代理对
            while (((base + ext).toByteArray().size > MAX_NAME_BYTES || chars(base + ext) > MAX_NAME_CHARS) && base.isNotEmpty()) {
                base = base.substring(0, base.offsetByCodePoints(base.length, -1))
            }
            return (base + ext).ifEmpty { FALLBACK }
        }
    }
}
