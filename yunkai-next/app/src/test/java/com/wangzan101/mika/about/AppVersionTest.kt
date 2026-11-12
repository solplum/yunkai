package com.wangzan101.mika.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun comparesNumericParts() {
        assertTrue(AppVersion.isNewer("0.2.0", "0.1.0"))
        assertTrue(AppVersion.isNewer("0.10.0", "0.9.9"))
        assertTrue(AppVersion.isNewer("1.0", "0.99.99"))
        assertFalse(AppVersion.isNewer("0.1.0", "0.1.0"))
        assertFalse(AppVersion.isNewer("0.1.0", "0.2.0"))
    }

    @Test
    fun ignoresLeadingVAndMissingParts() {
        assertEquals(0, AppVersion.compare("v0.2.0", "0.2.0"))
        assertEquals(0, AppVersion.compare("V1.2", "1.2.0"))
        assertTrue(AppVersion.isNewer("v0.1.1", "0.1"))
        assertEquals("0.2.0", AppVersion.normalize(" v0.2.0 "))
    }

    @Test
    fun releaseBeatsPrerelease() {
        assertTrue(AppVersion.isNewer("0.2.0", "0.2.0-beta.1"))
        assertFalse(AppVersion.isNewer("0.2.0-beta.1", "0.2.0"))
        assertTrue(AppVersion.isNewer("0.2.0-beta.1", "0.1.0"))
        assertTrue(AppVersion.isNewer("0.2.0-beta.2", "0.2.0-beta.1"))
        assertTrue(AppVersion.isNewer("0.2.0-beta.10", "0.2.0-beta.9"))
        assertTrue(AppVersion.isNewer("0.2.0-rc.1", "0.2.0-beta.3"))
        assertTrue(AppVersion.isNewer("0.2.0-beta.1", "0.2.0-beta"))
    }

    @Test
    fun buildMetadataIgnored() {
        assertEquals(0, AppVersion.compare("0.2.0+65fcf92", "0.2.0"))
    }
}
