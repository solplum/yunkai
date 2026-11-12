package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import org.junit.Assert.assertEquals
import org.junit.Test

class TokenEstimateTest {
    @Test fun hanAndLatin() {
        // 8 个非汉字字符 / 4 = 2;4 个汉字 × 0.75 = 3
        assertEquals(5, TokenEstimate.of(listOf(Message(Role.User, listOf(Part.Text("abcdefgh打开设置")), Author.Mika))))
        // 工具调用的名字与参数、工具结果都计入
        val call = Part.ToolCall("c1", "tap", "{}")
        val result = Part.ToolResult("c1", "12345678", isError = false)
        assertEquals(4, TokenEstimate.of(listOf(Message(Role.Assistant, listOf(call), Author.Mika), Message(Role.Tool, listOf(result), Author.Mika))))
        assertEquals(0, TokenEstimate.of(emptyList()))
    }

    @Test fun imagesCountFixed() {
        val big = "A".repeat(400_000)
        assertEquals(1000, TokenEstimate.of(listOf(Message(Role.User, listOf(Part.Image(big)), Author.Mika))))
        assertEquals(2001, TokenEstimate.of(listOf(Message(Role.User, listOf(Part.Image("x"), Part.Text("abcd"), Part.Image("y")), Author.Mika))))
    }

    @Test fun cjkPunctuationFullWidthKanaHangulCountAsWide() {
        // 「。」「，」「Ａ」、假名、谚文各 0.75:8 个 → 6
        assertEquals(6, TokenEstimate.of("。，Ａあアカ한글"))
        assertEquals(1, TokenEstimate.of("abcd"))
    }

    @Test fun imagesWithSizeCountByPixels() {
        // 原分辨率 1224×2700 与压缩后 580×1280(长边 1280)
        val (w, h) = com.wangzan101.mika.core.vision.ModelImage.scaled(1224, 2700)
        assertEquals(580 to 1280, w to h)
        assertEquals(4407, TokenEstimate.of(listOf(Message(Role.User, listOf(Part.Image("x", width = 1224, height = 2700)), Author.Mika))))
        assertEquals(990, TokenEstimate.of(listOf(Message(Role.User, listOf(Part.Image("x", width = w, height = h)), Author.Mika))))
    }
}
