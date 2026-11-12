package com.wangzan101.mika.core.chatgpt

import com.wangzan101.mika.core.model.ModelError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.net.URLDecoder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

class ChatGptAuthTest {
    private lateinit var server: MockWebServer
    private lateinit var auth: ChatGptAuth
    private val keys: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private var clock = 1_800_000_000_000L
    private val issuer get() = server.url("/").toString().trimEnd('/')

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        auth = ChatGptAuth(OkHttpClient(), issuer, now = { clock })
    }

    @After fun tearDown() { server.shutdown() }

    private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    private fun jwt(claims: String, kid: String = "k1", key: KeyPair = keys): String {
        val head = b64("""{"alg":"RS256","kid":"$kid","typ":"JWT"}""".toByteArray())
        val body = b64(claims.toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(key.private); update("$head.$body".toByteArray()); sign() }
        return "$head.$body.${b64(sig)}"
    }

    private fun jwks(kid: String = "k1"): MockResponse {
        val pub = keys.public as RSAPublicKey
        fun unsigned(b: java.math.BigInteger) = b.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        return MockResponse().setBody("""{"keys":[{"kty":"RSA","kid":"$kid","alg":"RS256","n":"${b64(unsigned(pub.modulus))}","e":"${b64(unsigned(pub.publicExponent))}"}]}""")
    }

    private fun claims(aud: String = "client-1", nonce: String = "n", exp: Long = clock / 1000 + 3600, iss: String = issuer) =
        """{"iss":"$iss","aud":"$aud","sub":"user-1","email":"a@b.test","nonce":"$nonce","exp":$exp,"iat":${clock / 1000}}"""

    private fun query(url: String): Map<String, String> = url.toHttpUrl().let { u -> u.queryParameterNames.associateWith { u.queryParameter(it)!! } }

    // ---------- 授权地址与 PKCE ----------

    @Test fun eachAttemptHasFreshRandomValuesAndAnS256Challenge() {
        val a = auth.newAttempt("http://127.0.0.1:1/auth/callback")
        val b = auth.newAttempt("http://127.0.0.1:1/auth/callback")
        assertTrue(a.state != b.state && a.nonce != b.nonce && a.verifier != b.verifier)
        assertTrue(a.verifier.length in 43..128)
        val expected = b64(MessageDigest.getInstance("SHA-256").digest(a.verifier.toByteArray()))
        assertEquals(expected, a.challenge)
        assertFalse(a.challenge.contains('='))
    }

    @Test fun firstSignInRegistersWithTheAppNameAndHostId() {
        val a = auth.newAttempt("http://127.0.0.1:4321/auth/callback")
        val url = auth.authorizeUrl(a, clientId = null, hostId = "urn:uuid:h", idTokenHint = "old", loginHint = "a@b.test")
        assertTrue(url.startsWith("$issuer/api/accounts/authorize?"))
        val q = query(url)
        assertEquals("dynamic_agent_client", q["client_id"])
        assertEquals("Mika", q["agent_name_hint"])
        assertEquals("urn:uuid:h", q["ext_agent_host_id"])
        assertEquals("code", q["response_type"])
        assertEquals("http://127.0.0.1:4321/auth/callback", q["redirect_uri"])
        assertEquals("openid profile email offline_access resource.invoke chatgpt.tokens.use.direct", q["scope"])
        assertEquals("https://api.openai.com/v1", q["resource"])
        assertEquals(a.state, q["state"])
        assertEquals(a.nonce, q["nonce"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals(a.challenge, q["code_challenge"])
        assertNull("第一次注册不带提示", q["id_token_hint"])
        assertNull(q["login_hint"])
    }

    @Test fun laterSignInsReuseTheIssuedClientAndSendHints() {
        val q = query(auth.authorizeUrl(auth.newAttempt("http://127.0.0.1:1/auth/callback"), "client-1", "urn:uuid:h", "idt", "a@b.test"))
        assertEquals("client-1", q["client_id"])
        assertNull(q["agent_name_hint"])
        assertEquals("idt", q["id_token_hint"])
        assertEquals("a@b.test", q["login_hint"])
    }

    // ---------- 回调 ----------

    @Test fun callbackChecksStateAndClientId() {
        val a = auth.newAttempt("r")
        assertEquals("c" to "issued", auth.readCallback(mapOf("state" to a.state, "code" to "c", "client_id" to "issued"), a, null))
        assertEquals("c" to "client-1", auth.readCallback(mapOf("state" to a.state, "code" to "c"), a, "client-1"))
        expect(SignInFailure.StateMismatch) { auth.readCallback(mapOf("state" to "x", "code" to "c"), a, null) }
        expect(SignInFailure.Denied) { auth.readCallback(mapOf("state" to a.state, "error" to "access_denied"), a, null) }
        expect(SignInFailure.ClientMismatch) { auth.readCallback(mapOf("state" to a.state, "code" to "c", "client_id" to "other"), a, "client-1") }
        expect(SignInFailure.NoClientId) { auth.readCallback(mapOf("state" to a.state, "code" to "c"), a, null) }
    }

    // ---------- 换令牌 ----------

    @Test fun exchangeAndRefreshPostTheDocumentedForms() = runTest {
        val tokens = """{"access_token":"at","refresh_token":"rt","id_token":"it","token_type":"Bearer","expires_in":3600,
            "scope":"chatgpt.tokens.use.direct email offline_access openid profile resource.invoke"}"""
        server.enqueue(MockResponse().setBody(tokens))
        server.enqueue(MockResponse().setBody(tokens))
        val a = auth.newAttempt("http://127.0.0.1:9/auth/callback")
        val ok = auth.exchange("client-1", "code-1", a) as TokenResult.Ok
        assertEquals(TokenSet("at", "rt", "it", 3600, listOf("chatgpt.tokens.use.direct", "email", "offline_access", "openid", "profile", "resource.invoke")), ok.tokens)
        val first = server.takeRequest()
        assertEquals("/api/accounts/oauth/token", first.path)
        assertEquals(
            mapOf("grant_type" to "authorization_code", "client_id" to "client-1", "code" to "code-1", "code_verifier" to a.verifier,
                "redirect_uri" to "http://127.0.0.1:9/auth/callback", "resource" to "https://api.openai.com/v1"),
            form(first.body.readUtf8()),
        )
        auth.refresh("client-1", "rt")
        assertEquals(
            mapOf("grant_type" to "refresh_token", "client_id" to "client-1", "refresh_token" to "rt", "resource" to "https://api.openai.com/v1"),
            form(server.takeRequest().body.readUtf8()),
        )
    }

    @Test fun refreshErrorsSayWhetherTheTokenIsGone() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"refresh_token_reused"}}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid_client"}"""))
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(TokenResult.Rejected("invalid_grant", true), auth.refresh("c", "r"))
        assertEquals(TokenResult.Rejected("refresh_token_reused", true), auth.refresh("c", "r"))
        assertEquals(TokenResult.Rejected("invalid_client", false), auth.refresh("c", "r"))
        assertTrue((auth.refresh("c", "r") as TokenResult.Failed).error is ModelError.Server)
    }

    @Test fun revokePostsTheRefreshToken() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        assertTrue(auth.revoke("client-1", "rt"))
        val r = server.takeRequest()
        assertEquals("/api/accounts/oauth/revoke", r.path)
        assertEquals(mapOf("token" to "rt", "token_type_hint" to "refresh_token", "client_id" to "client-1"), form(r.body.readUtf8()))
        server.enqueue(MockResponse().setResponseCode(500))
        assertFalse(auth.revoke("client-1", "rt"))
    }

    // ---------- ID 令牌 ----------

    @Test fun idTokenIsVerifiedAgainstJwksAndClaims() = runTest {
        server.enqueue(jwks())
        assertEquals(IdClaims("user-1", "a@b.test"), auth.verifyIdToken(jwt(claims()), "client-1", "n"))
        assertEquals("/.well-known/jwks.json", server.takeRequest().path)
        expectSuspend(SignInFailure.IdToken) { auth.verifyIdToken(jwt(claims(aud = "other")), "client-1", "n") }
        expectSuspend(SignInFailure.IdToken) { auth.verifyIdToken(jwt(claims(nonce = "x")), "client-1", "n") }
        expectSuspend(SignInFailure.IdToken) { auth.verifyIdToken(jwt(claims(iss = "https://evil.test")), "client-1", "n") }
        expectSuspend(SignInFailure.IdToken) { auth.verifyIdToken(jwt(claims(exp = clock / 1000 - 3600)), "client-1", "n") }
        val stranger = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        expectSuspend(SignInFailure.IdToken) { auth.verifyIdToken(jwt(claims(), key = stranger), "client-1", "n") }
        // 不认识的 kid:再取一次 JWKS,还是没有就不认
        server.enqueue(jwks())
        expectSuspend(SignInFailure.IdToken) { auth.verifyIdToken(jwt(claims(), kid = "k2"), "client-1", "n") }
        assertEquals(2, server.requestCount)
    }

    // ---------- 访问令牌 ----------

    private fun session(expiresAt: Long = clock + 3_600_000) = ChatGptSession(
        clientId = "client-1", sub = "user-1", email = "a@b.test", accessToken = "at", refreshToken = "rt", idToken = "it",
        expiresAtMs = expiresAt, scopes = listOf(ChatGptAuth.PLAN_SCOPE),
    )

    @Test fun tokensRefreshNearExpiryAndStoreTheRotatedPair() = runTest {
        var stored: ChatGptSession? = session()
        val tokens = ChatGptTokens(auth, { stored }, { stored = it }, now = { clock })
        assertEquals("at", tokens.get(false))
        assertEquals(0, server.requestCount)
        stored = session(expiresAt = clock + 60_000)
        server.enqueue(MockResponse().setBody("""{"access_token":"at2","refresh_token":"rt2","expires_in":3600,"scope":"${ChatGptAuth.PLAN_SCOPE} openid"}"""))
        assertEquals("at2", tokens.get(false))
        assertEquals("rt2", stored!!.refreshToken)
        assertEquals(clock + 3_600_000, stored!!.expiresAtMs)
        // 被拒了就换,哪怕还没到期
        server.enqueue(MockResponse().setBody("""{"access_token":"at3","expires_in":3600}"""))
        assertEquals("at3", tokens.get(true))
        assertEquals("rt2", stored!!.refreshToken)
        server.takeRequest()
        assertEquals("用换来的新刷新令牌", "rt2", form(server.takeRequest().body.readUtf8())["refresh_token"])
    }

    @Test fun deadRefreshTokenSignsOutButTemporaryFailuresKeepIt() = runTest {
        var stored: ChatGptSession? = session(expiresAt = 0)
        val tokens = ChatGptTokens(auth, { stored }, { stored = it }, now = { clock })
        server.enqueue(MockResponse().setResponseCode(503))
        try { tokens.get(false); fail() } catch (e: ModelError.Server) { }
        assertTrue(stored!!.signedIn)
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        try { tokens.get(false); fail() } catch (e: ModelError.SignInExpired) { }
        assertFalse(stored!!.signedIn)
        assertEquals("client-1", stored!!.clientId)
        try { tokens.get(false); fail() } catch (e: ModelError.SignInExpired) { }
    }

    @Test fun sessionRoundTripsThroughJson() {
        val s = session()
        assertEquals(s, ChatGptSession.fromJson(s.toJson()))
        assertNull(ChatGptSession.fromJson("not json"))
    }

    // ---------- 本机回调 ----------

    @Test fun loopbackBindsLocalhostAndHandlesOneCallback() = runBlocking {
        LoopbackCallback.open().use { cb ->
            assertTrue(cb.redirectUri.matches(Regex("""http://127\.0\.0\.1:\d+/auth/callback""")))
            val client = OkHttpClient()
            val waiting = async(Dispatchers.IO) { cb.await(10_000, { it["state"] == "s" }) { "<p>ok ${it["code"]}</p>" } }
            withContext(Dispatchers.IO) {
                val miss = client.newCall(Request.Builder().url("http://127.0.0.1:${cb.port}/favicon.ico").build()).execute()
                assertEquals(404, miss.code); miss.close()
                val stranger = client.newCall(Request.Builder().url("${cb.redirectUri}?code=x&state=other").build()).execute()
                assertEquals("不是这次登录的回调不收", 400, stranger.code); stranger.close()
                val hit = client.newCall(Request.Builder().url("${cb.redirectUri}?code=a%20b&state=s").build()).execute()
                assertEquals(200, hit.code)
                assertEquals("<p>ok a b</p>", hit.body!!.string())
            }
            assertEquals(mapOf("code" to "a b", "state" to "s"), waiting.await())
            assertTrue(InetAddress.getByName(LoopbackCallback.HOST).isLoopbackAddress)
        }
    }

    @Test fun loopbackTimesOutAndClosesThePort() = runBlocking {
        val cb = LoopbackCallback.open()
        val port = cb.port
        assertNull(cb.await(400) { "" })
        cb.close()
        val refused = runCatching { Socket("127.0.0.1", port).close() }.isFailure
        assertTrue("关掉之后端口不再收连接", refused)
    }

    // ---------- 整个登录 ----------

    /** 授权服务:换令牌时按这次的 nonce 签一张 ID 令牌;[scope] 是授予的权限。 */
    private fun authServer(nonce: () -> String, scope: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/.well-known/jwks.json" -> jwks()
                "/api/accounts/oauth/token" -> MockResponse().setBody(
                    """{"access_token":"at","refresh_token":"rt","id_token":"${jwt(claims(aud = "issued-1", nonce = nonce()))}","expires_in":3600,"scope":"$scope"}""",
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    /** 「浏览器」:读授权地址,带着授权码和 [extra] 跳回回调地址。 */
    private fun browser(seen: MutableMap<String, String>, extra: (Map<String, String>) -> String): (String) -> Unit = { url ->
        val q = query(url)
        seen.putAll(q)
        Thread {
            OkHttpClient().newCall(Request.Builder().url("${q["redirect_uri"]}?${extra(q)}").build()).execute().close()
        }.start()
    }

    @Test fun fullSignInRegistersExchangesVerifiesAndChecksThePlanScope() = runBlocking {
        val seen = mutableMapOf<String, String>()
        authServer({ seen["nonce"]!! }, "openid email ${ChatGptAuth.PLAN_SCOPE}")
        var registered: String? = null
        val session = signInWithChatGpt(
            auth, "urn:uuid:h", clientId = null, previous = null,
            openBrowser = browser(seen) { q -> "code=c1&state=${q["state"]}&client_id=issued-1" },
            onRegistered = { registered = it }, page = { "done" }, now = { clock },
        )
        assertEquals("dynamic_agent_client", seen["client_id"])
        assertEquals("issued-1", registered)
        assertEquals(
            ChatGptSession("issued-1", "user-1", "a@b.test", "at", "rt", session.idToken, clock + 3_600_000, listOf("openid", "email", ChatGptAuth.PLAN_SCOPE)),
            session,
        )
        assertTrue(session.planUsage)
    }

    @Test fun signInWithoutThePlanScopeFails() = runBlocking {
        val seen = mutableMapOf<String, String>()
        authServer({ seen["nonce"]!! }, "openid email")
        try {
            signInWithChatGpt(
                auth, "urn:uuid:h", clientId = "issued-1", previous = null,
                openBrowser = browser(seen) { q -> "code=c1&state=${q["state"]}" },
                onRegistered = { fail("已注册过") }, page = { "done" }, now = { clock },
            )
            fail()
        } catch (e: SignInException) {
            assertEquals(SignInFailure.NoPlanScope, e.reason)
        }
        assertEquals("issued-1", seen["client_id"])
    }

    @Test fun abandonedSignInTimesOut() = runBlocking {
        try {
            signInWithChatGpt(auth, "urn:uuid:h", null, null, openBrowser = {}, onRegistered = {}, page = { "" }, timeoutMs = 300)
            fail()
        } catch (e: SignInException) {
            assertEquals(SignInFailure.TimedOut, e.reason)
        }
    }

    private fun form(body: String) = body.split('&').associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    private fun expect(reason: SignInFailure, block: () -> Unit) {
        try { block(); fail("expected $reason") } catch (e: SignInException) { assertEquals(reason, e.reason) }
    }

    private suspend fun expectSuspend(reason: SignInFailure, block: suspend () -> Unit) {
        try { block(); fail("expected $reason") } catch (e: SignInException) { assertEquals(reason, e.reason) }
    }
}
