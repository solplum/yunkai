package com.wangzan101.mika.browser

import com.wangzan101.mika.cap.web.SiteEntry
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** 「常用网站」里的一格:打开时去的主机名、在 Mika 里是否登录着(这个网站的任一主机名登录着就算)。 */
data class FavoriteSite(val host: String, val loggedIn: Boolean)

/** 用户对「常用网站」的改动:自己加的([pinned],按加入的先后)和从里面移除的([hidden])。 */
data class FavoriteEdits(val pinned: List<String> = emptyList(), val hidden: Set<String> = emptySet())

/**
 * 浏览器页「常用网站」:用户自己加的网站在前,后面是站点登记表里的网站(Agent 和用户访问过的;登录过的在前)。
 * 同一网站(同一可注册域名)只占一格,打开时去其中最短的主机名。
 *
 * 只记用户的改动,不动站点登记表:从常用里移除只是不显示,登录状态、Cookie 和 Agent 用的登记都照旧
 * (退出登录在设置页「网站登录」里做);再加回来就又显示。
 * 存在 App 私有目录的一个 JSON 文件里;文件坏了就从空表重新开始。
 */
class BrowserFavorites(dir: File) {
    private val file = File(dir, FILE)
    private val _edits = MutableStateFlow(load())

    val edits: StateFlow<FavoriteEdits> = _edits.asStateFlow()

    /** 加入常用:放到自己加的最后;以前移除过的这个网站重新显示。 */
    @Synchronized
    fun add(host: String) {
        val h = norm(host) ?: return
        val site = siteKey(h)
        val e = _edits.value
        save(FavoriteEdits(e.pinned.filter { siteKey(it) != site } + h, e.hidden - site))
    }

    /** 从常用移除:自己加的去掉,登记表里来的不再显示。 */
    @Synchronized
    fun remove(host: String) {
        val h = norm(host) ?: return
        val site = siteKey(h)
        val e = _edits.value
        save(FavoriteEdits(e.pinned.filter { siteKey(it) != site }, e.hidden + site))
    }

    private fun load(): FavoriteEdits {
        if (!file.isFile) return FavoriteEdits()
        return try {
            val o = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return FavoriteEdits()
            fun list(k: String) = (o[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.let(::norm) }
            FavoriteEdits(list("pinned").distinct(), list("hidden").toSet())
        } catch (e: SerializationException) {
            FavoriteEdits()
        }
    }

    private fun save(e: FavoriteEdits) {
        _edits.value = e
        file.parentFile?.mkdirs()
        val json = buildJsonObject {
            put("pinned", JsonArray(e.pinned.map(::JsonPrimitive)))
            put("hidden", JsonArray(e.hidden.sorted().map(::JsonPrimitive)))
        }.toString()
        // 先写临时文件再改名,写到一半被杀不会留下半个文件
        val tmp = File(file.parentFile, "$FILE.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) {
            file.writeText(json)
            tmp.delete()
        }
    }

    companion object {
        /** 网格至多这么多格网站(再加一格「＋」,一行四个正好三行)。 */
        const val LIMIT = 11
        private const val FILE = "browser_favorites.json"

        private fun norm(host: String): String? {
            val s = host.trim()
            if (s.isEmpty()) return null
            return WebUrlPolicy.host(if ("://" in s) s else "https://$s")
        }

        /** 同一网站的标记:可注册域名,拿不准时是主机名本身。 */
        fun siteKey(host: String): String = WebUrlPolicy.registrable(host) ?: host

        /** 合成网格:自己加的在前(按加入先后),再按登记表的顺序;同一网站一格,移除过的不显示,至多 [limit] 格。 */
        fun combine(edits: FavoriteEdits, registry: List<SiteEntry>, limit: Int = LIMIT): List<FavoriteSite> {
            val bySite = registry.groupBy { siteKey(it.host) }
            fun loggedIn(site: String) = bySite[site].orEmpty().any { it.loggedInByUser }
            val out = LinkedHashMap<String, FavoriteSite>()
            for (h in edits.pinned) {
                val site = siteKey(h)
                if (site !in out) out[site] = FavoriteSite(h, loggedIn(site))
            }
            for (e in registry) {
                val site = siteKey(e.host)
                if (site in out || site in edits.hidden) continue
                val host = bySite[site].orEmpty().minBy { it.host.length }.host
                out[site] = FavoriteSite(host, loggedIn(site))
            }
            return out.values.take(limit)
        }

        /** [host] 所在的网站在 Mika 里是否登录着(登记表里这个网站的任一主机名登录着)。 */
        fun loggedIn(registry: List<SiteEntry>, host: String): Boolean {
            val site = siteKey(host)
            return registry.any { it.loggedInByUser && siteKey(it.host) == site }
        }

        /** [host] 所在的网站在不在 [sites] 里。 */
        fun contains(sites: List<FavoriteSite>, host: String): Boolean {
            val site = siteKey(host)
            return sites.any { siteKey(it.host) == site }
        }
    }
}
