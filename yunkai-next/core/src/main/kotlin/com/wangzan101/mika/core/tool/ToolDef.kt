package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.model.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 一个工具发给模型的定义;参数表同时用于生成 schema 和拒绝未知字段。 */
internal class ToolDef(
    val name: String,
    val description: String,
    val props: Map<String, JsonObject> = emptyMap(),
    val required: List<String> = emptyList(),
) {
    val allowed: Set<String> = props.keys + "reason"
    /** 参数里的 reason 是工具自己的字段(比如接管的原因),不是附加的用意说明。 */
    val ownReason = "reason" in props

    fun spec() = ToolSpec(name, description, buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            props.forEach { (k, v) -> put(k, v) }
            if (!ownReason) put("reason", str("Optional short note on why you are calling this; not used for execution."))
        })
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
        put("additionalProperties", false)
    }.toString())
}

internal fun str(d: String, enum: List<String>? = null) = buildJsonObject {
    put("type", "string"); put("description", d)
    enum?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
}

internal fun int(d: String, min: Int? = null, max: Int? = null) = buildJsonObject {
    put("type", "integer"); put("description", d)
    min?.let { put("minimum", it) }; max?.let { put("maximum", it) }
}

internal fun bool(d: String) = buildJsonObject { put("type", "boolean"); put("description", d) }

internal fun strArray(d: String, max: Int) = buildJsonObject {
    put("type", "array"); put("description", d); put("maxItems", max)
    put("items", buildJsonObject { put("type", "string") })
}

internal const val COORD = "Normalized 0-999 (0,0 is top-left, 999,999 is bottom-right) over the latest screen."
internal const val REF = "Element ref such as @e3 from the latest read of this screen; must be paired with its snapshotId."
internal const val SNAP = "snapshotId of the screen read that produced the ref. A mismatch returns ref_expired."
