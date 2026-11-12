package com.wangzan101.mika.core.eval

import com.wangzan101.mika.core.assembly.legacyNames
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.testing.RECORDED_TOOLS
import com.wangzan101.mika.core.testing.TOOLS
import com.wangzan101.mika.core.verify.RecordedVerifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 改名前记下的运行(如调优组那一晚的记录)按旧名对照表读:评测从记录里取的事实、核查当时的证据,
 * 和同一次运行用现在的名字记下时一模一样,重新打分结果不变。
 */
class RecordedRunNamesTest {
    private var t = 0L
    private fun screen(step: Int, app: String?, vararg names: String) = com.wangzan101.mika.core.testing.legacyScreenText(++t, step, app, names.toList())
    private fun reply(step: Int, tool: String, args: String = "{}") = RunEvent.ModelReply(++t, step, "", tool, args, null, null, null)
    private fun result(step: Int, tool: String, json: String) = RunEvent.ToolResult(++t, step, tool, json, null, null)

    /** 一次手机加网页、登录接管后交卷的运行,用现在的名字记下。 */
    private val current: List<RunEvent> = listOf(
        reply(1, "phone_launch_app", """{"package":"com.example.notes"}"""), result(1, "phone_launch_app", """{"ok":true}"""),
        screen(2, "com.example.notes", "新建", "搜索"), reply(2, "phone_type_text", """{"text":"买牛奶"}"""), result(2, "phone_type_text", """{"ok":true}"""),
        reply(3, "web_navigate", """{"url":"https://weather.example.com/"}"""), result(3, "web_navigate", """{"ok":true,"url":"https://weather.example.com/","title":"天气"}"""),
        reply(4, "web_read_page"), result(4, "web_read_page", """{"ok":true,"text":"北京 晴 25℃"}"""),
        screen(5, "网站 weather.example.com", "欢迎登录", "请输入密码", "登录"), reply(5, "handle_obstacle", """{"kind":"login"}"""),
        RunEvent.Barrier(++t, "login"), reply(6, "hand_to_user", """{"reason":"login","message":"请登录"}"""),
        RunEvent.State(++t, "waiting_takeover", "请登录"),
        screen(7, "网站 weather.example.com", "北京 晴 25℃"), reply(8, "finish", """{"answer":"北京晴 25℃\n依据:网页正文"}"""),
        RunEvent.Verifier(++t, "pass", "{}", step = 8),
    )

    /** 同一次运行在改名前记下的样子:每个改过名的工具换回它的旧名。 */
    private val old: List<RunEvent> = run {
        val legacy = legacyNames()
        val former = legacy.names.flatMap { o -> legacy.replacements(o).map { it to o } }.toMap()
        fun back(n: String) = former[n] ?: n
        current.map { e ->
            when (e) {
                is RunEvent.ModelReply -> e.copy(tool = e.tool?.let(::back))
                is RunEvent.ToolResult -> e.copy(tool = back(e.tool))
                else -> e
            }
        }
    }

    private fun facts(events: List<RunEvent>, tools: com.wangzan101.mika.core.tool.ToolIndex) = listOf(
        RunFacts.ledger(events, tools).all, RunFacts.appsSeen(events, tools), RunFacts.appsLaunched(events, tools),
        RunFacts.appsWrittenIn(events, tools), RunFacts.takeoverBarrier(events, tools),
    )

    @Test fun oldNamesRescoreLikeTheCurrentOnes() {
        assertNotEquals("the fixture must really use old names", current.map { (it as? RunEvent.ModelReply)?.tool }, old.map { (it as? RunEvent.ModelReply)?.tool })
        assertEquals(facts(current, TOOLS), facts(old, RECORDED_TOOLS))
        // 不按对照表读,旧记录取到的事实就不一样(这个测试守得住)
        assertNotEquals(facts(current, TOOLS), facts(old, TOOLS))
        // 核查当时的证据也一样
        val (v, f) = RecordedVerifications.pairs(current).single()
        val answer = RecordedVerifications.answer(current, f).orEmpty()
        assertEquals(RecordedVerifications.ledgerSources(current, v, f, "", answer), RecordedVerifications.ledgerSources(old, v, f, "", answer))
    }
}
