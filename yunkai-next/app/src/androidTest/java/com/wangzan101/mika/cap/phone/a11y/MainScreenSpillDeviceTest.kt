package com.wangzan101.mika.cap.phone.a11y

import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.TEST_PACKAGE
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import com.wangzan101.mika.core.uistate.ActionResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机测试:Mika 屏上的页面经另一个进程里的服务打开另一个 App(和从后台打开一样),系统把它放到主屏;
 * 点的那一下的结果要报 opened、它要被挪回 Mika 的屏,主屏最上面回到原来那个。
 * 打开哪个 App 由仪器参数 spillTarget 指定(默认系统设置);它已经有任务时不跑(挪过来再随屏销毁会丢掉那个任务)。
 * 装了两份的 App 会先问打开哪一个:那个问题不挪、留在主屏,报 picker、onUserScreen,任务等用户选;
 * 仪器参数 pickOption 给了时测试当用户在主屏上选(或 back 关掉),再看选出来的被挪回 Mika 的屏、报开的是哪一份。
 */
@RunWith(AndroidJUnit4::class)
class MainScreenSpillDeviceTest {
    // 和任务里一样,屏的尺寸、密度同主屏:挪过来的页面不必因尺寸变了重建(系统的选择框重建时会自己关掉)
    private val agent = run {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val m = android.util.DisplayMetrics().also {
            @Suppress("DEPRECATION")
            ctx.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
        }
        val shell = DeviceSupport.shell()
        val d = com.wangzan101.mika.cap.phone.display.AgentDisplay(shell, m.widthPixels, m.heightPixels, m.densityDpi)
        DeviceSupport.Agent(d, AndroidScreen(DeviceSupport.service(), shell, DeviceSupport.ready(d)))
    }
    private val screen get() = agent.screen
    private val shell = DeviceSupport.shell()
    private val target = InstrumentationRegistry.getArguments().getString("spillTarget") ?: SETTINGS

    @After
    fun tearDown() {
        // 没挪回来时,把留在主屏上的那个挪到 Mika 的屏,随屏一起销毁
        val main = shell.topTaskId(Display.DEFAULT_DISPLAY)
        // 中途失败、问题还留在主屏上:按返回关掉它
        screen.userPick.value?.let { p -> if (main >= 0 && shell.packageOfTask(main) == p.packageName) sh("input keyevent 4") }
        if (main >= 0 && shell.packageOfTask(main) == target) shell.moveTask(main, agent.display.displayId)
        agent.release()
    }

    /** 仪器参数 allowExisting=true 时也跑(那个任务会被挪过来、随屏关掉,和从最近任务里划掉一样)。 */
    private fun noTaskYet() {
        if (InstrumentationRegistry.getArguments().getString("allowExisting") == "true") return
        assumeFalse("$target already has a task",
            sh("dumpsys activity recents").lines().any { val l = it.trim(); l.startsWith("* Recent") && " A=" in l && l.substringAfter(" A=").substringAfter(':').startsWith(target) })
    }

    private suspend fun spillPage(): com.wangzan101.mika.core.uistate.ScreenSnapshot {
        screen.ensureDisplay()
        sh("am start --display ${agent.display.displayId} -n $TEST_PACKAGE/${SpillTestActivity::class.java.name} --es ${SpillTestActivity.EXTRA_TARGET} $target")
        val s = pollUntil(5_000) { runBlocking { screen.snapshot() }.takeIf { it.elements.any { e -> e.name == "open elsewhere" } } }
        assertNotNull("spill page did not come up", s)
        return s!!
    }

    /** 对照:不经 Mika 的动作、直接往 Mika 的屏上注入一下点按,开出来的落在主屏(这就是要修的现象)。 */
    @Test
    fun withoutTheWatchTheLaunchLandsOnTheMainScreen() = runBlocking<Unit> {
        noTaskYet()
        val s = spillPage()
        val vd = agent.display.displayId
        val mainBefore = shell.topTaskId(Display.DEFAULT_DISPLAY)
        val b = s.elements.first { it.name == "open elsewhere" }
        val vdBefore = shell.topTaskId(vd)
        assertTrue(shell.injectTap(vd, b.centerX, b.centerY))
        val landed = pollUntil(5_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it != mainBefore } }
        println("before: main top $mainBefore -> $landed (${landed?.let { shell.packageOfTask(it) }}), Mika screen top $vdBefore -> ${shell.topTaskId(vd)}")
        assertNotNull("launch did not land on the main screen", landed)
        assertEquals("Mika's screen changed", vdBefore, shell.topTaskId(vd))
    }

    @Test
    fun appOpenedOnTheMainScreenIsPulledBack() = runBlocking<Unit> {
        noTaskYet()
        val s = spillPage()
        val vd = agent.display.displayId
        val mainBefore = shell.topTaskId(Display.DEFAULT_DISPLAY)
        val button = s.elements.first { it.name == "open elsewhere" }
        val r = screen.tapRef(button.ref, s.snapshotId)
        assertTrue("expected Ok, got $r", r is ActionResult.Ok)
        val opened = (r as ActionResult.Ok).opened
        assertNotNull("tap did not report opened: $r", opened)
        val now = screen.snapshot()
        println("spill result: $r; on Mika's screen (${now.packageName}): ${now.elements.filter { it.clickable }.map { it.name }}")
        if (!opened!!.picker) {
            assertTrue("not pulled back: $opened", opened.pulledBack)
            assertEquals(target, shell.packageOfTask(shell.topTaskId(vd)))
            assertEquals("main screen top did not come back", mainBefore, pollUntil(3_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it == mainBefore } })
            return@runBlocking
        }
        // 系统在用户屏上问打开哪一个:不挪、不重开,留在主屏等用户选
        assertTrue("question was moved: $opened", !opened.pulledBack && opened.onUserScreen)
        val chooserTask = shell.topTaskId(Display.DEFAULT_DISPLAY)
        assertEquals("question is not on the main screen", opened.packageName, shell.packageOfTask(chooserTask))
        assertNotNull("run is not waiting for the user", screen.userPick.value)
        assertEquals("Mika's screen changed", SpillTestActivity::class.java.name, shell.topActivity(vd))
        // 仪器参数 pickOption=<第几个,从 0 起> 或 back:这时测试当用户,在主屏上点那个选项(或按返回关掉问题)
        val pick = InstrumentationRegistry.getArguments().getString("pickOption") ?: return@runBlocking
        if (pick == "back") sh("input keyevent 4")
        else {
            val label = r.chooser?.options?.firstOrNull()?.label
            val nodes = ScreenReader.contentWindows(DeviceSupport.service().windowsOn(Display.DEFAULT_DISPLAY))
                .filter { it.root?.packageName?.toString() == opened.packageName }
                .flatMap { w -> ScreenReader.read(listOf(w), agent.display.width, agent.display.height).entries.map { it.element } }
                .filter { it.clickable && it.name == label }
            val option = nodes.getOrNull(pick.toInt())
            assertNotNull("no option $pick: ${nodes.map { it.name }}", option)
            sh("input tap ${option!!.centerX} ${option.centerY}")
        }
        val settled = screen.awaitUserPick()
        // 选出来的要用户亲手处理:这时测试当用户点了继续
        if (settled is AndroidScreen.PickResult.NeedsUser) screen.afterUserStep(settled)
        val after = screen.snapshot()
        val top = shell.topTaskId(vd)
        println("after the user ($pick): $settled; opened=${after.opened}; Mika's screen top ${shell.packageOfTask(top)} user ${shell.userOfTask(top)}; " +
            "main top ${shell.topTaskId(Display.DEFAULT_DISPLAY)} (before $mainBefore)")
        if (pick == "back") assertTrue("not reported closed: ${after.opened}", after.opened?.closed == true)
        else {
            assertTrue("not pulled back: ${after.opened}", after.opened?.pulledBack == true && (after.opened?.inProfile != null || after.opened?.byUser == true))
            assertEquals(target, shell.packageOfTask(top))
        }
        assertEquals("main screen top did not come back", mainBefore, pollUntil(3_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it == mainBefore } })
    }
}
