package com.wangzan101.mika.task

import com.wangzan101.mika.core.memory.MemoryItem
import com.wangzan101.mika.core.memory.MemoryPersistence
import com.wangzan101.mika.core.memory.MemoryStore
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.skills.PersonalSkill
import com.wangzan101.mika.core.skills.PersonalSkills
import com.wangzan101.mika.core.skills.SkillStatus

/** 按顺序回复固定文字的模型;记下每次请求。 */
class TextModel(vararg replies: String) : ChatModel {
    override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 64_000, reasoning = false)
    private val queue = replies.toMutableList()
    val requests = mutableListOf<List<Message>>()
    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        requests += messages
        return ModelReply(listOf(Part.Text(queue.removeAt(0))), StopReason.EndTurn, null)
    }
}

fun memoryStore(): MemoryStore {
    var seq = 0
    return MemoryStore(object : MemoryPersistence {
        override fun load() = emptyList<MemoryItem>()
        override fun save(items: List<MemoryItem>) {}
    }, { 7L }, { "m${++seq}" })
}

class MapSkills(vararg s: PersonalSkill) : PersonalSkills {
    val active = linkedMapOf<String, PersonalSkill>().apply { s.forEach { put(it.name, it) } }
    val drafts = linkedMapOf<String, PersonalSkill>()
    override fun list() = active.values.toList()
    override fun get(name: String) = active[name]
    override fun put(s: PersonalSkill) { active[s.name] = s }
    override fun remove(name: String) { active.remove(name) }
    override fun update(name: String, transform: (PersonalSkill) -> PersonalSkill?): PersonalSkill? =
        active[name]?.let(transform)?.also { active[it.name] = it }
    override fun pending() = drafts.values.toList()
    override fun drafts(): Map<String, PersonalSkill> = drafts.toMap()
    override fun putPending(id: String, s: PersonalSkill) { drafts[id] = s.copy(status = SkillStatus.pending) }
    override fun approve(id: String, editedMarkdown: String?): PersonalSkill? {
        val d = drafts.remove(id) ?: return null
        val name = d.replacesDraftOf ?: d.name
        return d.copy(name = name, markdown = PersonalSkills.withName(editedMarkdown ?: d.markdown, name), status = SkillStatus.active, replacesDraftOf = null)
            .also { active[name] = it }
    }
    override fun reject(id: String) { drafts.remove(id) }
}
