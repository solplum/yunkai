package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.settings.ProviderConnection
import android.app.ActivityManager
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.cap.sandbox.SandboxState
import com.wangzan101.mika.capsule.CapsuleMode
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.replay.FileRouteStore
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.settings.Problem
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.SystemStatus
import com.wangzan101.mika.settings.notificationOverview
import com.wangzan101.mika.task.ParallelCapability
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.PillTone
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.StateCard
import com.wangzan101.mika.ui.components.StatusPill
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.core.usage.UsageQuery
import com.wangzan101.mika.core.usage.UsageRange
import com.wangzan101.mika.i18n.AppLanguage
import kotlinx.coroutines.Dispatchers
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置里能进入的子页面;[Home] 是设置首页。 */
private enum class SettingsPage { Home, Usage, PhoneOps, DuringWork, Capsule, Language, Notifications, WebSites, Sandbox, Routes, Recovery, Developer, RunStats }

/**
 * 设置页:总状态卡 + 基本 / 隐私与数据 / 高级 / 关于四组。各子页面在这里切换(返回键先回首页),
 * 模型页由 [onOpenModels]、关于页由 [onOpenAbout] 交给 Activity。修复动作由 [onFix] 交给 Activity 去做(请求权限、跳转系统设置)。
 */
@Composable
fun SettingsScreen(
    store: ProviderStore,
    status: SystemStatus,
    androidRelease: String,
    onFix: (Fix) -> Unit,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenMemory: (() -> Unit)? = null,
    webSites: WebSitesState? = null,
    /** 开发者选项里的「自主模式」:当前值和换它的办法。 */
    autonomous: Boolean = false,
    onAutonomous: (Boolean) -> Unit = {},
    /** 查到的新版本号;有它时「关于 Mika」一行写「x 可更新」带红点。 */
    updateVersion: String? = null,
    onOpenAbout: () -> Unit = {},
) {
    val configs by store.configs.collectAsState()
    val roles by store.roles.collectAsState()
    // 开发者选项里的「看图定位已验证」跟着看图模型(看图用主模型时是主模型那条配置)
    val visionConfig = remember(configs, roles) { store.configFor(ModelRole.Vision) }
    var page by rememberSaveable { mutableStateOf(SettingsPage.Home) }
    var explaining by remember { mutableStateOf<Problem?>(null) }

    fun fix(f: Fix) = if (f == Fix.AddModel) onOpenModels() else onFix(f)
    val onProblem: (Problem) -> Unit = { p -> StatusCopy.directFix(p)?.let(::fix) ?: run { explaining = p } }

    // 并行任务的上限只在实验开关 parallelTasks 打开时出现;设备能力按此刻算,第二块屏建失败过就按失败算
    val context = LocalContext.current
    val flags = remember { FeatureFlags.get(context) }
    var parallelMax by remember { mutableIntStateOf(flags.parallelMax()) }
    var keepScreenOn by remember { mutableStateOf(flags.keepScreenOn()) }
    var muteTarget by remember { mutableStateOf(flags.muteTarget()) }
    var capsuleMode by remember { mutableStateOf(flags.capsuleMode()) }
    // 换语言时界面会重建,这里每次进来读一次即可
    val language = remember { AppLanguage.current(context) }
    val runtimeLimit by (context.applicationContext as com.wangzan101.mika.MikaApp).taskState.parallelLimit.collectAsState()
    var speed by remember { mutableStateOf(flags.speedFlags()) }
    val routeStore = remember { FileRouteStore.shared(context) }
    var routeCount by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { routeCount = withContext(Dispatchers.IO) { runCatching { routeStore.count() }.getOrNull() } }
    val sandboxStatus by remember { SandboxState.get(context) }.status.collectAsState()
    // 「Token 用量」一行的值:近 7 天的合计,账本每记一笔就重算
    val usage = remember { (context.applicationContext as com.wangzan101.mika.MikaApp).usage }
    val usageVersion by usage.version.collectAsState()
    var usageWeek by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(usageVersion) {
        usageWeek = withContext(Dispatchers.Default) {
            UsageQuery.report(usage.snapshot(), UsageRange.LastDays(7), Instant.now(), ZoneId.systemDefault(), emptyMap()).totals.tokens
        }
    }
    val parallel = if (!speed.parallelTasks) null else ParallelSetting(
        parallelMax,
        ParallelCapability.detect(
            status.sdkInt, context.getSystemService(ActivityManager::class.java).isLowRamDevice, parallelMax,
            secondDisplayFailed = runtimeLimit.reason == ParallelCapability.SECOND_DISPLAY_FAILED,
        ),
    )
    // 开发者选项只在调试版和性能测试版里列出:发布版的各开关按评测结果统一定默认值
    val showDeveloper = BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "perf"
    val toHome = { page = SettingsPage.Home }
    // 通知页可以从首页或手机操作页进,返回回到进来的那一页
    var notificationsFrom by rememberSaveable { mutableStateOf(SettingsPage.Home) }
    val back = {
        page = when (page) {
            SettingsPage.RunStats -> SettingsPage.Developer
            SettingsPage.Notifications -> notificationsFrom
            else -> SettingsPage.Home
        }
    }

    if (page != SettingsPage.Home) BackHandler { back() }

    when (page) {
        SettingsPage.Home -> SettingsContent(
            status = status, androidRelease = androidRelease,
            mainModel = roles.main?.let { id -> configs.firstOrNull { it.id == id } }?.let(ProviderConnection::displayName),
            onBack = onBack, onProblem = onProblem, onOpenModels = onOpenModels,
            usageTokens = usageWeek, onOpenUsage = { page = SettingsPage.Usage },
            onOpenPhoneOps = { page = SettingsPage.PhoneOps },
            onOpenDuringWork = { page = SettingsPage.DuringWork },
            capsuleMode = capsuleMode, onOpenCapsule = { page = SettingsPage.Capsule },
            onOpenNotifications = { notificationsFrom = SettingsPage.Home; page = SettingsPage.Notifications },
            language = language, onOpenLanguage = { page = SettingsPage.Language },
            onOpenMemory = onOpenMemory,
            webSitesCount = webSites?.sites?.size, onOpenWebSites = { page = SettingsPage.WebSites },
            sandbox = sandboxStatus, onOpenSandbox = { page = SettingsPage.Sandbox },
            routes = routeCount, onOpenRoutes = { page = SettingsPage.Routes },
            onOpenRecovery = { page = SettingsPage.Recovery },
            showDeveloper = showDeveloper, onOpenDeveloper = { page = SettingsPage.Developer },
            versionName = BuildConfig.VERSION_NAME, updateVersion = updateVersion, onOpenAbout = onOpenAbout,
        )
        SettingsPage.Usage -> UsageScreen(usage, store.providers, onBack = toHome)
        SettingsPage.PhoneOps -> PhoneOpsScreen(
            status, androidRelease, onProblem,
            onOpenNotifications = { notificationsFrom = SettingsPage.PhoneOps; page = SettingsPage.Notifications },
            onRepair = { onFix(Fix.RepairPhoneHelper) }, onUnpair = { onFix(Fix.UnpairPhoneHelper) }, onBack = toHome,
        )
        SettingsPage.Notifications -> NotificationsScreen(status, onFix = ::fix, onBack = back)
        SettingsPage.DuringWork -> DuringWorkScreen(
            keepScreenOn, onKeepScreenOn = { flags.setKeepScreenOn(it); keepScreenOn = it },
            muteTarget, onMuteTarget = { flags.setMuteTarget(it); muteTarget = it },
            parallel, onParallelMax = { flags.setParallelMax(it); parallelMax = it }, onBack = toHome,
        )
        SettingsPage.Language -> LanguageScreen(language, onLanguage = { AppLanguage.set(context, it) }, onBack = toHome)
        SettingsPage.Capsule -> CapsuleScreen(capsuleMode, onMode = { flags.setCapsuleMode(it); capsuleMode = it }, onBack = toHome)
        // 没有数据时首页不显示入口,走不到这一页
        SettingsPage.WebSites -> webSites?.let { WebSitesScreen(it, onBack = toHome) }
        SettingsPage.Sandbox -> SandboxScreen(onBack = toHome)
        SettingsPage.Routes -> RoutesScreen(
            routeCount,
            onClear = { scope.launch { routeCount = withContext(Dispatchers.IO) { routeStore.clear(); routeStore.count() } } },
            onBack = toHome,
        )
        SettingsPage.Recovery -> RecoveryScreen(onBack = toHome)
        SettingsPage.Developer -> DeveloperOptionsScreen(
            speed, onSpeed = { flags.setSpeed(it); speed = it }, onBack = toHome,
            autonomous = autonomous, onAutonomous = onAutonomous,
            grounding = visionConfig?.let { GroundingSetting(it.model, it.capabilities.groundingVerified) },
            onGrounding = { verified -> scope.launch { withContext(Dispatchers.IO) { store.setVisionGroundingVerified(verified) } } },
            onOpenRunStats = { page = SettingsPage.RunStats },
        )
        SettingsPage.RunStats -> RunStatsScreen(remember { RunStore(context).metrics }, onBack = back)
    }

    explaining?.let { p ->
        val e = StatusCopy.explain(p, androidRelease)
        AlertDialog(
            onDismissRequest = { explaining = null },
            title = { Text(e.title) },
            text = { Text(e.body) },
            confirmButton = {
                if (e.action != null && e.fix != null) {
                    TextButton({ explaining = null; fix(e.fix) }) { Text(e.action) }
                } else {
                    TextButton({ explaining = null }) { Text(stringResource(R.string.ok_got_it)) }
                }
            },
            dismissButton = if (e.fix != null) ({ TextButton({ explaining = null }) { Text(stringResource(R.string.cancel)) } }) else null,
        )
    }
}

/**
 * 不带状态的设置首页,便于测试。可选的入口([usageTokens]、[onOpenMemory]、[webSitesCount]、[grantsCount]、[routes]、[sandbox])
 * 为空时对应那一行不显示;[showDeveloper] 为假(发布版)时没有开发者选项。
 */
@Composable
fun SettingsContent(
    status: SystemStatus,
    androidRelease: String,
    /** 当前主模型的名字;没有可用的主模型时为空。 */
    mainModel: String?,
    onBack: () -> Unit,
    onProblem: (Problem) -> Unit,
    onOpenModels: () -> Unit,
    /** 近 7 天用了多少 token;为空(还没统计出来)时不显示这一行。 */
    usageTokens: Long? = null,
    onOpenUsage: () -> Unit = {},
    onOpenPhoneOps: () -> Unit = {},
    onOpenDuringWork: () -> Unit = {},
    /** 悬浮胶囊此刻的选择,写在「悬浮胶囊」一行的右边。 */
    capsuleMode: CapsuleMode = CapsuleMode.DEFAULT,
    onOpenCapsule: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    /** 此刻选的界面语言,写在「语言」一行的右边。 */
    language: AppLanguage = AppLanguage.System,
    onOpenLanguage: () -> Unit = {},
    onOpenMemory: (() -> Unit)? = null,
    webSitesCount: Int? = null,
    onOpenWebSites: () -> Unit = {},
    sandbox: SandboxStatus? = null,
    onOpenSandbox: () -> Unit = {},
    /** 已记住的操作路线条数;为空(还没读出来)时不显示这一行。 */
    routes: Int? = null,
    onOpenRoutes: () -> Unit = {},
    onOpenRecovery: () -> Unit = {},
    showDeveloper: Boolean = false,
    onOpenDeveloper: () -> Unit = {},
    versionName: String = "",
    updateVersion: String? = null,
    onOpenAbout: () -> Unit = {},
) {
    val check = Prerequisites.check(status, hasMainModel = mainModel != null)
    PageScaffold(stringResource(R.string.settings_title), onBack) {
        StatusSummary(check, onProblem)

        GroupLabel(stringResource(R.string.settings_basic))
        GroupCard {
            ListRow(MikaIcons.Spark, stringResource(R.string.settings_model), first = true, onClick = onOpenModels) {
                RowValue(mainModel ?: stringResource(R.string.settings_not_set))
                RowChevron()
            }
            usageTokens?.let {
                ListRow(MikaIcons.Chart, UsageCopy.TITLE, onClick = onOpenUsage) {
                    RowValue(UsageCopy.settingsValue(it))
                    RowChevron()
                }
            }
            ListRow(MikaIcons.Phone, stringResource(R.string.settings_phone_ops), onClick = onOpenPhoneOps) {
                PhoneOpsPill(status)
                RowChevron()
            }
            ListRow(MikaIcons.Moon, stringResource(R.string.settings_during_work), stringResource(R.string.settings_during_work_sub), onClick = onOpenDuringWork) { RowChevron() }
            ListRow(MikaIcons.Message, CapsuleSettingsCopy.TITLE, CapsuleSettingsCopy.SUBTITLE, onClick = onOpenCapsule) {
                RowValue(CapsuleSettingsCopy.label(capsuleMode))
                RowChevron()
            }
            ListRow(MikaIcons.Globe, stringResource(R.string.language_title), onClick = onOpenLanguage) {
                RowValue(languageLabel(language))
                RowChevron()
            }
            val (notifText, notifTone) = StatusCopy.notificationSummary(status.notificationOverview().summary)
            ListRow(MikaIcons.Bell, StatusCopy.NOTIFICATIONS, stringResource(R.string.settings_notif_sub), onClick = onOpenNotifications) {
                RowValue(notifText, notifTone)
                RowChevron()
            }
        }

        val privacyRows = onOpenMemory != null || webSitesCount != null
        if (privacyRows) {
            GroupLabel(stringResource(R.string.settings_privacy))
            GroupCard {
                var first = true
                fun take(): Boolean = first.also { first = false }
                onOpenMemory?.let {
                    ListRow(MikaIcons.Heart, stringResource(R.string.memory_and_skills), stringResource(R.string.settings_memory_sub), first = take(), onClick = it) { RowChevron() }
                }
                webSitesCount?.let {
                    ListRow(MikaIcons.Globe, stringResource(R.string.settings_web_logins), first = take(), onClick = onOpenWebSites) { RowValue(trPlural(R.plurals.settings_count_sites, it)); RowChevron() }
                }
            }
        }

        val sandboxRow = sandbox?.takeIf { it !is SandboxStatus.Unsupported }
        val recoveryRow = status.sdkInt >= Prerequisites.MIN_SDK
        if (sandboxRow != null || routes != null || recoveryRow || showDeveloper) {
            GroupLabel(stringResource(R.string.settings_advanced))
            GroupCard {
                var first = true
                fun take(): Boolean = first.also { first = false }
                if (sandboxRow != null) {
                    ListRow(MikaIcons.Terminal, stringResource(R.string.settings_sandbox), first = take(), onClick = onOpenSandbox) {
                        RowValue(sandboxValue(sandboxRow)); RowChevron()
                    }
                }
                routes?.let {
                    ListRow(MikaIcons.Route, stringResource(R.string.settings_routes), first = take(), onClick = onOpenRoutes) { RowValue(trPlural(R.plurals.settings_count_routes, it)); RowChevron() }
                }
                if (recoveryRow) {
                    ListRow(MikaIcons.Wrench, stringResource(R.string.settings_recovery), first = take(), onClick = onOpenRecovery) { RowChevron() }
                }
                if (showDeveloper) {
                    ListRow(MikaIcons.Code, stringResource(R.string.settings_developer), first = take(), onClick = onOpenDeveloper) { RowChevron() }
                }
            }
        }

        GroupCard {
            ListRow(MikaIcons.Info, stringResource(R.string.settings_about), first = true, onClick = onOpenAbout) {
                TrailingValue(AboutCopy.settingsValue(updateVersion, versionName))
                RowChevron()
            }
        }
    }
}

/** 「手机操作」一行尾部的状态胶囊:无障碍和内置助手都就绪才是「可用」。 */
@Composable
private fun PhoneOpsPill(status: SystemStatus) {
    when {
        status.sdkInt < Prerequisites.MIN_SDK -> StatusPill(stringResource(R.string.pill_unsupported), PillTone.Neutral)
        status.builtinShell == BuiltinShellStatus.Running && status.accessibility == AccessibilityState.On -> StatusPill(stringResource(R.string.pill_available), PillTone.Ok)
        else -> StatusPill(stringResource(R.string.pill_needs_attention), PillTone.Warn)
    }
}

private fun sandboxValue(s: SandboxStatus): String = when (s) {
    is SandboxStatus.Ready -> tr(R.string.sandbox_ready)
    is SandboxStatus.Installing -> tr(R.string.sandbox_preparing)
    is SandboxStatus.Broken -> tr(R.string.pill_needs_attention)
    else -> tr(R.string.sandbox_not_prepared)
}

/**
 * 页面顶上的总状态:一切正常是「一切就绪」;否则变成第一个问题的提示,带修复按钮。
 * 焦点提醒改不了,只在手机操作页的「系统版本」一行里提示,不占这里。
 */
@Composable
private fun StatusSummary(check: Prerequisites.Check, onProblem: (Problem) -> Unit) {
    val problem = check.problems.firstOrNull { it != Problem.FocusMayLeak }
    if (problem == null) {
        StateCard(MikaIcons.Check, stringResource(R.string.all_ready), stringResource(R.string.settings_all_ready_detail), TileTone.Ok, Modifier.testTag("settings-status"))
    } else {
        val copy = StatusCopy.banner(problem)
        StateCard(
            MikaIcons.Exclaim, copy.title, copy.detail,
            if (problem in check.blockers) TileTone.Bad else TileTone.Warn,
            Modifier.testTag("settings-status"),
        ) {
            MikaButton(copy.link, { onProblem(problem) }, kind = ButtonKind.Tonal, size = ButtonSize.Small)
        }
    }
}
