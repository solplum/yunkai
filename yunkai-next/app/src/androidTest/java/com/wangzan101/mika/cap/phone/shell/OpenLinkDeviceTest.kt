package com.wangzan101.mika.cap.phone.shell

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.SystemPickers
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.tool.LinkPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 真机:白名单里的设置页都能在 Agent 的屏上打开,主屏不受影响;没有 App 能打开的链接返回 5。 */
@RunWith(AndroidJUnit4::class)
class OpenLinkDeviceTest {
    private val agent = DeviceSupport.newAgent()

    @After
    fun tearDown() = agent.release()

    @Test
    fun everySettingsActionOpensOnAgentDisplay() = runBlocking<Unit> {
        val shell = DeviceSupport.shell()
        val settings = SystemPickers.settingsPackage(InstrumentationRegistry.getInstrumentation().targetContext.packageManager)
        assertNotNull("no settings app on this device", settings)
        val main = DeviceSupport.mainScreenState(shell)
        val failed = mutableListOf<String>()
        for (action in LinkPolicy.SETTINGS_ACTIONS.sorted()) {
            val uri = if (action == LinkPolicy.APP_DETAILS) "package:${DeviceSupport.TEST_PACKAGE}" else null
            val r = agent.screen.openLink(action, uri, null)
            // 某些系统拿掉了个别原版设置页:解析不到算降级,不算失败;打开了就必须落在系统设置里
            if (r is ActionResult.Failed && r.reason == "link_unresolved") continue
            val top = agent.screen.snapshot().packageName
            if (r !is ActionResult.Ok || top != settings) failed += "$action -> $r on $top"
            assertEquals("main screen changed after $action", main, DeviceSupport.mainScreenState(shell))
        }
        assertTrue(failed.joinToString("\n"), failed.isEmpty())
    }

    @Test
    fun unresolvableUriReturns5() {
        val shell = DeviceSupport.shell()
        val id = agent.display.displayId
        assertEquals(ShellService.START_UNRESOLVED, shell.startView(id, null, "mika-no-such-scheme://page/1", null))
        assertEquals(ShellService.START_UNRESOLVED, shell.startView(id, null, "https://example.com/", "com.wangzan101.mika.no.such.app"))
        // 外壳侧再查一次:绕过分发器直接调也打不开
        assertEquals(ShellService.START_INVALID_LINK, shell.startView(id, null, "tel:10086", null))
        val r = runBlocking { agent.screen.openLink(null, "mika-no-such-scheme://page/1", null) }
        assertEquals(ActionResult.Failed("link_unresolved"), r)
    }
}
