package com.wangzan101.mika.cap.phone.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AppSwitchTest {
    private val pickers = setOf("com.android.permissioncontroller", "com.android.intentresolver", "com.android.documentsui")
    // 只有系统设置提供的页面在目标任务里算任务的一部分;预装的浏览器、应用商店不算
    private val settings = setOf("com.android.settings")
    private fun check(current: String?, sameTask: Boolean, target: String? = "com.clock"): AppSwitch.Switch? {
        var asked = false
        val r = AppSwitch.switchedTo(target, current, sameTask = { asked = true; sameTask }, partOfTask = pickers, settingsPage = { it in settings })
        if (current == null || current == target || current in pickers) assertFalse("sameTask queried needlessly", asked)
        return r
    }

    @Test fun systemPageOpenedByTheTargetInItsOwnTaskIsPartOfTheTask() {
        // 时钟「添加城市」打开的是系统设置提供的选择页,但在时钟自己的任务里
        assertNull(check("com.android.settings", sameTask = true))
    }

    @Test fun thirdPartyAppInTheTargetsTaskIsStillReportedButAsOpenedByTarget() {
        assertEquals(AppSwitch.Switch("com.share", byTarget = true), check("com.share", sameTask = true))
    }

    @Test fun preinstalledAppInTheTargetsTaskIsStillReported() {
        assertEquals(AppSwitch.Switch("com.vendor.browser", byTarget = true), check("com.vendor.browser", sameTask = true))
    }

    @Test fun systemPickersAndPermissionDialogsArePartOfTheTask() {
        for (p in pickers) assertNull(p, check(p, sameTask = false))
    }

    @Test fun anotherAppInAnotherTaskIsASwitch() {
        assertEquals(AppSwitch.Switch("com.ads", byTarget = false), check("com.ads", sameTask = false))
        // 系统 App 但不在目标的任务里:照样算被切走
        assertEquals(AppSwitch.Switch("com.android.settings", byTarget = false), check("com.android.settings", sameTask = false))
    }

    @Test fun nothingToCompare() {
        assertNull(check("com.ads", sameTask = false, target = null))
        assertNull(check(null, sameTask = false))
        assertNull(check("com.clock", sameTask = false))
    }
}
