// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.ui

import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.ListFailure
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.ClientSetup
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.RoleChoice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

@OptIn(ExperimentalCoroutinesApi::class)
class ModelSetupViewModelTest {
    @get:Rule val texts = ChineseTexts()

    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    /** 按地址给出列模型的结果;没写的地址列出空表。 */
    private val listing = mutableMapOf<String, ModelListResult>()
    private val listCalls = mutableListOf<Triple<ProviderConfig.Kind, String, String?>>()
    private var ids = 0

    private fun store() = ProviderStore(
        tmp.root, AesGcmKeyCipher { key },
        newId = { "id${++ids}" },
        userAgent = "Mika/1.2.3",
        modelFactory = { cfg, _, _, setup -> built += cfg.model to setup; Fake(cfg.capabilities) { reply() } },
        modelLister = { kind, url, apiKey, _ -> listCalls += Triple(kind, url, apiKey); listing[url] ?: ModelListResult.Ok(emptyList()) },
    )

    /** 建过的模型(模型名、请求头等);「测试并保存」的试调用回什么由 [reply] 定,默认调用试用的工具。 */
    private val built = mutableListOf<Pair<String, ClientSetup>>()
    private var reply: suspend () -> ModelReply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
    private val probes = mutableListOf<Triple<String, List<Message>, List<ToolSpec>>>()

    private inner class Fake(override val capabilities: Capabilities, private val answer: suspend () -> ModelReply) : ChatModel {
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            probes += Triple(system, messages, tools)
            return answer()
        }
    }

    private fun TestScope.vm(s: ProviderStore = store()) =
        ModelSetupViewModel(s, backgroundScope, io = StandardTestDispatcher(testScheduler))

    private val deepseekUrl = "https://api.deepseek.com"
    private val qwenUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    private val deepseek = ModelListResult.Ok(
        listOf(ListedModel("deepseek-chat"), ListedModel("deepseek-reasoner"), ListedModel("deepseek-coder", toolCalls = false)),
    )

    @Test fun testAndSaveStoresProviderAndPicksFirstModelAsMain() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        runCurrent()
        assertTrue("还没有服务商时表单展开", vm.state.value.showForm)
        assertFalse(vm.state.value.form.canTest)
        assertFalse(vm.state.value.canNext)
        vm.setApiKey("sk-1")
        assertTrue(vm.state.value.form.canTest)
        vm.testAndSave()
        assertEquals(ListState.Running, vm.state.value.form.test)
        runCurrent()
        assertEquals(Triple(ProviderConfig.Kind.OpenAiCompat, deepseekUrl, "sk-1"), listCalls.single())
        val p = store.providers.value.single()
        assertEquals("DeepSeek", p.name)
        assertEquals(3, p.models?.size)
        assertEquals("sk-1", store.providerKey(p.id))
        // 自动选第一个模型为主模型(只存这一条配置)
        assertEquals(listOf("deepseek-chat"), store.list().map { it.model })
        assertEquals(store.list().single().id, store.roles.value.main)
        val s = vm.state.value
        assertEquals(listOf(p), s.providers)
        assertFalse("保存后表单收起", s.showForm)
        assertEquals("", s.form.apiKey)
        assertEquals(ListState.Idle, s.form.test)
        assertTrue(s.canNext)
    }

    @Test fun failedTestShowsReasonAndSavesNothing() = runTest {
        listing[deepseekUrl] = ModelListResult.Failed(ListFailure.Auth)
        val store = store()
        val vm = vm(store)
        vm.setApiKey("bad"); vm.testAndSave(); runCurrent()
        assertEquals(ListState.Failed(ListFailure.Auth.text), vm.state.value.form.test)
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
        assertFalse(vm.state.value.canNext)
        assertTrue(vm.state.value.showForm)
        vm.setApiKey("good")
        assertEquals(ListState.Idle, vm.state.value.form.test)
        assertEquals("good", vm.state.value.form.apiKey)
    }

    /** 列出模型后让会当主模型的那个调用一次工具:带一个无参数的工具、回复上限够一次调用、带客户端标识;成功才存。 */
    @Test fun testAndSaveSendsOneTinyRequestToTheMainCandidate() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1"); vm.testAndSave(); runCurrent()
        val (model, setup) = built.first()
        assertEquals("deepseek-chat", model)
        assertEquals(1024, setup.maxOutputTokens)
        assertEquals("Mika/1.2.3", setup.headers["User-Agent"])
        assertEquals("DeepSeek 没声明会话头", setOf("User-Agent"), setup.headers.keys)
        val (_, messages, tools) = probes.single()
        assertEquals(1, messages.size)
        assertEquals(listOf("get_time"), tools.map { it.name })
        assertEquals(1, store.providers.value.size)
        assertNull(vm.state.value.probeNote)
    }

    /** 接口通、模型只回了话:接着试下一个可当主模型的(明说不会调用工具的不试);都只回话就存下服务商、不定主模型,请用户选;选好后提醒收起。 */
    @Test fun modelThatOnlyAnswersTextIsSavedButNotMadeMain() = runTest {
        listing[deepseekUrl] = deepseek
        reply = { ModelReply(listOf(Part.Text("It is noon.")), StopReason.EndTurn, null) }
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1"); vm.testAndSave(); runCurrent()
        val p = store.providers.value.single()
        assertEquals(3, p.models?.size)
        assertNull("不自动选主模型", store.roles.value.main)
        assertTrue(store.list().isEmpty())
        val s = vm.state.value
        assertFalse("存下了,表单收起", s.showForm)
        assertEquals(ListState.Idle, s.form.test)
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), built.map { it.first })
        assertEquals(
            "服务商已连上，但「deepseek-chat」没测试通过：它不会调用工具，云开 干活需要会调用工具的模型。从列表里选一个你开通了的模型。\n" +
                "试了 deepseek-chat、deepseek-reasoner，都没测试通过。",
            s.probeNote,
        )
        assertEquals(p.id, s.pickToProbe)
        assertTrue(s.probeBeforePick(ModelRole.Main, p.id))
        assertFalse(s.probeBeforePick(ModelRole.Vision, p.id))
        assertFalse(s.canNext)
        vm.pick(ModelRole.Main, p.id, "deepseek-reasoner"); runCurrent()
        assertNull(vm.state.value.probeNote)
        assertNull(vm.state.value.pickToProbe)
        assertEquals("deepseek-reasoner", store.configFor(ModelRole.Main)?.model)
    }

    /** 通义千问的 Key 下先列出别家的模型:先试自家的(qwen);第一个没开通就接着试下一个,通过的当主模型,说清先试的哪个没通过。 */
    @Test fun notActivatedModelFallsBackToTheNextCandidate() = runTest {
        listing[qwenUrl] = ModelListResult.Ok(listOf(
            ListedModel("antdigital/Ling-3.1-flash", toolCalls = true), ListedModel("text-embedding-v4"),
            ListedModel("qwen3-max-preview"), ListedModel("qwen-plus"),
        ))
        reply = {
            if (built.last().first != "qwen-plus") throw ModelError.ModelNotAvailable(built.last().first, "The product is not activated.")
            ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null)
        }
        val store = store()
        val vm = vm(store)
        vm.selectPreset("通义千问"); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals("自家的先试、向量模型不试", listOf("qwen3-max-preview", "qwen-plus"), built.map { it.first })
        val p = store.providers.value.single()
        assertEquals("qwen-plus", store.configFor(ModelRole.Main)?.model)
        assertTrue("qwen-plus" in p.verifiedModels)
        val s = vm.state.value
        assertFalse(s.showForm)
        assertNull(s.pickToProbe)
        assertEquals(
            "先试的 qwen3-max-preview 没测试通过：这个账号没开通或没权限用这个模型（服务商说：The product is not activated.）。主模型用了测试通过的「qwen-plus」。",
            s.probeNote,
        )
    }

    /** 试的几个都没开通:Key 是对的,照样存下服务商,不定主模型,说清试了哪些,请用户从列表里选;选的时候先试调用,不通不选。 */
    @Test fun allCandidatesNotActivatedSavesProviderAndAsksToPick() = runTest {
        listing[qwenUrl] = ModelListResult.Ok(listOf(
            ListedModel("antdigital/Ling-3.1-flash", toolCalls = true), ListedModel("qwen-plus"), ListedModel("qwen-max"), ListedModel("qwen-turbo"),
        ))
        reply = { throw ModelError.ModelNotAvailable(built.last().first, "The product is not activated.") }
        val store = store()
        val vm = vm(store)
        vm.selectPreset("通义千问"); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals("至多试 3 个", listOf("qwen-plus", "qwen-max", "qwen-turbo"), built.map { it.first })
        val p = store.providers.value.single()
        assertEquals(4, p.models?.size)
        assertEquals("sk", store.providerKey(p.id))
        assertNull(store.roles.value.main)
        val s = vm.state.value
        assertFalse(s.showForm)
        assertEquals(ListState.Idle, s.form.test)
        assertEquals(
            "服务商已连上，但「qwen-plus」没测试通过：这个账号没开通或没权限用这个模型（服务商说：The product is not activated.）。从列表里选一个你开通了的模型。\n" +
                "试了 qwen-plus、qwen-max、qwen-turbo，都没开通。",
            s.probeNote,
        )
        assertEquals(p.id, s.pickToProbe)
        // 用户选了一个:先试调用,没开通就说原因、不选
        var picked = 0
        vm.pickManual(ModelRole.Main, p.id, "antdigital/Ling-3.1-flash") { picked++ }; runCurrent()
        assertEquals(
            ListState.Failed("这个账号没开通「antdigital/Ling-3.1-flash」或没权限用（服务商说：The product is not activated.）。换一个模型，或到服务商后台开通。"),
            vm.state.value.manualPick[p.id],
        )
        assertEquals(0, picked)
        assertNull(store.roles.value.main)
        reply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
        vm.pickManual(ModelRole.Main, p.id, "qwen-turbo") { picked++ }; runCurrent()
        assertEquals(1, picked)
        assertEquals("qwen-turbo", store.configFor(ModelRole.Main)?.model)
        assertNull(vm.state.value.probeNote)
        assertNull(vm.state.value.pickToProbe)
    }

    /** 第一个就是 Key 不对(或别的不是模型的问题):不试别的,不存。 */
    @Test fun authErrorOnTheFirstCandidateSavesNothing() = runTest {
        listing[qwenUrl] = ModelListResult.Ok(listOf(ListedModel("qwen-plus"), ListedModel("qwen-max")))
        reply = { throw ModelError.Auth() }
        val store = store()
        val vm = vm(store)
        vm.selectPreset("通义千问"); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        assertEquals(listOf("qwen-plus"), built.map { it.first })
        assertTrue(vm.state.value.form.test is ListState.Failed)
        assertTrue(store.providers.value.isEmpty())
        assertNull(vm.state.value.pickToProbe)
    }

    /** 试调用失败(例如服务商要的请求头没带):服务端的报错原样给人看,什么都不存。 */
    @Test fun failedTinyRequestShowsTheServerErrorAndSavesNothing() = runTest {
        listing[deepseekUrl] = deepseek
        reply = { throw ModelError.BadResponse("HTTP 400 {\"error\":{\"type\":\"MissingSessionID\"}}") }
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1"); vm.testAndSave(); runCurrent()
        val failed = vm.state.value.form.test as ListState.Failed
        assertTrue(failed.message, failed.message.contains("MissingSessionID"))
        assertTrue(failed.message.contains("deepseek-chat"))
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
        assertTrue(vm.state.value.showForm)
    }

    @Test fun tinyRequestThatNeverAnswersTimesOut() = runTest {
        listing[deepseekUrl] = deepseek
        reply = { awaitCancellation() }
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk-1"); vm.testAndSave(); runCurrent()
        assertEquals(ListState.Running, vm.state.value.form.test)
        advanceTimeBy(ModelSetupViewModel.PROBE_TIMEOUT_MS + 1); runCurrent()
        val failed = vm.state.value.form.test as ListState.Failed
        assertTrue(failed.message, failed.message.contains("20"))
        assertTrue(store.providers.value.isEmpty())
    }

    /** 预设声明了会话头的(OpenCode Go):试调用就带上,存下的服务商也记着它。 */
    @Test fun presetSessionHeaderIsUsedForTheTestAndRemembered() = runTest {
        val go = ProviderPresets.all.first { it.sessionHeader != null }
        listing[go.baseUrl] = ModelListResult.Ok(listOf(ListedModel("glm-x", toolCalls = true)))
        val store = store()
        val vm = vm(store)
        vm.selectPreset(go.label); vm.setApiKey("k"); vm.testAndSave(); runCurrent()
        val setup = built.first().second
        assertTrue(setup.headers[go.sessionHeader!!].orEmpty().isNotEmpty())
        assertEquals(go.sessionHeader, store.providers.value.single().sessionHeader)
    }

    /** 没列出模型:先不存、不试,要一个模型名;填了再测试就用它试调用,通了存成不提供列表的服务商,它当主模型、记为核实过。 */
    @Test fun noListAsksForAModelAndProbesIt() = runTest {
        val url = "http://192.168.1.5:8000/v1"
        listing[url] = ModelListResult.Failed(ListFailure.NotSupported)
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.testAndSave(); runCurrent()
        assertTrue(probes.isEmpty())
        assertTrue("没存", store.providers.value.isEmpty())
        val f = vm.state.value.form
        assertTrue(f.noList)
        assertEquals(ListState.Idle, f.test)
        assertFalse("没填模型名不能测试", f.canTest)
        vm.setApiKey("k")
        assertTrue("换 Key 不收起模型名", vm.state.value.form.noList)
        vm.setModel(" qwen3 ")
        assertTrue(vm.state.value.form.canTest)
        vm.testAndSave(); runCurrent()
        assertEquals("qwen3", built.single().first)
        val p = store.providers.value.single()
        assertEquals(emptyList<ListedModel>(), p.models)
        assertEquals(setOf("qwen3"), p.verifiedModels)
        assertEquals("qwen3", store.configFor(ModelRole.Main)?.model)
        assertFalse(vm.state.value.showForm)
        assertEquals(emptyList<String>(), vm.state.value.unverified(p))
    }

    /** 没列出模型、手填的模型试调用也说找不到:不存,说清是哪个模型、地址或接口类型可能不对,例子按地址对得上的那家。 */
    @Test fun noListAndModelNotFoundSavesNothing() = runTest {
        val url = "https://generativelanguage.googleapis.com"
        listing[url] = ModelListResult.Failed(ListFailure.NotSupported)
        reply = { throw ModelError.ModelNotFound("gemini") }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ProviderPresets.custom.label); vm.setBaseUrl(url); vm.setApiKey("k")
        vm.testAndSave(); runCurrent()
        vm.setModel("gemini"); vm.testAndSave(); runCurrent()
        assertEquals(
            ListState.Failed("列不出模型，用「gemini」试调用也说找不到：地址或接口类型可能填错了，也可能没有这个模型（模型名例如 gemini-2.5-flash）。"),
            vm.state.value.form.test,
        )
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
        // 换了地址:要重新列,模型名先收起
        vm.setBaseUrl("$url/")
        assertFalse(vm.state.value.form.noList)
    }

    /** 预设(列表接口照理有)没列出模型、手填的找不到:只说模型不存在。 */
    @Test fun presetWithoutListReportsTheMissingModel() = runTest {
        val ark = ProviderPresets.all.first { it.label == "火山方舟" }
        listing[ark.baseUrl] = ModelListResult.Failed(ListFailure.NotSupported)
        reply = { throw ModelError.ModelNotFound("doubao") }
        val store = store()
        val vm = vm(store)
        vm.selectPreset(ark.label); vm.setApiKey("k"); vm.testAndSave(); runCurrent()
        assertTrue(vm.state.value.form.noList)
        vm.setModel("doubao"); vm.testAndSave(); runCurrent()
        assertEquals(ListState.Failed("模型「doubao」不存在。要填服务商文档里完整的模型 ID（例如 deepseek-chat）。"), vm.state.value.form.test)
        assertTrue(store.providers.value.isEmpty())
    }

    /** 自定义填了某家的官方地址:建议改用那家的预设;地址就是预设的、接口类型不同时一并说;改用时 Key 留着。 */
    @Test fun officialHostSuggestsThePreset() = runTest {
        val vm = vm()
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl("https://models.example.com/v1")
        assertNull(vm.state.value.form.suggestedPreset)
        vm.setBaseUrl("https://generativelanguage.googleapis.com")
        vm.setApiKey("gk")
        val f = vm.state.value.form
        assertEquals("Gemini", f.suggestedPreset?.label)
        assertTrue(f.presetKindMismatch)
        assertEquals(
            "这是「Gemini」的官方地址，用它的预设更省事：地址和接口类型都已填好。接口类型也对不上：「Gemini」用的是 Gemini 接口，这里选的是 OpenAI 兼容。",
            ModelSetupCopy.presetSuggestion(f),
        )
        assertEquals(ProviderConfig.Kind.Gemini, f.exampleKind)
        // 同一主机的别的路径(如它的兼容接口):照样建议,但不说接口类型不对
        vm.setBaseUrl("https://generativelanguage.googleapis.com/v1beta/openai")
        assertEquals("Gemini", vm.state.value.form.suggestedPreset?.label)
        assertFalse(vm.state.value.form.presetKindMismatch)
        vm.setKind(ProviderConfig.Kind.Gemini)
        vm.setBaseUrl("https://api.anthropic.com")
        assertEquals("Anthropic", vm.state.value.form.suggestedPreset?.label)
        assertTrue(vm.state.value.form.presetKindMismatch)
        vm.selectPreset("Anthropic")
        val after = vm.state.value.form
        assertEquals(ProviderConfig.Kind.Anthropic, after.kind)
        assertEquals("gk", after.apiKey)
        assertNull("选了预设就不再建议", after.suggestedPreset)
    }

    /** 选择页里手填模型名:先试调用,不通不选、说原因;通了才选上、记为核实过。 */
    @Test fun manualPickProbesFirst() = runTest {
        val store = store()
        val p = store.connect("192.168.1.5", ProviderConfig.Kind.OpenAiCompat, "http://192.168.1.5:8000/v1", "", emptyList())
        val vm = vm(store)
        runCurrent()
        reply = { throw ModelError.ModelNotFound("gemini") }
        var picked = 0
        vm.pickManual(ModelRole.Main, p.id, "gemini") { picked++ }
        assertEquals(ListState.Running, vm.state.value.manualPick[p.id])
        runCurrent()
        assertEquals(ListState.Failed("模型「gemini」不存在。要填服务商文档里完整的模型 ID（例如 deepseek-chat）。"), vm.state.value.manualPick[p.id])
        assertEquals(0, picked)
        assertNull(store.roles.value.main)
        // 只回话不调用工具的,当不了主模型
        reply = { ModelReply(listOf(Part.Text("noon")), StopReason.EndTurn, null) }
        vm.pickManual(ModelRole.Main, p.id, "chatty") { picked++ }; runCurrent()
        assertEquals(ListState.Failed("chatty 没有调用工具，云开 干活需要会调用工具的模型，换一个当主模型。"), vm.state.value.manualPick[p.id])
        assertNull(store.roles.value.main)
        reply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
        vm.pickManual(ModelRole.Main, p.id, " qwen3 ") { picked++ }; runCurrent()
        assertEquals(1, picked)
        assertNull(vm.state.value.manualPick[p.id])
        assertEquals("qwen3", store.configFor(ModelRole.Main)?.model)
        assertEquals(setOf("qwen3"), store.provider(p.id)?.verifiedModels)
    }

    /** 旧版本存下的不提供列表的服务商(手填的模型从没试过):提醒,「重新测试」通了提醒消失、记下;不通说原因。 */
    @Test fun unverifiedManualModelsWarnUntilRetested() = runTest {
        val store = store()
        val p = store.connect("generativelanguage.googleapis.com", ProviderConfig.Kind.OpenAiCompat, "https://generativelanguage.googleapis.com", "k", emptyList())
        store.use(ModelRole.Main, p.id, "gemini", ProviderPresets.custom.capabilities)
        val vm = vm(store)
        runCurrent()
        assertEquals(listOf("gemini"), vm.state.value.unverified(vm.state.value.providers.single()))
        reply = { throw ModelError.ModelNotFound("gemini") }
        vm.retest(p.id); runCurrent()
        assertEquals(
            ListState.Failed("模型「gemini」不存在。要填服务商文档里完整的模型 ID（例如 gemini-2.5-flash）。"),
            vm.state.value.retest[p.id],
        )
        assertEquals(listOf("gemini"), vm.state.value.unverified(vm.state.value.providers.single()))
        reply = { ModelReply(listOf(Part.ToolCall("c1", "get_time", "{}")), StopReason.ToolUse, null) }
        vm.retest(p.id); runCurrent()
        assertNull(vm.state.value.retest[p.id])
        assertEquals(emptyList<String>(), vm.state.value.unverified(vm.state.value.providers.single()))
        // 有列表的服务商不提醒
        listing[deepseekUrl] = deepseek
        vm.openForm(); vm.selectPreset("DeepSeek"); vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        val ds = vm.state.value.providers.first { it.name == "DeepSeek" }
        vm.pick(ModelRole.Light, ds.id, "deepseek-coder"); runCurrent()
        assertEquals(emptyList<String>(), vm.state.value.unverified(vm.state.value.providers.first { it.name == "DeepSeek" }))
    }

    /** 明文 http:本机、局域网的不提示;别的地址提醒一句,照样能测试。 */
    @Test fun plainHttpToARemoteHostWarnsButDoesNotBlock() = runTest {
        val vm = vm()
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setBaseUrl("http://192.168.1.10:11434/v1")
        assertNull(vm.state.value.form.unencryptedWarning)
        vm.setBaseUrl("https://models.example.com/v1")
        assertNull(vm.state.value.form.unencryptedWarning)
        vm.setBaseUrl("http://models.example.com/v1")
        assertEquals("这个地址不加密，API Key 和对话内容可能被同一网络里的人看到。", vm.state.value.form.unencryptedWarning)
        vm.setApiKey("k")
        assertTrue(vm.state.value.form.canTest)
    }

    @Test fun addingTwoProvidersInARowKeepsTheFirstMain() = runTest {
        listing[deepseekUrl] = deepseek
        listing[qwenUrl] = ModelListResult.Ok(listOf(ListedModel("qwen-vl", vision = true), ListedModel("qwen-max")))
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        val main = store.roles.value.main
        vm.openForm()
        assertTrue(vm.state.value.showForm)
        vm.selectPreset("通义千问")
        vm.setApiKey("qk"); vm.testAndSave(); runCurrent()
        assertEquals(listOf("DeepSeek", "通义千问"), vm.state.value.providers.map { it.name })
        assertEquals(listOf("sk", "qk"), store.providers.value.map { store.providerKey(it.id) })
        assertEquals("第二家不改主模型", main, store.roles.value.main)
        assertEquals(1, store.list().size)
        assertFalse(vm.state.value.showForm)
    }

    @Test fun visionCanComeFromAnotherProvider() = runTest {
        listing[deepseekUrl] = deepseek
        listing[qwenUrl] = ModelListResult.Ok(listOf(ListedModel("qwen-max"), ListedModel("qwen-plain", toolCalls = false)))
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        vm.openForm(); vm.selectPreset("通义千问"); vm.setApiKey("qk"); vm.testAndSave(); runCurrent()
        val qwen = vm.state.value.providers.first { it.name == "通义千问" }
        // 选择页列出所有服务商的全部模型,不按能力过滤
        assertEquals(listOf(3, 2), vm.state.value.providers.map { vm.state.value.options(it).size })
        vm.pick(ModelRole.Vision, qwen.id, "qwen-plain"); runCurrent()
        val vision = store.configFor(ModelRole.Vision)!!
        assertEquals("qwen-plain", vision.model)
        assertEquals(qwen.id, vision.providerId)
        assertTrue("选了看图就记看图", vision.capabilities.vision)
        assertEquals("qk", store.apiKey(vision.id))
        assertEquals("deepseek-chat", store.configFor(ModelRole.Main)?.model)
        assertTrue(vm.state.value.picked(ModelRole.Vision, qwen.id, "qwen-plain"))
        assertEquals(listOf(ModelRole.Vision), vm.state.value.rolesOf(qwen))
        // 用主模型:看图这一路用主模型,不再有单独的看图配置
        vm.choose(ModelRole.Vision, RoleChoice.SameAsMain); runCurrent()
        assertEquals("deepseek-chat", store.configFor(ModelRole.Vision)?.model)
        assertFalse(vm.state.value.picked(ModelRole.Vision, qwen.id, "qwen-plain"))
        assertEquals(1, store.list().size)
        assertEquals(emptyList<ModelRole>(), vm.state.value.rolesOf(qwen))
        // 不用
        vm.choose(ModelRole.Vision, RoleChoice.Off); runCurrent()
        assertNull(store.configFor(ModelRole.Vision))
        assertEquals(RoleChoice.Off, vm.state.value.roles.vision)
    }

    @Test fun pickingMainMarksToolCallsAndSwitchesProvider() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        val p = store.providers.value.single()
        vm.pick(ModelRole.Main, p.id, "deepseek-coder"); runCurrent()
        val main = store.configFor(ModelRole.Main)!!
        assertEquals("deepseek-coder", main.model)
        assertTrue(main.capabilities.toolCalls)
        vm.pick(ModelRole.Main, p.id, "deepseek-chat"); runCurrent()
        assertEquals("deepseek-chat", store.configFor(ModelRole.Main)?.model)
        assertEquals("没人用的配置删掉", 1, store.list().size)
    }

    @Test fun nextNeedsAMainModel() = runTest {
        val store = store()
        val vm = vm(store)
        vm.selectPreset("自定义")
        vm.setBaseUrl("http://192.168.1.5:8000/v1")
        assertTrue("自定义的 OpenAI 兼容接口可以不填 Key", vm.state.value.form.canTest)
        listing["http://192.168.1.5:8000/v1"] = ModelListResult.Failed(ListFailure.NotSupported)
        vm.testAndSave(); runCurrent()
        // 服务不提供列表:先要一个模型名,没存
        assertTrue(store.providers.value.isEmpty())
        assertFalse(vm.state.value.canNext)
        // 模型只回话、不调用工具:存下服务商,但不当主模型
        reply = { ModelReply(listOf(Part.Text("noon")), StopReason.EndTurn, null) }
        vm.setModel("chatty"); vm.testAndSave(); runCurrent()
        val p = store.providers.value.single()
        assertEquals("192.168.1.5", p.name)
        assertEquals(emptyList<ListedModel>(), p.models)
        assertFalse(vm.state.value.canNext)
        vm.pick(ModelRole.Main, p.id, " qwen3 "); runCurrent()
        assertEquals("qwen3", store.configFor(ModelRole.Main)?.model)
        assertTrue(vm.state.value.canNext)
        assertEquals(listOf("qwen3"), vm.state.value.options(vm.state.value.providers.single()).map { it.id })
    }

    @Test fun pickerListsProvidersWithoutRememberedListOnce() = runTest {
        val store = store()
        store.save(ProviderConfig("old", ProviderConfig.Kind.Anthropic, "https://api.anthropic.com", "claude-x",
            Capabilities(true, true, 200_000, false), "Anthropic"), "ak")
        store.choose(ModelRole.Main, RoleChoice.Model("old"))
        listing["https://api.anthropic.com"] = ModelListResult.Ok(listOf(ListedModel("claude-x"), ListedModel("claude-y")))
        val vm = vm(store)
        runCurrent()
        val p = vm.state.value.providers.single()
        assertNull(p.models)
        assertEquals(listOf("claude-x"), vm.state.value.options(p).map { it.id })
        vm.ensureListed(); runCurrent()
        vm.ensureListed(); runCurrent()
        assertEquals(1, listCalls.size)
        assertEquals("ak", listCalls.single().third)
        assertEquals(listOf("claude-x", "claude-y"), vm.state.value.options(vm.state.value.providers.single()).map { it.id })
        assertTrue("已有的配置保留它的能力", vm.state.value.options(vm.state.value.providers.single()).first().capabilities.vision)
    }

    @Test fun refreshFailureIsShownAndRemovingProviderDropsItsRoles() = runTest {
        listing[deepseekUrl] = deepseek
        val store = store()
        val vm = vm(store)
        vm.setApiKey("sk"); vm.testAndSave(); runCurrent()
        val p = store.providers.value.single()
        listing[deepseekUrl] = ModelListResult.Failed(ListFailure.Network)
        vm.refresh(p.id); runCurrent()
        assertEquals(ListState.Failed(ListFailure.Network.text), vm.state.value.listing[p.id])
        assertEquals("失败时保留原来的列表", 3, store.providers.value.single().models?.size)
        vm.changeKey(p.id, "sk2"); runCurrent()
        assertEquals("sk2", store.providerKey(p.id))
        vm.removeProvider(p.id); runCurrent()
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
        assertEquals(com.wangzan101.mika.settings.RoleChoices(), store.roles.value)
        assertFalse(vm.state.value.canNext)
        assertTrue(vm.state.value.showForm)
    }

    @Test fun copyTagsMarkGuessedCapabilities() {
        val o = com.wangzan101.mika.settings.ModelOptions.merge(
            listOf(ListedModel("a"), ListedModel("b", toolCalls = true, contextTokens = 64_000), ListedModel("c", toolCalls = false)),
            Capabilities(true, false, 64_000, false),
        )
        assertEquals(listOf("工具调用 · 未确认" to false), ModelSetupCopy.tags(o[0]))
        assertEquals(listOf("工具调用" to true, "64K" to false), ModelSetupCopy.tags(o[1]))
        assertEquals(listOf("不支持工具调用" to false), ModelSetupCopy.tags(o[2]))
    }
}
