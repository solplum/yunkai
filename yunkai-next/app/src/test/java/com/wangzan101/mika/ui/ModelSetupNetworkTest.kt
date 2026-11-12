package com.wangzan101.mika.ui

import com.wangzan101.mika.core.model.ListFailure
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.ProviderStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

/**
 * 「测试并保存」走真的列模型接口和适配器:自定义的 OpenAI 兼容接口,对着一个按路径回话的本地服务。
 * 列表接口 404 时分得出「这家真不提供列表」(手填的模型调得通)和「地址、接口类型填错了」(调用也 404)。
 */
class ModelSetupNetworkTest {
    @get:Rule val texts = ChineseTexts()

    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 路径 → 回什么;没写的路径回 404 网页。[chat] 给了时按请求里的模型名回调用接口。 */
    private val routes = mutableMapOf<String, () -> MockResponse>()
    private var chat: ((model: String) -> MockResponse)? = null
    private val chatModels = mutableListOf<String>()

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path?.substringBefore('?')
                chat?.takeIf { path == "/chat/completions" }?.let { answer ->
                    val model = Regex("\"model\"\\s*:\\s*\"([^\"]+)\"").find(request.body.readUtf8())?.groupValues?.get(1).orEmpty()
                    synchronized(chatModels) { chatModels += model }
                    return answer(model)
                }
                return routes[path]?.invoke()
                    ?: MockResponse().setResponseCode(404).setHeader("Content-Type", "text/html").setBody("<html><body>Not Found</body></html>")
            }
        }
        server.start()
    }

    @After fun stop() {
        scope.cancel()
        server.shutdown()
    }

    private val base get() = server.url("/").toString().trimEnd('/')

    private fun toolCall() = MockResponse().setHeader("Content-Type", "application/json").setBody(
        """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function",""" +
            """"function":{"name":"get_time","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
    )

    private fun setUp(): Pair<ProviderStore, ModelSetupViewModel> {
        val store = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Mika/1.2.3")
        val vm = ModelSetupViewModel(store, scope, io = Dispatchers.IO)
        vm.selectPreset(ProviderPresets.custom.label)
        vm.setKind(ProviderConfig.Kind.OpenAiCompat)
        vm.setBaseUrl(base)
        vm.setApiKey("sk")
        return store to vm
    }

    private fun ModelSetupViewModel.testAndWait() = runBlocking {
        testAndSave()
        withTimeout(15_000) { state.first { it.form.test != ListState.Running } }
    }

    @Test fun listAndChatBoth404MeansNothingIsSaved() {
        val (store, vm) = setUp()
        vm.testAndWait()
        assertTrue("先要一个模型名", vm.state.value.form.noList)
        assertTrue(store.providers.value.isEmpty())
        vm.setModel("gemini")
        vm.testAndWait()
        val failed = vm.state.value.form.test as ListState.Failed
        assertTrue(failed.message, failed.message.startsWith("列不出模型，用「gemini」试调用也说找不到：地址或接口类型可能填错了"))
        assertTrue(store.providers.value.isEmpty())
        assertTrue(store.list().isEmpty())
    }

    @Test fun list404ButChatWorksSavesANoListProvider() {
        routes["/chat/completions"] = ::toolCall
        val (store, vm) = setUp()
        vm.testAndWait()
        vm.setModel("my-model")
        vm.testAndWait()
        val p = store.providers.value.single()
        assertEquals(emptyList<Any>(), p.models)
        assertEquals(setOf("my-model"), p.verifiedModels)
        assertEquals("my-model", store.configFor(ModelRole.Main)?.model)
        assertFalse(vm.state.value.showForm)
        // 记下的「核实过」重新读入后还在
        assertEquals(setOf("my-model"), ProviderStore(tmp.root, AesGcmKeyCipher { key }).provider(p.id)?.verifiedModels)
        assertEquals(listOf("/models", "/models", "/chat/completions"), List(server.requestCount) { server.takeRequest().path })
    }

    /** 列表里先是别家的模型:没开通的那个调用回 400,接着试下一个,通了就存下、它当主模型,说清先试的哪个没通过。 */
    @Test fun notActivatedFirstModelFallsBackToTheNext() {
        routes["/models"] = ::thirdPartyFirst
        chat = { model -> if (model == "qwen-plus") toolCall() else notActivated() }
        val (store, vm) = setUp()
        vm.testAndWait()
        assertEquals(listOf("antdigital/Ling-3.1-flash", "qwen-plus"), chatModels)
        val p = store.providers.value.single()
        assertEquals("qwen-plus", store.configFor(ModelRole.Main)?.model)
        assertFalse(vm.state.value.showForm)
        val note = vm.state.value.probeNote.orEmpty()
        assertTrue(note, note.startsWith("先试的 antdigital/Ling-3.1-flash 没测试通过：这个账号没开通或没权限用这个模型（服务商说：The product is not activated"))
        assertTrue(note, note.endsWith("主模型用了测试通过的「qwen-plus」。"))
        assertEquals(null, vm.state.value.pickToProbe)
        assertTrue("qwen-plus" in p.verifiedModels)
    }

    /** 试的几个都没开通:Key 是对的,存下服务商、不定主模型,请用户从列表里选。 */
    @Test fun noCandidateActivatedSavesProviderWithoutMain() {
        routes["/models"] = ::thirdPartyFirst
        chat = { notActivated() }
        val (store, vm) = setUp()
        vm.testAndWait()
        assertEquals(listOf("antdigital/Ling-3.1-flash", "qwen-plus", "qwen-max"), chatModels)
        val p = store.providers.value.single()
        assertEquals(4, p.models?.size)
        assertEquals(null, store.roles.value.main)
        val s = vm.state.value
        assertEquals(p.id, s.pickToProbe)
        assertTrue(s.probeNote.orEmpty(), s.probeNote.orEmpty().startsWith("服务商已连上，但「antdigital/Ling-3.1-flash」没测试通过："))
        assertTrue(s.probeNote.orEmpty(), s.probeNote.orEmpty().endsWith("试了 antdigital/Ling-3.1-flash、qwen-plus、qwen-max，都没开通。"))
    }

    private fun thirdPartyFirst() = MockResponse().setHeader("Content-Type", "application/json").setBody(
        """{"object":"list","data":[{"id":"antdigital/Ling-3.1-flash"},{"id":"text-embedding-v4"},{"id":"qwen-plus"},{"id":"qwen-max"}]}""",
    )

    private fun notActivated() = MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json").setBody(
        """{"code":"InvalidParameter","message":"The product is not activated, please confirm that you have activated products and try again after activation.","request_id":"r1"}""",
    )

    @Test fun badKeyIsAKeyErrorAndNothingIsSaved() {
        routes["/models"] = { MockResponse().setResponseCode(401).setBody("""{"error":{"message":"invalid api key"}}""") }
        val (store, vm) = setUp()
        vm.testAndWait()
        assertEquals(ListState.Failed(ListFailure.Auth.text), vm.state.value.form.test)
        assertFalse("Key 不对不要模型名", vm.state.value.form.noList)
        assertTrue(store.providers.value.isEmpty())
    }
}
