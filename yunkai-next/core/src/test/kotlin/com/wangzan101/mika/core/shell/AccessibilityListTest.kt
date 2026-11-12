package com.wangzan101.mika.core.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityListTest {
    private val mika = PermissionCommands.accessibilityService("com.wangzan101.mika")
    private val mikaLong = "com.wangzan101.mika/com.wangzan101.mika.a11y.MikaAccessibilityService"
    private val other = "com.example.reader/.ReaderService"
    private val other2 = "org.sample.keys/org.sample.keys.KeyService"

    @Test
    fun emptyOrUnsetValues() {
        listOf(null, "", "   ", "null", "null\n", ":", " : ").forEach { raw ->
            assertEquals("raw=$raw", emptyList<String>(), AccessibilityList.entries(raw))
            assertFalse(AccessibilityList.contains(raw, mika))
            assertEquals(mika, AccessibilityList.with(raw, mika))
            assertEquals("", AccessibilityList.without(raw, mika))
        }
    }

    @Test
    fun appendKeepsOtherServicesInOrder() {
        assertEquals("$other:$mika", AccessibilityList.with(other, mika))
        assertEquals("$other:$other2:$mika", AccessibilityList.with("$other:$other2\n", mika))
    }

    @Test
    fun alreadyPresentIsNotDuplicated() {
        assertEquals("$other:$mika", AccessibilityList.with("$other:$mika", mika))
        // 系统里存的是完整类名写法,也算已经有了
        assertEquals("$mikaLong:$other", AccessibilityList.with("$mikaLong:$other", mika))
        assertTrue(AccessibilityList.contains(mikaLong, mika))
    }

    @Test
    fun removeKeepsOthersAndHandlesBothSpellings() {
        assertEquals("$other:$other2", AccessibilityList.without("$other:$mika:$other2", mika))
        assertEquals(other, AccessibilityList.without("$mikaLong:$other", mika))
        assertEquals(other, AccessibilityList.without(other, mika))
        assertEquals("", AccessibilityList.without(mika, mika))
    }

    @Test
    fun strayColonsAndSpacesAreCleanedUp() {
        assertEquals("$other:$other2:$mika", AccessibilityList.with(":$other:: $other2 :", mika))
        assertEquals("$other:$other2", AccessibilityList.without("$other::$mika:$other2:", mika))
    }

    @Test
    fun differentServiceOfSamePackageIsNotConfused() {
        val sibling = "com.wangzan101.mika/.a11y.OtherService"
        assertFalse(AccessibilityList.contains(sibling, mika))
        assertEquals(sibling, AccessibilityList.without("$sibling:$mika", mika))
    }
}
