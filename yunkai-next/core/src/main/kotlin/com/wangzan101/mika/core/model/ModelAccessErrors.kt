package com.wangzan101.mika.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 各适配器共用:HTTP 400 / 403 / 404 的报错内容说的是「这个模型不行」,而不是 Key 或请求本身不对时,认出是哪一种。
 * 各家的报错格式、措辞不同,这里只看内容里有没有这几类说法(不分大小写):
 * - 不支持调用工具(「does not support tools」「function calling is not supported」「不支持工具」…)→ [ModelError.ToolsUnsupported];
 * - 这个账号用不了这个模型(「not activated」「not authorized to access this model」「does not have access to the model」
 *   「Model.AccessDenied」「未开通」「无权限」…)→ [ModelError.ModelNotAvailable],带上服务端的原话;
 * - 没有这个模型(「model_not_found」「model not found」「model does not exist」「模型不存在」…)→ [ModelError.ModelNotFound]。
 * 404 没说前两类时由调用方照旧按找不到模型处理;认不出为 null,调用方按状态码处理。401 一律是 Key 的问题,不看。
 */
object ModelAccessErrors {
    private val json = Json { ignoreUnknownKeys = true }

    private val TOOLS_UNSUPPORTED = listOf(
        "does not support tools", "does not support tool", "doesn't support tools", "tools is not supported", "tools are not supported",
        "tool use is not supported", "tool calling is not supported", "function calling is not supported",
        "does not support function calling", "不支持工具", "不支持函数调用", "不支持 function call",
    )

    private val NOT_AVAILABLE = listOf(
        "not activated", "not been activated", "model.accessdenied", "model access denied", "accessdenied.unpurchased",
        "not authorized to access this model", "not authorized to use this model", "not authorized to access the model",
        "do not have access to the model", "does not have access to the model", "does not have access to model",
        "do not have access to model", "no access to model", "no permission to access the model", "no permission to use the model",
        "model_not_available", "model is not available", "model not available", "not available for your account",
        "未开通", "没有开通", "尚未开通", "无权限", "没有权限使用", "无权访问", "无权使用",
    )

    private val NOT_FOUND = listOf(
        "model_not_found", "model not found", "model does not exist", "model not exist", "no such model", "unknown model",
        "模型不存在", "找不到模型",
    )

    /** [model] 是请求的模型名。 */
    fun of(status: Int, body: String, model: String): ModelError? {
        if (status != 400 && status != 403 && status != 404) return null
        val lower = body.lowercase()
        return when {
            TOOLS_UNSUPPORTED.any { it in lower } -> ModelError.ToolsUnsupported(model)
            NOT_AVAILABLE.any { it in lower } -> ModelError.ModelNotAvailable(model, detail(body))
            status != 404 && NOT_FOUND.any { it in lower } -> ModelError.ModelNotFound(model)
            else -> null
        }
    }

    /** 服务端的原话:JSON 里 `error.message` / `message` / `msg`,不是 JSON 就是整段;截到 [DETAIL_MAX] 个字。 */
    internal fun detail(body: String): String {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val err = root?.get("error")
        val text = (err as? JsonObject)?.str("message") ?: (err as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: root?.str("message") ?: root?.str("msg") ?: body
        return text.trim().take(DETAIL_MAX)
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private const val DETAIL_MAX = 160
}
