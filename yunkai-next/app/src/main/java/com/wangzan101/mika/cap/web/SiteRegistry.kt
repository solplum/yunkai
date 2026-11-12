package com.wangzan101.mika.cap.web

import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * 站点登记表里的一项:主机名、最近访问时间、是否在 Mika 里处于登录状态(登录核实过、或页面上看出已登录时打上,
 * 看出已退出时去掉)。[challengedAtMs] / [challenge]:最近一次在这个网站上碰到人机验证或访问拦截的时间与种类
 * (captcha / access_blocked),这个网站的会话可能已被网站盯上;过了 [SiteRegistry.CHALLENGE_TTL_MS] 或清除该站数据后不再算。
 */
data class SiteEntry(
    val host: String,
    val lastVisitMs: Long,
    val loggedInByUser: Boolean,
    val challengedAtMs: Long? = null,
    val challenge: String? = null,
) {
    /** 此刻([now])还算「碰到过拦截」。 */
    fun challenged(now: Long): Boolean = challengedAtMs?.let { now - it < SiteRegistry.CHALLENGE_TTL_MS } == true
}

/**
 * 站点登记表:记下 Agent 和用户在内置浏览器里访问过的网站。系统接口列不出所有存了 Cookie 的站点,
 * 所以它是设置页「网站登录」能展示的唯一来源。存在 App 私有目录的一个 JSON 文件里;
 * 文件坏了时挪到一边留着(不拿新内容覆盖),从空表重新开始。
 */
class SiteRegistry(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val file = File(dir, FILE)
    private val sites = LinkedHashMap<String, SiteEntry>()
    private var corrupt = false
    private val _entries = MutableStateFlow<List<SiteEntry>>(emptyList())

    /** 和 [list] 相同的内容,变化时更新,给界面用。 */
    val entries: StateFlow<List<SiteEntry>> = _entries.asStateFlow()

    init {
        load()
        publish()
    }

    @Synchronized
    fun visited(host: String) {
        val h = normalize(host) ?: return
        sites[h] = sites[h]?.copy(lastVisitMs = clock()) ?: SiteEntry(h, clock(), false)
        save()
    }

    /** 页面上看出的登录状态:已登录记上、已退出去掉(会话过期了),看不出不动。返回记录是否变了。 */
    @Synchronized
    fun loginObserved(host: String, state: LoginState): Boolean {
        val h = normalize(host) ?: return false
        val want = when (state) { LoginState.SignedIn -> true; LoginState.SignedOut -> false; LoginState.Unknown -> return false }
        val cur = sites[h]
        if ((cur?.loggedInByUser ?: false) == want) return false
        sites[h] = (cur ?: SiteEntry(h, clock(), false)).copy(loggedInByUser = want)
        save()
        return true
    }

    /**
     * 人机验证或访问拦截把任务拦住了([kind]:captcha / access_blocked):记下时间与种类。
     * 同一种在 [CHALLENGE_REFRESH_MS] 内记过就不重写。
     */
    @Synchronized
    fun challenged(host: String, kind: String) {
        val h = normalize(host) ?: return
        val cur = sites[h]
        if (cur?.challenge == kind && cur.challengedAtMs?.let { clock() - it < CHALLENGE_REFRESH_MS } == true) return
        sites[h] = (cur ?: SiteEntry(h, clock(), false)).copy(challengedAtMs = clock(), challenge = kind)
        save()
    }

    /** 此刻还算碰到过拦截的网站。 */
    @Synchronized
    fun challengedSites(): Set<String> {
        val now = clock()
        return sites.values.filter { it.challenged(now) }.map { it.host }.toSet()
    }

    @Synchronized
    fun markLoggedIn(host: String) {
        val h = normalize(host) ?: return
        sites[h] = (sites[h] ?: SiteEntry(h, clock(), false)).copy(loggedInByUser = true)
        save()
    }

    /** 登录过的在前,再按最近访问。 */
    @Synchronized
    fun list(): List<SiteEntry> = sorted()

    @Synchronized
    fun remove(host: String) {
        val h = normalize(host) ?: return
        if (sites.remove(h) != null) save()
    }

    @Synchronized
    fun clear() {
        sites.clear()
        save()
    }

    private fun sorted() = sites.values.sortedWith(compareByDescending<SiteEntry> { it.loggedInByUser }.thenByDescending { it.lastVisitMs })

    private fun publish() {
        _entries.value = sorted()
    }

    private fun load() {
        if (!file.isFile) return
        val parsed = try {
            (Json.parseToJsonElement(file.readText()) as? JsonArray)?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val host = (o["host"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::normalize) ?: return@mapNotNull null
                SiteEntry(
                    host, (o["lastVisitMs"] as? JsonPrimitive)?.longOrNull ?: 0L, (o["loggedIn"] as? JsonPrimitive)?.booleanOrNull == true,
                    (o["challengedAt"] as? JsonPrimitive)?.longOrNull, (o["challenge"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                )
            }
        } catch (e: SerializationException) {
            null
        }
        if (parsed == null) {
            corrupt = true
            return
        }
        parsed.forEach { sites[it.host] = it }
    }

    private fun save() {
        dir.mkdirs()
        if (corrupt) {
            // 损坏的文件挪到一边留着,不拿新内容覆盖掉
            file.renameTo(File(dir, "$FILE.corrupt-${clock()}"))
            corrupt = false
        }
        val json = JsonArray(sites.values.map {
            buildJsonObject {
                put("host", it.host); put("lastVisitMs", it.lastVisitMs); put("loggedIn", it.loggedInByUser)
                it.challengedAtMs?.let { t -> put("challengedAt", t) }
                it.challenge?.let { k -> put("challenge", k) }
            }
        }).toString()
        // 先写临时文件再改名,写到一半被杀不会留下半个文件
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) {
            file.writeText(json)
            tmp.delete()
        }
        publish()
    }

    companion object {
        /** 碰到拦截的标记保留这么久(之后网站多半已不再盯着这次会话)。 */
        const val CHALLENGE_TTL_MS = 7L * 24 * 60 * 60 * 1000
        /** 同一种拦截这么久之内再碰到不重写记录。 */
        const val CHALLENGE_REFRESH_MS = 10L * 60 * 1000
        /** 算作「网站盯上了这次会话」的接管原因。 */
        val CHALLENGE_KINDS = setOf("captcha", "access_blocked")
        private const val FILE = "sites.json"

        /** 主机名统一成小写、去掉 www.;传进来的是网址也认。不像主机名的不登记。 */
        private fun normalize(raw: String): String? {
            val s = raw.trim()
            if (s.isEmpty()) return null
            return WebUrlPolicy.host(if (s.contains("://")) s else "https://$s")?.takeIf { h -> h.none { it.isWhitespace() } }
        }
    }
}
