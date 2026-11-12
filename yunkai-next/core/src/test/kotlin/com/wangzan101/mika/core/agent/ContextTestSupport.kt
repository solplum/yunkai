package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part

/** 一次请求里去掉每步接在末尾的运行时状态,便于按旧的位置看工具结果与提示。 */
internal fun List<Message>.withoutRuntime(): List<Message> = map(ContextBuilder::withoutRuntimeState).filter { it.parts.isNotEmpty() }

/** 消息的文字(不含运行时状态):上下文拆掉外壳只留正文,其余原样。 */
internal fun Message.plainText(): String = ContextBuilder.withoutRuntimeState(this).let { m ->
    ContextBuilder.bodyOf(m) ?: m.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
}

/** 某种上下文的正文,按出现顺序。 */
internal fun List<Message>.contextBodies(vararg kinds: ContextKind): List<String> =
    withoutRuntime().filter { ContextBuilder.kindOf(it) in kinds }.mapNotNull { ContextBuilder.bodyOf(it) }

/** 一次请求里的运行时状态正文,按出现顺序。 */
internal fun List<Message>.runtimeStates(): List<String> = flatMap { it.parts }.filterIsInstance<Part.Text>()
    .filter { ContextBuilder.kindOf(it.text) == ContextKind.RuntimeState }
    .map { ContextBuilder.bodyOf(com.wangzan101.mika.core.model.Message(com.wangzan101.mika.core.model.Role.User, listOf(it), com.wangzan101.mika.core.model.Author.Mika))!! }
