package com.wangzan101.mika.cap.phone.shell

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.SystemAction
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机测试:系统动作的 intent 在这台手机上有没有 App 接(没有就跳过,不算失败),有的话打开闹钟列表落在 Mika 的屏上、
 * 用户的主屏不动。不设真的闹钟、计时器,不建日程。
 */
@RunWith(AndroidJUnit4::class)
class SystemActionDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val agent = DeviceSupport.newAgent()

    @After
    fun tearDown() = agent.release()

    private fun handlers(a: SystemAction): List<SystemIntents.Handler> =
        context.packageManager.queryIntentActivities(SystemIntents.toIntent(SystemIntents.spec(a)), PackageManager.MATCH_DEFAULT_ONLY).map {
            SystemIntents.Handler(it.activityInfo.packageName, it.activityInfo.name, it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0)
        }

    @Test
    fun intentsResolveOrAreSkipped() {
        val all = listOf(SystemAction.SetAlarm(9, 40), SystemAction.SetTimer(60), SystemAction.ShowAlarms, SystemAction.AddCalendarEvent("t", 0))
        val resolved = all.associateWith { handlers(it) }
        assumeTrue("no app on this phone takes these actions: $resolved", resolved.values.any { it.isNotEmpty() })
        // 接得住的都能挑出一个直接指定的页面
        for ((a, h) in resolved) if (h.isNotEmpty()) assertTrue("$a", SystemIntents.choose(h, null) in h)
    }

    @Test
    fun alarmListOpensOnMikasScreenOnly() = runBlocking<Unit> {
        val h = handlers(SystemAction.ShowAlarms)
        assumeTrue("no clock app takes SHOW_ALARMS", h.isNotEmpty())
        val shell = DeviceSupport.shell()
        val main = DeviceSupport.mainScreenState(shell)
        val r = agent.screen.systemAction(SystemAction.ShowAlarms)
        assertTrue("$r", r.result is ActionResult.Ok)
        assertTrue("${r.handledBy}", r.handledBy in h.map { it.packageName })
        assertEquals(r.handledBy, agent.screen.snapshot().packageName)
        assertEquals(main, DeviceSupport.mainScreenState(shell))
    }
}
