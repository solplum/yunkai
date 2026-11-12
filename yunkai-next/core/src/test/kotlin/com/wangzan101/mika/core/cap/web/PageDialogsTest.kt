package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.uistate.PageDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageDialogsTest {
    private class Reply : DialogReply {
        val said = mutableListOf<String>()
        override fun accept(text: String?) { said += "accept:${text ?: "-"}" }
        override fun dismiss() { said += "dismiss" }
    }

    @Test fun dialogStaysPendingUntilAnswered() {
        val d = PageDialogs()
        val r = Reply()
        val confirm = PageDialog(PageDialog.CONFIRM, "确定删除吗?")
        d.open(confirm, r)
        // 没人回话就一直挂着,不自动确定
        assertEquals(confirm, d.current)
        assertTrue(r.said.isEmpty())
        assertEquals(DialogAnswer.Answered(confirm, accepted = true, textIgnored = false), d.answer(true, null))
        assertEquals(listOf("accept:-"), r.said)
        assertNull(d.current)
        assertEquals(DialogAnswer.NoDialog, d.answer(true, null))
        assertEquals("只回一次", 1, r.said.size)
    }

    @Test fun promptTakesTextOrItsDefault() {
        val d = PageDialogs()
        val r = Reply()
        d.open(PageDialog(PageDialog.PROMPT, "名字", defaultText = "访客"), r)
        d.answer(true, "小明")
        d.open(PageDialog(PageDialog.PROMPT, "名字", defaultText = "访客"), r)
        d.answer(true, null)
        assertEquals(listOf("accept:小明", "accept:访客"), r.said)
    }

    @Test fun textOnANonPromptOrADismissIsReportedUnused() {
        val d = PageDialogs()
        val r = Reply()
        d.open(PageDialog(PageDialog.ALERT, "hi"), r)
        assertEquals(true, (d.answer(true, "x") as DialogAnswer.Answered).textIgnored)
        d.open(PageDialog(PageDialog.PROMPT, "名字"), r)
        assertEquals(true, (d.answer(false, "x") as DialogAnswer.Answered).textIgnored)
        assertEquals(listOf("accept:-", "dismiss"), r.said)
    }

    @Test fun aNewerDialogDismissesTheOlderOneAndCloseDismissesWhatIsLeft() {
        val d = PageDialogs()
        val first = Reply(); val second = Reply()
        d.open(PageDialog(PageDialog.CONFIRM, "一"), first)
        d.open(PageDialog(PageDialog.BEFORE_UNLOAD, "二"), second)
        assertEquals(listOf("dismiss"), first.said)
        assertEquals("二", d.current?.message)
        d.close()
        assertEquals(listOf("dismiss"), second.said)
        assertNull(d.current)
    }
}
