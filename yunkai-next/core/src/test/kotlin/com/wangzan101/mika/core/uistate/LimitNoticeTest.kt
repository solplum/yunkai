package com.wangzan101.mika.core.uistate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LimitNoticeTest {
    private fun el(name: String, editable: Boolean = false) =
        UiElement("@e1", "text", name, 0, 0, listOf(0, 0, 999, 60), null, false, editable, false)
    private fun screen(vararg names: String, dialog: String? = null) = ScreenSnapshot("s", "www.example.com", names.map { el(it) }, 1080, 2400,
        web = WebInfo("https://www.example.com/", "t", "1", 200, dialog = dialog?.let { PageDialog(PageDialog.ALERT, it) }))

    @Test fun newShortLimitTextsAreFound() {
        val before = screen("职位详情", "立即沟通")
        for (t in listOf("今日沟通次数已用完", "您今天的打招呼次数已达上限，明天再来", "操作过于频繁，请稍后再试", "您已达到沟通上限", "超过每日投递上限",
            "You've reached your daily limit", "Too many requests. Try again tomorrow.")) {
            assertEquals(t, t, LimitNotice.find(before, screen("职位详情", "立即沟通", t)))
        }
        // 页面弹框的话也算
        assertEquals("今日次数已用完", LimitNotice.find(before, screen("职位详情", dialog = "今日次数已用完")))
        // 读不到动作前的一屏时,屏上的都算新出现的
        assertEquals("次数已达上限", LimitNotice.find(null, screen("次数已达上限")))
    }

    @Test fun oldLongOrUnrelatedTextsAreNot() {
        val faq = screen("帮助中心", "沟通次数已达上限怎么办?")
        assertNull(LimitNotice.find(faq, screen("帮助中心", "沟通次数已达上限怎么办?", "展开")))
        assertNull(LimitNotice.find(null, screen("已发送", "网络错误，请稍后再试", "继续沟通")))
        assertNull(LimitNotice.find(null, screen("为什么每天的沟通次数有上限?" + "说明".repeat(80) + "已达上限")))
        // 输入框里打的字不算
        assertNull(LimitNotice.find(null, ScreenSnapshot("s", "x", listOf(el("明天再来", editable = true)), 1080, 2400)))
        assertNull(LimitNotice.find(screen("a"), null))
    }
}
