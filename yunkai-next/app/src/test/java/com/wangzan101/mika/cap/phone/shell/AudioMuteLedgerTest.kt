package com.wangzan101.mika.cap.phone.shell

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 静音记录:解析、恢复规则(没设置过写 allow)、落文件、启动恢复只动记录里的包。 */
class AudioMuteLedgerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file() = File(tmp.root, "ledger")

    @Test fun parseDistinguishesUnsetFromEveryMode() {
        assertNull(AudioMuteLedger.parseSetMode("No operations."))
        assertNull(AudioMuteLedger.parseSetMode(""))
        assertEquals("allow", AudioMuteLedger.parseSetMode("PLAY_AUDIO: allow; time=+1d"))
        assertEquals("ignore", AudioMuteLedger.parseSetMode("PLAY_AUDIO: ignore; time=+2s"))
        assertEquals("deny", AudioMuteLedger.parseSetMode("PLAY_AUDIO: deny"))
        assertEquals("default", AudioMuteLedger.parseSetMode("PLAY_AUDIO: default"))
        // getAudioMode 对外:没设置过和明确 default 都是 "default"
        assertEquals("default", ShellService.parsePlayAudioMode("No operations."))
        assertEquals("ignore", ShellService.parsePlayAudioMode("PLAY_AUDIO: ignore"))
    }

    @Test fun restoreModeWritesAllowForUnsetAndKeepsExplicitModes() {
        assertEquals("allow", AudioMuteLedger.restoreMode("default"))
        assertEquals("allow", AudioMuteLedger.restoreMode(null))
        assertEquals("allow", AudioMuteLedger.restoreMode(""))
        for (m in listOf("allow", "ignore", "deny", "foreground")) assertEquals(m, AudioMuteLedger.restoreMode(m))
    }

    @Test fun recordsSurviveRestartAndAreRemovedOnRestore() {
        val a = AudioMuteLedger(file())
        a.put("a.app", "default")
        a.put("b.app", "deny")
        val b = AudioMuteLedger(file()) // 进程被杀后重新启动
        assertEquals(listOf("a.app", "b.app"), b.packages())
        assertEquals("deny", b["b.app"])
        b.remove("a.app")
        b.remove("b.app")
        assertFalse(file().exists())
        assertTrue(AudioMuteLedger(file()).packages().isEmpty())
    }

    @Test fun startupRestoreOnlyTouchesRecordedPackagesAndKeepsFailures() {
        AudioMuteLedger(file()).apply { put("a.app", "default"); put("b.app", "deny"); put("c.app", "allow") }
        val written = mutableListOf<Pair<String, String>>()
        val ledger = AudioMuteLedger(file())
        val all = ledger.restoreAll { pkg, mode -> written += pkg to mode; pkg != "c.app" }
        assertFalse(all)
        assertEquals(listOf("a.app" to "allow", "b.app" to "deny", "c.app" to "allow"), written)
        // 失败的留着,下次启动再试;成功的已删
        assertEquals(listOf("c.app"), AudioMuteLedger(file()).packages())
        assertTrue(AudioMuteLedger(file()).restoreAll { _, _ -> true })
        assertFalse(file().exists())
    }

    @Test fun unwritableLocationFallsBackToMemory() {
        val ledger = AudioMuteLedger(File(tmp.root, "no/such/dir/ledger"))
        ledger.put("a.app", "ignore")
        assertEquals("ignore", ledger["a.app"])
    }

    @Test fun garbageLinesAreIgnored() {
        file().writeText("a.app default\nnot a record\n; rm -rf /\nb.app ignore\n")
        assertEquals(listOf("a.app", "b.app"), AudioMuteLedger(file()).packages())
    }
}
