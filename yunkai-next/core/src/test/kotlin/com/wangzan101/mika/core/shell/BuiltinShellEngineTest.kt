package com.wangzan101.mika.core.shell

import com.wangzan101.mika.core.adb.AdbConnectionException
import com.wangzan101.mika.core.adb.AdbPairingException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinShellEngineTest {
    private val helper = MutableStateFlow<Any?>(null)

    private inner class FakePlatform : BuiltinShellPlatform {
        var pairedFlag = false
        var wireless: Boolean? = true
        var wifi = true
        var pairingPort: Int? = 37000
        var pairError: AdbPairingException.Reason? = null
        /** 每次起助手的结果:null = 成功并交接,其余抛对应异常;用完后一直成功。 */
        val launchResults = ArrayDeque<AdbConnectionException.Reason?>()
        var deliverOnLaunch = true
        val calls = mutableListOf<String>()

        override fun paired() = pairedFlag
        override fun setPaired(paired: Boolean) { pairedFlag = paired; calls += "paired=$paired" }
        override fun wirelessDebugging() = wireless
        override fun wifiConnected() = wifi
        override suspend fun findPairingPort(timeoutMs: Long) = pairingPort
        override fun pair(port: Int, code: String) {
            calls += "pair $port $code"
            pairError?.let { throw AdbPairingException(it, "x") }
        }
        override suspend fun launchHelper() {
            calls += "launch"
            launchResults.removeFirstOrNull()?.let { throw AdbConnectionException(it, "x") }
            if (deliverOnLaunch) helper.value = "binder"
        }
        override fun stopHelper() { calls += "stop"; helper.value = null }
        override fun forget() { calls += "forget" }
    }

    private val platform = FakePlatform()

    private fun TestScope.engine(
        backoff: Backoff = Backoff(baseMs = 1_000, maxMs = 8_000, maxAttempts = 3),
        autoOn: WirelessDebuggingAutoOn? = null,
    ) =
        BuiltinShellEngine(
            // 不用 backgroundScope:advanceUntilIdle 不等后台任务,看门狗的重试会被跳过
            platform, helper, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            StandardTestDispatcher(testScheduler), backoff,
            graceMs = 3_000, handoffTimeoutMs = 10_000, autoOn = autoOn,
        )

    /** 替用户打开无线调试:写的是假平台的开关;[revert] 为 true 时像不允许的 Wi-Fi 那样,写完马上被系统拨回。 */
    private var wants = true
    private var revert = false
    private var switchWrites = 0

    private fun TestScope.autoOn() = WirelessDebuggingAutoOn(
        wants = { wants }, canWrite = { true },
        read = { platform.wireless.also { if (revert) platform.wireless = false } },
        write = { switchWrites++; platform.wireless = true; true },
        now = { testScheduler.currentTime },
    )

    private fun launches() = platform.calls.count { it == "launch" }

    @Test
    fun backoffDoublesAndStops() {
        val b = Backoff(baseMs = 1_000, maxMs = 5_000, maxAttempts = 4)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, null), (1..4).map { b.delayAfter(it) })
        assertEquals(5_000L, Backoff(baseMs = 1_000, maxMs = 5_000, maxAttempts = 10).delayAfter(6))
    }

    @Test
    fun failureReasonsMapToWhatTheUserShouldDo() {
        assertEquals(BuiltinShellFailure.WrongCode, BuiltinShellFailures.ofPairing(AdbPairingException.Reason.WRONG_CODE))
        assertEquals(BuiltinShellFailure.PairingWindowClosed, BuiltinShellFailures.ofPairing(AdbPairingException.Reason.CONNECT_FAILED))
        assertEquals(BuiltinShellFailure.Unknown, BuiltinShellFailures.ofPairing(AdbPairingException.Reason.TLS_FAILED))
        assertEquals(BuiltinShellFailure.AuthorizationRevoked, BuiltinShellFailures.ofConnection(AdbConnectionException.Reason.UNAUTHORIZED))
        assertEquals(BuiltinShellFailure.ConnectFailed, BuiltinShellFailures.ofConnection(AdbConnectionException.Reason.CONNECT_FAILED))
        assertEquals(BuiltinShellFailure.ConnectFailed, BuiltinShellFailures.ofConnection(AdbConnectionException.Reason.TLS_FAILED))
    }

    @Test
    fun notPairedNeverLaunches() = runTest {
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.NotPaired, e.status.value)
        e.need()
        advanceUntilIdle()
        assertEquals(0, launches())
    }

    @Test
    fun pairingThenLaunchEndsRunning() = runTest {
        val e = engine()
        e.startPairing("123456", null)
        assertEquals(listOf("pair 37000 123456", "paired=true", "launch"), platform.calls)
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun pairingFailuresAreReported() = runTest {
        val e = engine()
        platform.pairError = AdbPairingException.Reason.WRONG_CODE
        e.startPairing("123456", 40000)
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode), e.status.value)
        assertEquals(false, platform.pairedFlag)
        platform.pairingPort = null
        e.startPairing("123456", null)
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.PairingWindowClosed), e.status.value)
    }

    /** 诊断信息用的最近一次出错:异常类别、原因和说明;连上后清空。 */
    @Test
    fun lastErrorRecordedAndClearedOnSuccess() = runTest {
        val e = engine()
        platform.pairError = AdbPairingException.Reason.WRONG_CODE
        e.startPairing("123456", 40000)
        assertEquals("AdbPairingException(WRONG_CODE): x", e.lastError)
        platform.pairError = null
        platform.launchResults += AdbConnectionException.Reason.UNAUTHORIZED
        e.startPairing("123456", 40000)
        assertEquals("AdbConnectionException(UNAUTHORIZED): x", e.lastError)
        e.retry()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals(null, e.lastError)
    }

    @Test
    fun startupWaitsForRedeliveryBeforeLaunching() = runTest {
        platform.pairedFlag = true
        val e = engine()
        e.start()
        runCurrent()
        assertEquals(BuiltinShellStatus.Connecting, e.status.value)
        advanceTimeBy(2_000)
        // 还活着的助手在宽限期内交接了:不用再经 adbd 起
        helper.value = "old"
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals(0, launches())
    }

    @Test
    fun startupLaunchesAfterGraceWhenNobodyDelivers() = runTest {
        platform.pairedFlag = true
        val e = engine()
        e.start()
        advanceTimeBy(2_999)
        assertEquals(0, launches())
        advanceUntilIdle()
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun deathIsFollowedByRestartWithBackoff() = runTest {
        platform.pairedFlag = true
        helper.value = "first"
        val e = engine()
        e.start()
        runCurrent()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        platform.launchResults += listOf(AdbConnectionException.Reason.CONNECT_FAILED, AdbConnectionException.Reason.CONNECT_FAILED)
        helper.value = null
        runCurrent()
        assertEquals(BuiltinShellStatus.Connecting, e.status.value)
        advanceTimeBy(1_001)
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed), e.status.value)
        // 第 1 次失败后等 1 s,第 2 次失败后等 2 s
        advanceTimeBy(1_001)
        assertEquals(2, launches())
        advanceTimeBy(1_000)
        assertEquals(2, launches())
        advanceUntilIdle()
        assertEquals(3, launches())
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun retriesStopAfterMaxAttemptsUntilAskedAgain() = runTest {
        platform.pairedFlag = true
        platform.launchResults += List(10) { AdbConnectionException.Reason.CONNECT_FAILED }
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(3, launches())
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed), e.status.value)
        e.retry()
        assertEquals(4, launches())
    }

    @Test
    fun revokedKeyAndMissingWifiAreNotRetriedAutomatically() = runTest {
        platform.pairedFlag = true
        platform.launchResults += AdbConnectionException.Reason.UNAUTHORIZED
        val e = engine()
        e.retry()
        advanceUntilIdle()
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked, BuiltinShellFix.RePair), e.status.value)

        platform.wifi = false
        platform.launchResults += AdbConnectionException.Reason.CONNECT_FAILED
        e.retry()
        advanceUntilIdle()
        assertEquals(2, launches())
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.NoWifi), e.status.value)
    }

    @Test
    fun wifiComingBackRetriesAfterNoWifi() = runTest {
        platform.pairedFlag = true
        platform.wifi = false
        platform.launchResults += AdbConnectionException.Reason.CONNECT_FAILED
        val e = engine()
        e.retry()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.NoWifi), e.status.value)

        platform.wifi = true
        e.wifiAvailable()
        advanceUntilIdle()
        assertEquals(2, launches())
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun wifiComingBackIgnoredUnlessWaitingForWifi() = runTest {
        platform.pairedFlag = true
        platform.launchResults += AdbConnectionException.Reason.UNAUTHORIZED
        val e = engine()
        e.retry()
        advanceUntilIdle()
        e.wifiAvailable()
        advanceUntilIdle()
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked, BuiltinShellFix.RePair), e.status.value)
    }

    @Test
    fun wirelessDebuggingOffWaitsForTheSwitch() = runTest {
        platform.pairedFlag = true
        platform.wireless = false
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.WirelessDebuggingOff, e.status.value)
        assertEquals(0, launches())
        platform.wireless = true
        e.wirelessDebuggingChanged()
        advanceUntilIdle()
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun wirelessDebuggingOffIsTurnedOnWhenTheHelperIsNeeded() = runTest {
        platform.pairedFlag = true
        platform.wireless = false
        val e = engine(autoOn = autoOn())
        e.start()
        advanceUntilIdle()
        assertEquals(1, switchWrites)
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun revertedOnThisWifiStopsAndSaysSoUntilWifiChanges() = runTest {
        platform.pairedFlag = true
        platform.wireless = false
        revert = true
        val e = engine(autoOn = autoOn())
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.WifiNotAllowed), e.status.value)
        assertEquals(1, switchWrites)
        assertEquals(0, launches())

        // 要用助手、开关又变了:不再写(每写一次系统就弹一次框)
        e.need()
        e.wirelessDebuggingChanged()
        advanceUntilIdle()
        assertEquals(1, switchWrites)
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.WifiNotAllowed), e.status.value)

        // 换到了允许过的 Wi-Fi:再写一次,这次开着
        revert = false
        e.wifiAvailable()
        advanceUntilIdle()
        assertEquals(2, switchWrites)
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun userRetryWritesAgainAfterARevert() = runTest {
        platform.pairedFlag = true
        platform.wireless = false
        revert = true
        val e = engine(autoOn = autoOn())
        e.retry()
        assertEquals(1, switchWrites)
        // 用户在系统弹框里允许了、再点重试
        revert = false
        e.retry()
        assertEquals(2, switchWrites)
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun switchOffInMikaNeverWrites() = runTest {
        platform.pairedFlag = true
        platform.wireless = false
        wants = false
        val e = engine(autoOn = autoOn())
        e.start()
        advanceUntilIdle()
        e.wifiAvailable()
        e.need()
        advanceUntilIdle()
        assertEquals(0, switchWrites)
        assertEquals(BuiltinShellStatus.WirelessDebuggingOff, e.status.value)
    }

    @Test
    fun withoutWifiWaitsAndTurnsOnWhenWifiComesBack() = runTest {
        platform.pairedFlag = true
        platform.wireless = false
        platform.wifi = false
        val e = engine(autoOn = autoOn())
        e.start()
        advanceUntilIdle()
        assertEquals(0, switchWrites)
        assertEquals(BuiltinShellStatus.WirelessDebuggingOff, e.status.value)

        platform.wifi = true
        e.wifiAvailable()
        advanceUntilIdle()
        assertEquals(1, switchWrites)
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun launchedButNeverHandedOffIsHelperStartFailed() = runTest {
        platform.pairedFlag = true
        platform.deliverOnLaunch = false
        val e = engine(Backoff(maxAttempts = 1))
        e.retry()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed), e.status.value)
    }

    @Test
    fun needLaunchesOnceEvenIfCalledRepeatedly() = runTest {
        platform.pairedFlag = true
        val e = engine()
        e.need()
        e.need()
        advanceUntilIdle()
        assertEquals(1, launches())
    }

    @Test
    fun unpairStopsHelperAndForgets() = runTest {
        platform.pairedFlag = true
        helper.value = "h"
        val e = engine()
        e.start()
        runCurrent()
        e.unpair()
        advanceUntilIdle()
        assertTrue(platform.calls.containsAll(listOf("stop", "forget", "paired=false")))
        assertEquals(BuiltinShellStatus.NotPaired, e.status.value)
        assertEquals(0, launches())
    }

    @Test
    fun discoveryReportsFoundPort() = runTest {
        val e = engine()
        e.startDiscovery()
        assertEquals(PairingDiscovery.Searching, e.pairingDiscovery.value)
        advanceUntilIdle()
        assertEquals(PairingDiscovery.Found(37000), e.pairingDiscovery.value)
        e.stopDiscovery()
        assertEquals(PairingDiscovery.Idle, e.pairingDiscovery.value)
    }
}
