package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.memory.MemoryIndex
import com.wangzan101.mika.core.memory.MemoryItem
import com.wangzan101.mika.core.memory.MemoryKind
import com.wangzan101.mika.core.memory.MemoryRejected
import com.wangzan101.mika.core.memory.MemorySource
import com.wangzan101.mika.core.memory.MemoryStatus
import com.wangzan101.mika.core.memory.MemoryStore
import com.wangzan101.mika.core.knowledge.FrontMatter
import com.wangzan101.mika.core.skills.SkillDrafter
import com.wangzan101.mika.core.assembly.knownToolNames
import com.wangzan101.mika.core.skills.PersonalSkill
import com.wangzan101.mika.core.skills.PersonalSkills
import com.wangzan101.mika.core.skills.SkillStatus
import com.wangzan101.mika.core.knowledge.AppKnowledgeDraftStore
import com.wangzan101.mika.core.knowledge.AppKnowledgeStage
import com.wangzan101.mika.settings.MemorySettings
import com.wangzan101.mika.settings.MemorySwitches
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.wangzan101.mika.i18n.uiLocale
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.tool.Verb

/** 页面三段:已记下的、个人技能、等你确认的(待批准的做法 + 技能与 App 知识草稿)。 */
enum class Segment { Memories, Skills, Pending }

/** 列表里的一条记忆;[source] 是「你在 10 月 23 日说:『…』」或「你手写的」。 */
data class Row(
    val id: String, val kind: MemoryKind, val content: String, val keywords: List<String>, val source: String,
    val pinned: Boolean, val pending: Boolean, val replaces: String?,
)

/** [clearStage]:0 没在清除,1 第一次确认,2 第二次确认。[message] 是一次性的提示(比如「这类信息我不会记」)。 */
data class MemoryUi(
    val segment: Segment = Segment.Memories,
    val query: String = "",
    val pending: List<Row> = emptyList(),
    val rows: List<Row> = emptyList(),
    val brokenNotice: String? = null,
    val switches: MemorySwitches = MemorySwitches(),
    val message: String? = null,
    val clearStage: Int = 0,
    val skills: SkillsUi = SkillsUi(),
) {
    /** 「待确认」段的条数:待批准的记忆 + 技能草稿 + 还没收下的 App 知识草稿。 */
    val pendingCount: Int get() = pending.size + skills.drafts.size + skills.appKnowledge.count { it.stage == AppKnowledgeStage.Draft }
}

/**
 * 「记忆与技能」页的状态:分段、搜索(空查询按更新时间倒序)、待批准的做法置顶、编辑、置顶、删除、手写新增、
 * 全部清除(两次确认)和三个开关。技能分段见 [SkillsUi]。
 */
class MemoryViewModel(
    private val store: MemoryStore,
    private val settings: MemorySettings,
    private val skills: PersonalSkills?,
    scope: CoroutineScope,
    brokenNotice: StateFlow<String?> = MutableStateFlow(null),
    /** 全部清除:记忆文件、坏文件另存、个人技能都删掉并重建密钥。 */
    private val wipe: () -> Unit = { store.clearAll() },
    private val clock: () -> Long = System::currentTimeMillis,
    /** 删掉了一条记忆:对话里指向它的提示也去掉。 */
    private val onForgotten: (String) -> Unit = {},
    /** 收下改过的技能时校验提到的工具名。 */
    private val toolNames: Set<String> = knownToolNames(),
    /** 跑技能脚本的工具名。 */
    private val scriptTool: String? = knownTools().nameOf(Verb.RunBundledScript),
    /** 收下改过的技能时:提到跑脚本的工具的,脚本得是清单里标为可运行的。 */
    private val runnableScripts: Set<String> = emptySet(),
    private val appDrafts: AppKnowledgeDraftStore? = null,
) {
    private val local = MutableStateFlow(MemoryUi())
    private val skillsVersion = MutableStateFlow(0)

    val ui: StateFlow<MemoryUi> = combine(local, store.items, settings.switches, brokenNotice, skillsVersion) { l, items, sw, broken, _ ->
        val all = filtered(items, l.query)
        l.copy(
            pending = all.filter { it.status == MemoryStatus.pending }.map(::row),
            rows = all.filter { it.status == MemoryStatus.active }.map(::row),
            brokenNotice = broken, switches = sw,
            skills = skillsUi(),
        )
    }.stateIn(scope, SharingStarted.Eagerly, MemoryUi())

    fun select(s: Segment) = local.update { it.copy(segment = s) }
    fun search(q: String) = local.update { it.copy(query = q) }
    fun dismissMessage() = local.update { it.copy(message = null) }

    fun edit(id: String, content: String, keywords: List<String>) =
        report(store.update(id, content, keywords, MemorySource("manual", atMs = clock())).exceptionOrNull())
    fun setPinned(id: String, pinned: Boolean) = store.setPinned(id, pinned)
    fun delete(id: String) {
        if (store.forget(id).isSuccess) onForgotten(id)
    }
    fun approve(id: String) { store.approve(id) }
    /** 待批准的做法点「不要」= 删除。 */
    fun reject(id: String) = delete(id)

    fun add(kind: MemoryKind, content: String, keywords: List<String>) =
        report(store.add(kind, content, keywords, MemorySource("manual", atMs = clock())).exceptionOrNull())

    fun requestClear() = local.update { it.copy(clearStage = 1) }
    fun cancelClear() = local.update { it.copy(clearStage = 0) }
    fun confirmClear() {
        val stage = local.value.clearStage
        if (stage == 2) wipe()
        local.update { it.copy(clearStage = if (stage == 1) 2 else 0) }
        skillsVersion.update { it + 1 }
    }

    fun setSwitches(transform: (MemorySwitches) -> MemorySwitches) = settings.update(transform)

    private fun report(e: Throwable?) {
        val msg = when ((e as? MemoryRejected)?.code) {
            null -> null
            "sensitive" -> tr(R.string.memory_sensitive)
            "too_long" -> tr(R.string.memory_too_long)
            "memory_full" -> tr(R.string.memory_full)
            "unavailable" -> tr(R.string.memory_unavailable)
            else -> tr(R.string.memory_not_changed)
        }
        if (msg != null) local.update { it.copy(message = msg) }
    }

    private fun filtered(items: List<MemoryItem>, query: String): List<MemoryItem> =
        if (query.isBlank()) items.sortedByDescending { it.updatedAt }
        else MemoryIndex(items).search(query, items.size.coerceAtLeast(1), includePending = true).map { it.first }

    private fun row(m: MemoryItem) = Row(m.id, m.kind, m.content, m.keywords, sourceLine(m.source), m.pinned, m.status == MemoryStatus.pending, m.replaces)

    // ---- 技能分段 ----
    private fun skillsUi(): SkillsUi = skills?.let { SkillsUi.of(it, appDrafts) } ?: SkillsUi()
    private fun skillsChanged() = skillsVersion.update { it + 1 }

    /** 收下草稿;[edited] 是用户改过的全文,和起草出来的一样要过格式与安全校验,不过就不收并说明。 */
    fun approveSkill(draftId: String, edited: String? = null) {
        val p = skills ?: return
        val problem = edited?.let { SkillDrafter.validate(it, toolNames, scriptTool, runnableScripts) }
        if (problem != null) {
            local.update { it.copy(message = SKILL_PROBLEMS[problem] ?: tr(R.string.skill_bad_format)) }
            return
        }
        if (p.approve(draftId, edited) == null) {
            local.update { it.copy(message = tr(R.string.skill_changed)) }
        }
        skillsChanged()
    }

    fun rejectSkill(draftId: String) { skills?.reject(draftId); skillsChanged() }

    fun approveAppDraft(id: String) { appDrafts?.approve(id); skillsChanged() }
    fun rejectAppDraft(id: String) { appDrafts?.reject(id); skillsChanged() }

    fun suspendSkill(name: String) = changeSkill(name) { it.copy(status = SkillStatus.suspended) }

    /** 恢复时清掉旧结果,免得下一次一不顺又马上被停用。 */
    fun restoreSkill(name: String) = changeSkill(name) {
        it.copy(status = if (it.learnedFrom != null && "verified" !in it.outcomes) SkillStatus.trial else SkillStatus.active, outcomes = emptyList())
    }

    fun deleteSkill(name: String) { skills?.remove(name); skillsChanged() }

    fun restorePreviousSkill(name: String) {
        val p = skills ?: return
        val revision = p.revisions(name).firstOrNull() ?: return
        if (!p.restore(revision.id)) local.update { it.copy(message = tr(R.string.skill_restore_failed)) }
        skillsChanged()
    }

    private fun changeSkill(name: String, change: (PersonalSkill) -> PersonalSkill) {
        val p = skills ?: return
        p.get(name)?.let { p.put(change(it)) }
        skillsChanged()
    }

    companion object {
        private val SKILL_PROBLEMS get() = mapOf(
            "sensitive" to tr(R.string.memory_sensitive),
            "invalid_name" to tr(R.string.skill_invalid_name),
            "missing_description" to tr(R.string.skill_missing_description),
            "missing_section" to tr(R.string.skill_missing_section),
            "too_long" to tr(R.string.skill_too_long),
            "unknown_tool" to tr(R.string.skill_unknown_tool),
        )

        fun sourceLine(s: MemorySource): String =
            if (s.type == "manual") tr(R.string.memory_manual)
            else SimpleDateFormat(tr(R.string.date_pattern_md), uiLocale()).format(Date(s.atMs)).let { day ->
                if (s.channel != null && s.channel != "Ui") tr(R.string.memory_said_via, day, s.channel, s.quote.orEmpty())
                else tr(R.string.memory_said, day, s.quote.orEmpty())
            }
    }
}

/** 技能分段的内容:待收下的草稿和已收下的个人技能(在用的在前)。 */
data class AppKnowledgeRow(val id: String, val name: String, val markdown: String, val stage: AppKnowledgeStage)

data class SkillsUi(val drafts: List<SkillRow> = emptyList(), val installed: List<SkillRow> = emptyList(),
    val appKnowledge: List<AppKnowledgeRow> = emptyList()) {
    companion object {
        fun of(skills: PersonalSkills, appDrafts: AppKnowledgeDraftStore? = null): SkillsUi {
            val installed = skills.list()
            val byName = installed.associateBy { it.name }
            val drafts = skills.drafts().map { (id, d) ->
                row(id, d).copy(changedLines = d.replacesDraftOf?.let { byName[it] }?.let { changedLines(it.markdown, d.markdown) }.orEmpty())
            }
            val appRows = appDrafts?.all().orEmpty().filter { it.stage != AppKnowledgeStage.Archived }
                .map { AppKnowledgeRow(it.id, it.appName, it.markdown, it.stage) }
            return SkillsUi(drafts, installed.sortedBy { it.status == SkillStatus.suspended }.map { row(it.name, it, skills) }, appRows)
        }

        private fun row(id: String, s: PersonalSkill, skills: PersonalSkills? = null): SkillRow {
            val meta = FrontMatter.parse(s.markdown).first
            return SkillRow(
                id, s.name, (meta["display"] as? String)?.takeIf { it.isNotBlank() } ?: s.name, meta["description"] as? String ?: "",
                s.markdown, suspended = s.status == SkillStatus.suspended, trial = s.status == SkillStatus.trial,
                canRestore = skills?.revisions(s.name)?.isNotEmpty() == true,
                recent = s.outcomes.takeLast(3).map(::outcomeLabel),
            )
        }

        /** 更新草稿相对原技能:「+ 」新增的行、「- 」去掉的行(不比空行)。 */
        fun changedLines(old: String, new: String): List<String> {
            val before = old.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            val after = new.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            return after.filter { it !in before }.map { "+ $it" } + before.filter { it !in after }.map { "- $it" }
        }

        private fun outcomeLabel(o: String) = when (o) {
            "verified" -> tr(R.string.verify_passed)
            "unverified" -> tr(R.string.verify_unverified)
            "verifier_rejected" -> tr(R.string.verify_returned)
            else -> tr(R.string.capsule_failed)
        }
    }
}

/** [id] 是草稿编号(已收下的为技能名);[changedLines] 是更新草稿相对原技能改了的行。 */
data class SkillRow(
    val id: String, val name: String, val display: String, val description: String, val markdown: String,
    val suspended: Boolean = false, val trial: Boolean = false, val canRestore: Boolean = false,
    val recent: List<String> = emptyList(), val changedLines: List<String> = emptyList(),
)
