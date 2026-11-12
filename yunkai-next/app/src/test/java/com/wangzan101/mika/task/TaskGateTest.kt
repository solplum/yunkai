package com.wangzan101.mika.task

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.agent.TaskState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskGateTest {
    @get:Rule val texts = ChineseTexts()

    private val locked = MutableStateFlow(false)
    private val shellUp = MutableStateFlow(true)
    private var moveBackCalls = 0
    private var moveBackResult = true
    private val gate = TaskGate(locked, shellUp) { moveBackCalls++; moveBackResult }
    private val reported = mutableListOf<TaskState?>()

    @Test
    fun passesImmediatelyWhenNothingBlocks() = runTest {
        gate.pass { reported += it }
        assertEquals(listOf<TaskState?>(null), reported)
    }

    @Test
    fun nonInteractiveWaitsForUnlockThenResumesByItself() = runTest {
        locked.value = DeviceStateWatcher.isLocked(interactive = false, keyguardLocked = false)
        var passed = false
        launch { gate.pass { reported += it }; passed = true }
        runCurrent()
        assertFalse(passed)
        assertEquals(TaskState.Held(TaskGate.LOCKED), reported.last())

        locked.value = DeviceStateWatcher.isLocked(interactive = true, keyguardLocked = true) // 亮屏但还在锁屏界面
        runCurrent()
        assertFalse(passed)
        locked.value = DeviceStateWatcher.isLocked(interactive = true, keyguardLocked = false)
        runCurrent()
        assertTrue(passed)
        assertEquals(null, reported.last())
    }

    @Test
    fun movedAwayPausesUntilUserContinuesThenMovesBack() = runTest {
        gate.resume() // 暂停之前的「继续」不算数
        gate.onTaskMovedAway()
        var passed = false
        launch { gate.pass { reported += it }; passed = true }
        runCurrent()
        assertFalse(passed)
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.MOVED_AWAY)), reported.last())
        assertEquals(0, moveBackCalls)

        gate.resume()
        runCurrent()
        assertTrue(passed)
        assertEquals(1, moveBackCalls)

        // 挪回来之后不再暂停
        reported.clear()
        gate.pass { reported += it }
        assertEquals(listOf<TaskState?>(null), reported)
    }

    @Test
    fun failedMoveBackStaysPaused() = runTest {
        moveBackResult = false
        gate.onTaskMovedAway()
        var passed = false
        launch { gate.pass { reported += it }; passed = true }
        runCurrent()
        gate.resume()
        runCurrent()
        assertFalse(passed)
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.MOVE_BACK_FAILED)), reported.last())

        moveBackResult = true
        gate.resume()
        runCurrent()
        assertTrue(passed)
    }

    @Test
    fun helperLossPausesUntilBackAndUserContinues() = runTest {
        shellUp.value = false
        var passed = false
        launch { gate.pass { reported += it }; passed = true }
        runCurrent()
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.SHELL_DOWN)), reported.last())

        gate.resume() // 助手还没恢复:继续无效
        runCurrent()
        assertFalse(passed)

        shellUp.value = true
        runCurrent()
        assertFalse(passed) // 恢复了也要等用户点继续
        gate.resume()
        runCurrent()
        assertTrue(passed)
    }

    @Test
    fun movedAwayMeansOnMainAndNotOnAgentDisplay() {
        assertTrue(DeviceStateWatcher.isMovedAway(agentTop = 5, mainTop = 7, taskId = 7))
        assertFalse(DeviceStateWatcher.isMovedAway(agentTop = 7, mainTop = 3, taskId = 7))
        // Agent 自己关掉了 App:不在任何屏最上面,不算被拿走
        assertFalse(DeviceStateWatcher.isMovedAway(agentTop = -1, mainTop = 3, taskId = 7))
        assertFalse(DeviceStateWatcher.isMovedAway(agentTop = -1, mainTop = -1, taskId = -1))
    }

    @Test
    fun continueTappedRightAsPauseIsShownIsNotLost() = runTest {
        gate.onTaskMovedAway()
        var passed = false
        // 通知一显示「已暂停」用户就点了继续:点击发生在 setWaiting 回调期间
        launch {
            gate.pass { reported += it; if (it is TaskState.Held && moveBackCalls == 0) gate.resume() }
            passed = true
        }
        runCurrent()
        assertTrue(passed)
        assertEquals(1, moveBackCalls)
    }

    @Test
    fun exposesPendingMoveForImmediateNotice() {
        assertFalse(gate.movedAwayPending)
        gate.onTaskMovedAway()
        assertTrue(gate.movedAwayPending)
    }

    @Test
    fun continueTappedAfterMoveDetectedButBeforeGateCounts() = runTest {
        // 发现被挪走后通知立即显示暂停;用户在 Agent 走到 gate 之前就点了继续
        gate.onTaskMovedAway()
        gate.resume()
        gate.pass { reported += it }
        assertEquals(1, moveBackCalls)
        assertEquals(null, reported.last())
    }

    /** 网页、沙箱上的步骤不需要内置助手,也不会被挪到主屏:只看锁屏。 */
    @Test
    fun shellAndMovedAwayOnlyCheckedForPhoneSteps() = runTest {
        shellUp.value = false
        gate.onTaskMovedAway()
        gate.pass({ false }) { reported += it }
        assertEquals(listOf<TaskState?>(null), reported)
        var passed = false
        launch { gate.pass({ true }) { reported += it }; passed = true }
        runCurrent()
        assertFalse(passed)
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.SHELL_DOWN)), reported.last())
        // 锁屏照样挡网页的步骤
        reported.clear()
        locked.value = true
        var webPassed = false
        launch { gate.pass({ false }) { reported += it }; webPassed = true }
        runCurrent()
        assertFalse(webPassed)
        assertEquals(TaskState.Held(TaskGate.LOCKED), reported.last())
        locked.value = false
        runCurrent()
        assertTrue(webPassed)
        coroutineContext.cancelChildren()
    }
}
