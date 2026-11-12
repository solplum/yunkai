package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.model.Part
import kotlinx.serialization.json.JsonObject
import com.wangzan101.mika.core.assembly.toolDispatcher

/** 测试里直接驱动分发器(测工具本身,不测执行顺序);产品代码只能经执行入口拿到凭证。 */
internal suspend fun ToolDispatcher.execute(call: Part.ToolCall, step: Int? = null, sub: SubActions? = null): ToolExecution =
    execute(call, ActionPass(), step, sub)

/** 测试里按名字问工具的声明(产品代码经本任务的分发器问)。 */
internal fun isWritingTool(name: String, @Suppress("UNUSED_PARAMETER") args: JsonObject): Boolean = toolDispatcher(FakeScreen()).writesScreen(name)

internal fun isSandboxTool(name: String): Boolean = toolDispatcher(FakeScreen()).index[name]?.capability == "sandbox"
