package com.wangzan101.mika.about

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** 一个发布的版本:版本号(不带 v)、标题、更新说明(Markdown)、发布日期、发布页、APK 直链、国内备用下载。 */
data class ReleaseInfo(
    val version: String,
    val name: String,
    /** 更新说明;「国内备用下载」那一行已拿掉,另放在 [mirrorUrl]。 */
    val notes: String,
    /** 发布日期 yyyy-MM-dd;来源没写时为空。 */
    val date: String?,
    val pageUrl: String,
    val apkUrl: String?,
    val mirrorUrl: String?,
) {
    /** 「从 GitHub 下载」打开的地址:有 APK 直链就下 APK,没有就打开发布页。 */
    val downloadUrl: String get() = apkUrl ?: pageUrl
}

/**
 * 读 GitHub 发布页接口(releases/latest)的版本信息:tag_name、name、body、published_at、html_url、
 * assets 里第一个 .apk 的 browser_download_url。
 *
 * 国内备用下载的约定:更新说明里单独一行写「国内备用下载: <地址>」(冒号全角半角都行,也可以写成 Markdown 链接
 * `[国内备用下载](<地址>)`)。读到后从说明里拿掉这一行,显示成按钮。
 */
object Releases {
    private val json = Json { ignoreUnknownKeys = true }

    private val MIRROR_LINE = Regex("""国内备用下载.*?(https?://[^\s)>\]]+)""")

    fun fromGitHub(text: String): ReleaseInfo {
        val o = json.parseToJsonElement(text).jsonObject
        val tag = o.str("tag_name") ?: error("no tag_name")
        val body = o.str("body").orEmpty()
        val apk = o["assets"]?.let { a ->
            runCatching { a.jsonArray }.getOrNull()?.map { it.jsonObject }
                ?.firstOrNull { it.str("name")?.endsWith(".apk", ignoreCase = true) == true }
                ?.str("browser_download_url")
        }
        val version = AppVersion.normalize(tag)
        return ReleaseInfo(
            version = version,
            name = o.str("name")?.takeIf { it.isNotBlank() } ?: version,
            notes = withoutMirrorLine(body),
            date = o.str("published_at")?.take(10)?.takeIf { it.length == 10 },
            pageUrl = o.str("html_url") ?: "${MikaLinks.RELEASES}/tag/$tag",
            apkUrl = apk,
            mirrorUrl = mirrorIn(body),
        )
    }

    /** 说明里「国内备用下载」那一行的地址;没有为空。 */
    fun mirrorIn(notes: String): String? = notes.lineSequence().firstNotNullOfOrNull { MIRROR_LINE.find(it)?.groupValues?.get(1) }

    fun withoutMirrorLine(notes: String): String =
        notes.lines().filterNot { MIRROR_LINE.containsMatchIn(it) }.joinToString("\n").trim()

    /** 存盘用:记下最近一次查到的版本,下次打开 App 时红点还在。 */
    fun toJson(r: ReleaseInfo): JsonObject = buildJsonObject {
        put("version", r.version)
        put("name", r.name)
        put("notes", r.notes)
        r.date?.let { put("date", it) }
        put("pageUrl", r.pageUrl)
        r.apkUrl?.let { put("apkUrl", it) }
        r.mirrorUrl?.let { put("mirrorUrl", it) }
    }

    fun fromJson(o: JsonObject): ReleaseInfo? = runCatching {
        ReleaseInfo(
            version = o.str("version")!!, name = o.str("name") ?: o.str("version")!!, notes = o.str("notes").orEmpty(),
            date = o.str("date"), pageUrl = o.str("pageUrl") ?: MikaLinks.RELEASES, apkUrl = o.str("apkUrl"), mirrorUrl = o.str("mirrorUrl"),
        )
    }.getOrNull()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
