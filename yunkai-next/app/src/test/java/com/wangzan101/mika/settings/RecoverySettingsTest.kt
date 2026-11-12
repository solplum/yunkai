package com.wangzan101.mika.settings

import android.content.SharedPreferences
import com.wangzan101.mika.core.shell.RecoveryCapability
import com.wangzan101.mika.core.shell.RecoveryConsent
import com.wangzan101.mika.core.shell.RestoreOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoverySettingsTest {
    private val a11y = RecoveryCapability.Accessibility
    private val battery = RecoveryCapability.BatteryWhitelist
    private val restore = RecoveryCapability.RestoreAfterReboot

    @Test fun freshInstallWantsEverythingAndHasNothingApplied() {
        val c = RecoverySettings(MemoryPrefs()).consent.value
        assertEquals(RecoveryConsent(), c)
        assertEquals(RecoveryCapability.entries, c.pending)
    }

    @Test fun oldInstallWithoutChoiceKeysCountsAsUntouched() {
        // 旧版本只写「已生效」:false 分不出是没动过还是关掉过,一律当没动过,连接可用时按默认打开
        val prefs = MemoryPrefs()
        prefs.edit().putBoolean("accessibility", true).putBoolean("battery_whitelist", false)
            .putBoolean("restore_after_reboot", false).commit()
        val c = RecoverySettings(prefs).consent.value
        assertEquals(RecoveryConsent(applied = setOf(a11y)), c)
        assertEquals(listOf(battery, restore, RecoveryCapability.AutoWirelessDebugging), c.pending)
    }

    @Test fun appliedAndDeclinedSurviveARestart() {
        val prefs = MemoryPrefs()
        val saved = RecoveryConsent(applied = setOf(a11y, restore), declined = setOf(battery))
        RecoverySettings(prefs).save(saved)
        assertEquals(saved, RecoverySettings(prefs).consent.value)
        // 亲手关掉后又打开:回到「要」
        RecoverySettings(prefs).save(saved.chose(battery, true))
        assertTrue(RecoverySettings(prefs).consent.value.wants(battery))
    }

    @Test fun lastRestoreIsClearedOnlyWhenRestoreItselfChanges() {
        val prefs = MemoryPrefs()
        val s = RecoverySettings(prefs)
        s.save(RecoveryConsent(applied = setOf(restore)))
        s.recordRestore(RestoreOutcome.NotRecovered)
        // 别的项变了:开机结果仍然有效
        s.save(RecoveryConsent(applied = setOf(restore), declined = setOf(battery)))
        assertEquals(RestoreOutcome.NotRecovered, RecoverySettings(prefs).lastRestore.value)
        s.save(RecoveryConsent(declined = setOf(battery)))
        assertNull(s.lastRestore.value)
        assertNull(RecoverySettings(prefs).lastRestore.value)
    }

    /** 内存里的设置,只够这几项读写。 */
    private class MemoryPrefs : SharedPreferences {
        private val values = HashMap<String, Any?>()

        override fun getAll(): Map<String, *> = values.toMap()
        override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
            @Suppress("UNCHECKED_CAST") (values[key] as? Set<String>) ?: defValues
        override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun contains(key: String) = key in values
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clear = false
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: Set<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { removed += key }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean {
                if (clear) values.clear()
                removed.forEach(values::remove)
                values.putAll(pending)
                return true
            }
            override fun apply() { commit() }
        }
    }
}
