package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.knowledge.KnowledgeEntry
import com.wangzan101.mika.core.knowledge.KnowledgeStore
import com.wangzan101.mika.core.knowledge.Link
import com.wangzan101.mika.core.knowledge.Surface
import com.wangzan101.mika.core.knowledge.UNAVAILABLE_TOOLS_NOTE
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

private class PkgScreen(private val pkgs: List<String?>) : ScreenPort by FakeScreen() {
    private var i = 0
    override suspend fun snapshot() = ScreenSnapshot("s${i}", pkgs[minOf(i++, pkgs.size - 1)], emptyList(), 1080, 2400)
}

private fun e(name: String, pkg: String, alias: String = name, body: String? = "正文$name") =
    KnowledgeEntry(name, name, Surface.Android, listOf(pkg), emptyList(), listOf(alias), null, "", body)

private fun call(name: String, args: String = "{}") = Part.ToolCall("k$name${args.hashCode()}", name, args)
private fun reply(vararg c: Part.ToolCall) = com.wangzan101.mika.core.model.ModelReply(c.toList(), com.wangzan101.mika.core.model.StopReason.ToolUse, null)

/** 参考资料:来源 + 正文。 */
private fun List<Message>.refs() = filter { ContextBuilder.kindOf(it) == ContextKind.AppKnowledge }
    .map { "${ContextBuilder.sourceOf(it)}\n${ContextBuilder.bodyOf(it)}" }

class AgentLoopKnowledgeTest {
    private fun run(task: String, store: KnowledgeStore, screen: ScreenPort, vararg script: Any): List<Message> {
        val model = FakeModel(*script)
        kotlinx.coroutines.runBlocking { AgentLoop(model, toolDispatcher(screen), "B", knowledge = store).run(task) }
        return model.requests.last()
    }

    private val finish = com.wangzan101.mika.core.testing.answerReply("ok")

    @Test fun injectsOnTaskMatch() {
        val msgs = run("用 alpha 做点事", KnowledgeStore(listOf(e("alpha", "p.a"))), FakeScreen(), finish)
        assertEquals(listOf("alpha · p.a\n$UNAVAILABLE_TOOLS_NOTE\n正文alpha"), msgs.refs())
        assertTrue(msgs.any { m -> (m.parts.first() as? Part.Text)?.text?.contains("trust=\"${Trust.Reference.label}\"") == true })
    }

    @Test fun injectsOnFirstForegroundApp() {
        val store = KnowledgeStore(listOf(e("alpha", "p.a")))
        val msgs = run("随便", store, PkgScreen(listOf("p.a", "p.a")),
            reply(call("phone_observe")), reply(call("phone_list_elements")), finish)
        assertEquals(1, msgs.refs().size)
    }

    @Test fun neverMoreThanThree() {
        val store = KnowledgeStore((1..5).map { e("app$it", "p.$it") })
        val msgs = run("app1 app2", store, PkgScreen(listOf("p.3", "p.4", "p.5")),
            reply(call("phone_observe")), reply(call("phone_list_elements")), reply(call("phone_observe", """{"x":1}""")), finish)
        assertEquals(3, msgs.refs().size)
    }

    @Test fun stubsAreNotInjected() {
        val store = KnowledgeStore(listOf(e("alpha", "p.a", body = null)))
        val msgs = run("用 alpha", store, PkgScreen(listOf("p.a")), reply(call("phone_observe")), finish)
        assertEquals(0, msgs.refs().size)
    }

    @Test fun taskMatchedEntryNotInjectedAgainOnForeground() {
        val msgs = run("用 alpha", KnowledgeStore(listOf(e("alpha", "p.a"))), PkgScreen(listOf("p.a")), reply(call("phone_observe")), finish)
        assertEquals(1, msgs.refs().size)
    }

    @Test fun taskMatchUsesInstalledAppNames() {
        val store = KnowledgeStore(listOf(e("brand", "p.shop", alias = "某牌")))
        val model = FakeModel(finish)
        kotlinx.coroutines.runBlocking {
            AgentLoop(model, toolDispatcher(FakeScreen()), "B", knowledge = store, installedApps = { listOf("p.fun" to "乐园") }).run("打开某牌乐园")
        }
        assertEquals(0, model.requests.last().refs().size)
    }

    @Test fun knowledgeSurvivesCompression() {
        val store = KnowledgeStore(listOf(e("alpha", "p.a")))
        val model = FakeModel(reply(call("phone_observe")), *(1..15).map { reply(call("wait", """{"seconds":${it / 10.0}}""")) }.toTypedArray(), finish, contextTokens = 100)
        kotlinx.coroutines.runBlocking { AgentLoop(model, toolDispatcher(PkgScreen(listOf("p.a"))), "B", knowledge = store).run("随便") }
        val last = model.requests.last()
        assertEquals(1, last.refs().size)
        assertTrue(last.any { m -> m.plainText().startsWith(ContextCompressor.HEADER) })
    }

    @Test fun linksInjectedOnlyWithDeepLinks() {
        val store = KnowledgeStore(listOf(e("alpha", "p.a").copy(links = listOf(Link("扫一扫", "someapp://scan")))))
        fun refs(flags: SpeedFlags): List<String> {
            val model = FakeModel(finish)
            kotlinx.coroutines.runBlocking {
                AgentLoop(model, toolDispatcher(FakeScreen(), deepLinks = flags.deepLinks), "B", knowledge = store, flags = flags).run("用 alpha 扫码")
            }
            return model.requests.last().refs()
        }
        assertTrue(refs(SpeedFlags(deepLinks = true)).single().contains("扫一扫: someapp://scan"))
        assertTrue(refs(SpeedFlags.OFF).single().let { "someapp://" !in it })
    }

    @Test fun injectedLinksBecomeOpenable() {
        val store = KnowledgeStore(listOf(e("alpha", "p.a").copy(links = listOf(Link("扫一扫", "someapp://scan")))))
        val tools = toolDispatcher(FakeScreen(), deepLinks = true)
        val model = FakeModel(reply(call("phone_open_link", """{"uri":"someapp://scan"}""")), reply(call("phone_open_link", """{"uri":"someapp://other"}""")), finish)
        kotlinx.coroutines.runBlocking { AgentLoop(model, tools, "B", knowledge = store, flags = SpeedFlags(deepLinks = true)).run("用 alpha 扫码") }
        val results = model.requests.last().flatMap { it.parts }.filterIsInstance<Part.ToolResult>().map { it.content }
        // 列出的那条交给了端口(假端口不支持),没列出的被拒
        assertTrue(results.toString(), results.any { "\"unsupported\"" in it })
        assertTrue(results.toString(), results.any { "reference notes" in it })
    }
}
