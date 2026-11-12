package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.knowledge.KnowledgeEntry
import com.wangzan101.mika.core.knowledge.Link
import com.wangzan101.mika.core.knowledge.Surface
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import com.wangzan101.mika.core.testing.TOOLS
import com.wangzan101.mika.core.assembly.toolDispatcher

class ContextBuilderTest {
    private fun app(name: String, body: String = "正文$name", pkg: String = "p.$name", links: List<Link> = emptyList()) =
        KnowledgeEntry(name, "显示$name", Surface.Android, listOf(pkg), emptyList(), listOf(name), null, "", body, links = links)
    private fun site(name: String, domain: String) =
        KnowledgeEntry(name, "站$name", Surface.Web, emptyList(), listOf(domain), listOf(name), null, "", "正文$name")
    private fun text(m: Message) = (m.parts.single() as Part.Text).text
    private val utc = ZoneId.of("UTC")

    @Test fun envelopeCarriesKindSourceTrustAndRoundTrips() {
        val m = ContextBuilder().notice("步数自查", "正文里有 </context> 也不会提前合上")
        val t = text(m)
        assertEquals(Role.User, m.role)
        assertTrue(t, t.startsWith("<context kind=\"notice\" source=\"步数自查\" trust=\"${Trust.Mika.label}\">\n"))
        assertTrue(t.endsWith("\n</context>"))
        assertEquals(1, Regex("</context>").findAll(t).count())
        assertEquals(ContextKind.Notice, ContextBuilder.kindOf(m))
        assertEquals("步数自查", ContextBuilder.sourceOf(m))
        assertEquals("正文里有 <\\/context> 也不会提前合上", ContextBuilder.bodyOf(m))
        // 用户原话、工具结果不是上下文
        assertNull(ContextBuilder.kindOf(Message(Role.User, listOf(Part.Text("帮我查天气")), Author.Mika)))
        assertNull(ContextBuilder.kindOf(Message(Role.Tool, listOf(Part.ToolResult("c", ContextBuilder.envelope(ContextKind.Notice, "x", "y"), false)), Author.Mika)))
        assertNull(ContextBuilder.kindOf(Message(Role.User, listOf(Part.Text("<context kind=\"unknown\" source=\"x\">\n\n</context>")), Author.Mika)))
    }

    @Test fun startOrdersMessagesAndOnlyTaskIsPlain() {
        val s = ContextBuilder().start(
            "帮我查天气", priorTurns = "之前", memory = "记忆", channel = "可用渠道:…",
            knowledge = listOf(app("a"), site("s", "s.example.com")), knowledgeNote = "(注)", skillsIndex = "索引",
            skill = ContextBuilder.SkillText("personal:x", "说明\n", "全文"),
        )
        assertEquals(
            listOf(ContextKind.PriorTurns, ContextKind.Memory, null, ContextKind.Channel,
                ContextKind.AppKnowledge, ContextKind.SiteKnowledge, ContextKind.SkillsIndex, ContextKind.Skill),
            s.messages.map { ContextBuilder.kindOf(it) },
        )
        assertEquals("帮我查天气", text(s.messages[2]))
        assertTrue(s.messages.all { it.role == Role.User })
        assertEquals("(注)\n正文a", ContextBuilder.bodyOf(s.messages[4]))
        assertEquals("显示a · p.a", ContextBuilder.sourceOf(s.messages[4]))
        assertEquals("站s · s.example.com", ContextBuilder.sourceOf(s.messages[5]))
        assertEquals("说明\n全文", ContextBuilder.bodyOf(s.messages[7]))
        assertEquals(listOf("a", "s"), s.knowledge.map { it.name })
        assertTrue(s.skillKept)
        // 参考资料标明可能过时;记忆标明是用户以前说的
        assertTrue(Trust.Reference.label in text(s.messages[4]) && Trust.UserMemory.label in text(s.messages[1]))
    }

    @Test fun knowledgeItemCappedWithHint() {
        val s = ContextBuilder().start("t", knowledge = listOf(app("a", body = "字".repeat(KNOWLEDGE_BODY_LIMIT + 50))))
        val body = ContextBuilder.bodyOf(s.messages[1])!!
        assertEquals(clipKnowledge("字".repeat(KNOWLEDGE_BODY_LIMIT + 50)), body)
        assertTrue(body.endsWith(KNOWLEDGE_TRUNCATED_HINT))
    }

    @Test fun layerBudgetClipsThenDrops() {
        // L2 上限 5000:第一份 3000 全给,第二份截到剩下的 2000,第三份没额度了不给
        val b = ContextBuilder(ContextBudget(layers = mapOf(ContextLayer.Knowledge to 5_000)))
        val s = b.start("t", knowledge = (1..3).map { app("k$it", body = "字".repeat(3_000)) })
        val bodies = s.messages.mapNotNull { m -> ContextBuilder.bodyOf(m)?.takeIf { ContextBuilder.kindOf(m) == ContextKind.AppKnowledge } }
        assertEquals(2, bodies.size)
        assertEquals(3_000, bodies[0].length)
        assertEquals(2_000, bodies[1].length)
        assertTrue(bodies[1].endsWith(KNOWLEDGE_TRUNCATED_HINT))
        assertEquals(listOf("k1", "k2"), s.knowledge.map { it.name })
        // 中途再来一份:L2 已满,不给
        assertNull(b.knowledge(app("k4"), "", withLinks = false))
    }

    @Test fun totalBudgetTrimsLowPriorityLayersFirst() {
        // 总额 4000:L5 先拿走 2500(不截),L2 截到剩下的 1500,L4 一点不剩:索引与个人技能都不给
        val b = ContextBuilder(ContextBudget(total = 4_000))
        val s = b.start("t", memory = "忆".repeat(2_500), knowledge = listOf(app("a", body = "知".repeat(3_000))),
            skillsIndex = "索引", skill = ContextBuilder.SkillText("personal:x", "说明\n", "全文"))
        val kinds = s.messages.map { ContextBuilder.kindOf(it) }
        assertEquals(listOf(ContextKind.Memory, null, ContextKind.AppKnowledge), kinds)
        assertEquals(1_500, ContextBuilder.bodyOf(s.messages[2])!!.length)
        assertFalse(s.skillKept)
        // L5 即使超出总额也不丢
        val big = ContextBuilder(ContextBudget(total = 100)).start("t", priorTurns = "前".repeat(500), memory = "忆".repeat(500))
        assertEquals(listOf(ContextKind.PriorTurns, ContextKind.Memory, null), big.messages.map { ContextBuilder.kindOf(it) })
    }

    @Test fun knowledgeOncePerEntryAndAtMostThreePerTask() {
        val b = ContextBuilder()
        val s = b.start("t", knowledge = listOf(app("a"), app("b")))
        assertEquals(2, s.knowledge.size)
        assertNull(b.knowledge(app("a"), "", false))
        assertNotNull(b.knowledge(app("c"), "", false))
        assertNull(b.knowledge(app("d"), "", false))
        assertNull(b.knowledge(app("e", body = "x").copy(body = null), "", false))
    }

    @Test fun linksOnlyWhenAskedAndNeverClipped() {
        val e = app("a", body = "字".repeat(9_000), links = listOf(Link("扫一扫", "someapp://scan", "p.a")))
        val on = ContextBuilder.bodyOf(ContextBuilder().knowledge(e, "", withLinks = true)!!)!!
        assertTrue(on.endsWith("- 扫一扫: someapp://scan (package p.a)"))
        assertTrue(ContextBuilder.LINKS_HEADER in on && KNOWLEDGE_TRUNCATED_HINT in on)
        val off = ContextBuilder.bodyOf(ContextBuilder().knowledge(e, "", withLinks = false)!!)!!
        assertFalse("someapp://" in off)
    }

    @Test fun linkedSkillsAddOnlyUnlistedOnes() {
        val b = ContextBuilder()
        b.start("t", skillsIndex = "索引", indexedSkills = listOf("skill:a"))
        val m = b.linkedSkills("关联 甲 的技能", "头", listOf("skill:a" to "- skill:a", "skill:b" to "- skill:b"))!!
        assertEquals(ContextKind.SkillsIndex, ContextBuilder.kindOf(m))
        assertEquals("头\n- skill:b", ContextBuilder.bodyOf(m))
        assertNull(b.linkedSkills("关联 乙 的技能", "头", listOf("skill:b" to "- skill:b")))
        // L4 用完就不再补
        val tight = ContextBuilder(ContextBudget(layers = mapOf(ContextLayer.Skills to 5)))
        assertNull(tight.linkedSkills("x", "头", listOf("skill:c" to "- skill:c 很长的描述")))
    }

    @Test fun runtimeStateFormats() {
        val b = ContextBuilder(zone = utc)
        val t0 = 1_793_000_000_000L // 2026-10-26 07:33 UTC,周一
        fun body(place: Place?, label: String? = null, mode: String? = null, step: Int = 3) =
            ContextBuilder.bodyOf(Message(Role.User, listOf(b.runtimeState(place, label, mode, step, 60, t0)), Author.Mika))
        assertEquals("当前界面:还没读取当前界面或页面\n步数:第 3 步,上限 60 步\n本地时间:2026-10-26 07:33 周一", body(null))
        assertEquals("当前界面:App 铁路12306(com.MobileTicket)", body(Place.app("com.MobileTicket"), "铁路12306")!!.lines()[0])
        assertEquals("当前界面:App com.x", body(Place.app("com.x"))!!.lines()[0])
        assertEquals("当前界面:网页 www.12306.cn(手机版)", body(Place.site("www.12306.cn"), mode = "mobile")!!.lines()[0])
        assertEquals("当前界面:网页 a.com", body(Place.site("a.com"))!!.lines()[0])
        assertEquals("步数:已用完(上限 60 步)", body(null, step = 61)!!.lines()[1])
        assertEquals(ContextKind.RuntimeState, ContextBuilder.kindOf(b.runtimeState(null, null, null, 1, 60, t0).text))
    }
}

/**
 * 运行时状态:每步一段,接在这一步最后一条消息的末尾,只追加;发过的消息一概不改,
 * 所以第 N 步的请求是第 N+1 步请求的前缀(裁剪、折叠的档位移动处除外)。
 */
class RuntimeStateHistoryTest {
    private fun tools(vararg c: Part.ToolCall) = ModelReply(c.toList(), StopReason.ToolUse, null)
    private val finish = com.wangzan101.mika.core.testing.answerReply("ok")
    /** 一次请求按消息逐条序列化;前缀关系按字符串判断。 */
    private fun wire(req: List<Message>) = req.joinToString("\n") { it.toString() }

    private fun run(flags: SpeedFlags, vararg script: ModelReply, web: com.wangzan101.mika.core.cap.web.FakeWebPort? = null): FakeModel {
        val m = FakeModel(*script, finish)
        runBlocking {
            AgentLoop(m, toolDispatcher(FakeScreen(), web = web), "BASE", maxSteps = 20, clock = { 0L },
                zone = ZoneId.of("UTC"), flags = flags).run("t")
        }
        return m
    }

    private fun assertAppendOnly(m: FakeModel) {
        m.requests.zipWithNext().forEachIndexed { i, (a, b) ->
            assertTrue("request ${i + 1} is not a prefix of request ${i + 2}", wire(b).startsWith(wire(a)))
        }
        m.requests.forEachIndexed { i, req ->
            // 每步一段,都还在;最新一段在最后一条消息的末尾
            assertEquals(i + 1, req.runtimeStates().size)
            assertTrue(req.runtimeStates().last().contains("步数:第 ${i + 1} 步,上限 20 步"))
            val tail = req.last().parts.last() as Part.Text
            assertEquals(ContextKind.RuntimeState, ContextBuilder.kindOf(tail.text))
        }
    }

    private val waits = (1..8).map { tools(Part.ToolCall("w$it", "wait", """{"seconds":${it / 10.0}}""")) }.toTypedArray()

    @Test fun requestsAreAppendOnlyInPlainMode() = assertAppendOnly(run(SpeedFlags.OFF, *waits))

    // 读屏在阶梯档位内(不超过 5 次)也不动前缀
    @Test fun observationsWithinBandAreAppendOnly() =
        assertAppendOnly(run(SpeedFlags.OFF, *(1..5).map { tools(Part.ToolCall("o$it", "phone_observe", """{"n":$it}""")) }.toTypedArray()))

    @Test fun stateRidesOnTheStepsOwnMessage() {
        val m = run(SpeedFlags.OFF, tools(Part.ToolCall("o1", "phone_observe", "{}")))
        // 第 1 步接在任务原话上(没有别的上下文);第 2 步接在 observe 的工具结果上,而不是另起一条
        val first = m.requests[0]
        assertEquals(1, first.size)
        assertEquals("t", (first[0].parts[0] as Part.Text).text)
        val second = m.requests[1]
        assertEquals(3, second.size)
        assertEquals(Role.Tool, second.last().role)
        assertTrue(second.last().parts.first() is Part.ToolResult)
        assertTrue(second.runtimeStates().last().startsWith("当前界面:App "))
    }

    @Test fun webSurfaceAlwaysShowsCurrentMode() {
        val web = com.wangzan101.mika.core.cap.web.FakeWebPort()
        val go = tools(Part.ToolCall("n1", "web_navigate", """{"url":"https://shop.example.com/a"}"""))
        val desktop = run(SpeedFlags.OFF, go, web = web)
        assertTrue(desktop.requests[1].runtimeStates().last(), desktop.requests[1].runtimeStates().last().startsWith("当前界面:网页 shop.example.com(桌面版)"))
        // 版本以浏览器此刻实际用的为准,不看本任务调没调过 web_site_mode
        web.mode = "mobile"
        val mobile = run(SpeedFlags.OFF, go, web = web)
        assertTrue(mobile.requests[1].runtimeStates().last().startsWith("当前界面:网页 shop.example.com(手机版)"))
    }

    @Test fun foldKeepsPinnedContextWithoutItsStaleState() {
        val b = ContextBuilder(zone = ZoneId.of("UTC"))
        fun round(i: Int) = listOf(
            Message(Role.Assistant, listOf(Part.ToolCall("c$i", "phone_tap", """{"target":"按钮$i"}""")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("c$i", """{"ok":true,"changed":true,"pad":"${"x".repeat(400)}"}""", false),
                b.runtimeState(Place.app("com.x"), null, null, i + 1, 60, 0)), Author.Mika),
        )
        val knowledge = b.knowledge(KnowledgeEntry("a", "甲", Surface.Android, listOf("p.a"), emptyList(), emptyList(), null, "", "正文"), "", false)!!
        val withState = knowledge.copy(parts = knowledge.parts + b.runtimeState(Place.app("p.a"), null, null, 4, 60, 0))
        val h = listOf(Message(Role.User, listOf(Part.Text("task")), Author.Mika)) + (1..3).flatMap(::round) + withState + (4..30).flatMap(::round)
        val c = ContextCompressor(TOOLS).compress(h, contextTokens = 1000)
        assertTrue(c.foldedSteps > 0)
        assertTrue(knowledge in c.messages)
        // 留下的状态都是没折叠的那些步自己的
        assertEquals(30 - c.foldedSteps, c.messages.runtimeStates().size)
    }

    @Test fun systemPromptIsByteIdenticalWithinTask() = runBlocking {
        val m = FakeModel(tools(Part.ToolCall("o1", "phone_observe", "{}")), tools(Part.ToolCall("w1", "wait", """{"seconds":0.1}""")), finish)
        val systems = mutableListOf<String>()
        val model = object : com.wangzan101.mika.core.model.ChatModel by m {
            override suspend fun complete(system: String, messages: List<Message>, tools: List<com.wangzan101.mika.core.model.ToolSpec>): ModelReply {
                systems += system
                return m.complete(system, messages, tools)
            }
        }
        AgentLoop(model, toolDispatcher(FakeScreen()), "BASE", maxSteps = 10, clock = { 0L }, zone = ZoneId.of("UTC")).run("t")
        assertEquals(3, systems.size)
        // 第一次读屏展开了手机一组:系统提示词只在那之后变一次,之后逐字相同
        assertEquals(2, systems.distinct().size)
        assertEquals(systems[1], systems[2])
        assertEquals("当前界面:还没读取当前界面或页面", m.requests[0].runtimeStates().single().lines()[0])
    }
}

/** 前台到了关联技能的 App:技能补进索引,同一技能只列一次。 */
class LinkedSkillsTest {
    @Test fun foregroundAppAddsLinkedSkills() = runBlocking {
        val catalog = com.wangzan101.mika.core.knowledge.GuideCatalog.loadBundled()
        val store = com.wangzan101.mika.core.knowledge.KnowledgeStore(com.wangzan101.mika.core.knowledge.KnowledgeStore.loadBundled())
        val linked = catalog.linkedSkills("wechat").map { GuideRecommender.id(it) }
        assertTrue(linked.isNotEmpty())
        val screen = object : com.wangzan101.mika.core.uistate.ScreenPort by FakeScreen() {
            override suspend fun snapshot() = com.wangzan101.mika.core.uistate.ScreenSnapshot("s", "com.tencent.mm", emptyList(), 1080, 2400)
        }
        fun tools(vararg c: Part.ToolCall) = ModelReply(c.toList(), StopReason.ToolUse, null)
        val m = FakeModel(tools(Part.ToolCall("o1", "phone_observe", "{}")), tools(Part.ToolCall("o2", "phone_observe", "{}")),
            com.wangzan101.mika.core.testing.answerReply("ok"))
        AgentLoop(m, toolDispatcher(screen, skills = catalog), "B", knowledge = store, skills = catalog).run("看看")
        val added = m.requests.last().filter { ContextBuilder.kindOf(it) == ContextKind.SkillsIndex }
        assertEquals(1, added.size)
        assertTrue(ContextBuilder.sourceOf(added[0])!!.startsWith("关联 "))
        val body = ContextBuilder.bodyOf(added[0])!!
        assertTrue(body, linked.all { "- $it(" in body })
    }
}
