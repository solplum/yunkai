package com.wangzan101.mika.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.wangzan101.mika.R
import com.wangzan101.mika.core.chatgpt.ChatGptAuth
import com.wangzan101.mika.i18n.tr
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.ModelOption
import com.wangzan101.mika.settings.ModelOptions
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.CapabilityTag
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.ProgressDots
import com.wangzan101.mika.ui.components.SelectChip
import com.wangzan101.mika.ui.components.TagTone
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/**
 * 配置模型:用途(主模型、看图模型、轻量模型)和服务商两块。首次上手时 [onboarding] 为真
 * (服务商在前,底部有分页点和「下一步」);设置 › 模型([ModelsScreen])也是这一页,标题和引导语不同,没有底部按钮。
 * 选用途、服务商详情、服务商的全部模型是这一页里的子页面。上手时「下一步」选好主模型才可点,点了调 [onDone]。
 */
@Composable
fun ModelSetupScreen(store: ProviderStore, onboarding: Boolean, onBack: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val vm = remember { ModelSetupViewModel(store, scope, chatGptBrowser = AndroidChatGptBrowser(context.applicationContext)) }
    val ui by vm.state.collectAsState()
    var picking by rememberSaveable { mutableStateOf<ModelRole?>(null) }
    var openProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var allModels by rememberSaveable { mutableStateOf(false) }

    val provider = openProvider?.let { id -> ui.providers.firstOrNull { it.id == id } }
    if (provider != null) {
        LaunchedEffect(provider.id) { vm.ensureListed() }
        val actions = ProviderDetailActions(
            refresh = { vm.refresh(provider.id) }, changeKey = { vm.changeKey(provider.id, it) },
            remove = { vm.removeProvider(provider.id); openProvider = null; allModels = false },
            use = { role, model -> vm.pick(role, provider.id, model) },
            showAll = { allModels = true },
            retest = { vm.retest(provider.id) },
            signIn = vm::signInWithChatGpt, signOut = { vm.signOutChatGpt(provider.id) },
            manageUsage = { openInBrowser(context, ChatGptAuth.USAGE_URL) },
        )
        if (allModels) {
            BackHandler { allModels = false }
            AllModelsContent(ui, provider, actions, onBack = { allModels = false })
        } else {
            BackHandler { openProvider = null }
            ProviderDetailContent(ui, provider, actions, onBack = { openProvider = null })
        }
        return
    }
    picking?.let { role ->
        BackHandler { picking = null }
        LaunchedEffect(role) { vm.ensureListed(); vm.clearManualPick() }
        RolePickerContent(
            ui, role,
            RolePickerActions(
                pick = { pid, model ->
                    // 刚存下、没有一个模型试通过的那家:先试调用,通了才选上、返回
                    if (ui.probeBeforePick(role, pid)) vm.pickManual(role, pid, model) { picking = null }
                    else { vm.pick(role, pid, model); picking = null }
                },
                pickManual = { pid, model -> vm.pickManual(role, pid, model) { picking = null } },
                choose = { vm.choose(role, it); picking = null },
                addProvider = { vm.openForm(); picking = null },
            ),
            onBack = { picking = null },
        )
        return
    }

    if (ui.chatGptWelcome) ChatGptWelcome(vm::dismissChatGptWelcome)
    BackHandler(onBack = onBack)
    ModelSetupContent(
        ui, onboarding,
        ModelSetupActions(
            selectPreset = vm::selectPreset, setKind = vm::setKind, setBaseUrl = vm::setBaseUrl, setApiKey = vm::setApiKey,
            setModel = vm::setModel,
            testAndSave = vm::testAndSave, openForm = vm::openForm, closeForm = vm::closeForm,
            openProvider = { openProvider = it; allModels = false }, pickRole = { picking = it }, next = onDone,
            signInWithChatGpt = vm::signInWithChatGpt, cancelChatGpt = vm::cancelChatGptSignIn,
        ),
        onBack,
    )
}

/** 主页面上的全部动作。 */
class ModelSetupActions(
    val selectPreset: (String) -> Unit = {},
    val setKind: (ProviderConfig.Kind) -> Unit = {},
    val setBaseUrl: (String) -> Unit = {},
    val setApiKey: (String) -> Unit = {},
    val setModel: (String) -> Unit = {},
    val testAndSave: () -> Unit = {},
    val openForm: () -> Unit = {},
    val closeForm: () -> Unit = {},
    val openProvider: (String) -> Unit = {},
    val pickRole: (ModelRole) -> Unit = {},
    val next: () -> Unit = {},
    val signInWithChatGpt: () -> Unit = {},
    val cancelChatGpt: () -> Unit = {},
)

/**
 * 不带状态的主页面,便于测试。改动当场生效(测试通过就存下服务商,用途选完就用),
 * 所以设置里进来只有返回;上手时底部留「下一步」,键盘弹出时藏起来,把地方让给表单。
 */
@Composable
fun ModelSetupContent(ui: ModelSetupUi, onboarding: Boolean, actions: ModelSetupActions, onBack: () -> Unit) {
    val c = MikaTheme.colors
    // 表单收起(测试通过存好了,或取消)后页面上没有要打字的地方,顺手收起键盘
    val focus = LocalFocusManager.current
    LaunchedEffect(ui.showForm) { if (!ui.showForm) focus.clearFocus() }
    val onPick: (ModelRole) -> Unit = { role -> if (ui.providers.isEmpty()) actions.openForm() else actions.pickRole(role) }
    PageBackground {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).windowInsetsPadding(WindowInsets.ime)) {
            TopBar(if (onboarding) ModelSetupCopy.TITLE else ModelsCopy.TITLE, MikaIcons.Back, onBack, stringResource(R.string.back))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Text(
                    if (onboarding) ModelSetupCopy.LEAD else ModelsCopy.LEAD, Modifier.padding(top = 4.dp, bottom = 12.dp),
                    fontSize = 14.5.sp, lineHeight = 23.sp, color = c.ink2,
                )
                // 上手时先连服务商再选模型;设置里先看用途
                if (onboarding) {
                    ProvidersSection(ui, actions)
                    RolesSection(ui, onPick)
                    FooterNote(ModelSetupCopy.ONBOARDING_NOTE)
                } else {
                    RolesSection(ui, onPick)
                    ProvidersSection(ui, actions)
                }
                Spacer(Modifier.height(16.dp))
            }
            // 看键盘实际占的高度;isImeVisible 在系统第一次报告前默认是 true,会把按钮误藏
            if (onboarding && WindowInsets.ime.getBottom(LocalDensity.current) == 0) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
                    ProgressDots(Onboarding.DOTS, Onboarding.dot(OnboardingStep.Model), Modifier.padding(bottom = 14.dp))
                    MikaButton(ModelSetupCopy.NEXT, actions.next, Modifier.fillMaxWidth().testTag("setup-next"), enabled = ui.canNext)
                }
            }
        }
    }
}

/** 服务商:每家一行(写明在给哪些用途干活),加「添加服务商」;没有服务商或点了添加时,表单就摊在这里。 */
@Composable
private fun ProvidersSection(ui: ModelSetupUi, actions: ModelSetupActions) {
    val c = MikaTheme.colors
    if (ui.providers.isNotEmpty()) {
        GroupLabel(ModelsCopy.PROVIDERS)
        GroupCard {
            ui.providers.forEachIndexed { i, p ->
                val line = if (p.kind == ProviderConfig.Kind.ChatGpt && ui.chatGptAccount?.signedIn != true) ModelsCopy.CHATGPT_SIGNED_OUT
                    else ModelsCopy.providerLine(p, ui.listing[p.id], ui.rolesOf(p))
                val warning = ModelsCopy.UNVERIFIED.takeIf { ui.unverified(p).isNotEmpty() }
                ProviderRow(p, line, first = i == 0, warning = warning) { actions.openProvider(p.id) }
            }
            if (!ui.showForm) {
                ListRow(
                    MikaIcons.Plus, ModelsCopy.ADD_PROVIDER, titleColor = c.accent, tone = TileTone.Accent,
                    onClick = actions.openForm,
                )
            }
        }
    }
    ui.probeNote?.let { FooterNote(it, Modifier.testTag("setup-probe-note"), color = c.warn) }
    // 刚存下的那家一个模型都没试通过:直接去选主模型(选的时候先试调用)
    if (ui.pickToProbe != null && ui.roles.main == null) {
        Text(
            ModelSetupCopy.PICK_MAIN,
            Modifier.padding(start = 16.dp, bottom = 12.dp).clickable { actions.pickRole(ModelRole.Main) }.testTag("setup-pick-main").padding(vertical = 4.dp),
            fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.accent,
        )
    }
    if (ui.showForm) AddProviderForm(ui, actions)
    FooterNote(ModelsCopy.KEY_NOTE)
}

/** 添加服务商:预设胶囊 → (自定义时)接口类型和地址 → API Key →「测试并保存」。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddProviderForm(ui: ModelSetupUi, actions: ModelSetupActions) {
    val c = MikaTheme.colors
    val f = ui.form
    var showKey by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GroupLabel(ModelsCopy.ADD_PROVIDER, Modifier.weight(1f))
        if (ui.providers.isNotEmpty()) {
            Text(
                ModelSetupCopy.CANCEL, Modifier.clickable(onClick = actions.closeForm).padding(horizontal = 4.dp, vertical = 6.dp),
                fontSize = 13.sp, color = c.accent,
            )
        }
    }
    if (ui.chatGptAccount?.signedIn != true) ChatGptCard(ui, actions)
    FlowRow(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ProviderPresets.all.forEach { p ->
            // 「自定义」按界面语言显示;服务商的名字原样
            SelectChip(if (p == ProviderPresets.custom) stringResource(R.string.preset_custom) else p.label, f.preset == p.label, { actions.selectPreset(p.label) }, Modifier.testTag("preset-${p.label}"))
        }
    }
    GroupCard {
        if (f.isCustom) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(ModelSetupCopy.KIND, Modifier.padding(end = 12.dp), style = MikaType.Body, color = c.ink)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProviderConfig.Kind.withApiKey.forEach { k -> SelectChip(ModelsCopy.kindLabel(k), f.kind == k, { actions.setKind(k) }, small = true) }
                }
            }
        }
        if (f.urlEditable) {
            InputRow(
                ModelSetupCopy.BASE_URL, f.baseUrl, actions.setBaseUrl, "https://…", first = !f.isCustom,
                keyboard = KeyboardType.Uri, modifier = Modifier.testTag("setup-base-url"),
            )
        }
        InputRow(
            ModelSetupCopy.API_KEY, f.apiKey, actions.setApiKey,
            if (f.keyRequired) ModelSetupCopy.KEY_HINT else ModelSetupCopy.KEY_OPTIONAL, first = !f.urlEditable,
            keyboard = KeyboardType.Password, secret = !showKey, modifier = Modifier.testTag("setup-api-key"),
        ) {
            Text(if (showKey) stringResource(R.string.hide) else stringResource(R.string.show), Modifier.clickable { showKey = !showKey }, fontSize = 13.sp, color = c.accent)
        }
        if (f.noList) {
            InputRow(
                ModelSetupCopy.MODEL_NAME, f.model, actions.setModel, ModelSetupCopy.modelExample(f.exampleKind),
                keyboard = KeyboardType.Uri, modifier = Modifier.testTag("setup-model"),
            )
        }
    }
    f.urlProblem?.takeIf { f.urlEditable && f.baseUrl.isNotBlank() }?.let { FooterNote(it, color = c.bad) }
    f.suggestedPreset?.let { preset -> PresetSuggestion(f, preset.label) { actions.selectPreset(preset.label) } }
    if (f.noList) FooterNote(if (f.isCustom) ModelSetupCopy.NO_LIST_CUSTOM else ModelSetupCopy.NO_LIST_PRESET, Modifier.testTag("setup-no-list"))
    if (f.isSelfHosted) FooterNote(ModelSetupCopy.SELF_HOSTED_NOTE, Modifier.testTag("setup-self-hosted"))
    f.unencryptedWarning?.let { FooterNote(it, Modifier.testTag("setup-url-unencrypted"), color = c.warn) }
    MikaButton(
        if (f.test == ListState.Running) ModelSetupCopy.TESTING else ModelSetupCopy.TEST_AND_SAVE,
        actions.testAndSave, Modifier.fillMaxWidth().padding(top = 2.dp).testTag("setup-test"),
        kind = ButtonKind.Secondary, size = ButtonSize.Medium, enabled = f.canTest,
    )
    (f.test as? ListState.Failed)?.let { failed ->
        Text(
            ModelSetupCopy.failed(failed.message), Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).testTag("setup-test-failed"),
            fontSize = 13.sp, lineHeight = 18.sp, color = c.bad,
        )
    }
    FooterNote(ModelSetupCopy.TEST_NOTE, Modifier.padding(top = 6.dp))
}

/** 自定义填的是某家的官方地址:建议改用那家的预设(不拦着),接口类型对不上时一并说;点一下就换过去,Key 留着。 */
@Composable
private fun PresetSuggestion(f: AddForm, label: String, onUse: () -> Unit) {
    val c = MikaTheme.colors
    FooterNote(ModelSetupCopy.presetSuggestion(f), Modifier.padding(top = 2.dp).testTag("setup-preset-hint"), color = c.warn)
    Text(
        ModelSetupCopy.usePreset(label),
        Modifier.padding(start = 16.dp, bottom = 12.dp).clickable(onClick = onUse).testTag("setup-use-preset").padding(vertical = 4.dp),
        fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.accent,
    )
}

/**
 * 「用你的 ChatGPT 套餐」:不填 Key,点「使用 ChatGPT 继续」去浏览器登录。登录中可以取消;失败说原因。
 * 文字照 OpenAI 对这项功能的界面要求(标题、说明、按钮),并写明哪些套餐可用、Mika 不另收费。
 */
@Composable
private fun ChatGptCard(ui: ModelSetupUi, actions: ModelSetupActions) {
    val c = MikaTheme.colors
    val running = ui.chatGptSignIn == ListState.Running
    GroupCard {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(ModelsCopy.CHATGPT_TITLE, style = MikaType.Body, fontWeight = FontWeight.SemiBold, color = c.ink)
            Text(ModelsCopy.CHATGPT_BODY, Modifier.padding(top = 4.dp, bottom = 12.dp), fontSize = 13.sp, lineHeight = 19.sp, color = c.ink2)
            MikaButton(
                ModelsCopy.CHATGPT_CONTINUE, actions.signInWithChatGpt, Modifier.fillMaxWidth().testTag("chatgpt-continue"),
                size = ButtonSize.Medium, enabled = !running,
            )
            if (running) {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(ModelsCopy.CHATGPT_WAITING, Modifier.weight(1f), fontSize = 13.sp, lineHeight = 18.sp, color = c.ink2)
                    Text(
                        ModelSetupCopy.CANCEL, Modifier.clickable(onClick = actions.cancelChatGpt).padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                        fontSize = 13.sp, color = c.accent,
                    )
                }
            }
            (ui.chatGptSignIn as? ListState.Failed)?.let {
                Text(it.message, Modifier.padding(top = 10.dp).testTag("chatgpt-failed"), fontSize = 13.sp, lineHeight = 18.sp, color = c.bad)
            }
        }
    }
}

/** 第一次用 ChatGPT 登录成功后说明一次:Mika 里的用量走你的 ChatGPT 套餐。 */
@Composable
private fun ChatGptWelcome(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ModelsCopy.CHATGPT_WELCOME_TITLE) },
        text = { Text(ModelsCopy.CHATGPT_WELCOME_BODY) },
        confirmButton = { TextButton(onDismiss) { Text(ModelsCopy.CHATGPT_GOT_IT) } },
    )
}

/**
 * 登录要用的系统能力:授权页用系统浏览器打开;回调页上放一个回到 Mika 的链接(intent 链接,点了由浏览器打开 Mika);
 * 登录完(成功或失败)把 Mika 带回前台。安卓限制后台打开界面,带不回来时用户点回调页的链接或从最近任务回来。
 */
private class AndroidChatGptBrowser(private val context: Context) : ChatGptBrowser {
    override fun open(url: String) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, tr(R.string.chatgpt_no_browser), Toast.LENGTH_SHORT).show()
        }
    }

    override fun returnToApp() {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
    }

    override fun page(params: Map<String, String>): String {
        val text = tr(if (params["error"] != null) R.string.chatgpt_page_cancelled else R.string.chatgpt_page_done)
        val back = "intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=${context.packageName};end"
        return ChatGptPage.html(tr(R.string.chatgpt_page_title), text, tr(R.string.chatgpt_page_back), back)
    }
}

/** 回调后浏览器里那一页:一句话加一个回到 App 的按钮。文字转义后放进去。 */
internal object ChatGptPage {
    fun html(title: String, text: String, back: String, backUrl: String): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">""" +
            """<title>${esc(title)}</title><style>body{font-family:sans-serif;margin:0;padding:48px 24px;text-align:center;color:#1d1d1f;background:#fff}""" +
            """p{font-size:17px;line-height:1.5}a{display:inline-block;margin-top:16px;padding:12px 28px;border-radius:999px;background:#1d1d1f;""" +
            """color:#fff;text-decoration:none;font-size:16px}</style></head><body><p>${esc(text)}</p><a href="${esc(backUrl)}">${esc(back)}</a></body></html>"""
    }
}

/** 用途:主模型、看图模型、轻量模型各一张卡,点开是选择页。 */
@Composable
private fun RolesSection(ui: ModelSetupUi, onPick: (ModelRole) -> Unit) {
    GroupLabel(ModelsCopy.ROLES)
    GroupCard {
        ModelRole.entries.forEachIndexed { i, role ->
            RoleCard(ModelsCopy.roleIcon(role), ModelsCopy.roleLabel(role), ModelsCopy.roleLine(ui, role), first = i == 0, tag = "role-${role.name.lowercase()}") {
                onPick(role)
            }
        }
    }
}

/** 模型的能力标签;[accent] 为假时一律中性色(列表行里强调色留给「在用」的胶囊)。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Tags(o: ModelOption, forVision: Boolean = false, accent: Boolean = true) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ModelSetupCopy.tags(o, forVision).forEach { (text, strong) -> CapabilityTag(text, if (strong && accent) TagTone.Accent else TagTone.Neutral) }
    }
}

internal val ModelIdStyle = MikaType.Body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)

/** 配置模型页(上手时)和添加服务商表单的文字(纯函数,便于测试)。用途的说法在 [ModelsCopy]。 */
object ModelSetupCopy {
    val TITLE: String get() = tr(R.string.model_setup_title)
    val LEAD: String get() = tr(R.string.model_setup_lead)
    val ONBOARDING_NOTE: String get() = tr(R.string.model_setup_onboarding_note)
    val CANCEL: String get() = tr(R.string.cancel)
    val KIND: String get() = tr(R.string.model_kind)
    val BASE_URL: String get() = tr(R.string.model_base_url)
    const val API_KEY = "API Key"
    val KEY_HINT: String get() = tr(R.string.model_key_hint)
    val KEY_OPTIONAL: String get() = tr(R.string.model_key_optional)
    val TEST_AND_SAVE: String get() = tr(R.string.model_test_save)
    val TESTING: String get() = tr(R.string.model_testing)
    val PICK_MAIN: String get() = tr(R.string.model_pick_main)
    val TEST_NOTE: String get() = tr(R.string.model_test_note)
    val SELF_HOSTED_NOTE: String get() = tr(R.string.model_self_hosted_note)
    val MODEL_NAME: String get() = tr(R.string.model_manual_name)
    val NO_LIST_CUSTOM: String get() = tr(R.string.model_no_list_custom)
    val NO_LIST_PRESET: String get() = tr(R.string.model_no_list_preset)

    /** 手填模型名时举的例子,按接口类型(各家模型名的样子不同)。 */
    fun modelExample(kind: ProviderConfig.Kind): String = tr(
        when (kind) {
            ProviderConfig.Kind.Gemini -> R.string.model_example_gemini
            ProviderConfig.Kind.Anthropic -> R.string.model_example_anthropic
            ProviderConfig.Kind.OpenAiResponses, ProviderConfig.Kind.ChatGpt -> R.string.model_example_openai
            ProviderConfig.Kind.OpenAiCompat -> R.string.model_example_compat
        },
    )

    /** 自定义地址是某家官方地址时的建议;接口类型和那家对不上时补一句。 */
    fun presetSuggestion(f: AddForm): String {
        val preset = f.suggestedPreset ?: return ""
        val match = tr(R.string.model_preset_match, preset.label)
        return if (!f.presetKindMismatch) match
        else match + tr(R.string.sentence_sep) + tr(R.string.model_preset_kind_mismatch, preset.label, ModelsCopy.kindLabel(preset.kind), ModelsCopy.kindLabel(f.kind))
    }

    fun usePreset(label: String) = tr(R.string.model_use_preset, label)
    val NEXT: String get() = tr(R.string.next)

    fun failed(reason: String) = tr(R.string.model_failed, reason)

    /** 模型的能力标签:(文字, 是否强调色)。接口没给、按预设推断的标「未确认」。只作参考,不拦选择。 */
    fun tags(o: ModelOption, forVision: Boolean = false): List<Pair<String, Boolean>> = buildList {
        val caps = o.capabilities
        if (forVision) {
            add((if (o.visionKnown) tr(R.string.cap_vision) else tr(R.string.cap_vision_unconfirmed)) to false)
        } else {
            when {
                !caps.toolCalls -> add(tr(R.string.cap_no_tools) to false)
                o.toolCallsKnown -> add(tr(R.string.cap_tools) to true)
                else -> add(tr(R.string.cap_tools_unconfirmed) to false)
            }
            if (caps.vision && o.visionKnown) add(tr(R.string.cap_vision) to false)
        }
        if (caps.reasoning) add(tr(R.string.cap_reasoning) to false)
        if (o.contextKnown) add(ModelOptions.contextLabel(caps.contextTokens) to false)
    }
}
