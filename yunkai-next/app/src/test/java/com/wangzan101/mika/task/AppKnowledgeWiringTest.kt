package com.wangzan101.mika.task

import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.knowledge.AppKnowledgeDrafter
import com.wangzan101.mika.core.knowledge.AppKnowledgeStage
import com.wangzan101.mika.core.knowledge.KnowledgeStore
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.knowledge.AppKnowledgeFiles
import com.wangzan101.mika.settings.MemorySwitches
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppKnowledgeWiringTest {
    @get:Rule val texts = ChineseTexts()

    @get:Rule val tmp = TemporaryFolder()
    private val knowledge = KnowledgeStore(KnowledgeStore.loadBundled())
    private val md = "---\nname: xianyu\npackages: [com.taobao.idlefish]\n---\n" +
        "## 是什么\n闲鱼二手交易应用，提供商品和私信。\n## 界面\n首页顶部有搜索入口，底部是消息、我的等标签。\n" +
        "## 功能\n- **搜索**：从首页搜索进入，能看到联系人、群和聊天记录的结果。"

    private fun runEvents(trial: Boolean = false): List<RunEvent> {
        val out = mutableListOf<RunEvent>(RunEvent.Observed(1, 1, "闲鱼首页", place = Place.app("com.taobao.idlefish")))
        listOf("phone_launch_app", "phone_tap", "phone_tap", "phone_scroll", "phone_tap").forEachIndexed { i, name ->
            out += RunEvent.ModelReply((i + 2).toLong(), i + 1, "", name,
                if (i == 0) """{"package":"com.taobao.idlefish"}""" else """{"x":500,"y":500}""", null, null, null)
            out += RunEvent.ToolResult((i + 20).toLong(), i + 1, name, """{"ok":true}""", true, null)
        }
        if (trial) out += RunEvent.State(99, "knowledge_trial_used", "xianyu")
        out += RunEvent.TaskEnded(100, "succeeded", "done")
        return out
    }

    private fun aftermath(events: List<RunEvent>) = RunAftermath("c1", "r1", "查看闲鱼", TaskState.Succeeded("已查看"),
        emptyList(), emptyList(), emptyList(), emptySet(), knownTools(), { events }, knowledge = knowledge,
        outcome = com.wangzan101.mika.core.agent.RunOutcome.Done)

    @Test fun draftRequiresApprovalThenVerifiedTrial() = runBlocking {
        val files = AppKnowledgeFiles(tmp.root)
        val learning = RunLearning(MemorySwitches(learnSkills = true), null, MapSkills(), GuideCatalog.loadBundled(),
            null, null, { "d1" }, appDrafter = AppKnowledgeDrafter(TextModel(md)), appDrafts = files)
        val notes = learning.learn(aftermath(runEvents()))
        assertEquals(AppKnowledgeStage.Draft, files.all().single().stage)
        assertTrue(notes.skillNotes.single().text.contains("APP.md"))
        assertTrue(files.notes().isEmpty())
        files.approve("d1")
        assertTrue(files.notes().getValue("xianyu").contains("未核实"))
        learning.learn(aftermath(runEvents(trial = true)))
        assertEquals(AppKnowledgeStage.Verified, files.all().single().stage)
    }
}
