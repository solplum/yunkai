package com.wangzan101.mika.core.skills

/** 测试用的内存实现,语义同 app 层的文件实现。 */
class InMemoryPersonalSkills(vararg skills: PersonalSkill) : PersonalSkills {
    private val active = linkedMapOf<String, PersonalSkill>().apply { skills.forEach { put(it.name, it) } }
    private val drafts = linkedMapOf<String, PersonalSkill>()

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
        val s = d.copy(name = name, markdown = PersonalSkills.withName(editedMarkdown ?: d.markdown, name), status = SkillStatus.active, replacesDraftOf = null)
        active[s.name] = s
        return s
    }
    override fun reject(id: String) { drafts.remove(id) }
}

fun personalSkill(
    name: String,
    body: String = "## 入口\n打开 App\n## 步骤\n1. 做事",
    display: String = name,
    description: String = "描述$name",
    status: SkillStatus = SkillStatus.active,
    extraMeta: String = "",
) = PersonalSkill(
    name = name,
    markdown = "---\nname: $name\ndisplay: $display\ndescription: $description\n${extraMeta}source: learned\n---\n$body",
    status = status, learnedFrom = "run1", replacesDraftOf = null, createdAt = 0L, outcomes = emptyList(),
)
