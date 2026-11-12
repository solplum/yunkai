package com.wangzan101.mika.ui

import com.wangzan101.mika.ChineseUi
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.capsule.CapsuleMode
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.Problem
import com.wangzan101.mika.settings.SystemStatus
import com.wangzan101.mika.task.ParallelCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val ready = SystemStatus(34, BuiltinShellStatus.Running, AccessibilityState.On, notificationsAllowed = true)

    private fun home(
        status: SystemStatus = ready,
        mainModel: String? = "DeepSeek",
        onProblem: (Problem) -> Unit = {},
        opened: MutableList<String> = mutableListOf(),
        showDeveloper: Boolean = true,
        usageTokens: Long? = 123_000,
    ) {
        compose.setContent {
            SettingsContent(
                status = status, androidRelease = "14", mainModel = mainModel, onBack = {}, onProblem = onProblem,
                onOpenModels = { opened += "models" }, usageTokens = usageTokens, onOpenUsage = { opened += "usage" }, onOpenPhoneOps = { opened += "phone" }, onOpenDuringWork = { opened += "work" },
                capsuleMode = CapsuleMode.DuringTasks, onOpenCapsule = { opened += "capsule" }, onOpenNotifications = { opened += "notifications" },
                onOpenMemory = { opened += "memory" },
                webSitesCount = 3, onOpenWebSites = { opened += "web" },
                sandbox = SandboxStatus.Ready(network = false), onOpenSandbox = { opened += "sandbox" },
                routes = 8, onOpenRoutes = { opened += "routes" }, onOpenRecovery = { opened += "recovery" },
                showDeveloper = showDeveloper, onOpenDeveloper = { opened += "dev" },
                versionName = "0.1.0", onOpenAbout = { opened += "about" },
            )
        }
    }

    /** 页面是一整列滚动内容:先把这一行滚进视口再断言或点击。 */
    private fun scrollTo(text: String) {
        compose.awaitOne(text).performScrollTo()
        compose.waitForIdle()
    }

    @Test
    fun homeShowsGroupsStatusAndValues() {
        home()
        compose.onNodeWithText("一切就绪").assertIsDisplayed()
        compose.onNodeWithText("基本").assertIsDisplayed()
        // 首页一屏放不下(多了「语言」一行后更长):每个值先滚进视口再看。
        // 依次是模型、手机操作、通知、网站登录、操作路线、计算环境、关于
        for (v in listOf("DeepSeek", "可用", "正常", "3 个", "8 条", "已就绪", "0.1.0")) {
            scrollTo(v)
            compose.onNodeWithText(v).assertIsDisplayed()
        }
    }

    @Test
    fun homeRowsOpenTheirPages() {
        val opened = mutableListOf<String>()
        home(opened = opened)
        for (t in listOf("模型", "Token 用量", "手机操作", "干活时", "悬浮胶囊", "通知", "记忆与技能", "网站登录", "文件与计算环境", "记住的操作路线", "恢复与修复", "开发者选项", "关于 Mika")) {
            scrollTo(t)
            compose.onNodeWithText(t).performClick()
        }
        assertEquals(listOf("models", "usage", "phone", "work", "capsule", "notifications", "memory", "web", "sandbox", "routes", "recovery", "dev", "about"), opened)
    }

    /** 「Token 用量」写近 7 天的合计;一点没有时写「还没有」,还没统计出来时不显示这一行。 */
    @Test
    fun usageRowShowsWeekTotal() {
        var tokens by mutableStateOf<Long?>(123_000)
        compose.setContent {
            SettingsContent(
                status = ready, androidRelease = "14", mainModel = "DeepSeek", onBack = {}, onProblem = {}, onOpenModels = {},
                usageTokens = tokens,
            )
        }
        compose.onNodeWithText("近 7 天 12.3 万").assertIsDisplayed()
        tokens = 0
        compose.awaitOne("还没有").assertIsDisplayed()
        tokens = null
        compose.waitForIdle()
        compose.onAllNodesWithText("Token 用量").assertCountEquals(0)
    }

    /** 发布版不显示开发者选项。 */
    @Test
    fun developerRowHiddenInRelease() {
        home(showDeveloper = false)
        compose.onAllNodesWithText("开发者选项").assertCountEquals(0)
    }

    /** 有问题时状态卡变成提示,按钮把问题交给 onProblem。 */
    @Test
    fun statusCardTurnsIntoFixPrompt() {
        val clicked = mutableListOf<Problem>()
        home(
            SystemStatus(34, BuiltinShellStatus.WirelessDebuggingOff, AccessibilityState.On, notificationsAllowed = true),
            onProblem = { clicked += it },
        )
        compose.onNodeWithText("无线调试关着").assertIsDisplayed()
        compose.onAllNodesWithText("一切就绪").assertCountEquals(0)
        compose.onNodeWithText("需要处理").assertIsDisplayed()
        compose.awaitOne("去打开").performClick()
        assertEquals(listOf<Problem>(Problem.PhoneHelper(BuiltinShellStatus.WirelessDebuggingOff)), clicked)
    }

    @Test
    fun noModelAndOldSystem() {
        home(SystemStatus(32, BuiltinShellStatus.Running, AccessibilityState.EnabledNotRunning, notificationsAllowed = false), mainModel = null)
        compose.onNodeWithText("系统版本太低").assertIsDisplayed()
        compose.onNodeWithText("未设置").assertIsDisplayed()
        compose.onNodeWithText("不支持").assertIsDisplayed()
        // 安卓 13 以下手机操作整个不可用:不给恢复与修复
        compose.onAllNodesWithText("恢复与修复").assertCountEquals(0)
    }

    @Test
    fun phoneOpsPageShowsPermissionRowsAndPairing() {
        var repaired = 0
        compose.setContent {
            PhoneOpsScreen(
                SystemStatus(34, BuiltinShellStatus.WirelessDebuggingOff, AccessibilityState.On, notificationsAllowed = true),
                "14", onProblem = {}, onOpenNotifications = {}, onRepair = { repaired++ }, onUnpair = {}, onBack = {},
            )
        }
        compose.onNodeWithText("无障碍服务").assertIsDisplayed()
        compose.onNodeWithText("已开启").assertIsDisplayed()
        compose.onNodeWithText("无线调试已关").assertIsDisplayed()
        compose.onNodeWithText("正常").assertIsDisplayed()
        compose.onNodeWithText("安卓 14").assertIsDisplayed()
        compose.onNodeWithText("满足").assertIsDisplayed()
        compose.onNodeWithText(PhoneSetupCopy.USAGE_NOTE).assertExists()
        compose.onNodeWithText(PhoneSetupCopy.REPAIR).performClick()
        assertEquals(1, repaired)
    }

    /** 手机操作页的「通知」一行显示总体状态,点它进通知页。 */
    @Test
    fun phoneOpsNotificationRowOpensNotifications() {
        var opened = 0
        compose.setContent {
            PhoneOpsScreen(
                ready.copy(notificationsAllowed = false), "14", onProblem = {}, onOpenNotifications = { opened++ },
                onRepair = {}, onUnpair = {}, onBack = {},
            )
        }
        compose.onNodeWithText("已关闭").assertIsDisplayed()
        compose.onNodeWithText("通知").performClick()
        assertEquals(1, opened)
    }

    /** 通知页:总开关允许、一个频道被调低、进度被关时,各行显示状态,点各行交给 onFix。 */
    @Test
    fun notificationsPageShowsChannelsAndOpensThem() {
        val fixes = mutableListOf<Fix>()
        compose.setContent {
            NotificationsScreen(
                ready.copy(attentionImportance = 4, resultImportance = 2, progressImportance = 0), onFix = { fixes += it }, onBack = {},
            )
        }
        compose.onNodeWithText("已允许").assertIsDisplayed()
        compose.onNodeWithText("需要你接管、确认、回答时").assertIsDisplayed()
        compose.onNodeWithText("会弹出").assertIsDisplayed()
        compose.onNodeWithText("只进通知栏").assertIsDisplayed()
        compose.onNodeWithText("已关闭").assertIsDisplayed()
        compose.onNodeWithText(StatusCopy.NOTIF_FOOTER).assertExists()
        compose.onNodeWithText("需要你处理").performClick()
        compose.onNodeWithText("任务结果").performClick()
        compose.onNodeWithText("任务进度").performClick()
        assertEquals(listOf(Fix.OpenAttentionChannel, Fix.OpenResultChannel, Fix.OpenProgressChannel), fixes)
    }

    /** 总开关关着:给「去打开」,三个频道都显示已关闭。 */
    @Test
    fun notificationsPageWhenAppOff() {
        val fixes = mutableListOf<Fix>()
        compose.setContent { NotificationsScreen(ready.copy(notificationsAllowed = false), onFix = { fixes += it }, onBack = {}) }
        compose.onAllNodesWithText("已关闭").assertCountEquals(3)
        compose.onNodeWithText("去打开").performClick()
        assertEquals(listOf(Fix.AllowNotifications), fixes)
    }

    @Test
    fun unpairAsksFirst() {
        var unpaired = 0
        compose.setContent { PhoneOpsScreen(ready, "14", onProblem = {}, onOpenNotifications = {}, onRepair = {}, onUnpair = { unpaired++ }, onBack = {}) }
        compose.onNodeWithText(PhoneSetupCopy.UNPAIR).performClick()
        assertEquals(0, unpaired)
        compose.clickInDialog(PhoneSetupCopy.UNPAIR_CONFIRM)
        assertEquals(1, unpaired)
    }

    /** 并行开关打开、但设备不支持时:这一行灰掉,写明原因,不给加减。 */
    @Test
    fun parallelDisabledShowsReason() {
        compose.setContent {
            DuringWorkScreen(
                keepScreenOn = true, onKeepScreenOn = {}, muteTarget = true, onMuteTarget = {},
                parallel = ParallelSetting(2, ParallelCapability.detect(33, lowRam = false, userMax = 2, secondDisplayFailed = false)),
                onParallelMax = {}, onBack = {},
            )
        }
        compose.onNodeWithText("同时进行的任务").assertIsDisplayed()
        compose.onNodeWithText("安卓 14 以上才支持同时进行多个任务").assertIsDisplayed()
        compose.onNodeWithText("1 个").assertIsDisplayed()
        compose.onNodeWithText("+").assertDoesNotExist()
    }

    @Test
    fun parallelMaxAdjustable() {
        val picked = mutableListOf<Int>()
        var max by mutableStateOf(2)
        compose.setContent {
            DuringWorkScreen(
                keepScreenOn = true, onKeepScreenOn = {}, muteTarget = true, onMuteTarget = {},
                parallel = ParallelSetting(max, ParallelCapability.detect(34, lowRam = false, userMax = max, secondDisplayFailed = false)),
                onParallelMax = { max = it; picked += it }, onBack = {},
            )
        }
        compose.onNodeWithText("屏幕保持常亮").assertIsDisplayed()
        compose.onNodeWithText("2 个").assertIsDisplayed()
        compose.onNodeWithText("+").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("3 个").assertIsDisplayed()
        compose.onNodeWithText("−").performClick()
        assertEquals(listOf(3, 2), picked)
    }

    @Test
    fun duringWorkWithoutParallelHasNoParallelRow() {
        compose.setContent {
            DuringWorkScreen(true, {}, true, {}, parallel = null, onParallelMax = {}, onBack = {})
        }
        compose.onNodeWithText("被操作的 App 静音").assertIsDisplayed()
        compose.onAllNodesWithText("同时进行的任务").assertCountEquals(0)
    }

    /** 「干活时」两项默认开着,用户可以关:点了开关回调新值。 */
    @Test
    fun duringWorkTogglesCanBeTurnedOff() {
        val changes = mutableListOf<String>()
        compose.setContent {
            DuringWorkScreen(
                keepScreenOn = true, onKeepScreenOn = { changes += "screen:$it" },
                muteTarget = true, onMuteTarget = { changes += "mute:$it" },
                parallel = null, onParallelMax = {}, onBack = {},
            )
        }
        compose.onNodeWithTag("work-keep-screen-on").performClick()
        compose.onNodeWithTag("work-mute-target").performClick()
        assertEquals(listOf("screen:false", "mute:false"), changes)
    }

    /** 首页「悬浮胶囊」一行写着当前的选择。 */
    @Test
    fun capsuleRowShowsCurrentChoice() {
        compose.setContent {
            SettingsContent(status = ready, androidRelease = "14", mainModel = "m", onBack = {}, onProblem = {}, onOpenModels = {}, capsuleMode = CapsuleMode.Always)
        }
        compose.awaitOne("一直显示").assertIsDisplayed()
    }

    /** 悬浮胶囊子页三选一,选了回调新值;页脚说明用的是无障碍服务。 */
    @Test
    fun capsulePageOffersThreeChoices() {
        var mode by mutableStateOf(CapsuleMode.DuringTasks)
        val picked = mutableListOf<CapsuleMode>()
        compose.setContent { CapsuleScreen(mode, onMode = { picked += it; mode = it }, onBack = {}) }
        compose.awaitOne("有任务时显示").assertIsDisplayed()
        compose.onNodeWithText("关闭").performScrollTo().assertIsDisplayed()
        // 脚注在三个选项下面,矮屏或大字号时要滚一下才看得到
        compose.onNodeWithText("无障碍服务", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("一直显示").performScrollTo().performClick()
        compose.onNodeWithText("关闭").performScrollTo().performClick()
        assertEquals(listOf(CapsuleMode.Always, CapsuleMode.Off), picked)
    }

    /** 「全部清除」先问一句;确认后清空,条数变成 0、按钮消失;取消则不动。 */
    @Test
    fun clearRoutesAsksThenEmpties() {
        var routes by mutableStateOf(3)
        var cleared = 0
        compose.setContent { RoutesScreen(routes, onClear = { cleared++; routes = 0 }, onBack = {}) }
        compose.onNodeWithText("已记住的操作路线").assertIsDisplayed()
        compose.onNodeWithText("3 条", substring = true).assertIsDisplayed()
        compose.onNodeWithText("全部清除").performClick()
        compose.clickInDialog("取消")
        assertEquals(0, cleared)
        compose.onNodeWithText("全部清除").performClick()
        compose.awaitOne("清除全部已记住的操作路线?", inDialog = true).assertIsDisplayed()
        compose.clickInDialog("清除")
        assertEquals(1, cleared)
        compose.onNodeWithText("0 条", substring = true).assertIsDisplayed()
        compose.onNodeWithText("全部清除").assertDoesNotExist()
    }

    /** 开发者选项:八个开关分速度、实验两组,点一下打开对应那一项。 */
    @Test
    fun developerOptionsHasTwoGroupsAndToggles() {
        val changed = mutableListOf<SpeedFlags>()
        var flags by mutableStateOf(SpeedFlags.OFF)
        compose.setContent { DeveloperOptionsScreen(flags, onSpeed = { flags = it; changed += it }, onBack = {}) }
        compose.onNodeWithText("速度").assertIsDisplayed()
        for (t in listOf("先读元素，必要时看图", "操作后带回屏幕变化", "有把握时连做几步", "直接打开要去的页面", "简单步骤交给轻量模型")) {
            scrollTo(t)
            compose.onNodeWithText(t).assertIsDisplayed()
        }
        scrollTo("实验")
        compose.onNodeWithText("实验").assertIsDisplayed()
        scrollTo("照着上次的做法做")
        compose.onNodeWithText("照着上次的做法做").performClick()
        assertEquals(listOf(SpeedFlags(replay = true)), changed)
        scrollTo("不同对话同时进行")
        scrollTo("长任务的摘要分条写")
        compose.onNodeWithText("长任务的摘要分条写").assertIsDisplayed()
    }

    /** 「自主模式」在开发者选项最前面,默认关;点开关或整行都切换。 */
    @Test
    fun autonomousSwitchComesFirstAndToggles() {
        val toggled = mutableListOf<Boolean>()
        var on by mutableStateOf(false)
        compose.setContent { DeveloperOptionsScreen(SpeedFlags.OFF, onSpeed = {}, onBack = {}, autonomous = on, onAutonomous = { toggled += it; on = it }) }
        compose.onNodeWithText(AutonomyCopy.GROUP).assertIsDisplayed()
        compose.onNodeWithText(AutonomyCopy.TITLE).assertIsDisplayed()
        compose.onNodeWithTag("dev-autonomous").assertIsOff().performClick()
        compose.onNodeWithTag("dev-autonomous").assertIsOn()
        compose.onNodeWithText(AutonomyCopy.TITLE).performClick()
        compose.onNodeWithTag("dev-autonomous").assertIsOff()
        assertEquals(listOf(true, false), toggled)
    }

    /** 「看图定位已验证」挪到开发者选项:有看图模型才出现,点了切换。 */
    @Test
    fun groundingSwitchShowsOnlyWithVisionModel() {
        val toggled = mutableListOf<Boolean>()
        var grounding by mutableStateOf<GroundingSetting?>(null)
        compose.setContent {
            DeveloperOptionsScreen(SpeedFlags.OFF, onSpeed = {}, onBack = {}, grounding = grounding, onGrounding = { toggled += it })
        }
        compose.onNodeWithText(GroundingCopy.TITLE).assertDoesNotExist()
        grounding = GroundingSetting("qwen-vl-max", verified = false)
        scrollTo(GroundingCopy.TITLE)
        compose.onNodeWithText(GroundingCopy.TITLE).performClick()
        assertEquals(listOf(true), toggled)
    }

    @Test
    fun developerGroupsCoverAllEightSwitchesOnce() {
        val speed = DeveloperSwitches.speed(SpeedFlags.OFF)
        val experimental = DeveloperSwitches.experimental(SpeedFlags.OFF)
        assertEquals(5, speed.size)
        assertEquals(3, experimental.size)
        assertEquals(8, (speed + experimental).map { it.id }.toSet().size)
    }

    private class Calls { var reset = 0; val network = mutableListOf<Boolean>() }

    private fun advanced(status: SandboxStatus, network: Boolean = false, isolationIssue: Boolean = false): Calls {
        val c = Calls()
        compose.setContent {
            Column {
                AdvancedSection(
                    status = status, networkAllowed = network, isolationIssue = isolationIssue,
                    onNetworkChange = { c.network += it }, onReset = { c.reset++ },
                )
            }
        }
        return c
    }

    @Test
    fun unsupportedHidesAdvanced() {
        advanced(SandboxStatus.Unsupported("此设备不支持(需要 arm64)"))
        compose.onAllNodesWithText(SandboxCopy.NETWORK_TITLE).assertCountEquals(0)
        compose.onAllNodesWithText("重置运行环境").assertCountEquals(0)
    }

    @Test
    fun advancedHasNoInstallOrSandboxWording() {
        advanced(SandboxStatus.NotInstalled)
        compose.onNodeWithText(SandboxCopy.NETWORK_TITLE).assertIsDisplayed()
        compose.onNodeWithText("重置运行环境").assertIsDisplayed()
        for (t in listOf("沙箱", "安装沙箱", "软件源", "清空工作区")) compose.onAllNodesWithText(t, substring = true).assertCountEquals(0)
    }

    @Test
    fun resetAsksFirst() {
        val c = advanced(SandboxStatus.Ready(network = false))
        compose.onNodeWithText("重置运行环境").performClick()
        assertEquals(0, c.reset)
        compose.onNodeWithText(SandboxCopy.RESET_BODY).assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        assertEquals(0, c.reset)
        compose.onNodeWithText("重置运行环境").performClick()
        compose.onNodeWithText("重置").performClick()
        assertEquals(1, c.reset)
    }

    @Test
    fun isolationIssueShown() {
        advanced(SandboxStatus.Ready(network = true), network = true, isolationIssue = true)
        compose.onNodeWithText(SandboxCopy.NETWORK_EXPLAIN).assertIsDisplayed()
        compose.onNodeWithText(SandboxCopy.ISOLATION_UNAVAILABLE).assertIsDisplayed()
    }

    @Test
    fun networkToggleReportsChange() {
        val c = advanced(SandboxStatus.Ready(network = false))
        compose.onNodeWithTag("sandbox-network").performClick()
        assertTrue(c.network == listOf(true))
    }
}
