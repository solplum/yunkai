package com.wangzan101.mika.cap.sandbox

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.workspace.SandboxTree
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SandboxStateTest {
    @get:Rule val texts = ChineseTexts()

    private val tmp: File = Files.createTempDirectory("sandbox-state").toFile()
    private val dirs = SandboxDirs(File(tmp, "files/sandbox"), File(tmp, "cache/proot-tmp"), File(tmp, "lib"), JVM_GATE, SandboxTree())
    private fun state(abis: List<String> = listOf("arm64-v8a")) = SandboxState(dirs, File(tmp, "files/sandbox-settings.json")) { abis }

    @Test
    fun networkDefaultsOff() {
        val s = state()
        assertFalse(s.networkAllowed)
        assertEquals(SandboxStatus.NotInstalled, s.status.value)
        s.networkAllowed = true
        assertTrue("persisted", state().networkAllowed)
    }

    @Test
    fun notArm64IsUnsupported() {
        assertTrue(state(listOf("x86_64")).status.value is SandboxStatus.Unsupported)
    }

    @Test
    fun unfinishedInstallIsNotReady() {
        val s = state()
        assertNull(s.completedStep())
        s.markStep(InstallStep.Extract)
        // 进程在安装中途被杀:重新读取时不能是就绪
        val again = state()
        assertEquals(InstallStep.Extract, again.completedStep())
        assertTrue(again.status.value is SandboxStatus.Broken)
    }
}
