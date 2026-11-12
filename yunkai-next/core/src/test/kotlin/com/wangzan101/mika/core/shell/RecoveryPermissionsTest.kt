package com.wangzan101.mika.core.shell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPermissionsTest {
    // 包名由调用方给,不是写死的
    private val pkg = "org.example.app"
    private val mika = PermissionCommands.accessibilityService(pkg)
    private val other = "com.example.reader/.ReaderService"

    private class Store(initial: RecoveryConsent = RecoveryConsent()) : RecoveryConsentStore {
        override val consent = MutableStateFlow(initial)
        override fun save(consent: RecoveryConsent) { this.consent.value = consent }
    }

    /** 假的执行器:记下跑过的命令(去掉退出码包装),[a11y] 是系统里的无障碍列表。 */
    private class FakeShell(var a11y: String? = null, val failOn: String? = null) : ShellCommandRunner {
        private val PUT_LIST = "settings put secure enabled_accessibility_services "
        private val DELETE_LIST = "settings delete secure enabled_accessibility_services"
        val ran = mutableListOf<String>()
        override suspend fun exec(command: String): String {
            if (command == PermissionCommands.READ_ACCESSIBILITY) return (a11y ?: "null") + "\n"
            val bare = command.substringBefore(" 2>&1;")
            ran += bare
            val code = if (failOn != null && bare.contains(failOn)) 255 else 0
            // 写成功的列表照实留下,之后再读就是新的
            if (code == 0 && bare.startsWith(PUT_LIST)) a11y = bare.removePrefix(PUT_LIST).trim('\'')
            if (code == 0 && bare == DELETE_LIST) a11y = null
            return (if (code == 0) "" else "Exception occurred\n") + PermissionCommands.EXIT_MARK + code + "\n"
        }
    }

    @Test
    fun commandsAreTheExpectedShellCommands() {
        assertEquals("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS", PermissionCommands.grantWriteSecureSettings(pkg))
        assertEquals("pm revoke $pkg android.permission.WRITE_SECURE_SETTINGS", PermissionCommands.revokeWriteSecureSettings(pkg))
        assertEquals("cmd deviceidle whitelist +$pkg", PermissionCommands.whitelistAdd(pkg))
        assertEquals("cmd deviceidle whitelist -$pkg", PermissionCommands.whitelistRemove(pkg))
        assertEquals(
            listOf("settings put secure enabled_accessibility_services '$other:$mika'", "settings put secure accessibility_enabled 1"),
            PermissionCommands.writeAccessibility("$other:$mika"),
        )
        // 列表空了:删掉这一项并关掉总开关,不留空字符串
        assertEquals(
            listOf("settings delete secure enabled_accessibility_services", "settings put secure accessibility_enabled 0"),
            PermissionCommands.writeAccessibility(""),
        )
    }

    @Test
    fun quotingSurvivesOddCharacters() {
        assertEquals("'a\$b'", PermissionCommands.quote("a\$b"))
        assertEquals("'it'\\''s'", PermissionCommands.quote("it's"))
    }

    @Test
    fun exitCodeParsing() {
        assertEquals(0, PermissionCommands.exitCode("Added: x\n${PermissionCommands.EXIT_MARK}0\n"))
        assertEquals(255, PermissionCommands.exitCode("boom\n${PermissionCommands.EXIT_MARK}255"))
        assertEquals(null, PermissionCommands.exitCode("no marker at all"))
        assertTrue(PermissionCommands.checked("pm grant x y").endsWith("2>&1; echo ${PermissionCommands.EXIT_MARK}\$?"))
    }

    private val all = RecoveryCapability.entries.toSet()
    private val a11y = RecoveryCapability.Accessibility
    private val battery = RecoveryCapability.BatteryWhitelist
    private val restore = RecoveryCapability.RestoreAfterReboot
    private val autoOn = RecoveryCapability.AutoWirelessDebugging

    @Test
    fun turningOnRunsItsCommandsAndRecordsIt() = runTest {
        val shell = FakeShell(a11y = other)
        val store = Store(RecoveryConsent(declined = all))
        val p = RecoveryPermissions({ shell }, store, pkg)

        assertEquals(PermissionResult.Done, p.set(a11y, true))
        assertEquals(PermissionCommands.writeAccessibility("$other:$mika"), shell.ran)
        assertEquals(PermissionResult.Done, p.set(battery, true))
        assertEquals(PermissionResult.Done, p.set(restore, true))
        assertEquals(RecoveryConsent(applied = setOf(a11y, battery, restore), declined = setOf(autoOn)), store.consent.value)
        assertTrue(PermissionCommands.whitelistAdd(pkg) in shell.ran)
        assertTrue(PermissionCommands.grantWriteSecureSettings(pkg) in shell.ran)
    }

    @Test
    fun switchOffRevertsOnlyThatOneThingAndRemembersTheChoice() = runTest {
        val shell = FakeShell(a11y = "$other:$mika")
        val store = Store(RecoveryConsent(applied = setOf(a11y, battery, restore), declined = setOf(autoOn)))
        val p = RecoveryPermissions({ shell }, store, pkg)

        assertEquals(PermissionResult.Done, p.set(battery, false))
        assertEquals(listOf(PermissionCommands.whitelistRemove(pkg)), shell.ran)
        assertEquals(RecoveryConsent(applied = setOf(a11y, restore), declined = setOf(battery, autoOn)), store.consent.value)

        shell.ran.clear()
        p.set(restore, false)
        assertEquals(listOf(PermissionCommands.revokeWriteSecureSettings(pkg)), shell.ran)

        shell.ran.clear()
        p.set(a11y, false)
        assertEquals(PermissionCommands.writeAccessibility(other), shell.ran)
        assertEquals(RecoveryConsent(declined = all), store.consent.value)
    }

    @Test
    fun withoutRunnerChoicesAreRecordedButNothingRuns() = runTest {
        val store = Store(RecoveryConsent(declined = setOf(a11y)))
        val p = RecoveryPermissions({ null }, store, pkg)
        // 打开:先记下要,提示先完成设置,等连接可用时补上
        assertEquals(PermissionResult.NoRunner, p.set(a11y, true))
        assertTrue(store.consent.value.wants(a11y))
        assertEquals(listOf(a11y, battery, restore, autoOn), store.consent.value.pending)
        // 关掉一项没生效过的:没有要收回的,记下就算完成
        assertEquals(PermissionResult.Done, p.set(battery, false))
        assertEquals(RecoveryConsent(declined = setOf(battery)), store.consent.value)
    }

    @Test
    fun withoutRunnerAnAppliedOneCannotBeTurnedOff() = runTest {
        val store = Store(RecoveryConsent(applied = setOf(battery)))
        assertEquals(PermissionResult.NoRunner, RecoveryPermissions({ null }, store, pkg).set(battery, false))
        assertEquals(RecoveryConsent(applied = setOf(battery)), store.consent.value)
    }

    @Test
    fun failedCommandKeepsTheOldState() = runTest {
        val store = Store(RecoveryConsent(declined = setOf(restore)))
        val p = RecoveryPermissions({ FakeShell(failOn = "pm grant") }, store, pkg)
        val r = p.set(restore, true)
        assertTrue(r is PermissionResult.Failed)
        assertEquals(RecoveryConsent(declined = setOf(restore)), store.consent.value)
    }

    @Test
    fun runnerThatThrowsIsAFailureNotACrash() = runTest {
        val store = Store()
        val p = RecoveryPermissions({ ShellCommandRunner { error("connection closed") } }, store, pkg)
        assertTrue(p.set(battery, true) is PermissionResult.Failed)
        assertEquals(RecoveryConsent(), store.consent.value)
    }

    @Test
    fun applyPendingTurnsOnUntouchedOnesOnly() = runTest {
        val shell = FakeShell(a11y = other)
        // 电池白名单用户亲手关掉了;要修改系统设置授权的两项早已生效
        val store = Store(RecoveryConsent(applied = setOf(restore, autoOn), declined = setOf(battery)))
        val p = RecoveryPermissions({ shell }, store, pkg)

        assertEquals(PermissionResult.Done, p.applyPending())
        assertEquals(PermissionCommands.writeAccessibility("$other:$mika"), shell.ran)
        assertEquals(RecoveryConsent(applied = setOf(a11y, restore, autoOn), declined = setOf(battery)), store.consent.value)
        assertTrue(p.autoFailed.value.isEmpty())

        // 都生效了:再来一次什么都不跑
        shell.ran.clear()
        assertEquals(PermissionResult.Done, p.applyPending())
        assertTrue(shell.ran.isEmpty())
    }

    @Test
    fun applyPendingWithoutRunnerDoesNothing() = runTest {
        val store = Store()
        val p = RecoveryPermissions({ null }, store, pkg)
        assertEquals(PermissionResult.NoRunner, p.applyPending())
        assertEquals(RecoveryConsent(), store.consent.value)
        assertTrue(p.autoFailed.value.isEmpty())
    }

    @Test
    fun applyPendingRecordsFailuresQuietlyAndClearsThemLater() = runTest {
        var shell = FakeShell(failOn = "deviceidle")
        val store = Store()
        val p = RecoveryPermissions({ shell }, store, pkg)

        assertTrue(p.applyPending() is PermissionResult.Failed)
        // 一项失败不挡住其余几项
        assertEquals(RecoveryConsent(applied = setOf(a11y, restore, autoOn)), store.consent.value)
        assertEquals(setOf(battery), p.autoFailed.value)

        shell = FakeShell()
        assertEquals(PermissionResult.Done, p.applyPending())
        assertEquals(listOf(PermissionCommands.whitelistAdd(pkg)), shell.ran)
        assertTrue(p.autoFailed.value.isEmpty())
    }

    @Test
    fun appliesEachTimeTheHelperBecomesRunning() = runTest {
        val shell = FakeShell()
        val status = MutableStateFlow<BuiltinShellStatus>(BuiltinShellStatus.NotPaired)
        val store = Store(RecoveryConsent(declined = setOf(a11y, restore, autoOn)))
        val p = RecoveryPermissions({ shell }, store, pkg)
        val job = launch { p.applyWhenRunning(status) }

        runCurrent()
        assertTrue(shell.ran.isEmpty())
        status.value = BuiltinShellStatus.Connecting
        runCurrent()
        assertTrue(shell.ran.isEmpty())

        status.value = BuiltinShellStatus.Running
        runCurrent()
        assertEquals(listOf(PermissionCommands.whitelistAdd(pkg)), shell.ran)

        // 掉线又重起:这期间收回过(比如解除配对又配上),再补一次
        status.value = BuiltinShellStatus.Connecting
        runCurrent()
        store.save(store.consent.value.copy(applied = emptySet()))
        status.value = BuiltinShellStatus.Running
        runCurrent()
        assertEquals(listOf(PermissionCommands.whitelistAdd(pkg), PermissionCommands.whitelistAdd(pkg)), shell.ran)
        job.cancel()
    }

    @Test
    fun unpairRevokesEverythingThatWasApplied() = runTest {
        val shell = FakeShell(a11y = "$mika:$other")
        val store = Store(RecoveryConsent(applied = all))
        val p = RecoveryPermissions({ shell }, store, pkg)

        assertEquals(PermissionResult.Done, p.revokeAll())
        assertEquals(RecoveryConsent(), store.consent.value)
        assertTrue(PermissionCommands.revokeWriteSecureSettings(pkg) in shell.ran)
        assertTrue(PermissionCommands.whitelistRemove(pkg) in shell.ran)
        assertTrue(shell.ran.containsAll(PermissionCommands.writeAccessibility(other)))
    }

    @Test
    fun theTwoSwitchesThatTurnOnWirelessDebuggingShareOnePermission() = runTest {
        val shell = FakeShell()
        val store = Store(RecoveryConsent(applied = setOf(restore, autoOn)))
        val p = RecoveryPermissions({ shell }, store, pkg)

        // 另一项还要用修改系统设置的授权:关掉这一项只记下,不收回
        assertEquals(PermissionResult.Done, p.set(restore, false))
        assertEquals(emptyList<String>(), shell.ran)
        assertEquals(RecoveryConsent(applied = setOf(autoOn), declined = setOf(restore)), store.consent.value)
        // 两项都关掉才收回
        assertEquals(PermissionResult.Done, p.set(autoOn, false))
        assertEquals(listOf(PermissionCommands.revokeWriteSecureSettings(pkg)), shell.ran)
        assertEquals(RecoveryConsent(declined = setOf(restore, autoOn)), store.consent.value)
        // 打开任一项都要这份授权
        shell.ran.clear()
        assertEquals(PermissionResult.Done, p.set(autoOn, true))
        assertEquals(listOf(PermissionCommands.grantWriteSecureSettings(pkg)), shell.ran)
    }

    @Test
    fun unpairRevokesTheSharedPermissionOnce() = runTest {
        val shell = FakeShell()
        val p = RecoveryPermissions({ shell }, Store(RecoveryConsent(applied = setOf(restore, autoOn))), pkg)
        assertEquals(PermissionResult.Done, p.revokeAll())
        assertEquals(listOf(PermissionCommands.revokeWriteSecureSettings(pkg)), shell.ran)
    }

    @Test
    fun unpairLeavesAloneWhatMikaNeverTurnedOn() = runTest {
        // 用户自己在系统里开的无障碍、自己加的电池白名单,不是这里给的,解除配对不去动
        val shell = FakeShell(a11y = "$mika:$other")
        val p = RecoveryPermissions({ shell }, Store(), pkg)
        p.revokeAll()
        assertEquals(listOf(PermissionCommands.revokeWriteSecureSettings(pkg)), shell.ran)
    }

    @Test
    fun unpairThenRePairKeepsTheUsersChoices() = runTest {
        val shell = FakeShell(a11y = other)
        val store = Store(RecoveryConsent(applied = setOf(a11y, restore), declined = setOf(battery)))
        val p = RecoveryPermissions({ shell }, store, pkg)

        p.revokeAll()
        // 收回的是生效状态,不是用户的选择:没关掉的不会因此变成「亲手关掉」
        assertEquals(RecoveryConsent(declined = setOf(battery)), store.consent.value)

        shell.ran.clear()
        shell.a11y = other
        assertEquals(PermissionResult.Done, p.applyPending())
        assertEquals(RecoveryConsent(applied = setOf(a11y, restore, autoOn), declined = setOf(battery)), store.consent.value)
        assertFalse(PermissionCommands.whitelistAdd(pkg) in shell.ran)
    }

    @Test
    fun unpairClearsAppliedEvenWithoutRunnerOrOnFailure() = runTest {
        val store = Store(RecoveryConsent(applied = setOf(a11y, restore)))
        assertEquals(PermissionResult.NoRunner, RecoveryPermissions({ null }, store, pkg).revokeAll())
        assertEquals(RecoveryConsent(), store.consent.value)

        val store2 = Store(RecoveryConsent(applied = setOf(battery, restore)))
        val shell = FakeShell(failOn = "deviceidle")
        assertTrue(RecoveryPermissions({ shell }, store2, pkg).revokeAll() is PermissionResult.Failed)
        assertEquals(RecoveryConsent(), store2.consent.value)
        // 一项失败不挡住其余几项
        assertTrue(PermissionCommands.revokeWriteSecureSettings(pkg) in shell.ran)
    }

    @Test
    fun consentHelpers() {
        // 什么都没记:每项都默认要、都还没生效
        val fresh = RecoveryConsent()
        assertTrue(RecoveryCapability.entries.all(fresh::wants))
        assertEquals(RecoveryCapability.entries, fresh.pending)

        val c = fresh.chose(battery, false).withApplied(a11y, true)
        assertFalse(c.wants(battery))
        assertTrue(c.isApplied(a11y))
        assertEquals(listOf(restore, autoOn), c.pending)
        assertEquals(fresh, c.chose(battery, true).withApplied(a11y, false))
    }

    @Test
    fun restartAccessibilityRemovesThenAddsBackKeepingOthers() = runTest {
        val shell = FakeShell(a11y = "$other:$mika:com.example.b/.B")
        val store = Store(RecoveryConsent(applied = setOf(a11y)))
        val p = RecoveryPermissions({ shell }, store, pkg)

        assertEquals(PermissionResult.Done, p.restartAccessibility(gapMs = 1_000))
        assertEquals(
            PermissionCommands.writeAccessibility("$other:com.example.b/.B") +
                PermissionCommands.writeAccessibility("$other:com.example.b/.B:$mika"),
            shell.ran,
        )
        // 不是打开或收回能力:记录不变
        assertEquals(RecoveryConsent(applied = setOf(a11y)), store.consent.value)
    }

    @Test
    fun restartAccessibilityWhenMikaIsTheOnlyService() = runTest {
        val shell = FakeShell(a11y = mika)
        assertEquals(PermissionResult.Done, RecoveryPermissions({ shell }, Store(), pkg).restartAccessibility(0))
        assertEquals(PermissionCommands.writeAccessibility("") + PermissionCommands.writeAccessibility(mika), shell.ran)
    }

    @Test
    fun restartAccessibilityAddsItBackWhenMissing() = runTest {
        val shell = FakeShell(a11y = other)
        assertEquals(PermissionResult.Done, RecoveryPermissions({ shell }, Store(), pkg).restartAccessibility(0))
        assertEquals(PermissionCommands.writeAccessibility("$other:$mika"), shell.ran)
        assertEquals(PermissionResult.NoRunner, RecoveryPermissions({ null }, Store(), pkg).restartAccessibility(0))
    }

    @Test
    fun restartAccessibilityStopsWhenRemovalFails() = runTest {
        val shell = FakeShell(a11y = "$other:$mika", failOn = "enabled_accessibility_services")
        assertTrue(RecoveryPermissions({ shell }, Store(), pkg).restartAccessibility(0) is PermissionResult.Failed)
        assertEquals(1, shell.ran.size)
    }
}
