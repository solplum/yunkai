package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.gate.gateTargetOf
import com.wangzan101.mika.core.risk.GateTarget
import com.wangzan101.mika.core.model.Part
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.WEB_SCRIPT_TOOL
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.assembly.allTools
import com.wangzan101.mika.core.assembly.knownToolNames
import com.wangzan101.mika.core.testing.SANDBOX_TOOLS
import com.wangzan101.mika.core.testing.WEB_TOOLS

/** 由工具声明的风险推出的分类,和改成声明之前按名字列的表一样;手机、网页两套屏幕工具各有一份同样的动词。 */
class ToolRiskTest {
    private fun both(vararg verbs: String) = verbs.flatMap { listOf("phone_$it", "web_$it") }.toSet()

    /** 原来要过锁屏/暂停关卡的工具表。 */
    private val oldWriting = both("tap", "scroll", "swipe", "type_text", "clear_text", "back", "press_key", "diagnose") + setOf(
        "phone_launch_app", "phone_share_text", "phone_gesture", "hand_to_user",
        "phone_set_alarm", "phone_set_timer", "phone_show_alarms", "phone_add_calendar_event", "phone_set_wheel",
        "web_navigate", "web_tabs", "web_upload", "web_interact", "web_site_mode", "phone_open_link",
    )
    private val oldSandbox = setOf("run_command", "run_bundled_script", "read_file", "write_file", "search_files", "share_file")
    private val oldWeb = setOf(
        "web_navigate", "web_read_page", "web_evaluate_js", "web_run_site_command", "web_tabs", "web_downloads", "web_upload",
        "web_site_mode", "web_script", "web_interact", "web_capture", "web_wait_for",
    ) + both("observe", "list_elements", "screenshot", "describe", "find_visually", "tap", "scroll", "swipe",
        "type_text", "clear_text", "back", "press_key", "diagnose").filter { it.startsWith("web_") }
    private val names = allTools().map { it.name }

    @Test fun writingToolsDerivedFromRisk() {
        assertEquals(oldWriting, names.filter { isWritingTool(it, JsonObject(emptyMap())) }.toSet())
    }

    @Test fun groupsMatchOldNameSets() {
        assertEquals(oldSandbox, names.filter(::isSandboxTool).toSet())
        assertEquals(oldSandbox, SANDBOX_TOOLS)
        assertEquals(oldWeb, WEB_TOOLS)
    }

    @Test fun readCallsRefuseWhatTheOldListsRefused() = runTest {
        val refused = oldWriting + oldSandbox + WEB_SCRIPT_TOOL + setOf("phone_find", "phone_collect", "phone_dialog", "phone_run", "phone_system_command", "schedule_task", "spawn_agent")
        assertEquals(refused, allTools().filter { !it.readOnly }.map { it.name }.toSet())
        val d = toolDispatcher(FakeScreen())
        for (name in names) {
            val r = d.executeRead(Part.ToolCall("c", name, "{}")).content
            assertEquals(name, name in refused, r["error"] == JsonPrimitive("not_a_read"))
        }
    }

    @Test fun externalToolsDeclareHowTheirTargetIsJudged() {
        for (t in allTools()) assertEquals(t.name, t.risk == Risk.External, t.gateTarget != null || t.approvalRisk != null)
        assertEquals(both("tap", "type_text", "press_key", "swipe", "scroll") + setOf("web_interact", "web_upload", "schedule_task"),
            allTools().filter { it.risk == Risk.External }.map { it.name }.toSet())
    }

    /**
     * 原来 needsCheck / classify 按名字分的几支,现在按声明的目标分;每支的判法没动,
     * 名字到目标的对应和原来一样,判定结果就一样(具体情形由 IrreversibleDetectorTest 覆盖)。
     */
    @Test fun gateTargetsMatchOldNameBranches() {
        val perVerb = mapOf("tap" to GateTarget.Tap, "type_text" to GateTarget.Enter, "press_key" to GateTarget.Key, "swipe" to GateTarget.Swipe, "scroll" to GateTarget.Swipe)
        val old = perVerb.flatMap { (v, g) -> listOf("phone_$v" to g, "web_$v" to g) }.toMap() +
            mapOf("web_upload" to GateTarget.FileControl, "web_interact" to GateTarget.Interact)
        for (t in allTools().map { it.name } + "do_steps" + "unknown") assertEquals(t, old[t], gateTargetOf(t))
    }

    @Test fun everyToolDeclaresOneGroupAndUniqueName() {
        assertEquals(names.size, names.toSet().size)
        assertTrue(knownToolNames().containsAll(names))
    }
}
