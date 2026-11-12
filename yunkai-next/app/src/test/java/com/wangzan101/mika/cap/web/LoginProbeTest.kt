package com.wangzan101.mika.cap.web

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginProbeTest {
    private fun signals(
        url: String = "https://shop.example.com/",
        pw: Boolean = false,
        account: List<String> = emptyList(),
        avatars: Int = 0,
    ) = LoginSignals(url, pw, account, avatars)

    private fun state(vararg account: String, pw: Boolean = false, avatars: Int = 0, url: String = "https://shop.example.com/") =
        LoginProbe.classify(signals(url, pw, account.toList(), avatars))

    @Test fun signedOutSignals() {
        assertEquals(LoginState.SignedOut, state(pw = true))
        assertEquals(LoginState.SignedOut, state("首页", "登录/注册"))
        assertEquals(LoginState.SignedOut, state("Sign in"))
    }

    @Test fun signedInNeedsAProminentLogoutOrAccountEntry() {
        assertEquals(LoginState.SignedIn, state("首页", "退出登录"))
        assertEquals(LoginState.SignedIn, state("Log out"))
        assertEquals(LoginState.SignedIn, state("退出"))
        assertEquals(LoginState.SignedIn, state("个人中心"))
        assertEquals(LoginState.SignedIn, state("Sign out ›"))
    }

    @Test fun helpAndLookalikeLinksAreNotLogout() {
        // 注销账号的说明、帮助页,以及「退出」开头的别的意思
        assertEquals(LoginState.Unknown, state("账号注销"))
        assertEquals(LoginState.Unknown, state("注销说明"))
        assertEquals(LoginState.Unknown, state("退出全屏"))
        assertEquals(LoginState.Unknown, state("How to log out"))
        assertEquals(LoginState.Unknown, state("Log out of every device you have ever used"))
    }

    @Test fun avatarsAloneNeverDecide() {
        assertEquals(LoginState.Unknown, state(avatars = 3))
        // 头像只挡住「已退出」
        assertEquals(LoginState.Unknown, state("登录", avatars = 1))
    }

    @Test fun conflictsAndLoginPagesAreUnknown() {
        assertEquals(LoginState.Unknown, state())
        // 改密码页:密码框和退出链接都在
        assertEquals(LoginState.Unknown, state("退出登录", pw = true))
        assertEquals(LoginState.Unknown, state("登录", "个人中心"))
        // 单点登录、通行证跳转途中不下结论,哪怕有密码框
        assertEquals(LoginState.Unknown, state(pw = true, url = "https://passport.example.com/login?return=/"))
        assertEquals(LoginState.Unknown, state("退出登录", url = "https://example.com/sso/callback"))
    }

    @Test fun loginUrlMatchesPathSegmentsOnly() {
        assertTrue(LoginProbe.loginUrl("https://a.example.com/user/login"))
        assertTrue(LoginProbe.loginUrl("https://a.example.com/signin"))
        assertTrue(LoginProbe.loginUrl("https://a.example.com/oauth/authorize"))
        assertFalse(LoginProbe.loginUrl("https://a.example.com/blog-in-spring"))
        assertFalse(LoginProbe.loginUrl("https://a.example.com/search?q=login"))
        assertFalse(LoginProbe.loginUrl(null))
    }

    @Test fun parsesTheScriptResult() {
        val inner = """{"url":"https://a.example.com/","pw":true,"account":["登录"],"avatars":2}"""
        val s = LoginProbe.parse(JsonPrimitive(inner).toString())!!
        assertEquals("https://a.example.com/", s.url)
        assertTrue(s.passwordVisible)
        assertEquals(listOf("登录"), s.account)
        assertEquals(2, s.avatars)
        assertNull(LoginProbe.parse("null"))
        assertNull(LoginProbe.parse(null))
        assertNull(LoginProbe.parse("\"not json\""))
    }

    @Test fun scanIsBounded() {
        assertTrue(LoginProbe.SCRIPT.contains("budget=4000"))
        assertTrue(LoginProbe.SCRIPT.contains("createTreeWalker"))
    }
}
