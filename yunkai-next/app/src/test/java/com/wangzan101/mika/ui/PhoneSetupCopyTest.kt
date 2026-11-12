// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.ui

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.shell.BuiltinShellFailure
import com.wangzan101.mika.core.shell.BuiltinShellFix
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.core.shell.SetupStep
import com.wangzan101.mika.core.shell.SetupSuspect
import com.wangzan101.mika.settings.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import com.wangzan101.mika.ui.components.ValueTone
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSetupCopyTest {
    @get:Rule val texts = ChineseTexts()

    /** 给用户看的字里不出现这些技术说法(系统设置里本来就有的选项名除外,见下)。 */
    private val jargon = listOf("shell", "端口", "mDNS", "TLS", "Binder", "uid", "进程")

    private fun allText(): List<String> = buildList {
        add(PhoneSetupCopy.INTRO)
        SetupStep.entries.forEach { val c = PhoneSetupCopy.step(it); add(c.title); add(c.body); c.action?.let(::add); add(c.done); add(c.brief) }
        BuiltinShellFailure.entries.forEach { val f = PhoneSetupCopy.failure(it); add(f.title); add(f.body) }
        BuiltinShellFix.entries.forEach { add(PhoneSetupCopy.fixLabel(it)) }
        statuses.forEach { add(PhoneSetupCopy.statusLine(it)); add(PhoneSetupCopy.statusLine(it, usbDebugging = false)); add(PhoneSetupCopy.statusValue(it).first) }
        addAll(
            listOf(
                PhoneSetupCopy.SUCCESS_LINE, PhoneSetupCopy.SUCCESS_NOTE, PhoneSetupCopy.USAGE_NOTE, PhoneSetupCopy.UNPAIR_BODY, PhoneSetupCopy.SKIP_NOTE, PhoneSetupCopy.DEGRADED_LINE, PhoneSetupCopy.PORT_FOUND,
                PhoneSetupCopy.NEED_NOTIFICATIONS, PhoneSetupCopy.MANUAL_HINT, PhoneSetupCopy.NOTIFY_ENTER_TEXT,
                PhoneSetupCopy.NOTIFY_ENTER_TITLE, PhoneSetupCopy.NOTIFY_ACTION, PhoneSetupCopy.NOTIFY_INPUT_LABEL,
                PhoneSetupCopy.AUTO_READ_HINT, PhoneSetupCopy.ACCESSIBILITY_SKIP, PhoneSetupCopy.NOTIFY_WINDOW_FOUND,
                PhoneSetupCopy.NOTIFY_CODE_INVALID, PhoneSetupCopy.PAIRING_CHANNEL_OFF, PhoneSetupCopy.OPEN_PAIRING_CHANNEL,
                PhoneSetupCopy.PAIRING_LOWERED, PhoneSetupCopy.NOTIFY_DONE_TITLE, PhoneSetupCopy.NOTIFY_DONE_TEXT,
                PhoneSetupCopy.ACCESSIBILITY_SKIPPED, PhoneSetupCopy.APP_INFO, PhoneSetupCopy.TURNED_ON_NEXT,
                PhoneSetupCopy.REVISIT_BACK, PhoneSetupCopy.REVISIT_NOTE, PhoneSetupCopy.CONNECT_PORT_SUBMIT,
            ),
        )
        suspects.forEach { add(PhoneSetupCopy.suspect(it)); add(PhoneSetupCopy.goBackTo(it.step)) }
        addAll(PhoneSetupCopy.NOTIFY_GUIDE)
        BuiltinShellFailure.entries.forEach { add(PhoneSetupCopy.notifyRetry(it)) }
    }

    private val suspects = listOf(
        SetupSuspect(SetupStep.DeveloperOptions, unreadable = false),
        SetupSuspect(SetupStep.WirelessDebugging, unreadable = false),
        SetupSuspect(SetupStep.WirelessDebugging, unreadable = true),
    )

    private val statuses = listOf(
        BuiltinShellStatus.NotPaired, BuiltinShellStatus.WirelessDebuggingOff, BuiltinShellStatus.Connecting, BuiltinShellStatus.Running,
    ) + BuiltinShellFailure.entries.map { BuiltinShellStatus.Failed(it) }

    @Test
    fun copyIsPlainChinese() {
        // 连不上时要用户照着「无线调试」页面填端口:只有这一句直说「端口」
        val portAllowed = PhoneSetupCopy.failure(BuiltinShellFailure.ConnectFailed).body
        allText().forEach { t ->
            assertTrue("empty copy", t.isNotBlank())
            jargon.forEach { j -> if (!(j == "端口" && t == portAllowed)) assertFalse("「$j」 in: $t", t.contains(j, ignoreCase = true)) }
            // 只有系统里那个选项名本身带 adb
            if (t.contains("adb")) assertTrue(t, t.contains("「停用 adb 授权超时功能」"))
        }
    }

    /** 自动找不到端口时说清楚能做什么,并照着「无线调试」页面填端口。 */
    @Test
    fun connectFailureSaysWhatToTryAndHowToFindThePort() {
        val body = PhoneSetupCopy.failure(BuiltinShellFailure.ConnectFailed).body
        assertEquals("找不到无线调试的端口。可以：关掉再打开「无线调试」后重试；换用手机热点或家里的 Wi-Fi；或在下面手动填端口。", body)
        assertEquals("填无线调试的端口", PhoneSetupCopy.CONNECT_PORT_LABEL)
        assertTrue(PhoneSetupCopy.CONNECT_PORT_HINT.contains("「IP 地址和端口」冒号后面的数字"))
        assertEquals("连接", PhoneSetupCopy.CONNECT_PORT_SUBMIT)
        assertEquals("正在本机查找无线调试端口…", PhoneSetupCopy.SCANNING_PORTS)
    }

    @Test
    fun connectPortInputAcceptsOnlyValidPorts() {
        assertEquals(41235, ConnectPortInput.parse("41235"))
        assertEquals(41235, ConnectPortInput.parse(" 41 235 "))
        assertEquals(41235, ConnectPortInput.parse("４１２３５"))
        // 整段粘了「IP 地址和端口」也行
        assertEquals(41235, ConnectPortInput.parse("192.0.2.10:41235"))
        assertEquals(41235, ConnectPortInput.parse("192.0.2.10：41235"))
        assertEquals(1, ConnectPortInput.parse("1"))
        assertEquals(65535, ConnectPortInput.parse("65535"))
        for (bad in listOf("", " ", "0", "65536", "70000", "abc", "41a35", "-1", "192.0.2.10:")) assertNull(bad, ConnectPortInput.parse(bad))
    }

    @Test
    fun eachStepPointsToTheRightPlace() {
        assertEquals("打开关于手机", PhoneSetupCopy.step(SetupStep.DeveloperOptions).action)
        assertTrue(PhoneSetupCopy.step(SetupStep.DeveloperOptions).body.contains("版本号"))
        assertTrue(PhoneSetupCopy.step(SetupStep.WirelessDebugging).body.contains("无线调试"))
        assertTrue(PhoneSetupCopy.step(SetupStep.PairingCode).body.contains("使用配对码配对设备"))
        // 配对码怎么交给 Mika:无障碍开着时自己读,没开时按三步在通知里填
        assertEquals(
            listOf("点「使用配对码配对设备」", "屏幕顶部弹出 云开 的通知；没看到就下拉通知栏", "点「填配对码」，输入 6 位数字"),
            PhoneSetupCopy.NOTIFY_GUIDE,
        )
        assertTrue(PhoneSetupCopy.NOTIFY_GUIDE[2].contains("「${PhoneSetupCopy.NOTIFY_ACTION}」"))
        assertTrue(PhoneSetupCopy.AUTO_READ_HINT.contains("自己读"))
        assertNull(PhoneSetupCopy.step(SetupStep.Connect).action)
        // USB 调试一步讲清好处:不插线、断开 Wi-Fi 也能用
        val usb = PhoneSetupCopy.step(SetupStep.UsbDebugging)
        assertTrue(usb.body.contains("「USB 调试」"))
        assertTrue(usb.body.contains("Wi-Fi"))
        assertTrue(usb.body.contains("数据线"))
    }

    @Test
    fun accessibilityStepExplainsTheBenefitAndTheWayOut() {
        val a11y = PhoneSetupCopy.step(SetupStep.Accessibility)
        assertTrue(a11y.body.contains("不用手动填"))
        assertTrue(a11y.body.contains("「无障碍」"))
        assertTrue(PhoneSetupCopy.APP_INFO.contains("受限制的设置"))
    }

    @Test
    fun skippingAccessibilityReadsAsTheRecommendedPath() {
        assertEquals("跳过(配对后 云开 会自己打开)", PhoneSetupCopy.ACCESSIBILITY_SKIP)
        assertTrue(PhoneSetupCopy.step(SetupStep.Accessibility).body.contains("推荐"))
        assertEquals("我已经打开了,下一步", PhoneSetupCopy.TURNED_ON_NEXT)
    }

    @Test
    fun suspectsNameTheStepAndHowItWasRead() {
        assertTrue(PhoneSetupCopy.suspect(suspects[0]).contains("开发者选项"))
        assertTrue(PhoneSetupCopy.suspect(suspects[1]).contains("好像没开"))
        assertTrue(PhoneSetupCopy.suspect(suspects[2]).contains("读不到"))
        assertEquals("回到「打开无线调试」", PhoneSetupCopy.goBackTo(SetupStep.WirelessDebugging))
    }

    @Test
    fun alwaysAllowCheckboxIsNamedTheSameEverywhere() {
        val box = "「始终允许使用此网络」"
        listOf(
            PhoneSetupCopy.step(SetupStep.WirelessDebugging).body, PhoneSetupCopy.failure(BuiltinShellFailure.WifiNotAllowed).body,
            PhoneSetupCopy.WIFI_NOT_ALLOWED_LINE,
        ).forEach { assertTrue(it, it.contains(box)) }
    }

    @Test
    fun notificationReadsAsAnInstruction() {
        assertEquals("输入配对码", PhoneSetupCopy.NOTIFY_ENTER_TITLE)
        assertEquals("把屏幕上的 6 位数字填在这里，不用离开配对窗口", PhoneSetupCopy.NOTIFY_ENTER_TEXT)
        assertEquals("填配对码", PhoneSetupCopy.NOTIFY_ACTION)
        assertEquals("6 位数字", PhoneSetupCopy.NOTIFY_INPUT_LABEL)
        assertEquals("正在配对…", PhoneSetupCopy.NOTIFY_WORKING)
        assertEquals("配对成功", PhoneSetupCopy.NOTIFY_DONE_TITLE)
    }

    @Test
    fun notificationErrorsSayHowToGetANewCode() {
        assertEquals(
            "配对码不对或已失效：在配对窗口重新点「使用配对码配对设备」拿新的码，再填一次",
            PhoneSetupCopy.notifyRetry(BuiltinShellFailure.WrongCode),
        )
        assertTrue(PhoneSetupCopy.notifyRetry(BuiltinShellFailure.PairingWindowClosed).contains("「使用配对码配对设备」拿新的码，再填一次"))
        assertEquals("要填 6 位数字，再填一次", PhoneSetupCopy.NOTIFY_CODE_INVALID)
        // 配对码要在通知里填:通知没开时一句话说清为什么
        assertTrue(PhoneSetupCopy.NEED_NOTIFICATIONS.startsWith("配对码要在通知里填"))
        assertTrue(PhoneSetupCopy.PAIRING_CHANNEL_OFF.contains("「${PhoneSetupCopy.CHANNEL_NAME}」"))
        assertTrue(PhoneSetupCopy.PAIRING_LOWERED.contains("「在屏幕上弹出」"))
    }

    @Test
    fun runningWithoutUsbDebuggingGetsAGentleHint() {
        assertEquals("已就绪" to ValueTone.Ok, PhoneSetupCopy.statusValue(BuiltinShellStatus.Running))
        assertEquals("已连接,能操作手机 App", PhoneSetupCopy.statusLine(BuiltinShellStatus.Running))
        assertTrue(PhoneSetupCopy.statusLine(BuiltinShellStatus.Running, usbDebugging = false).contains("Wi-Fi 断开后会停止"))
    }

    @Test
    fun noWifiSaysItRecoversByItself() {
        val noWifi = BuiltinShellStatus.Failed(BuiltinShellFailure.NoWifi)
        assertTrue(PhoneSetupCopy.failure(BuiltinShellFailure.NoWifi).body.contains("自动恢复"))
        assertTrue(PhoneSetupCopy.statusLine(noWifi).contains("自动恢复"))
        assertTrue(StatusCopy.banner(Problem.PhoneHelper(noWifi)).detail.contains("自动恢复"))
    }

    @Test
    fun failuresAreDistinctAndActionable() {
        val titles = BuiltinShellFailure.entries.map { PhoneSetupCopy.failure(it).title }
        assertEquals(titles.size, titles.toSet().size)
        assertEquals("重新配对", PhoneSetupCopy.fixLabel(BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode).fix))
        assertEquals("重试", PhoneSetupCopy.fixLabel(BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed).fix))
    }

    @Test
    fun statusValuesForSettings() {
        assertEquals("已就绪" to ValueTone.Ok, PhoneSetupCopy.statusValue(BuiltinShellStatus.Running))
        assertEquals("未设置" to ValueTone.Bad, PhoneSetupCopy.statusValue(BuiltinShellStatus.NotPaired))
        assertEquals("配对码不对或已过期", PhoneSetupCopy.statusLine(BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode)))
    }

    @Test
    fun reconnectOnlyWhenRetryingIsTheFix() {
        val reconnectable = statuses.filter { PhoneSetupCopy.canReconnect(it) }
        assertEquals(
            listOf(BuiltinShellFailure.ConnectFailed, BuiltinShellFailure.HelperStartFailed, BuiltinShellFailure.Unknown),
            reconnectable.map { (it as BuiltinShellStatus.Failed).reason },
        )
        reconnectable.forEach { s ->
            assertEquals("重新连接", StatusCopy.banner(Problem.PhoneHelper(s)).link)
            assertEquals(Fix.RetryPhoneHelper, StatusCopy.directFix(Problem.PhoneHelper(s)))
        }
        // 没配对要走引导;无线调试关着要先去打开;没 Wi-Fi 会自己恢复;配对失效要重新配对
        listOf(
            BuiltinShellStatus.NotPaired, BuiltinShellStatus.WirelessDebuggingOff, BuiltinShellStatus.Connecting,
            BuiltinShellStatus.Running, BuiltinShellStatus.Failed(BuiltinShellFailure.NoWifi),
            BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked),
        ).forEach { assertFalse(PhoneSetupCopy.canReconnect(it)) }
    }

    @Test
    fun bannerAndFixForPhoneHelperProblem() {
        statuses.filter { it != BuiltinShellStatus.Running }.forEach { s ->
            val p = Problem.PhoneHelper(s)
            val fix = when {
                PhoneSetupCopy.canReconnect(s) -> Fix.RetryPhoneHelper
                // 这个 Wi-Fi 还没允许无线调试:直接去亲手打开一次
                s == BuiltinShellStatus.Failed(BuiltinShellFailure.WifiNotAllowed) -> Fix.OpenWirelessDebugging
                else -> Fix.OpenPhoneSetup
            }
            assertEquals(fix, StatusCopy.directFix(p))
            val b = StatusCopy.banner(p)
            jargon.forEach { j -> assertFalse(b.title.contains(j) || b.detail.contains(j)) }
            assertEquals(Fix.OpenPhoneSetup, StatusCopy.explain(p, "14").fix)
        }
        assertEquals("手机操作还没设置", StatusCopy.banner(Problem.PhoneHelper(BuiltinShellStatus.NotPaired)).title)
    }
}
