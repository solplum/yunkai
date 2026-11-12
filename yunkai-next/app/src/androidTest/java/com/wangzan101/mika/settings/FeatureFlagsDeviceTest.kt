package com.wangzan101.mika.settings

import android.content.Context
import com.wangzan101.mika.capsule.CapsuleMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeatureFlagsDeviceTest {
    @Test fun upgradesOldSpeedSettingsOnceThenRespectsUserChoice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("feature_flags_batch_migration_test", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().putString("speed_flags", "none").commit()
            val flags = FeatureFlags(prefs)
            assertTrue(flags.speedFlags().batchSteps)
            assertTrue(flags.speedFlags().adaptiveVision)
            assertTrue(flags.speedFlags().inlineScreen)
            flags.setSpeed(flags.speedFlags().copy(batchSteps = false, adaptiveVision = false))
            assertFalse(flags.speedFlags().batchSteps)
            assertFalse(flags.speedFlags().adaptiveVision)
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test fun capsuleDefaultsToAlwaysAndKeepsChoice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("feature_flags_capsule_test", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().commit()
            val flags = FeatureFlags(prefs)
            assertEquals(CapsuleMode.Always, flags.capsuleMode())
            flags.setCapsuleMode(CapsuleMode.Off)
            assertEquals(CapsuleMode.Off, FeatureFlags(prefs).capsuleMode())
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
