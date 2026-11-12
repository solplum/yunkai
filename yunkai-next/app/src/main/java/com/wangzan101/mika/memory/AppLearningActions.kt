package com.wangzan101.mika.memory

import android.content.Context
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.skills.SkillStatus
import com.wangzan101.mika.ui.LearningActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 结果卡片上记忆与技能提示的按钮,作用在进程内同一份记忆与个人技能([memory])上;读写都在后台线程。 */
class AppLearningActions(private val context: Context, private val memory: MemoryRuntime) : LearningActions {
    private val store get() = memory.store()
    private val skills get() = memory.skills()

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    override suspend fun undo(token: String) = io { store.undo(token) }
    override suspend fun approve(pendingId: String) = io { store.approve(pendingId) }
    override suspend fun forget(memoryId: String) = io { store.forget(memoryId).isSuccess }
    override fun viewSkill(draftId: String) = memory.requestOpen(MemoryOpen(draftId))
    // 恢复时清掉旧结果,免得下一次一不顺又马上被停用
    override suspend fun restoreSkill(name: String) {
        io { skills.update(name) {
            it.copy(status = if (it.learnedFrom != null && "verified" !in it.outcomes) SkillStatus.trial else SkillStatus.active, outcomes = emptyList())
        } }
    }
    override suspend fun deleteSkill(name: String) = io { skills.remove(name) }
    override suspend fun save(notes: RunNotes) {
        memory.publish(notes)
        io {
            runCatching {
                ConversationStore.shared(context).updateTurn(notes.conversationId, notes.runId) {
                    it.copy(memoryNotes = notes.memoryNotes, usedMemory = notes.usedMemory, skillNotes = notes.skillNotes)
                }
            }
        }
    }
}
