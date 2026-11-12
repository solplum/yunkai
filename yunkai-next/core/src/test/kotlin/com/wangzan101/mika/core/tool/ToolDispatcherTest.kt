package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.PressKeys
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import com.wangzan101.mika.core.uistate.Verification
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

class FakeScreen : ScreenPort {
    val calls = mutableListOf<String>()
    var result: ActionResult = ActionResult.Ok()
    var png: ByteArray? = byteArrayOf(1, 2, 3)
    var snapshots = 0
    private val el = UiElement("@e1", "button", "搜索", 540, 120, listOf(0, 0, 999, 100), "a:id/s", true, false, false)
    override suspend fun snapshot(): ScreenSnapshot {
        snapshots++
        return ScreenSnapshot("snap$snapshots", "com.x", listOf(el), 1080, 2400)
    }
    /** 和真实端口一样:看一眼不登记编号,不算一次读屏。 */
    override suspend fun peek() = ScreenSnapshot("peek", "com.x", listOf(el), 1080, 2400)
    override suspend fun screenshotPng() = png
    override suspend fun tapRef(ref: String, snapshotId: String): ActionResult { calls += "tapRef $ref $snapshotId"; return result }
    override suspend fun tapXY(x: Int, y: Int): ActionResult { calls += "tapXY $x $y"; return result }
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult { calls += "type $ref $snapshotId $text"; return result }
    override suspend fun clearText(ref: String?, snapshotId: String?): ActionResult { calls += "clear $ref"; return result }
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult { calls += "scroll"; return result }
    val swipeMs = mutableListOf<Long>()
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ActionResult { calls += "swipe $x1 $y1 $x2 $y2"; swipeMs += durationMs; return result }
    override suspend fun back(): ActionResult { calls += "back"; return result }
    override suspend fun pressKey(keyCode: Int): ActionResult { calls += "key $keyCode"; return result }
    override suspend fun openApp(packageName: String, cold: Boolean): ActionResult { calls += "open $packageName $cold"; return result }
    override suspend fun waitStable(timeoutMs: Long) = true
    override suspend fun listApps() = listOf("com.x" to "X")
}

class ToolDispatcherTest {
    private fun call(name: String, args: String = "{}") = Part.ToolCall("c1", name, args)
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test fun callReasonIsTheModelsNoteUnlessTheToolOwnsTheField() {
        val d = toolDispatcher(FakeScreen())
        fun args(json: String) = Json.parseToJsonElement(json).jsonObject
        assertEquals("打开设置看电量", d.callReason("phone_launch_app", args("""{"packageName":"com.x","reason":" 打开设置看电量 "}""")))
        assertNull(d.callReason("phone_observe", args("{}")))
        assertNull(d.callReason("phone_observe", args("""{"reason":"  "}""")))
        // 接管的 reason 是原因代号,不是用意
        assertNull(d.callReason("hand_to_user", args("""{"reason":"captcha","message":"m"}""")))
    }

    @Test fun tapByRefCallsPort() = runTest {
        val s = FakeScreen(); val r = toolDispatcher(s).execute(call("phone_tap", """{"ref":"@e3","snapshotId":"abc"}"""))
        assertEquals(listOf("tapRef @e3 abc"), s.calls)
        assertTrue(r.content["ok"]!!.jsonPrimitive.boolean)
    }

    @Test fun tapNormalizedXYConvertsToPixels() = runTest {
        val s = FakeScreen(); toolDispatcher(s).execute(call("phone_tap", """{"x":500,"y":999}"""))
        assertEquals(1, s.snapshots) // 没有快照时先取一次
        assertEquals(listOf("tapXY 540 2399"), s.calls)
    }

    // 端口不会双击时如实报错,不退成单击
    @Test fun doubleTapOnAPortThatCannotIsRecoverableError() = runTest {
        val s = FakeScreen(); val r = toolDispatcher(s).execute(call("phone_tap", """{"x":1,"y":1,"clicks":2}"""))
        assertEquals("double_tap_unsupported", r.content.s("error")); assertTrue(s.calls.isEmpty())
    }

    @Test fun badArgsReturnStructuredError() = runTest {
        val d = toolDispatcher(FakeScreen())
        for (a in listOf("""{"ref":"e3","snapshotId":"a"}""", """{"x":1}""", """{"x":1000,"y":1}""", """{"ref":"@e1"}""", """{"x":1,"y":1,"bogus":1}""", "[]", "{")) {
            val r = d.execute(call("phone_tap", a))
            assertEquals(a, "invalid_arguments", r.content.s("error"))
            assertFalse(r.content["ok"]!!.jsonPrimitive.boolean)
            assertTrue(r.content.containsKey("message")); assertTrue(r.content.containsKey("recoverable"))
        }
        val m = d.execute(call("phone_type_text", """{"text":""}""")).content.s("message")
        assertTrue("text" in m)
    }

    @Test fun unknownToolReturnsUnknownToolError() = runTest {
        assertEquals("unknown_tool", toolDispatcher(FakeScreen()).execute(call("phone_fly")).content.s("error"))
        assertEquals("unknown_tool", toolDispatcher(FakeScreen()).execute(call("phone_screenshot")).content.s("error")) // 视觉关闭
    }

    @Test fun staleNodeErrorIsSurfaced() = runTest {
        val s = FakeScreen().apply { result = ActionResult.Failed("ref_expired") }
        val r = toolDispatcher(s).execute(call("phone_tap", """{"ref":"@e1","snapshotId":"old"}"""))
        assertEquals("ref_expired", r.content.s("error")); assertTrue("Read the screen again" in r.content.s("message"))
    }

    @Test fun takeoverOutcome() = runTest {
        val r = toolDispatcher(FakeScreen()).execute(call("hand_to_user", """{"reason":"login","message":"请登录"}"""))
        assertEquals(Outcome.Takeover("login", "请登录"), r.outcome)
        val bad = toolDispatcher(FakeScreen()).execute(call("hand_to_user", """{"reason":"nope","message":"x"}"""))
        assertNull(bad.outcome); assertEquals("invalid_arguments", bad.content.s("error"))
    }

    @Test fun aliasesMapToM1Reasons() = runTest {
        val d = toolDispatcher(FakeScreen())
        for ((alias, reason) in TAKEOVER_REASON_ALIASES) {
            val r = d.execute(call("hand_to_user", """{"reason":"$alias","message":"m"}"""))
            assertEquals(alias, reason, (r.outcome as Outcome.Takeover).reason)
        }
        assertEquals(mapOf("password_required" to "login", "2fa" to "login", "manual_review" to "other", "risk_control" to "access_blocked"), TAKEOVER_REASON_ALIASES)
    }

    @Test fun takeoverCarriesPaymentFields() = runTest {
        val r = toolDispatcher(FakeScreen()).execute(call("hand_to_user",
            """{"reason":"payment","message":"请付款","item":"咖啡豆 ×1","amount":"¥89.00","address":"上海"}"""))
        assertEquals(Outcome.Takeover("payment", "请付款", item = "咖啡豆 ×1", amount = "¥89.00", address = "上海"), r.outcome)
    }

    @Test fun refusedPayDeclarationFillsPaymentTakeover() = runTest {
        // 模型先按 request_confirmation 申报付款被拒,随后接管时没写金额:用申报里的
        val d = toolDispatcher(FakeScreen())
        val refused = d.execute(call("request_confirmation", """{"action":"pay","target":"某店","content":"咖啡豆 ×1","amount":"¥89.00","app":"com.x"}"""))
        assertEquals("payment_requires_takeover", refused.content.s("error"))
        val r = d.execute(call("hand_to_user", """{"reason":"payment","message":"请付款"}"""))
        assertEquals(Outcome.Takeover("payment", "请付款", item = "咖啡豆 ×1", amount = "¥89.00"), r.outcome)
        // 模型自己写了的不覆盖;别的原因不填
        assertEquals("¥10", (d.execute(call("hand_to_user", """{"reason":"payment","message":"m","amount":"¥10"}""")).outcome as Outcome.Takeover).amount)
        assertNull((d.execute(call("hand_to_user", """{"reason":"login","message":"m"}""")).outcome as Outcome.Takeover).amount)
    }

    @Test fun refusedPayIsUsedOnceAndClearedOnAppChange() = runTest {
        val pay = call("request_confirmation", """{"action":"pay","target":"某店","content":"咖啡豆 ×1","amount":"¥89.00","app":"com.x"}""")
        fun payTakeover() = call("hand_to_user", """{"reason":"payment","message":"请付款"}""")
        // 一次付款接管用掉之后,之后无关的付款接管不再带上
        val d = toolDispatcher(FakeScreen())
        d.execute(pay)
        assertEquals("¥89.00", (d.execute(payTakeover()).outcome as Outcome.Takeover).amount)
        assertNull((d.execute(payTakeover()).outcome as Outcome.Takeover).amount)
        // 打开别的 App 后作废
        val d2 = toolDispatcher(FakeScreen())
        d2.execute(pay)
        d2.execute(call("phone_launch_app", """{"package":"com.y"}"""))
        val t2 = d2.execute(payTakeover()).outcome as Outcome.Takeover
        assertNull(t2.amount); assertNull(t2.item)
        // 被切到别的 App 后作废
        val s3 = FakeScreen()
        val d3 = toolDispatcher(s3)
        d3.execute(pay)
        s3.result = ActionResult.Ok(appSwitched = "com.z")
        d3.execute(call("phone_tap", """{"ref":"@e1","snapshotId":"x"}"""))
        assertNull((d3.execute(payTakeover()).outcome as Outcome.Takeover).amount)
    }

    @Test fun takeoverOutcomesSurviveLongActionLog() = runTest {
        val d = toolDispatcher(FakeScreen())
        d.execute(call("hand_to_user", """{"reason":"payment","message":"请付款"}"""))
        d.noteTakeover("payment", HandBack(HandBackOutcome.skipped))
        repeat(40) { d.execute(call("phone_launch_app", """{"package":"com.a$it"}""")) }
        val e = d.evidence()
        assertTrue(e.actions.none { "hand_to_user" in it })
        assertEquals(1, e.takeovers.size)
        assertTrue(e.takeovers.single(), "hand_to_user(payment) 接管被跳过" in e.takeovers.single())
    }

    @Test fun argsTrimmed() = runTest {
        val r = toolDispatcher(FakeScreen()).execute(call("ask_user", """{"prompt":"  选哪个?\n","options":[" 同事张伟 ","同学张伟\t"]}"""))
        assertEquals(Outcome.Input("选哪个?", listOf("同事张伟", "同学张伟")), r.outcome)
        val d = toolDispatcher(FakeScreen())
        // 去空白后为空、选项重复到不足两个:参数不对
        assertEquals("invalid_arguments", d.execute(call("ask_user", """{"prompt":"  \n "}""")).content.s("error"))
        assertEquals("invalid_arguments", d.execute(call("ask_user", """{"prompt":"选","options":["A"," "]}""")).content.s("error"))
        assertEquals("invalid_arguments", d.execute(call("ask_user", """{"prompt":"选","options":["A"," A "]}""")).content.s("error"))
        assertEquals(listOf("A", "B"), (d.execute(call("ask_user", """{"prompt":"选","options":["A","B"," A"]}""")).outcome as Outcome.Input).options)
    }

    @Test fun terminalOutcomes() = runTest {
        val d = toolDispatcher(FakeScreen())
        // 办完不经工具:没有 finish 这个工具了
        assertTrue(d.definitions().none { it.name == "finish" })
        assertEquals(Outcome.Input("哪个?"), d.execute(call("ask_user", """{"prompt":"哪个?"}""")).outcome)
        assertEquals(Outcome.Input("选", listOf("a", "b")), d.execute(call("ask_user", """{"prompt":"选","options":["a","b"]}""")).outcome)
        assertEquals("invalid_arguments", d.execute(call("ask_user", """{"prompt":"选","options":["a"]}""")).content.s("error"))
        assertEquals(Outcome.Failure("卡住"), d.execute(call("end_not_done", """{"message":"卡住"}""")).outcome)
        val rf = d.execute(call("end_not_done", """{"message":"m","subtype":"blocked","recoverable":true}"""))
        assertEquals(Outcome.Failure("m", "blocked", true), rf.outcome)
        assertEquals("blocked", rf.content.s("subtype"))
        assertEquals("invalid_arguments", d.execute(call("end_not_done", """{"message":"m","subtype":"x"}""")).content.s("error"))
    }

    @Test fun pressKeyAllowsOnlyNavigationAndEditingKeys() = runTest {
        val s = FakeScreen(); val d = toolDispatcher(s)
        for (k in listOf("KEYCODE_DPAD_UP", "KEYCODE_DPAD_CENTER", "KEYCODE_ENTER", "KEYCODE_NUMPAD_ENTER", "KEYCODE_DEL",
            "KEYCODE_FORWARD_DEL", "KEYCODE_TAB", "KEYCODE_SPACE", "KEYCODE_ESCAPE", "KEYCODE_PAGE_DOWN",
            "KEYCODE_MOVE_HOME", "KEYCODE_MOVE_END", "KEYCODE_BACK", "KEYCODE_MENU", "66", "160", "4")) {
            assertTrue(k, d.execute(call("phone_press_key", """{"key":"$k"}""")).content["ok"]!!.jsonPrimitive.boolean)
        }
        assertEquals(listOf(19, 23, 66, 160, 67, 112, 61, 62, 111, 93, 122, 123, 4, 82, 66, 160, 4).map { "key $it" }, s.calls)
        s.calls.clear()
        for (k in listOf("KEYCODE_VOLUME_UP", "KEYCODE_VOLUME_DOWN", "KEYCODE_POWER", "KEYCODE_SLEEP", "KEYCODE_WAKEUP",
            "KEYCODE_APP_SWITCH", "KEYCODE_HOME", "KEYCODE_SEARCH", "KEYCODE_ASSIST", "KEYCODE_CAMERA",
            "KEYCODE_MEDIA_PLAY_PAUSE", "24", "25", "26", "223", "224", "3", "187", "-1", "99999")) {
            val r = d.execute(call("phone_press_key", """{"key":"$k"}"""))
            assertEquals(k, "invalid_arguments", r.content.s("error"))
        }
        assertTrue(s.calls.isEmpty())
        assertTrue(PressKeys.isAllowed(66)); assertFalse(PressKeys.isAllowed(26)); assertFalse(PressKeys.isAllowed(3))
    }

    @Test fun typeTextReportsAppSwitchAndSkipsEnter() = runTest {
        val s = FakeScreen().apply { result = ActionResult.Ok(verification = Verification.MATCH, appSwitched = "com.other") }
        val r = toolDispatcher(s).execute(call("phone_type_text", """{"text":"hi","pressEnter":true}"""))
        assertEquals("com.other", r.content.s("app_switched"))
        assertFalse(r.content["pressedEnter"]!!.jsonPrimitive.boolean)
        assertTrue(r.content.s("hint"), "com.other" in r.content.s("hint"))
        assertEquals(listOf("type null null hi"), s.calls)
    }

    @Test fun launchAppForwardsPortHint() = runTest {
        val s = FakeScreen().apply { result = ActionResult.Ok(hint = "coldIgnored: app has a task outside Mika's screen") }
        val r = toolDispatcher(s).execute(call("phone_launch_app", """{"package":"com.x","cold":true}"""))
        assertTrue(r.content.s("hint"), "coldIgnored" in r.content.s("hint"))
    }

    @Test fun pressKeyDescriptionNamesTheAllowlist() {
        val spec = toolDispatcher(FakeScreen()).definitions().first { it.name == "phone_press_key" }
        assertTrue(spec.description, "KEYCODE_NUMPAD_ENTER" in spec.description)
        assertFalse(spec.description, "VOLUME" in spec.description)
    }

    @Test fun writingToolClassification() {
        val e = buildJsonObject { }
        for (n in listOf("phone_tap", "phone_type_text", "phone_scroll", "phone_swipe", "phone_back", "phone_launch_app", "phone_clear_text", "phone_press_key")) assertTrue(n, isWritingTool(n, e))
        for (n in listOf("phone_observe", "phone_screenshot", "wait", "phone_list_elements", "phone_list_apps", "end_not_done")) assertFalse(n, isWritingTool(n, e))
    }

    @Test fun observeAttachesImageOnlyWithVision() = runTest {
        val off = toolDispatcher(FakeScreen()).execute(call("phone_observe"))
        assertNull(off.image)
        assertEquals("com.x", off.content.s("currentApp")); assertEquals("snap1", off.content.s("snapshotId"))
        assertEquals(1080, off.content["viewport"]!!.jsonObject["width"]!!.jsonPrimitive.int)
        assertEquals(1, off.content["elements"]!!.jsonArray.size)
        assertFalse(off.content["truncated"]!!.jsonPrimitive.boolean)
        val on = toolDispatcher(FakeScreen(), visionEnabled = true).execute(call("phone_observe"))
        assertArrayEquals(byteArrayOf(1, 2, 3), on.image)
        assertNull(toolDispatcher(FakeScreen()).execute(call("phone_list_elements")).image)
    }

    @Test fun definitionsFollowVision() {
        val off = toolDispatcher(FakeScreen()).definitions().map { it.name }
        assertFalse("phone_screenshot" in off); assertTrue("phone_observe" in off); assertFalse("home" in off)
        assertTrue("phone_screenshot" in toolDispatcher(FakeScreen(), true).definitions().map { it.name })
        toolDispatcher(FakeScreen()).definitions().forEach { assertTrue(it.name, it.parametersJsonSchema.startsWith("{")) }
    }

    @Test fun typeTextPressEnterAndLaunchApp() = runTest {
        val s = FakeScreen(); val d = toolDispatcher(s)
        d.execute(call("phone_type_text", """{"text":"hi","pressEnter":true}"""))
        d.execute(call("phone_launch_app", """{"package":"com.x","cold":true}"""))
        d.execute(call("phone_press_key", """{"key":"KEYCODE_ENTER"}"""))
        assertEquals(listOf("type null null hi", "key 66", "open com.x true", "key 66"), s.calls)
    }

    @Test fun notEditableExplainsWhatToDo() = runTest {
        val s = FakeScreen().apply { result = ActionResult.Failed("not_editable") }
        val r = toolDispatcher(s).execute(call("phone_type_text", """{"text":"hi","ref":"@e1","snapshotId":"a"}"""))
        assertEquals("not_editable", r.content.s("error"))
        assertTrue(r.content.s("message"), "actual input field" in r.content.s("message"))
    }

    @Test fun typeTextCarriesPortHintWhenVerified() = runTest {
        val s = FakeScreen().apply { result = ActionResult.Ok(verification = Verification.MATCH, hint = "先点开了真正的输入框") }
        val r = toolDispatcher(s).execute(call("phone_type_text", """{"text":"hi"}"""))
        assertEquals("match", r.content.s("verification"))
        assertTrue("先点开了真正的输入框" in r.content.s("hint"))
    }

    @Test fun typeTextCarriesPortHintWhenUnverified() = runTest {
        val s = FakeScreen().apply { result = ActionResult.Ok(verification = Verification.UNKNOWN, hint = "这个输入框需要粘贴") }
        val r = toolDispatcher(s).execute(call("phone_type_text", """{"text":"hi","pressEnter":true}"""))
        assertEquals("unknown", r.content.s("verification"))
        assertTrue(r.content.s("hint")!!, "这个输入框需要粘贴" in r.content.s("hint")!!)
        assertEquals(listOf("type null null hi"), s.calls) // 核实不了就不按回车
    }

    @Test fun scrollPrefersAccessibilityAndSwipeUsesNormalizedCoordinates() = runTest {
        val s = FakeScreen(); val d = toolDispatcher(s)
        d.execute(call("phone_scroll", """{"direction":"down"}"""))
        d.execute(call("phone_scroll", """{"direction":"down","amount":4}"""))
        d.execute(call("phone_swipe", """{"startX":0,"startY":999,"endX":999,"endY":0}"""))
        assertEquals("scroll", s.calls[0])
        assertEquals("swipe 540 1681 540 720", s.calls[1])
        assertEquals("swipe 0 2399 1079 0", s.calls[2])
    }

    @Test fun swipeHonorsDuration() = runTest {
        val s = FakeScreen(); val d = toolDispatcher(s)
        d.execute(call("phone_swipe", """{"startX":500,"startY":800,"endX":500,"endY":200,"durationMs":1200}"""))
        d.execute(call("phone_swipe", """{"startX":500,"startY":800,"endX":500,"endY":200}"""))
        d.execute(call("phone_scroll", """{"direction":"down"}"""))
        assertEquals(listOf(1200L, 400L), s.swipeMs)
        val bad = d.execute(call("phone_swipe", """{"startX":500,"startY":800,"endX":500,"endY":200,"durationMs":9000}"""))
        assertFalse(bad.content["ok"]!!.jsonPrimitive.boolean)
        assertEquals(2, s.swipeMs.size)
    }

    @Test fun failedPortResultBecomesStructuredError() = runTest {
        val s = FakeScreen().apply { result = ActionResult.Failed("no_focus") }
        val r = toolDispatcher(s).execute(call("phone_back"))
        assertEquals("no_focus", r.content.s("error")); assertTrue(r.content["recoverable"]!!.jsonPrimitive.boolean)
        assertNotNull(r.content["message"])
    }

    @Test fun schemaReasonHandling() {
        val specs = toolDispatcher(FakeScreen()).definitions().associateBy { it.name }
        val tk = Json.parseToJsonElement(specs["hand_to_user"]!!.parametersJsonSchema).jsonObject
        val r = tk["properties"]!!.jsonObject["reason"]!!.jsonObject
        assertEquals(TAKEOVER_REASONS, r["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(tk["required"]!!.jsonArray.any { it.jsonPrimitive.content == "reason" })
        val back = Json.parseToJsonElement(specs["phone_back"]!!.parametersJsonSchema).jsonObject
        assertTrue(back["properties"]!!.jsonObject.containsKey("reason"))
        assertEquals(0, back["required"]!!.jsonArray.size)
    }

    @Test fun listAppsReturnsPlainPackagesAndLabels() = runTest {
        val c = toolDispatcher(FakeScreen()).execute(call("phone_list_apps")).content
        assertEquals("com.x", c["packages"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("X", c["apps"]!!.jsonArray[0].jsonObject["label"]!!.jsonPrimitive.content)
    }

    @Test fun unknownToolNamesTool() = runTest {
        assertEquals("zzz", toolDispatcher(FakeScreen()).execute(call("zzz")).content.s("tool"))
    }

    @Test fun portExceptionBecomesOperationFailed() = runTest {
        val s = object : ScreenPort by FakeScreen() { override suspend fun back(): ActionResult = throw IllegalStateException("boom") }
        assertEquals("operation_failed", toolDispatcher(s).execute(call("phone_back")).content.s("error"))
    }

    @Test fun waitClearTextScrollXYAndScreenshot() = runTest {
        val s = FakeScreen(); val d = toolDispatcher(s, visionEnabled = true)
        assertEquals("0.5", d.execute(call("wait", """{"seconds":0.5}""")).content.s("waitedSeconds"))
        assertEquals("invalid_arguments", d.execute(call("wait", """{"seconds":99}""")).content.s("error"))
        d.execute(call("phone_clear_text", """{"ref":"@e2","snapshotId":"q"}"""))
        d.execute(call("phone_scroll", """{"direction":"right","amount":2,"x":500,"y":500}"""))
        assertEquals("invalid_arguments", d.execute(call("phone_scroll", """{"direction":"up","x":5}""")).content.s("error"))
        assertEquals("clear @e2", s.calls[0])
        // right, dist 200: x 600 -> 400 (px 648 -> 432), y 500 -> 1201
        assertEquals("swipe 648 1201 432 1201", s.calls[1])
        val shot = d.execute(call("phone_screenshot"))
        assertArrayEquals(byteArrayOf(1, 2, 3), shot.image)
        assertTrue(shot.content["ok"]!!.jsonPrimitive.boolean)
        s.png = null
        assertEquals("screenshot_unavailable", d.execute(call("phone_screenshot")).content.s("error"))
    }

    private val lookupTools = listOf("knowledge_search", "knowledge_read", "skill_search", "skill_read")

    @Test fun lookupToolsListedOnlyWithCatalogAndAreReadOnly() = runTest {
        assertFalse(toolDispatcher(FakeScreen()).definitions().any { it.name in lookupTools })
        val d = toolDispatcher(FakeScreen(), skills = com.wangzan101.mika.core.knowledge.GuideCatalog.loadBundled())
        assertTrue(d.definitions().map { it.name }.containsAll(lookupTools))
        for (t in lookupTools) assertFalse(t, isWritingTool(t, JsonObject(emptyMap())))
    }

    // 知识和技能各查各的、各读各的;读错了组的名字时说清该用哪个工具
    @Test fun knowledgeAndSkillLookupsThroughDispatcher() = runTest {
        val d = toolDispatcher(FakeScreen(), skills = com.wangzan101.mika.core.knowledge.GuideCatalog.loadBundled())
        val found = d.execute(call("knowledge_search", """{"query":"闲鱼"}""")).content
        val matches = found["matches"]!!.jsonArray
        assertTrue(matches.size in 1..10)
        assertEquals(setOf("name", "display", "kind", "description"), matches[0].jsonObject.keys)
        assertTrue(matches.all { it.jsonObject.s("kind") in setOf("app", "site") })
        val sites = d.execute(call("knowledge_search", """{"query":"知乎","kind":"site"}""")).content["matches"]!!.jsonArray
        assertTrue(sites.isNotEmpty() && sites.all { it.jsonObject.s("kind") == "site" })
        assertEquals("invalid_arguments", d.execute(call("knowledge_search", """{"query":"x","kind":"skill"}""")).content.s("error"))
        val skills = d.execute(call("skill_search", """{"query":"文档"}""")).content["matches"]!!.jsonArray
        assertTrue(skills.isNotEmpty() && skills.all { it.jsonObject.s("kind") in setOf("skill", "personal") })
        val view = d.execute(call("knowledge_read", """{"name":"app:xianyu"}""")).content
        assertTrue(view["ok"]!!.jsonPrimitive.boolean)
        assertTrue(view.s("content").contains("com.taobao.idlefish"))
        assertTrue(d.execute(call("skill_read", """{"name":"documents"}""")).content["ok"]!!.jsonPrimitive.boolean)
        val wrongGroup = d.execute(call("skill_read", """{"name":"app:xianyu"}""")).content
        assertEquals("skill_not_found", wrongGroup.s("error"))
        assertTrue(wrongGroup.s("message"), "knowledge_read" in wrongGroup.s("message"))
        val skillAsKnowledge = d.execute(call("knowledge_read", """{"name":"documents"}""")).content
        assertEquals("knowledge_not_found", skillAsKnowledge.s("error"))
        assertTrue(skillAsKnowledge.s("message"), "skill_read" in skillAsKnowledge.s("message"))
        assertEquals("knowledge_not_found", d.execute(call("knowledge_read", """{"name":"nope"}""")).content.s("error"))
        assertEquals("skill_not_found", d.execute(call("skill_read", """{"name":"nope"}""")).content.s("error"))
        assertEquals("invalid_arguments", d.execute(call("knowledge_read", """{"name":"x","file":"y"}""")).content.s("error"))
        assertEquals("invalid_arguments", d.execute(call("skill_search", """{"query":" "}""")).content.s("error"))
    }

    @Test fun evidenceHasFreshReadRecentReadsAndActions() = runTest {
        val s = FakeScreen()
        val d = toolDispatcher(s)
        repeat(4) { d.execute(call("phone_observe")) }
        s.result = ActionResult.Ok(changed = true)
        d.execute(call("phone_tap", """{"ref":"@e1","snapshotId":"snap4"}"""))
        d.execute(call("phone_type_text", """{"text":"天气"}"""))
        s.result = ActionResult.Ok(secret = true)
        d.execute(call("phone_type_text", """{"text":"pw123456"}"""))
        s.result = ActionResult.Ok(changed = false)
        d.execute(call("phone_back"))
        val before = s.snapshots
        val e = d.evidence()
        assertEquals(before + 1, s.snapshots) // 此刻重新读了一屏
        assertEquals("当前 App:com.x\n搜索", e.freshText)
        assertEquals(e.read(ObsKind.Read).distinct(), e.read(ObsKind.Read)) // 同样的读屏只记一次
        assertNull(e.freshImage)
        // 密码框不写原文
        assertEquals(listOf("第5步 phone_tap(搜索) 生效", "第6步 phone_type_text(「天气」) 生效", "第7步 phone_type_text() 画面没变化", "第8步 phone_back() 画面没变化"), e.actions)
        assertNotNull(d.evidence(withImage = true).freshImage)
    }

    @Test fun evidenceSurvivesScreenshotFailure() = runTest {
        val s = object : ScreenPort by FakeScreen() {
            override suspend fun screenshotPng(): ByteArray? = throw IllegalStateException("capture failed")
        }
        val e = toolDispatcher(s).evidence(withImage = true)
        assertNull(e.freshImage)
        assertEquals("当前 App:com.x\n搜索", e.freshText)
    }

    /** 还没有虚拟屏时的端口:读屏是空屏,写操作失败 no_app_open。 */
    private class NoDisplayScreen(private val inner: FakeScreen = FakeScreen()) : ScreenPort by inner {
        override suspend fun snapshot() = ScreenSnapshot("empty", null, emptyList(), 1080, 2400, hasWindow = false)
        override suspend fun screenshotPng(): ByteArray? = null
        override suspend fun tapXY(x: Int, y: Int): ActionResult = ActionResult.Failed("no_app_open")
        override suspend fun back(): ActionResult = ActionResult.Failed("no_app_open")
    }

    @Test fun beforeLaunchObserveShowsEmptyScreenHintAndWritesSayLaunchFirst() = runTest {
        val d = toolDispatcher(NoDisplayScreen())
        val o = d.execute(call("phone_observe")).content
        assertTrue(o["ok"]!!.jsonPrimitive.boolean)
        assertTrue(o.s("hint"), "Start with phone_launch_app" in o.s("hint"))
        for (r in listOf(d.execute(call("phone_tap", """{"x":500,"y":500}""")), d.execute(call("phone_back")))) {
            assertEquals("no_app_open", r.content.s("error"))
            assertTrue(r.content.s("message"), "Open an app or a page first" in r.content.s("message"))
        }
    }

    @Test fun evidenceKnowsWhetherAnAppWasOpened() = runTest {
        val s = FakeScreen()
        val d = toolDispatcher(s)
        assertFalse(d.evidence().appOpened)
        s.result = ActionResult.Failed("app_not_found")
        d.execute(call("phone_launch_app", """{"package":"com.x"}"""))
        assertFalse(d.evidence().appOpened)
        s.result = ActionResult.Ok()
        d.execute(call("phone_launch_app", """{"package":"com.x"}"""))
        assertTrue(d.evidence().appOpened)
    }

    private suspend fun barrier(kind: String, reason: String? = null) =
        toolDispatcher(FakeScreen())
            .execute(call("handle_obstacle", buildJsonObject { put("kind", kind); reason?.let { put("reason", it) } }.toString())).content

    @Test fun obstacleHintsPerKind() = runTest {
        val expect = mapOf(
            "popup" to listOf("own close control", "Do not call hand_to_user"),
            "login" to listOf("hand_to_user with reason login"),
            "captcha" to listOf("hand_to_user with reason captcha"),
            "access_blocked" to listOf("Do not retry", "hand_to_user with reason access_blocked", "subtype blocked"),
            "payment" to listOf("complete it directly"),
        )
        for ((k, words) in expect) {
            val r = barrier(k)
            assertTrue(k, r["ok"]!!.jsonPrimitive.boolean)
            assertEquals(k, r.s("kind"))
            for (w in words) assertTrue("$k: ${r.s("hint")}", w in r.s("hint"))
        }
        assertEquals(5, expect.keys.map { barrier(it).s("hint") }.toSet().size)
    }

    @Test fun unknownKindIsBadArgs() = runTest {
        for (k in listOf("slider", "LOGIN")) {
            val r = barrier(k)
            assertEquals(k, "invalid_arguments", r.s("error"))
            assertTrue(r.s("message"), "popup|login|captcha|access_blocked|payment" in r.s("message"))
        }
        assertEquals("invalid_arguments", barrier("").s("error"))
        val tooLong = barrier("login", reason = "x".repeat(301))
        assertEquals("invalid_arguments", tooLong.s("error"))
    }

    @Test fun loginAndCaptchaHintsUnchangedInMeaning() = runTest {
        // M1 的两种:都是叫 hand_to_user(带同名 reason)、交回后重新读屏;带 reason 参数不影响结果
        for (k in listOf("login", "captcha")) {
            val h = barrier(k, reason = "页面要求登录后查看").s("hint")
            assertTrue(h, "hand_to_user with reason $k" in h && "observe again" in h)
        }
        assertTrue("Do not try to solve" in barrier("captcha").s("hint"))
    }
}
