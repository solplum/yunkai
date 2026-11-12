package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.ui.components.keepsKeyboard
import com.wangzan101.mika.ui.components.KeyboardDismissArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.core.chatgpt.SignInFailure
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.ModelOption
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.Hairline
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.PillTone
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.SearchField
import com.wangzan101.mika.ui.components.StatusPill
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 设置 › 模型:和上手时的配置模型是同一页([ModelSetupScreen]),不在引导流程里。 */
@Composable
fun ModelsScreen(store: ProviderStore, onBack: () -> Unit) {
    ModelSetupScreen(store, onboarding = false, onBack = onBack, onDone = onBack)
}

/** 子页面共用的骨架:顶栏 + 可滚动内容。 */
@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    PageBackground {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).windowInsetsPadding(WindowInsets.ime)) {
            TopBar(title, MikaIcons.Back, onBack, stringResource(R.string.back))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                content()
            }
        }
    }
}

/** 用途卡:大图标块、「名字 · 做什么」、选了什么(模型名用等宽字)、说明。 */
@Composable
internal fun RoleCard(icon: ImageVector, label: String, line: ModelsCopy.RoleLine, first: Boolean = false, tag: String, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Column {
        if (!first) Hairline(start = 0.dp)
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(tag).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(40.dp).background(c.neutralSoft, RoleTileShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(21.dp), tint = c.ink)
            }
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 12.5.sp, lineHeight = 17.sp, color = c.ink3)
                Text(
                    line.title, Modifier.padding(top = 1.dp), fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = if (line.isModel) FontFamily.Monospace else null, color = if (line.unset) c.ink3 else c.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(line.subtitle, Modifier.padding(top = 1.dp), style = MikaType.Caption, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            RowChevron()
        }
    }
}

private val RoleTileShape = RoundedCornerShape(12.dp)

/** 服务商一行:左边是名字首字的图标块;[warning] 是要提醒的一句(用提醒色另起一行)。 */
@Composable
internal fun ProviderRow(p: Provider, line: String, first: Boolean, warning: String? = null, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Column {
        if (!first) Hairline()
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("provider-${p.name}").padding(horizontal = 16.dp).padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(MikaSize.Tile).background(c.neutralSoft, MikaShapes.Tile), contentAlignment = Alignment.Center) {
                Text(p.name.take(1).uppercase(), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.ink)
            }
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MikaType.Body, color = c.ink, maxLines = 1)
                Text(line, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink3)
                warning?.let { Text(it, Modifier.padding(top = 2.dp).testTag("provider-warning-${p.name}"), style = MikaType.Caption, color = c.warn) }
            }
            RowChevron()
        }
    }
}

// ---------- 选某个用途用哪个模型 ----------

class RolePickerActions(
    /** (服务商 id, 模型名) */
    val pick: (String, String) -> Unit = { _, _ -> },
    /** 不提供列表的服务商手填的模型名(服务商 id, 模型名):先试调用,通了才选。 */
    val pickManual: (String, String) -> Unit = { _, _ -> },
    /** 看图、轻量的固定选项(用主模型、不用)。 */
    val choose: (RoleChoice) -> Unit = {},
    val addProvider: () -> Unit = {},
)

/** 选择页顶上的固定选项:看图「用主模型」「不用」,轻量「用主模型」;主模型没有。 */
internal fun fixedChoices(role: ModelRole): List<RoleChoice> = when (role) {
    ModelRole.Main -> emptyList()
    ModelRole.Vision -> listOf(RoleChoice.SameAsMain, RoleChoice.Off)
    ModelRole.Light -> listOf(RoleChoice.SameAsMain)
}

/**
 * 选用途:看图、轻量顶上是固定选项;下面按服务商分组列出所有已连接服务商的全部模型(用记住的列表),
 * 标签只作参考,不按能力过滤或置灰;服务不提供列表的那家可以手动填模型名。点一下就生效并返回。
 */
@Composable
fun RolePickerContent(ui: ModelSetupUi, role: ModelRole, actions: RolePickerActions, onBack: () -> Unit) {
    val c = MikaTheme.colors
    var filter by rememberSaveable { mutableStateOf("") }
    val groups = ui.providers.map { it to ui.options(it) }
    val total = groups.sumOf { it.second.size }
    val q = filter.trim()
    Page(ModelsCopy.roleName(role), onBack) {
        Text(ModelsCopy.roleHint(role), Modifier.padding(top = 4.dp, bottom = 12.dp), fontSize = 14.5.sp, lineHeight = 22.sp, color = c.ink2)
        val fixed = fixedChoices(role)
        if (fixed.isNotEmpty()) {
            GroupCard {
                fixed.forEachIndexed { i, choice ->
                    RadioRow(
                        ModelsCopy.choiceTitle(choice), selected = ui.roles[role] == choice, onClick = { actions.choose(choice) },
                        subtitle = ModelsCopy.choiceNote(role, choice), first = i == 0,
                    )
                }
            }
        }
        if (total > FILTER_FROM) {
            GroupLabel(ModelsCopy.PICK_FROM, Modifier.padding(top = 8.dp))
            Box(Modifier.padding(bottom = 4.dp)) {
                SearchField(filter, { filter = it }, ModelsCopy.FILTER_HINT, Modifier.testTag("model-filter"))
            }
        }
        var anyShown = false
        groups.forEach { (p, options) ->
            val shown = options.filter { q.isEmpty() || it.id.contains(q, ignoreCase = true) }
            val manual = p.models?.isEmpty() == true
            if (q.isNotEmpty() && shown.isEmpty()) return@forEach
            anyShown = true
            val state = ui.listing[p.id]
            GroupLabel(p.name)
            if (shown.isNotEmpty() || manual) {
                GroupCard {
                    shown.take(PICK_AT_MOST).forEachIndexed { i, o ->
                        RadioRow(
                            o.id, selected = ui.picked(role, p.id, o.id), onClick = { actions.pick(p.id, o.id) }, first = i == 0,
                            titleStyle = ModelIdStyle, below = { Tags(o, forVision = role == ModelRole.Vision) },
                        )
                    }
                    if (manual) ManualModel(p, ui.manualPick[p.id], first = shown.isEmpty()) { actions.pickManual(p.id, it) }
                }
            }
            val manualState = ui.manualPick[p.id]
            when {
                manualState is ListState.Failed -> FooterNote(manualState.message, Modifier.testTag("manual-model-failed"), color = c.bad)
                // 选列表里的模型也可能先试调用(见 ModelSetupUi.probeBeforePick);手填的那一行自己显示进度
                !manual && manualState == ListState.Running -> FooterNote(ModelSetupCopy.TESTING, Modifier.testTag("pick-probe-running"))
                state == ListState.Running -> FooterNote(ModelsCopy.LISTING)
                state is ListState.Failed -> FooterNote(ModelSetupCopy.failed(state.message), color = c.bad)
                shown.size > PICK_AT_MOST -> FooterNote(ModelsCopy.more(shown.size - PICK_AT_MOST))
            }
        }
        if (q.isNotEmpty() && !anyShown) FooterNote(ModelsCopy.NO_MATCH)
        if (ui.providers.isEmpty()) FooterNote(ModelsCopy.ROLE_EMPTY)
        GroupCard(Modifier.padding(top = 8.dp)) {
            ListRow(MikaIcons.Plus, ModelsCopy.ADD_PROVIDER, first = true, titleColor = c.accent, tone = TileTone.Accent, onClick = actions.addProvider)
        }
        FooterNote(ModelsCopy.PICKER_NOTE)
    }
}

/** 服务不提供模型列表时:照服务商文档填模型名;点「使用」先试调用一次([state] 是进度),通了才选上。 */
@Composable
private fun ManualModel(p: Provider, state: ListState?, first: Boolean, onUse: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val running = state == ListState.Running
    val enabled = name.isNotBlank() && !running
    val example = ModelSetupCopy.modelExample(ProviderPresets.forHost(p.baseUrl)?.kind ?: p.kind)
    InputRow(ModelsCopy.MODEL_NAME, name, { name = it }, example, first = first, modifier = Modifier.testTag("manual-model")) {
        Text(
            if (running) ModelSetupCopy.TESTING else ModelsCopy.USE, Modifier.clickable(enabled = enabled) { onUse(name.trim()) }.testTag("manual-model-use"),
            fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (enabled) MikaTheme.colors.accent else MikaTheme.colors.ink3,
        )
    }
}

private const val FILTER_FROM = 8
private const val PICK_AT_MOST = 80

/** 服务商详情里最多放几个模型;其余进「全部模型」页。 */
internal const val MODEL_PREVIEW = 3

// ---------- 一个服务商 ----------

class ProviderDetailActions(
    val refresh: () -> Unit = {},
    val changeKey: (String) -> Unit = {},
    val remove: () -> Unit = {},
    /** 把这家的一个模型用作某个用途(「用作」面板里点的)。 */
    val use: (ModelRole, String) -> Unit = { _, _ -> },
    val showAll: () -> Unit = {},
    /** 在用、没核实过的手填模型再试调用一次(见 [ModelSetupUi.unverified])。 */
    val retest: () -> Unit = {},
    /** ChatGPT 服务商:再登录、退出登录、打开 ChatGPT 的用量页。 */
    val signIn: () -> Unit = {},
    val signOut: () -> Unit = {},
    val manageUsage: () -> Unit = {},
)

/**
 * 一个服务商:连接(状态、接口、地址、Key 可更换)、几个模型(在用的在前,最多 [MODEL_PREVIEW] 个,其余点「查看全部」)、删除。
 * 点一个模型从底部弹出「用作」。页面长度基本固定,模型再多也不会把删除挤到很远。
 */
@Composable
fun ProviderDetailContent(ui: ModelSetupUi, p: Provider, actions: ProviderDetailActions, onBack: () -> Unit) {
    val c = MikaTheme.colors
    var changingKey by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var using by remember { mutableStateOf<ModelOption?>(null) }
    val state = ui.listing[p.id]
    val list = ui.sortedOptions(p)
    Page(p.name, onBack) {
        GroupLabel(ModelsCopy.CONNECTION)
        if (p.kind == ProviderConfig.Kind.ChatGpt) {
            ChatGptConnection(ui, state, actions)
        } else {
            GroupCard {
                ListRow(null, ModelsCopy.STATUS, first = true) {
                    when (state) {
                        ListState.Running -> RowValue(ModelsCopy.LISTING)
                        is ListState.Failed -> RowValue(ModelsCopy.LIST_FAILED, ValueTone.Bad)
                        else -> RowValue(ModelsCopy.CONNECTED, ValueTone.Ok)
                    }
                }
                ListRow(null, ModelsCopy.KIND) { RowValue(ModelsCopy.kindLabel(p.kind)) }
                ListRow(null, ModelsCopy.BASE_URL) { RowValue(ModelsCopy.shortUrl(p.baseUrl)) }
                ListRow(null, ModelsCopy.API_KEY, onClick = { changingKey = true }) {
                    RowValue(if (p.id in ui.keyed) ModelsCopy.KEY_SAVED else ModelsCopy.KEY_NONE)
                    RowValue(ModelsCopy.CHANGE, ValueTone.Link)
                }
            }
        }
        (state as? ListState.Failed)?.let { FooterNote(ModelSetupCopy.failed(it.message), color = c.bad) }

        GroupLabel(ModelsCopy.MODELS)
        if (list.isNotEmpty()) {
            GroupCard {
                list.take(MODEL_PREVIEW).forEachIndexed { i, o -> ModelRow(o, ui.rolesOf(p.id, o.id), first = i == 0) { using = o } }
                if (list.size > MODEL_PREVIEW) {
                    ListRow(null, ModelsCopy.viewAll(list.size), titleColor = c.accent, onClick = actions.showAll) { RowChevron() }
                }
            }
            FooterNote(ModelsCopy.MODELS_NOTE)
        }
        val unverified = ui.unverified(p)
        if (unverified.isNotEmpty()) {
            val retest = ui.retest[p.id]
            FooterNote(ModelsCopy.unverifiedNote(unverified), Modifier.testTag("provider-unverified"), color = c.warn)
            MikaButton(
                if (retest == ListState.Running) ModelSetupCopy.TESTING else ModelsCopy.RETEST, actions.retest,
                Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("provider-retest"),
                kind = ButtonKind.Secondary, size = ButtonSize.Medium, enabled = retest != ListState.Running,
            )
            (retest as? ListState.Failed)?.let { FooterNote(it.message, Modifier.testTag("provider-retest-failed"), color = c.bad) }
        }
        when {
            p.models?.isEmpty() == true -> FooterNote(ModelsCopy.NO_LIST_NOTE)
            p.models == null && list.isEmpty() && state != ListState.Running -> FooterNote(ModelsCopy.NOT_LISTED)
        }

        GroupCard(Modifier.padding(top = 8.dp)) {
            ListRow(MikaIcons.Trash, ModelsCopy.DELETE, titleColor = c.bad, tone = TileTone.Bad, first = true, onClick = { confirmDelete = true })
        }
    }

    using?.let { o -> UseAsSheet(ui, p, o.id, onUse = { role -> actions.use(role, o.id) }, onDismiss = { using = null }) }
    if (changingKey) {
        var key by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { changingKey = false },
            title = { Text(ModelsCopy.CHANGE_KEY) },
            text = {
                KeyboardDismissArea {
                    Box(Modifier.fillMaxWidth().keepsKeyboard().background(c.fill, MikaShapes.Button).padding(12.dp)) {
                        if (key.isEmpty()) Text(ModelsCopy.NEW_KEY_HINT, style = MikaType.Body, color = c.ink3)
                        BasicTextField(
                            key, { key = it }, Modifier.fillMaxWidth().testTag("new-key"), singleLine = true,
                            textStyle = MikaType.Body.copy(color = c.ink), cursorBrush = SolidColor(c.accent),
                            visualTransformation = PasswordVisualTransformation(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton({
                    changingKey = false
                    if (key.isNotBlank()) actions.changeKey(key)
                }) { Text(ModelsCopy.SAVE) }
            },
            dismissButton = { TextButton({ changingKey = false }) { Text(ModelSetupCopy.CANCEL) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(ModelsCopy.deleteTitle(p.name)) },
            text = { Text(ModelsCopy.deleteBody(ui.rolesOf(p))) },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    actions.remove()
                }, Modifier.testTag("confirm-delete")) { Text(ModelsCopy.DELETE_CONFIRM, color = c.bad) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text(ModelSetupCopy.CANCEL) } },
        )
    }
}

/**
 * ChatGPT 的连接:账号(邮箱)、退出登录或再登录、管理用量(打开 ChatGPT 的用量页)。
 * 退出登录只清这台手机上的令牌,服务商、模型和用途都留着。
 */
@Composable
private fun ChatGptConnection(ui: ModelSetupUi, state: ListState?, actions: ProviderDetailActions) {
    val c = MikaTheme.colors
    val account = ui.chatGptAccount
    val signedIn = account?.signedIn == true
    GroupCard {
        ListRow(null, ModelsCopy.STATUS, first = true) {
            when {
                !signedIn -> RowValue(ModelsCopy.CHATGPT_SIGNED_OUT, ValueTone.Warn)
                state == ListState.Running -> RowValue(ModelsCopy.LISTING)
                state is ListState.Failed -> RowValue(ModelsCopy.LIST_FAILED, ValueTone.Bad)
                else -> RowValue(ModelsCopy.CHATGPT_SIGNED_IN, ValueTone.Ok)
            }
        }
        account?.email?.takeIf { it.isNotEmpty() }?.let { email -> ListRow(null, ModelsCopy.CHATGPT_ACCOUNT) { RowValue(email) } }
        ListRow(null, ModelsCopy.CHATGPT_MANAGE_USAGE, titleColor = c.accent, onClick = actions.manageUsage) { RowChevron() }
        if (signedIn) {
            ListRow(null, ModelsCopy.CHATGPT_SIGN_OUT, titleColor = c.bad, onClick = actions.signOut)
        } else {
            ListRow(
                null, if (ui.chatGptSignIn == ListState.Running) ModelsCopy.CHATGPT_WAITING else ModelsCopy.CHATGPT_CONTINUE,
                titleColor = c.accent, onClick = actions.signIn,
            )
        }
    }
    FooterNote(if (signedIn) ModelsCopy.CHATGPT_PLAN_NOTE else ModelsCopy.CHATGPT_SIGNED_OUT_NOTE)
    if (ui.chatGptRevokeUnconfirmed) FooterNote(ModelsCopy.CHATGPT_REVOKE_UNCONFIRMED, color = c.warn)
    (ui.chatGptSignIn as? ListState.Failed)?.let { FooterNote(it.message, color = c.bad) }
}

/**
 * 「<服务商> · 全部模型」:搜索框固定在顶栏下面,列表只在下面滚(模型多时按需排版);右上角刷新重新列一次。
 * 点模型同样弹出「用作」。
 */
@Composable
fun AllModelsContent(ui: ModelSetupUi, p: Provider, actions: ProviderDetailActions, onBack: () -> Unit) {
    val c = MikaTheme.colors
    var filter by rememberSaveable { mutableStateOf("") }
    var using by remember { mutableStateOf<ModelOption?>(null) }
    val state = ui.listing[p.id]
    val all = ui.sortedOptions(p)
    val q = filter.trim()
    val shown = all.filter { q.isEmpty() || it.id.contains(q, ignoreCase = true) }
    PageBackground {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).windowInsetsPadding(WindowInsets.ime)) {
            TopBar(ModelsCopy.allModelsTitle(p.name), MikaIcons.Back, onBack, stringResource(R.string.back), right = {
                BarIconButton(MikaIcons.Refresh, ModelsCopy.REFRESH, actions.refresh, enabled = state != ListState.Running)
            })
            Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp)) {
                SearchField(filter, { filter = it }, ModelsCopy.FILTER_HINT, Modifier.testTag("all-models-filter"))
            }
            when (state) {
                ListState.Running -> FooterNote(ModelsCopy.LISTING)
                is ListState.Failed -> FooterNote(ModelSetupCopy.failed(state.message), color = c.bad)
                else -> Unit
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                itemsIndexed(shown, key = { _, o -> o.id }) { i, o ->
                    Box(Modifier.fillMaxWidth().clip(rowShape(i, shown.size)).background(c.surface)) {
                        ModelRow(o, ui.rolesOf(p.id, o.id), first = i == 0) { using = o }
                    }
                }
                item {
                    FooterNote(
                        if (q.isNotEmpty() && shown.isEmpty()) ModelsCopy.NO_MATCH else ModelsCopy.allModelsNote(all.size),
                        Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
    }
    using?.let { o -> UseAsSheet(ui, p, o.id, onUse = { role -> actions.use(role, o.id) }, onDismiss = { using = null }) }
}

/** 一张卡拆成懒加载的行时,每行的圆角:首行上圆、末行下圆。 */
private fun rowShape(i: Int, n: Int): RoundedCornerShape {
    val r = MikaRadius.Card
    return RoundedCornerShape(topStart = if (i == 0) r else 0.dp, topEnd = if (i == 0) r else 0.dp,
        bottomStart = if (i == n - 1) r else 0.dp, bottomEnd = if (i == n - 1) r else 0.dp)
}

/** 模型一行:模型名(等宽)和标签,右边标着它在给哪些用途干活。 */
@Composable
private fun ModelRow(o: ModelOption, roles: List<ModelRole>, first: Boolean, onClick: () -> Unit) {
    Column {
        if (!first) Hairline(start = 16.dp)
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("model-${o.id}").padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(o.id, style = ModelIdStyle, color = MikaTheme.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.padding(top = 4.dp)) { Tags(o, accent = false) }
            }
            roles.forEach { StatusPill(ModelsCopy.roleName(it), PillTone.Accent) }
            RowChevron()
        }
    }
}

/** 点一个模型后从底部升起:「<服务商> · 用作」主模型 / 看图模型 / 轻量模型,点了马上生效。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UseAsSheet(ui: ModelSetupUi, p: Provider, model: String, onUse: (ModelRole) -> Unit, onDismiss: () -> Unit) {
    val c = MikaTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp).testTag("use-as")) {
            Text(model, Modifier.padding(horizontal = 16.dp), style = ModelIdStyle, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(ModelsCopy.useAsSubtitle(p.name), Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp), style = MikaType.Caption, color = c.ink3)
            ModelRole.entries.forEachIndexed { i, role ->
                val on = ui.picked(role, p.id, model)
                RadioRow(
                    ModelsCopy.roleName(role), selected = on, first = i == 0,
                    subtitle = if (on) ModelsCopy.IN_USE else ModelsCopy.roleWhat(role),
                    onClick = { onUse(role); onDismiss() },
                )
            }
            ListRow(null, ModelSetupCopy.CANCEL, titleColor = c.ink2, onClick = onDismiss)
        }
    }
}

/** 模型页(用途、选择页、服务商详情、全部模型)的文字(纯函数,便于测试)。三个用途的说法全 App 只在这里。 */
object ModelsCopy {
    val TITLE: String get() = tr(R.string.settings_model)
    val LEAD: String get() = tr(R.string.models_lead)
    val ROLES: String get() = tr(R.string.models_roles)
    val PROVIDERS: String get() = tr(R.string.models_providers)
    val ADD_PROVIDER: String get() = tr(R.string.models_add_provider)
    val KEY_NOTE: String get() = tr(R.string.models_key_note)
    val UNSET: String get() = tr(R.string.settings_not_set)
    val MAIN_ADD_FIRST: String get() = tr(R.string.models_main_add_first)
    val MAIN_PICK: String get() = tr(R.string.models_main_pick)
    val SAME_AS_MAIN: String get() = tr(R.string.models_same_as_main)
    val MAIN_NOT_PICKED: String get() = tr(R.string.models_main_not_picked)
    val OFF: String get() = tr(R.string.models_off)
    val OFF_NOTE: String get() = tr(R.string.models_off_note)
    val VISION_SAME_NOTE: String get() = tr(R.string.models_vision_same_note)
    val LIGHT_SAME_NOTE: String get() = tr(R.string.models_light_same_note)
    val PICK_FROM: String get() = tr(R.string.models_pick_from)
    val FILTER_HINT: String get() = tr(R.string.models_filter_hint)
    val NO_MATCH: String get() = tr(R.string.models_no_match)
    val MODEL_NAME: String get() = tr(R.string.models_model_name)
    val USE: String get() = tr(R.string.models_use)
    val ROLE_EMPTY: String get() = tr(R.string.models_role_empty)
    val PICKER_NOTE: String get() = tr(R.string.models_picker_note)
    val LISTING: String get() = tr(R.string.models_listing)
    val CONNECTION: String get() = tr(R.string.models_connection)
    val STATUS: String get() = tr(R.string.models_status)
    val CONNECTED: String get() = tr(R.string.phone_connected)
    val LIST_FAILED: String get() = tr(R.string.models_list_failed)
    val KIND: String get() = tr(R.string.model_kind)
    val BASE_URL: String get() = tr(R.string.models_url)
    const val API_KEY = "API Key"
    val KEY_SAVED: String get() = tr(R.string.models_key_saved)
    val KEY_NONE: String get() = tr(R.string.models_key_none)
    val CHANGE: String get() = tr(R.string.models_change)
    val CHANGE_KEY: String get() = tr(R.string.models_change_key)
    val NEW_KEY_HINT: String get() = tr(R.string.models_new_key_hint)
    val SAVE: String get() = tr(R.string.save)
    val MODELS: String get() = tr(R.string.models_models)
    val MODELS_NOTE: String get() = tr(R.string.models_models_note)
    val NO_LIST_NOTE: String get() = tr(R.string.models_no_list_note)
    val UNVERIFIED: String get() = tr(R.string.models_unverified)
    val RETEST: String get() = tr(R.string.models_retest)

    fun unverifiedNote(models: List<String>) = tr(R.string.models_unverified_note, models.joinToString(tr(R.string.list_sep)))
    val NOT_LISTED: String get() = tr(R.string.models_not_listed)
    val REFRESH: String get() = tr(R.string.refresh)
    val IN_USE: String get() = tr(R.string.models_in_use)
    val DELETE: String get() = tr(R.string.models_delete)
    val DELETE_CONFIRM: String get() = tr(R.string.confirm_delete)
    val CHATGPT_TITLE: String get() = tr(R.string.chatgpt_use_plan_title)
    val CHATGPT_BODY: String get() = tr(R.string.chatgpt_use_plan_body)
    val CHATGPT_CONTINUE: String get() = tr(R.string.chatgpt_continue)
    val CHATGPT_WAITING: String get() = tr(R.string.chatgpt_waiting)
    val CHATGPT_WELCOME_TITLE: String get() = tr(R.string.chatgpt_welcome_title)
    val CHATGPT_WELCOME_BODY: String get() = tr(R.string.chatgpt_welcome_body)
    val CHATGPT_GOT_IT: String get() = tr(R.string.chatgpt_got_it)
    val CHATGPT_ACCOUNT: String get() = tr(R.string.chatgpt_account)
    val CHATGPT_SIGNED_IN: String get() = tr(R.string.chatgpt_signed_in)
    val CHATGPT_SIGNED_OUT: String get() = tr(R.string.chatgpt_signed_out)
    val CHATGPT_SIGN_OUT: String get() = tr(R.string.chatgpt_sign_out)
    val CHATGPT_MANAGE_USAGE: String get() = tr(R.string.chatgpt_manage_usage)
    val CHATGPT_PLAN_NOTE: String get() = tr(R.string.chatgpt_plan_note)
    val CHATGPT_SIGNED_OUT_NOTE: String get() = tr(R.string.chatgpt_signed_out_note)
    val CHATGPT_REVOKE_UNCONFIRMED: String get() = tr(R.string.chatgpt_revoke_unconfirmed)

    /** 用 ChatGPT 登录没成的原因。 */
    fun chatGptFailure(reason: SignInFailure): String = tr(
        when (reason) {
            SignInFailure.Denied -> R.string.chatgpt_fail_denied
            SignInFailure.StateMismatch, SignInFailure.ClientMismatch -> R.string.chatgpt_fail_mismatch
            SignInFailure.NoClientId -> R.string.chatgpt_fail_no_client
            SignInFailure.TimedOut -> R.string.chatgpt_fail_timed_out
            SignInFailure.Exchange -> R.string.chatgpt_fail_exchange
            SignInFailure.IdToken -> R.string.chatgpt_fail_id_token
            SignInFailure.NoPlanScope -> R.string.chatgpt_fail_no_plan
            SignInFailure.Network -> R.string.chatgpt_fail_network
        },
    )

    /** 用途卡上的一行:[title] 是模型名([isModel])、「用主模型」「不用」或「未设置」([unset]),[subtitle] 是补充说明。 */
    data class RoleLine(val title: String, val subtitle: String, val isModel: Boolean = false, val unset: Boolean = false)

    fun roleName(role: ModelRole) = when (role) {
        ModelRole.Main -> tr(R.string.role_main)
        ModelRole.Vision -> tr(R.string.role_vision)
        ModelRole.Light -> tr(R.string.role_light)
    }

    /** 这个用途做什么。 */
    fun roleWhat(role: ModelRole) = when (role) {
        ModelRole.Main -> tr(R.string.role_main_what)
        ModelRole.Vision -> tr(R.string.role_vision_what)
        ModelRole.Light -> tr(R.string.role_light_what)
    }

    fun roleLabel(role: ModelRole) = "${roleName(role)} · ${roleWhat(role)}"

    /** 选择页顶上的说明。 */
    fun roleHint(role: ModelRole) = when (role) {
        ModelRole.Main -> tr(R.string.role_main_hint)
        ModelRole.Vision -> tr(R.string.role_vision_hint)
        ModelRole.Light -> tr(R.string.role_light_hint)
    }

    fun roleIcon(role: ModelRole): ImageVector = when (role) {
        ModelRole.Main -> MikaIcons.Spark
        ModelRole.Vision -> MikaIcons.Eye
        ModelRole.Light -> MikaIcons.Leaf
    }

    /** 用途卡上写什么:选了模型写模型名和服务商;「用主模型」写主模型是谁;「不用」写只靠文字读屏。 */
    fun roleLine(ui: ModelSetupUi, role: ModelRole): RoleLine {
        fun modelLine(cfg: ProviderConfig) = RoleLine(cfg.model, ui.providerOf(cfg)?.name ?: cfg.name, isModel = true)
        val main = ui.configFor(ModelRole.Main)
        return when (val choice = ui.roles[role]) {
            null -> RoleLine(UNSET, if (ui.providers.isEmpty()) MAIN_ADD_FIRST else MAIN_PICK, unset = true)
            RoleChoice.SameAsMain -> RoleLine(SAME_AS_MAIN, main?.model ?: MAIN_NOT_PICKED)
            RoleChoice.Off -> RoleLine(OFF, OFF_NOTE)
            is RoleChoice.Model -> ui.configs.firstOrNull { it.id == choice.configId }?.let(::modelLine)
                ?: RoleLine(UNSET, MAIN_PICK, unset = true)
        }
    }

    fun choiceTitle(choice: RoleChoice) = if (choice == RoleChoice.Off) OFF else SAME_AS_MAIN

    fun choiceNote(role: ModelRole, choice: RoleChoice) = when {
        choice == RoleChoice.Off -> OFF_NOTE
        role == ModelRole.Vision -> VISION_SAME_NOTE
        else -> LIGHT_SAME_NOTE
    }

    fun roleNames(roles: List<ModelRole>) = roles.joinToString(tr(R.string.list_sep)) { roleName(it) }

    fun deleteTitle(name: String) = tr(R.string.models_delete_title, name)

    /** 删除前说清会影响哪些用途。 */
    fun deleteBody(roles: List<ModelRole>) =
        tr(R.string.models_delete_body) + if (roles.isEmpty()) tr(R.string.models_delete_unused) else tr(R.string.models_delete_used, roleNames(roles))

    fun more(n: Int) = trPlural(R.plurals.models_more, n)

    fun viewAll(n: Int) = trPlural(R.plurals.models_view_all, n)

    fun allModelsTitle(name: String) = tr(R.string.models_all_title, name)

    fun allModelsNote(n: Int) = trPlural(R.plurals.models_all_note, n)

    fun useAsSubtitle(name: String) = tr(R.string.models_use_as, name)

    fun kindLabel(kind: ProviderConfig.Kind) = when (kind) {
        ProviderConfig.Kind.OpenAiCompat -> tr(R.string.models_openai_compat)
        ProviderConfig.Kind.Anthropic -> "Anthropic"
        ProviderConfig.Kind.Gemini -> "Gemini"
        ProviderConfig.Kind.OpenAiResponses -> "OpenAI Responses"
        ProviderConfig.Kind.ChatGpt -> "ChatGPT"
    }

    fun shortUrl(url: String) = url.removePrefix("https://").removePrefix("http://")

    /** 记住的模型数;还没列过时为空,服务不提供列表时说明。 */
    fun modelCount(p: Provider): String? = when {
        p.models == null -> null
        p.models.isEmpty() -> tr(R.string.models_no_list)
        else -> trPlural(R.plurals.models_count, p.models.count { it.usable })
    }

    /** 服务商一行的说明:已连接 · N 个模型 · 哪些用途在用;刷新失败时说明。 */
    fun providerLine(p: Provider, state: ListState?, roles: List<ModelRole>): String =
        if (state is ListState.Failed) LIST_FAILED
        else listOfNotNull(CONNECTED, modelCount(p), roles.takeIf { it.isNotEmpty() }?.let { tr(R.string.models_used_by, roleNames(it)) }).joinToString(" · ")
}
