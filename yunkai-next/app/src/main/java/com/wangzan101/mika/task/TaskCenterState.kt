package com.wangzan101.mika.task

import android.graphics.Bitmap
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.takeover.Takeover
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 任务进行中用户在同一对话里插的话:[Pending] 刚发出、还没送到任务,[Received] 任务的收件箱已收下(下一步就处理),
 * [Joined] 已交给当前任务的模型,[Next] 任务已收尾、作为下一件事,[Cancelled] 用户点了停止,连同任务一起作废。
 */
enum class SteerState { Pending, Received, Joined, Next, Cancelled }

/** 还没交给模型的插话(任务就此收尾时作为下一件事,停止时作废)。 */
val SteerState.unconsumed: Boolean get() = this == SteerState.Pending || this == SteerState.Received

data class SteerNote(val id: String, val text: String, val state: SteerState)

/** 进程内 TaskCenter 的展示状态，由 App 容器持有，Service 只是写入它的宿主。 */
class TaskCenterState {
    val shownLock = Any()
    @Volatile var shownRun: TaskRun? = null

    val _runs = MutableStateFlow<List<TaskRun>>(emptyList())
    val runs: StateFlow<List<TaskRun>> = _runs.asStateFlow()
    val _activeConversations = MutableStateFlow<List<String>>(emptyList())
    val activeConversations: StateFlow<List<String>> = _activeConversations.asStateFlow()
    val _shownTask = MutableStateFlow<Pair<String, TaskState>?>(null)
    val shownTask: StateFlow<Pair<String, TaskState>?> = _shownTask.asStateFlow()
    fun run(conversationId: String): TaskRun? = _runs.value.firstOrNull { it.conversationId == conversationId }
    fun show(conversationId: String) { run(conversationId)?.show() }

    @Volatile var secondDisplayFailed = false
    val _parallelLimit = MutableStateFlow(ParallelLimit(1, null))
    val parallelLimit: StateFlow<ParallelLimit> = _parallelLimit.asStateFlow()
    fun markSecondDisplayFailed() {
        secondDisplayFailed = true
        _parallelLimit.value = ParallelLimit(1, ParallelCapability.SECOND_DISPLAY_FAILED)
    }

    val _state = MutableStateFlow<TaskState>(TaskState.Idle)
    val state: StateFlow<TaskState> = _state.asStateFlow()
    val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    val _takeoverStage = MutableStateFlow(Takeover.Stage.None)
    val takeoverStage: StateFlow<Takeover.Stage> = _takeoverStage.asStateFlow()
    val _conversationId = MutableStateFlow<String?>(null)
    val conversationId: StateFlow<String?> = _conversationId.asStateFlow()
    val _runId = MutableStateFlow<String?>(null)
    val _sharedFiles = MutableStateFlow<List<SharedFile>>(emptyList())
    val sharedFiles: StateFlow<List<SharedFile>> = _sharedFiles.asStateFlow()
    val _endedRunId = MutableStateFlow<String?>(null)
    val endedRunId: StateFlow<String?> = _endedRunId.asStateFlow()
    val _endedRunIds = MutableStateFlow<Map<String, String?>>(emptyMap())
    val endedRunIds: StateFlow<Map<String, String?>> = _endedRunIds.asStateFlow()
    /** 显示中的任务到此刻的活动(实际做过的事,来自运行事件)。 */
    val _activities = MutableStateFlow<List<RunEvent.Activity>>(emptyList())
    val activities: StateFlow<List<RunEvent.Activity>> = _activities.asStateFlow()
    /** 显示中的任务收到的插话和它们的去向。 */
    val _steers = MutableStateFlow<List<SteerNote>>(emptyList())
    val steers: StateFlow<List<SteerNote>> = _steers.asStateFlow()
    /** 停止之后正在写交代的那几次运行(运行编号);写好记进对话、或写不成,就拿掉。 */
    val _summarizing = MutableStateFlow<Set<String>>(emptySet())
    val summarizing: StateFlow<Set<String>> = _summarizing.asStateFlow()
    val _frames = MutableStateFlow<Bitmap?>(null)
    val frames: StateFlow<Bitmap?> = _frames.asStateFlow()
}
