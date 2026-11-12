package com.wangzan101.mika.settings

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 地址是不是在本机或局域网里:本机(localhost、127.0.0.0/8、::1)、私有网段(10/8、172.16/12、192.168/16、
 * IPv6 的 fc00::/7)、链路本地(169.254/16、fe80::/10)和 `.local` 名字。只看地址本身,不做解析。
 */
object LocalHosts {
    fun isLocal(host: String): Boolean {
        val h = host.trim().trimStart('[').trimEnd(']').trimEnd('.').lowercase()
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local")) return true
        ipv4(h)?.let { (a, b) ->
            return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
        }
        if (':' in h) {
            if (h == "::1") return true
            val first = h.substringBefore(':').toIntOrNull(16) ?: return false
            return first and 0xfe00 == 0xfc00 || first and 0xffc0 == 0xfe80
        }
        return false
    }

    /** 明文 http、又不在本机或局域网的地址:Key 和对话会在网上不加密地传。认不出的地址不算。 */
    fun unencryptedRemote(url: String): Boolean {
        val u = url.trim().toHttpUrlOrNull() ?: return false
        return !u.isHttps && !isLocal(u.host)
    }

    /** 点分四段的 IPv4 的前两段;不是 IPv4 为 null。 */
    private fun ipv4(h: String): Pair<Int, Int>? {
        val parts = h.split('.')
        if (parts.size != 4) return null
        val n = parts.map { p -> p.toIntOrNull()?.takeIf { p.isNotEmpty() && p.all(Char::isDigit) && it in 0..255 } ?: return null }
        return n[0] to n[1]
    }
}
