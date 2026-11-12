package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelEndpoint
import com.wangzan101.mika.core.model.ModelProtocol
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.usage.UsagePurpose
import com.wangzan101.mika.core.usage.UsageSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

class ProviderStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val built = mutableListOf<Pair<ProviderConfig, String?>>()
    private val endpoints = mutableListOf<ModelEndpoint?>()

    private val setups = mutableListOf<ClientSetup>()

    private fun store() = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Mika/1.2.3") { cfg, apiKey, endpoint, setup ->
        built += cfg to apiKey
        setups += setup
        endpoints += endpoint
        FakeModel(cfg.capabilities)
    }

    private fun cfg(id: String, tools: Boolean = true, vision: Boolean = false) = ProviderConfig(
        id = id, kind = ProviderConfig.Kind.OpenAiCompat, baseUrl = "https://example.test/v1", model = "m-$id",
        capabilities = Capabilities(toolCalls = tools, vision = vision, contextTokens = 64_000, reasoning = false),
        name = "P $id",
    )

    private val none = Capabilities(toolCalls = false, vision = false, contextTokens = 64_000, reasoning = false)

    /** 存一条配置并让它当主模型(没人用的配置会被清掉)。 */
    private fun ProviderStore.saveAsMain(c: ProviderConfig, apiKey: String?) {
        save(c, apiKey)
        assertTrue(choose(ModelRole.Main, RoleChoice.Model(c.id)))
    }

    private fun connected(s: ProviderStore, name: String = "P", key: String = "k") =
        s.connect(name, ProviderConfig.Kind.OpenAiCompat, "https://${name.lowercase()}.test/v1", key, listOf(ListedModel("m1"), ListedModel("m2")))

    // ---------- 用途的选择 ----------

    @Test
    fun freshInstallHasNoMainVisionOffAndLightSameAsMain() {
        val s = store()
        assertEquals(RoleChoices(main = null, vision = RoleChoice.Off, light = RoleChoice.SameAsMain), s.roles.value)
        assertNull(s.modelFor(ModelRole.Main))
        assertNull(s.modelFor(ModelRole.Vision))
        assertNull(s.modelFor(ModelRole.Light))
    }

    @Test
    fun lightSameAsMainResolvesToMainAndVisionStaysOffEvenIfMainCanSee() {
        val s = store()
        s.saveAsMain(cfg("a", vision = true), "sk-a")
        assertEquals("a", s.configFor(ModelRole.Light)?.id)
        // 不再有「主模型能看图就兼任看图」的隐含规则
        assertNull(s.configFor(ModelRole.Vision))
        val m = s.modelFor(ModelRole.Main)
        assertEquals("套上计量后能力照旧", cfg("a", vision = true).capabilities, m?.capabilities)
        assertEquals("a" to "sk-a", built.single().first.id to built.single().second)
    }

    @Test
    fun visionSameAsMainUsesMainAndSendsImagesWithoutChangingMainItself() {
        val s = store()
        s.saveAsMain(cfg("a", vision = false), "sk-a")
        assertTrue(s.choose(ModelRole.Vision, RoleChoice.SameAsMain))
        val v = s.configFor(ModelRole.Vision)!!
        assertEquals("a", v.id)
        assertTrue("用户说主模型能看图,看图这一路照发截图", v.capabilities.vision)
        s.modelFor(ModelRole.Vision)
        assertTrue(built.last().first.capabilities.vision)
        assertEquals("sk-a", built.last().second)
        assertFalse("主模型自己办事仍按它存着的标记", s.configFor(ModelRole.Main)!!.capabilities.vision)
        // 重开仍在
        assertEquals(RoleChoice.SameAsMain, store().roles.value.vision)
    }

    @Test
    fun visionSameAsMainWithoutMainHasNoVisionModel() {
        val s = store()
        assertTrue(s.choose(ModelRole.Vision, RoleChoice.SameAsMain))
        assertNull(s.configFor(ModelRole.Vision))
    }

    @Test
    fun choicesTheRoleDoesNotTakeAreRejected() {
        val s = store()
        s.saveAsMain(cfg("a"), "sk-a")
        assertFalse(s.choose(ModelRole.Main, RoleChoice.SameAsMain))
        assertFalse(s.choose(ModelRole.Main, RoleChoice.Off))
        assertFalse(s.choose(ModelRole.Light, RoleChoice.Off))
        assertFalse("不存在的配置", s.choose(ModelRole.Main, RoleChoice.Model("zzz")))
        assertEquals(RoleChoices(main = "a"), s.roles.value)
    }

    @Test
    fun explicitChoiceIsNotBlockedByCapabilityFlagsAndMarksThem() {
        val s = store()
        val p = connected(s)
        assertTrue(s.use(ModelRole.Main, p.id, "m1", none))
        assertTrue(s.use(ModelRole.Vision, p.id, "m2", none))
        val main = s.configFor(ModelRole.Main)!!
        val vision = s.configFor(ModelRole.Vision)!!
        assertEquals("m1" to "m2", main.model to vision.model)
        assertTrue("选作主模型就记工具调用", main.capabilities.toolCalls)
        assertTrue("选作看图就记看图", vision.capabilities.vision)
        assertTrue(store().list().first { it.model == "m2" }.capabilities.vision)
        // 轻量不要求任何能力
        assertTrue(s.use(ModelRole.Light, p.id, "m2", none))
        assertEquals(vision.id, s.configFor(ModelRole.Light)?.id)
        assertFalse(s.use(ModelRole.Main, "missing", "m1", none))
    }

    @Test
    fun usingTheSameModelReusesItsConfig() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        s.use(ModelRole.Light, p.id, "m1", none)
        assertEquals(1, s.list().size)
        assertEquals(s.roles.value.main, (s.roles.value.light as RoleChoice.Model).configId)
        assertEquals("k", s.apiKey(s.roles.value.main!!))
    }

    @Test
    fun lightUsersGetTheLightModelAndNotTheMainOne() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        s.use(ModelRole.Light, p.id, "m2", none)
        built.clear()
        s.modelFor(ModelRole.Light)
        assertEquals("m2" to "k", built.single().first.model to built.single().second)
        built.clear()
        s.modelFor(ModelRole.Main)
        assertEquals("m1", built.single().first.model)
    }

    // ---------- 没人用的配置删掉 ----------

    @Test
    fun configsNoRoleUsesAreDeletedButTheProviderKeyStays() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        s.use(ModelRole.Light, p.id, "m2", none)
        assertEquals(listOf("m1", "m2"), s.list().map { it.model })
        // 轻量改回用主模型:m2 没人用了
        assertTrue(s.choose(ModelRole.Light, RoleChoice.SameAsMain))
        assertEquals(listOf("m1"), s.list().map { it.model })
        // 换主模型:m1 没人用了
        s.use(ModelRole.Main, p.id, "m2", none)
        assertEquals(listOf("m2"), s.list().map { it.model })
        assertEquals("k", s.providerKey(p.id))
        assertEquals("k", s.apiKey(s.list().single().id))
        assertEquals(listOf("m2"), store().list().map { it.model })
    }

    @Test
    fun visionSameAsMainKeepsTheMainConfigAndNothingElse() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        s.use(ModelRole.Vision, p.id, "m2", none)
        s.choose(ModelRole.Vision, RoleChoice.SameAsMain)
        assertEquals(listOf("m1"), s.list().map { it.model })
        assertEquals(setOf(s.roles.value.main), s.roles.value.referenced)
    }

    @Test
    fun loadDropsUnusedConfigsAndChoicesPointingNowhere() {
        val s = store()
        s.saveAsMain(cfg("a"), "sk-a")
        s.save(cfg("b"), null)
        assertEquals(2, s.list().size)
        val reopened = store()
        assertEquals(listOf("a"), reopened.list().map { it.id })
        // 手改坏的文件:看图指向不存在的配置,退回不用
        val f = File(tmp.root, "providers.json")
        f.writeText(f.readText().replace("\"Vision\":{\"use\":\"none\"}", "\"Vision\":{\"use\":\"model\",\"config\":\"gone\"}"))
        assertEquals(RoleChoice.Off, store().roles.value.vision)
    }

    // ---------- Key ----------

    @Test
    fun keyIsNotStoredInPlainPrefs() {
        val secret = "sk-very-secret-0123456789"
        store().saveAsMain(cfg("a"), secret)
        tmp.root.walkTopDown().filter { it.isFile }.forEach { f ->
            assertFalse("plaintext key found in ${f.name}", f.readBytes().toString(Charsets.ISO_8859_1).contains(secret))
        }
        assertEquals(secret, store().apiKey("a"))
    }

    @Test
    fun saveWithNullKeyKeepsExistingKey() {
        val s = store()
        s.saveAsMain(cfg("a"), "sk-a")
        s.save(cfg("a").copy(model = "other"), null)
        assertEquals("sk-a", s.apiKey("a"))
        assertEquals("other", s.list().single().model)
        assertTrue(s.hasKey(s.list().single().providerId))
    }

    @Test
    fun undecryptableKeyReadsAsMissing() {
        store().saveAsMain(cfg("a"), "sk-a")
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(ProviderStore(tmp.root, AesGcmKeyCipher { otherKey }).apiKey("a"))
    }

    /** 试调用通过的模型名随服务商存下;再连一次同一家时合并;旧文件没有这一项时为空。 */
    @Test
    fun verifiedModelsPersist() {
        val s = store()
        val p = s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k", emptyList(), verified = setOf("a"))
        assertEquals(setOf("a"), p.verifiedModels)
        s.markVerified(p.id, "b")
        s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", null, emptyList(), verified = setOf("c"))
        assertEquals(setOf("a", "b", "c"), store().provider(p.id)?.verifiedModels)
        val f = File(tmp.root, "providers.json")
        f.writeText(f.readText().replace(Regex(",\"verifiedModels\":\\[[^\\]]*\\]"), ""))
        assertFalse(f.readText().contains("verifiedModels"))
        assertEquals(emptySet<String>(), store().provider(p.id)?.verifiedModels)
    }

    @Test
    fun connectedProviderWithoutModelsPersistsWithItsListAndKey() {
        val s = store()
        val listed = listOf(ListedModel("a", displayName = "A", toolCalls = true, contextTokens = 128_000), ListedModel("b", vision = false))
        val p = s.connect("DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com", "sk-d", listed)
        assertTrue("连上了还没选模型", s.list().isEmpty())
        assertNull(s.roles.value.main)
        val reopened = store()
        assertEquals(listOf(p), reopened.providers.value)
        assertEquals(listed, reopened.providers.value.single().models)
        assertEquals("sk-d", reopened.providerKey(p.id))
        // 同一家再连一次:更新 Key 和列表,不重复添加
        val again = s.connect("DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com/", "sk-new", listOf(ListedModel("c")))
        assertEquals(p.id, again.id)
        assertEquals(1, s.providers.value.size)
        assertEquals("sk-new", s.providerKey(p.id))
        assertEquals(listOf("c"), store().providers.value.single().models!!.map { it.id })
        // 没列过的是 null,列过但没有的是空列表
        s.rememberModels(p.id, emptyList())
        assertEquals(emptyList<ListedModel>(), store().providers.value.single().models)
    }

    @Test
    fun modelsOfOneProviderShareOneKey() {
        val s = store()
        val p = connected(s, key = "k1")
        s.use(ModelRole.Main, p.id, "m1", none)
        s.use(ModelRole.Light, p.id, "m2", none)
        s.updateProviderKey(p.id, "k2")
        assertEquals(listOf("k2", "k2"), s.list().map { s.apiKey(it.id) })
        s.modelFor(ModelRole.Main)
        assertEquals("k2", built.last().second)
        // Key 文件里只有一份(按服务商存)
        val keys = kotlinx.serialization.json.Json.parseToJsonElement(File(tmp.root, "provider-keys.json").readText())
        assertEquals(setOf(p.id), (keys as kotlinx.serialization.json.JsonObject).keys)
    }

    @Test
    fun localServiceWithoutKeyHasNoKey() {
        val s = store()
        val p = s.connect("local", ProviderConfig.Kind.OpenAiCompat, "http://192.168.1.5:8000/v1", "", emptyList())
        assertFalse(s.hasKey(p.id))
    }

    @Test
    fun configsPublishedAsFlow() {
        val s = store()
        s.saveAsMain(cfg("a"), "sk-a")
        assertSame(s.list(), s.configs.value)
        assertEquals(1, s.configs.value.size)
    }

    // ---------- 删除服务商 ----------

    @Test
    fun removeProviderDropsItsModelsAndKeyAndResetsItsRoles() {
        val s = store()
        val p = connected(s, key = "k1")
        s.save(cfg("other"), "sk-o")
        s.choose(ModelRole.Main, RoleChoice.Model("other"))
        s.use(ModelRole.Vision, p.id, "m2", none)
        s.use(ModelRole.Light, p.id, "m1", none)
        s.removeProvider(p.id)
        assertEquals(listOf("other"), s.list().map { it.id })
        assertEquals(RoleChoices(main = "other", vision = RoleChoice.Off, light = RoleChoice.SameAsMain), s.roles.value)
        assertNull(s.providerKey(p.id))
        assertEquals(listOf("P other"), s.providers.value.map { it.name })
        // 重开后数据一致
        val reopened = store()
        assertEquals(listOf("other"), reopened.list().map { it.id })
        assertEquals(s.roles.value, reopened.roles.value)
        assertNull(reopened.providerKey(p.id))
        assertEquals("sk-o", reopened.apiKey("other"))
    }

    @Test
    fun removingTheMainProviderLeavesMainUnsetAndKeepsOtherRoles() {
        val s = store()
        val a = connected(s, "A")
        val b = connected(s, "B")
        s.use(ModelRole.Main, a.id, "m1", none)
        s.use(ModelRole.Vision, b.id, "m1", none)
        s.removeProvider(a.id)
        assertNull(s.roles.value.main)
        assertEquals(b.id, s.configFor(ModelRole.Vision)?.providerId)
        // 重开时不会把剩下的那条当成主模型(那是旧版本的规则)
        assertNull(store().roles.value.main)
        assertEquals(b.id, store().configFor(ModelRole.Vision)?.providerId)
    }

    // ---------- 旧版本的文件 ----------

    private fun enc(k: String): String = java.util.Base64.getEncoder().encodeToString(AesGcmKeyCipher { key }.encrypt(k.toByteArray()))

    private fun legacy(id: String, name: String, url: String, model: String, vision: Boolean = false) =
        """{"id":"$id","name":"$name","kind":"OpenAiCompat","baseUrl":"$url","model":"$model","toolCalls":true,"vision":$vision,""" +
            """"contextTokens":64000,"reasoning":false,"groundingVerified":false,"promptCaching":"implicit"}"""

    /** 旧版本的文件:只有模型配置,Key 按配置 id 存,角色只记显式指定的。 */
    private fun writeLegacyFiles(roles: String = """{"Main":"c2","Vision":"c3"}""") {
        File(tmp.root, "providers.json").writeText(
            """{"providers":[""" +
                legacy("c1", "DeepSeek", "https://api.deepseek.com", "deepseek-chat") + "," +
                legacy("c2", "DeepSeek", "https://api.deepseek.com/", "deepseek-reasoner") + "," +
                legacy("c3", "", "http://192.168.1.5:8000/v1", "local", vision = true) +
                """],"roles":$roles}""",
        )
        File(tmp.root, "provider-keys.json").writeText("""{"c1":"${enc("sk-ds")}","c2":"${enc("sk-ds")}","c3":"${enc("sk-local")}"}""")
    }

    @Test
    fun legacyConfigsAreGroupedIntoProvidersOnLoad() {
        writeLegacyFiles()
        val s = store()
        assertEquals(listOf("DeepSeek", "192.168.1.5"), s.providers.value.map { it.name })
        val ds = s.providers.value[0]
        val local = s.providers.value[1]
        assertNull("还没列过模型", ds.models)
        // Key 归到服务商
        assertEquals("sk-ds", s.providerKey(ds.id))
        assertEquals("sk-local", s.providerKey(local.id))
        // 显式指定的照旧;没人用的 c1 删掉
        assertEquals(RoleChoices(main = "c2", vision = RoleChoice.Model("c3"), light = RoleChoice.SameAsMain), s.roles.value)
        assertEquals(listOf("c2", "c3"), s.list().map { it.id })
        assertEquals(listOf(ds.id, local.id), s.list().map { it.providerId })
        assertEquals("deepseek-reasoner", s.configFor(ModelRole.Main)?.model)
        s.modelFor(ModelRole.Vision)
        assertEquals("c3" to "sk-local", built.last().first.id to built.last().second)
        // 按配置 id 存的旧 Key 清掉了
        val keys = kotlinx.serialization.json.Json.parseToJsonElement(File(tmp.root, "provider-keys.json").readText())
        assertEquals(setOf(ds.id, local.id), (keys as kotlinx.serialization.json.JsonObject).keys)
    }

    @Test
    fun legacyDefaultsAreWrittenOutAsTheyWorked() {
        // 没指定主模型:那时用第一个配置;它不能看图,所以看图不用;轻量跟随主模型
        writeLegacyFiles(roles = "{}")
        val s = store()
        assertEquals(RoleChoices(main = "c1", vision = RoleChoice.Off, light = RoleChoice.SameAsMain), s.roles.value)
        assertEquals(listOf("c1"), s.list().map { it.id })
        assertTrue(File(tmp.root, "providers.json").readText().contains("\"uses\""))
    }

    @Test
    fun legacyMainThatCanSeeKeepsServingVision() {
        writeLegacyFiles(roles = """{"Main":"c3","Light":"c1"}""")
        val s = store()
        assertEquals(RoleChoices(main = "c3", vision = RoleChoice.SameAsMain, light = RoleChoice.Model("c1")), s.roles.value)
        assertEquals("c3", s.configFor(ModelRole.Vision)?.id)
        assertEquals(listOf("c1", "c3"), s.list().map { it.id })
    }

    @Test
    fun legacyRolesPointingToMissingConfigsFallBackToTheOldDefaults() {
        writeLegacyFiles(roles = """{"Main":"gone","Vision":"gone"}""")
        val s = store()
        assertEquals(RoleChoices(main = "c1", vision = RoleChoice.Off, light = RoleChoice.SameAsMain), s.roles.value)
    }

    @Test
    fun migrationRunsOnceAndRepeatsWithoutSideEffects() {
        writeLegacyFiles(roles = """{"Main":"c3"}""")
        val first = store()
        val providers = first.providers.value
        val configs = first.list()
        val roles = first.roles.value
        val configText = File(tmp.root, "providers.json").readText()
        val keyText = File(tmp.root, "provider-keys.json").readText()
        repeat(2) {
            val again = store()
            assertEquals(providers, again.providers.value)
            assertEquals(configs, again.list())
            assertEquals(roles, again.roles.value)
            assertEquals(configText, File(tmp.root, "providers.json").readText())
            assertEquals(keyText, File(tmp.root, "provider-keys.json").readText())
        }
    }

    @Test
    fun interruptedMigrationIsRedoneWithoutLosingKeys() {
        writeLegacyFiles()
        store()
        // 模拟中途断掉:配置文件回到旧格式,Key 文件里新旧两份都在
        val migratedKeys = File(tmp.root, "provider-keys.json").readText()
        writeLegacyFiles()
        val legacyKeys = kotlinx.serialization.json.Json.parseToJsonElement(File(tmp.root, "provider-keys.json").readText())
            as kotlinx.serialization.json.JsonObject
        val newKeys = kotlinx.serialization.json.Json.parseToJsonElement(migratedKeys) as kotlinx.serialization.json.JsonObject
        File(tmp.root, "provider-keys.json").writeText(kotlinx.serialization.json.JsonObject(legacyKeys + newKeys).toString())
        val s = store()
        assertEquals(2, s.providers.value.size)
        assertEquals(listOf("sk-ds", "sk-local"), listOf("c2", "c3").map { s.apiKey(it) })
        val keys = kotlinx.serialization.json.Json.parseToJsonElement(File(tmp.root, "provider-keys.json").readText())
        assertEquals(s.providers.value.map { it.id }.toSet(), (keys as kotlinx.serialization.json.JsonObject).keys)
    }

    // ---------- 看图定位已验证 ----------

    @Test
    fun groundingVerifiedFollowsTheVisionModelAndPersists() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        // 没有看图模型时不动
        s.setVisionGroundingVerified(true)
        assertFalse(s.list().single().capabilities.groundingVerified)
        s.use(ModelRole.Vision, p.id, "m2", none)
        s.setVisionGroundingVerified(true)
        assertTrue(s.configFor(ModelRole.Vision)!!.capabilities.groundingVerified)
        assertFalse(s.configFor(ModelRole.Main)!!.capabilities.groundingVerified)
        assertTrue(store().configFor(ModelRole.Vision)!!.capabilities.groundingVerified)
        // 看图用主模型时改的是主模型那条
        s.choose(ModelRole.Vision, RoleChoice.SameAsMain)
        assertFalse(s.configFor(ModelRole.Vision)!!.capabilities.groundingVerified)
        s.setVisionGroundingVerified(true)
        assertTrue(s.configFor(ModelRole.Main)!!.capabilities.groundingVerified)
    }

    @Test
    fun roleChoicesResolveAndValidate() {
        val r = RoleChoices(main = "a", vision = RoleChoice.SameAsMain, light = RoleChoice.Model("b"))
        assertEquals("a", r.resolve(ModelRole.Vision))
        assertEquals("b", r.resolve(ModelRole.Light))
        assertEquals(setOf("a", "b"), r.referenced)
        assertNull(RoleChoices().resolve(ModelRole.Light))
        assertNull(r.with(ModelRole.Light, RoleChoice.Off))
        assertNull(r.with(ModelRole.Main, RoleChoice.SameAsMain))
        assertEquals(RoleChoices(main = null, vision = RoleChoice.SameAsMain, light = RoleChoice.SameAsMain), r.keepOnly(emptySet()))
    }

    // ---------- 按模型指定的接口 ----------

    private val chat = ModelEndpoint(ModelProtocol.ChatCompletions, "https://x.test/openai/v1", "reasoning_content")
    private val messages = ModelEndpoint(ModelProtocol.Messages, "https://x.test/anthropic/v1")
    private val generate = ModelEndpoint(ModelProtocol.GenerateContent, "https://x.test/google/v1beta")
    private val responses = ModelEndpoint(ModelProtocol.Responses, "https://x.test/openai/v1/")

    @Test
    fun perModelEndpointsPersistWithTheList() {
        val s = store()
        val listed = listOf(
            ListedModel("a", endpoint = chat), ListedModel("b", endpoint = messages), ListedModel("c", endpoint = generate),
            ListedModel("r", endpoint = responses), ListedModel("u", unsupportedApis = listOf("realtime")), ListedModel("plain"),
        )
        s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k", listed)
        assertEquals(listed, store().providers.value.single().models)
    }

    @Test
    fun eachModelIsBuiltWithTheEndpointItsListingNamed() = runTest {
        val s = store()
        val p = s.connect(
            "X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k",
            listOf(ListedModel("a", endpoint = chat), ListedModel("b", endpoint = messages), ListedModel("plain")),
        )
        s.use(ModelRole.Main, p.id, "b", none)
        s.use(ModelRole.Light, p.id, "plain", none)
        store().modelFor(ModelRole.Main)
        store().modelFor(ModelRole.Light)
        assertEquals(listOf(messages, null), endpoints)
        assertEquals(listOf("k", "k"), built.map { it.second })
        // 重新列出、地址换了,下次建模型用新的
        val moved = messages.copy(baseUrl = "https://y.test/anthropic/v1")
        s.rememberModels(p.id, listOf(ListedModel("b", endpoint = moved)))
        s.modelFor(ModelRole.Main)
        assertEquals(moved, endpoints.last())
    }

    @Test
    fun adapterAndBaseUrlFollowTheProtocolWithoutRepeatingTheVersionPath() {
        val c = cfg("1")
        assertEquals(ProviderConfig.Kind.OpenAiCompat to "https://example.test/v1", ProviderStore.adapterFor(c, null))
        assertEquals(ProviderConfig.Kind.OpenAiCompat to "https://x.test/openai/v1", ProviderStore.adapterFor(c, chat))
        assertEquals(ProviderConfig.Kind.Anthropic to "https://x.test/anthropic", ProviderStore.adapterFor(c, messages))
        assertEquals(ProviderConfig.Kind.Gemini to "https://x.test/google", ProviderStore.adapterFor(c, generate))
        assertEquals(ProviderConfig.Kind.OpenAiResponses to "https://x.test/openai/v1", ProviderStore.adapterFor(c, responses))
        val anthropicConfig = c.copy(kind = ProviderConfig.Kind.Anthropic, baseUrl = "https://api.anthropic.com")
        assertEquals(ProviderConfig.Kind.Anthropic to "https://api.anthropic.com", ProviderStore.adapterFor(anthropicConfig, null))
    }

    // ---------- 请求头 ----------

    /** 声明了会话头的服务商:同一段对话建的模型带同一个会话号,换一段对话就换;User-Agent 每个都带。 */
    @Test
    fun sessionHeaderIsStableWithinAConversationAndDiffersAcross() {
        val s = store()
        val p = s.connect("Go", ProviderConfig.Kind.OpenAiCompat, "https://go.test/v1", "k", listOf(ListedModel("m1")), sessionHeader = "x-test-session")
        s.use(ModelRole.Main, p.id, "m1", none)
        val a = ModelSession.of("conv-a")
        s.modelFor(ModelRole.Main, a); s.modelFor(ModelRole.Light, a); s.modelFor(ModelRole.Main, ModelSession.of("conv-b"))
        val sent = setups.map { it.headers["x-test-session"] }
        assertEquals(listOf(a.id, a.id, ModelSession.of("conv-b").id), sent)
        assertTrue(sent[0] != sent[2])
        assertTrue(setups.all { it.headers["User-Agent"] == "Mika/1.2.3" })
    }

    /** 列表把模型指到 Responses 的(同一服务商下只说这一种协议的模型):走真的 Responses 适配器,照样带客户端标识和会话号。 */
    @Test
    fun responsesRoutedModelCarriesTheSessionHeader() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer().also { it.start() }
        try {
            val s = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Mika/1.2.3")
            val responsesBase = server.url("/v1").toString()
            val listed = ListedModel("r-only", endpoint = ModelEndpoint(ModelProtocol.Responses, responsesBase))
            val p = s.connect("Go", ProviderConfig.Kind.OpenAiCompat, "https://go.test/v1", "k", listOf(listed), sessionHeader = "x-test-session")
            s.use(ModelRole.Main, p.id, "r-only", none)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"))
            val session = ModelSession.of("conv-a")
            s.modelFor(ModelRole.Main, session)!!.complete("s", emptyList(), emptyList())
            val req = server.takeRequest()
            assertEquals("/v1/responses", req.path)
            assertEquals("Mika/1.2.3", req.getHeader("User-Agent"))
            assertEquals(session.id, req.getHeader("x-test-session"))
            assertEquals("Bearer k", req.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun noSessionHeaderForProvidersThatDoNotDeclareIt() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        s.modelFor(ModelRole.Main, ModelSession.of("conv-a"))
        assertEquals(mapOf("User-Agent" to "Mika/1.2.3"), setups.single().headers)
    }

    /** 会话头随服务商存下;旧版本存下的(没有这一项)按地址对得上的预设补上,预设的地址就会带。 */
    @Test
    fun sessionHeaderPersistsAndIsFilledFromThePresetForOldFiles() {
        val goUrl = ProviderPresets.all.first { it.sessionHeader != null }
        val s = store()
        val p = s.connect(goUrl.label, goUrl.kind, goUrl.baseUrl, "k", emptyList())
        assertEquals(goUrl.sessionHeader, p.sessionHeader)
        assertEquals(goUrl.sessionHeader, store().provider(p.id)?.sessionHeader)
        val f = File(tmp.root, "providers.json")
        f.writeText(f.readText().replace(",\"sessionHeader\":\"${goUrl.sessionHeader}\"", ""))
        assertFalse(f.readText().contains("sessionHeader"))
        assertEquals(goUrl.sessionHeader, store().provider(p.id)?.sessionHeader)
        assertTrue("written back", f.readText().contains("sessionHeader"))
    }

    /** 试调用走真的适配器:带客户端标识和声明的会话头、带一个试用的工具;回了工具调用才算通过,服务端的报错原样带回。 */
    @Test
    fun probeGoesThroughTheRealAdapterWithHeaders() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer().also { it.start() }
        try {
            val s = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Mika/1.2.3")
            val url = server.url("/v1").toString()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function",""" +
                    """"function":{"name":"get_time","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
            ))
            assertEquals(ProbeResult.ToolCalled, s.probe(ProviderConfig.Kind.OpenAiCompat, url, "sk", "x-test-session", "m", null, none))
            val req = server.takeRequest()
            assertEquals("/v1/chat/completions", req.path)
            assertEquals("Mika/1.2.3", req.getHeader("User-Agent"))
            assertTrue(req.getHeader("x-test-session").orEmpty().isNotEmpty())
            assertEquals("Bearer sk", req.getHeader("Authorization"))
            val body = req.body.readUtf8()
            assertTrue(body, body.contains("\"tools\"") && body.contains("get_time"))
            assertFalse("Chat Completions 不给回复上限", body.contains("max_tokens"))

            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(400).setBody("""{"error":{"type":"MissingSessionID"}}"""))
            val e = s.probe(ProviderConfig.Kind.OpenAiCompat, url, "sk", null, "m", null, none) as ProbeResult.Failed
            assertTrue(e.error.message.orEmpty(), e.error.message.orEmpty().contains("MissingSessionID"))
            assertNull(server.takeRequest().getHeader("x-test-session"))

            // Responses 不给回复上限;回了函数调用就算通过
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"get_time\",\"arguments\":\"{}\"}}\n\n" +
                    "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n",
            ))
            assertEquals(ProbeResult.ToolCalled, s.probe(ProviderConfig.Kind.OpenAiResponses, url, "sk", null, "m", null, none))
            val responses = server.takeRequest()
            assertEquals("/v1/responses", responses.path)
            assertEquals("Mika/1.2.3", responses.getHeader("User-Agent"))
            val responsesBody = responses.body.readUtf8()
            assertFalse(responsesBody.contains("max_output_tokens"))
            assertTrue(responsesBody.contains("get_time"))
        } finally {
            server.shutdown()
        }
    }

    /** 接口通、模型只回了话不调用工具:不是失败,但要分得出来。 */
    @Test
    fun probeThatGetsOnlyTextReportsNoToolCall() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer().also { it.start() }
        try {
            val s = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Mika/1.2.3")
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"role":"assistant","content":"It is noon."},"finish_reason":"stop"}]}"""))
            assertEquals(ProbeResult.NoToolCall, s.probe(ProviderConfig.Kind.OpenAiCompat, server.url("/v1").toString(), "sk", null, "m", null, none))
        } finally {
            server.shutdown()
        }
    }

    /** 试调用给 Messages 的回复上限留得够一次工具调用(16 个 token 装不下)。 */
    @Test
    fun probeLeavesRoomForAToolCall() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer().also { it.start() }
        try {
            val s = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Mika/1.2.3")
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"content":[{"type":"tool_use","id":"t1","name":"get_time","input":{}}],"stop_reason":"tool_use","usage":{"input_tokens":1,"output_tokens":1}}""",
            ))
            assertEquals(ProbeResult.ToolCalled, s.probe(ProviderConfig.Kind.Anthropic, server.url("/").toString(), "sk", null, "m", null, none))
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body, body.contains("\"max_tokens\":1024"))
            assertTrue(body.contains("get_time"))
        } finally {
            server.shutdown()
        }
    }

    // ---------- 用量 ----------

    @Test
    fun everyModelHandedOutIsMeteredUnderTheRoleItWasAskedFor() = runTest {
        val seen = mutableListOf<Pair<UsageSource, Usage?>>()
        val s = ProviderStore(tmp.root, AesGcmKeyCipher { key }, usage = { src, u -> seen += src to u }) { cfg, _, _, _ -> FakeModel(cfg.capabilities) }
        val p = connected(s, name = "甲")
        s.use(ModelRole.Main, p.id, "m1", none)
        s.choose(ModelRole.Vision, RoleChoice.SameAsMain)
        s.use(ModelRole.Light, p.id, "m2", none)
        s.modelFor(ModelRole.Main)!!.complete("s", emptyList(), emptyList())
        s.modelFor(ModelRole.Vision)!!.complete("s", emptyList(), emptyList())
        s.modelFor(ModelRole.Light)!!.complete("s", emptyList(), emptyList())
        assertEquals(
            listOf(
                UsageSource(p.id, "甲", "m1", UsagePurpose.Main),
                UsageSource(p.id, "甲", "m1", UsagePurpose.Vision),
                UsageSource(p.id, "甲", "m2", UsagePurpose.Light),
            ),
            seen.map { it.first },
        )
        assertEquals(Usage(3, 2), seen.first().second)
    }

    private class FakeModel(override val capabilities: Capabilities) : ChatModel {
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
            ModelReply(emptyList(), StopReason.EndTurn, Usage(3, 2))
    }
}
