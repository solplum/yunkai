package com.wangzan101.mika.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAccessErrorsTest {
    private fun notAvailable(status: Int, body: String) = ModelAccessErrors.of(status, body, "m") as ModelError.ModelNotAvailable

    /** 各家「这个账号用不了这个模型」的说法:认成 ModelNotAvailable,带上服务端的原话。 */
    @Test fun accountCannotUseTheModel() {
        val notActivated = """{"code":"InvalidParameter","message":"The product is not activated, please confirm that you have activated products and try again after activation.","request_id":"x"}"""
        val e = notAvailable(400, notActivated)
        assertEquals("m", e.model)
        assertTrue(e.detail, e.detail.startsWith("The product is not activated"))
        assertEquals("Model access denied.", notAvailable(403, """{"error":{"code":"Model.AccessDenied","message":"Model access denied."}}""").detail)
        notAvailable(403, """{"error":{"message":"You are not authorized to access this model","type":"permission_error"}}""")
        notAvailable(403, """{"error":{"message":"Project does not have access to model gpt-x","code":"model_not_found"}}""")
        notAvailable(400, """{"error":{"code":"1220","message":"您当前使用该 API 的权限未开通"}}""")
        notAvailable(403, """{"error":{"message":"无权限访问该模型"}}""")
        // 404 也可能这样说
        notAvailable(404, """{"error":{"message":"Model not activated for this account"}}""")
        assertEquals("plain text not activated", notAvailable(400, "plain text not activated").detail)
    }

    @Test fun modelDoesNotSupportTools() {
        assertTrue(ModelAccessErrors.of(400, """{"error":{"message":"registry.ollama.ai/library/gemma:2b does not support tools"}}""", "gemma") is ModelError.ToolsUnsupported)
        assertTrue(ModelAccessErrors.of(400, """{"error":{"message":"Function calling is not supported for this model"}}""", "x") is ModelError.ToolsUnsupported)
    }

    /** 400 / 403 里说没有这个模型的:认成找不到模型;404 没说别的由调用方照旧处理。 */
    @Test fun modelNotFoundInsideOtherStatuses() {
        assertEquals("m", (ModelAccessErrors.of(400, """{"error":{"message":"Model Not Exist","type":"invalid_request_error"}}""", "m") as ModelError.ModelNotFound).model)
        assertTrue(ModelAccessErrors.of(400, """{"error":{"code":"model_not_found"}}""", "m") is ModelError.ModelNotFound)
        assertNull(ModelAccessErrors.of(404, """{"error":{"code":"model_not_found","message":"The model `x` does not exist or you do not have access to it."}}""", "m"))
    }

    /** Key、额度、请求本身不对的不认;401 一律不看。 */
    @Test fun otherErrorsAreLeftToTheAdapter() {
        assertNull(ModelAccessErrors.of(401, """{"error":{"message":"not activated"}}""", "m"))
        assertNull(ModelAccessErrors.of(400, """{"error":{"message":"Invalid parameter: messages"}}""", "m"))
        assertNull(ModelAccessErrors.of(403, """{"error":{"message":"Incorrect API key provided"}}""", "m"))
        assertNull(ModelAccessErrors.of(500, """{"error":{"message":"not activated"}}""", "m"))
        assertNull(ModelAccessErrors.of(400, "context_length_exceeded", "m"))
    }
}
