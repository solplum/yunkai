package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 循环自己报的终态不经发布写出去:终态只由 TaskRun.end 在记好本次运行编号之后写,
 * 否则界面先收到结束,拿到的运行编号是空的,卡片上没有「查看过程」。
 */
class TaskPublishTest {
    @Test fun loopTerminalStatesAreNotPublished() {
        assertFalse(TaskService.loopMayPublish(TaskState.Succeeded("ok")))
        assertFalse(TaskService.loopMayPublish(TaskState.Failed("x", FailKind.GaveUp)))
        assertFalse(TaskService.loopMayPublish(TaskState.Cancelled))
        assertFalse(TaskService.loopMayPublish(TaskState.Idle))
        assertTrue(TaskService.loopMayPublish(TaskState.Running(1, "看屏幕", "phone_observe")))
        assertTrue(TaskService.loopMayPublish(TaskState.WaitingInput("哪个?", null)))
    }
}
