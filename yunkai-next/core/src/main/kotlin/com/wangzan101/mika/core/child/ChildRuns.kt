package com.wangzan101.mika.core.child

import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.RunSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable enum class ChildPhase { Running, Completed, Interrupted, Unknown }

@Serializable data class ChildRecord(val id: String, val parentId: String, val phase: ChildPhase,
    val result: String? = null, val atMs: Long)

interface ChildRunStore {
    fun save(record: ChildRecord)
    fun get(id: String): ChildRecord?
    fun all(): List<ChildRecord>
}

class MemoryChildRunStore : ChildRunStore {
    private val records = linkedMapOf<String, ChildRecord>()
    @Synchronized override fun save(record: ChildRecord) { records[record.id] = record }
    @Synchronized override fun get(id: String) = records[id]
    @Synchronized override fun all() = records.values.toList()
}

data class ChildResult(val id: RunId, val phase: ChildPhase, val text: String)

fun interface ChildRunHost { suspend fun spawn(spec: RunSpec, format: String?): ChildResult }

/** 子 Run 先保存结论再交给父 Run；父 Run 消失时保留 Unknown 记录，不自动重跑。 */
class ChildAgentRunner(
    private val create: (RunSpec) -> AgentLoop,
    private val store: ChildRunStore,
    private val ownerAlive: (RunId) -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
) : ChildRunHost {
    private val active = HashMap<RunId, MutableSet<AgentLoop>>()

    override suspend fun spawn(spec: RunSpec, format: String?): ChildResult {
        require(spec.parent != null && spec.provenance.origin == com.wangzan101.mika.core.run.RunOrigin.Child)
        val parent = spec.parent
        val agent = create(spec)
        synchronized(active) { active.getOrPut(parent) { mutableSetOf() } += agent }
        store.save(ChildRecord(spec.id.value, parent.value, ChildPhase.Running, atMs = clock()))
        try {
            val end = agent.run(spec, RunInbox())
            val raw = when (end) {
                is TaskState.Succeeded -> end.answer
                is TaskState.Failed -> end.reason
                TaskState.Cancelled -> "已中断"
                else -> "未取得结果"
            }
            val phase = if (end == TaskState.Cancelled) ChildPhase.Interrupted else ChildPhase.Completed
            val answer = raw.take(MAX_RESULT_CHARS)
            val checked = if (format == "json" && runCatching { Json.parseToJsonElement(answer) }.isFailure)
                "$answer\n(子 Run 的 JSON 格式未通过校验，保留原文)" else answer
            store.save(ChildRecord(spec.id.value, parent.value, phase, checked, clock()))
            if (!ownerAlive(parent)) {
                store.save(ChildRecord(spec.id.value, parent.value, ChildPhase.Unknown, checked, clock()))
                return ChildResult(spec.id, ChildPhase.Unknown, checked)
            }
            return ChildResult(spec.id, phase, checked)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { store.save(ChildRecord(spec.id.value, parent.value, ChildPhase.Interrupted, "已中断", clock())) }
            throw e
        } finally {
            synchronized(active) { active[parent]?.remove(agent); if (active[parent]?.isEmpty() == true) active.remove(parent) }
        }
    }

    fun cancelAll(parent: RunId) { synchronized(active) { active[parent]?.toList() }.orEmpty().forEach(AgentLoop::cancel) }

    companion object { const val MAX_RESULT_CHARS = 4000 }
}
