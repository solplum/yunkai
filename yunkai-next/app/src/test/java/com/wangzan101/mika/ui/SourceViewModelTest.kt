package com.wangzan101.mika.ui

import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.verify.AnswerSource
import com.wangzan101.mika.record.RunStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val store by lazy { RunStore(tmp.root.resolve("files"), { null }, 30, { 1L }) { it } }

    @Test fun mapsSavedSourceToObservedTextAndScreenshot() {
        val run = store.open("看页面")
        run.event(RunEvent.Observed(1, 2, "页面上显示余额 10 元", id = 7, title = "余额"))
        run.media(3, byteArrayOf(1, 2, 3))
        run.event(RunEvent.Sources(2, listOf(AnswerSource(7, "read", "com.example.bank", "余额", 2, listOf(3)))))
        run.close()
        val vm = SourceViewModel(store, run.runId, null) { "银行" }
        val item = vm.load().entries.single()
        assertEquals("银行", item.place)
        assertEquals("页面上显示余额 10 元", item.text)
        assertEquals(listOf(3), item.screenshots)
        assertTrue(vm.screen(3)?.isFile == true)
    }

    @Test fun oldAnswerKeepsLegacyTextWhenThereIsNoRun() {
        val page = SourceViewModel(store, null, "原始依据").load()
        assertEquals("原始依据", page.legacyText)
        assertEquals(emptyList<SourceEntry>(), page.entries)
        assertNull(SourceViewModel(store, null, null).screen(1))
    }
}
