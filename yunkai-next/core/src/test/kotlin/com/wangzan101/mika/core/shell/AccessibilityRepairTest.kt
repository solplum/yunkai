package com.wangzan101.mika.core.shell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityRepairTest {
    private val stuck = A11yObservation(enabled = true, connected = false)
    private val on = A11yObservation(enabled = true, connected = true)
    private val off = A11yObservation(enabled = false, connected = false)

    /** 假的环境:[comesBack] 为 true 时,重启后过 [connectAfterMs] 服务连上。 */
    private inner class Rig(
        initial: A11yObservation = stuck,
        helperStatus: BuiltinShellStatus = BuiltinShellStatus.Running,
        var consent: RecoveryConsent = RecoveryConsent(),
        var comesBack: Boolean = true,
        val connectAfterMs: Long = 500,
        val result: PermissionResult = PermissionResult.Done,
        var direct: Boolean = false,
    ) {
        val service = MutableStateFlow(initial)
        val helper = MutableStateFlow(helperStatus)
        val restarts = mutableListOf<Long>()
        lateinit var scope: TestScope

        val repair = AccessibilityRepair(
            restart = { gap ->
                restarts += scope.currentTime
                if (comesBack && result == PermissionResult.Done) {
                    scope.launch { kotlinx.coroutines.delay(gap + connectAfterMs); service.value = on }
                }
                result
            },
            consent = { consent },
            direct = { direct },
            now = { scope.currentTime },
        )

        fun start(s: TestScope) {
            scope = s
            s.backgroundScope.launch { repair.watch(service, helper) }
            s.runCurrent()
        }
    }

    @Test
    fun repairsStuckServiceWhileHelperRuns() = runTest {
        val rig = Rig().also { it.start(this) }
        advanceTimeBy(AccessibilityRepair.GRACE_MS - 1)
        assertEquals(emptyList<Long>(), rig.restarts)
        advanceTimeBy(10_000)
        assertEquals(listOf(AccessibilityRepair.GRACE_MS), rig.restarts)
        val last = rig.repair.last.value!!
        assertTrue(last.restarted)
        assertEquals(1, last.attempts)
    }

    @Test
    fun noRepairWhenItConnectsDuringGrace() = runTest {
        val rig = Rig().also { it.start(this) }
        advanceTimeBy(1_000)
        rig.service.value = on
        advanceTimeBy(60_000)
        assertEquals(emptyList<Long>(), rig.restarts)
        assertNull(rig.repair.last.value)
    }

    @Test
    fun turnsItBackOnWhenRemovedFromTheSystemList() = runTest {
        // 强行停止时系统会把 Mika 从无障碍列表里拿掉:一样自动打开
        val rig = Rig(initial = off).also { it.start(this) }
        advanceTimeBy(60_000)
        assertEquals(1, rig.restarts.size)
        assertTrue(rig.repair.last.value!!.restarted)
    }

    @Test
    fun noRepairWhenDeclinedInMika() = runTest {
        val rig = Rig(consent = RecoveryConsent(declined = setOf(RecoveryCapability.Accessibility))).also { it.start(this) }
        advanceTimeBy(60_000)
        assertEquals(emptyList<Long>(), rig.restarts)
    }

    @Test
    fun repairsWithoutHelperWhenAppCanWriteSettingsItself() = runTest {
        // 无线调试关着、助手不在,但 App 自己有修改系统设置的授权:照样修
        val rig = Rig(initial = off, helperStatus = BuiltinShellStatus.NotPaired, direct = true).also { it.start(this) }
        advanceTimeBy(60_000)
        assertEquals(1, rig.restarts.size)
        assertTrue(rig.repair.last.value!!.restarted)
    }

    @Test
    fun waitsForHelperThenRepairs() = runTest {
        val rig = Rig(helperStatus = BuiltinShellStatus.Connecting).also { it.start(this) }
        advanceTimeBy(60_000)
        assertEquals(emptyList<Long>(), rig.restarts)
        rig.helper.value = BuiltinShellStatus.Running
        advanceTimeBy(AccessibilityRepair.GRACE_MS + 1)
        assertEquals(1, rig.restarts.size)
    }

    @Test
    fun givesUpAfterLimitWithBackoff() = runTest {
        val rig = Rig(comesBack = false).also { it.start(this) }
        advanceTimeBy(120_000)
        assertEquals(AccessibilityRepair.MAX_ATTEMPTS, rig.restarts.size)
        // 第二次在第一次等连接超时、再退避之后
        val gap = rig.restarts[1] - rig.restarts[0]
        assertTrue("gap $gap", gap >= AccessibilityRepair.CONNECT_TIMEOUT_MS + AccessibilityRepair.BACKOFF_MS)
        assertFalse(rig.repair.last.value!!.restarted)
        // 用尽之后状态再变(助手掉线又回来)也不再试
        rig.helper.value = BuiltinShellStatus.Connecting
        runCurrent()
        rig.helper.value = BuiltinShellStatus.Running
        advanceTimeBy(120_000)
        assertEquals(AccessibilityRepair.MAX_ATTEMPTS, rig.restarts.size)
    }

    @Test
    fun newIncidentAfterItCameBackCountsAgain() = runTest {
        val rig = Rig(comesBack = false).also { it.start(this) }
        advanceTimeBy(120_000)
        assertEquals(2, rig.restarts.size)
        // 用户手动关掉再打开后连上了;之后又卡住:重新计数
        rig.service.value = on
        runCurrent()
        rig.comesBack = true
        rig.service.value = stuck
        advanceTimeBy(60_000)
        assertEquals(3, rig.restarts.size)
        assertTrue(rig.repair.last.value!!.restarted)
    }

    @Test
    fun failedCommandCountsAsAttempt() = runTest {
        val rig = Rig(result = PermissionResult.Failed("boom")).also { it.start(this) }
        advanceTimeBy(120_000)
        assertEquals(AccessibilityRepair.MAX_ATTEMPTS, rig.restarts.size)
        assertFalse(rig.repair.last.value!!.restarted)
    }

    @Test
    fun stopsRetryingWhenDeclinedInMikaBetweenAttempts() = runTest {
        val rig = Rig(comesBack = false).also { it.start(this) }
        advanceTimeBy(AccessibilityRepair.GRACE_MS + 1)
        assertEquals(1, rig.restarts.size)
        rig.consent = RecoveryConsent(declined = setOf(RecoveryCapability.Accessibility))
        advanceTimeBy(120_000)
        assertEquals(1, rig.restarts.size)
    }

    @Test
    fun slowConnectWithinTimeoutIsSuccess() = runTest {
        val rig = Rig(connectAfterMs = AccessibilityRepair.CONNECT_TIMEOUT_MS - 2_000).also { it.start(this) }
        advanceTimeBy(60_000)
        assertEquals(1, rig.restarts.size)
        assertTrue(rig.repair.last.value!!.restarted)
    }

    @Test
    fun phaseFollowsTheRepair() = runTest {
        val rig = Rig(connectAfterMs = 2_000).also { it.start(this) }
        assertEquals(RepairPhase.Pending, rig.repair.phase.value)
        advanceTimeBy(AccessibilityRepair.GRACE_MS + 1)
        assertEquals(RepairPhase.Repairing, rig.repair.phase.value)
        advanceTimeBy(10_000)
        assertEquals(RepairPhase.Idle, rig.repair.phase.value)
        // 被拿掉了也要修;只有在 Mika 里关掉了替我打开才一直是 Idle
        assertEquals(RepairPhase.Pending, Rig(initial = off).also { it.start(this) }.repair.phase.value)
        val declined = RecoveryConsent(declined = setOf(RecoveryCapability.Accessibility))
        assertEquals(RepairPhase.Idle, Rig(initial = off, consent = declined).also { it.start(this) }.repair.phase.value)
    }

    @Test
    fun phaseIdleAfterGivingUp() = runTest {
        val rig = Rig(comesBack = false).also { it.start(this) }
        advanceTimeBy(AccessibilityRepair.WORST_CASE_MS)
        assertEquals(RepairPhase.Idle, rig.repair.phase.value)
        assertEquals(AccessibilityRepair.MAX_ATTEMPTS, rig.restarts.size)
    }

    @Test
    fun awaitKeepsTheShortWaitWhenNothingIsBeingRepaired() = runTest {
        val service = MutableStateFlow<String?>(null)
        val phase = MutableStateFlow(RepairPhase.Idle)
        val wait = async { AccessibilityRepair.awaitService(service, phase, baseMs = 5_000) }
        advanceTimeBy(5_001)
        assertTrue(wait.isCompleted)
        assertNull(wait.await())
        // 一开始就连着:马上拿到
        assertEquals("svc", AccessibilityRepair.awaitService(MutableStateFlow<String?>("svc"), phase, baseMs = 5_000))
    }

    @Test
    fun awaitWaitsThroughARepairThatSucceeds() = runTest {
        val service = MutableStateFlow<String?>(null)
        val phase = MutableStateFlow(RepairPhase.Repairing)
        val wait = async { AccessibilityRepair.awaitService(service, phase, baseMs = 5_000) }
        advanceTimeBy(12_000)
        assertFalse(wait.isCompleted)
        service.value = "svc"
        phase.value = RepairPhase.Idle
        runCurrent()
        assertEquals("svc", wait.await())
    }

    @Test
    fun awaitStopsWhenTheRepairGivesUp() = runTest {
        val service = MutableStateFlow<String?>(null)
        val phase = MutableStateFlow(RepairPhase.Pending)
        val wait = async { AccessibilityRepair.awaitService(service, phase, baseMs = 5_000) }
        advanceTimeBy(9_000)
        assertFalse(wait.isCompleted)
        phase.value = RepairPhase.Idle
        runCurrent()
        assertTrue(wait.isCompleted)
        assertNull(wait.await())
    }

    @Test
    fun awaitIsCapped() = runTest {
        val service = MutableStateFlow<String?>(null)
        val phase = MutableStateFlow(RepairPhase.Repairing)
        val wait = async { AccessibilityRepair.awaitService(service, phase, baseMs = 5_000, capMs = 25_000) }
        advanceTimeBy(25_001)
        assertTrue(wait.isCompleted)
        assertNull(wait.await())
    }

    @Test
    fun awaitWithTheRealRepairEndToEnd() = runTest {
        val rig = Rig(connectAfterMs = 3_000).also { it.start(this) }
        // 服务实例:连上前是 null
        val instance = MutableStateFlow<String?>(null)
        backgroundScope.launch { rig.service.collect { instance.value = if (it.connected) "svc" else null } }
        val wait = async { AccessibilityRepair.awaitService(instance, rig.repair.phase, baseMs = 5_000) }
        advanceTimeBy(AccessibilityRepair.WORST_CASE_MS)
        assertEquals("svc", wait.await())
    }
}
