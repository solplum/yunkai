package com.wangzan101.mika.browser

import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * 一个网站在浏览器页上的样子:名字([name],读不到为 null)和它的来历([rank],见 [SiteNames]),
 * 网站图标存下的时间([iconAtMs],没有图标为 null;界面按它判断要不要重读图标文件)。
 */
data class SiteLook(val name: String?, val rank: Int, val iconAtMs: Long?)

/** 界面上画一个网站要的全部:名字、名字的第一个字、底色(没有图标时用)、图标文件和它的版本。 */
data class SiteFace(val host: String, val name: String, val initial: String, val color: Long, val icon: File?, val iconAtMs: Long)

/**
 * 网站的名字和图标:用户在浏览器页里打开网页时记下(名字取自页面,图标取自 WebView 收到的网站图标),给「常用网站」「最近打开」用。
 * 按主机名记;同时记到可注册域名上(那边还没有更好的时),同一网站别的子域名的页面也认得。
 * 名字存在 App 私有目录的一个 JSON 文件里,图标是旁边 icons 目录里的 PNG;文件坏了就从空表重新开始。
 */
class SiteLooks(dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val file = File(dir, FILE)
    private val iconDir = File(dir, ICONS)
    private val _entries = MutableStateFlow(load())

    /** 主机名 → 样子。 */
    val entries: StateFlow<Map<String, SiteLook>> = _entries.asStateFlow()

    /** 记下页面给出的名字:来历不比已有的差才换。 */
    @Synchronized
    fun named(host: String, name: String, rank: Int) {
        val n = name.trim().takeIf { it.isNotEmpty() } ?: return
        var map = _entries.value
        keys(host).forEachIndexed { i, h ->
            val cur = map[h]
            // 主机名本身:同样可靠的新名字也换;可注册域名:只在更可靠时换(别的子域名的页面不抢首页的名字)
            val replace = cur?.name == null || (if (i == 0) rank >= cur.rank else rank > cur.rank)
            if (replace && cur?.name != n) map = map + (h to SiteLook(n, rank, cur?.iconAtMs))
        }
        if (map != _entries.value) save(map)
    }

    /**
     * 图标写进 [iconFile] 之前先问这里:要存到哪些主机名上。主机名本身没有图标、或存了超过 [ICON_REFRESH_MS] 时存;
     * 可注册域名还没有图标时也存一份。每打开一页都会收到图标,这样不会每次都重写。
     */
    @Synchronized
    fun iconTargets(host: String): List<String> = keys(host).filterIndexed { i, h ->
        val at = _entries.value[h]?.iconAtMs
        at == null || (i == 0 && clock() - at >= ICON_REFRESH_MS)
    }

    /** 这个主机名的图标文件(可能还不存在)。 */
    fun iconFile(host: String): File = File(iconDir, "${host.lowercase()}.png")

    /** 图标已经写进 [iconFile]。 */
    @Synchronized
    fun iconSaved(host: String) {
        val h = host.lowercase()
        val cur = _entries.value[h]
        save(_entries.value + (h to SiteLook(cur?.name, cur?.rank ?: 0, clock())))
    }

    /** 画这个网站要的样子:先找主机名本身,再找可注册域名;都没有时名字、底色按主机名推。 */
    fun face(host: String): SiteFace = faceOf(host, _entries.value, ::iconFile)

    @Synchronized
    fun clear() {
        iconDir.listFiles()?.forEach { it.delete() }
        save(emptyMap())
    }

    private fun load(): Map<String, SiteLook> {
        if (!file.isFile) return emptyMap()
        return try {
            (Json.parseToJsonElement(file.readText()) as? JsonObject)?.mapNotNull { (h, e) ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val rank = (o["rank"] as? JsonPrimitive)?.intOrNull ?: 0
                // 早先按别的页面的标题起的名字可能是错的(见 SiteNames):丢掉,等首页或声明的网站名重新起
                val name = (o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { rank >= SiteNames.RANK_HOME_TITLE }
                h to SiteLook(name, if (name == null) 0 else rank, (o["iconAt"] as? JsonPrimitive)?.longOrNull)
            }?.toMap().orEmpty()
        } catch (e: SerializationException) {
            emptyMap()
        }
    }

    private fun save(map: Map<String, SiteLook>) {
        _entries.value = map
        file.parentFile?.mkdirs()
        val json = JsonObject(map.mapValues { (_, l) ->
            buildJsonObject {
                l.name?.let { put("name", it) }
                put("rank", l.rank)
                l.iconAtMs?.let { put("iconAt", it) }
            }
        }).toString()
        // 先写临时文件再改名,写到一半被杀不会留下半个文件
        val tmp = File(file.parentFile, "$FILE.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) {
            file.writeText(json)
            tmp.delete()
        }
    }

    companion object {
        private const val FILE = "browser_sites.json"
        private const val ICONS = "browser_icons"
        /** 同一主机名的图标隔这么久才重存一次(网站换了图标时跟上)。 */
        const val ICON_REFRESH_MS = 24L * 60 * 60 * 1000

        /** 主机名本身,再加上它的可注册域名(不同时)。 */
        fun keys(host: String): List<String> {
            val h = host.trim().lowercase().removePrefix("www.")
            if (h.isEmpty()) return emptyList()
            val site = WebUrlPolicy.registrable(h)
            return if (site == null || site == h) listOf(h) else listOf(h, site)
        }

        fun faceOf(host: String, looks: Map<String, SiteLook>, iconFile: (String) -> File?): SiteFace {
            val found = keys(host).mapNotNull { looks[it] }
            val name = found.firstNotNullOfOrNull { it.name } ?: SiteNames.fallback(host)
            val iconKey = keys(host).firstOrNull { looks[it]?.iconAtMs != null }
            return SiteFace(
                host, name, SiteNames.initial(name), SiteTiles.color(host),
                iconKey?.let(iconFile), iconKey?.let { looks[it]?.iconAtMs } ?: 0L,
            )
        }
    }
}

/**
 * 网站的名字从哪来,越可靠 [rank] 越大:页面声明的网站名(og:site_name、application-name)最可靠;
 * 首页的标题次之(多半以网站名开头)。别的页面的标题不拿来起名:里面哪一段是网站名说不准,登录、出错这类页面的标题
 * 末段常是「统一登录」之类的页面名,会把网站改错名。都没有时用主机名推一个。
 */
object SiteNames {
    const val RANK_DECLARED = 3
    /** 来历低于它的名字(早先按别的页面的标题记下的)不再用,读进来时丢掉(见 [SiteLooks])。 */
    const val RANK_HOME_TITLE = 2
    /** 名字太长时截到这么多字(网格里一格放不下更多)。 */
    const val MAX_LEN = 12

    /** 标题里网站名和页面名之间常用的分隔:两边带空格的横线、冒号,或竖线、下划线、间隔号。 */
    private val SEPARATORS = Regex("""\s+[-–—:：]\s+|\s*[|｜_·•]\s*""")

    /** 从页面上读到的东西选一个名字和它的来历:声明的网站名,或首页标题的第一段;选不出为 null。 */
    fun pick(declared: String?, title: String?, homePage: Boolean): Pair<String, Int>? {
        clean(declared)?.let { return it to RANK_DECLARED }
        if (!homePage) return null
        val first = title?.split(SEPARATORS)?.firstNotNullOfOrNull(::clean) ?: return null
        return first to RANK_HOME_TITLE
    }

    /** 在页面主框架里读网站名和标题的只读脚本,结果是 {site, title} 的 JSON(经 evaluateJavascript 再包一层字符串)。 */
    val SCRIPT = """(function(){try{function m(s){var e=document.querySelector(s);return e&&e.content?String(e.content):'';}
return JSON.stringify({site:m('meta[property="og:site_name"]')||m('meta[name="application-name"]')||m('meta[name="apple-mobile-web-app-title"]'),title:String(document.title||'')});}catch(x){return null;}})()"""

    /** 解析 [SCRIPT] 的结果:(声明的网站名, 标题);读不出为 null。 */
    fun parse(raw: String?): Pair<String?, String?>? {
        val inner = runCatching { (Json.parseToJsonElement(raw ?: return null) as? JsonPrimitive)?.takeIf { it.isString }?.content }.getOrNull() ?: return null
        val o = runCatching { Json.parseToJsonElement(inner) as? JsonObject }.getOrNull() ?: return null
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        return str("site") to str("title")
    }

    /** 路径是空或 / 时算首页。 */
    fun isHomePage(url: String): Boolean {
        val path = runCatching { java.net.URI(url.trim()).rawPath }.getOrNull().orEmpty()
        return path.isEmpty() || path == "/"
    }

    /** 没有读到名字时:可注册域名去掉后缀(shop.example.com → example);IP 等原样。 */
    fun fallback(host: String): String {
        val h = host.trim().lowercase().removePrefix("www.")
        if ('.' !in h || h.startsWith("[") || h.all { it.isDigit() || it == '.' }) return h
        WebUrlPolicy.registrable(h)?.let { return it.substringBefore('.') }
        return h.substringBeforeLast('.')
    }

    /** 名字的第一个字(西文大写)。 */
    fun initial(name: String): String {
        val cp = name.trim().codePointAt0() ?: return "?"
        return String(Character.toChars(cp)).uppercase()
    }

    private fun String.codePointAt0(): Int? = if (isEmpty()) null else codePointAt(0)

    private fun clean(s: String?): String? {
        val t = s?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (t.length > MAX_LEN) t.take(MAX_LEN).trimEnd() else t
    }
}

/** 没有图标时网站方块的底色:按主机名(不计 www)固定选一个,同一网站每次都一样。 */
object SiteTiles {
    /** 深浅色下都压得住白字的几种颜色(ARGB)。 */
    val PALETTE = listOf(
        0xFFE5533D, 0xFFF08A24, 0xFFC9A100, 0xFF2E9E5B, 0xFF16A0A0,
        0xFF2F7DE1, 0xFF5B5BD6, 0xFF9A4FD0, 0xFFD6457F, 0xFF6B7280,
    )

    fun color(host: String): Long {
        val key = host.trim().lowercase().removePrefix("www.")
        // FNV-1a:相近的主机名也分得开,每次运行结果一样
        var h = 0x811C9DC5.toInt()
        for (ch in key) h = (h xor ch.code) * 0x01000193
        return PALETTE[Math.floorMod(h, PALETTE.size)]
    }
}
