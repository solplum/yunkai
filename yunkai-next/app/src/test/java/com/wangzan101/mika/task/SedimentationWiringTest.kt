// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.task

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.skills.PersonalSkill
import com.wangzan101.mika.core.skills.SkillDrafter
import com.wangzan101.mika.core.skills.SkillStatus
import com.wangzan101.mika.settings.MemorySwitches
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.knownTools

@OptIn(ExperimentalCoroutinesApi::class)
class SedimentationWiringTest {
    @get:Rule val texts = ChineseTexts()

    private val catalog = GuideCatalog.loadBundled()
    private val tools = setOf("phone_launch_app", "phone_observe", "phone_tap", "phone_type_text", "hand_to_user", "finish")
    private val draftMd = "---\nname: send-wechat-message\ndisplay: 发微信消息\ndescription: 给指定联系人发一条微信消息\napps: [com.tencent.mm]\nsource: learned\n---\n" +
        "## 入口\n用 `phone_launch_app` 打开。\n## 步骤\n1. 点搜索\n## 容易出错的地方\n同名群聊。\n## 怎样算办成(核实证据)\n消息出现在聊天里。\n## 什么时候停下交给用户\n要登录时。"

    private fun events(verdict: String = "pass", extra: List<RunEvent> = emptyList()): List<RunEvent> {
        var t = 0L
        val ev = mutableListOf<RunEvent>(RunEvent.TaskStarted(++t, "发微信", "m"))
        listOf("phone_launch_app", "phone_tap", "phone_tap", "phone_type_text", "phone_tap", "phone_tap").forEachIndexed { i, tool ->
            ev += RunEvent.ModelReply(++t, i + 1, "", tool, "{}", null, null, null)
            ev += RunEvent.ToolResult(++t, i + 1, tool, """{"ok":true}""", true, null)
        }
        ev += extra
        ev += RunEvent.Verifier(++t, verdict, """{"reason":"","checks":[]}""")
        ev += RunEvent.TaskEnded(++t, "succeeded", "ok")
        return ev
    }

    private fun learning(skills: MapSkills, learn: Boolean, autoApprove: Boolean = false, model: TextModel = TextModel(draftMd)) =
        RunLearning(MemorySwitches(learnSkills = learn), null, skills, GuideCatalog.withPersonal(catalog, skills), null, SkillDrafter(model), { "draft-1" }, autoApprove)

    private fun after(ev: List<RunEvent>, end: TaskState = TaskState.Succeeded("ok"), outcome: RunOutcome = RunOutcome.Done) = RunAftermath(
        "c1", "run-1", "给老妈发微信", end, emptyList(), emptyList(), emptyList(), tools, knownTools(), events = { ev }, outcome = outcome,
    )

    @Test fun draftOnlyWhenEnabledAndEligible() {
        val off = MapSkills()
        val offModel = TextModel(draftMd)
        runBlocking { learning(off, learn = false, model = offModel).learn(after(events())) }
        assertTrue(off.drafts.isEmpty() && offModel.requests.isEmpty())

        // 只从核实过的任务学:没核对过的不起草
        val notVerified = MapSkills()
        runBlocking { learning(notVerified, learn = true).learn(after(events(), end = TaskState.Succeeded("ok", verified = false, reviewed = false))) }
        assertTrue(notVerified.drafts.isEmpty())

        // 回答核实过,但判下来只办了一部分、没办成或没判成:不起草
        for (o in listOf(RunOutcome.Partial, RunOutcome.NotPossible, RunOutcome.Unknown)) {
            val notDone = MapSkills()
            val model = TextModel(draftMd)
            runBlocking { learning(notDone, learn = true, model = model).learn(after(events(), outcome = o)) }
            assertTrue(o.wire, notDone.drafts.isEmpty() && model.requests.isEmpty())
        }

        // 走的是回放路线:不再起草
        val replayed = MapSkills()
        runBlocking { learning(replayed, learn = true).learn(after(events(extra = listOf(RunEvent.Replay(50, "r", 1, 3, true, ""))))) }
        assertTrue(replayed.drafts.isEmpty())

        val on = MapSkills()
        val notes = runBlocking { learning(on, learn = true).learn(after(events())) }
        assertEquals(listOf("draft-1"), on.drafts.keys.toList())
        assertEquals("云开 学会了一项技能:给指定联系人发一条微信消息", notes.skillNotes.single().text)
        assertEquals("draft-1", notes.skillNotes.single().draftId)
    }

    @Test fun evalAutoApproveHook() {
        val skills = MapSkills()
        val notes = runBlocking { learning(skills, learn = true, autoApprove = true).learn(after(events())) }
        assertTrue(skills.drafts.isEmpty())
        assertEquals(SkillStatus.active, skills.get("send-wechat-message")!!.status)
        assertTrue(notes.skillNotes.isEmpty())
    }

    @Test fun draftingRunsAfterResultNotification() = runTest {
        val order = mutableListOf<String>()
        RunLearning.launchAfter(this, notify = { order += "notify" }) { order += "learn" }
        assertEquals(listOf("notify"), order)
        runCurrent()
        assertEquals(listOf("notify", "learn"), order)
    }

    @Test fun usedSkillOutcomeRecordedAndSuspensionNoted() {
        val skill = PersonalSkill("wechat-msg", draftMd.replace("send-wechat-message", "wechat-msg"), SkillStatus.active, "r0", null, 0, emptyList())
        val skills = MapSkills(skill)
        val used = listOf(RunEvent.SkillUsed(40, "wechat-msg", "injected"), RunEvent.SkillUsed(41, "wechat-msg", "viewed"))
        val notes = runBlocking {
            learning(skills, learn = true).learn(after(events(verdict = "fail", extra = used), TaskState.Failed("核查未通过:消息没发出去", FailKind.GaveUp)))
        }
        assertEquals(SkillStatus.suspended, skills.get("wechat-msg")!!.status)
        assertEquals(listOf("verifier_rejected"), skills.get("wechat-msg")!!.outcomes)
        assertEquals("wechat-msg", notes.skillNotes.single().suspended)
        // 照着个人技能做成的:只记结果,不起草
        val ok = MapSkills(skill)
        runBlocking { learning(ok, learn = true).learn(after(events(extra = used))) }
        assertEquals(listOf("verified"), ok.get("wechat-msg")!!.outcomes)
        assertTrue(ok.drafts.isEmpty())
        // 回答如实说只办了一部分:照着技能做没办成,记 failed
        val partial = MapSkills(skill)
        runBlocking { learning(partial, learn = true).learn(after(events(extra = used), outcome = RunOutcome.Partial)) }
        assertEquals(listOf("failed"), partial.get("wechat-msg")!!.outcomes)
        // 取消的不记
        val cancelled = MapSkills(skill)
        runBlocking { learning(cancelled, learn = false).learn(after(events(extra = used), TaskState.Cancelled)) }
        assertNull(cancelled.get("wechat-msg")!!.outcomes.firstOrNull())
    }

    @Test fun injectedOnlySkillNeedsTwoFailuresInARow() {
        val skill = PersonalSkill("wechat-msg", draftMd.replace("send-wechat-message", "wechat-msg"), SkillStatus.active, "r0", null, 0, emptyList())
        val skills = MapSkills(skill)
        val used = listOf(RunEvent.SkillUsed(40, "wechat-msg", "injected"))
        val rejected = TaskState.Failed("核查未通过:没发出去", FailKind.GaveUp)
        runBlocking { learning(skills, learn = false).learn(after(events(verdict = "fail", extra = used), rejected)) }
        assertEquals(SkillStatus.active, skills.get("wechat-msg")!!.status)
        val notes = runBlocking { learning(skills, learn = false).learn(after(events(verdict = "fail", extra = used), rejected)) }
        assertEquals(SkillStatus.suspended, skills.get("wechat-msg")!!.status)
        assertEquals("wechat-msg", notes.skillNotes.single().suspended)
    }
}
