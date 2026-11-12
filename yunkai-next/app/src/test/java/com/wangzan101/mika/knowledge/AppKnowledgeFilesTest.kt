package com.wangzan101.mika.knowledge

import com.wangzan101.mika.core.knowledge.AppKnowledgeDraft
import com.wangzan101.mika.core.knowledge.AppKnowledgeStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppKnowledgeFilesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val md = "---\nname: qq\npackages: [com.tencent.mobileqq]\n---\n" +
        "## 是什么\nQQ 应用。\n## 界面\n首页有搜索。\n## 功能\n搜索与查看。"

    @Test fun draftTrialVerifiedAndArchive() {
        val files = AppKnowledgeFiles(tmp.root)
        val draft = AppKnowledgeDraft("d1", "qq", "com.tencent.mobileqq", md, "run-1", AppKnowledgeStage.Draft, 1)
        assertTrue(files.putPending(draft))
        assertFalse(files.putPending(draft.copy(id = "d2")))
        assertTrue(files.notes().isEmpty())
        assertTrue(File(tmp.root, "knowledge/app-drafts/d1/APP.md").isFile)
        assertTrue(files.approve("d1"))
        assertTrue(files.notes().getValue("qq").contains("未核实"))
        assertEquals(AppKnowledgeStage.Verified, files.recordTrial("qq", true)?.stage)
        assertTrue(files.notes().getValue("qq").contains("已核实"))
        assertTrue(File(tmp.root, "knowledge/app-drafts/archive").listFiles().orEmpty().isNotEmpty())
    }

    @Test fun failedTrialIsArchivedAndNeverInjected() {
        val files = AppKnowledgeFiles(tmp.root)
        assertTrue(files.putPending(AppKnowledgeDraft("d1", "qq", "com.tencent.mobileqq", md, "run-1", AppKnowledgeStage.Draft, 1)))
        files.approve("d1")
        assertEquals(AppKnowledgeStage.Archived, files.recordTrial("qq", false)?.stage)
        assertTrue(files.notes().isEmpty())
        assertEquals(AppKnowledgeStage.Archived, AppKnowledgeFiles(tmp.root).all().single().stage)
    }
}
