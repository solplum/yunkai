package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.knowledge.ChannelFacts
import com.wangzan101.mika.core.knowledge.KnowledgeStore
import com.wangzan101.mika.core.knowledge.entry
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.cap.web.FakeWebPort
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

class AgentLoopWebKnowledgeTest {
    private var n = 0
    private fun call(name: String, args: String = "{}") = Part.ToolCall("w${++n}", name, args)
    private fun reply(vararg c: Part.ToolCall) = ModelReply(c.toList(), StopReason.ToolUse, null)
    private fun go(url: String) = reply(call("web_navigate", """{"url":"$url"}"""))
    private val observe get() = reply(call("phone_observe"))
    private val finish get() = com.wangzan101.mika.core.testing.answerReply("ok")

    private fun List<Message>.userTexts() = filter { it.role == Role.User }.map { it.plainText() }
    private fun List<Message>.refs() = contextBodies(ContextKind.AppKnowledge, ContextKind.SiteKnowledge)

    private val shop = entry("shop", "好店", domains = listOf("shop.example.com"))
    private val news = entry("news", "新闻", domains = listOf("news.example.org"))

    private suspend fun run(task: String, store: KnowledgeStore, vararg script: ModelReply, web: FakeWebPort? = FakeWebPort(),
                            facts: (suspend () -> ChannelFacts)? = null): FakeModel {
        val m = FakeModel(*script)
        AgentLoop(m, toolDispatcher(FakeScreen(), web = web), "BASE", knowledge = store, channelFacts = facts).run(task)
        return m
    }

    @Test fun domainKnowledgeInjectedOnFirstVisit() = runTest {
        val m = run("看看", KnowledgeStore(listOf(shop)), go("https://shop.example.com/a"), observe, finish)
        assertTrue(m.requests[0].refs().isEmpty())
        // 打开网页的结果就带站点,紧接着注入;之后 observe 同一站点不再重复
        assertEquals(1, m.requests[1].refs().size)
        assertEquals(1, m.requests[2].refs().size)
        assertTrue(m.requests[2].refs()[0].contains("正文shop"))
    }

    @Test fun injectedFromNavigateAndReadPageResults() = runTest {
        // 打开网页的结果里就有站点:不用等 observe
        val nav = run("看看", KnowledgeStore(listOf(shop)), go("https://shop.example.com/a"), finish)
        assertEquals(1, nav.requests[1].refs().size)
        // 读页的结果带 url:切了标签之后读页也能认出新站点
        val web = FakeWebPort().apply { page = page.copy(url = "https://news.example.org/today") }
        val read = run("看看", KnowledgeStore(listOf(shop, news)), go("https://shop.example.com/a"), reply(call("web_read_page")), finish, web = web)
        assertTrue(read.requests[2].refs().any { "正文news" in it })
    }

    @Test fun sameSiteNotInjectedTwice() = runTest {
        val web = FakeWebPort()
        val m = run("看看", KnowledgeStore(listOf(shop)), go("https://shop.example.com/a"), observe,
            go("https://m.shop.example.com/b"), observe, finish, web = web)
        assertEquals(1, m.requests.last().refs().size)
    }

    @Test fun sharesQuotaWithPackages() = runTest {
        // 任务文字命中两份(手机),网页上再来两个站点:每任务至多 3 份
        val a = entry("a", "甲App", packages = listOf("com.a")); val b = entry("b", "乙App", packages = listOf("com.b"))
        val m = run("用甲App和乙App", KnowledgeStore(listOf(a, b, shop, news)),
            go("https://shop.example.com/"), observe, go("https://news.example.org/"), observe, finish)
        val refs = m.requests.last().refs()
        assertEquals(3, refs.size)
        assertFalse(refs.any { "正文news" in it })
    }

    @Test fun channelLineBeforeTask() = runTest {
        val xhs = entry("xhs", "小红书", listOf("com.xingin.xhs"), listOf("xiaohongshu.com"))
        val m = run("搜小红书上的露营攻略", KnowledgeStore(listOf(xhs)), finish,
            facts = { ChannelFacts(setOf("com.xingin.xhs"), emptySet(), true) })
        val texts = m.requests[0].userTexts()
        val line = texts.single { it.startsWith("可用渠道:") }
        assertEquals("可用渠道:小红书 — App:已安装(com.xingin.xhs);网页:xiaohongshu.com", line)
        // 渠道行在第一次调用模型时就在,且排在参考资料前面
        val kinds = m.requests[0].map { ContextBuilder.kindOf(it) }
        assertTrue(kinds.indexOf(ContextKind.Channel) in 0 until kinds.indexOf(ContextKind.AppKnowledge))
        val none = run("搜小红书上的露营攻略", KnowledgeStore(listOf(xhs)), finish)
        assertTrue(none.requests[0].userTexts().none { it.startsWith("可用渠道:") })
    }

    @Test fun webPromptOnlyWhenWebAvailable() = runTest {
        val groups = { offered: Set<String> -> com.wangzan101.mika.core.capability.PromptEnv(offered, false).gate(SystemPrompt.read("groups")) }
        val phone = SystemPrompt.read("phone").lines().filterNot { "<!-- needs: phone_open_link -->" in it || "<!-- needs: phone_find_visually -->" in it }.joinToString("\n")
        val web = SystemPrompt.read("web").lines().filterNot { "<!-- needs: share_file -->" in it || "<!-- needs: web_run_site_command -->" in it }.joinToString("\n")
        // 两组都收着:只有入口一段和选渠道一段
        val collapsed = run("t", KnowledgeStore(emptyList()), finish)
        val entries = groups(setOf("phone_launch_app", "phone_set_alarm", "web_read_page", "web_navigate"))
        assertEquals("BASE\n\n$entries\n\n" + SystemPrompt.read("channels"), collapsed.system)
        // 打开网页、再打开 App:两组都展开,段落按固定的先后
        val withWeb = run("t", KnowledgeStore(emptyList()), go("https://shop.example.com/a"), reply(call("phone_launch_app", """{"package":"com.x"}""")), finish)
        assertEquals("BASE\n\n$entries\n\n" + SystemPrompt.read("channels") + "\n\n" + SystemPrompt.read("handover") + "\n\n" + phone + "\n\n" + web, withWeb.system)
        val phoneOnly = run("t", KnowledgeStore(emptyList()), reply(call("phone_launch_app", """{"package":"com.x"}""")), finish, web = null)
        assertEquals("BASE\n\n" + groups(setOf("phone_launch_app", "phone_set_alarm")) + "\n\n" + SystemPrompt.read("handover") + "\n\n" + phone, phoneOnly.system)
    }
}
