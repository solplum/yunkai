package com.wangzan101.mika.core.testing

import com.wangzan101.mika.core.record.RunEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 测试用:旧运行记录里的一条 screen_text(读屏交给模型的元素文字),按读旧记录的同一条路读进来(读出来是编号为 0 的 observed)。
 */
fun legacyScreenText(
    atMs: Long, step: Int, app: String?, names: List<String>, site: String? = null, inputs: List<String>? = null, focused: String? = null,
): RunEvent.Observed = RunEvent.decode(buildJsonObject {
    put("type", "screen_text"); put("atMs", atMs); put("step", step)
    app?.let { put("app", it) }
    put("names", JsonArray(names.map(::JsonPrimitive)))
    site?.let { put("site", it) }
    inputs?.let { put("inputs", JsonArray(it.map(::JsonPrimitive))) }
    focused?.let { put("focused", it) }
}.toString()) as RunEvent.Observed
