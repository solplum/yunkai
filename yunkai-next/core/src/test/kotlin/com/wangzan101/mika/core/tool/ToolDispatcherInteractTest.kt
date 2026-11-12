package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.cap.web.DEVTOOLS_UNAVAILABLE
import com.wangzan101.mika.core.cap.web.FakeWebPort
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.INTERACT_TOOL
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.assembly.knownToolNames

class ToolDispatcherInteractTest {
    private var n = 0
    private fun call(name: String, args: String = "{}") = Part.ToolCall("c${++n}", name, args)
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun reply(vararg c: Part.ToolCall) = ModelReply(c.toList(), StopReason.ToolUse, null)
    private fun FakeModel.result(i: Int): JsonObject =
        Json.parseToJsonElement(requests[i].last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().first().content).jsonObject

    private suspend fun onWeb(web: FakeWebPort = FakeWebPort(), stableRefs: Boolean = false): Pair<ToolDispatcher, String> {
        val t = toolDispatcher(FakeScreen(), web = web, stableRefs = stableRefs)
        t.execute(call("web_navigate", """{"url":"https://shop.example.com/cart"}"""))
        return t to t.execute(call("web_observe")).content.s("snapshotId")
    }

    private suspend fun ToolDispatcher.interact(args: String) = execute(call(INTERACT_TOOL, args)).content

    @Test fun offeredWithTheWebPortOnly() = runTest {
        assertTrue(toolDispatcher(FakeScreen()).definitions().none { it.name == INTERACT_TOOL })
        assertTrue(toolDispatcher(FakeScreen(), web = FakeWebPort()).definitions().any { it.name == INTERACT_TOOL })
        assertTrue(INTERACT_TOOL in knownToolNames())
        assertTrue(isWritingTool(INTERACT_TOOL, JsonObject(emptyMap())))
    }

    @Test fun actionsReachThePort() = runTest {
        val web = FakeWebPort()
        val (t, sid) = onWeb(web)
        assertEquals("true", t.interact("""{"action":"hover","ref":"@e1","snapshotId":"$sid"}""").s("ok"))
        assertEquals("true", t.interact("""{"action":"hover","x":500,"y":0}""").s("ok"))
        assertEquals("true", t.interact("""{"action":"drag","ref":"@e1","snapshotId":"$sid","toX":999,"toY":999,"steps":5}""").s("ok"))
        assertEquals("true", t.interact("""{"action":"drag","x":0,"y":0,"toRef":"@e2","snapshotId":"$sid"}""").s("ok"))
        assertEquals("true", t.interact("""{"action":"select","ref":"@e2","snapshotId":"$sid","option":"上海"}""").s("ok"))
        assertEquals("true", t.interact("""{"action":"key","key":"Control+A"}""").s("ok"))
        assertEquals(listOf(
            "hover Ref(ref=@e1, snapshotId=$sid)",
            // 0–999 按最近一屏(1080×2400)换成 view 像素
            "hover Xy(x=540, y=0)",
            "drag Ref(ref=@e1, snapshotId=$sid) Xy(x=1079, y=2399) 5",
            "drag Xy(x=0, y=0) Ref(ref=@e2, snapshotId=$sid) 10",
            "select @e2 $sid 上海",
            "combo Control+a",
        ), web.calls.filter { it.startsWith("hover") || it.startsWith("drag") || it.startsWith("select") || it.startsWith("combo") })
    }

    @Test fun argumentsAreValidatedPerAction() = runTest {
        val web = FakeWebPort()
        val (t, sid) = onWeb(web)
        val bad = mapOf(
            """{"action":"web_tap"}""" to "action",
            """{"action":"hover"}""" to "ref",
            """{"action":"hover","x":3}""" to "y",
            """{"action":"hover","ref":"@e1","snapshotId":"$sid","x":1,"y":1}""" to "x",
            """{"action":"hover","ref":"@e1"}""" to "snapshotId",
            """{"action":"hover","ref":"@v1","snapshotId":"$sid"}""" to "ref",
            """{"action":"hover","ref":"@e1","snapshotId":"$sid","key":"Enter"}""" to "key",
            """{"action":"drag","ref":"@e1","snapshotId":"$sid"}""" to "toRef",
            """{"action":"drag","ref":"@e1","snapshotId":"$sid","toX":1,"toY":1,"steps":0}""" to "steps",
            """{"action":"drag","ref":"@e1","snapshotId":"$sid","toX":1000,"toY":1}""" to "toX",
            """{"action":"select","ref":"@e1","snapshotId":"$sid"}""" to "option",
            """{"action":"select","option":"a"}""" to "ref",
            """{"action":"key"}""" to "key",
            """{"action":"key","key":"Hyper+Q"}""" to "key",
            """{"action":"key","key":"Control+V"}""" to "key",
            """{"action":"key","key":"Meta+c"}""" to "key",
            """{"action":"key","key":"Enter","ref":"@e1","snapshotId":"$sid"}""" to "ref",
            """{"action":"key","key":"Enter","bogus":1}""" to "bogus",
        )
        for ((args, field) in bad) {
            val r = t.interact(args)
            assertEquals(args, "invalid_arguments", r.s("error"))
            assertTrue("$args → ${r.s("message")}", r.s("message").contains(field))
        }
        assertTrue(web.calls.none { it.startsWith("hover") || it.startsWith("drag") || it.startsWith("select") || it.startsWith("combo") })
    }

    @Test fun phoneSnapshotsAreRefusedOnTheWeb() = runTest {
        val web = FakeWebPort()
        val t = toolDispatcher(FakeScreen(), web = web)
        // 手机的编号交给网页的工具:不交给端口,说明它是手机的
        val phoneSid = t.execute(call("phone_observe")).content.s("snapshotId")
        t.execute(call("web_navigate", """{"url":"https://shop.example.com/cart"}"""))
        val r = t.interact("""{"action":"hover","ref":"@e1","snapshotId":"$phoneSid"}""")
        assertEquals("wrong_surface", r.s("error"))
        assertTrue(r.s("message"), r.s("message").contains("phone"))
        assertTrue(web.calls.none { it.startsWith("hover") })
    }

    @Test fun portErrorsCarryCodesAndHints() = runTest {
        val web = FakeWebPort()
        val (t, sid) = onWeb(web)
        web.result = ActionResult.Failed(DEVTOOLS_UNAVAILABLE)
        val r = t.interact("""{"action":"hover","ref":"@e1","snapshotId":"$sid"}""")
        assertEquals("devtools_unavailable", r.s("error")); assertTrue(r.s("message"), r.s("message").contains("web_tap"))
        web.result = ActionResult.Failed("no_such_option", hint = "Options: \"北京\", \"上海\".")
        val o = t.interact("""{"action":"select","ref":"@e2","snapshotId":"$sid","option":"广州"}""")
        assertEquals("no_such_option", o.s("error")); assertTrue(o.s("message"), o.s("message").contains("\"上海\""))
        for (code in listOf("not_a_select", "option_disabled", "select_disabled", "select_not_applied", "obscured", "offscreen")) {
            web.result = ActionResult.Failed(code)
            val f = t.interact("""{"action":"select","ref":"@e2","snapshotId":"$sid","option":"x"}""")
            assertEquals(code, f.s("error")); assertTrue(f.s("message"), !f.s("message").startsWith("The action failed"))
        }
        web.result = ActionResult.Failed("ref_expired")
        assertEquals("ref_expired", t.interact("""{"action":"drag","ref":"@e1","snapshotId":"$sid","toX":1,"toY":1}""").s("error"))
    }

    @Test fun stableRefsAreTranslatedOnBothEndsOfADrag() = runTest {
        val web = FakeWebPort()
        val (t, sid) = onWeb(web, stableRefs = true)
        assertEquals("true", t.interact("""{"action":"drag","ref":"@e1","snapshotId":"$sid","toRef":"@e2"}""").s("ok"))
        assertEquals("drag Ref(ref=@e1, snapshotId=wt1-1) Ref(ref=@e2, snapshotId=wt1-1) 10", web.calls.last())
        // 退役的编号不交给端口
        assertEquals("ref_expired", t.interact("""{"action":"drag","ref":"@e1","snapshotId":"$sid","toRef":"@e9"}""").s("error"))
        assertEquals("ref_expired", t.interact("""{"action":"select","ref":"@e9","snapshotId":"$sid","option":"x"}""").s("error"))
        assertTrue(web.calls.last().startsWith("drag"))
    }

    @Test fun actionsAreLogged() = runTest {
        val (t, sid) = onWeb()
        t.interact("""{"action":"key","key":"Escape"}""")
        t.interact("""{"action":"select","ref":"@e2","snapshotId":"$sid","option":"上海"}""")
        val log = t.evidence().actions
        assertTrue(log.toString(), log.any { "web_interact(key Escape) 生效" in it })
        assertTrue(log.toString(), log.any { "web_interact(select 「上海」 选择文件) 生效" in it })
    }

    // ---------- 不可逆确认 ----------

    /** 购物车页,屏上有应付金额:这里命中不可逆判定的动作都要花钱,询问时要先同意(只有花钱才问)。 */
    private fun shop(vararg e: UiElement) = FakeWebPort().apply { elements = e.toList() + total }
    private val total = UiElement("@e9", "text", "合计 ¥25", 300, 1700, listOf(0, 800, 600, 860), null, false, false, false)
    private val field = UiElement("@e1", "edit", "留言", 500, 500, listOf(0, 400, 999, 600), null, true, true, false, focused = true)
    private val trash = UiElement("@e2", "button", "删除", 900, 2300, listOf(700, 940, 990, 990), null, true, false, false)

    private suspend fun gated(web: FakeWebPort, args: String): JsonObject {
        val m = FakeModel(
            reply(call("web_navigate", """{"url":"https://shop.example.com/cart"}""")),
            reply(call("web_observe")),
            reply(call(INTERACT_TOOL, args)),
            reply(call("end_not_done", """{"message":"停"}""")),
        )
        AgentLoop(m, toolDispatcher(FakeScreen(), web = web), "BASE", confirmations = ConfirmationGate()).run("做")
        return m.result(3)
    }

    // 在留言框里回车是发送:发消息不花钱,屏上有金额也直接做
    @Test fun enterInAFieldSendsWithoutAsking() = runTest {
        for (key in listOf("Enter", "Control+Enter")) {
            val web = shop(field)
            val r = gated(web, """{"action":"key","key":"$key"}""")
            assertTrue(key, r["error"]?.toString()?.contains("confirmation_required") != true)
            assertTrue(key, web.calls.any { it.startsWith("combo") })
        }
    }

    @Test fun dragOntoDeleteNeedsConfirmation() = runTest {
        val web = shop(field, trash)
        val r = gated(web, """{"action":"drag","x":500,"y":500,"toRef":"@e2","snapshotId":"wt1-1"}""")
        assertEquals("confirmation_required", r.s("error")); assertEquals("delete", r.s("action"))
        assertTrue(web.calls.none { it.startsWith("drag") })
    }

    private val city = UiElement("@e3", "combobox", "城市", 500, 1500, listOf(0, 600, 999, 700), null, true, false, false)

    @Test fun harmlessInteractionsPassTheGate() = runTest {
        for (args in listOf("""{"action":"key","key":"Escape"}""", """{"action":"key","key":"Control+A"}""",
            """{"action":"hover","ref":"@e2","snapshotId":"wt1-1"}""", """{"action":"select","ref":"@e3","snapshotId":"wt1-1","option":"北京"}""")) {
            val web = shop(field, trash, city)
            assertEquals(args, "true", gated(web, args).s("ok"))
        }
    }

    @Test fun selectThatMaySubmitNeedsConfirmation() = runTest {
        for (args in listOf("""{"action":"select","ref":"@e3","snapshotId":"wt1-1","option":"删除账户"}""",
            """{"action":"select","ref":"@e2","snapshotId":"wt1-1","option":"x"}""")) {
            val web = shop(field, trash, city)
            val r = gated(web, args)
            assertEquals(args, "confirmation_required", r.s("error")); assertEquals("delete", r.s("action"))
            assertTrue(web.calls.none { it.startsWith("select") })
        }
    }

    private val focusedTrash = UiElement("@e2", "button", "删除", 900, 2300, listOf(700, 940, 990, 990), null, true, false, false, focused = true)

    @Test fun activationKeysOnAFocusedDeleteButtonNeedConfirmation() = runTest {
        for (key in listOf("Space", "Shift+Space", "Control+Enter")) {
            val web = shop(focusedTrash)
            val r = gated(web, """{"action":"key","key":"$key"}""")
            assertEquals(key, "confirmation_required", r.s("error"))
            assertTrue(web.calls.none { it.startsWith("combo") })
        }
    }

    @Test fun commandKeysNeedConfirmation() = runTest {
        // 焦点在「删除」上按删除键;页面本身(没有焦点元素)上按退格
        for ((elements, key) in listOf(listOf(focusedTrash) to "Delete", listOf(trash) to "Backspace", listOf(focusedTrash) to "d")) {
            val web = shop(*elements.toTypedArray())
            assertEquals(key, "confirmation_required", gated(web, """{"action":"key","key":"$key"}""").s("error"))
            assertTrue(web.calls.none { it.startsWith("combo") })
        }
        // 输入框里是编辑;页面本身上没声明的字母放行
        assertEquals("true", gated(shop(field, trash), """{"action":"key","key":"Delete"}""").s("ok"))
        assertEquals("true", gated(shop(trash), """{"action":"key","key":"d"}""").s("ok"))
    }

    @Test fun focusAndEscapeKeysAreNeverGated() = runTest {
        for (key in listOf("Tab", "Shift+Tab", "Escape", "ArrowDown")) {
            val web = shop(focusedTrash)
            assertEquals(key, "true", gated(web, """{"action":"key","key":"$key"}""").s("ok"))
        }
    }

    @Test fun arrowOnAListThatCanSelectDeleteNeedsConfirmation() = runTest {
        val list = UiElement("@e1", "listbox", "操作", 500, 500, listOf(0, 100, 999, 400), null, true, false, false, focused = true)
        val del = UiElement("@e2", "option", "删除", 500, 600, listOf(0, 200, 999, 300), null, true, false, false)
        val web = shop(list, del)
        val r = gated(web, """{"action":"key","key":"ArrowDown"}""")
        assertEquals("confirmation_required", r.s("error")); assertEquals("delete", r.s("action"))
        assertEquals("true", gated(shop(list, del), """{"action":"key","key":"Escape"}""").s("ok"))
    }

    @Test fun uncertainInputIsReportedNotRetried() = runTest {
        val web = FakeWebPort()
        val (t, sid) = onWeb(web)
        web.result = ActionResult.Failed("input_uncertain")
        val r = t.execute(call("web_tap", """{"ref":"@e1","snapshotId":"$sid"}""")).content
        assertEquals("input_uncertain", r.s("error")); assertTrue(r.s("message"), r.s("message").contains("observe"))
        assertEquals(1, web.calls.count { it.startsWith("tapRef") })
        val k = t.interact("""{"action":"key","key":"Enter"}""")
        assertEquals("input_uncertain", k.s("error"))
    }
}
