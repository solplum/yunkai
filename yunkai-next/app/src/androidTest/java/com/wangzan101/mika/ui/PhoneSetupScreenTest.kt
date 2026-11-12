package com.wangzan101.mika.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.core.shell.BuiltinShellFailure
import com.wangzan101.mika.core.shell.BuiltinShellFix
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.core.shell.PairingChannelHealth
import com.wangzan101.mika.core.shell.PairingDiscovery
import com.wangzan101.mika.core.shell.PhoneSetupFlow
import com.wangzan101.mika.core.shell.SetupInputs
import com.wangzan101.mika.core.shell.SetupStep
import com.wangzan101.mika.core.shell.SetupSuspect
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 引导页和「高级」里的手机操作几行(只用假数据,不碰系统设置)。 */
@RunWith(AndroidJUnit4::class)
class PhoneSetupScreenTest {
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<String>()

    private fun show(inputs: SetupInputs, headsUpLowered: Boolean = false, diagnostics: Boolean = false, scanning: Boolean = false) = compose.setContent {
        PhoneSetupContent(
            view = PhoneSetupFlow.view(inputs),
            onStepAction = { calls += "step $it" },
            onFailureFix = { calls += "fix $it" },
            onAllowNotifications = { calls += "notify" },
            onConfirmStep = { calls += "confirm $it" },
            onSubmit = { c, p -> calls += "submit $c $p" },
            onClose = { calls += "close" },
            headsUpLowered = headsUpLowered,
            onOpenHeadsUp = { calls += "headsUp" },
            onAppInfo = { calls += "appInfo" },
            onSkipAccessibility = { calls += "skipA11y" },
            onRevisit = { calls += "revisit $it" },
            onOpenPairingChannel = { calls += "pairingChannel" },
            onDiagnostics = if (diagnostics) ({ calls += "diagnostics" }) else null,
            scanning = scanning,
            onConnectPort = { calls += "port $it" },
        )
    }

    /** 连不上:「稍后再说」还在,能照着「无线调试」页面填端口;填错当场提示,不去连。 */
    @Test
    fun failedConnectOffersLaterAndManualPort() {
        show(SetupInputs(true, true, BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed)))
        compose.onNodeWithTag("setup-later").assertIsDisplayed()
        compose.onNodeWithTag("setup-connect-port").performScrollTo().performTextInput("70000")
        compose.onNodeWithTag("setup-connect-port-submit").performScrollTo().performClick()
        compose.onNodeWithText(PhoneSetupCopy.PORT_INVALID).assertExists()
        compose.onNodeWithTag("setup-connect-port").performTextClearance()
        compose.onNodeWithTag("setup-connect-port").performTextInput("41235")
        compose.onNodeWithTag("setup-connect-port-submit").performScrollTo().performClick()
        compose.onNodeWithTag("setup-later").performClick()
        assertEquals(listOf("port 41235", "close"), calls)
    }

    /** 正在连接:也能「稍后再说」;在本机找端口时照实说。 */
    @Test
    fun workingOffersLaterAndShowsPortSearch() {
        show(SetupInputs(true, true, BuiltinShellStatus.Connecting), scanning = true)
        compose.onNodeWithText(PhoneSetupCopy.SCANNING_PORTS).assertIsDisplayed()
        compose.onNodeWithTag("setup-connect-port").assertDoesNotExist()
        compose.onNodeWithTag("setup-later").assertIsDisplayed().performClick()
        assertEquals(listOf("close"), calls)
    }

    /** 失败在配对一步(码不对)时不给填连接端口,「稍后再说」照样在。 */
    @Test
    fun pairingFailureHasLaterButNoConnectPort() {
        show(SetupInputs(true, true, BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode)))
        compose.onNodeWithTag("setup-later").assertIsDisplayed()
        compose.onNodeWithTag("setup-connect-port").assertDoesNotExist()
    }

    /** 走到配对一步、无障碍跳过了:配对码只能填在通知里。 */
    private val pairingWithoutA11y = SetupInputs(true, true, BuiltinShellStatus.NotPaired, accessibility = false, accessibilitySkipped = true)

    /** 「复制诊断信息」底部一直在;失败时出错的那一步里也有一个。 */
    @Test
    fun diagnosticsButtonAtBottomAndInFailedStep() {
        show(SetupInputs(true, true, BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed)), diagnostics = true)
        compose.onNodeWithTag("setup-diagnostics-failed").performScrollTo().performClick()
        compose.onNodeWithTag("setup-diagnostics").assertIsDisplayed().performClick()
        assertEquals(listOf("diagnostics", "diagnostics"), calls)
    }

    @Test
    fun diagnosticsButtonInGuidePhase() {
        show(SetupInputs(false, false, BuiltinShellStatus.NotPaired), diagnostics = true)
        compose.onNodeWithTag("setup-diagnostics").assertIsDisplayed()
        compose.onNodeWithTag("setup-diagnostics-failed").assertDoesNotExist()
    }

    @Test
    fun firstStepOpensAboutPhone() {
        show(SetupInputs(false, false, BuiltinShellStatus.NotPaired))
        compose.onNodeWithText(PhoneSetupCopy.step(SetupStep.DeveloperOptions).title).assertIsDisplayed()
        compose.onNodeWithTag("setup-action").performClick()
        compose.onNodeWithTag("setup-later").performClick()
        assertEquals(listOf("step DeveloperOptions", "close"), calls)
    }

    @Test
    fun accessibilityStepOpensSettingsOffersAppInfoAndCanBeSkipped() {
        show(SetupInputs(true, true, BuiltinShellStatus.NotPaired, accessibility = false, restrictedSettings = true))
        compose.onNodeWithText(PhoneSetupCopy.step(SetupStep.Accessibility).title).assertIsDisplayed()
        compose.onNodeWithText(PhoneSetupCopy.ACCESSIBILITY_SKIP).assertIsDisplayed()
        compose.onNodeWithTag("setup-action").performClick()
        compose.onNodeWithTag("setup-app-info").performClick()
        compose.onNodeWithTag("setup-skip-a11y").performClick()
        assertEquals(listOf("step Accessibility", "appInfo", "skipA11y"), calls)
        // 无障碍靠跳过往下走,不另给「已打开」
        compose.onNodeWithTag("setup-confirm").assertDoesNotExist()
    }

    @Test
    fun everySwitchStepHasAManualNext() {
        // 什么都读成关着:第一步就能点「我已经打开了,下一步」
        show(SetupInputs(false, false, BuiltinShellStatus.NotPaired, usbDebugging = false, accessibility = false))
        compose.onNodeWithText(PhoneSetupCopy.TURNED_ON_NEXT).assertIsDisplayed()
        compose.onNodeWithTag("setup-confirm").performClick()
        assertEquals(listOf("confirm DeveloperOptions"), calls)
    }

    @Test
    fun wirelessStepHasAManualNext() {
        show(SetupInputs(true, false, BuiltinShellStatus.NotPaired))
        compose.onNodeWithTag("setup-confirm").performScrollTo().performClick()
        assertEquals(listOf("confirm WirelessDebugging"), calls)
    }

    @Test
    fun doneStepsCanBeTappedToRedo() {
        show(SetupInputs(true, true, BuiltinShellStatus.NotPaired))
        compose.onNodeWithTag("setup-step-UsbDebugging").performClick()
        // 后面没做的不能点
        compose.onNodeWithTag("setup-step-Connect").performClick()
        compose.onNodeWithText(PhoneSetupCopy.REVISIT_NOTE).performScrollTo().assertIsDisplayed()
        assertEquals(listOf("revisit UsbDebugging"), calls)
    }

    @Test
    fun revisitedStepOffersNextAndBack() {
        show(SetupInputs(true, true, BuiltinShellStatus.NotPaired, revisit = SetupStep.UsbDebugging))
        compose.onNodeWithText(PhoneSetupCopy.step(SetupStep.UsbDebugging).body).assertIsDisplayed()
        compose.onNodeWithTag("setup-confirm").performScrollTo().performClick()
        compose.onNodeWithTag("setup-revisit-back").performScrollTo().performClick()
        assertEquals(listOf("confirm UsbDebugging", "revisit null"), calls)
    }

    @Test
    fun connectionFailureSaysWhichStepToCheckAndGoesBack() {
        show(SetupInputs(true, null, BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed)))
        compose.onNodeWithText(PhoneSetupCopy.suspect(SetupSuspect(SetupStep.WirelessDebugging, unreadable = true))).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("setup-suspect-back").performScrollTo().performClick()
        assertEquals(listOf("revisit WirelessDebugging"), calls)
    }

    @Test
    fun pairingWithoutAccessibilityShowsTheThreeStepGuide() {
        show(pairingWithoutA11y)
        compose.onNodeWithTag("setup-pairing-guide").performScrollTo().assertIsDisplayed()
        PhoneSetupCopy.NOTIFY_GUIDE.forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithTag("setup-pairing-hint").assertDoesNotExist()
        // 通知好好的:主按钮还是去无线调试,也没有修通知的按钮
        compose.onNodeWithTag("setup-notify-fix").assertDoesNotExist()
        compose.onNodeWithTag("setup-action").performScrollTo().performClick()
        assertEquals(listOf("step PairingCode"), calls)
    }

    @Test
    fun pairingWithAccessibilityShowsTheAutoReadHint() {
        show(SetupInputs(true, true, BuiltinShellStatus.NotPaired))
        compose.onNodeWithText(PhoneSetupCopy.AUTO_READ_HINT).assertIsDisplayed()
        compose.onNodeWithTag("setup-pairing-guide").assertDoesNotExist()
    }

    @Test
    fun pairingAsksToAllowNotificationsFirstWhenTheyAreOff() {
        show(pairingWithoutA11y.copy(notificationsAllowed = false))
        compose.onNodeWithText(PhoneSetupCopy.NEED_NOTIFICATIONS).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("setup-notify-fix").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf("notify"), calls)
    }

    @Test
    fun pairingOpensTheChannelSettingsWhenPairingNotificationsAreOff() {
        show(pairingWithoutA11y.copy(pairingChannel = PairingChannelHealth.Off))
        compose.onNodeWithText(PhoneSetupCopy.PAIRING_CHANNEL_OFF).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(PhoneSetupCopy.OPEN_PAIRING_CHANNEL).performScrollTo().performClick()
        assertEquals(listOf("pairingChannel"), calls)
    }

    @Test
    fun pairingWarnsWhenPairingNotificationsDoNotPopUp() {
        show(pairingWithoutA11y.copy(pairingChannel = PairingChannelHealth.Lowered))
        compose.onNodeWithTag("setup-pairing-lowered").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("setup-notify-fix").assertDoesNotExist()
        compose.onNodeWithTag("setup-pairing-lowered-open").performScrollTo().performClick()
        assertEquals(listOf("pairingChannel"), calls)
    }

    @Test
    fun pairingStepWarnsWhenHeadsUpIsLowered() {
        show(SetupInputs(true, true, BuiltinShellStatus.NotPaired, PairingDiscovery.Searching, notificationsAllowed = true), headsUpLowered = true)
        compose.onNodeWithTag("setup-heads-up").assertIsDisplayed()
        compose.onNodeWithText(PhoneSetupCopy.HEADS_UP_LOWERED).assertIsDisplayed()
        compose.onNodeWithTag("setup-heads-up-open").performClick()
        assertEquals(listOf("headsUp"), calls)
    }

    @Test
    fun pairingStepTakesAManualCode() {
        show(SetupInputs(true, true, BuiltinShellStatus.NotPaired, PairingDiscovery.Searching))
        compose.onNodeWithTag("setup-manual").performClick()
        compose.onNodeWithTag("setup-code").performTextInput("123 456")
        compose.onNodeWithTag("setup-port").performTextInput("37001")
        // 真机上键盘弹出来会把按钮挤到可见区下面,先滚过去再点
        compose.onNodeWithTag("setup-submit").performScrollTo().performClick()
        assertEquals(listOf("submit 123456 37001"), calls)
    }

    @Test
    fun failureShowsReasonAndOneTapFix() {
        show(SetupInputs(true, true, BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed)))
        compose.onNodeWithText(PhoneSetupCopy.failure(BuiltinShellFailure.HelperStartFailed).title).assertIsDisplayed()
        compose.onNodeWithTag("setup-fix").performClick()
        assertEquals(listOf("fix ${BuiltinShellFix.Retry}"), calls)
    }

    @Test
    fun successKeepsChecklistAndWaitsForTheDoneButton() {
        show(SetupInputs(true, true, BuiltinShellStatus.Running))
        compose.onNodeWithText(PhoneSetupCopy.SUCCESS_LINE).assertIsDisplayed()
        compose.onNodeWithText(PhoneSetupCopy.step(SetupStep.DeveloperOptions).title).assertIsDisplayed()
        compose.onNodeWithText(PhoneSetupCopy.SUCCESS_NOTE).assertIsDisplayed()
        compose.onNodeWithText(PhoneSetupCopy.SUCCESS_DONE).assertIsDisplayed()
        assertEquals(emptyList<String>(), calls)
        compose.onNodeWithTag("setup-done").performClick()
        assertEquals(listOf("close"), calls)
    }

    @Test
    fun unpairNeedsConfirmation() {
        compose.setContent { PhoneHelperSection(BuiltinShellStatus.Running, onRepair = { calls += "repair" }, onUnpair = { calls += "unpair" }) }
        compose.onNodeWithText(PhoneSetupCopy.REPAIR).performClick()
        compose.onNodeWithText(PhoneSetupCopy.UNPAIR).performClick()
        compose.onNodeWithText(PhoneSetupCopy.UNPAIR_TITLE).assertIsDisplayed()
        compose.onNodeWithText(PhoneSetupCopy.UNPAIR_CONFIRM).performClick()
        assertEquals(listOf("repair", "unpair"), calls)
    }
}
