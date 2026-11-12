package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.model.ListFailure
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelEndpoint
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.LocalHosts
import com.wangzan101.mika.settings.ModelOption
import com.wangzan101.mika.settings.ModelOptions
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderConnection
import com.wangzan101.mika.settings.ProviderPreset
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.ProbeResult
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.settings.RoleChoices
import com.wangzan101.mika.core.chatgpt.ChatGptAuth
import com.wangzan101.mika.core.chatgpt.SignInException
import com.wangzan101.mika.core.chatgpt.SignInFailure
import com.wangzan101.mika.core.chatgpt.signInWithChatGpt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 列模型(「测试并保存」或刷新一个服务商)的进度。 */
sealed interface ListState {
    data object Idle : ListState
    data object Running : ListState
    data class Failed(val message: String) : ListState
}

/** 用 ChatGPT 登录的账号:[email] 来自核对过的 ID 令牌(可能为空);退出登录后 [signedIn] 为假。 */
data class ChatGptAccount(val email: String, val signedIn: Boolean)

/**
 * 「用 ChatGPT 登录」要用到的系统能力:在浏览器里打开授权页、登录完回到 Mika、回调后浏览器里显示的网页。
 * 界面给出(要 Context);测试里不给,就不能登录。
 */
interface ChatGptBrowser {
    fun open(url: String)
    fun returnToApp()
    /** [params] 里有 `error` 表示用户在授权页取消了。 */
    fun page(params: Map<String, String>): String
}

/**
 * 「添加服务商」表单。[noList] 为真时上次测试没列出模型:要先填 [model](手填的模型名),再测试就用它试调用。
 */
data class AddForm(
    val preset: String,
    val kind: ProviderConfig.Kind,
    val baseUrl: String,
    val apiKey: String,
    val test: ListState = ListState.Idle,
    val model: String = "",
    val noList: Boolean = false,
) {
    val isCustom: Boolean get() = preset == ProviderPresets.custom.label

    /** 跑在用户自己机器上的服务(见 [ProviderPreset.selfHosted]):地址预填了本机的默认端口,要能改成那台机器的。 */
    val isSelfHosted: Boolean get() = presetFor(preset).selfHosted

    /** 地址要用户填或改:自定义,和自己跑的服务。 */
    val urlEditable: Boolean get() = isCustom || isSelfHosted

    val urlProblem: String?
        get() = when {
            baseUrl.isBlank() -> tr(R.string.model_need_url)
            !baseUrl.trim().startsWith("http://") && !baseUrl.trim().startsWith("https://") -> tr(R.string.model_url_scheme)
            else -> null
        }

    /** 明文 http 又不在本机、局域网的地址:提醒一句,不拦着(见 [LocalHosts])。 */
    val unencryptedWarning: String?
        get() = if (urlProblem == null && LocalHosts.unencryptedRemote(baseUrl)) tr(R.string.model_url_unencrypted) else null

    /** 自己跑的服务和自定义的 OpenAI 兼容接口(多半是本地服务)可以不填 Key,其余都要。 */
    val keyRequired: Boolean get() = !isSelfHosted && !(isCustom && kind == ProviderConfig.Kind.OpenAiCompat)

    val canTest: Boolean get() =
        urlProblem == null && (!keyRequired || apiKey.isNotBlank()) && test != ListState.Running && (!noList || model.isNotBlank())

    /** 自定义填的是某家服务商的官方地址(主机名对得上,见 [ProviderPresets.forHost]):建议改用那个预设。 */
    val suggestedPreset: ProviderPreset? get() = if (isCustom && urlProblem == null) ProviderPresets.forHost(baseUrl) else null

    /** 地址就是 [suggestedPreset] 的那个地址,选的接口类型却和它的不一样:照这样一定调不通。 */
    val presetKindMismatch: Boolean
        get() = suggestedPreset?.let { s -> ProviderPresets.forBaseUrl(baseUrl.trim()) == s && kind != s.kind } == true

    /** 举模型名的例子按哪一类接口:地址对得上某个预设的按那个预设,否则按选的接口类型。 */
    val exampleKind: ProviderConfig.Kind get() = suggestedPreset?.kind ?: kind

    /** 存成服务商时用的名字:预设用预设名,自定义用地址的主机名。 */
    val providerName: String get() = if (isCustom) ProviderConnection.nameForUrl(baseUrl) else preset

    /** 这家要放会话号的请求头名:选的预设声明了的用它;自定义的地址对得上某个预设也用那个预设的。 */
    val sessionHeader: String? get() = presetFor(preset).sessionHeader ?: ProviderPresets.forBaseUrl(baseUrl.trim())?.sessionHeader

    /** 这家自己的模型名里的词(见 [ProviderPreset.familyTokens]):选的预设给了的用它,否则按地址对得上的预设。 */
    val familyTokens: List<String> get() = presetFor(preset).familyTokens.ifEmpty { ModelOptions.familyOf(baseUrl) }

    companion object {
        fun of(p: ProviderPreset) = AddForm(p.label, p.kind, p.baseUrl, "")
        fun presetFor(label: String): ProviderPreset = ProviderPresets.all.firstOrNull { it.label == label } ?: ProviderPresets.custom
    }
}

/**
 * 配置模型(上手时)和设置 › 模型共用的全部状态:已连接的服务商、添加服务商表单、三个用途。
 * [providers]、[configs]、[roles] 是 [ProviderStore] 的快照,[keyed] 是存了 Key 的服务商 id;
 * [listing] 是各服务商刷新列表的进度(按服务商 id)。
 */
data class ModelSetupUi(
    val form: AddForm,
    val formOpen: Boolean = false,
    val providers: List<Provider> = emptyList(),
    val configs: List<ProviderConfig> = emptyList(),
    val roles: RoleChoices = RoleChoices(),
    val keyed: Set<String> = emptySet(),
    val listing: Map<String, ListState> = emptyMap(),
    /** ChatGPT 服务商的账号;没有这家时为 null。 */
    val chatGptAccount: ChatGptAccount? = null,
    /** 用 ChatGPT 登录的进度。 */
    val chatGptSignIn: ListState = ListState.Idle,
    /** 第一次登录成功后说明一次「在用你的 ChatGPT 套餐」,点「知道了」收起。 */
    val chatGptWelcome: Boolean = false,
    /** 退出登录时 ChatGPT 那边没确认注销(本机令牌已清)。 */
    val chatGptRevokeUnconfirmed: Boolean = false,
    /**
     * 刚存下服务商时试调用的说明(见 [ModelSetupViewModel.testAndSave]):先试的模型没通过、主模型用了哪个,
     * 或一个都没通过、要用户自己选;再测试、选了主模型时清掉。
     */
    val probeNote: String? = null,
    /**
     * 刚存下、试调用的模型一个都没通过的服务商 id:在它里面选主模型时先试调用(见 [probeBeforePick]);选好了清掉。
     */
    val pickToProbe: String? = null,
    /** 选择页里手填模型名后试调用的进度(按服务商 id)。 */
    val manualPick: Map<String, ListState> = emptyMap(),
    /** 服务商页「重新测试」的进度(按服务商 id)。 */
    val retest: Map<String, ListState> = emptyMap(),
) {
    /** 还没有服务商时表单总是展开。 */
    val showForm: Boolean get() = formOpen || providers.isEmpty()

    /** 在 [providerId] 里选 [role] 要先试调用(走 [ModelSetupViewModel.pickManual]):刚存下、没有一个模型试通过的那家选主模型。 */
    fun probeBeforePick(role: ModelRole, providerId: String): Boolean = role == ModelRole.Main && providerId == pickToProbe

    /** 选好了主模型就能「下一步」。 */
    val canNext: Boolean get() = roles.main != null

    /** 这个用途实际用的配置(「用主模型」时是主模型的)。 */
    fun configFor(role: ModelRole): ProviderConfig? = roles.resolve(role)?.let { id -> configs.firstOrNull { it.id == id } }

    fun providerOf(cfg: ProviderConfig): Provider? = providers.firstOrNull { it.id == cfg.providerId }

    /** 一个服务商可选的全部模型(记住的列表加已有配置)。 */
    fun options(p: Provider): List<ModelOption> = ModelOptions.ofProvider(p, configs)

    /** 一个服务商的全部模型,在用的排前面,其余保持原来的顺序。 */
    fun sortedOptions(p: Provider): List<ModelOption> = options(p).sortedBy { if (rolesOf(p.id, it.id).isEmpty()) 1 else 0 }

    /** 实际在用这个模型的用途(含「用主模型」跟过来的),按主模型、看图、轻量的顺序。 */
    fun rolesOf(providerId: String, model: String): List<ModelRole> =
        ModelRole.entries.filter { role -> configFor(role)?.let { it.providerId == providerId && it.model == model } == true }

    /** 实际在用这个服务商的用途。 */
    fun rolesOf(p: Provider): List<ModelRole> = ModelRole.entries.filter { configFor(it)?.providerId == p.id }

    /**
     * 不提供列表的服务商里,在用、却没试调用通过过的模型名(见 [Provider.verifiedModels]):这些是手填的,填错了任务就会失败。
     * 有列表的服务商,模型是从列表里选的,不算。
     */
    fun unverified(p: Provider): List<String> =
        if (p.kind == ProviderConfig.Kind.ChatGpt || p.models?.isEmpty() != true) emptyList()
        else roles.referenced.mapNotNull { id -> configs.firstOrNull { it.id == id && it.providerId == p.id }?.model }
            .distinct().filter { it !in p.verifiedModels }

    /** 这个用途明确选的是不是这个模型(「用主模型」不算)。 */
    fun picked(role: ModelRole, providerId: String, model: String): Boolean {
        val id = (roles[role] as? RoleChoice.Model)?.configId ?: return false
        return configs.firstOrNull { it.id == id }?.let { it.providerId == providerId && it.model == model } == true
    }
}

/**
 * 配置模型的状态与动作。添加服务商:选预设 → 填 Key →「测试并保存」(列模型,再让会当主模型的那个回一句话,都成了才存下这家和列表,不成说原因、不存);
 * 改了服务商、地址或 Key,进行中的测试作废。用途:从所有已连接服务商记住的模型里选,选中即生效;
 * 看图还可以「用主模型」「不用」,轻量可以「用主模型」。连上第一家(还没有主模型)时自动选它的一个模型为主模型。
 * 首次上手和设置 › 模型共用。
 */
class ModelSetupViewModel(
    private val store: ProviderStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val chatGptBrowser: ChatGptBrowser? = null,
) {
    private val _state = MutableStateFlow(snapshot(ModelSetupUi(AddForm.of(ProviderPresets.all.first()))))
    val state: StateFlow<ModelSetupUi> = _state.asStateFlow()

    private var testJob: Job? = null
    private var signInJob: Job? = null
    private val refreshJobs = mutableMapOf<String, Job>()

    init {
        // 别处(开发者选项等)改了存储时跟着更新
        scope.launch { merge(store.providers, store.configs, store.roles).collect { sync() } }
    }

    private fun snapshot(ui: ModelSetupUi): ModelSetupUi {
        val providers = store.providers.value
        return ui.copy(
            providers = providers, configs = store.configs.value, roles = store.roles.value,
            keyed = providers.filter { store.hasKey(it.id) }.map { it.id }.toSet(),
            chatGptAccount = store.chatGptProvider()?.let { p ->
                store.chatGptSession(p.id).let { ChatGptAccount(it?.email.orEmpty(), it?.signedIn == true) }
            },
        )
    }

    private fun sync() = _state.update(::snapshot)

    // ---------- 添加服务商 ----------

    fun openForm() = _state.update { it.copy(formOpen = true) }

    fun closeForm() {
        testJob?.cancel()
        _state.update { it.copy(formOpen = false, form = AddForm.of(ProviderPresets.all.first())) }
    }

    fun selectPreset(label: String) {
        val p = AddForm.presetFor(label)
        if (p.label == _state.value.form.preset) return
        val key = _state.value.form.apiKey
        edit { AddForm.of(p).copy(apiKey = key) }
    }

    // 换了接口类型或地址,上次「没列出模型」就不作数了,要重新列
    fun setKind(kind: ProviderConfig.Kind) = edit { it.copy(kind = kind, noList = false) }
    fun setBaseUrl(url: String) = edit { it.copy(baseUrl = url, noList = false) }
    fun setApiKey(key: String) = edit { it.copy(apiKey = key) }
    fun setModel(model: String) = edit { it.copy(model = model) }

    /** 连接信息变了:进行中的测试和上次的失败原因作废。 */
    private fun edit(change: (AddForm) -> AddForm) {
        testJob?.cancel()
        _state.update { it.copy(form = change(it.form).copy(test = ListState.Idle)) }
    }

    /**
     * 测试并保存:列得出模型,再按 [ModelOptions.candidates] 的先后让模型调用一次工具(见 [probe]),成了就存下这家服务商、
     * 拿通过的那个当主模型(还没有主模型时),表单清空收起。先试的没通过、是那个模型自己的问题(这个账号没开通或没权限、
     * 没有这个模型、不会调用工具)时,接着试下一个,至多试 [PROBE_CANDIDATES] 个:Key 已经证明是对的,
     * 通过了的当主模型并说一句先试的哪些没通过;都没通过也存下服务商、不定主模型,说明原因并请用户从列表里选
     * ([ModelSetupUi.probeNote]、[ModelSetupUi.pickToProbe],选的时候先试调用)。
     * 第一个就因为别的原因失败(Key、网络、地址、超时、服务端出错)说原因、不存。
     *
     * 没列出模型(列表接口不存在,或列出来是空的):可能这家真不提供列表,也可能地址、接口类型填错了,单看列表分不出来。
     * 所以不直接存,要用户填一个模型名([AddForm.noList]),再测试时用它试调用:通了才存成不提供列表的服务商,
     * 不通说原因、不存。Key 不对等别的列表错误照旧直接说原因。
     */
    fun testAndSave() {
        val f = _state.value.form
        if (!f.canTest) return
        testJob?.cancel()
        _state.update { it.copy(form = it.form.copy(test = ListState.Running), probeNote = null, pickToProbe = null) }
        testJob = scope.launch {
            val key = f.apiKey.trim()
            val baseUrl = f.baseUrl.trim()
            val models = when (val r = withContext(io) { store.listModels(f.kind, baseUrl, key.ifEmpty { null }, f.sessionHeader) }) {
                is ModelListResult.Ok -> r.models
                is ModelListResult.Failed -> {
                    if (r.reason != ListFailure.NotSupported) {
                        _state.update { it.copy(form = it.form.copy(test = ListState.Failed(r.message))) }
                        return@launch
                    }
                    emptyList()
                }
            }
            val typed = f.model.trim()
            if (models.isEmpty() && typed.isEmpty()) {
                _state.update { it.copy(form = it.form.copy(test = ListState.Idle, noList = true)) }
                return@launch
            }
            // 不提供列表:用手填的那个试,和有列表时分开走
            if (models.isEmpty()) {
                val caps = (ProviderPresets.forBaseUrl(baseUrl) ?: ProviderPresets.custom).capabilities
                val outcome = probeModel(f.kind, baseUrl, key.ifEmpty { null }, f.sessionHeader, typed, null, caps, f.exampleKind, listMissing = f.isCustom)
                if (outcome is ProbeOutcome.Failed) {
                    _state.update { it.copy(form = it.form.copy(test = ListState.Failed(outcome.message))) }
                    return@launch
                }
                val noTools = outcome is ProbeOutcome.NoToolCall
                withContext(io) {
                    val p = store.connect(f.providerName, f.kind, baseUrl, key, models, f.sessionHeader, setOf(typed))
                    if (!noTools && store.roles.value.main == null) use(ModelRole.Main, p, typed, ModelOptions.ofProvider(p, store.list()))
                }
                val note = if (noTools) tr(R.string.model_probe_no_tools, typed) else null
                _state.update { snapshot(it.copy(form = AddForm.of(ProviderPresets.all.first()), formOpen = false, probeNote = note)) }
                return@launch
            }
            val probed = when (val r = probe(f, baseUrl, key.ifEmpty { null }, models)) {
                is ListProbe.Blocked -> {
                    _state.update { it.copy(form = it.form.copy(test = ListState.Failed(r.message))) }
                    return@launch
                }
                is ListProbe.Done -> r
            }
            val noneUsable = probed.main == null && probed.rejected.isNotEmpty()
            val saved = withContext(io) {
                val p = store.connect(f.providerName, f.kind, baseUrl, key, models, f.sessionHeader, setOfNotNull(probed.main))
                if (!noneUsable && store.roles.value.main == null) {
                    val options = ModelOptions.ofProvider(p, store.list())
                    (probed.main ?: ModelOptions.defaultMain(options, f.familyTokens) ?: options.firstOrNull()?.id)
                        ?.let { m -> use(ModelRole.Main, p, m, options) }
                }
                p
            }
            _state.update {
                snapshot(it.copy(
                    form = AddForm.of(ProviderPresets.all.first()), formOpen = false,
                    probeNote = probeNote(probed), pickToProbe = saved.id.takeIf { noneUsable },
                ))
            }
        }
    }

    /**
     * 试调用的结论:通过、[model] 没调用工具、失败([message] 是给人看的原因)。
     * 失败是那个模型自己的问题(这个账号用不了、没有这个模型、不支持工具)时 [modelReason] 是短的原因,换一个模型可能就行;
     * [notAvailable] 为真是没开通或没权限。
     */
    private sealed interface ProbeOutcome {
        data object Passed : ProbeOutcome
        data class NoToolCall(val model: String) : ProbeOutcome
        data class Failed(val message: String, val modelReason: String? = null, val notAvailable: Boolean = false) : ProbeOutcome
    }

    /** 先试、没通过的一个模型:[reason] 是短的原因。 */
    private data class Rejected(val model: String, val reason: String, val notAvailable: Boolean)

    /** 有列表时试调用的结果:[Blocked] 是第一个就因为模型以外的原因失败,不存;[Done] 见 [probe]。 */
    private sealed interface ListProbe {
        data class Blocked(val message: String) : ListProbe
        /** [main] 是通过了的模型;[rejected] 是在它之前试过、没通过的。都为空是没有可试的模型。 */
        data class Done(val main: String?, val rejected: List<Rejected>) : ListProbe
    }

    /**
     * 列出模型后按 [ModelOptions.candidates] 的先后(没有就第一个)让模型调用一次工具(见 [probeModel]),至多试
     * [PROBE_CANDIDATES] 个:通过就停;没通过、是那个模型自己的问题就试下一个。第一个因为别的原因失败为 [ListProbe.Blocked];
     * 之后的因为别的原因失败就不再试(Key 已证明是对的),和都没通过一样处理。列表里一个能用的也没有时算通过、不试。
     */
    private suspend fun probe(f: AddForm, baseUrl: String, key: String?, models: List<ListedModel>): ListProbe {
        // 用 ChatGPT 登录不走这张表单(登录本身就核对过了);表单只给 Key 的接口类型,这里再挡一次
        if (f.kind == ProviderConfig.Kind.ChatGpt) return ListProbe.Done(null, emptyList())
        val base = (ProviderPresets.forBaseUrl(baseUrl) ?: ProviderPresets.custom).capabilities
        val options = ModelOptions.merge(models, base)
        val order = ModelOptions.candidates(options, f.familyTokens).ifEmpty { listOfNotNull(options.firstOrNull()?.id) }
        val rejected = mutableListOf<Rejected>()
        for ((i, model) in order.take(PROBE_CANDIDATES).withIndex()) {
            val caps = options.first { it.id == model }.capabilities
            val endpoint = models.firstOrNull { it.id == model }?.endpoint
            when (val o = probeModel(f.kind, baseUrl, key, f.sessionHeader, model, endpoint, caps, f.exampleKind, listMissing = false)) {
                ProbeOutcome.Passed -> return ListProbe.Done(model, rejected)
                is ProbeOutcome.NoToolCall -> rejected += Rejected(model, tr(R.string.model_reason_no_tools), notAvailable = false)
                is ProbeOutcome.Failed -> when {
                    o.modelReason != null -> rejected += Rejected(model, o.modelReason, o.notAvailable)
                    i == 0 -> return ListProbe.Blocked(o.message)
                    else -> {
                        rejected += Rejected(model, o.message, notAvailable = false)
                        break
                    }
                }
            }
        }
        return ListProbe.Done(null, rejected)
    }

    /**
     * 存下服务商后给人看的一句:先试的没通过、后面的通过了,说哪些没通过、主模型用了哪个;
     * 都没通过,说第一个为什么没通过、试过哪些,请用户从列表里选。都顺利为 null。
     */
    private fun probeNote(r: ListProbe.Done): String? {
        if (r.rejected.isEmpty()) return null
        val names = r.rejected.joinToString(tr(R.string.list_sep)) { it.model }
        val first = r.rejected.first()
        if (r.main != null) return tr(R.string.model_probe_fallback, names, first.reason, r.main)
        val head = tr(R.string.model_probe_none_usable, first.model, first.reason)
        if (r.rejected.size == 1) return head
        val tried = if (r.rejected.all { it.notAvailable }) tr(R.string.model_probe_tried_unavailable, names) else tr(R.string.model_probe_tried, names)
        return "$head\n$tried"
    }

    /**
     * 让 [model] 调用一次工具,和真实任务走同一条建模型的路(请求头、会话号都一样):Key 不对、少了服务商要的请求头、
     * 模型名不对、模型不会调用工具,这里就露出来,不等到第一个任务。超过 [PROBE_TIMEOUT_MS] 没回算失败,服务端的报错原样带上;
     * 找不到模型时说清是哪个、模型名大概长什么样([exampleKind] 那一类的例子)。[listMissing] 为真时列表也没列出来,
     * 那多半是地址或接口类型填错了,一并说。
     */
    private suspend fun probeModel(
        kind: ProviderConfig.Kind, baseUrl: String, key: String?, sessionHeader: String?,
        model: String, endpoint: ModelEndpoint?, caps: Capabilities, exampleKind: ProviderConfig.Kind, listMissing: Boolean,
    ): ProbeOutcome {
        val result = try {
            withContext(io) { withTimeout(PROBE_TIMEOUT_MS) { store.probe(kind, baseUrl, key, sessionHeader, model, endpoint, caps) } }
        } catch (e: TimeoutCancellationException) {
            return ProbeOutcome.Failed(tr(R.string.model_probe_timeout, model, (PROBE_TIMEOUT_MS / 1000).toInt()))
        }
        return when (result) {
            ProbeResult.ToolCalled -> ProbeOutcome.Passed
            ProbeResult.NoToolCall -> ProbeOutcome.NoToolCall(model)
            is ProbeResult.Failed -> when (val e = result.error) {
                is ModelError.ModelNotAvailable -> {
                    val detail = e.detail.ifEmpty { e.message.orEmpty() }
                    ProbeOutcome.Failed(
                        tr(R.string.model_probe_not_available, model, detail),
                        tr(R.string.model_reason_not_available, detail), notAvailable = true,
                    )
                }
                is ModelError.ToolsUnsupported -> ProbeOutcome.Failed(tr(R.string.model_probe_no_tools_pick, model), tr(R.string.model_reason_no_tools))
                is ModelError.ModelNotFound -> ProbeOutcome.Failed(
                    if (listMissing) tr(R.string.model_probe_not_found_no_list, model, ModelSetupCopy.modelExample(exampleKind))
                    else tr(R.string.model_probe_not_found, model, ModelSetupCopy.modelExample(exampleKind)),
                    tr(R.string.model_reason_not_found),
                )
                else -> ProbeOutcome.Failed(tr(R.string.model_probe_failed, model, e.message.orEmpty()))
            }
        }
    }

    /** 已连上的服务商里试调用 [model]:用存着的 Key、请求头,能力按已有的配置或预设。 */
    private suspend fun probeSaved(p: Provider, model: String): ProbeOutcome {
        val options = ModelOptions.ofProvider(p, store.list())
        val caps = options.firstOrNull { it.id == model }?.capabilities ?: ModelOptions.baseOf(p)
        val endpoint = p.models?.firstOrNull { it.id == model }?.endpoint
        val example = ProviderPresets.forHost(p.baseUrl)?.kind ?: p.kind
        return probeModel(p.kind, p.baseUrl, store.providerKey(p.id), p.sessionHeader, model, endpoint, caps, example, listMissing = false)
    }

    // ---------- 用 ChatGPT 登录 ----------

    /**
     * 打开浏览器登录 ChatGPT,回调后换令牌、核对,列出模型,存成(或更新)ChatGPT 服务商,回到 Mika。
     * 还没有主模型时选它的一个模型为主模型。第一次注册成功后说明一次在用套餐额度。失败说原因。
     */
    fun signInWithChatGpt() {
        val browser = chatGptBrowser ?: return
        if (_state.value.chatGptSignIn == ListState.Running) return
        _state.update { it.copy(chatGptSignIn = ListState.Running, chatGptRevokeUnconfirmed = false) }
        signInJob = scope.launch {
            val (listProblem, first) = try {
                withContext(io) {
                    val (hostId, clientId) = store.chatGptRegistration()
                    val existing = store.chatGptProvider()
                    val session = signInWithChatGpt(
                        store.chatGptAuth, hostId, clientId, existing?.let { store.chatGptSession(it.id) },
                        openBrowser = browser::open, onRegistered = store::rememberChatGptClient, page = browser::page,
                    )
                    val listed = store.listModels(ProviderConfig.Kind.ChatGpt, ChatGptAuth.API_BASE, session.accessToken)
                    val p = store.connectChatGpt(session, (listed as? ModelListResult.Ok)?.models ?: existing?.models.orEmpty())
                    if (store.roles.value.main == null) {
                        val options = ModelOptions.ofProvider(p, store.list())
                        (ModelOptions.defaultMain(options) ?: options.firstOrNull()?.id)?.let { m -> use(ModelRole.Main, p, m, options) }
                    }
                    (listed as? ModelListResult.Failed)?.message to (clientId == null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = (e as? SignInException)?.reason ?: SignInFailure.Network
                browser.returnToApp()
                _state.update { it.copy(chatGptSignIn = ListState.Failed(ModelsCopy.chatGptFailure(reason))) }
                return@launch
            }
            browser.returnToApp()
            val id = store.chatGptProvider()?.id
            _state.update {
                snapshot(it.copy(
                    chatGptSignIn = ListState.Idle, chatGptWelcome = first, formOpen = false,
                    listing = if (listProblem != null && id != null) it.listing + (id to ListState.Failed(listProblem)) else it.listing - id.orEmpty(),
                ))
            }
        }
    }

    /** 放弃正在进行的登录(本机回调随之关掉)。 */
    fun cancelChatGptSignIn() {
        signInJob?.cancel()
        _state.update { it.copy(chatGptSignIn = ListState.Idle) }
    }

    fun dismissChatGptWelcome() = _state.update { it.copy(chatGptWelcome = false) }

    /** 退出 ChatGPT 登录:服务端注销、本机清令牌;服务商和用途不动,再登录就能接着用。 */
    fun signOutChatGpt(providerId: String) {
        scope.launch {
            val revoked = withContext(io) { store.signOutChatGpt(providerId) }
            _state.update { snapshot(it.copy(chatGptRevokeUnconfirmed = !revoked, listing = it.listing - providerId)) }
        }
    }

    // ---------- 用途 ----------

    /** 把 [providerId] 下的 [model] 用作 [role](选择页和「用作」面板都走这里):没有配置的当场建一条。 */
    fun pick(role: ModelRole, providerId: String, model: String) {
        val m = model.trim()
        if (m.isEmpty()) return
        scope.launch {
            withContext(io) {
                val p = store.provider(providerId) ?: return@withContext
                use(role, p, m, ModelOptions.ofProvider(p, store.list()))
            }
            // 选好了主模型,试调用的说明就过去了
            _state.update { snapshot(if (role == ModelRole.Main) it.copy(probeNote = null, pickToProbe = null) else it) }
        }
    }

    /** 看图、轻量的固定选项:「用主模型」或(只有看图)「不用」。 */
    fun choose(role: ModelRole, choice: RoleChoice) {
        scope.launch {
            withContext(io) { store.choose(role, choice) }
            sync()
        }
    }

    /**
     * 选择页里给不提供列表的服务商手填了模型名,或在刚存下、没有一个模型试通过的那家选主模型(见 [ModelSetupUi.probeBeforePick]):
     * 先试调用一次,通了才用作 [role](记为核实过),然后调 [onPicked];不通说原因、不选。主模型还要会调用工具。
     */
    fun pickManual(role: ModelRole, providerId: String, model: String, onPicked: () -> Unit = {}) {
        val m = model.trim()
        if (m.isEmpty() || _state.value.manualPick[providerId] == ListState.Running) return
        val p = store.provider(providerId) ?: return
        _state.update { it.copy(manualPick = it.manualPick + (providerId to ListState.Running)) }
        scope.launch {
            val outcome = probeSaved(p, m)
            val problem = when {
                outcome is ProbeOutcome.Failed -> outcome.message
                outcome is ProbeOutcome.NoToolCall && role == ModelRole.Main -> tr(R.string.model_probe_no_tools_pick, m)
                else -> null
            }
            if (problem != null) {
                _state.update { it.copy(manualPick = it.manualPick + (providerId to ListState.Failed(problem))) }
                return@launch
            }
            withContext(io) {
                store.markVerified(p.id, m)
                store.provider(p.id)?.let { fresh -> use(role, fresh, m, ModelOptions.ofProvider(fresh, store.list())) }
            }
            _state.update {
                snapshot(if (role == ModelRole.Main) it.copy(manualPick = it.manualPick - providerId, probeNote = null, pickToProbe = null)
                    else it.copy(manualPick = it.manualPick - providerId))
            }
            onPicked()
        }
    }

    /** 选择页打开时,上次手填试调用的结果作废。 */
    fun clearManualPick() = _state.update { it.copy(manualPick = emptyMap()) }

    /**
     * 「重新测试」:这家在用、没核实过的模型(见 [ModelSetupUi.unverified])逐个试调用,通了记为核实过;
     * 有一个不通就停下说原因。
     */
    fun retest(providerId: String) {
        val p = store.provider(providerId) ?: return
        val models = _state.value.unverified(p)
        if (models.isEmpty() || _state.value.retest[providerId] == ListState.Running) return
        _state.update { it.copy(retest = it.retest + (providerId to ListState.Running)) }
        scope.launch {
            for (m in models) {
                val outcome = probeSaved(p, m)
                if (outcome is ProbeOutcome.Failed) {
                    _state.update { snapshot(it.copy(retest = it.retest + (providerId to ListState.Failed(outcome.message)))) }
                    return@launch
                }
                withContext(io) { store.markVerified(p.id, m) }
            }
            _state.update { snapshot(it.copy(retest = it.retest - providerId)) }
        }
    }

    private fun use(role: ModelRole, p: Provider, model: String, options: List<ModelOption>) {
        val caps = options.firstOrNull { it.id == model }?.capabilities ?: ModelOptions.baseOf(p)
        store.use(role, p.id, model, caps)
    }

    // ---------- 一个服务商 ----------

    /** 重新列一次这个服务商的模型并记下;失败时记下原因。 */
    fun refresh(providerId: String) {
        val p = store.provider(providerId) ?: return
        if (refreshJobs[providerId]?.isActive == true) return
        _state.update { it.copy(listing = it.listing + (providerId to ListState.Running)) }
        refreshJobs[providerId] = scope.launch {
            val r = withContext(io) { store.listModels(p) }
            val models: List<ListedModel>? = when (r) {
                is ModelListResult.Ok -> r.models
                is ModelListResult.Failed -> if (r.reason == ListFailure.NotSupported) emptyList() else null
            }
            if (models != null) withContext(io) { store.rememberModels(p.id, models) }
            val after = if (r is ModelListResult.Failed && models == null) ListState.Failed(r.message) else ListState.Idle
            _state.update { snapshot(it.copy(listing = it.listing + (providerId to after))) }
        }
    }

    /** 选择页、服务商页打开时:还没记住列表的服务商列一次。 */
    fun ensureListed() {
        _state.value.providers.filter { it.models == null && _state.value.listing[it.id] == null && listable(it) }.forEach { refresh(it.id) }
    }

    /** 退出了登录的 ChatGPT 列不了模型,等再登录。 */
    private fun listable(p: Provider) = p.kind != ProviderConfig.Kind.ChatGpt || store.chatGptSession(p.id)?.signedIn == true

    /** 换 Key,然后用新 Key 重新列一次模型。 */
    fun changeKey(providerId: String, apiKey: String) {
        val k = apiKey.trim()
        if (k.isEmpty()) return
        scope.launch {
            withContext(io) { store.updateProviderKey(providerId, k) }
            refreshJobs.remove(providerId)?.cancel()
            refresh(providerId)
        }
    }

    /** 删掉服务商和它的 Key;用着它的用途退回默认。 */
    fun removeProvider(providerId: String) {
        scope.launch {
            withContext(io) {
                // ChatGPT 的先在服务端注销登录,再删
                if (store.provider(providerId)?.kind == ProviderConfig.Kind.ChatGpt) store.signOutChatGpt(providerId)
                store.removeProvider(providerId)
            }
            _state.update { snapshot(it.copy(listing = it.listing - providerId)) }
        }
    }

    companion object {
        /** 「测试并保存」里试调用最多等多久。 */
        const val PROBE_TIMEOUT_MS = 20_000L

        /** 「测试并保存」有列表时至多试几个模型:先试的那个,加没通过时接着试的 2 个。 */
        const val PROBE_CANDIDATES = 3
    }
}
