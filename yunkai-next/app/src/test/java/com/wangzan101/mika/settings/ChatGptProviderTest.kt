package com.wangzan101.mika.settings

import com.wangzan101.mika.core.chatgpt.ChatGptAuth
import com.wangzan101.mika.core.chatgpt.ChatGptSession
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ListFailure
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.model.ModelRole
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

class ChatGptProviderTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var auth: MockWebServer
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val listCalls = mutableListOf<Triple<ProviderConfig.Kind, String, String?>>()
    private val listHeaders = mutableListOf<Map<String, String>>()

    @Before fun setUp() { auth = MockWebServer().also { it.start() } }
    @After fun tearDown() { auth.shutdown() }

    private fun store() = ProviderStore(
        tmp.root, AesGcmKeyCipher { key },
        userAgent = "Mika/1.2.3",
        modelLister = { kind, url, token, headers -> listCalls += Triple(kind, url, token); listHeaders += headers; ModelListResult.Ok(listOf(ListedModel("gpt-x"))) },
        chatGptAuth = ChatGptAuth(OkHttpClient(), auth.url("/").toString().trimEnd('/')),
    )

    private fun session(expiresAt: Long = System.currentTimeMillis() + 3_600_000) = ChatGptSession(
        clientId = "client-1", sub = "user-1", email = "a@b.test", accessToken = "secret-access", refreshToken = "secret-refresh",
        idToken = "idt", expiresAtMs = expiresAt, scopes = listOf(ChatGptAuth.PLAN_SCOPE),
    )

    private val caps = Capabilities(toolCalls = true, vision = false, contextTokens = 200_000, reasoning = true)

    @Test fun hostIdIsCreatedOnceAndTheIssuedClientIsRemembered() {
        val (host, client) = store().chatGptRegistration()
        assertTrue(host.matches(Regex("urn:uuid:[0-9a-f-]{36}")))
        assertNull(client)
        store().rememberChatGptClient("client-1")
        assertEquals(host to "client-1", store().chatGptRegistration())
    }

    @Test fun sessionIsStoredEncryptedLikeAnApiKeyAndModelsAreListedWithTheAccessToken() = runTest {
        val s = store()
        val saved = session()
        val p = s.connectChatGpt(saved, listOf(ListedModel("gpt-x")))
        assertEquals(ProviderConfig.Kind.ChatGpt, p.kind)
        assertEquals(ChatGptAuth.API_BASE, p.baseUrl)
        assertEquals("ChatGPT", p.name)
        assertEquals(saved, store().chatGptSession(p.id))
        tmp.root.walk().filter(File::isFile).forEach { f -> assertFalse(f.name, f.readText().contains("secret-")) }
        store().listModels(p)
        assertEquals(Triple(ProviderConfig.Kind.ChatGpt, ChatGptAuth.API_BASE, "secret-access"), listCalls.single())
        // 客户端标识照带;ChatGPT 没声明会话头,不发会话号
        assertEquals(mapOf("User-Agent" to "Mika/1.2.3"), listHeaders.single())
        assertNull(p.sessionHeader)
        // 再登录一次是更新同一家,不新添
        s.connectChatGpt(session().copy(accessToken = "secret-2"), emptyList())
        assertEquals(1, s.providers.value.size)
        assertTrue(s.use(ModelRole.Main, p.id, "gpt-x", caps))
        assertNotNull(s.modelFor(ModelRole.Main))
    }

    @Test fun expiringAccessTokenIsRefreshedBeforeListing() = runTest {
        val s = store()
        val p = s.connectChatGpt(session(expiresAt = 0), emptyList())
        auth.enqueue(MockResponse().setBody("""{"access_token":"secret-new","refresh_token":"secret-r2","expires_in":3600,"scope":"${ChatGptAuth.PLAN_SCOPE}"}"""))
        s.listModels(p)
        assertEquals("secret-new", listCalls.single().third)
        assertEquals("/api/accounts/oauth/token", auth.takeRequest().path)
        assertEquals("secret-r2", store().chatGptSession(p.id)!!.refreshToken)
    }

    @Test fun signOutRevokesClearsTokensAndKeepsTheProviderAndRegistration() = runTest {
        val s = store()
        s.rememberChatGptClient("client-1")
        val p = s.connectChatGpt(session(), listOf(ListedModel("gpt-x")))
        s.use(ModelRole.Main, p.id, "gpt-x", caps)
        auth.enqueue(MockResponse().setResponseCode(200))
        assertTrue(s.signOutChatGpt(p.id))
        assertEquals("/api/accounts/oauth/revoke", auth.takeRequest().path)
        val after = store().chatGptSession(p.id)!!
        assertFalse(after.signedIn)
        assertEquals("client-1", after.clientId)
        assertEquals("a@b.test", after.email)
        assertEquals("client-1", store().chatGptRegistration().second)
        assertEquals(p.id, store().configFor(ModelRole.Main)?.providerId)
        assertEquals(ListFailure.Auth, (store().listModels(p) as ModelListResult.Failed).reason)
        assertTrue("退出后列模型不发请求", listCalls.isEmpty())
    }

    @Test fun unconfirmedRevokeStillClearsLocalTokens() = runTest {
        val s = store()
        val p = s.connectChatGpt(session(), emptyList())
        auth.enqueue(MockResponse().setResponseCode(500))
        assertFalse(s.signOutChatGpt(p.id))
        assertFalse(store().chatGptSession(p.id)!!.signedIn)
    }

    @Test fun removingTheProviderForgetsTheClientButKeepsTheHostId() {
        val s = store()
        val host = s.chatGptRegistration().first
        s.rememberChatGptClient("client-1")
        val p = s.connectChatGpt(session(), emptyList())
        s.removeProvider(p.id)
        assertEquals(host to null, store().chatGptRegistration())
        assertNull(store().chatGptProvider())
    }
}
