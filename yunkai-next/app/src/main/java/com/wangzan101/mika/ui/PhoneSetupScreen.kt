package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.ui.components.keepsKeyboard
import com.wangzan101.mika.about.Diagnostics
import com.wangzan101.mika.about.DiagnosticsCollector
import com.wangzan101.mika.about.setupInputs
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.core.shell.BuiltinShellFailure
import com.wangzan101.mika.core.shell.BuiltinShellFix
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.core.shell.PairingCode
import com.wangzan101.mika.core.shell.PairingNotifyIssue
import com.wangzan101.mika.core.shell.PhoneSetupFlow
import com.wangzan101.mika.core.shell.SetupPhase
import com.wangzan101.mika.core.shell.SetupStep
import com.wangzan101.mika.core.shell.SetupView
import com.wangzan101.mika.core.shell.StepMark
import com.wangzan101.mika.settings.SystemStatus
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.cardSurface
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.ProgressDots
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaShapes
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

/** 用户确认做好的几步,转屏、进后台后还在。 */
private val StepSetSaver = Saver<Set<SetupStep>, String>(
    save = { set -> set.joinToString(",") { it.name } },
    restore = { s -> s.split(',').mapNotNull { n -> SetupStep.entries.firstOrNull { it.name == n } }.toSet() },
)

/**
 * 「设置手机操作」引导页:接上实时状态。每一步按系统开关自动打勾,读不到或读不准时用户点「我已经打开了,下一步」;
 * 点清单里前面的某一步可以回去重做。停在配对一步时开始找配对窗口:无障碍开着就由 Mika 自己读配对码,否则发带输入框的通知。
 * [repair] 是从设置页「重新配对」进来的。
 */
@Composable
fun PhoneSetupScreen(status: SystemStatus, repair: Boolean, onFix: (Fix) -> Unit, onboarding: Boolean = false, onClose: () -> Unit) {
    val context = LocalContext.current
    val phone = remember { PhoneServices.of(context) }
    val pairing = phone.pairing
    val discovery by phone.shellControl.pairingDiscovery.collectAsState()
    val scanning by phone.shellManager.scanningPorts.collectAsState()
    val shell = status.builtinShell
    var repairStarted by rememberSaveable { mutableStateOf(false) }
    if (repair && !repairStarted) { repairStarted = true; pairing.startRepair() }
    val repairing by pairing.repairing.collectAsState()
    var confirmed by rememberSaveable(stateSaver = StepSetSaver) { mutableStateOf(emptySet<SetupStep>()) }
    var revisit by rememberSaveable { mutableStateOf<SetupStep?>(null) }
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    var accessibilitySkipped by rememberSaveable { mutableStateOf(false) }

    var diagnosticsOpen by rememberSaveable { mutableStateOf(false) }

    val inputs = status.setupInputs(discovery, repairing, accessibilitySkipped, confirmed, revisit)
    val view = PhoneSetupFlow.view(inputs)
    // 回去重做的那一步:读到它从关着变成开着,就自动回到原来的步骤,不用再点
    val revisitDetected = revisit?.let { PhoneSetupFlow.detected(it, inputs) }
    var revisitSawOff by rememberSaveable(revisit) { mutableStateOf(false) }
    LaunchedEffect(revisit, revisitDetected) {
        if (revisitDetected == false) revisitSawOff = true
        else if (revisitDetected == true && revisitSawOff) revisit = null
    }
    // 开始连接或已经成功:以助手为准,不再停在回头的那一步
    LaunchedEffect(view.phase) { if (view.phase == SetupPhase.Working || view.phase == SetupPhase.Success) revisit = null }
    // 到了配对一步:开始找配对窗口;通知没允许就在这时请求(配对码要填在通知里)。
    // 「配对」通知被关掉要去系统设置里开,不自动跳过去,页面上给按钮
    LaunchedEffect(view.current) {
        if (view.current == SetupStep.PairingCode) pairing.begin()
    }
    val notificationsOff = view.notifyIssue == PairingNotifyIssue.NotificationsOff
    LaunchedEffect(notificationsOff) {
        if (notificationsOff && !askedNotifications) {
            askedNotifications = true
            onFix(Fix.AllowNotifications)
        }
    }
    LaunchedEffect(view.phase) { if (view.phase == SetupPhase.Success) phone.setupPrompt.clear() }

    val close = { pairing.cancelRepair(); onClose() }
    BackHandler(onBack = close)
    PhoneSetupContent(
        view = view,
        onStepAction = { step ->
            when (step) {
                SetupStep.DeveloperOptions -> onFix(Fix.OpenAboutPhone)
                SetupStep.UsbDebugging -> onFix(Fix.OpenUsbDebugging)
                SetupStep.Accessibility -> onFix(Fix.OpenAccessibility)
                SetupStep.WirelessDebugging -> onFix(Fix.OpenDeveloperOptions)
                SetupStep.PairingCode -> { pairing.begin(); onFix(Fix.OpenWirelessDebugging) }
                SetupStep.Connect -> Unit
            }
        },
        onFailureFix = { fix ->
            when (fix) {
                BuiltinShellFix.Retry -> onFix(Fix.RetryPhoneHelper)
                BuiltinShellFix.RePair -> { pairing.begin(); onFix(Fix.OpenWirelessDebugging) }
                BuiltinShellFix.OpenWifi -> onFix(Fix.OpenWifi)
                BuiltinShellFix.OpenWirelessDebugging -> onFix(Fix.OpenWirelessDebugging)
            }
        },
        onAllowNotifications = { onFix(Fix.AllowNotifications) },
        onOpenPairingChannel = { onFix(Fix.OpenPairingChannel) },
        headsUpLowered = status.notificationsAllowed && (status.attentionLowered || status.resultLowered),
        onOpenHeadsUp = { onFix(if (status.attentionLowered) Fix.OpenAttentionChannel else Fix.OpenResultChannel) },
        onConfirmStep = { step -> confirmed = confirmed + step; revisit = null },
        onSubmit = { code, port -> pairing.submit(code, port) },
        onClose = close,
        onAppInfo = { onFix(Fix.OpenAppInfo) },
        onSkipAccessibility = { accessibilitySkipped = true; revisit = null },
        onRevisit = { step -> revisit = step },
        onboarding = onboarding,
        onDiagnostics = { diagnosticsOpen = true },
        scanning = scanning,
        onConnectPort = { port ->
            phone.pairing.scope.launch {
                phone.sharedShell.reconnect()
                phone.shellManager.connectWithPort(port)
            }
        },
    )
    // 卡在哪一步都能把诊断信息发给作者:用的是引导页此刻的输入,用户确认过的步骤也在里面
    if (diagnosticsOpen) {
        DiagnosticsSheet(load = { Diagnostics.render(DiagnosticsCollector.collect(context, status, inputs)) }, onDismiss = { diagnosticsOpen = false })
    }
}

/**
 * 不带状态的引导页,便于测试。步骤是一张编号清单:做完的打勾,当前一步亮起并带按钮,后面的变灰。
 * 读开关的几步都有 [onConfirmStep](「我已经打开了,下一步」);做完的步骤可以点,[onRevisit] 回去重做,传 null 是回到原来的步骤。
 * [onboarding] 为真时是首次上手的最后一步,底部多一排分页点。[onSubmit] 收到的码已经校验过是 6 位数字。
 * 配对一步通知发不出来时([SetupView.needsNotifications]),先修通知是主按钮:[onAllowNotifications] 或 [onOpenPairingChannel]。
 * [onDiagnostics] 不为空时底部总有「复制诊断信息」,失败时出错的那一步里也有一个。
 * 除了成功,哪个阶段底部都有「稍后再说」,返回也总能离开:不把用户困在这一页。
 * [scanning] 为真时连接一步说明正在本机找端口;连不上时 [onConnectPort] 收用户填的无线调试端口(已校验 1–65535)。
 */
@Composable
fun PhoneSetupContent(
    view: SetupView,
    onStepAction: (SetupStep) -> Unit,
    onFailureFix: (BuiltinShellFix) -> Unit,
    onAllowNotifications: () -> Unit,
    onConfirmStep: (SetupStep) -> Unit,
    onSubmit: (code: String, port: Int?) -> Unit,
    onClose: () -> Unit,
    headsUpLowered: Boolean = false,
    onOpenHeadsUp: () -> Unit = {},
    onAppInfo: () -> Unit = {},
    onSkipAccessibility: () -> Unit = {},
    onRevisit: (SetupStep?) -> Unit = {},
    onboarding: Boolean = false,
    onOpenPairingChannel: () -> Unit = {},
    onDiagnostics: (() -> Unit)? = null,
    scanning: Boolean = false,
    onConnectPort: ((Int) -> Unit)? = null,
) {
    val canRevisit = view.phase == SetupPhase.Guide || view.phase == SetupPhase.Failed
    fun revisitable(step: SetupStep) = canRevisit && step != view.current && step in PhoneSetupFlow.REVISITABLE &&
        view.marks.getValue(step) in setOf(StepMark.Done, StepMark.Skipped, StepMark.Failed)
    val c = MikaTheme.colors
    PageBackground {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
            TopBar(PhoneSetupCopy.BAR_TITLE, MikaIcons.Back, onClose, stringResource(R.string.back))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                if (view.phase == SetupPhase.Success) {
                    Text(
                        PhoneSetupCopy.SUCCESS_LINE, Modifier.padding(top = 4.dp, bottom = 16.dp).testTag("setup-success"),
                        style = MikaType.Body, fontWeight = FontWeight.SemiBold, color = c.ok,
                    )
                } else {
                    Text(
                        PhoneSetupCopy.INTRO, Modifier.padding(top = 4.dp, bottom = 16.dp),
                        fontSize = 14.5.sp, lineHeight = 23.sp, color = c.ink2,
                    )
                }
                GroupCard {
                    SetupStep.entries.forEach { step ->
                        StepRow(
                            step, view.marks.getValue(step), current = step == view.current, first = step.ordinal == 0,
                            onClick = if (revisitable(step)) ({ onRevisit(step) }) else null,
                        ) {
                            if (step == view.current) CurrentStepBody(
                                step, view, onStepAction, onFailureFix, onAllowNotifications, onOpenPairingChannel, headsUpLowered, onOpenHeadsUp,
                                onConfirmStep, onSubmit, onAppInfo, onSkipAccessibility, onRevisit, onDiagnostics, onConnectPort,
                            )
                            if (step == SetupStep.Connect && view.phase == SetupPhase.Working) WorkingLine(scanning)
                        }
                    }
                }
                if (SetupStep.entries.any(::revisitable)) FooterNote(PhoneSetupCopy.REVISIT_NOTE)
                if (view.phase != SetupPhase.Success) FooterNote(PhoneSetupCopy.SKIP_NOTE)
            }
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
                if (view.phase == SetupPhase.Success) {
                    Text(
                        PhoneSetupCopy.SUCCESS_NOTE, Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        style = MikaType.Caption, color = c.ink3, textAlign = TextAlign.Center,
                    )
                }
                if (onboarding) ProgressDots(Onboarding.DOTS, Onboarding.dot(OnboardingStep.Phone), Modifier.padding(bottom = 8.dp))
                when (view.phase) {
                    SetupPhase.Success -> MikaButton(
                        if (onboarding) PhoneSetupCopy.START_USING else PhoneSetupCopy.SUCCESS_DONE, onClose,
                        Modifier.fillMaxWidth().testTag("setup-done"),
                    )
                    // 正在连接、连不上时也能先离开,之后在设置里接着弄
                    SetupPhase.Guide, SetupPhase.Working, SetupPhase.Failed -> MikaButton(
                        PhoneSetupCopy.LATER, onClose, Modifier.fillMaxWidth().testTag("setup-later"),
                        kind = ButtonKind.Text, size = ButtonSize.Medium,
                    )
                }
                onDiagnostics?.let {
                    MikaButton(
                        PhoneSetupCopy.DIAGNOSTICS, it, Modifier.fillMaxWidth().testTag("setup-diagnostics"),
                        kind = ButtonKind.Text, size = ButtonSize.Small,
                    )
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: SetupStep, mark: StepMark, current: Boolean, first: Boolean, onClick: (() -> Unit)?, body: @Composable () -> Unit) {
    val copy = PhoneSetupCopy.step(step)
    val c = MikaTheme.colors
    Box(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).testTag("setup-step-${step.name}")) {
        if (!first) Box(Modifier.padding(start = 58.dp).fillMaxWidth().height(0.5.dp).background(c.hair))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StepBadge(step.ordinal + 1, mark)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        copy.title, Modifier.weight(1f), style = MikaType.Body,
                        fontWeight = if (mark == StepMark.Current || mark == StepMark.Failed) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (mark == StepMark.Todo || mark == StepMark.Skipped) c.ink3 else c.ink,
                    )
                    if (mark == StepMark.Done) RowValue(copy.done, ValueTone.Ok)
                    if (mark == StepMark.Skipped) RowValue(PhoneSetupCopy.ACCESSIBILITY_SKIPPED)
                }
                if (!current) Text(copy.brief, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = if (mark == StepMark.Todo) c.ink3 else c.ink2)
                body()
            }
        }
    }
}

@Composable
private fun StepBadge(n: Int, mark: StepMark) {
    val c = MikaTheme.colors
    val bg = when (mark) {
        StepMark.Done -> c.ok
        StepMark.Current -> c.accent
        StepMark.Failed -> c.bad
        StepMark.Todo, StepMark.Skipped -> c.greySoft
    }
    Box(Modifier.size(28.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        when (mark) {
            StepMark.Done -> Icon(MikaIcons.Check, null, Modifier.size(15.dp), tint = c.onAccent)
            StepMark.Failed -> Icon(MikaIcons.Exclaim, null, Modifier.size(15.dp), tint = c.onAccent)
            else -> Text("$n", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (mark == StepMark.Todo || mark == StepMark.Skipped) c.ink3 else c.onAccent)
        }
    }
}

@Composable
private fun CurrentStepBody(
    step: SetupStep,
    view: SetupView,
    onStepAction: (SetupStep) -> Unit,
    onFailureFix: (BuiltinShellFix) -> Unit,
    onAllowNotifications: () -> Unit,
    onOpenPairingChannel: () -> Unit,
    headsUpLowered: Boolean,
    onOpenHeadsUp: () -> Unit,
    onConfirmStep: (SetupStep) -> Unit,
    onSubmit: (String, Int?) -> Unit,
    onAppInfo: () -> Unit,
    onSkipAccessibility: () -> Unit,
    onRevisit: (SetupStep?) -> Unit,
    onDiagnostics: (() -> Unit)?,
    onConnectPort: ((Int) -> Unit)?,
) {
    val copy = PhoneSetupCopy.step(step)
    Text(copy.body, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = MikaTheme.colors.ink2)
    view.failure?.let { f ->
        val fc = PhoneSetupCopy.failure(f.reason)
        Column(
            Modifier.padding(top = 10.dp).fillMaxWidth().background(MikaTheme.colors.badSoft, RoundedCornerShape(12.dp)).padding(12.dp),
        ) {
            Text(fc.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MikaTheme.colors.bad)
            Text(fc.body, Modifier.padding(top = 2.dp), fontSize = 13.sp, lineHeight = 18.sp, color = MikaTheme.colors.ink2)
        }
        PillButton(PhoneSetupCopy.fixLabel(f.fix), primary = true, tag = "setup-fix") { onFailureFix(f.fix) }
        // 自动找不到端口:让用户照着「无线调试」页面填
        if (step == SetupStep.Connect && f.reason == BuiltinShellFailure.ConnectFailed) onConnectPort?.let { ConnectPort(it) }
        if (view.phase == SetupPhase.Failed) onDiagnostics?.let { LinkText(PhoneSetupCopy.DIAGNOSTICS, "setup-diagnostics-failed", it) }
    }
    // 没成功时最可能的原因在前面哪一步:说清楚,给回去的按钮
    view.suspect?.let { s ->
        Text(
            PhoneSetupCopy.suspect(s),
            Modifier.padding(top = 10.dp).fillMaxWidth().background(MikaTheme.colors.warnSoft, RoundedCornerShape(12.dp)).padding(12.dp)
                .testTag("setup-suspect"),
            fontSize = 13.sp, lineHeight = 18.sp, color = MikaTheme.colors.ink,
        )
        PillButton(PhoneSetupCopy.goBackTo(s.step), primary = view.failure == null, tag = "setup-suspect-back") { onRevisit(s.step) }
    }
    // 没有无障碍时配对码只能填在通知里:通知发不出来就先修它,这是这一步的主按钮
    val fixNotificationsFirst = step == SetupStep.PairingCode && view.needsNotifications && !view.autoRead
    if (step == SetupStep.PairingCode) {
        when (view.notifyIssue) {
            PairingNotifyIssue.NotificationsOff -> {
                Text(PhoneSetupCopy.NEED_NOTIFICATIONS, Modifier.padding(top = 8.dp), fontSize = 13.sp, lineHeight = 18.sp, color = MikaTheme.colors.warn)
                PillButton(PhoneSetupCopy.ALLOW_NOTIFICATIONS, primary = fixNotificationsFirst, tag = "setup-notify-fix") { onAllowNotifications() }
            }
            PairingNotifyIssue.ChannelOff -> {
                Text(PhoneSetupCopy.PAIRING_CHANNEL_OFF, Modifier.padding(top = 8.dp), fontSize = 13.sp, lineHeight = 18.sp, color = MikaTheme.colors.warn)
                PillButton(PhoneSetupCopy.OPEN_PAIRING_CHANNEL, primary = fixNotificationsFirst, tag = "setup-notify-fix") { onOpenPairingChannel() }
            }
            // 不弹出也能下拉去填:只提醒,不抢主按钮;无障碍开着时用不上通知,不提
            PairingNotifyIssue.NotHeadsUp -> if (!view.autoRead) {
                Text(
                    PhoneSetupCopy.PAIRING_LOWERED,
                    Modifier.padding(top = 10.dp).fillMaxWidth().background(MikaTheme.colors.warnSoft, RoundedCornerShape(12.dp)).padding(12.dp)
                        .testTag("setup-pairing-lowered"),
                    fontSize = 13.sp, lineHeight = 18.sp, color = MikaTheme.colors.ink,
                )
                PillButton(PhoneSetupCopy.OPEN_HEADS_UP, primary = false, tag = "setup-pairing-lowered-open") { onOpenPairingChannel() }
            }
            null -> Unit
        }
        if (headsUpLowered && !view.needsNotifications) {
            Column(
                Modifier.padding(top = 10.dp).fillMaxWidth().background(MikaTheme.colors.warnSoft, RoundedCornerShape(12.dp)).padding(12.dp)
                    .testTag("setup-heads-up"),
            ) {
                Text(PhoneSetupCopy.HEADS_UP_LOWERED, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = MikaTheme.colors.warn)
            }
            PillButton(PhoneSetupCopy.OPEN_HEADS_UP, primary = false, tag = "setup-heads-up-open") { onOpenHeadsUp() }
        }
        if (view.autoRead) AutoReadHint() else NotifyGuide()
        if (view.portFound && !view.autoRead) Text(PhoneSetupCopy.PORT_FOUND, Modifier.padding(top = 8.dp), fontSize = 13.sp, color = MikaTheme.colors.ok)
    }
    // 无障碍一步推荐跳过:配对后 Mika 自己打开,也不会被「受限制的设置」拦住
    val a11y = step == SetupStep.Accessibility
    if (a11y) PillButton(PhoneSetupCopy.ACCESSIBILITY_SKIP, primary = true, tag = "setup-skip-a11y", onClick = onSkipAccessibility)
    if (view.failure == null && copy.action != null) {
        PillButton(copy.action, primary = !a11y && !fixNotificationsFirst, tag = "setup-action") { onStepAction(step) }
    }
    if (a11y && view.showAppInfo) PillButton(PhoneSetupCopy.APP_INFO, primary = false, tag = "setup-app-info", onClick = onAppInfo)
    // 读到的开关只是参考:用户说打开了就往下走,哪一步都不会卡死
    if (step in PhoneSetupFlow.CONFIRMABLE) PillButton(PhoneSetupCopy.TURNED_ON_NEXT, primary = false, tag = "setup-confirm") { onConfirmStep(step) }
    if (step == SetupStep.PairingCode) ManualCode(askPort = !view.portFound, onSubmit = onSubmit)
    if (view.revisiting) LinkText(PhoneSetupCopy.REVISIT_BACK, "setup-revisit-back") { onRevisit(null) }
}

@Composable
private fun LinkText(text: String, tag: String?, onClick: () -> Unit) {
    Text(
        text, Modifier.padding(top = 6.dp).clickable(onClick = onClick).padding(vertical = 4.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier),
        fontSize = 13.sp, color = MikaTheme.colors.accent,
    )
}

/** 无障碍开着:去系统设置前说一句,Mika 会自己读配对码。 */
@Composable
private fun AutoReadHint() {
    val c = MikaTheme.colors
    Row(
        Modifier.padding(top = 10.dp).fillMaxWidth().background(c.okSoft, RoundedCornerShape(12.dp))
            .padding(12.dp).testTag("setup-pairing-hint"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(MikaIcons.Check, null, Modifier.size(20.dp), tint = c.ok)
        Text(PhoneSetupCopy.AUTO_READ_HINT, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
    }
}

/** 无障碍没开:用三行编号小图示说清楚配对码怎么填进通知,不用离开配对窗口。 */
@Composable
private fun NotifyGuide() {
    val c = MikaTheme.colors
    val icons = listOf(MikaIcons.Phone, MikaIcons.Bell, MikaIcons.Key)
    Column(
        Modifier.padding(top = 10.dp).fillMaxWidth().background(c.accentSoft, RoundedCornerShape(12.dp))
            .padding(12.dp).testTag("setup-pairing-guide"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PhoneSetupCopy.NOTIFY_GUIDE.forEachIndexed { i, line ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(22.dp).background(c.accent, CircleShape), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.onAccent)
                }
                Icon(icons[i], null, Modifier.size(18.dp), tint = c.accent)
                Text(line, Modifier.weight(1f), fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = c.ink)
            }
        }
    }
}

/** 通知没弹出来时的兜底:在 App 里直接填码(找不到配对窗口时连带填端口)。 */
@Composable
private fun ManualCode(askPort: Boolean, onSubmit: (String, Int?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Text(
        PhoneSetupCopy.MANUAL_TOGGLE, Modifier.padding(top = 8.dp).clickable { open = !open }.padding(vertical = 4.dp).testTag("setup-manual"),
        fontSize = 13.sp, color = MikaTheme.colors.accent,
    )
    if (!open) return
    Text(PhoneSetupCopy.MANUAL_HINT, fontSize = 12.5.sp, lineHeight = 17.sp, color = MikaTheme.colors.ink3)
    OutlinedTextField(
        code, { code = it; error = null }, Modifier.fillMaxWidth().padding(top = 6.dp).keepsKeyboard().testTag("setup-code"),
        label = { Text(PhoneSetupCopy.CODE_LABEL) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
    if (askPort) {
        OutlinedTextField(
            port, { port = it; error = null }, Modifier.fillMaxWidth().padding(top = 6.dp).keepsKeyboard().testTag("setup-port"),
            label = { Text(PhoneSetupCopy.PORT_LABEL) }, supportingText = { Text(PhoneSetupCopy.PORT_HINT) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
    error?.let { Text(it, Modifier.padding(top = 4.dp), fontSize = 12.5.sp, color = MikaTheme.colors.bad) }
    PillButton(PhoneSetupCopy.SUBMIT, primary = true, tag = "setup-submit") {
        val c = PairingCode.normalize(code)
        val p = if (askPort && port.isNotBlank()) PairingCode.port(port) else null
        error = when {
            c == null -> PhoneSetupCopy.CODE_INVALID
            askPort && port.isNotBlank() && p == null -> PhoneSetupCopy.PORT_INVALID
            else -> null
        }
        if (error == null && c != null) onSubmit(c, p)
    }
}

/** 用户填的无线调试端口:可能整段粘了「IP 地址和端口」(取最后一个冒号后面)、带空格或全角数字;不在 1–65535 为 null。 */
internal object ConnectPortInput {
    fun parse(text: String): Int? {
        val tail = text.substringAfterLast(':').substringAfterLast('：')
        val digits = tail.filterNot { it.isWhitespace() }.map { c -> if (c in '０'..'９') '0' + (c - '０') else c }.joinToString("")
        return PairingCode.port(digits)
    }
}

/** 连不上时的兜底:填「无线调试」页面上的端口,点「连接」。 */
@Composable
private fun ConnectPort(onConnect: (Int) -> Unit) {
    var port by rememberSaveable { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    OutlinedTextField(
        port, { port = it; invalid = false }, Modifier.fillMaxWidth().padding(top = 10.dp).keepsKeyboard().testTag("setup-connect-port"),
        label = { Text(PhoneSetupCopy.CONNECT_PORT_LABEL) },
        supportingText = { Text(if (invalid) PhoneSetupCopy.PORT_INVALID else PhoneSetupCopy.CONNECT_PORT_HINT, color = if (invalid) MikaTheme.colors.bad else MikaTheme.colors.ink3) },
        singleLine = true, isError = invalid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    PillButton(PhoneSetupCopy.CONNECT_PORT_SUBMIT, primary = false, tag = "setup-connect-port-submit") {
        val p = ConnectPortInput.parse(port)
        invalid = p == null
        if (p != null) onConnect(p)
    }
}

@Composable
private fun WorkingLine(scanning: Boolean) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), color = MikaTheme.colors.accent, strokeWidth = 2.dp)
        Text(
            if (scanning) PhoneSetupCopy.SCANNING_PORTS else PhoneSetupCopy.WORKING, Modifier.testTag("setup-working"),
            fontSize = 13.5.sp, color = MikaTheme.colors.ink2,
        )
    }
}

@Composable
private fun PillButton(text: String, primary: Boolean, tag: String? = null, onClick: () -> Unit) {
    MikaButton(
        text, onClick, Modifier.padding(top = 10.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier),
        kind = if (primary) ButtonKind.Primary else ButtonKind.Tonal, size = ButtonSize.Small,
    )
}

// ---------- 设置页「高级」 ----------

/** 「高级」分组里内置助手的三行:状态、重新配对、解除配对(要确认)。没配对过时不能解除。 */
@Composable
fun PhoneHelperSection(status: BuiltinShellStatus, onRepair: () -> Unit, onUnpair: () -> Unit, usbDebugging: Boolean = true) {
    var confirming by remember { mutableStateOf(false) }
    GroupCard {
        val (value, tone) = PhoneSetupCopy.statusValue(status)
        ListRow(MikaIcons.Phone, PhoneSetupCopy.ROW_TITLE, PhoneSetupCopy.statusLine(status, usbDebugging), first = true) {
            RowValue(value, tone)
        }
        ListRow(MikaIcons.Link, PhoneSetupCopy.REPAIR, PhoneSetupCopy.REPAIR_SUBTITLE, onClick = onRepair) {
            RowChevron()
        }
        val paired = status != BuiltinShellStatus.NotPaired
        ListRow(
            MikaIcons.Close, PhoneSetupCopy.UNPAIR, PhoneSetupCopy.UNPAIR_SUBTITLE,
            titleColor = if (paired) MikaTheme.colors.bad else MikaTheme.colors.ink3, tone = if (paired) TileTone.Bad else TileTone.Neutral, onClick = if (paired) ({ confirming = true }) else null,
        )
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(PhoneSetupCopy.UNPAIR_TITLE) },
            text = { Text(PhoneSetupCopy.UNPAIR_BODY) },
            confirmButton = { TextButton({ confirming = false; onUnpair() }) { Text(PhoneSetupCopy.UNPAIR_CONFIRM, color = MikaTheme.colors.bad) } },
            dismissButton = { TextButton({ confirming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

// ---------- 对话页 ----------

/** 任务要用手机而手机操作没准备好时,任务卡片下面的一行。 */
@Composable
fun PhoneSetupLine(onOpen: () -> Unit) {
    val shape = MikaShapes.Card
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).cardSurface(MikaTheme.colors, shape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(PhoneSetupCopy.DEGRADED_LINE, Modifier.weight(1f), fontSize = 13.sp, lineHeight = 18.sp, color = MikaTheme.colors.ink2)
        Text(
            PhoneSetupCopy.DEGRADED_ACTION, Modifier.clickable(onClick = onOpen).padding(4.dp).testTag("phone-setup-open"),
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MikaTheme.colors.accent,
        )
    }
}
