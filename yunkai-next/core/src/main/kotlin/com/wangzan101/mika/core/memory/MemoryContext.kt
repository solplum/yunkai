package com.wangzan101.mika.core.memory

/** [message] 为 null 表示这次没有记忆可带;[usedIds] 是块里实际列出的条目,顺序同列表。 */
data class MemoryBlock(val message: String?, val usedIds: List<String>)

/**
 * 每个任务的记忆块:作为一条用户消息放在对话上文之后、任务原文之前。
 * 置顶的 + 过阈值的相关条目(已批准的做法单列且有上限),总长受预算约束。
 */
object MemoryContext {
    const val MAX_CHARS = 1200
    const val MAX_PINNED = 5
    const val MAX_RELEVANT = 8
    const val MAX_PROCEDURES = 3
    const val MIN_SCORE = 0.5

    private const val FACTS_HEADER = "关于用户的记忆(用户告诉过你的;可能过时,和用户这次说的冲突时以这次为准):"
    private const val PROCEDURES_HEADER = "用户批准的做法(和用户这次说的冲突时以这次为准):"

    /** [rewrite]:做法里提到的旧工具名换成现在的(见 LegacyNames.rewrite);默认原样。 */
    fun build(items: List<MemoryItem>, goal: String, lastUserTurn: String?, rewrite: (String) -> String = { it }): MemoryBlock {
        val active = items.filter { it.status == MemoryStatus.active }
        val query = listOfNotNull(goal, lastUserTurn).joinToString("\n")
        val ranked = MemoryIndex(active).search(query, limit = active.size)
        val score = ranked.associate { it.first.id to it.second }

        // 做法的上限把置顶的也算在内
        var procedures = 0
        fun procedureFits(m: MemoryItem) = m.kind != MemoryKind.procedure || procedures < MAX_PROCEDURES

        val pinned = mutableListOf<MemoryItem>()
        for (m in active.filter { it.pinned }
            .sortedWith(compareByDescending<MemoryItem> { score[it.id] ?: 0.0 }.thenByDescending { it.updatedAt })) {
            if (pinned.size == MAX_PINNED) break
            if (!procedureFits(m)) continue
            if (m.kind == MemoryKind.procedure) procedures++
            pinned += m
        }
        val relevant = mutableListOf<MemoryItem>()
        for ((item, s) in ranked) {
            if (s < MIN_SCORE || relevant.size == MAX_RELEVANT) break
            if (item.pinned || !procedureFits(item)) continue
            if (item.kind == MemoryKind.procedure) procedures++
            relevant += item
        }

        // 保留优先级从高到低:置顶(按分数)在前,相关(按分数)在后;超长从末尾去掉
        val keep = (pinned + relevant).toMutableList()
        while (keep.isNotEmpty()) {
            val (message, ids) = render(keep, rewrite)
            if (message.length <= MAX_CHARS) return MemoryBlock(message, ids)
            keep.removeAt(keep.lastIndex)
        }
        return MemoryBlock(null, emptyList())
    }

    private fun render(items: List<MemoryItem>, rewrite: (String) -> String): Pair<String, List<String>> {
        val (procedures, facts) = items.partition { it.kind == MemoryKind.procedure }
        val sections = listOf(FACTS_HEADER to facts, PROCEDURES_HEADER to procedures).filter { it.second.isNotEmpty() }
        val message = sections.joinToString("\n\n") { (header, list) ->
            header + "\n" + list.withIndex().joinToString("\n") { (i, m) ->
                "${i + 1}. ${if (m.kind == MemoryKind.procedure) rewrite(m.content) else m.content}"
            }
        }
        return message to (facts + procedures).map { it.id }
    }
}
