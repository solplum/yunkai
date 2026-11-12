package com.wangzan101.mika.core.skills

import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.record.RunEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.TOOLS
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.assembly.knownToolNames
import com.wangzan101.mika.core.testing.SANDBOX_TOOLS
import com.wangzan101.mika.core.testing.WEB_TOOLS

class SkillDrafterTest {
    private val goal = "给『老妈』发『明天回家吃饭』"
    private val answer = "已给老妈发了「明天回家吃饭」,聊天里显示已发送"
    private val tools = setOf("phone_launch_app", "phone_observe", "phone_tap", "phone_type_text", "phone_back", "finish", "hand_to_user")

    private val observeJson = """{"ok":true,"currentApp":"com.tencent.mm","snapshotId":"s1","elements":[""" +
        """{"ref":"@e1","role":"button","name":"搜索","center":{"x":1,"y":1}},""" +
        """{"ref":"@e2","role":"text","name":"张三:晚上一起吃饭吗","center":{"x":1,"y":1}},""" +
        """{"ref":"@e3","role":"item","name":"老妈","center":{"x":1,"y":1}}]}"""

    private fun runEvents(
        verdict: String = "pass",
        outcome: String = "succeeded",
        actions: Int = 6,
        extra: List<RunEvent> = emptyList(),
    ): List<RunEvent> {
        var t = 0L
        val ev = mutableListOf<RunEvent>(RunEvent.TaskStarted(++t, goal, "m"))
        fun act(step: Int, tool: String, args: String, changed: Boolean? = true, result: String = """{"ok":true}""") {
            ev += RunEvent.ModelReply(++t, step, "", tool, args, null, null, null)
            ev += RunEvent.ToolResult(++t, step, tool, result, changed, null)
        }
        act(1, "phone_launch_app", """{"package":"com.tencent.mm"}""")
        ev += RunEvent.ModelReply(++t, 2, "", "phone_observe", "{}", null, null, null)
        ev += RunEvent.ToolResult(++t, 2, "phone_observe", observeJson, null, 1)
        ev += com.wangzan101.mika.core.testing.legacyScreenText(++t, 2, "com.tencent.mm", listOf("搜索", "张三:晚上一起吃饭吗", "老妈"))
        val script = listOf(
            Triple("phone_tap", """{"ref":"@e1","snapshotId":"s1"}""", true),
            Triple("phone_type_text", """{"text":"老妈","ref":"@e1","snapshotId":"s1"}""", true),
            Triple("phone_tap", """{"ref":"@e3","snapshotId":"s1"}""", true),
            Triple("phone_type_text", """{"text":"明天回家吃饭"}""", true),
            Triple("phone_tap", """{"x":900,"y":950}""", false),
        )
        script.take(actions - 1).forEachIndexed { i, (tool, args, changed) -> act(i + 3, tool, args, changed) }
        ev += extra
        ev += RunEvent.Verifier(++t, verdict, """{"reason":"","checks":[{"criterion":"c","status":"met","quote":"明天回家吃饭"}]}""")
        ev += RunEvent.TaskEnded(++t, outcome, "done")
        return ev
    }

    private fun md(
        name: String = "send-wechat-message",
        description: String = "给指定联系人发一条微信消息",
        apps: String = "com.tencent.mm",
        body: String = "",
        drop: String? = null,
    ): String {
        val sections = listOf(
            "## 入口" to "用 `phone_launch_app` 打开微信。",
            "## 步骤" to "1. 点搜索,用 `phone_type_text` 输入 老妈\n2. 输入 明天回家吃饭 后点发送$body",
            "## 容易出错的地方" to "搜索结果里可能有同名群聊。",
            "## 怎样算办成(核实证据)" to "聊天里出现刚发的消息,没有红色感叹号。",
            "## 什么时候停下交给用户" to "需要登录时用 `hand_to_user`。",
        ).filter { it.first != drop }
        return "---\nname: $name\ndisplay: 发微信消息\ndescription: $description\napps: [$apps]\nsource: learned\nlearned_from: r1\n---\n" +
            sections.joinToString("\n") { "${it.first}\n${it.second}" }
    }

    private fun text(s: String) = ModelReply(listOf(Part.Text(s)), StopReason.EndTurn, null)
    private val bundled = GuideCatalog.loadBundled()

    private fun draft(reply: Any, existing: PersonalSkills = InMemoryPersonalSkills(), model: FakeModel = FakeModel(reply)): DraftResult =
        runBlocking {
            SkillDrafter(model).draft(DraftInput("r1", goal, answer, emptyList(), runEvents(), tools, TOOLS), existing, GuideCatalog.withPersonal(bundled, existing))
        }

    @Test fun eligibilityRules() {
        assertNull(Eligibility.check(runEvents(), null, TOOLS, verified = true))
        assertEquals("not_verified", Eligibility.check(runEvents(), null, TOOLS, verified = false))
        assertEquals("not_succeeded", Eligibility.check(runEvents(outcome = "failed"), null, TOOLS, verified = true))
        assertEquals("too_few_actions", Eligibility.check(runEvents(actions = 4), null, TOOLS, verified = true))
        assertEquals("takeover_skipped", Eligibility.check(runEvents(extra = listOf(RunEvent.Takeover(50, "login", "skipped", 10, 0))), null, TOOLS, verified = true))
        assertNull(Eligibility.check(runEvents(extra = listOf(RunEvent.Takeover(50, "login", "complete", 10, 3))), null, TOOLS, verified = true))
        assertEquals("confirmation_declined", Eligibility.check(runEvents(extra = listOf(RunEvent.Confirmation(50, "send", null, null, null, "declined"))), null, TOOLS, verified = true))
        assertNull(Eligibility.check(runEvents(extra = listOf(RunEvent.Confirmation(50, "send", null, null, null, "approved"))), null, TOOLS, verified = true))
        assertEquals("followed_personal_skill", Eligibility.check(runEvents(), "send-wechat-message", TOOLS, verified = true))
        assertEquals("takeover_user_finished", Eligibility.check(runEvents(extra = listOf(RunEvent.Takeover(50, "payment", "user_finished", 10, 4))), null, TOOLS, verified = true))
        // 出错的动作不算步数
        val withErrors = runEvents().map { e ->
            if (e is RunEvent.ToolResult && e.tool == "phone_tap") e.copy(resultJson = """{"ok":false,"error":"ref_expired"}""") else e
        }
        assertEquals("too_few_actions", Eligibility.check(withErrors, null, TOOLS, verified = true))
    }

    @Test fun digestOmitsTypedTextAndScreens() {
        val lines = ActionDigest.of(runEvents(), TOOLS)
        assertEquals(listOf("phone_launch_app", "phone_tap", "phone_type_text", "phone_tap", "phone_type_text", "phone_tap"), lines.map { it.tool })
        assertEquals(listOf("com.tencent.mm", "搜索", "搜索", "老妈", null, null), lines.map { it.target })
        assertTrue(lines.all { it.where == "com.tencent.mm" })
        assertEquals(false, lines.last().changed)
        val text = ActionDigest.render(lines)
        assertTrue(text.contains("<输入内容>"))
        assertFalse(text.contains("明天回家吃饭"))
        assertFalse(text.contains("张三"))
        assertEquals(2, ActionDigest.of(runEvents(), TOOLS, maxLines = 2).size)
        val failed = ActionDigest.of(listOf(
            RunEvent.ModelReply(1, 1, "", "phone_tap", """{"x":1,"y":2}""", null, null, null),
            RunEvent.ToolResult(2, 1, "phone_tap", """{"ok":false,"error":"ref_expired"}""", null, null),
        ), TOOLS)
        assertEquals("ref_expired", failed.single().error)
    }

    @Test fun goalLiteralsRedacted() {
        val d = draft(text(md())) as DraftResult.Draft
        assertFalse(d.skill.markdown.contains("老妈"))
        assertFalse(d.skill.markdown.contains("明天回家吃饭"))
        assertTrue(d.skill.markdown.contains("输入 <参数>\n2. 输入 <参数> 后点发送"))
        assertEquals(SkillStatus.pending, d.skill.status)
        assertEquals("r1", d.skill.learnedFrom)
        assertNull(d.isUpdateOf)
    }

    @Test fun requestHasNoScreenText() {
        val model = FakeModel(text(md()))
        draft(text(md()), model = model)
        val request = model.requests.single().flatMap { it.parts }.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
        assertFalse(request.contains("张三"))
        assertFalse(request.contains("晚上一起吃饭"))
        assertFalse(request.contains("老妈"))
        assertFalse(request.contains("明天回家吃饭"))
        assertTrue(request.contains("目标:给『<参数>』发『<参数>』"))
        assertTrue(request.contains("<输入内容>"))
        assertTrue(request.contains("tap 「搜索」"))
        assertTrue(model.offeredTools.single().isEmpty())
    }

    @Test fun evidenceAndTypedValuesNeverInDraft() {
        val ev = runEvents()
        val model = FakeModel(text(md(body = "\n3. 核对 王小明 订单20241023001已提交,联系人 王小明,单号 20241023001,共 35元")))
        val d = runBlocking { SkillDrafter(model).draft(DraftInput("r1", goal, "王小明 订单20241023001已提交", emptyList(), ev, tools, TOOLS), InMemoryPersonalSkills(), bundled) } as DraftResult.Draft
        assertFalse(d.skill.markdown.contains("王小明"))
        assertFalse(d.skill.markdown.contains("20241023001"))
        assertFalse(d.skill.markdown.contains("35"))
        val request = model.requests.single().flatMap { it.parts }.filterIsInstance<Part.Text>().joinToString { it.text }
        assertFalse(request.contains("20241023001"))
        assertTrue(d.skill.markdown.contains("learned_from: r1"))
    }

    @Test fun partialScreenTextLeakDropsDraft() {
        val ev = runEvents()
        val model = FakeModel(text(md(body = "\n3. 确认是王小明先生")))
        val r = runBlocking { SkillDrafter(model).draft(DraftInput("r1", goal, "收件人王小明先生的地址", emptyList(), ev, tools, TOOLS), InMemoryPersonalSkills(), bundled) }
        assertEquals(DraftResult.Dropped("screen_text_leak"), r)
    }

    @Test fun similarPendingDraftDropsAndSuspendedBecomesUpdate() {
        val pendingStore = InMemoryPersonalSkills().apply { putPending("d0", personalSkill("wechat-msg", display = "微信消息", extraMeta = "apps: [com.tencent.mm]\n")) }
        assertEquals(DraftResult.Dropped("similar_draft_pending"), draft(text(md()), existing = pendingStore))
        val suspended = personalSkill("wechat-msg", display = "微信消息", status = SkillStatus.suspended, extraMeta = "apps: [com.tencent.mm]\n")
        val d = draft(text(md()), existing = InMemoryPersonalSkills(suspended)) as DraftResult.Draft
        assertEquals("wechat-msg", d.isUpdateOf)
    }

    @Test fun approveRewritesName() {
        val store = InMemoryPersonalSkills(personalSkill("wechat-msg"))
        store.putPending("d1", personalSkill("send-wechat-message").copy(replacesDraftOf = "wechat-msg"))
        val got = store.approve("d1", editedMarkdown = "---\nname: renamed-by-user\ndisplay: x\n---\n## 入口\n改过")!!
        assertTrue(got.markdown.startsWith("---\nname: wechat-msg\n"))
    }

    @Test fun sensitiveAfterRedactionDropsDraft() {
        assertEquals(DraftResult.Dropped("sensitive"), draft(text(md(body = "\n3. 支付密码是 778899"))))
    }

    @Test fun missingSectionDropped() {
        assertEquals(DraftResult.Dropped("missing_section"), draft(text(md(drop = "## 容易出错的地方"))))
    }

    @Test fun unknownToolNameDropped() {
        assertEquals(DraftResult.Dropped("unknown_tool"), draft(text(md(body = "\n3. 用 `open_chat` 打开聊天"))))
    }

    // 沙箱技能脚本:只有清单里标为可运行的才能写进草稿
    @Test fun runScriptOnlyForRunnableManifestEntries() {
        val runnable = bundled.runnableScripts()
        assertTrue("skills/documents/scripts/extract_pdf.py" in runnable)
        assertFalse(runnable.any { it.endsWith("scripts/fetch_transcript.py") })
        val withSandbox = tools + "run_bundled_script"
        fun check(body: String) = SkillDrafter.validate(md(body = body), withSandbox, TOOLS.nameOf(Verb.RunBundledScript), runnable)
        assertNull(check("\n3. 用 `run_bundled_script` 跑 skills/documents/scripts/extract_pdf.py 取 PDF 文字"))
        assertNull(check("\n3. 用 `run_bundled_script` 跑 scripts/extract_pdf.py"))
        assertEquals("unrunnable_script", check("\n3. 用 `run_bundled_script` 跑 sites/youtube.com/scripts/fetch_transcript.py"))
        assertEquals("unrunnable_script", check("\n3. 用 `run_bundled_script` 跑 scripts/palettes.py 和 scripts/extract_marker.py"))
        assertEquals("unrunnable_script", check("\n3. 用 `run_bundled_script` 跑一个脚本"))
        // 起草路径用目录里的清单
        val events = runEvents()
        val r = runBlocking {
            SkillDrafter(FakeModel(text(md(body = "\n3. 用 `run_bundled_script` 跑 sites/youtube.com/scripts/fetch_transcript.py"))))
                .draft(DraftInput("r1", goal, answer, emptyList(), events, withSandbox, TOOLS), InMemoryPersonalSkills(), bundled)
        }
        assertEquals(DraftResult.Dropped("unrunnable_script"), r)
    }

    // 网页任务:网页工具名算已知工具;网址里的查询词和读到的网页正文都不进起草请求
    @Test fun webRunDraftKeepsUrlsAndPageTextOut() {
        val known = knownToolNames()
        assertTrue(known.containsAll(WEB_TOOLS))
        assertTrue(known.containsAll(SANDBOX_TOOLS))
        val web = listOf(
            RunEvent.ModelReply(90, 1, "", "web_navigate", """{"url":"https://s.example.com/search?q=13800001111"}""", null, null, null),
            RunEvent.ToolResult(91, 1, "web_navigate", """{"ok":true,"surface":"web"}""", true, null),
            RunEvent.ModelReply(92, 2, "", "web_read_page", "{}", null, null, null),
            RunEvent.ToolResult(93, 2, "web_read_page", """{"ok":true,"text":"张三的手机号 13912345678"}""", null, null),
        )
        val model = FakeModel(text(md(body = "\n3. 用 `web_navigate` 打开搜索页")))
        val r = runBlocking {
            SkillDrafter(model).draft(DraftInput("r1", goal, answer, emptyList(), runEvents(extra = web), tools + WEB_TOOLS, TOOLS),
                InMemoryPersonalSkills(), bundled)
        }
        assertTrue(r is DraftResult.Draft)
        val sent = model.requests.single().toString()
        for (secret in listOf("13800001111", "13912345678", "张三的手机号")) assertFalse(secret, sent.contains(secret))
    }

    @Test fun tooLongDropped() {
        assertEquals(DraftResult.Dropped("too_long"), draft(text(md(body = "\n" + "很".repeat(4000)))))
    }

    @Test fun invalidOrTakenNameDropped() {
        assertEquals(DraftResult.Dropped("invalid_name"), draft(text(md(name = "Send_Wechat"))))
        assertEquals(DraftResult.Dropped("name_taken"), draft(text(md(name = "wechat")))) // 与内置同名
    }

    @Test fun similarPersonalSkillBecomesUpdate() {
        val mine = personalSkill("wechat-msg", display = "微信消息", extraMeta = "apps: [com.tencent.mm]\n")
        val d = draft(text("```markdown\n" + md() + "\n```"), existing = InMemoryPersonalSkills(mine)) as DraftResult.Draft
        assertEquals("wechat-msg", d.isUpdateOf)
        assertEquals("wechat-msg", d.skill.name)
        assertEquals("wechat-msg", d.skill.replacesDraftOf)
        assertTrue(d.skill.markdown.startsWith("---\nname: wechat-msg\n"))
        // 用的 App 不同:不算同一份,另起一份
        val other = personalSkill("wechat-msg", display = "微信消息", extraMeta = "apps: [com.other.app]\n")
        val d2 = draft(text(md()), existing = InMemoryPersonalSkills(other)) as DraftResult.Draft
        assertNull(d2.isUpdateOf)
        assertEquals("send-wechat-message", d2.skill.name)
    }

    @Test fun modelErrorDropped() {
        assertEquals(DraftResult.Dropped("model_error"), draft(ModelError.Auth()))
    }

    /** 网页上的动作记在「网站 主机名」下,不沿用之前的 App;网页上点到的名字算网页内容,草稿里出现就丢。 */
    @Test fun webActionsTrackTheSiteAndPageNamesAreLeakSources() {
        val pageObserve = """{"ok":true,"currentSite":"shop.example.com","snapshotId":"w1","elements":[""" +
            """{"ref":"@e1","role":"link","name":"李四的订单 2024-001","center":{"x":1,"y":1}}]}"""
        val web = listOf(
            RunEvent.ModelReply(90, 10, "", "web_navigate", """{"url":"https://shop.example.com/orders?u=lisi"}""", null, null, null),
            RunEvent.ToolResult(91, 10, "web_navigate", """{"ok":true,"currentSite":"shop.example.com"}""", true, null),
            RunEvent.ModelReply(92, 11, "", "phone_observe", "{}", null, null, null),
            RunEvent.ToolResult(93, 11, "phone_observe", pageObserve, null, null),
            RunEvent.ModelReply(94, 12, "", "phone_tap", """{"ref":"@e1","snapshotId":"w1"}""", null, null, null),
            RunEvent.ToolResult(95, 12, "phone_tap", """{"ok":true}""", true, null),
            RunEvent.ModelReply(96, 13, "", "phone_launch_app", """{"package":"com.tencent.mm"}""", null, null, null),
            RunEvent.ToolResult(97, 13, "phone_launch_app", """{"ok":true}""", true, null),
        )
        val lines = ActionDigest.of(runEvents(extra = web), TOOLS)
        val nav = lines.single { it.tool == "web_navigate" }
        assertEquals("shop.example.com", nav.target) // 只记站点,不记查询串
        assertEquals("网站 shop.example.com", nav.where)
        val tapOnPage = lines.single { it.step == 12 }
        assertEquals("网站 shop.example.com", tapOnPage.where)
        assertEquals("李四的订单 2024-001", tapOnPage.target)
        assertTrue(tapOnPage.fromPage)
        // 回到手机:又记在 App 下
        assertEquals("com.tencent.mm", lines.single { it.step == 13 }.where)
        assertFalse(lines.first { it.tool == "phone_tap" }.fromPage)

        val leaking = FakeModel(text(md(body = "\n3. 打开 李四的订单 看详情")))
        val r = runBlocking {
            SkillDrafter(leaking).draft(DraftInput("r1", goal, answer, emptyList(), runEvents(extra = web), tools + WEB_TOOLS, TOOLS),
                InMemoryPersonalSkills(), bundled)
        }
        // 网页上的名字和输入的文字一样被换掉(或整份丢弃),不会原样进草稿
        if (r is DraftResult.Draft) assertFalse(r.skill.markdown, r.skill.markdown.contains("李四")) else assertEquals(DraftResult.Dropped("screen_text_leak"), r)
    }
}
