package com.wangzan101.mika.cap.web

/**
 * 进程里所有任务的网页标签共用的上限(全局至多 [max] 个):每个任务一个标签池,各池作为 [Member] 加入。
 * 「最近用过」用这里的同一个钟,跨任务也比得出谁最久没用。成员的方法都在主线程上调。
 */
class TabBudget(val max: Int = DEFAULT_MAX) {
    interface Member {
        fun tabCount(): Int
        /** 最久没用、可以关掉的标签(编号, 最近用过的时刻);锁住的(接管中)和 [except] 不算。没有为 null。 */
        fun oldestSpare(except: String?): Pair<String, Long>?
        /** 因为全局上限关掉它(发「limit」事件)。 */
        fun evictForBudget(tabId: String)
    }

    private val members = mutableListOf<Member>()
    private var clock = 0L

    @Synchronized fun tick(): Long = ++clock

    @Synchronized fun join(m: Member) {
        if (members.none { it === m }) members += m
    }

    @Synchronized fun leave(m: Member) {
        members.removeAll { it === m }
    }

    fun total(): Int = snapshot().sumOf { it.tabCount() }

    fun full(): Boolean = total() >= max

    /** 关掉所有任务里最久没用、可以关的那个标签;没有可关的返回 false。 */
    fun evictOldest(except: String?): Boolean {
        val victim = snapshot().mapNotNull { m -> m.oldestSpare(except)?.let { m to it } }.minByOrNull { it.second.second } ?: return false
        victim.first.evictForBudget(victim.second.first)
        return true
    }

    @Synchronized private fun snapshot() = members.toList()

    companion object {
        const val DEFAULT_MAX = 8
    }
}
