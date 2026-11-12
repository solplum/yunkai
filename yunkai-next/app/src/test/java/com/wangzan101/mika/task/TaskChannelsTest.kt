package com.wangzan101.mika.task

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TaskChannelsTest {
    // 修复 1 M4:「需要你」频道(接管、提问、确认共用)建成高优先级
    @Test fun attentionChannelCreatedHigh() {
        assertEquals(NotificationManager.IMPORTANCE_HIGH, TaskService.CHANNEL_IMPORTANCE[TaskService.CHANNEL_ATTENTION])
    }

    // 结果要弹出一次,进度保持安静
    @Test fun resultHighProgressQuiet() {
        assertEquals(NotificationManager.IMPORTANCE_HIGH, TaskService.CHANNEL_IMPORTANCE[TaskService.CHANNEL_RESULT])
        assertEquals(NotificationManager.IMPORTANCE_LOW, TaskService.CHANNEL_IMPORTANCE["task_progress"])
    }

    // 旧结果频道建成了较低级别、调不高:换新 id,旧 id 要删掉
    @Test fun resultChannelUsesNewIdAndOldIsDeleted() {
        assertEquals("task_result_v2", TaskService.CHANNEL_RESULT)
        assertEquals("task_result", TaskService.OLD_CHANNEL_RESULT)
        assertNotEquals(TaskService.OLD_CHANNEL_RESULT, TaskService.CHANNEL_RESULT)
        assertFalse(TaskService.CHANNEL_IMPORTANCE.containsKey(TaskService.OLD_CHANNEL_RESULT))
    }
}
