// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
// Modified for Yunkai Next isolated prototype (2026-10-30); see repository-root UPSTREAM.md.
package com.wangzan101.mika.cap.phone.a11y

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.cap.phone.display.LazyAgentDisplay
import com.wangzan101.mika.shell.IMikaShell
import com.wangzan101.mika.cap.phone.shell.ShellBinding
import com.wangzan101.mika.cap.phone.PhoneServices
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import com.wangzan101.mika.a11y.MikaAccessibilityService

/** 真机测试共用:shell 连接、无障碍服务、虚拟屏上的 AndroidScreen。需要内置助手在运行(已配对、无线调试开着)且 Mika 无障碍服务已开启。 */
object DeviceSupport {
    const val SETTINGS = "com.android.settings"
    val TEST_PACKAGE get() = InstrumentationRegistry.getInstrumentation().context.packageName
    private var connection: ShellBinding? = null
    private val phone get() = PhoneServices.of(InstrumentationRegistry.getInstrumentation().targetContext)

    /**
     * 与 App 共用进程内的那条 shell 连接并一直占着:各自绑定再各自解绑会让先解绑的一方结束 shell 服务,
     * 另一方手里的 binder 随之失效。
     */
    @Synchronized
    fun shell(): IMikaShell {
        val c = connection ?: phone.sharedShell.acquire().also {
            connection = it
            rebindAccessibility()
        }
        return awaitShell(c)
    }

    /** 等连接拿到活着的助手;等不到就带着当前状态失败,提示先配对。助手被杀后看门狗重起要一会儿,断开回调到达前也可能还拿着已死的 binder。 */
    fun awaitShell(c: ShellBinding, timeoutMs: Long = 30_000): IMikaShell = runBlocking {
        withTimeoutOrNull(timeoutMs) { c.shell.filterNotNull().first { it.asBinder().pingBinder() } }
    } ?: error(
        "Mika's phone helper is not running (status=${phone.shellControl.status.value}): " +
            "pair it once in Mika (设置手机操作) with Wireless debugging on, keep Wireless debugging on, then rerun",
    )

    /** 服务掉线(前面的测试结束了本进程的绑定等)时先促使系统重绑,再等它连上。 */
    fun service(): ScreenAccessibility {
        if (ScreenAccessibility.instance.value == null) rebindAccessibility()
        return runBlocking {
            withTimeoutOrNull(10_000) { ScreenAccessibility.instance.filterNotNull().first() }
        } ?: error("Mika accessibility service is not enabled")
    }

    class Agent(val display: AgentDisplay, val screen: AndroidScreen) {
        fun release() = display.release()
    }

    fun newAgent(): Agent {
        val shell = shell()
        val d = AgentDisplay(shell, 720, 1600, 320)
        return Agent(d, AndroidScreen(service(), shell, ready(d)))
    }

    /** 已经建好的屏:测试自己先建屏(还可能先往上放 App),读屏不必等 phone_launch_app。 */
    fun ready(d: AgentDisplay): LazyAgentDisplay = LazyAgentDisplay(d.width, d.height) { d }.also { runBlocking { it.get() } }

    /**
     * 启动测试时系统会强停本 App,无障碍服务随之被记为已崩溃、不再自动重绑。
     * 已开启时把它从开启列表里摘下再放回,促使系统重新绑定;没开启就不动。
     */
    private fun rebindAccessibility() {
        if (ScreenAccessibility.instance.value != null) return
        val key = "enabled_accessibility_services"
        val mika = "${InstrumentationRegistry.getInstrumentation().targetContext.packageName}/${MikaAccessibilityService::class.java.name}"
        val enabled = sh("settings get secure $key").trim().split(':')
        if (mika !in enabled) return
        try {
            sh("settings put secure $key ${enabled.filter { it != mika }.joinToString(":").ifEmpty { "\"\"" }}")
            Thread.sleep(500)
        } finally {
            sh("settings put secure $key ${enabled.joinToString(":")}")
        }
    }

    fun sh(cmd: String): String {
        // 不压制其他无障碍服务,否则 Mika 的服务会在测试期间被暂停
        val ua = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val pfd = ua.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    /** 在虚拟屏上打开测试包里的页面(按类给,类名和清单里登记的一致)。 */
    fun startTestActivity(displayId: Int, activity: Class<out android.app.Activity>) {
        sh("am start --display $displayId -n $TEST_PACKAGE/${activity.name}")
    }

    /**
     * 主屏状态:系统的最上层焦点屏、0 号屏的焦点窗口与焦点 Activity、主屏最上面的任务。
     * Agent 在虚拟屏上做任何事,这些都不应该变。
     */
    fun mainScreenState(shell: IMikaShell): String {
        val topDisplay = sh("dumpsys window").lines().map { it.trim() }
            .firstOrNull { it.startsWith("mTopFocusedDisplayId=") }
        val section = sh("dumpsys window displays").substringAfter("Display: mDisplayId=0", "")
            .substringBefore("Display: mDisplayId=").lines().map { it.trim() }
        val focus = section.firstOrNull { it.startsWith("mCurrentFocus=") }
        val focusedApp = section.firstOrNull { it.startsWith("mFocusedApp=") }
            ?.let { Regex("u0 (\\S+)").find(it)?.groupValues?.get(1) }
        return "$topDisplay $focus focusedApp=$focusedApp topTask=${shell.topTaskId(android.view.Display.DEFAULT_DISPLAY)}"
    }

    fun <T : Any> pollUntil(timeoutMs: Long, block: () -> T?): T? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            block()?.let { return it }
            Thread.sleep(100)
        }
        return block()
    }
}
