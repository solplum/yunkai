package com.wangzan101.mika.browser

import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import java.net.URLEncoder

/**
 * 地址栏和首页搜索框里输入的一句话 → 要打开的网址。
 * - 带 `://` 的原样打开(能不能打开由 [WebUrlPolicy.checkNavigable] 再判);
 * - localhost、IPv4、方括号里的 IPv6(可带端口和路径)补上 http://;
 * - 像域名的(由点隔开的英文字母、数字、连字符,最后一段是至少两个字母或 xn-- 开头,可带端口和路径)补上 https://;
 * - 其余(含空格、中文、只有一个词、邮箱这类)用必应搜索。
 */
object AddressInput {
    const val SEARCH_PREFIX = "https://www.bing.com/search?q="

    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    private val LABEL = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
    private val TLD = Regex("^([A-Za-z]{2,63}|xn--[A-Za-z0-9-]{1,59})$")
    private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")
    private val IPV6 = Regex("^\\[[0-9A-Fa-f:.]+]$")
    private val PORT = Regex("^\\d{1,5}$")

    /** 输入为空(只有空白)时为 null。 */
    fun resolve(input: String): String? {
        val s = input.trim()
        if (s.isEmpty()) return null
        if (s.any { it.isWhitespace() }) return search(s)
        if (SCHEME.containsMatchIn(s)) return s
        val (host, port) = authority(s) ?: return search(s)
        if (port != null && (!PORT.matches(port) || port.toInt() !in 1..65535)) return search(s)
        return when {
            host.equals("localhost", ignoreCase = true) || IPV4.matches(host) || IPV6.matches(host) -> "http://$s"
            domain(host) -> "https://$s"
            else -> search(s)
        }
    }

    fun search(query: String): String = SEARCH_PREFIX + URLEncoder.encode(query.trim(), "UTF-8")

    /** 网址是必应的搜索结果页时为搜的词,否则为 null。 */
    fun searchQuery(url: String): String? {
        if (!url.startsWith(SEARCH_PREFIX)) return null
        val q = url.removePrefix(SEARCH_PREFIX).substringBefore('&').substringBefore('#')
        return runCatching { java.net.URLDecoder.decode(q, "UTF-8") }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** 开头到第一个 / ? # 为止的主机名和端口;里面带 @ 等不像主机名的字符时为 null。 */
    private fun authority(s: String): Pair<String, String?>? {
        val auth = s.substringBefore('/').substringBefore('?').substringBefore('#')
        if (auth.isEmpty() || '@' in auth) return null
        if (auth.startsWith("[")) {
            val end = auth.indexOf(']')
            if (end < 0) return null
            val rest = auth.substring(end + 1)
            if (rest.isNotEmpty() && !rest.startsWith(":")) return null
            return auth.substring(0, end + 1) to rest.removePrefix(":").ifEmpty { null }
        }
        val parts = auth.split(':')
        return when (parts.size) {
            1 -> parts[0] to null
            2 -> parts[0] to parts[1]
            else -> null
        }
    }

    private fun domain(host: String): Boolean {
        val labels = host.split('.')
        if (labels.size < 2) return false
        return labels.all { LABEL.matches(it) } && TLD.matches(labels.last())
    }
}
