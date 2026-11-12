package com.wangzan101.mika.cap.phone.a11y

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 真机:系统选择器、文件选择器、权限框的包名按标准 Intent 能解析出来,且不会把普通 App 算进去。 */
@RunWith(AndroidJUnit4::class)
class SystemPickersTest {
    @Test
    fun resolvesThePickersOfThisDevice() {
        val pm = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
        val pickers = SystemPickers.resolve(pm)
        assertTrue(pickers.toString(), SystemPickers.SYSTEM_PACKAGE in pickers)
        // 至少解析出权限框和文件选择器(AOSP 都有)
        assertTrue(pickers.toString(), pickers.size >= 3)
        val documents = pm.resolveActivity(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 0)
            ?.activityInfo?.packageName
        assertTrue("$documents not in $pickers", documents == null || documents in pickers)
        assertTrue(pickers.none { it == "com.android.settings" || it.startsWith("com.wangzan101") })
        assertTrue(SystemPickers.settingsPackage(pm) == DeviceSupport.SETTINGS)
    }
}
