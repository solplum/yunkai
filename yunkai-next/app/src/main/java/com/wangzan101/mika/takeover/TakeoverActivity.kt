package com.wangzan101.mika.takeover

import android.app.Activity
import android.os.Bundle
import com.wangzan101.mika.task.TaskService

/** 不显示界面:把通知里点的接管按钮转给任务服务后立即结束。 */
class TakeoverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent?.getIntExtra(TaskService.EXTRA_WAIT_KEY, -1)?.takeIf { it >= 0 }
        // 按钮属于哪个任务;几个任务同时等接管时不能落到别的任务上
        val run = intent?.getStringExtra(TaskService.EXTRA_RUN)
        when (intent?.action) {
            TaskService.ACTION_TAKE_OVER -> TaskService.takeOver(this, key, run)
            TaskService.ACTION_HAND_BACK -> TaskService.handBack(this, key, run)
            TaskService.ACTION_FINISHED_MYSELF -> TaskService.finishedMyself(this, key, run)
            TaskService.ACTION_SKIP -> TaskService.skip(this, key, run)
        }
        finish()
    }
}
