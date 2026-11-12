package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.memory.MemoryItem
import com.wangzan101.mika.core.memory.MemoryPersistence
import com.wangzan101.mika.core.memory.MemoryStore
import com.wangzan101.mika.core.memory.UserUtterances
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.cap.sandbox.FakeSandbox
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenCheck
import com.wangzan101.mika.core.uistate.ScreenDiagnosis
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenState
import com.wangzan101.mika.core.uistate.Verification
import com.wangzan101.mika.core.testing.PromptSurface
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.cap.web.FakeWebPort
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import com.wangzan101.mika.core.guard.GuardVerdict
import com.wangzan101.mika.core.guard.Severity
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.assembly.toolDispatcher

/**
 * 工具与主循环给模型的反馈文字(提示、错误说明、提醒):按典型情形逐条触发,原样写进金样。
 * 情形按行为描述,不依赖这些字放在哪个文件里。
 */
internal object PromptSurfaceFeedback {
    private fun call(name: String, args: String = "{}") = Part.ToolCall("c1", name, args)

    /** 各错误码经一次失败的点击转成的说明(端口报什么码,模型就看到对应的说明)。 */
    private val FAILURE_CODES = listOf(
        "app_in_use_by_user", "app_in_use_by_other_task", "app_not_found", "no_focus", "not_editable", "no_app_open", "no_history",
        "tab_crashed", "obscured", "offscreen", "key_not_allowed", "no_tab_open", "handed_over", "site_mode_unavailable",
        "downloads_unavailable", "devtools_unavailable", "input_uncertain", "not_a_select", "no_such_option", "option_disabled",
        "select_disabled", "select_not_applied", "web_surface_inactive", "upload_not_opened", "upload_unavailable", "focus_moved",
        "link_unresolved", "invalid_link", "phone_unavailable", "ref_expired", "some_new_code", "Not A Code!",
    )

    private class Checked(private val c: ScreenCheck?, private val base: FakeScreen = FakeScreen()) : ScreenPort by base {
        override suspend fun checkScreen() = c
    }

    suspend fun tools(): String {
        val out = mutableListOf<Pair<String, String>>()
        suspend fun add(label: String, d: ToolDispatcher, name: String, args: String = "{}") {
            out += label to d.execute(call(name, args)).content.toString()
        }
        val phone = toolDispatcher(FakeScreen())
        add("unknown tool", phone, "phone_fly")
        add("invalid: unknown argument", phone, "phone_tap", """{"x":1,"y":1,"bogus":1}""")
        add("invalid: unknown argument (no refs)", phone, "phone_launch_app", """{"package":"com.x","bogus":1}""")
        add("invalid: not json", phone, "phone_tap", "{")
        add("invalid: not an object", phone, "phone_tap", "[]")
        add("invalid: required", phone, "phone_type_text", "{}")
        add("invalid: located ref elsewhere", phone, "phone_type_text", """{"text":"a","ref":"@v1","snapshotId":"s"}""")
        add("finish without evidence", phone, "finish", """{"answer":"done"}""")
        add("pay via confirmation", phone, "request_confirmation", """{"action":"pay","target":"x","content":"y","app":"com.x"}""")
        add("order without amount", phone, "request_confirmation", """{"action":"order","target":"x","content":"y","app":"com.x"}""")
        for (k in listOf("popup", "login", "captcha", "access_blocked", "payment")) add("barrier $k", phone, "handle_obstacle", """{"kind":"$k"}""")
        add("recall unknown", phone, "view_past_screens", """{"ids":[99]}""")
        add("phone_open_link not listed", toolDispatcher(FakeScreen(), deepLinks = true), "phone_open_link", """{"uri":"https://a.example.com/x","package":"com.x"}""")
        add("phone_launch_app bad package", phone, "phone_launch_app", """{"package":"nope"}""")

        for (code in FAILURE_CODES) {
            val s = FakeScreen().apply { result = ActionResult.Failed(code) }
            add("tap failed $code", toolDispatcher(s), "phone_tap", """{"x":1,"y":1}""")
        }
        add("tap failed with port hint", toolDispatcher(FakeScreen().apply { result = ActionResult.Failed("obscured", "盖住它的是登录弹窗") }), "phone_tap", """{"x":1,"y":1}""")
        add("tap unchanged", toolDispatcher(FakeScreen().apply { result = ActionResult.Ok(changed = false) }), "phone_tap", """{"x":1,"y":1}""")
        add("back unchanged", toolDispatcher(FakeScreen().apply { result = ActionResult.Ok(changed = false) }), "phone_back")
        add("scroll unchanged", toolDispatcher(FakeScreen().apply { result = ActionResult.Ok(changed = false, appSwitched = "com.y") }), "phone_scroll", """{"direction":"down"}""")
        add("tap switched app", toolDispatcher(FakeScreen().apply { result = ActionResult.Ok(changed = true, appSwitched = "com.y") }), "phone_tap", """{"x":1,"y":1}""")
        add("tap switched by target", toolDispatcher(FakeScreen().apply { result = ActionResult.Ok(changed = true, appSwitched = "com.y", switchedByTarget = true) }), "phone_tap", """{"x":1,"y":1}""")
        add("tap not settled", toolDispatcher(FakeScreen().apply { result = ActionResult.Ok(changed = true, settled = false) }), "phone_tap", """{"x":1,"y":1}""")
        add("type mismatch", toolDispatcher(FakeScreen().apply { result = ActionResult.Ok(verification = Verification.MISMATCH) }), "phone_type_text", """{"text":"a"}""")
        add("type unknown + enter", toolDispatcher(FakeScreen().apply { result = ActionResult.Ok(verification = Verification.UNKNOWN) }), "phone_type_text", """{"text":"a","pressEnter":true}""")
        add("type switched + enter", toolDispatcher(FakeScreen().apply { result = ActionResult.Ok(verification = Verification.MATCH, appSwitched = "com.y") }), "phone_type_text", """{"text":"a","pressEnter":true}""")
        add("double tap", phone, "phone_tap", """{"x":1,"y":1,"clicks":2}""")

        val shot = toolDispatcher(FakeScreen())
        shot.execute(call("phone_observe"))
        add("screenshotId as snapshotId", shot, "phone_tap", """{"ref":"@e1","snapshotId":"1"}""")

        for ((label, c) in listOf(
            "blank" to ScreenCheck(ScreenState.BLANK, "com.x", false),
            "blank no window" to ScreenCheck(ScreenState.BLANK, null, false, cause = ScreenDiagnosis.NO_WINDOW),
            "loading" to ScreenCheck(ScreenState.LOADING, "com.x", false),
            "protected" to ScreenCheck(ScreenState.PROTECTED, "com.x", false),
            "needs human" to ScreenCheck(ScreenState.NEEDS_HUMAN, "com.x", true, takeoverReason = "login"),
            "unsupported browser" to ScreenCheck(ScreenState.NORMAL, "com.x", false, takeoverReason = ScreenDiagnosis.UNSUPPORTED_BROWSER),
        )) add("phone_diagnose $label", toolDispatcher(Checked(c)), "phone_diagnose")

        val web = FakeWebPort()
        val onWeb = toolDispatcher(FakeScreen(), web = web, workspacePath = { com.wangzan101.mika.core.workspace.WorkspacePathCheck.NotFound })
        add("interact on phone", onWeb, "web_interact", """{"action":"key","key":"Enter"}""")
        add("script on phone", onWeb, "web_script", """{"code":"return 1"}""")
        add("navigate", onWeb, "web_navigate", """{"url":"https://shop.example.com/"}""")
        add("tabs switch unknown", onWeb, "web_tabs", """{"action":"switch","tabId":"zz"}""")
        add("capture without devtools", onWeb, "web_capture")
        add("wait without devtools", onWeb, "web_wait_for", """{"condition":"network_idle"}""")
        web.devtools = true
        web.waitOutcome = com.wangzan101.mika.core.cap.web.WaitOutcome(false, 5000, web.url)
        add("wait not met", onWeb, "web_wait_for", """{"condition":"text_present","value":"x"}""")
        add("capture unknown id", onWeb, "web_capture", """{"id":"r9"}""")
        add("upload outside", onWeb, "web_upload", """{"ref":"@e1","snapshotId":"w1","paths":["/sdcard/a.jpg"]}""")
        add("upload missing", onWeb, "web_upload", """{"ref":"@e1","snapshotId":"w1","paths":["/workspace/a.jpg"]}""")
        add("downloads", onWeb, "web_downloads")
        web.crash = true
        add("read page crashed", onWeb, "web_read_page")
        web.crash = false
        web.handedOver = true
        add("read page handed over", onWeb, "web_read_page")
        web.handedOver = false
        web.downloadsAvailable = false
        add("downloads unavailable", onWeb, "web_downloads")

        val mem = MemoryStore(object : MemoryPersistence {
            override fun load(): List<MemoryItem> = emptyList()
            override fun save(items: List<MemoryItem>) {}
        }, clock = { 1L }, ids = { "m" })
        val withMem = toolDispatcher(FakeScreen(), memory = mem, utterances = UserUtterances().apply { add("帮我看看订单") })
        add("memory quote not from user", withMem, "memory_save", """{"kind":"fact","content":"x","quote":"别的话"}""")

        val notReady = toolDispatcher(FakeScreen(), sandbox = FakeSandbox(SandboxStatus.NotInstalled), workspace = { "w" })
        add("sandbox not ready", notReady, "run_command", """{"command":"ls"}""")

        out += "read refuses a write" to phone.executeRead(call("phone_tap", """{"x":1,"y":1}""")).content.toString()
        return PromptSurface.entries(out)
    }

    /**
     * 主循环的提醒、说明、来源标签:经主循环和它的策略里用它们的那些名字取(私有的按名字反射调),
     * 证明每处用的是对的那条、填法对。带参数的照典型参数调。
     */
    fun loop(): String {
        fun fileCall(cls: String, name: String, vararg args: Any): String {
            val m = Class.forName("com.wangzan101.mika.core.agent.$cls").declaredMethods.first { it.name == name }.apply { isAccessible = true }
            return m.invoke(null, *args) as String
        }
        val empty = TurnPolicy.afterEmpty(already = false, truncated = false)!!
        val truncated = TurnPolicy.afterEmpty(already = false, truncated = true)!!
        val unconfirmed = TurnPolicy.unconfirmed(listOf("¥89", "「已发送」"))
        val progress = TurnPolicy.beforeStep(TurnSnapshot(11, 20))!!
        val final = TurnPolicy.finalTurn()
        val stuck = GuardVerdict("no_progress", Severity.Warn, JsonObject(emptyMap()))
        val giveUp = StuckAdvice.next(StuckState(warnings = 1), StuckInput(listOf(stuck), null, null)).second!!
        val typedAgain = StuckAdvice.next(StuckState(typed = mapOf("null\u0000外卖" to 2)), StuckInput(listOf(stuck), "外卖", null)).second!!
        val items = listOf(
            "empty_reply" to empty.text,
            "truncated_reply" to truncated.text,
            "unconfirmed_nudge" to unconfirmed.text,
            "answer_or_failure" to ANSWER_OR_FAILURE,
            "give_up_advice" to giveUp,
            "repeated_input_advice" to typedAgain,
            "progress_check" to progress.text,
            "final_turn" to final.text,
            "screenshot_note" to NoticePolicy.imageNote(Verb.Screenshot, offScreen = false),
            "file_image_note" to NoticePolicy.imageNote(Verb.ReadFile, offScreen = true),
            "action_frame_note" to NoticePolicy.imageNote(Verb.Tap, offScreen = false),
            "batch_view_note" to fileCall("AgentLoopKt", "batchViewNote", ""),
            "recalled_note" to NoticePolicy.imageNote(Verb.ViewPastScreens, offScreen = false),
            "nudge_source" to empty.source,
            "unconfirmed_source" to unconfirmed.source,
            "progress_source" to progress.source,
            "final_source" to final.source,
            "replay_source" to fileCall("RouteReplayKt", "getREPLAY_SOURCE"),
            "approved_step_first" to AgentLoop.APPROVED_STEP_FIRST,
            "replay_screen_header" to fileCall("RouteReplayKt", "getREPLAY_SCREEN_HEADER"),
            "replay_no_screen" to fileCall("RouteReplayKt", "getREPLAY_NO_SCREEN"),
            "confirmation_declined" to UserExchange.CONFIRMATION_DECLINED,
            "confirmation_approved" to UserExchange.CONFIRMATION_APPROVED,
            "no_answer_message" to UserExchange.INPUT_TIMEOUT_MESSAGE,
            "handback_note" to UserExchange.HANDBACK_NOTE,
            "no_handback_note" to UserExchange.NO_HANDBACK_NOTE,
            "user_finished_note" to AgentLoop.USER_FINISHED_NOTE,
            "user_finished_prompt" to UserExchange.USER_FINISHED_PROMPT,
        )
        return PromptSurface.entries(items + loopResults())
    }

    /** 主循环自己写的工具结果:一次给了多个调用、申报的 App 不是眼前的、没有确认关卡时申报。 */
    private fun loopResults(): List<Pair<String, String>> = runBlocking {
        fun reply(vararg calls: Part.ToolCall) = ModelReply(calls.toList(), StopReason.ToolUse, null)
        val done = com.wangzan101.mika.core.testing.answerReply("ok")
        val confirm = Part.ToolCall("c", "request_confirmation", "{\"action\":\"send\",\"target\":\"x\",\"content\":\"y\",\"app\":\"com.y\"}")
        val a = FakeModel(reply(Part.ToolCall("o1", "phone_observe", "{}"), Part.ToolCall("o2", "phone_observe", "{}")), reply(confirm), done)
        AgentLoop(a, toolDispatcher(FakeScreen()), "BASE").run("t")
        val b = FakeModel(reply(confirm), done)
        AgentLoop(b, toolDispatcher(FakeScreen()), "BASE", confirmations = null).run("t")
        listOf(a, b).flatMapIndexed { i, m ->
            m.requests.last().flatMap { it.parts }.filterIsInstance<Part.ToolResult>().map { "loop result ${i + 1}.${it.callId}" to it.content }
        }
    }
}
