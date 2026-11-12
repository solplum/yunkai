package com.wangzan101.mika.takeover

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.tool.HandBackOutcome
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeoverQueueTest {
    // 主屏一次只交一个 App:第二个任务的接管排在第一个后面
    @Test fun secondWaitsForFirst() = runTest {
        val queue = TakeoverQueue()
        val portA = TakeoverControllerTest.FakePort()
        val portB = TakeoverControllerTest.FakePort()
        val a = Takeover(portA, backgroundScope, queue) { testScheduler.currentTime }
        val b = Takeover(portB, backgroundScope, queue) { testScheduler.currentTime }
        a.onState(TaskState.WaitingTakeover("login", "请登录"))
        b.onState(TaskState.WaitingTakeover("captcha", "请拖动滑块"))
        assertTrue(queue.isFirst(a))
        assertEquals(1, queue.behind(a))

        assertFalse(b.handOver())
        assertEquals(Takeover.Stage.Waiting, b.stage.value)
        assertTrue(portB.log.isEmpty())

        assertTrue(a.handOver())
        assertTrue(a.handBack())
        assertTrue(queue.isFirst(b))
        assertEquals(0, queue.behind(b))
        assertTrue(b.handOver())
        assertEquals(listOf("toMain:1", "unmute:a.app"), portB.log)
    }

    // 排在后面的接管不计时:等前一个的用户处理多久都不会把它判成超时;排到最前后才开始计时
    @Test fun queuedTakeoverDoesNotTimeOutBehindFirst() = runTest {
        val queue = TakeoverQueue()
        val portA = TakeoverControllerTest.FakePort()
        val portB = TakeoverControllerTest.FakePort()
        val a = Takeover(portA, backgroundScope, queue) { testScheduler.currentTime }
        val b = Takeover(portB, backgroundScope, queue) { testScheduler.currentTime }
        a.onState(TaskState.WaitingTakeover("login", "请登录"))
        b.onState(TaskState.WaitingTakeover("captcha", "请拖动滑块"))
        assertTrue(a.handOver())
        advanceTimeBy(Takeover.TAKEOVER_WAIT_TIMEOUT_MS + 1_000)
        runCurrent()
        assertEquals(Takeover.Stage.Waiting, b.stage.value)
        assertTrue(portB.results.isEmpty())

        assertTrue(a.handBack())
        runCurrent()
        advanceTimeBy(Takeover.TAKEOVER_WAIT_TIMEOUT_MS - 1_000)
        runCurrent()
        assertTrue(portB.results.isEmpty())
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(HandBackOutcome.timeout), portB.results.map { it.outcome })
    }
}
