package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.SystemPrompt
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.sandbox.FakeSandbox
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.cap.web.FakeWebPort
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.testing.fakeModel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具分两层:一直都在的通用工具加各组的入口(短说明),第一次用到一组时展开整组(全文)并留到任务结束;
 * 顺序固定,展开只改从那一组起的部分;开局按上一轮用过的组、带着的工作区文件预先展开。
 */
class ToolGroupsTest {
    private var seq = 0
    private fun call(name: String, args: String = "{}") = Part.ToolCall("g${++seq}", name, args)
    private fun all() = toolDispatcher(FakeScreen(), web = FakeWebPort(), sandbox = FakeSandbox(), workspace = { "w" })
    private fun names(specs: List<ToolSpec>) = specs.map { it.name }

    /** 只聊天时发给模型的:通用工具(接管、报障碍、回看画面不在内)加五个入口。 */
    private val chatOnly = listOf(
        "wait", "ask_user", "request_confirmation", "end_not_done",
        "phone_list_apps", "phone_launch_app", "phone_set_alarm", "phone_set_timer", "phone_add_calendar_event",
        "web_navigate", "web_read_page", "run_command",
    )

    @Test fun chatOnlyGetsBaseAndEntries() {
        val d = all()
        assertEquals(chatOnly, names(d.offeredNow()))
        assertEquals(emptySet<String>(), d.expanded)
        // 入口收着时用短说明,参数和全文一样
        val now = d.offeredNow().associateBy { it.name }
        val full = d.definitions().associateBy { it.name }
        for (e in listOf("phone_launch_app", "phone_list_apps", "phone_set_alarm", "phone_set_timer", "phone_add_calendar_event", "web_navigate", "web_read_page", "run_command")) {
            assertEquals(Prompts.entry(e), now.getValue(e).description)
            assertEquals(full.getValue(e).parametersJsonSchema, now.getValue(e).parametersJsonSchema)
            assertTrue(e, now.getValue(e).description.length < full.getValue(e).description.length)
        }
        // 全部工具(每组都展开)一个不少
        assertTrue(names(d.definitions()).containsAll(listOf("phone_tap", "web_tap", "read_file", "hand_to_user", "handle_obstacle", "view_past_screens")))
    }

    @Test fun entryCallExpandsItsGroup() = runTest {
        val d = all()
        val r = d.execute(call("phone_launch_app", """{"package":"com.x"}""")).content
        assertTrue("phone" in r["moreTools"]!!.jsonPrimitive.content)
        assertEquals(setOf("phone"), d.expanded)
        val now = d.offeredNow()
        // 整组和有屏时才有的通用工具都来了,入口换回全文
        assertTrue(names(now).containsAll(listOf("phone_observe", "phone_tap", "hand_to_user", "handle_obstacle", "view_past_screens")))
        assertEquals(d.definitions().single { it.name == "phone_launch_app" }, now.single { it.name == "phone_launch_app" })
        // 网页、沙箱还收着
        assertFalse("web_tap" in names(now))
        assertEquals(Prompts.entry("web_navigate"), now.single { it.name == "web_navigate" }.description)
        // 第二次调用不再说
        assertNull(d.execute(call("phone_launch_app", """{"package":"com.x"}""")).content["moreTools"])
        assertEquals(setOf("phone"), d.used)
    }

    @Test fun readersAloneDoNotExpand() = runTest {
        val d = all()
        d.execute(call("phone_list_apps"))
        // 设闹钟、计时器单用就够:不展开手机一组
        d.execute(call("phone_set_alarm", """{"hour":9,"minute":40}"""))
        d.execute(call("phone_set_timer", """{"seconds":600}"""))
        d.execute(call("web_read_page", """{"url":"https://example.com"}"""))
        assertEquals(emptySet<String>(), d.expanded)
        assertEquals(emptySet<String>(), d.used)
        assertEquals(chatOnly, names(d.offeredNow()))
    }

    @Test fun collapsedToolCalledFromMemoryRunsAndExpands() = runTest {
        val d = all()
        // 模型没看到 phone_observe(手机一组收着),凭记忆调了:照常执行,手机一组展开
        val r = d.execute(call("phone_observe")).content
        assertEquals("true", r["ok"]!!.jsonPrimitive.content)
        assertTrue(r["moreTools"] != null)
        assertEquals(setOf("phone"), d.expanded)
        // 参数不对也展开:下一次看得到全文再改
        val w = d.execute(call("web_tap", """{"bogus":1}""")).content
        assertEquals("false", w["ok"]!!.jsonPrimitive.content)
        assertTrue(w["moreTools"] != null)
        assertEquals(setOf("phone", "web"), d.expanded)
    }

    @Test fun orderIsFixedAndExpandingOnlyChangesFromItsGroup() = runTest {
        val d = all()
        val collapsed = d.offeredNow()
        d.execute(call("web_navigate", """{"url":"https://example.com"}"""))
        val web = d.offeredNow()
        d.execute(call("phone_launch_app", """{"package":"com.x"}"""))
        val both = d.offeredNow()
        d.execute(call("run_command", """{"command":"ls"}"""))
        val everything = d.offeredNow()
        // 一直都在的那几个始终在最前面、逐字不变
        val base = collapsed.takeWhile { it.name !in setOf("phone_list_apps", "phone_launch_app") }
        for (l in listOf(web, both, everything)) assertEquals(base, l.take(base.size))
        // 先开网页再开手机,和反过来一样:顺序只看组,不看展开的先后
        assertEquals(both, toolDispatcher(FakeScreen(), web = FakeWebPort(), sandbox = FakeSandbox(), workspace = { "w" }).also {
            it.expand("phone"); it.expand("web")
        }.offeredNow())
        // 展开沙箱(排在最后)只在末尾改:前面的手机、网页两组逐字不变
        val runCommandAt = both.indexOfFirst { it.name == "run_command" }
        assertEquals(both.take(runCommandAt), everything.take(runCommandAt))
        // 每组都展开了:就是全部的工具(顺序按组)
        assertEquals(d.definitions().map { it.name }.toSet(), names(everything).toSet())
        assertEquals(everything, d.offeredNow(all = true))
        // 有屏的通用工具一组排在一直都在的后面、手机一组前面
        val i = names(everything)
        assertTrue(i.indexOf("end_not_done") < i.indexOf("hand_to_user") && i.indexOf("view_past_screens") < i.indexOf("phone_observe"))
    }

    @Test fun batchSpecGoesWithScreenGroups() {
        val d = all()
        val batch = ToolSpec("do_steps", "d", "{}")
        assertFalse("do_steps" in names(d.offeredNow(listOf(batch))))
        d.expand("sandbox")
        assertFalse("do_steps" in names(d.offeredNow(listOf(batch))))
        d.expand("web")
        val now = names(d.offeredNow(listOf(batch)))
        // 排在有屏的通用工具一组的末尾,后面就是手机一组(收着,只有入口)
        assertEquals(now.indexOf("handle_obstacle") + 1, now.indexOf("do_steps"))
        assertEquals("phone_list_apps", now[now.indexOf("do_steps") + 1])
    }

    @Test fun preloadRules() {
        // 上一轮用过的组
        assertEquals(listOf("phone", "web"), all().preload("再看一下", listOf("web", "phone")))
        // 带着工作区里的文件(附件一行写着 /workspace/ 下的路径)
        assertEquals(listOf("sandbox"), all().preload("总结一下\n附件:/workspace/inbox/a.pdf(12 KB)", emptyList()))
        // 设备不支持沙箱:不展开
        val none = toolDispatcher(FakeScreen(), sandbox = FakeSandbox(SandboxStatus.Unsupported("x86")), workspace = { "w" })
        assertEquals(emptyList<String>(), none.preload("附件:/workspace/inbox/a.pdf", listOf("sandbox")))
        // 本任务没有的组不展开;什么都没有就都收着
        assertEquals(emptyList<String>(), toolDispatcher(FakeScreen()).preload("看看", listOf("web")))
        assertEquals(emptyList<String>(), all().preload("1+1 等于几", emptyList()))
    }

    @Test fun loopPreloadsFromPreviousTurnAndRecordsUsedGroups() = runTest {
        val m = fakeModel("main") { call("phone_launch_app", "package" to "com.x"); answer("好了") }
        val loop = AgentLoop(m, all(), SystemPrompt.identity())
        loop.run("打开看看")
        assertEquals(setOf("phone"), loop.usedGroups)
        // 下一轮带着「上一轮用过手机」:开局第一次请求就有整组手机工具和手机一段
        val m2 = fakeModel("main") { answer("好") }
        AgentLoop(m2, all(), SystemPrompt.identity()).run("再看一下", listOf(Turn("打开看看", "好了", "succeeded", groups = loop.usedGroups.toList())))
        val first = m2.requests.first()
        assertTrue("phone_tap" in first.tools.map { it.name })
        assertFalse("web_tap" in first.tools.map { it.name })
        assertTrue(SystemPrompt.read("phone").lineSequence().first() in first.system)
    }

    @Test fun childAndScheduledRunsUseTheSameLayers() = runTest {
        // 子任务只有网页和(只读的)沙箱:网页一样只给入口,用到才展开;只读的沙箱没有入口(跑命令不给),那一组一开始就展开
        val child = toolDispatcher(FakeScreen(), web = FakeWebPort(), sandbox = FakeSandbox(), workspace = { "w" },
            runSpec = RunSpec(RunId("c"), "查", "t", provenance = RunProvenance(RunOrigin.Child, Attendance.Unattended)))
        val start = names(child.offeredNow())
        assertEquals(listOf("web_navigate", "web_read_page"), start.filter { it.startsWith("web_") || it.startsWith("phone_") })
        assertTrue("read_file" in start && "search_files" in start && "run_command" !in start)
        assertEquals(setOf("sandbox"), child.expanded)
        child.execute(call("web_navigate", """{"url":"https://example.com"}"""))
        assertEquals(setOf("sandbox", "web"), child.expanded)
        assertTrue("web_observe" in names(child.offeredNow()))
        // 定时触发的任务(没人在场):接管不给,其余同样分层
        val scheduled = toolDispatcher(FakeScreen(), web = FakeWebPort(), runSpec = RunSpec(RunId("s"), "查", "t",
            provenance = RunProvenance(RunOrigin.Trigger, Attendance.Unattended)))
        assertFalse("phone_observe" in names(scheduled.offeredNow()))
        scheduled.execute(call("phone_launch_app", """{"package":"com.x"}"""))
        val now = names(scheduled.offeredNow())
        assertTrue("phone_observe" in now && "handle_obstacle" in now && "hand_to_user" !in now)
    }
}
