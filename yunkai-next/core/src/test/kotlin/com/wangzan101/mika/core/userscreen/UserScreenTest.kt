package com.wangzan101.mika.core.userscreen

import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.VisibleTextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class UserScreenTest {
    private val zone = ZoneOffset.UTC
    private val at = 1_761_624_120_000L

    @Test fun composeAndParse() {
        val text = UserScreenMessage.compose("s1", " 第三条有没有坑? ")
        assertEquals("[[screen:s1]]\n第三条有没有坑?", text)
        val p = UserScreenMessage.parse(text)
        assertEquals(listOf("s1"), p.screens)
        assertEquals("第三条有没有坑?", p.words)
        assertEquals("[[screen:s1]]", UserScreenMessage.compose("s1", ""))
        assertEquals("", UserScreenMessage.parse("[[screen:s1]]").words)
    }

    @Test fun ordinaryTextAndAttachmentsAreNotScreens() {
        val text = "看看这张图\n附件:/workspace/inbox/a.jpg(12 KB)"
        assertFalse(UserScreenMessage.has(text))
        assertEquals(text, UserScreenMessage.parse(text).words)
        // 句子中间写着这几个字不算
        assertFalse(UserScreenMessage.has("我打了 [[screen:s1]] 这几个字"))
    }

    @Test fun saidSentences() {
        val s = UserScreen("s1", at, "com.example.pay", "支付")
        assertEquals("用户刚截下他正在看的屏幕(支付,10月28日 04:02),没有附言。", UserScreenPrompt.said("s1", s, "", zone))
        assertEquals("用户刚截下他正在看的屏幕(支付,10月28日 04:02),附言:记一下", UserScreenPrompt.said("s1", s, "记一下", zone))
        assertTrue(UserScreenPrompt.said("s1", s.copy(blocked = true), "", zone).contains("不让截图"))
        assertTrue(UserScreenPrompt.said("s9", null, "", zone).contains("已经不在了"))
    }

    @Test fun earlierLine() {
        val s = UserScreen("s1", at, "com.example.pay", "支付", lines = listOf(ScreenLine("支付成功", 500, 200), ScreenLine("¥38.50", 500, 260)))
        assertEquals("(用户之前发来的屏幕 s1:支付,10月28日 04:02;屏上开头是「支付成功 ¥38.50」)", UserScreenPrompt.earlier("s1", s, zone))
        assertTrue(UserScreenPrompt.earlier("s1", s.copy(lines = emptyList()), zone).contains("没有读到字"))
        assertTrue(UserScreenPrompt.earlier("s1", s.copy(blocked = true), zone).contains("不让截图"))
        val long = s.copy(lines = listOf(ScreenLine("字".repeat(100), 0, 0)))
        assertEquals(UserScreenPrompt.GIST_CHARS + 1, UserScreenPrompt.gist(long)!!.length)
    }

    @Test fun appFallsBackToPackage() {
        assertEquals("com.example.pay", UserScreen("s", 0, "com.example.pay", null).app)
        assertEquals(UserScreen.UNKNOWN_APP, UserScreen("s", 0, null, null).app)
    }

    @Test fun jsonRoundTrip() {
        val s = UserScreen("s1", at, "com.example.pay", "支付", blocked = false, lines = listOf(ScreenLine("支付成功", 500, 200)), fromOcr = true)
        assertEquals(s, UserScreenJson.decode(UserScreenJson.encode(s)))
        assertNull(UserScreenJson.decode("not json"))
    }

    /** 和读屏端口给的一样:中心是像素(按 1080×2400),bounds 已归一到 0–999;[x]/[y]/[w]/[h] 都按 0–999 给。 */
    private fun el(name: String, x: Int, y: Int, secret: Boolean = false, w: Int = 100, h: Int = 20) =
        UiElement("r", "TextView", name, Math.round(x * 1079 / 999.0).toInt(), Math.round(y * 2399 / 999.0).toInt(),
            listOf(x - w / 2, y - h / 2, x + w / 2, y + h / 2), null, false, false, false, secret = secret)

    @Test fun linesFromTreeAndOcrTopToBottomLeftToRight() {
        val snap = ScreenSnapshot("u", "com.example.chat", listOf(el("右边的话", 800, 300), el("左边的话", 200, 302), el("密码", 500, 500, secret = true)), 1080, 2400)
        val ocr = listOf(
            VisibleTextLine("图片里的字", listOf(100, 600, 400, 640)),
            VisibleTextLine("12:30", listOf(20, 2, 120, 20)),
        )
        val (lines, fromOcr) = UserScreenText.lines(snap, ocr, top = 30, bottom = 960)
        assertEquals(listOf("左边的话", "右边的话", "图片里的字"), lines.map { it.text })
        // 交给模型的中心点是 0–999,不是像素
        assertEquals(200, lines[0].x)
        assertEquals(302, lines[0].y)
        assertTrue(fromOcr)
        val (treeOnly, noOcr) = UserScreenText.lines(snap, null)
        assertEquals(2, treeOnly.size)
        assertFalse(noOcr)
    }

    @Test fun rowThatOnlyJoinsItsChildrenIsLeftOut() {
        // 一行设置项:整行的名字是里面两段字连起来,只留里面那两段(各有各的位置);整行名字里有自己的字时照留
        val snap = ScreenSnapshot("u", "com.example.settings", listOf(
            el("WLAN 家里的网络", 500, 400, w = 900, h = 60), el("WLAN", 250, 400), el("家里的网络", 800, 400),
            el("移动网络", 500, 500, w = 900, h = 60), el("移动网络", 280, 500),
            el("蓝牙,已开启", 500, 600, w = 900, h = 60), el("蓝牙", 250, 600),
        ), 1080, 2400)
        val (lines, _) = UserScreenText.lines(snap, null)
        assertEquals(listOf("WLAN", "家里的网络", "移动网络", "蓝牙", "蓝牙,已开启"), lines.map { it.text })
    }

    @Test fun poorTreeNeedsOcr() {
        assertTrue(UserScreenText.needsOcr(ScreenSnapshot("u", "com.example.chat", emptyList(), 1080, 2400)))
        val rich = (0 until 10).map { el("第 $it 行", 500, 50 + it * 80) }
        assertFalse(UserScreenText.needsOcr(ScreenSnapshot("u", "com.example.chat", rich, 1080, 2400)))
    }
}
