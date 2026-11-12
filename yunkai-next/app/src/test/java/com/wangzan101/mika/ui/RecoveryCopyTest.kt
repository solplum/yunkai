package com.wangzan101.mika.ui

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.shell.AutoOnBlock
import com.wangzan101.mika.core.shell.PermissionResult
import com.wangzan101.mika.core.shell.RecoveryCapability
import com.wangzan101.mika.core.shell.RecoveryConsent
import com.wangzan101.mika.core.shell.RepairOutcome
import com.wangzan101.mika.core.shell.RestoreOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class RecoveryCopyTest {
    @get:Rule val texts = ChineseTexts()

    private val jargon = listOf("shell", "端口", "mDNS", "TLS", "Binder", "uid", "进程", "adb", "WRITE_SECURE", "deviceidle")

    private fun allText(): List<String> = buildList {
        RecoveryCapability.entries.forEach {
            val c = RecoveryCopy.switch(it)
            add(c.title); add(c.subtitle); add(c.explain)
        }
        RestoreOutcome.entries.forEach { o -> RecoveryCopy.lastRestore(o)?.let(::add) }
        AutoOnBlock.entries.forEach { b -> RecoveryCopy.autoOnBlocked(b)?.let(::add) }
        addAll(listOf(RecoveryCopy.NEED_SETUP, RecoveryCopy.FAILED, RecoveryCopy.WORKING))
    }

    @Test
    fun copyIsPlainChinese() {
        allText().forEach { t ->
            assertTrue("empty copy", t.isNotBlank())
            jargon.forEach { j -> assertFalse("「$j」 in: $t", t.contains(j, ignoreCase = true)) }
        }
    }

    @Test
    fun everySwitchSaysWhatItDoesAndHowToTurnItOff() {
        RecoveryCapability.entries.forEach {
            val c = RecoveryCopy.switch(it)
            assertTrue(c.explain, c.explain.contains("打开后"))
            assertTrue(c.explain, c.explain.contains("关掉这里"))
            assertEquals("默认打开", c.subtitle)
        }
        assertEquals(RecoveryCapability.entries.size, RecoveryCapability.entries.map { RecoveryCopy.switch(it).title }.toSet().size)
        assertTrue(RecoveryCopy.switch(RecoveryCapability.RestoreAfterReboot).explain.contains("解除配对"))
    }

    @Test
    fun resultsAndRestoreStatus() {
        assertNull(RecoveryCopy.result(PermissionResult.Done))
        assertEquals("需要先完成手机操作设置", RecoveryCopy.result(PermissionResult.NoRunner))
        assertEquals(RecoveryCopy.FAILED, RecoveryCopy.result(PermissionResult.Failed("x")))
        assertNull(RecoveryCopy.lastRestore(null))
        assertNull(RecoveryCopy.lastRestore(RestoreOutcome.Restored))
        val shown = RestoreOutcome.entries.mapNotNull(RecoveryCopy::lastRestore)
        assertEquals(3, shown.toSet().size)
    }

    @Test
    fun pendingNoteOnlyForDefaultOnesNotYetApplied() {
        val a11y = RecoveryCapability.Accessibility
        val fresh = RecoveryConsent()
        // 还没配好手机操作:开关显示打开,下面说要先完成设置
        assertEquals(RecoveryCopy.NEED_SETUP, RecoveryCopy.pending(a11y, fresh, paired = false, autoFailed = emptySet()))
        // 配好了、正在补上:不多说;补上没成功:照实说
        assertNull(RecoveryCopy.pending(a11y, fresh, paired = true, autoFailed = emptySet()))
        assertEquals(RecoveryCopy.FAILED, RecoveryCopy.pending(a11y, fresh, paired = true, autoFailed = setOf(a11y)))
        // 已经生效,或用户亲手关掉:什么都不说
        assertNull(RecoveryCopy.pending(a11y, RecoveryConsent(applied = setOf(a11y)), paired = false, autoFailed = setOf(a11y)))
        assertNull(RecoveryCopy.pending(a11y, RecoveryConsent(declined = setOf(a11y)), paired = false, autoFailed = emptySet()))
    }

    @Test
    fun autoOnNoteOnlyWhenStopped() {
        assertNull(RecoveryCopy.autoOnBlocked(null))
        assertTrue(RecoveryCopy.autoOnBlocked(AutoOnBlock.NotAllowedHere)!!.contains("始终允许"))
        assertEquals(AutoOnBlock.entries.size, AutoOnBlock.entries.mapNotNull(RecoveryCopy::autoOnBlocked).toSet().size)
    }

    @Test
    fun restartNoteOnlyAfterSuccess() {
        // 1970-01-01 08:05 UTC
        val at = (8 * 60 + 5) * 60_000L
        assertEquals("上次在 08:05 自动重启过无障碍服务。", RecoveryCopy.restarted(RepairOutcome(true, at, 1), ZoneOffset.UTC))
        assertNull(RecoveryCopy.restarted(RepairOutcome(false, at, 2), ZoneOffset.UTC))
        assertNull(RecoveryCopy.restarted(null))
    }
}
