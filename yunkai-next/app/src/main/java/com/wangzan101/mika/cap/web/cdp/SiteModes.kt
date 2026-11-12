package com.wangzan101.mika.cap.web.cdp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * 哪些网站改用手机版网页(默认都是桌面版)。按主机名记,去掉开头的 www.;子域名跟着上级走
 * (记了 example.com,m.example.com 也用手机版)。存在 App 私有目录的一个 JSON 文件里,坏了就从空表开始。
 */
class SiteModes(dir: File) {
    private val file = File(dir, FILE)
    private val mobile = LinkedHashSet<String>()

    init {
        runCatching {
            if (file.isFile) {
                (Json.parseToJsonElement(file.readText()) as? JsonArray)?.forEach { e ->
                    (e as? JsonPrimitive)?.contentOrNull?.let(::key)?.let { mobile += it }
                }
            }
        }
    }

    @Synchronized
    fun isMobile(host: String?): Boolean {
        var h = host?.let(::key) ?: return false
        while (true) {
            if (h in mobile) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }

    /** 记下这个网站用哪一版;改成桌面版时连上级域名的记录一起去掉,否则还会跟着上级走。 */
    @Synchronized
    fun set(host: String, mobile: Boolean) {
        val k = key(host) ?: return
        if (mobile) this.mobile += k
        else this.mobile.removeAll { k == it || k.endsWith(".$it") }
        save()
    }

    @Synchronized
    fun mobileSites(): List<String> = mobile.toList()

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JsonArray(mobile.map(::JsonPrimitive)).toString())
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    private companion object {
        const val FILE = "site_modes.json"

        fun key(host: String): String? =
            host.trim().trimEnd('.').lowercase().removePrefix("www.").takeIf { it.isNotEmpty() && ' ' !in it && '/' !in it }
    }
}
