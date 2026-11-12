package com.wangzan101.mika.settings

import android.content.Context
import com.wangzan101.mika.core.chatgpt.ChatGptAuth
import com.wangzan101.mika.core.chatgpt.ChatGptSession
import com.wangzan101.mika.core.chatgpt.ChatGptTokens
import com.wangzan101.mika.core.model.AnthropicModel
import com.wangzan101.mika.core.model.BearerToken
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.ClientHeaders
import com.wangzan101.mika.core.model.GeminiModel
import com.wangzan101.mika.core.model.ListFailure
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelCatalog
import com.wangzan101.mika.core.model.ModelEndpoint
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.model.ModelProtocol
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.model.OpenAiCompatModel
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.PromptCaching
import com.wangzan101.mika.core.model.ResponsesModel
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.usage.UsagePurpose
import com.wangzan101.mika.core.usage.UsageSink
import com.wangzan101.mika.core.usage.UsageSource
import com.wangzan101.mika.core.usage.metered
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 一个模型接入:属于哪个服务商([providerId])、模型名、能力。接口类型、地址和名字照抄所属服务商(建模型时用);
 * Key 存在服务商上。这是存储内部的一层:用户只选「哪个用途用哪个模型」,配置随选择建、没人用就删。
 */
data class ProviderConfig(
    val id: String,
    val kind: Kind,
    val baseUrl: String,
    val model: String,
    val capabilities: Capabilities,
    /** 界面上显示的名字。 */
    val name: String = "",
    /** 所属 [Provider] 的 id;为空时由 [ProviderStore.save] 按接口类型、地址和名字归到对应服务商。 */
    val providerId: String = "",
) {
    /**
     * 接口类型。[OpenAiResponses] 用在模型列表给某个模型写明 Responses 协议时,和预设「OpenAI」(添加自定义服务商时不选它);
     * [ChatGpt] 是用 ChatGPT 账号登录、用套餐额度调用的那一家:凭据是登录(见 [ChatGptSession]),不是 API Key。
     */
    enum class Kind {
        OpenAiCompat, Anthropic, Gemini, OpenAiResponses, ChatGpt;

        companion object {
            /** 模型单独指定的协议用哪种接口类型的适配器。 */
            fun of(protocol: ModelProtocol): Kind = when (protocol) {
                ModelProtocol.ChatCompletions -> OpenAiCompat
                ModelProtocol.Messages -> Anthropic
                ModelProtocol.GenerateContent -> Gemini
                ModelProtocol.Responses -> OpenAiResponses
            }

            /** 添加自定义服务商时可选的接口类型(填地址和 API Key 的)。 */
            val withApiKey: List<Kind> = listOf(OpenAiCompat, Anthropic, Gemini)
        }
    }
}

/**
 * 一个已连接的服务商:名字、接口类型、地址;Key 单独加密保存。
 * [models] 是最近一次列出的模型(「测试并保存」或刷新时记下,和配置存在同一个文件里);
 * 为 null 表示还没列过,空列表表示列过但服务不提供列表(或一个也没有)。
 * [sessionHeader] 是它要求放对话会话号的请求头名(来自预设,见 [ProviderPreset.sessionHeader]);不要的为 null。
 * [verifiedModels] 是在这家试调用(见 [ProviderStore.probe])得到过回复的模型名:不提供列表的服务商,模型名是手填的,
 * 没在这里的就没核实过(旧版本存下的都没有,那时不提供列表的服务商不试调用)。
 */
data class Provider(
    val id: String,
    val name: String,
    val kind: ProviderConfig.Kind,
    val baseUrl: String,
    val models: List<ListedModel>? = null,
    val sessionHeader: String? = null,
    val verifiedModels: Set<String> = emptySet(),
) {
    val key: String get() = ProviderConnection.keyOf(kind, baseUrl, name)
}

/** 一个用途选了什么:一个具体模型、用主模型,或不用(只有看图可以不用)。 */
sealed interface RoleChoice {
    /** 用 [configId] 这条模型配置。 */
    data class Model(val configId: String) : RoleChoice
    data object SameAsMain : RoleChoice
    data object Off : RoleChoice
}

/**
 * 三个用途各选了什么,都是用户明说的,没有按能力推断的默认分配。
 * [main] 是主模型的配置 id,为 null 表示还没选;看图默认不用,轻量默认用主模型。
 */
data class RoleChoices(
    val main: String? = null,
    val vision: RoleChoice = RoleChoice.Off,
    val light: RoleChoice = RoleChoice.SameAsMain,
) {
    /** 这个用途的选择;主模型没选时为 null。 */
    operator fun get(role: ModelRole): RoleChoice? = when (role) {
        ModelRole.Main -> main?.let(RoleChoice::Model)
        ModelRole.Vision -> vision
        ModelRole.Light -> light
    }

    /** 这个用途实际用的配置 id:「用主模型」取主模型的;不用、或主模型还没选时为 null。 */
    fun resolve(role: ModelRole): String? = when (val c = get(role)) {
        is RoleChoice.Model -> c.configId
        RoleChoice.SameAsMain -> main
        RoleChoice.Off, null -> null
    }

    /** 有用途在用的配置 id;其余的配置没人用,会被删掉。 */
    val referenced: Set<String> get() = ModelRole.entries.mapNotNull(::resolve).toSet()

    /** 改一个用途;这个用途不接受的选择(主模型只能是具体模型,轻量不能不用)返回 null。 */
    fun with(role: ModelRole, choice: RoleChoice): RoleChoices? = when (role) {
        ModelRole.Main -> (choice as? RoleChoice.Model)?.let { copy(main = it.configId) }
        ModelRole.Vision -> copy(vision = choice)
        ModelRole.Light -> if (choice == RoleChoice.Off) null else copy(light = choice)
    }

    /** 指向 [valid] 之外的选择退回默认:主模型变成没选,看图不用,轻量用主模型。 */
    fun keepOnly(valid: Set<String>): RoleChoices {
        fun RoleChoice.gone() = this is RoleChoice.Model && configId !in valid
        return RoleChoices(
            main = main?.takeIf { it in valid },
            vision = if (vision.gone()) RoleChoice.Off else vision,
            light = if (light.gone()) RoleChoice.SameAsMain else light,
        )
    }
}

/**
 * 建一个模型客户端时额外给的:[headers] 每个请求都带(客户端标识、会话号,见 [ClientHeaders]);
 * [maxOutputTokens] 限制回复长度(连接测试用),null 按适配器默认。
 */
data class ClientSetup(val headers: Map<String, String> = emptyMap(), val maxOutputTokens: Int? = null)

/** 「测试并保存」试调用(见 [ProviderStore.probe])的结果。 */
sealed interface ProbeResult {
    /** 模型调用了试用的工具:接口通,也会调用工具。 */
    data object ToolCalled : ProbeResult
    /** 接口通,模型只回了话、没调用工具:Mika 干活靠工具,这个模型当不了主模型。 */
    data object NoToolCall : ProbeResult
    /** 请求失败;[error] 带着服务端的报错。 */
    data class Failed(val error: ModelError) : ProbeResult
}

/**
 * 服务商、模型配置的存取与用途选择。配置存成 JSON 文件,API Key 经 [KeyCipher] 加密后另存一个文件,每个服务商一份。
 *
 * 用户只管两层:连了哪些服务商、每个用途([RoleChoices])用哪个模型。模型配置([ProviderConfig])是内部的一层:
 * 选模型时当场建,没有用途再用它就删掉(见 [prune];Key 按服务商存,删配置不动 Key)。
 * 用途选什么就是什么,不看能力标记;选具体模型时把对应标记记为 true(见 [choose])。
 *
 * 旧版本的文件:只有模型配置、Key 按配置 id 存的,读入时按 [ProviderConnection.keyOf] 归成服务商,
 * Key 取那组里任一条的,原来按配置 id 存的 Key 清掉(见 [migrate]);用途只记了显式指定的,
 * 读入时把那时实际生效的分配一次写明(见 [migrateRoles])。已迁移好的不再动,重复执行没有副作用。
 *
 * 拿来调用的模型只从 [modelFor] 出去,出去前一律套上计量(见 [metered]),每次调用都记到 [usage]:
 * 谁调用、调用几次都不用各自记账。
 */
class ProviderStore(
    dir: File,
    private val cipher: KeyCipher,
    private val usage: UsageSink = UsageSink { _, _ -> },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** 发给模型服务的 User-Agent;App 给 `Mika/<版本>`。 */
    private val userAgent: String = "Mika",
    /** 列模型:接口类型、地址、Key、每个请求都带的头。 */
    private val modelLister: suspend (ProviderConfig.Kind, String, String?, Map<String, String>) -> ModelListResult = ::listAvailable,
    /** 「用 ChatGPT 登录」的授权服务(换令牌、刷新、注销)。 */
    val chatGptAuth: ChatGptAuth = defaultChatGptAuth,
    /**
     * 建可调用的模型:配置、Key、服务商列表里给这个模型单独指定的接口(没有为 null,按配置的接口类型和地址)、请求头等。
     * 为 null 时用内置的适配器(测试里换成假的)。
     */
    modelFactory: ((ProviderConfig, String?, ModelEndpoint?, ClientSetup) -> ChatModel)? = null,
) {
    private val modelFactory: (ProviderConfig, String?, ModelEndpoint?, ClientSetup) -> ChatModel = modelFactory ?: ::buildModel
    /** 每个 ChatGPT 服务商一份:同一时刻只有一个在换令牌。 */
    private val chatGptTokens = mutableMapOf<String, ChatGptTokens>()
    private val configFile = File(dir, CONFIG_FILE)
    private val keyFile = File(dir, KEY_FILE)
    private val chatGptHostFile = File(dir, CHATGPT_HOST_FILE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _providers = MutableStateFlow<List<Provider>>(emptyList())
    /** 已连接的服务商,按连接的先后。 */
    val providers: StateFlow<List<Provider>> = _providers.asStateFlow()

    private val _configs = MutableStateFlow<List<ProviderConfig>>(emptyList())
    val configs: StateFlow<List<ProviderConfig>> = _configs.asStateFlow()

    private val _roles = MutableStateFlow(RoleChoices())
    /** 三个用途各选了什么;指向的配置都在 [configs] 里。 */
    val roles: StateFlow<RoleChoices> = _roles.asStateFlow()

    /** 服务商 id → 加密后的 Key。 */
    private var keys: Map<String, String> = emptyMap()

    init {
        dir.mkdirs()
        load()
    }

    fun list(): List<ProviderConfig> = _configs.value

    fun provider(id: String): Provider? = _providers.value.firstOrNull { it.id == id }

    /**
     * 新增或替换同 id 的模型配置(新增的要再交给 [choose],否则下次读入时没人用会被删掉)。
     * 配置挂到 [ProviderConfig.providerId] 指的服务商下(接口类型、地址、名字以服务商的为准);
     * 没指或指的不存在,就按接口类型、地址和名字找对应的服务商,找不到新建一个。
     * [apiKey] 是那个服务商的 Key:null 表示不改,空串表示清除。
     */
    @Synchronized
    fun save(cfg: ProviderConfig, apiKey: String?) {
        val p = provider(cfg.providerId) ?: findOrAddProvider(ProviderConnection.displayName(cfg), cfg.kind, cfg.baseUrl)
        val attached = cfg.copy(providerId = p.id, kind = p.kind, baseUrl = p.baseUrl, name = p.name)
        val list = _configs.value
        val i = list.indexOfFirst { it.id == cfg.id }
        _configs.value = if (i >= 0) list.toMutableList().also { it[i] = attached } else list + attached
        if (apiKey != null) setKey(p.id, apiKey)
        writeConfig()
    }

    /** 这个模型配置所属服务商的 Key。 */
    fun apiKey(configId: String): String? =
        _configs.value.firstOrNull { it.id == configId }?.let { providerKey(it.providerId) }

    /** 服务商的 Key。解不开(例如密钥库被系统清掉)时当作没有 Key。 */
    fun providerKey(providerId: String): String? {
        val enc = synchronized(this) { keys[providerId] } ?: return null
        return runCatching { String(cipher.decrypt(decode(enc))) }.getOrNull()
    }

    /** 这个服务商存了 Key 没有(本地服务可以不填)。 */
    fun hasKey(providerId: String): Boolean = synchronized(this) { providerId in keys }

    /**
     * 「测试并保存」连上了一个服务商:存下它(名字、接口类型、地址、Key)和列出的模型。
     * 接口类型、地址和名字都相同的服务商已经有了,就更新那一个(换 Key、换列表),不重复添加。
     * [apiKey] 为 null 时不改已存的 Key。[verified] 是这次试调用得到回复的模型名,记进 [Provider.verifiedModels]。
     */
    @Synchronized
    fun connect(
        name: String, kind: ProviderConfig.Kind, baseUrl: String, apiKey: String?, models: List<ListedModel>,
        sessionHeader: String? = ProviderPresets.forBaseUrl(baseUrl)?.sessionHeader,
        verified: Set<String> = emptySet(),
    ): Provider {
        val found = findOrAddProvider(name, kind, baseUrl)
        val p = found.copy(models = models, sessionHeader = sessionHeader, verifiedModels = found.verifiedModels + verified)
        _providers.value = _providers.value.map { if (it.id == p.id) p else it }
        if (apiKey != null) setKey(p.id, apiKey)
        writeConfig()
        return p
    }

    /** 记下服务商最近一次列出的模型。 */
    @Synchronized
    fun rememberModels(providerId: String, models: List<ListedModel>) {
        if (provider(providerId) == null) return
        _providers.value = _providers.value.map { if (it.id == providerId) it.copy(models = models) else it }
        writeConfig()
    }

    /** 记下 [model] 在这家试调用得到了回复(见 [Provider.verifiedModels])。 */
    @Synchronized
    fun markVerified(providerId: String, model: String) {
        val p = provider(providerId) ?: return
        if (model in p.verifiedModels) return
        _providers.value = _providers.value.map { if (it.id == providerId) it.copy(verifiedModels = it.verifiedModels + model) else it }
        writeConfig()
    }

    /** 换掉一个服务商的 Key(它下面的模型都用这一个)。 */
    @Synchronized
    fun updateProviderKey(providerId: String, apiKey: String) {
        if (provider(providerId) != null) setKey(providerId, apiKey)
    }

    /**
     * 删掉一个服务商:它下面的模型配置和 Key 一起删。用着它的用途退回默认(主模型变成没选,看图不用,轻量用主模型);
     * 界面上删除前已经说明哪些用途要重新选。删的是 ChatGPT 的,连注册到的 client_id 一起忘掉(本机标识留着):
     * 下次登录重新注册,可以换一个账号。
     */
    @Synchronized
    fun removeProvider(providerId: String) {
        if (provider(providerId)?.kind == ProviderConfig.Kind.ChatGpt) {
            writeAtomically(chatGptHostFile, buildJsonObject { put("hostId", chatGptRegistration().first) }.toString())
        }
        _providers.value = _providers.value.filterNot { it.id == providerId }
        _configs.value = _configs.value.filterNot { it.providerId == providerId }
        _roles.value = _roles.value.keepOnly(_configs.value.map { it.id }.toSet())
        prune()
        if (providerId in keys) setKey(providerId, "")
        writeConfig()
    }

    /**
     * 把服务商 [providerId] 下的 [model] 用作 [role]:这个模型还没有配置就按 [caps] 建一条,再交给 [choose]。
     * 服务商不存在时返回 false。
     */
    @Synchronized
    fun use(role: ModelRole, providerId: String, model: String, caps: Capabilities): Boolean {
        val p = provider(providerId) ?: return false
        val id = _configs.value.firstOrNull { it.providerId == p.id && it.model == model }?.id
            ?: newId().also { save(ProviderConfig(it, p.kind, p.baseUrl, model, caps, p.name, p.id), null) }
        return choose(role, RoleChoice.Model(id))
    }

    /**
     * 定一个用途:用户选什么就是什么,不看能力标记。选具体模型时,主模型把工具调用、看图模型把看图标记记为 true
     * (运行时按这两个标记决定发不发工具定义、附不附图)。之后没人用的配置删掉。
     * 配置不存在、或这个用途不接受这种选择(见 [RoleChoices.with])时返回 false。
     */
    @Synchronized
    fun choose(role: ModelRole, choice: RoleChoice): Boolean {
        if (choice is RoleChoice.Model && _configs.value.none { it.id == choice.configId }) return false
        val next = _roles.value.with(role, choice) ?: return false
        if (choice is RoleChoice.Model) {
            _configs.value = _configs.value.map { c ->
                if (c.id != choice.configId) c
                else when (role) {
                    ModelRole.Main -> c.copy(capabilities = c.capabilities.copy(toolCalls = true))
                    ModelRole.Vision -> c.copy(capabilities = c.capabilities.copy(vision = true))
                    ModelRole.Light -> c
                }
            }
        }
        _roles.value = next
        prune()
        writeConfig()
        return true
    }

    /** 看图模型的「看图定位已验证」(开发者选项里改);看图用主模型时改的是主模型那条配置。没有看图模型时不动。 */
    @Synchronized
    fun setVisionGroundingVerified(verified: Boolean) {
        val id = _roles.value.resolve(ModelRole.Vision) ?: return
        _configs.value = _configs.value.map { c ->
            if (c.id == id) c.copy(capabilities = c.capabilities.copy(groundingVerified = verified)) else c
        }
        writeConfig()
    }

    /**
     * 这个用途实际用的配置;没选、不用时为 null。看图「用主模型」时是主模型那条配置、看图标记为 true:
     * 用户说了主模型能看图,截图就照发。这只影响看图这一路,主模型自己办事时仍按它存着的标记。
     */
    fun configFor(role: ModelRole): ProviderConfig? {
        val choices = _roles.value
        val cfg = choices.resolve(role)?.let { id -> _configs.value.firstOrNull { it.id == id } } ?: return null
        val viaMain = role == ModelRole.Vision && choices.vision == RoleChoice.SameAsMain
        return if (viaMain) cfg.copy(capabilities = cfg.capabilities.copy(vision = true)) else cfg
    }

    /**
     * 这个用途用的模型,套好计量;没选、不用时为 null。用量记在 [role] 名下:「用主模型」的看图、轻量调用
     * 记作看图、轻量,服务商和模型是主模型那条配置的。这是 App 里建可调用模型的唯一入口。
     * [session] 是这次调用属于的对话(同一段对话里建的模型都给同一个);服务商声明了会话头的,每个请求都带上它。
     */
    fun modelFor(role: ModelRole, session: ModelSession = ModelSession.fresh()): ChatModel? = configFor(role)?.let { cfg ->
        val p = provider(cfg.providerId)
        val source = UsageSource(cfg.providerId, p?.name ?: cfg.name, cfg.model, UsagePurpose.of(role))
        modelFactory(cfg, apiKey(cfg.id), endpointOf(cfg), ClientSetup(headers(p?.sessionHeader, session))).metered(source, usage)
    }

    /**
     * 「测试并保存」的试调用:还没存下的服务商,按真实任务同一条路([modelFactory]、同样的请求头)建出 [model],
     * 带一个无参数的工具([PROBE_TOOL])、请它调用;回复限得很短(协议允许的话,见 [buildModel])。
     * 回了工具调用为 [ProbeResult.ToolCalled],只回了话为 [ProbeResult.NoToolCall],请求失败带着服务端的报错原样返回。
     * 不计量:这几个 token 不属于任何用途。会话号用新的,不属于任何对话。超时由调用方管。
     */
    suspend fun probe(
        kind: ProviderConfig.Kind, baseUrl: String, apiKey: String?, sessionHeader: String?,
        model: String, endpoint: ModelEndpoint?, caps: Capabilities,
    ): ProbeResult {
        val cfg = ProviderConfig(PROBE_ID, kind, baseUrl, model, caps.copy(toolCalls = true))
        val m = modelFactory(cfg, apiKey, endpoint, ClientSetup(headers(sessionHeader, ModelSession.fresh()), PROBE_MAX_TOKENS))
        return try {
            val reply = m.complete(PROBE_SYSTEM, listOf(Message(Role.User, listOf(Part.Text(PROBE_TEXT)), Author.Mika)), listOf(PROBE_TOOL))
            if (reply.parts.any { it is Part.ToolCall }) ProbeResult.ToolCalled else ProbeResult.NoToolCall
        } catch (e: ModelError) {
            ProbeResult.Failed(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProbeResult.Failed(ModelError.BadResponse(e.message.orEmpty(), e))
        }
    }

    private fun headers(sessionHeader: String?, session: ModelSession) = ClientHeaders.of(userAgent, sessionHeader, session)

    /** 服务商最近一次列出的模型里,给这个模型单独指定的接口;每次建模型时现查,重新列过就用新的。 */
    private fun endpointOf(cfg: ProviderConfig): ModelEndpoint? =
        provider(cfg.providerId)?.models?.firstOrNull { it.id == cfg.model }?.endpoint

    /** 列出这个服务商的可用模型(见 [ModelCatalog]);不属于任何对话,声明了会话头的用新的会话号。 */
    suspend fun listModels(kind: ProviderConfig.Kind, baseUrl: String, apiKey: String?, sessionHeader: String? = null): ModelListResult =
        modelLister(kind, baseUrl, apiKey, headers(sessionHeader, ModelSession.fresh()))

    /** 重新列一个已连接服务商的模型:用存着的 Key;ChatGPT 用登录的访问令牌(快过期先换新的)。 */
    suspend fun listModels(p: Provider): ModelListResult {
        if (p.kind != ProviderConfig.Kind.ChatGpt) return listModels(p.kind, p.baseUrl, providerKey(p.id), p.sessionHeader)
        val token = try {
            tokensFor(p.id).get(fresh = false)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ModelError.SignInExpired) {
            return ModelListResult.Failed(ListFailure.Auth)
        } catch (e: ModelError) {
            return ModelListResult.Failed(if (e is ModelError.Network) ListFailure.Network else ListFailure.Server)
        }
        return listModels(p.kind, p.baseUrl, token)
    }

    // ---------- ChatGPT 登录 ----------

    /** ChatGPT 服务商的登录(和 API Key 存在同一处、同样加密);没有或解不开为 null。 */
    fun chatGptSession(providerId: String): ChatGptSession? = ChatGptSession.fromJson(providerKey(providerId))

    /** 已连接的 ChatGPT 服务商(只有一家)。 */
    fun chatGptProvider(): Provider? = _providers.value.firstOrNull { it.kind == ProviderConfig.Kind.ChatGpt }

    /** 登录成功:存下登录和列出的模型;已有 ChatGPT 服务商就更新那一家。 */
    @Synchronized
    fun connectChatGpt(session: ChatGptSession, models: List<ListedModel>): Provider =
        connect(CHATGPT_NAME, ProviderConfig.Kind.ChatGpt, ChatGptAuth.API_BASE, session.toJson(), models)

    /**
     * 退出登录:先在服务端注销刷新令牌(确认不了也照样清),再清掉本机的令牌;注册(client_id)和账号留着,再登录时沿用。
     * 返回服务端是否确认了注销。服务商和它的模型、用途都不动。
     */
    suspend fun signOutChatGpt(providerId: String): Boolean {
        val s = chatGptSession(providerId) ?: return true
        val revoked = s.refreshToken.isEmpty() || chatGptAuth.revoke(s.clientId, s.refreshToken)
        saveChatGptSession(providerId, s.signedOut())
        return revoked
    }

    /**
     * 这台设备在 ChatGPT 那边的本机标识和注册到的 client_id。标识第一次用时生成,之后一直不变;client_id 注册后记下,
     * 退出登录后留着(再登录沿用,不重复注册),删掉 ChatGPT 服务商时忘掉。两者都不是密钥,明文存。
     */
    @Synchronized
    fun chatGptRegistration(): Pair<String, String?> {
        val saved = runCatching { json.parseToJsonElement(chatGptHostFile.readText()).jsonObject }.getOrNull()
        val host = saved?.get("hostId")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val client = saved?.get("clientId")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        if (host != null) return host to client
        val fresh = ChatGptAuth.newHostId()
        writeAtomically(chatGptHostFile, buildJsonObject { put("hostId", fresh) }.toString())
        return fresh to null
    }

    /** 记下注册到的 client_id(换令牌前就记,换失败了下次也不用重新注册)。 */
    @Synchronized
    fun rememberChatGptClient(clientId: String) {
        val host = chatGptRegistration().first
        writeAtomically(chatGptHostFile, buildJsonObject { put("hostId", host); put("clientId", clientId) }.toString())
    }

    @Synchronized
    private fun saveChatGptSession(providerId: String, session: ChatGptSession) {
        if (provider(providerId) != null) setKey(providerId, session.toJson())
    }

    private fun tokensFor(providerId: String): ChatGptTokens = synchronized(chatGptTokens) {
        chatGptTokens.getOrPut(providerId) {
            ChatGptTokens(chatGptAuth, { chatGptSession(providerId) }, { saveChatGptSession(providerId, it) })
        }
    }

    /** 模型单独指定了接口的,按它的协议选适配器、用它的地址;否则按配置的接口类型和地址。Key 都用服务商的。 */
    /**
     * 请求头四种协议一样带。回复上限([ClientSetup.maxOutputTokens])只给 Messages(本来就必填)和 generateContent;
     * Chat Completions 不给(有的模型只认 `max_tokens`、有的只认 `max_completion_tokens`,给错了整个请求被拒,
     * 连接测试就会误报),Responses 也不给(ChatGPT 套餐那条路不收 `max_output_tokens`)。
     */
    private fun buildModel(cfg: ProviderConfig, apiKey: String?, endpoint: ModelEndpoint?, setup: ClientSetup): ChatModel {
        val (kind, baseUrl) = adapterFor(cfg, endpoint)
        return when (kind) {
            ProviderConfig.Kind.OpenAiCompat ->
                OpenAiCompatModel(baseUrl, apiKey, cfg.model, cfg.capabilities, http, endpoint?.reasoningField, setup.headers)
            ProviderConfig.Kind.Anthropic -> AnthropicModel(
                baseUrl, apiKey.orEmpty(), cfg.model, cfg.capabilities, http,
                maxTokens = setup.maxOutputTokens ?: DEFAULT_ANTHROPIC_MAX_TOKENS, headers = setup.headers,
            )
            ProviderConfig.Kind.Gemini ->
                GeminiModel(baseUrl, apiKey.orEmpty(), cfg.model, cfg.capabilities, http, setup.headers, setup.maxOutputTokens)
            ProviderConfig.Kind.OpenAiResponses ->
                ResponsesModel(baseUrl, BearerToken.fixed(apiKey), cfg.model, cfg.capabilities, http, headers = setup.headers)
            ProviderConfig.Kind.ChatGpt ->
                ResponsesModel(baseUrl, tokensFor(cfg.providerId), cfg.model, cfg.capabilities, http, chatGptPlan = true, headers = setup.headers)
        }
    }

    private fun findOrAddProvider(name: String, kind: ProviderConfig.Kind, baseUrl: String): Provider {
        val key = ProviderConnection.keyOf(kind, baseUrl, name)
        _providers.value.firstOrNull { it.key == key }?.let { return it }
        val p = Provider(newId(), name.trim(), kind, baseUrl.trim())
        _providers.value = _providers.value + p
        return p
    }

    private fun setKey(providerId: String, apiKey: String) {
        keys = if (apiKey.isEmpty()) keys - providerId else keys + (providerId to encode(cipher.encrypt(apiKey.toByteArray())))
        writeAtomically(keyFile, keysJson())
    }

    /** 删掉没有用途在用的模型配置。Key 存在服务商上,不受影响。 */
    private fun prune() {
        val used = _roles.value.referenced
        _configs.value = _configs.value.filter { it.id in used }
    }

    private fun load() {
        if (configFile.isFile) runCatching {
            val root = json.parseToJsonElement(configFile.readText()).jsonObject
            _providers.value = root["services"]?.jsonArray?.mapNotNull { runCatching { providerOf(it.jsonObject) }.getOrNull() }.orEmpty()
            _configs.value = root["providers"]?.jsonArray?.mapNotNull { runCatching { configOf(it.jsonObject) }.getOrNull() }.orEmpty()
            val uses = root["uses"]?.jsonObject
            _roles.value = if (uses != null) choicesOf(uses) else migrateRoles(root["roles"]?.jsonObject)
        }
        if (keyFile.isFile) runCatching {
            keys = json.parseToJsonElement(keyFile.readText()).jsonObject.mapValues { it.value.jsonPrimitive.content }
        }
        migrate()
        // 指向不存在的配置的选择退回默认,没人用的配置删掉;和文件里的不一样(含刚迁移的用途)才写回
        _roles.value = _roles.value.keepOnly(_configs.value.map { it.id }.toSet())
        prune()
        if (configFile.isFile) {
            val text = configJson()
            if (runCatching { configFile.readText() }.getOrNull() != text) writeAtomically(configFile, text)
        }
    }

    /**
     * 旧版本的 `roles` 只记显式指定的配置 id,没指定的按默认规则分配:主模型取第一个配置,看图在主模型能看图时由它兼任,
     * 轻量跟随主模型。这里把那时实际生效的分配写明成 [RoleChoices],之后不再有隐含的规则。
     */
    private fun migrateRoles(old: JsonObject?): RoleChoices {
        val ids = _configs.value.map { it.id }.toSet()
        fun explicit(role: ModelRole) = old?.get(role.name)?.jsonPrimitive?.content?.takeIf { it in ids }
        val main = explicit(ModelRole.Main) ?: _configs.value.firstOrNull()?.id
        val mainSees = _configs.value.firstOrNull { it.id == main }?.capabilities?.vision == true
        return RoleChoices(
            main = main,
            vision = explicit(ModelRole.Vision)?.let(RoleChoice::Model) ?: if (mainSees) RoleChoice.SameAsMain else RoleChoice.Off,
            light = explicit(ModelRole.Light)?.let(RoleChoice::Model) ?: RoleChoice.SameAsMain,
        )
    }

    /**
     * 把没有归属的模型配置(旧版本的文件)归到服务商下:按 [ProviderConnection.keyOf] 分组,每组一个服务商,
     * Key 取组里任一条按配置 id 存的(密文原样搬过去)。先写含新旧两份的 Key 文件,再写配置,最后清掉不属于任何服务商的 Key;
     * 中途断掉的话,下次读入时没归属的配置还在,会重做。都归好了就只清理残留的 Key。
     */
    private fun migrate() {
        val known = _providers.value.map { it.id }.toSet()
        val orphans = _configs.value.filter { it.providerId !in known }
        if (orphans.isNotEmpty()) {
            val attachedTo = mutableMapOf<String, Provider>()
            orphans.groupBy { ProviderConnection.keyOf(it.kind, it.baseUrl, ProviderConnection.displayName(it)) }.values.forEach { group ->
                val first = group.first()
                val p = findOrAddProvider(ProviderConnection.displayName(first), first.kind, first.baseUrl)
                group.forEach { attachedTo[it.id] = p }
                if (p.id !in keys) group.firstNotNullOfOrNull { keys[it.id] }?.let { keys = keys + (p.id to it) }
            }
            writeAtomically(keyFile, keysJson())
            _configs.value = _configs.value.map { c ->
                attachedTo[c.id]?.let { p -> c.copy(providerId = p.id, kind = p.kind, baseUrl = p.baseUrl, name = p.name) } ?: c
            }
            writeConfig()
        }
        val ids = _providers.value.map { it.id }.toSet()
        if (keys.keys.any { it !in ids }) {
            keys = keys.filterKeys { it in ids }
            writeAtomically(keyFile, keysJson())
        }
    }

    private fun providerOf(o: JsonObject) = Provider(
        id = o.str("id"),
        name = o.str("name"),
        kind = ProviderConfig.Kind.valueOf(o.str("kind")),
        baseUrl = o.str("baseUrl"),
        // 旧版本存下的没有这一项:按地址对得上的预设补上(读入后写回文件时记进去)
        sessionHeader = o["sessionHeader"]?.jsonPrimitive?.content ?: ProviderPresets.forBaseUrl(o.str("baseUrl"))?.sessionHeader,
        verifiedModels = o["verifiedModels"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
        models = o["models"]?.jsonArray?.map { e ->
            val m = e.jsonObject
            ListedModel(
                id = m.str("id"),
                displayName = m["displayName"]?.jsonPrimitive?.content.orEmpty(),
                toolCalls = m["toolCalls"]?.jsonPrimitive?.booleanOrNull,
                vision = m["vision"]?.jsonPrimitive?.booleanOrNull,
                contextTokens = m["contextTokens"]?.jsonPrimitive?.intOrNull,
                reasoning = m["reasoning"]?.jsonPrimitive?.booleanOrNull,
                chat = m["chat"]?.jsonPrimitive?.booleanOrNull,
                endpoint = (m["api"] as? JsonObject)?.let { a ->
                    ModelProtocol.of(a["protocol"]?.jsonPrimitive?.content)?.let { protocol ->
                        ModelEndpoint(protocol, a.str("baseUrl"), a["reasoningField"]?.jsonPrimitive?.content)
                    }
                },
                unsupportedApis = m["unsupportedApis"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            )
        },
    )

    private fun configOf(o: JsonObject) = ProviderConfig(
        id = o.str("id"),
        kind = ProviderConfig.Kind.valueOf(o.str("kind")),
        baseUrl = o.str("baseUrl"),
        model = o.str("model"),
        capabilities = Capabilities(
            toolCalls = o["toolCalls"]!!.jsonPrimitive.boolean,
            vision = o["vision"]!!.jsonPrimitive.boolean,
            contextTokens = o["contextTokens"]!!.jsonPrimitive.int,
            reasoning = o["reasoning"]!!.jsonPrimitive.boolean,
            groundingVerified = o["groundingVerified"]?.jsonPrimitive?.boolean ?: false,
            promptCaching = PromptCaching.of(o["promptCaching"]?.jsonPrimitive?.content),
        ),
        name = o["name"]?.jsonPrimitive?.content.orEmpty(),
        providerId = o["providerId"]?.jsonPrimitive?.content.orEmpty(),
    )

    /** `uses` 里的一个用途:`{"use":"model","config":id}`、`{"use":"main"}` 或 `{"use":"none"}`;认不出的按默认。 */
    private fun choicesOf(o: JsonObject): RoleChoices {
        fun choice(role: ModelRole): RoleChoice? {
            val c = runCatching { o[role.name]?.jsonObject }.getOrNull() ?: return null
            return when (c["use"]?.jsonPrimitive?.content) {
                USE_MODEL -> c["config"]?.jsonPrimitive?.content?.let(RoleChoice::Model)
                USE_MAIN -> RoleChoice.SameAsMain
                USE_NONE -> RoleChoice.Off
                else -> null
            }
        }
        var r = RoleChoices()
        ModelRole.entries.forEach { role -> choice(role)?.let { c -> r.with(role, c)?.let { r = it } } }
        return r
    }

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content

    private fun writeConfig() = writeAtomically(configFile, configJson())

    private fun configJson(): String {
        val root = buildJsonObject {
            putJsonArray("services") {
                _providers.value.forEach { p ->
                    addJsonObject {
                        put("id", p.id); put("name", p.name); put("kind", p.kind.name); put("baseUrl", p.baseUrl)
                        p.sessionHeader?.let { put("sessionHeader", it) }
                        if (p.verifiedModels.isNotEmpty()) putJsonArray("verifiedModels") { p.verifiedModels.forEach { add(it) } }
                        p.models?.let { models ->
                            putJsonArray("models") {
                                models.forEach { m ->
                                    addJsonObject {
                                        put("id", m.id)
                                        if (m.displayName.isNotEmpty()) put("displayName", m.displayName)
                                        m.toolCalls?.let { put("toolCalls", it) }
                                        m.vision?.let { put("vision", it) }
                                        m.contextTokens?.let { put("contextTokens", it) }
                                        m.reasoning?.let { put("reasoning", it) }
                                        m.chat?.let { put("chat", it) }
                                        m.endpoint?.let { e ->
                                            putJsonObject("api") {
                                                put("protocol", e.protocol.wire); put("baseUrl", e.baseUrl)
                                                e.reasoningField?.let { put("reasoningField", it) }
                                            }
                                        }
                                        if (m.unsupportedApis.isNotEmpty()) putJsonArray("unsupportedApis") { m.unsupportedApis.forEach { add(it) } }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            putJsonArray("providers") {
                _configs.value.forEach { c ->
                    addJsonObject {
                        put("id", c.id); put("providerId", c.providerId); put("name", c.name); put("kind", c.kind.name)
                        put("baseUrl", c.baseUrl); put("model", c.model)
                        put("toolCalls", c.capabilities.toolCalls); put("vision", c.capabilities.vision)
                        put("contextTokens", c.capabilities.contextTokens); put("reasoning", c.capabilities.reasoning)
                        put("groundingVerified", c.capabilities.groundingVerified)
                        put("promptCaching", c.capabilities.promptCaching.wire)
                    }
                }
            }
            putJsonObject("uses") {
                ModelRole.entries.forEach { role ->
                    when (val c = _roles.value[role]) {
                        is RoleChoice.Model -> putJsonObject(role.name) { put("use", USE_MODEL); put("config", c.configId) }
                        RoleChoice.SameAsMain -> putJsonObject(role.name) { put("use", USE_MAIN) }
                        RoleChoice.Off -> putJsonObject(role.name) { put("use", USE_NONE) }
                        null -> Unit
                    }
                }
            }
        }
        return root.toString()
    }

    private fun keysJson() = buildJsonObject { keys.forEach { (id, v) -> put(id, v) } }.toString()

    private fun writeAtomically(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            check(tmp.renameTo(f)) { "cannot write ${f.name}" }
        }
    }

    private fun encode(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun decode(s: String) = Base64.getDecoder().decode(s)

    /** 给出进程里那一份的 Application。 */
    interface Owner {
        val providers: ProviderStore
    }

    companion object {
        private const val CONFIG_FILE = "providers.json"
        private const val KEY_FILE = "provider-keys.json"
        private const val CHATGPT_HOST_FILE = "chatgpt-host.json"

        /** `uses` 里每个用途的 `use`:具体模型(另记 `config`)、用主模型、不用。旧版本的文件里只有 `roles`。 */
        private const val USE_MODEL = "model"
        private const val USE_MAIN = "main"
        private const val USE_NONE = "none"

        private val http by lazy { OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build() }

        private val catalog by lazy { ModelCatalog(OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()) }

        private val defaultChatGptAuth by lazy { ChatGptAuth(OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()) }

        /** ChatGPT 服务商的名字(品牌名,不随界面语言变)。 */
        const val CHATGPT_NAME = "ChatGPT"

        /**
         * 试调用:临时配置的 id、系统提示、那句话、试用的工具、回复上限。
         * 上限留得够一次工具调用,连同必须先想一想的模型的思考(Messages、generateContent 把思考也算在上限里)。
         */
        private const val PROBE_ID = "probe"
        private const val PROBE_SYSTEM = "This is a connection test. Call the get_time tool now. Do not answer with text."
        private const val PROBE_TEXT = "What time is it? Use the get_time tool."
        private val PROBE_TOOL = ToolSpec("get_time", "Returns the current time.", """{"type":"object","properties":{}}""")
        private const val PROBE_MAX_TOKENS = 1024
        private const val DEFAULT_ANTHROPIC_MAX_TOKENS = 4096

        /**
         * 列模型的接口:OpenAI 兼容、Responses 和 ChatGPT 都是 `GET {base}/models`(Bearer;ChatGPT 的 [apiKey] 是访问令牌)。
         * [headers] 每个请求都带(见 [ClientHeaders])。
         */
        suspend fun listAvailable(kind: ProviderConfig.Kind, baseUrl: String, apiKey: String?, headers: Map<String, String>): ModelListResult =
            when (kind) {
                ProviderConfig.Kind.OpenAiCompat, ProviderConfig.Kind.OpenAiResponses, ProviderConfig.Kind.ChatGpt ->
                    catalog.openAiCompat(baseUrl, apiKey, headers)
                ProviderConfig.Kind.Anthropic -> catalog.anthropic(baseUrl, apiKey.orEmpty(), headers)
                ProviderConfig.Kind.Gemini -> catalog.gemini(baseUrl, apiKey.orEmpty(), headers)
            }

        /** 调用这个模型用哪种适配器、交给它什么 Base URL。 */
        internal fun adapterFor(cfg: ProviderConfig, endpoint: ModelEndpoint?): Pair<ProviderConfig.Kind, String> =
            if (endpoint == null) cfg.kind to cfg.baseUrl else ProviderConfig.Kind.of(endpoint.protocol) to endpoint.adapterBaseUrl

        /** 产品里的那份(App 建):配置在 files/settings 下,Key 用 Android 密钥库加密,用量记到 [usage]。 */
        fun create(context: Context, usage: UsageSink, userAgent: String): ProviderStore =
            ProviderStore(File(context.applicationContext.filesDir, "settings"), KeystoreKeyCipher(), usage, userAgent = userAgent)

        /** App 内共用的那份(经 [Owner] 给出)。 */
        fun get(context: Context): ProviderStore = (context.applicationContext as? Owner)?.providers
            ?: error("the Application (${context.applicationContext.javaClass.name}) must implement ProviderStore.Owner to give the model settings")
    }
}
