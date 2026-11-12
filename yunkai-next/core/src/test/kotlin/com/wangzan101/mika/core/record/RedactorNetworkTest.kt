package com.wangzan101.mika.core.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorNetworkTest {
    private fun url(u: String) = Redactor.network(url = u).url!!
    private fun body(b: String) = Redactor.network(body = b).body!!

    @Test fun secretQueryParamsAreMasked() {
        assertEquals(
            "https://a.test/cb?access_token=***&page=2&sig=***&X-Amz-Signature=***&sessionId=***",
            url("https://a.test/cb?access_token=abc123&page=2&sig=zz&X-Amz-Signature=ff00&sessionId=42"),
        )
        assertEquals("https://a.test/p?key=***&q=shoes", url("https://a.test/p?key=AIzaShort&q=shoes"))
        // 片段里的 OAuth 隐式授权结果
        assertEquals("https://a.test/#id_token=***&state=***", url("https://a.test/#id_token=x.y.z&state=s1"))
    }

    @Test fun oauthCodeOnlyWithOauthSiblings() {
        assertEquals("https://a.test/cb?code=***&state=***", url("https://a.test/cb?code=4/0AX&state=xyz"))
        assertEquals("https://a.test/item?code=SKU42", url("https://a.test/item?code=SKU42"))
    }

    @Test fun ordinaryNamesThatLookAlikeAreKept() {
        assertEquals("https://a.test/b?author=ann&keyword=red&monkey=1", url("https://a.test/b?author=ann&keyword=red&monkey=1"))
        assertEquals("""{"author":"ann","keyword":"red","id":7}""", body("""{"author":"ann","keyword":"red","id":7}"""))
    }

    @Test fun jsonSecretFieldsAreMaskedAtAnyDepth() {
        val out = body("""{"user":{"name":"Ann","password":"p@ss","refreshToken":"r1","csrf_token":"c"},"session":{"sid":123},"otp":"654321","ok":true}""")
        assertEquals("""{"user":{"name":"Ann","password":"***","refreshToken":"***","csrf_token":"***"},"session":{"sid":"***"},"otp":"***","ok":true}""", out)
    }

    @Test fun truncatedJsonStillMasked() {
        val out = body("""{"items":[1,2],"access_token":"abc","next":"x""")
        assertTrue(out, out.contains(""""access_token":"***""""))
        assertFalse(out.contains("abc"))
    }

    @Test fun jwtAndHighEntropyStringsAreMasked() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
        assertEquals("""{"x":"***"}""", body("""{"x":"$jwt"}"""))
        assertEquals("""{"blob":"***"}""", body("""{"blob":"Qm9vT2p5ZWxsb3dXb3JsZDEyMzQ1Njc4OTBhYmNkZWZn"}"""))
        // 小写十六进制(哈希、编号)和普通长文本照留
        val hex = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        assertEquals("""{"etag":"$hex"}""", body("""{"etag":"$hex"}"""))
        assertEquals("https://a.test/r/***", url("https://a.test/r/Qm9vT2p5ZWxsb3dXb3JsZDEyMzQ1Njc4OTBhYmNkZWZn"))
    }

    @Test fun formBodiesAndApiKeysAreMasked() {
        assertEquals("username=ann&password=***&remember=1", body("username=ann&password=hunter2&remember=1"))
        assertEquals("""{"k":"***"}""", body("""{"k":"sk-abcdefghijklmnopqrstuvwx"}"""))
    }

    @Test fun headersAreMaskedByName() {
        val h = Redactor.network(headers = mapOf("Set-Cookie" to "sid=1", "Authorization" to "Basic eA==", "X-CSRF-Token" to "t", "Content-Type" to "application/json")).headers!!
        assertEquals(mapOf("Set-Cookie" to "***", "Authorization" to "***", "X-CSRF-Token" to "***", "Content-Type" to "application/json"), h)
    }
}
