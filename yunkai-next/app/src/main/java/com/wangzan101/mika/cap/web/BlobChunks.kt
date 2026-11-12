package com.wangzan101.mika.cap.web

/**
 * 页面分段交回的 blob 内容(base64)。页面报的长度、每段长度、累计长度都不信:
 * 报的长度超过 [maxBytes] 换算成 base64 的上限(每 3 字节 4 个字,另加填充余量)就在分配之前拒掉;
 * 不按报的长度预先分配;每段至多 [chunk] 个字,累计不超过报的长度。
 */
internal class BlobChunks(private val declared: Int, private val maxBytes: Long, private val chunk: Int = CHUNK) {
    sealed interface Outcome {
        class Ok(val bytes: ByteArray) : Outcome
        class Failed(val error: String) : Outcome
    }

    private val sb = StringBuilder()

    /** 报的长度能不能收;不能时给原因。 */
    fun refusal(): String? = when {
        declared < 0 -> UNREADABLE
        declared > maxBase64(maxBytes) -> WebDownloads.TOO_LARGE
        else -> null
    }

    val received: Int get() = sb.length
    val complete: Boolean get() = sb.length >= declared

    /** 收下一段;超长、超出报的长度时为失败原因。 */
    fun add(part: String): String? {
        if (part.length > chunk) return UNREADABLE
        if (sb.length.toLong() + part.length > declared) return UNREADABLE
        sb.append(part)
        return null
    }

    fun finish(): Outcome {
        val bytes = runCatching { java.util.Base64.getDecoder().decode(sb.toString()) }.getOrNull() ?: return Outcome.Failed(UNREADABLE)
        if (bytes.size > maxBytes) return Outcome.Failed(WebDownloads.TOO_LARGE)
        return Outcome.Ok(bytes)
    }

    companion object {
        /** 与 page.js 的 BLOB_CHUNK 一致。 */
        const val CHUNK = 1 shl 20
        const val UNREADABLE = "unreadable"

        fun maxBase64(maxBytes: Long): Long = maxBytes * 4 / 3 + 8
    }
}
