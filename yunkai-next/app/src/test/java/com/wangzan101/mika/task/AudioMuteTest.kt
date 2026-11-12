package com.wangzan101.mika.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryMarker : MarkerStore {
    var saved: Map<String, String?>? = null
    override fun load() = saved
    override fun save(values: Map<String, String?>) { saved = values }
    override fun clear() { saved = null }
}

class AudioMuteTest {
    private class FakeAudio(val modes: MutableMap<String, String>) : AudioPort {
        var online = true
        override fun mode(packageName: String) = if (online) modes[packageName] ?: "default" else null
        override fun mute(packageName: String): Boolean = online.also { if (it) modes[packageName] = "ignore" }
        override fun setMode(packageName: String, mode: String): Boolean = online.also { if (it) modes[packageName] = mode }
    }

    @Test
    fun restoresPriorModesAfterCrash() {
        val audio = FakeAudio(mutableMapOf("a.app" to "allow"))
        val store = MemoryMarker()
        val mute = AudioMute(audio, store)
        assertTrue(mute.mute("a.app"))
        assertTrue(mute.mute("b.app"))
        assertTrue(mute.mute("a.app")) // 再次静音不能把 ignore 记成原值
        assertEquals("ignore", audio.modes["a.app"])

        // 进程崩溃:新实例按持久化记录恢复
        assertTrue(AudioMute(audio, store).restoreAll())
        assertEquals("allow", audio.modes["a.app"])
        assertEquals("default", audio.modes["b.app"])
        assertNull(store.saved)
    }

    @Test
    fun unreadableModeMeansNotMuted() {
        val audio = FakeAudio(mutableMapOf()).apply { online = false }
        val store = MemoryMarker()
        assertFalse(AudioMute(audio, store).mute("a.app"))
        assertNull(store.saved)
    }

    @Test
    fun failedRestoreKeepsRecord() {
        val audio = FakeAudio(mutableMapOf("a.app" to "allow"))
        val store = MemoryMarker()
        AudioMute(audio, store).mute("a.app")
        audio.online = false
        assertFalse(AudioMute(audio, store).restoreAll())
        assertEquals(mapOf("a.app" to "allow"), store.saved)
    }

    @Test
    fun restoreOneForTakeoverThenMuteAgain() {
        val audio = FakeAudio(mutableMapOf("a.app" to "allow"))
        val store = MemoryMarker()
        val mute = AudioMute(audio, store)
        mute.mute("a.app")
        mute.mute("b.app")
        assertTrue(mute.restore("a.app"))
        assertEquals("allow", audio.modes["a.app"])
        assertEquals("ignore", audio.modes["b.app"])
        assertEquals(mapOf("b.app" to "default"), store.saved)
        // 交还后重新静音,原值仍是 allow
        assertTrue(mute.mute("a.app"))
        assertTrue(mute.restoreAll())
        assertEquals("allow", audio.modes["a.app"])
    }
}
