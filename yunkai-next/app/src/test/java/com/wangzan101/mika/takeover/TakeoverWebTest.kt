package com.wangzan101.mika.takeover

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.tool.HandBackOutcome
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 当前界面是网页时,接管把同一个网页标签拿到眼前,不挪 App 任务。 */
class TakeoverWebTest {
    private class WebFake(val base: TakeoverControllerTest.FakePort = TakeoverControllerTest.FakePort()) : TakeoverPort by base {
        var web: Boolean? = true
        var busy = false
        override fun webBusy() = busy
        var counts = HandoverActivity(3, "登录 - 示例商城")
        val log get() = base.log
        val results get() = base.results
        override suspend fun showWeb(reason: String, key: Int): Boolean? {
            val w = web ?: return null
            log += "showWeb:$reason:$key"
            return w
        }
        override suspend fun returnWeb(loggedIn: Boolean): HandoverActivity? {
            log += "returnWeb:$loggedIn"
            return counts
        }
    }

    private val port = WebFake()
    private lateinit var takeover: Takeover
    private val waiting = TaskState.WaitingTakeover("login", "请登录")

    private fun test(body: suspend TestScope.() -> Unit) = runTest {
        takeover = Takeover(port, backgroundScope) { testScheduler.currentTime }
        body()
    }

    @Test
    fun handOverShowsTheWebTabInsteadOfMovingTasks() = test {
        takeover.onState(waiting)
        assertTrue(takeover.handOver())
        assertEquals(listOf("showWeb:login:1"), port.log)
        assertEquals(Takeover.Stage.HandedOver, takeover.stage.value)
    }

    @Test
    fun handBackReturnsTheTabWithCountsAndMarksLogin() = test {
        takeover.onState(waiting)
        takeover.handOver()
        advanceTimeBy(5_000)
        assertTrue(takeover.handBack())
        assertEquals(listOf("showWeb:login:1", "returnWeb:true", "resume:complete"), port.log)
        val r = port.results.single()
        assertEquals(3, r.inputCount)
        assertEquals("登录 - 示例商城", r.stoppedAt)
        assertEquals(5_000L, r.durationMs)
        assertEquals(Takeover.Stage.None, takeover.stage.value)
    }

    @Test
    fun finishedMyselfAlsoMarksLogin() = test {
        takeover.onState(waiting)
        takeover.handOver()
        takeover.finishedMyself()
        assertEquals("returnWeb:true", port.log[1])
        assertTrue(port.results.single().userFinished)
    }

    @Test
    fun skipReturnsTheTabWithoutCounts() = test {
        takeover.onState(waiting)
        takeover.handOver()
        takeover.skip()
        assertEquals(listOf("showWeb:login:1", "returnWeb:false", "resume:skipped"), port.log)
        assertNull(port.results.single().inputCount)
    }

    @Test
    fun putAsideGoesBackToWaitingAndCanHandOverAgain() = test {
        takeover.onState(waiting)
        takeover.handOver()
        assertTrue(takeover.putAside())
        assertEquals(Takeover.Stage.Waiting, takeover.stage.value)
        assertEquals(listOf("showWeb:login:1", "returnWeb:false"), port.log)
        assertTrue(port.results.isEmpty())
        assertTrue(takeover.handOver())
        assertEquals("showWeb:login:1", port.log.last())
        assertTrue(takeover.handBack())
        assertEquals(HandBackOutcome.complete, port.results.single().outcome)
    }

    @Test
    fun putAsideStillTimesOutWhenNobodyComesBack() = test {
        takeover.onState(waiting)
        takeover.handOver()
        takeover.putAside()
        advanceTimeBy(Takeover.TAKEOVER_WAIT_TIMEOUT_MS + 1)
        runCurrent()
        assertEquals(HandBackOutcome.timeout, port.results.single().outcome)
    }

    @Test
    fun putAsideOnlyWhileHandedOver() = test {
        assertFalse(takeover.putAside())
        takeover.onState(waiting)
        assertFalse(takeover.putAside())
        takeover.handOver()
        assertFalse(takeover.putAside(key = 99))
    }

    @Test
    fun failedToShowStaysWaiting() = test {
        port.web = false
        takeover.onState(waiting)
        assertFalse(takeover.handOver())
        assertEquals(Takeover.Stage.Waiting, takeover.stage.value)
        assertEquals(Takeover.Problem.MoveToMainFailed, takeover.problem.value)
    }

    @Test
    fun busyLoginScreenIsReportedAsBusyNotAsAFailedMove() = test {
        port.web = false
        port.busy = true
        takeover.onState(waiting)
        assertFalse(takeover.handOver())
        assertEquals(Takeover.Stage.Waiting, takeover.stage.value)
        assertEquals(Takeover.Problem.WebBusy, takeover.problem.value)
    }

    @Test
    fun cancelReturnsTheTab() = test {
        takeover.onState(waiting)
        takeover.handOver()
        takeover.cancel()
        assertEquals(listOf("showWeb:login:1", "returnWeb:false", "cancel"), port.log)
    }

    /** 并行的两个任务都要接管:同一时间只有排在最前的能把东西拿到主屏,网页的也一样。 */
    @Test
    fun webTakeoverWaitsItsTurnInTheQueue() = runTest {
        val queue = TakeoverQueue()
        val phoneRun = TakeoverControllerTest.FakePort()
        val first = Takeover(phoneRun, backgroundScope, queue) { testScheduler.currentTime }
        val webRun = WebFake()
        val second = Takeover(webRun, backgroundScope, queue) { testScheduler.currentTime }
        first.onState(TaskState.WaitingTakeover("captcha", "滑块"))
        second.onState(waiting)
        assertFalse(second.handOver())
        assertTrue(webRun.log.none { it.startsWith("showWeb") })
        assertTrue(first.handOver())
        first.handBack()
        assertTrue(second.handOver())
        assertEquals("showWeb:login:1", webRun.log.single())
    }

    @Test
    fun phoneSurfaceStillMovesTasks() = test {
        port.web = null
        takeover.onState(waiting)
        takeover.handOver()
        assertEquals(listOf("toMain:1", "unmute:a.app"), port.log)
    }
}
