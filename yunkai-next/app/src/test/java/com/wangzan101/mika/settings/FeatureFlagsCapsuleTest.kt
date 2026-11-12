package com.wangzan101.mika.settings

import android.content.SharedPreferences
import com.wangzan101.mika.capsule.CapsuleMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FeatureFlagsCapsuleTest {
    @Test fun capsuleDefaultsToAlways() {
        assertEquals(CapsuleMode.Always, FeatureFlags(MemoryPrefs()).capsuleMode())
    }

    @Test fun capsuleChoiceIsKeptAndUnknownFallsBack() {
        val prefs = MemoryPrefs()
        FeatureFlags(prefs).setCapsuleMode(CapsuleMode.Off)
        assertEquals(CapsuleMode.Off, FeatureFlags(prefs).capsuleMode())
        prefs.edit().putString("capsule_mode", "Sometimes").commit()
        assertEquals(CapsuleMode.Always, FeatureFlags(prefs).capsuleMode())
    }

    @Test fun changesEmitCurrentThenEachChange() = runTest {
        val flags = FeatureFlags(MemoryPrefs())
        assertEquals(CapsuleMode.Always, flags.capsuleModeChanges().first())
        val seen = mutableListOf<CapsuleMode>()
        val job = launch { flags.capsuleModeChanges().take(2).toList(seen) }
        runCurrent()
        flags.setCapsuleMode(CapsuleMode.Off)
        job.join()
        assertEquals(listOf(CapsuleMode.Always, CapsuleMode.Off), seen)
    }

    /** 内存里的设置,改动时同步通知监听者(和系统实现一样在写入后回调)。 */
    private class MemoryPrefs : SharedPreferences {
        private val values = HashMap<String, Any?>()
        private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): Map<String, *> = values.toMap()
        override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
            @Suppress("UNCHECKED_CAST") (values[key] as? Set<String>) ?: defValues
        override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun contains(key: String) = key in values
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { listeners += l }
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) { listeners -= l }

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private var clear = false
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: Set<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { pending[key] = null }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean {
                if (clear) values.clear()
                pending.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
                pending.keys.forEach { k -> listeners.toList().forEach { it.onSharedPreferenceChanged(this@MemoryPrefs, k) } }
                return true
            }
            override fun apply() { commit() }
        }
    }
}
