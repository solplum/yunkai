package com.wangzan101.mika.core.knowledge

import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.assembly.knownTools
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppKnowledgeLearningTest {
    private val app = KnowledgeStore.loadBundled().first { it.name == "xianyu" }
    private val markdown = "---\nname: xianyu\npackages: [com.taobao.idlefish]\n---\n" +
        "## 是什么\n闲鱼二手交易应用，提供商品和私信。\n## 界面\n首页顶部有搜索入口，底部是消息、我的等标签。\n" +
        "## 功能\n- **搜索**：从首页搜索进入，能看到商品的结果。"
    private fun events(): List<RunEvent> {
        val out = mutableListOf<RunEvent>()
        val names = listOf("phone_launch_app", "phone_tap", "phone_tap", "phone_scroll", "phone_tap")
        names.forEachIndexed { i, name ->
            out += RunEvent.ModelReply(i.toLong(), i + 1, "", name,
                if (i == 0) """{"package":"com.taobao.idlefish"}""" else """{"x":500,"y":500}""", null, null, null)
            out += RunEvent.ToolResult((i + 20).toLong(), i + 1, name, """{"ok":true}""", true, null)
        }
        return out
    }

    @Test fun verifiedActionsProduceDraftButNoDirectKnowledge() = runBlocking {
        val model = FakeModel(ModelReply(listOf(Part.Text(markdown)), StopReason.EndTurn, null))
        val draft = AppKnowledgeDrafter(model).draft("r1", "查看闲鱼", "已查看", events(), knownTools(), app, "d1", 5)
        assertEquals(AppKnowledgeStage.Draft, draft?.stage)
        assertEquals("xianyu", draft?.appName)
        assertTrue(model.requests.single().single().parts.filterIsInstance<Part.Text>().single().text.contains("实际动作"))
    }

    @Test fun invalidAppPackageIsRejected() = runBlocking {
        val bad = markdown.replace("com.taobao.idlefish", "com.other.app")
        val model = FakeModel(ModelReply(listOf(Part.Text(bad)), StopReason.EndTurn, null))
        assertNull(AppKnowledgeDrafter(model).draft("r1", "查看闲鱼", "已查看", events(), knownTools(), app, "d1", 5))
    }

    @Test fun missingRequiredSectionIsRejected() = runBlocking {
        val bad = markdown.replace("## 功能", "## 其他")
        val model = FakeModel(ModelReply(listOf(Part.Text(bad)), StopReason.EndTurn, null))
        assertNull(AppKnowledgeDrafter(model).draft("r1", "查看闲鱼", "已查看", events(), knownTools(), app, "d1", 5))
    }
}
