package com.wangzan101.mika.task

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.knowledge.FrontMatter
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.knowledge.KnowledgeStore
import com.wangzan101.mika.core.knowledge.AppKnowledgeDrafter
import com.wangzan101.mika.core.knowledge.AppKnowledgeDraftStore
import com.wangzan101.mika.core.memory.Change
import com.wangzan101.mika.core.memory.MemoryExtractor
import com.wangzan101.mika.core.memory.MemorySource
import com.wangzan101.mika.core.memory.MemoryStore
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.observation.PlaceKind
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.skills.DraftInput
import com.wangzan101.mika.core.skills.DraftResult
import com.wangzan101.mika.core.skills.Eligibility
import com.wangzan101.mika.core.skills.PersonalSkills
import com.wangzan101.mika.core.skills.SkillDrafter
import com.wangzan101.mika.core.skills.SkillNote
import com.wangzan101.mika.core.skills.SkillOutcomes
import com.wangzan101.mika.core.skills.SkillResult
import com.wangzan101.mika.core.skills.SkillStatus
import com.wangzan101.mika.memory.RunNotes
import com.wangzan101.mika.settings.MemorySwitches
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.wangzan101.mika.core.tool.ToolIndex

/** 一次运行结束后,学东西要用的全部材料;由 [TaskRun] 在任务收尾时交出。 */
data class RunAftermath(
    val conversationId: String,
    val runId: String?,
    val goal: String,
    val end: TaskState,
    /** 用户本次说的话;记忆关着时为空。 */
    val utterances: List<String>,
    /** 任务中 memory_save 存下的。 */
    val saved: List<Change>,
    val usedMemory: List<String>,
    val toolNames: Set<String>,
    /** 按名字查工具做的动作。 */
    val tools: ToolIndex,
    /** 这次运行的事件(从运行记录读);读不到为空。 */
    val events: suspend () -> List<RunEvent>,
    val origin: RunOrigin = RunOrigin.Ui,
    val knowledge: KnowledgeStore? = null,
    /** 交出回答后判的结局;只从办成([RunOutcome.Done])的学。没判成、没判过为 [RunOutcome.Unknown]。 */
    val outcome: RunOutcome = RunOutcome.Unknown,
)

/**
 * 任务结束后:记下用到的个人技能的结果(可能自动停用)、从用户的话里提取记忆、办成的任务起草技能。
 * 办成按交出回答后判的结局([RunAftermath.outcome])算,不按「交出了回答」。
 * 全在结果通知发出之后的后台协程里做([launchAfter]);哪一步出错都不影响任务结局。
 *
 * 不起草的情形另加一条:这次走的是回放的路线([RunEvent.Replay] 有对上的步)——路线已经记住了,
 * 再起草只会把同一条路线抄一遍。只走了几步批量(do_steps)的照常起草,那是模型自己规划的。
 */
class RunLearning(
    private val switches: MemorySwitches,
    /** 记忆总开关关着时为 null。 */
    private val store: MemoryStore?,
    private val skills: PersonalSkills,
    private val catalog: GuideCatalog,
    private val extractor: MemoryExtractor?,
    private val drafter: SkillDrafter?,
    private val ids: () -> String,
    /** 评测钩子:草稿起好直接收下(训练轮)。 */
    private val autoApprove: Boolean = false,
    private val yieldToForeground: suspend () -> Unit = {},
    private val appDrafter: AppKnowledgeDrafter? = null,
    private val appDrafts: AppKnowledgeDraftStore? = null,
) {
    suspend fun learn(a: RunAftermath): RunNotes {
        yieldToForeground()
        val events = guarded(emptyList()) { a.events() }
        val used = events.filterIsInstance<RunEvent.SkillUsed>()
        val skillNotes = recordOutcomes(used, a.end, a.outcome).toMutableList()
        val trialNames = events.filterIsInstance<RunEvent.State>().filter { it.name == "knowledge_trial_used" }.map { it.detail }.distinct()
        trialNames.forEach { name -> guarded(null) { appDrafts?.recordTrial(name, a.outcome == RunOutcome.Done && (a.end as? TaskState.Succeeded)?.verified == true) } }

        val changes = a.saved.toMutableList()
        val store = store
        val extractor = extractor
        // 没有运行编号就没处挂提示,用户看不到也撤销不了:不提取
        val learnable = a.origin == RunOrigin.Ui && a.end is TaskState.Succeeded
        val readWeb = events.filterIsInstance<RunEvent.Observed>().any { it.place?.kind in setOf(PlaceKind.Site, PlaceKind.Page) }
        if (learnable && !readWeb && a.runId != null && store != null && extractor != null && switches.autoRemember && a.utterances.isNotEmpty()) {
            val now = store.clock()
            changes += guarded(emptyList()) {
                yieldToForeground()
                extractor.extract(a.utterances, MemoryExtractor.related(a.utterances, store.items.value), store) { q ->
                    MemorySource("user_said", q, a.conversationId, now, a.origin.name)
                }
            }
        }

        // 做法只从办成的学:回答如实说没办成、只办了一部分,或没判成的,不起草
        val done = learnable && a.outcome == RunOutcome.Done
        if (done) draft(a, events, used.map { it.name }.distinct())?.let(skillNotes::add)
        if (done) draftApp(a, events)?.let(skillNotes::add)
        return RunNotes(a.conversationId, a.runId.orEmpty(), MemoryExtractor.notes(changes), a.usedMemory, skillNotes)
    }

    /** 每份技能单独读改写(和别的任务同时记结果也不丢);一份出错不影响别的。 */
    private suspend fun recordOutcomes(used: List<RunEvent.SkillUsed>, end: TaskState, outcome: RunOutcome): List<SkillNote> {
        val result = SkillOutcomes.resultOf(end, outcome) ?: return emptyList()
        return used.groupBy { it.name }.mapNotNull { (name, uses) ->
            // 只是开头塞了全文、Agent 没去看的,不能确定照着做了
            val injectedOnly = uses.none { it.how == "viewed" }
            val next = guarded(null) {
                skills.update(name) { s -> if (s.status == SkillStatus.active || s.status == SkillStatus.trial) SkillOutcomes.record(s, result, followed = true, injectedOnly = injectedOnly) else null }
            } ?: return@mapNotNull null
            when (next.status) {
                SkillStatus.suspended -> SkillNote(tr(R.string.skill_suspended), suspended = name)
                SkillStatus.active -> if (uses.isNotEmpty() && result == SkillResult.verified) SkillNote(tr(R.string.skill_verified, name)) else null
                else -> null
            }
        }
    }

    private suspend fun draft(a: RunAftermath, events: List<RunEvent>, used: List<String>): SkillNote? {
        val d = drafter ?: return null
        val runId = a.runId ?: return null
        if (!switches.learnSkills) return null
        if (events.any { it is RunEvent.Replay && it.matched }) return null
        // 照着个人技能做成的只记一次结果,不再起草
        val followed = used.firstOrNull().takeIf { SkillOutcomes.resultOf(a.end, a.outcome) == SkillResult.verified }
        val done = a.end as? TaskState.Succeeded
        if (Eligibility.check(events, followed, a.tools, verified = done?.verified == true) != null) return null
        val r = guarded(null) {
            yieldToForeground()
            d.draft(DraftInput(runId, a.goal, done?.answer.orEmpty(), done?.sources.orEmpty(), events, a.toolNames, a.tools), skills, catalog)
        }
        val draft = r as? DraftResult.Draft ?: return null
        val id = ids()
        guarded(Unit) { skills.putPending(id, draft.skill) }
        if (autoApprove) {
            guarded(null) { skills.approve(id) }
            return null
        }
        val description = FrontMatter.parse(draft.skill.markdown).first["description"] as? String ?: draft.skill.name
        return SkillNote(tr(R.string.skill_learned, description), draftId = id)
    }

    private suspend fun draftApp(a: RunAftermath, events: List<RunEvent>): SkillNote? {
        val drafter = appDrafter ?: return null
        val drafts = appDrafts ?: return null
        val knowledge = a.knowledge ?: return null
        val runId = a.runId ?: return null
        val done = a.end as? TaskState.Succeeded ?: return null
        if (!switches.learnSkills || !done.verified || events.any { it is RunEvent.Replay && it.matched }) return null
        val packages = events.filterIsInstance<RunEvent.Observed>().mapNotNull { e ->
            e.place?.takeIf { it.kind == PlaceKind.App }?.name
        }.distinct()
        val app = packages.singleOrNull()?.let(knowledge::byPackage) ?: return null
        if (drafts.all().any { it.appName == app.name && it.stage != com.wangzan101.mika.core.knowledge.AppKnowledgeStage.Archived }) return null
        val draft = guarded(null) {
            yieldToForeground()
            drafter.draft(runId, a.goal, done.answer, events, a.tools, app, ids(), events.lastOrNull()?.atMs ?: 0L)
        } ?: return null
        if (!guarded(false) { drafts.putPending(draft) }) return null
        return SkillNote(tr(R.string.skill_app_draft, app.display))
    }

    /** 学的每一步出错都不影响别的步骤;取消照常往外抛。 */
    private suspend fun <T> guarded(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fallback
    }

    companion object {
        /** 先发结果通知,再在后台学:用户先看到结果,学东西慢一点没关系。 */
        suspend fun launchAfter(scope: CoroutineScope, notify: suspend () -> Unit, learn: suspend () -> Unit): Job {
            notify()
            return scope.launch { learn() }
        }
    }
}
