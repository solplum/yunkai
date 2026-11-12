package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.ModelError
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelErrorReasonTest {
    /** 找不到模型:知道模型名时说清是哪个、去哪改、模型名大概长什么样;不知道时退回一般的说法。 */
    @Test fun modelNotFoundNamesTheModel() {
        assertEquals(
            "模型「gemini」在这个服务商那里不存在。去设置 › 模型,检查模型名(要填完整的模型 ID,比如 Gemini 的模型名类似 gemini-2.5-flash)或改用对应的预设。",
            StopRules.modelFailed(ModelError.ModelNotFound("gemini")).reason,
        )
        assertEquals("找不到该模型,请到设置 › 模型检查模型名和 Base URL 是否正确。", StopRules.modelFailed(ModelError.ModelNotFound()).reason)
        assertEquals("找不到模型「gemini」", ModelError.ModelNotFound("gemini").message)
    }

    /** 这个账号用不了的模型、不支持工具的模型:说清是哪个、去哪换,或到服务商后台开通。 */
    @Test fun modelNotAvailableNamesTheModel() {
        assertEquals(
            "模型「qwen-max」这个账号没开通或没权限用。去设置 › 模型换一个模型，或到服务商后台开通。",
            StopRules.modelFailed(ModelError.ModelNotAvailable("qwen-max", "not activated")).reason,
        )
        assertEquals(
            "模型「gemma」不支持调用工具,Mika 干活要靠工具。去设置 › 模型换一个会调用工具的模型。",
            StopRules.modelFailed(ModelError.ToolsUnsupported("gemma")).reason,
        )
    }
}
