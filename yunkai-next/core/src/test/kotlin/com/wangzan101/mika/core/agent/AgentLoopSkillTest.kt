package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.skills.InMemoryPersonalSkills
import com.wangzan101.mika.core.skills.PersonalSkill
import com.wangzan101.mika.core.skills.personalSkill
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

class AgentLoopSkillTest {
    private class ListSink : RunSink {
        val events = mutableListOf<RunEvent>()
        override fun event(e: RunEvent) { events += e }
    }

    private fun call(name: String, args: String = "{}") = Part.ToolCall("k$name${args.hashCode()}", name, args)
    private fun reply(vararg c: Part.ToolCall) = ModelReply(c.toList(), StopReason.ToolUse, null)
    private val finish = com.wangzan101.mika.core.testing.answerReply("ok")
    private val bundled = GuideCatalog.loadBundled()

    private class Run(val requests: List<List<Message>>, val events: List<RunEvent>)

    private fun run(task: String, vararg personal: PersonalSkill, recommend: Boolean = true, script: List<Any> = listOf(finish)): Run {
        val catalog = GuideCatalog.withPersonal(bundled, InMemoryPersonalSkills(*personal))
        val model = FakeModel(*script.toTypedArray())
        val sink = ListSink()
        runBlocking {
            AgentLoop(model, toolDispatcher(FakeScreen(), skills = catalog), "B", sink = sink,
                skills = catalog, recommendSkills = recommend).run(task)
        }
        return Run(model.requests, sink.events)
    }

    private fun List<Message>.userTexts() = filter { it.role == Role.User }.map { it.plainText() }
    private fun List<Message>.index() = userTexts().filter { it.startsWith(SKILL_INDEX_HEADER) || it.startsWith(KNOWLEDGE_INDEX_HEADER) }
    private fun List<Message>.fullTexts() = userTexts().filter { it.startsWith(PERSONAL_SKILL_HEADER) }

    @Test fun indexLinesForTop3() {
        val r = run("用闲鱼查手机,再发微信给小王,再在知乎搜一下")
        val idx = r.requests.first().index().single()
        // 知识和技能各在自己的标题下(标题里写着用哪个工具读全文)
        val (lines, headers) = idx.lines().partition { it.startsWith("- ") }
        assertEquals(3, lines.size)
        assertTrue(headers.isNotEmpty() && headers.all { it == SKILL_INDEX_HEADER || it == KNOWLEDGE_INDEX_HEADER })
        assertTrue(idx.lines().first() == KNOWLEDGE_INDEX_HEADER && lines.any { it.startsWith("- app:xianyu(闲鱼):") })
        // 索引行在任务原文之后
        val texts = r.requests.first().userTexts()
        assertTrue(texts.indexOf(idx) > texts.indexOfFirst { it.startsWith("用闲鱼") })
    }

    @Test fun personalSkillFullTextInjectedOnce() {
        val mine = personalSkill("lao-ma-msg", body = "## 入口\n打开微信找老妈", display = "给老妈发消息")
        val r = run("给老妈发消息说晚点到", mine, script = listOf(reply(call("skill_read", """{"name":"lao-ma-msg"}""")), finish))
        val first = r.requests.first().fullTexts()
        assertEquals(listOf("${PERSONAL_SKILL_HEADER}给老妈发消息(personal:lao-ma-msg)\n## 入口\n打开微信找老妈"), first)
        assertEquals(1, r.requests.last().fullTexts().size)
        assertTrue(r.requests.first().index().single().contains("- personal:lao-ma-msg(给老妈发消息):"))
    }

    @Test fun longPersonalSkillTruncated() {
        val body = "## 步骤\n" + "很".repeat(4000)
        val r = run("给老妈发消息", personalSkill("lao-ma-msg", body = body, display = "给老妈发消息"))
        val text = r.requests.first().fullTexts().single()
        assertTrue(text.endsWith("\n…(已截断,可用 skill_read 查看全文)"))
        assertEquals(body.take(PERSONAL_SKILL_LIMIT), text.substringAfter("\n").substringBefore("\n…(已截断"))
    }

    @Test fun skillUsedEventsEmitted() {
        val mine = personalSkill("lao-ma-msg", display = "给老妈发消息")
        val other = personalSkill("my-trip", display = "我的出行")
        val r = run("给老妈发消息", mine, other, script = listOf(
            reply(call("skill_read", """{"name":"my-trip"}""")),
            reply(call("knowledge_read", """{"name":"app:wechat"}""")), // 内置的不记
            finish,
        ))
        val used = r.events.filterIsInstance<RunEvent.SkillUsed>().map { it.name to it.how }
        assertEquals(listOf("lao-ma-msg" to "injected", "my-trip" to "viewed"), used)
    }

    @Test fun noMatchNoInjection() {
        val r = run("把屏幕亮度调高一点", personalSkill("lao-ma-msg", display = "给老妈发消息"))
        assertTrue(r.requests.first().index().isEmpty())
        assertTrue(r.requests.first().fullTexts().isEmpty())
        assertTrue(r.events.none { it is RunEvent.SkillUsed })
        // 关掉推荐时即使命中也不给
        val off = run("给老妈发消息", personalSkill("lao-ma-msg", display = "给老妈发消息"), recommend = false)
        assertTrue(off.requests.first().index().isEmpty() && off.requests.first().fullTexts().isEmpty())
    }

    @Test fun suspendedPersonalNotInjected() {
        val r = run("给老妈发消息", personalSkill("lao-ma-msg", display = "给老妈发消息", status = com.wangzan101.mika.core.skills.SkillStatus.suspended))
        assertTrue(r.requests.first().fullTexts().isEmpty())
    }
}
