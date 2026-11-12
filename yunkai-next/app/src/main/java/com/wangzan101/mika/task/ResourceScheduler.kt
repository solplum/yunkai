package com.wangzan101.mika.task

import com.wangzan101.mika.cap.web.TabBudget
import com.wangzan101.mika.takeover.TakeoverQueue
import java.util.concurrent.locks.ReentrantLock

/** 进程内的共享资源入口。TaskCenter 管运行队列，这里管运行之间会互相争用的资源。 */
class ResourceScheduler(
    val tabBudget: TabBudget = TabBudget(),
    val takeoverQueue: TakeoverQueue = TakeoverQueue(),
    val clipboard: ReentrantLock = ReentrantLock(true),
) {
    private val apps = HashMap<String, String>()

    /** 占用与检查在同一把锁里；App 已被另一任务释放或关闭时允许接管。 */
    @Synchronized
    fun reserveApp(packageName: String, owner: String, holderActive: (String) -> Boolean, stillUsed: () -> Boolean): Boolean {
        val holder = apps[packageName]
        if (holder != null && holder != owner && holderActive(holder) && stillUsed()) return false
        apps[packageName] = owner
        return true
    }

    @Synchronized fun releaseOwner(owner: String) { apps.values.removeAll { it == owner } }

    @Synchronized fun appHolder(packageName: String): String? = apps[packageName]
}
