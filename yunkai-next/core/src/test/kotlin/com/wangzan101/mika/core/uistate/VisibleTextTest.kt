package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleTextTest {
    @Test fun keepsOnlyVisibleTextMissingFromTreeAndNeverReturnsPasswordField() {
        val screen = ScreenSnapshot("p1", "com.example", listOf(
            UiElement("@e1", "TextView", "搜索", 500, 80, listOf(450, 50, 550, 100), null, true, false, false),
            UiElement("@e2", "EditText", "", 500, 900, listOf(100, 850, 900, 950), null, false, true, false, secret = true),
        ), 1080, 2400)
        val lines = VisibleText.missing(screen, listOf(
            VisibleTextLine("搜索", listOf(460, 60, 540, 95)),
            VisibleTextLine("明天改到下午", listOf(150, 400, 720, 450)),
            VisibleTextLine("明天改到下午", listOf(152, 402, 718, 448)),
            VisibleTextLine("123456", listOf(200, 875, 600, 925)),
        ))
        assertEquals(listOf("明天改到下午"), lines.map { it.text })
    }

    @Test fun phoneObservationShowsOcrOnlyTextWithoutSendingAnImage() = runTest {
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = ScreenSnapshot("p1", "com.example", listOf(
                UiElement("@e1", "TextView", "群聊", 500, 80, listOf(400, 40, 600, 110), null, false, false, false),
            ), 1080, 2400)
            override suspend fun readVisibleText() = listOf(
                VisibleTextLine("群聊", listOf(420, 55, 580, 100)),
                VisibleTextLine("明天改到下午", listOf(150, 400, 720, 450)),
            )
        }
        val result = toolDispatcher(screen).executeRead(Part.ToolCall("o", "phone_observe", "{}"))
        val shown = result.content["visibleText"].toString()
        assertTrue(shown, "明天改到下午" in shown)
        assertTrue(shown, "群聊" !in shown)
        assertEquals(null, result.image)
    }
}
