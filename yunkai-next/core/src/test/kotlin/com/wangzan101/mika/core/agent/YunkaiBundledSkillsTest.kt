package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.knowledge.GuideView
import com.wangzan101.mika.core.knowledge.KnowledgeIndex
import com.wangzan101.mika.core.knowledge.SKILL_KINDS
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Real bundled resources -> generated index -> recommendation -> skill_read -> next model turn. */
class YunkaiBundledSkillsTest {
    private val catalog = GuideCatalog.loadBundled()
    private val requests = mapOf(
        "yunkai-eli5" to listOf("@eli5 给我讲一下量子纠缠", "请用大白话讲明白黑洞", "给我科普一下潮汐"),
        "yunkai-goutoujunshi" to listOf("@狗头军师 帮我分析一下", "暧昧对象这句怎么回", "分手后该不该复合"),
    )

    @Test fun sourceIndexAndRuntimeContainBothRealSkills() {
        val built = KnowledgeIndex.build(File("src/main/resources"))
        assertTrue(built.errors.joinToString("\n"), built.errors.isEmpty())
        for (name in requests.keys) {
            assertTrue(built.entries.any { it.kind == "skill" && it.name == name })
            assertTrue(catalog.items.any { it.kind == "skill" && it.name == name })
            val view = catalog.view("skill:$name", among = SKILL_KINDS) as GuideView.Found
            assertEquals("SKILL.md", view.file)
            assertFalse(view.truncated)
            assertTrue(view.content.contains("原生"))
            assertTrue(view.content.contains("app/src/main/res/raw/skill_"))
        }
    }

    @Test fun chineseAndLegacyMentionsAreActuallyRecommended() {
        for ((name, tasks) in requests) for (task in tasks) {
            assertTrue(task, catalog.find(task, among = SKILL_KINDS).any { it.name == name })
            assertTrue(task, "skill:$name" in GuideRecommender.recommend(catalog, task).ids)
        }
        val unrelated = GuideRecommender.recommend(catalog, "把屏幕亮度调高一点").ids
        assertTrue(unrelated.none { it.startsWith("skill:yunkai-") })
    }

    @Test fun bothSkillsAreReadableInActualAgentLoopWithoutDeviceOrNetwork() = runBlocking {
        for ((name, tasks) in requests) {
            val call = Part.ToolCall("read-$name", "skill_read", """{"name":"skill:$name"}""")
            val model = FakeModel(ModelReply(listOf(call), StopReason.ToolUse, null), answerReply("已按技能整理建议"))
            AgentLoop(model, toolDispatcher(FakeScreen(), skills = catalog), "test", skills = catalog).run(tasks.first())
            assertTrue(model.requests.first().any { "skill:$name" in it.plainText() })
            val result = model.requests.last().flatMap { it.parts }.filterIsInstance<Part.ToolResult>()
                .single { it.callId == call.id }
            assertTrue(result.content, result.content.contains("\"ok\":true"))
            assertTrue(result.content, result.content.contains(name))
            assertTrue(result.content, result.content.contains("原生"))
        }
    }

    @Test fun teachingKeepsNativeFallbackAndRelationshipKeepsEvidenceAndSafety() {
        val teaching = (catalog.view("skill:yunkai-eli5") as GuideView.Found).content
        assertTrue(teaching.contains("默认直接输出中文 Markdown"))
        assertTrue(teaching.contains("share_file"))
        assertTrue(teaching.contains("比方在哪里不再成立"))
        val relationship = (catalog.view("skill:yunkai-goutoujunshi") as GuideView.Found).content
        for (section in listOf("工具箱一", "工具箱二", "工具箱三", "工具箱四", "安全边界", "停止条件", "不假设旧版忆枢")) {
            assertTrue(section, relationship.contains(section))
        }
    }
}
