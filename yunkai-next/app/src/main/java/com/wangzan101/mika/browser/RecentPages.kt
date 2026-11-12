package com.wangzan101.mika.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/** 最近打开的一页:网址、标题(读不到时为空)、打开的时间。 */
data class RecentPage(val url: String, val title: String, val openedAtMs: Long)

/**
 * 浏览器页「最近打开」:用户在 Mika 的浏览器页里打开过的网页,最近的在前,至多 [limit] 条。
 * 同一网址(不计 # 后面的部分)只留一条,再打开时挪到最前、标题换成新的。
 * 存在 App 私有目录的一个 JSON 文件里;文件坏了就从空表重新开始。
 */
class RecentPages(dir: File, private val limit: Int = LIMIT, private val clock: () -> Long = System::currentTimeMillis) {
    private val file = File(dir, FILE)
    private val _entries = MutableStateFlow(load())

    /** 最近的在前。 */
    val entries: StateFlow<List<RecentPage>> = _entries.asStateFlow()

    @Synchronized
    fun add(url: String, title: String?) {
        val key = key(url)
        if (key.isEmpty()) return
        val page = RecentPage(url.trim(), title?.trim().orEmpty(), clock())
        val rest = _entries.value.filter { key(it.url) != key }
        save((listOf(page) + rest).take(limit))
    }

    /** 当前页标题后到时补上:只改已有的那一条,不挪位置。 */
    @Synchronized
    fun retitle(url: String, title: String) {
        val key = key(url)
        val t = title.trim()
        if (t.isEmpty() || _entries.value.none { key(it.url) == key && it.title != t }) return
        save(_entries.value.map { if (key(it.url) == key) it.copy(title = t) else it })
    }

    @Synchronized
    fun clear() {
        save(emptyList())
    }

    private fun key(url: String) = url.trim().substringBefore('#')

    private fun load(): List<RecentPage> {
        if (!file.isFile) return emptyList()
        return try {
            (Json.parseToJsonElement(file.readText()) as? JsonArray)?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val url = (o["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                RecentPage(url, (o["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(), (o["at"] as? JsonPrimitive)?.longOrNull ?: 0L)
            }.orEmpty().take(limit)
        } catch (e: SerializationException) {
            emptyList()
        }
    }

    private fun save(list: List<RecentPage>) {
        _entries.value = list
        file.parentFile?.mkdirs()
        val json = JsonArray(list.map { buildJsonObject { put("url", it.url); put("title", it.title); put("at", it.openedAtMs) } }).toString()
        // 先写临时文件再改名,写到一半被杀不会留下半个文件
        val tmp = File(file.parentFile, "$FILE.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) {
            file.writeText(json)
            tmp.delete()
        }
    }

    companion object {
        const val LIMIT = 10
        private const val FILE = "browser_recent.json"
    }
}
