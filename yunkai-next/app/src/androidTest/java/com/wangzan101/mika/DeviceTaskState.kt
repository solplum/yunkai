package com.wangzan101.mika

import androidx.test.platform.app.InstrumentationRegistry

/** 设备测试从 App 持有的 TaskCenter 读取状态；TaskService 只负责 Android 宿主。 */
object DeviceTaskState {
    private val center get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MikaApp).taskState
    val state get() = center.state
    val busy get() = center.busy
    val takeoverStage get() = center.takeoverStage
    val conversationId get() = center.conversationId
    val frames get() = center.frames
    val runs get() = center.runs
    val summarizing get() = center.summarizing
    val endedRunIds get() = center.endedRunIds
    fun run(conversationId: String) = center.run(conversationId)
}
