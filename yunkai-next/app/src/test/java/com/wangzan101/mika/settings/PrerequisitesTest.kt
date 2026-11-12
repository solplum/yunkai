package com.wangzan101.mika.settings

import com.wangzan101.mika.core.shell.BuiltinShellFailure
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrerequisitesTest {
    private val ready = SystemStatus(
        sdkInt = 34,
        builtinShell = BuiltinShellStatus.Running,
        accessibility = AccessibilityState.On,
        notificationsAllowed = true,
    )

    @Test
    fun allReadyCanStart() {
        val c = Prerequisites.check(ready, hasMainModel = true)
        assertTrue(c.canStartTask)
        assertTrue(c.problems.isEmpty())
    }

    @Test
    fun belowApi33BlocksStart() {
        val c = Prerequisites.check(ready.copy(sdkInt = 32), hasMainModel = true)
        assertFalse(c.canStartTask)
        assertEquals(listOf(Problem.SystemTooOld), c.blockers)
    }

    @Test
    fun noMainModelBlocksStart() {
        val c = Prerequisites.check(ready, hasMainModel = false)
        assertFalse(c.canStartTask)
        assertEquals(listOf(Problem.NoModel), c.blockers)
    }

    @Test
    fun accessibilityOnlyNeededForPhoneApps() {
        for (a in listOf(AccessibilityState.Off, AccessibilityState.EnabledNotRunning)) {
            val c = Prerequisites.check(ready.copy(accessibility = a), hasMainModel = true)
            assertTrue(c.canStartTask)
            assertFalse(c.phoneReady)
            assertEquals(listOf(Problem.Accessibility(a)), c.problems)
        }
    }

    @Test
    fun notificationsOffOnlyWarns() {
        val c = Prerequisites.check(ready.copy(notificationsAllowed = false), hasMainModel = true)
        assertTrue(c.canStartTask)
        assertEquals(listOf(Problem.NotificationsOff), c.problems)
        assertTrue(c.blockers.isEmpty())
    }

    @Test
    fun android13OnlyWarnsAboutFocus() {
        val c = Prerequisites.check(ready.copy(sdkInt = 33), hasMainModel = true)
        assertTrue(c.canStartTask)
        assertEquals(listOf(Problem.FocusMayLeak), c.problems)
        assertTrue(Prerequisites.check(ready.copy(sdkInt = 34), hasMainModel = true).problems.isEmpty())
        assertEquals(listOf(Problem.SystemTooOld), Prerequisites.check(ready.copy(sdkInt = 32), hasMainModel = true).problems)
    }

    @Test
    fun problemsOrderedBySeverity() {
        val c = Prerequisites.check(
            SystemStatus(33, BuiltinShellStatus.NotPaired, AccessibilityState.Off, false), hasMainModel = false,
        )
        assertEquals(
            listOf(
                Problem.PhoneHelper(BuiltinShellStatus.NotPaired), Problem.Accessibility(AccessibilityState.Off),
                Problem.NoModel, Problem.NotificationsOff, Problem.FocusMayLeak,
            ),
            c.problems,
        )
    }

    // 修复 1 M4:「需要你」提醒被用户或系统调低时提示,但不挡任务
    @Test
    fun attentionLoweredIsAWarning() {
        val c = Prerequisites.check(ready.copy(attentionLowered = true), hasMainModel = true)
        assertEquals(listOf<Problem>(Problem.AttentionLowered(attention = true, result = false)), c.problems)
        assertTrue(c.canStartTask)
    }

    // 「需要你处理」和「任务结果」两个频道各种组合:一处判断,带上是哪个被调低
    @Test
    fun loweredChannelCombinations() {
        fun problems(attention: Boolean, result: Boolean) =
            Prerequisites.check(ready.copy(attentionLowered = attention, resultLowered = result), hasMainModel = true).problems
        assertEquals(emptyList<Problem>(), problems(false, false))
        assertEquals(listOf<Problem>(Problem.AttentionLowered(true, false)), problems(true, false))
        assertEquals(listOf<Problem>(Problem.AttentionLowered(false, true)), problems(false, true))
        assertEquals(listOf<Problem>(Problem.AttentionLowered(true, true)), problems(true, true))
        assertTrue(Prerequisites.check(ready.copy(resultLowered = true), hasMainModel = true).canStartTask)
    }

    // 总开关关着时只报「通知未允许」,不重复提示频道
    @Test
    fun masterSwitchOffHidesChannelProblems() {
        val c = Prerequisites.check(
            ready.copy(notificationsAllowed = false, attentionLowered = true, resultLowered = true), hasMainModel = true,
        )
        assertEquals(listOf<Problem>(Problem.NotificationsOff), c.problems)
    }

    // 网页、沙箱任务不需要内置助手:照样能开始,用到手机时才报 phone_unavailable;问题照样列出来提示
    @Test
    fun phoneHelperOnlyNeededForPhoneApps() {
        listOf(
            BuiltinShellStatus.NotPaired, BuiltinShellStatus.WirelessDebuggingOff, BuiltinShellStatus.Connecting,
            BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode),
        ).forEach { s ->
            val c = Prerequisites.check(ready.copy(builtinShell = s), hasMainModel = true)
            assertTrue(c.canStartTask)
            assertFalse(c.phoneReady)
            assertEquals(listOf<Problem>(Problem.PhoneHelper(s)), c.problems)
        }
        // 安卓 13 以下只报系统太旧
        assertEquals(
            listOf<Problem>(Problem.SystemTooOld),
            Prerequisites.check(ready.copy(sdkInt = 32, builtinShell = BuiltinShellStatus.NotPaired), hasMainModel = true).problems,
        )
    }
}
