// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.knowledge.KnowledgeStore
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.cap.sandbox.FakeSandbox
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.testing.FakeChatModel
import com.wangzan101.mika.core.testing.LoopSnapshot
import com.wangzan101.mika.core.testing.Screen
import com.wangzan101.mika.core.testing.ScriptedScreen
import com.wangzan101.mika.core.testing.button
import com.wangzan101.mika.core.testing.fakeModel
import com.wangzan101.mika.core.testing.field
import com.wangzan101.mika.core.testing.label
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ScriptWebPort
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.verify.Verifier
import com.wangzan101.mika.core.cap.web.FakeWebPort
import com.wangzan101.mika.core.cap.web.PageText
import com.wangzan101.mika.core.cap.web.ScriptRun
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import com.wangzan101.mika.core.testing.WEB_SCRIPT_TOOL
import com.wangzan101.mika.core.assembly.toolDispatcher

/**
 * 用假模型驱动真的 AgentLoop 跑典型场景:断言结局,并把每次发给模型的消息序列和金样比对,
 * 重构(执行入口、工具注册表、能力接口、主循环瘦身)前后模型所见不变。
 * 金样重新生成:./gradlew :core:test --tests '*LoopScenarioTest' -PupdateSnapshots
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoopScenarioTest {
    private val bundled by lazy { KnowledgeStore(KnowledgeStore.loadBundled()) }
    private val catalog by lazy { GuideCatalog.loadBundled() }

    private fun loop(
        model: FakeChatModel, tools: ToolDispatcher, maxSteps: Int = 20,
        checker: FakeChatModel? = null,
        knowledge: KnowledgeStore? = null, skills: GuideCatalog? = null,
        installed: List<Pair<String, String>> = emptyList(), flags: SpeedFlags = SpeedFlags.OFF,
    ) = AgentLoop(
        model, tools, SystemPrompt.identity(), maxSteps, knowledge = knowledge, installedApps = { installed },
        verifier = checker?.let { Verifier(it, null) },
        flags = flags, clock = { CLOCK }, skills = skills, zone = ZoneOffset.UTC,
    )

    private fun snapshot(name: String, end: TaskState, vararg models: Pair<String, FakeChatModel>) {
        models.forEach { (role, m) -> assertEquals("$role 的剧本没有用完", 0, m.remaining) }
        LoopSnapshot.check(name, LoopSnapshot.render(end.toString(), models.toList()))
    }

    private fun FakeChatModel.lastResult(i: Int): String =
        requests[i].messages.last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().first().content

    // (a) 手机:看屏(带进度说明)→ 点 → 看屏 → 文字回答;回答里的引文读到过,不请核查模型;参考资料按前台 App 注入
    @Test fun simplePhoneTask() = runTest {
        val screen = ScriptedScreen(listOf(
            Screen(SETTINGS, listOf(label("@e1", "设置", 80), button("@e2", "蓝牙", 300), button("@e3", "WLAN", 400))),
            Screen(SETTINGS, listOf(label("@e1", "蓝牙", 80), label("@e2", "已开启", 300))),
        ))
        val main = fakeModel("main") {
            reply { thinking("先看屏"); text("看一下设置首页"); call("phone_observe") }
            call("phone_tap", "ref" to "@e2", "snapshotId" to "s1")
            call("phone_observe")
            answer("蓝牙已开启:蓝牙页显示「已开启」。")
        }
        val checker = fakeModel("checker") { }
        val end = loop(main, toolDispatcher(screen, skills = catalog), checker = checker,
            knowledge = bundled, skills = catalog, installed = listOf(SETTINGS to "设置")).run("看看蓝牙开没开")
        end as TaskState.Succeeded
        assertEquals("蓝牙已开启:蓝牙页显示「已开启」。", end.answer)
        assertTrue(end.verified)
        // 来源是读到「已开启」的那一屏(第 3 步)
        assertEquals(listOf(3), end.sources.map { it.step })
        assertEquals(listOf("tap @e2 s1"), screen.calls)
        assertTrue(checker.requests.isEmpty())
        snapshot("a_simple_phone", end, "main" to main)
    }

    // (b) 网页:打开站点后按域名注入站点知识;沙箱在
    @Test fun webTaskWithSiteKnowledge() = runTest {
        val web = FakeWebPort().apply {
            title = "知乎"
            page = pageText("https://www.zhihu.com/hot", "知乎", "热榜\n1. 今日话题")
        }
        val main = fakeModel("main") {
            call("web_navigate", "url" to "https://www.zhihu.com/hot")
            call("web_read_page")
            answer("知乎热榜第一是「今日话题」")
        }
        val tools = toolDispatcher(FakeScreen(), skills = catalog, web = web, sandbox = FakeSandbox())
        val end = loop(main, tools, knowledge = bundled, skills = catalog).run("用网页看一下知乎热榜")
        assertTrue("$end", end is TaskState.Succeeded)
        val refs = main.requests[1].messages.contextBodies(ContextKind.SiteKnowledge)
        assertEquals(1, refs.size)
        snapshot("b_web_site_knowledge", end, "main" to main)
    }

    // (c) 空回复:只提醒一次;最终回答分块到达,按顺序拼起来
    @Test fun emptyReplyIsNudged() = runTest {
        val main = fakeModel("main") {
            reply { thinking("想想") }
            call("phone_observe")
            reply { text("找到了,", "首页有「搜索」按钮") }
        }
        val end = loop(main, toolDispatcher(FakeScreen())).run("找搜索按钮")
        assertEquals("找到了,首页有「搜索」按钮", (end as TaskState.Succeeded).answer)
        assertEquals(ContextKind.Notice, ContextBuilder.kindOf(main.requests[1].messages.withoutRuntime().last()))
        snapshot("c_empty_reply_nudge", end, "main" to main)
    }

    // (d) 编号过期:端口报 ref_expired,模型重新看屏再点
    @Test fun staleRefThenReobserve() = runTest {
        val screen = ScriptedScreen(
            listOf(Screen(APP, listOf(button("@e1", "搜索", 100))), Screen(APP, listOf(label("@e1", "搜索结果", 100)))),
            results = listOf(ActionResult.Failed("ref_expired")),
        )
        val main = fakeModel("main") {
            call("phone_observe")
            call("phone_tap", "ref" to "@e1", "snapshotId" to "s1")
            call("phone_observe")
            call("phone_tap", "ref" to "@e1", "snapshotId" to "s2")
            answer("打开了搜索")
        }
        val end = loop(main, toolDispatcher(screen)).run("点搜索")
        assertTrue("$end", end is TaskState.Succeeded)
        assertTrue("ref_expired" in main.lastResult(2))
        snapshot("d_ref_expired", end, "main" to main)
    }

    // (e) 不可逆操作:被拦下 → 申请确认 → 用户不同意 → 如实放弃
    @Test fun irreversibleDeclined() = runTest {
        // 询问时只有花钱的那一步先问:提交订单被拦下,申请同意,用户不同意
        val screen = ScriptedScreen(listOf(
            Screen(APP, listOf(label("@e1", "耳机店", 40), label("@e2", "合计 ¥25", 800), button("@e3", "提交订单", 950))),
        ))
        val main = fakeModel("main") {
            call("phone_observe")
            call("phone_tap", "ref" to "@e3", "snapshotId" to "s1")
            call("request_confirmation", "action" to "order", "target" to "耳机店", "content" to "一副耳机", "amount" to "¥25", "app" to APP)
            reportFailure("用户没有同意下单")
        }
        val l = loop(main, toolDispatcher(screen))
        val run = async { l.run("买一副耳机") }
        advanceUntilIdle()
        assertTrue("${l.state.value}", l.state.value is TaskState.WaitingConfirmation)
        l.answerConfirmation(false)
        val end = run.await()
        assertEquals(TaskState.Failed("用户没有同意下单", FailKind.GaveUp), end)
        assertFalse(screen.calls.any { it.startsWith("tap @e3") })
        assertTrue("confirmation_required" in main.lastResult(2))
        assertTrue("confirmation_declined" in main.lastResult(3))
        snapshot("e_irreversible_declined", end, "main" to main)
    }

    // (f) 一次多步(do_steps):整批算一步,子步逐个执行
    @Test fun batchSteps() = runTest {
        val screen = ScriptedScreen(listOf(
            Screen(APP, listOf(field("@e1", "搜索商品", 100), button("@e2", "搜索", 200))),
            Screen(APP, listOf(field("@e1", "咖啡", 100, hint = false), button("@e2", "搜索", 200))),
            Screen(APP, listOf(label("@e1", "咖啡豆 ¥89", 300))),
        ))
        val steps = listOf(
            mapOf("tool" to "phone_type_text", "args" to mapOf("text" to "咖啡", "ref" to "@e1", "snapshotId" to "s1")),
            mapOf("tool" to "phone_tap", "args" to mapOf("ref" to "@e2", "snapshotId" to "s1")),
        )
        val main = fakeModel("main") {
            call("phone_observe")
            call(DO_STEPS_TOOL, "steps" to steps)
            // 批做完不带回这一屏(不看图的模型):读过才能说价格
            call("phone_observe")
            answer("咖啡豆 ¥89")
        }
        val end = loop(main, toolDispatcher(screen), flags = SpeedFlags(batchSteps = true)).run("搜咖啡看价格")
        assertTrue("$end", end is TaskState.Succeeded)
        assertEquals(listOf("type @e1 咖啡", "tap @e2 s1"), screen.calls)
        snapshot("f_batch_steps", end, "main" to main)
    }

    // (g) 页面脚本:普通子步执行,不可逆的子步被拦下不执行
    @Test fun browserScriptWithGatedSubAction() = runTest {
        val web = FakeWebPort().apply {
            elements = listOf(button("@e1", "搜索", 100).copy(resourceId = "#go"), button("@e2", "提交订单", 300).copy(resourceId = "#order"))
        }
        val port = ScriptWebPort(web) { b ->
            b.call("snapshot", JsonObject(emptyMap()))
            b.call("tap", buildJsonObject { put("ref", "@e1") })
            b.call("snapshot", JsonObject(emptyMap()))
            b.call("tap", buildJsonObject { put("ref", "@e2") })
            ScriptRun.Done("\"unreachable\"")
        }
        val main = fakeModel("main") {
            call("web_navigate", "url" to "https://shop.example.com/")
            call(WEB_SCRIPT_TOOL, "code" to "await mika.tap('@e1'); await mika.tap('@e2')")
            reportFailure("下单需要用户确认,脚本没有执行")
        }
        val end = loop(main, toolDispatcher(FakeScreen(), web = port)).run("搜一下再下单")
        assertTrue("$end", end is TaskState.Failed)
        assertEquals(1, web.calls.count { it.startsWith("tapRef") })
        assertTrue("confirmation_required" in main.lastResult(2))
        snapshot("g_browser_script_gated", end, "main" to main)
    }

    // (h) 服务出错:限流、网络、5xx 都是一时的故障,重发同一个请求(模型看到的内容不变),接着做完
    @Test fun providerErrors() = runTest {
        val main = fakeModel("main") {
            rateLimited(retryAfterMs = 500)
            networkError()
            call("phone_observe")
            serverError(503)
            answer("找到了")
        }
        val end = loop(main, toolDispatcher(FakeScreen())).run("找搜索按钮")
        com.wangzan101.mika.core.testing.assertAnswered("找到了", end)
        assertEquals(5, main.requests.size)
        // 重发的是同一个请求
        assertEquals(main.requests[3], main.requests[4])
        snapshot("h_provider_errors", end, "main" to main)
    }

    // (i) 上下文超长:狠压缩后重发,同一步接着做
    @Test fun contextOverflowCompressesAndContinues() = runTest {
        val main = fakeModel("main") {
            for (i in 1..8) call("wait", "seconds" to 1 + i / 10.0)
            contextOverflow()
            answer("等完了")
        }
        val end = loop(main, toolDispatcher(FakeScreen())).run("等一会儿")
        assertTrue("$end", end is TaskState.Succeeded)
        assertTrue(main.requests[9].messages.size < main.requests[8].messages.size)
        snapshot("i_context_overflow", end, "main" to main)
    }

    // (j) 步数用完:最后一轮只给 end_not_done,做完了就用文字回答
    @Test fun stepBudgetExhausted() = runTest {
        val main = fakeModel("main") {
            for (i in 1..3) call("wait", "seconds" to 1 + i / 10.0)
            reportFailure("查过首页和菜单,没找到")
        }
        val end = loop(main, toolDispatcher(FakeScreen()), maxSteps = 3).run("找夜间模式")
        assertEquals(TaskState.Failed("查过首页和菜单,没找到", FailKind.GaveUp), end)
        assertEquals(listOf("end_not_done"), main.requests.last().toolNames)
        snapshot("j_step_budget", end, "main" to main)
    }

    // (k) 输出被截断(只有半截文字,不是回答,提醒一次)与参数不是合法 JSON 的调用
    @Test fun truncatedAndMalformed() = runTest {
        val main = fakeModel("main") {
            truncated("我先打开")
            malformedCall("phone_tap")
            answer("找到了")
        }
        val end = loop(main, toolDispatcher(FakeScreen())).run("找搜索按钮")
        assertTrue("$end", end is TaskState.Succeeded)
        assertTrue("target_unresolved" in main.lastResult(2))
        snapshot("k_truncated_malformed", end, "main" to main)
    }

    // (l) 回答里的价格没读到过:请核查模型 → 退回一次,点名那一处 → 仍没对上,照样交出并标成未确认
    @Test fun ungroundedValueIsNudgedOnceThenLabelled() = runTest {
        val screen = ScriptedScreen(listOf(Screen(APP, listOf(label("@e1", "咖啡豆 ¥89", 300)))))
        val main = fakeModel("main") {
            call("phone_observe")
            answer("咖啡豆 ¥79")
            answer("咖啡豆 ¥79(页面上看到的)")
        }
        val checker = fakeModel("checker") {
            verdict("fail", "屏上写的是 ¥89", "看咖啡豆的价格" to null)
            verdict("fail", "屏上写的是 ¥89", "看咖啡豆的价格" to null)
        }
        val end = loop(main, toolDispatcher(screen), checker = checker).run("看咖啡豆的价格")
        end as TaskState.Succeeded
        assertFalse(end.verified)
        assertEquals(listOf("79", "屏上写的是 ¥89"), end.unconfirmed)
        assertEquals(ContextKind.Notice, ContextBuilder.kindOf(main.requests[2].messages.withoutRuntime().last()))
        snapshot("l_unconfirmed_labelled", end, "main" to main, "verifier" to checker)
    }

    // (m) 不可逆操作:被拦下 → 申请确认 → 用户同意 → 发送 → 读到「已发送」→ 回答;做过对外的事,回答另请核查模型
    // 发送不花钱:不先问,直接发,发完读一遍核实
    @Test fun irreversibleDoneDirectlyIsVerified() = runTest {
        val screen = ScriptedScreen(listOf(
            Screen(CHAT, listOf(label("@e1", "张三", 40), field("@e2", "发消息"), button("@e3", "+", 950))),
            Screen(CHAT, listOf(label("@e1", "张三", 40), field("@e2", "明天见", hint = false), button("@e3", "发送", 950))),
            Screen(CHAT, listOf(label("@e1", "张三", 40), label("@e4", "明天见", 600), label("@e5", "已发送", 640), field("@e2", "发消息"))),
        ))
        val main = fakeModel("main") {
            call("phone_observe")
            call("phone_type_text", "text" to "明天见", "ref" to "@e2", "snapshotId" to "s1")
            call("phone_tap", "ref" to "@e3", "snapshotId" to "s1")
            call("phone_observe")
            answer("已给张三发了「明天见」,聊天里显示「已发送」。")
        }
        val checker = fakeModel("checker") { verdict("pass", "聊天里显示已发送", "给张三发明天见" to "已发送") }
        val end = loop(main, toolDispatcher(screen), checker = checker).run("给张三发明天见")
        assertTrue("$end", end is TaskState.Succeeded && end.verified)
        assertTrue(screen.calls.any { it.startsWith("tap @e3") })
        snapshot("m_irreversible_verified", end, "main" to main, "verifier" to checker)
    }

    private companion object {
        const val CLOCK = 1_761_300_000_000L
        const val SETTINGS = "com.android.settings"
        const val CHAT = "com.chat"
        const val APP = "com.x"
        fun pageText(url: String, title: String, text: String) =
            PageText(url, title, text, emptyList(), emptyList(), false, null)
    }
}
