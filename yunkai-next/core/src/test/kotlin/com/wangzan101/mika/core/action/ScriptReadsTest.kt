package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.cap.web.SCRIPT_MAX_READS
import com.wangzan101.mika.core.tool.ScriptWebPort
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.cap.web.FakeWebPort
import com.wangzan101.mika.core.cap.web.ScriptRun
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.WEB_SCRIPT_TOOL
import com.wangzan101.mika.core.assembly.toolDispatcher

/** 页面脚本轮询一个不变的页面:读只在这次脚本调用里计数,最多提醒,不叫停任务,也不带到之后模型的读上。 */
class ScriptReadsTest {
    @Test fun pollingAnUnchangedPageNeitherStopsTheTaskNorLeaks() = runTest {
        val web = FakeWebPort().apply { elements = listOf(UiElement("@e1", "button", "搜索", 540, 120, listOf(0, 0, 999, 100), "#go", true, false, false)) }
        val port = ScriptWebPort(web) { b ->
            repeat(SCRIPT_MAX_READS) { b.call("snapshot", JsonObject(emptyMap())) }
            ScriptRun.Done("\"done\"")
        }
        val tools = toolDispatcher(FakeScreen(), web = port)
        tools.execute(Part.ToolCall("n", "web_navigate", """{"url":"https://shop.example.com/"}"""))
        val guards = OperationGuards()
        val executor = ActionExecutor(tools, guards, ConfirmationGate(), gate = {}, recorder = ActionRecorder(RunSink.NONE))

        val script = executor.run(Part.ToolCall("s", WEB_SCRIPT_TOOL, """{"code":"poll"}"""), Origin.Model(1))
        assertTrue("$script", script is ActionOutcome.Ran)
        assertNull(guards.stop)
        val reads = (script as ActionOutcome.Ran).after.filter { it.code == "read_loop" }
        assertTrue("脚本里的重读要提醒", reads.isNotEmpty())
        // 读的重复(read_loop、repeating_action)都只提醒
        assertTrue(script.verdicts.all { it.severity == com.wangzan101.mika.core.guard.Severity.Warn })

        // 之后模型自己读同一页:按任务的计数从头算,头几次不提醒
        val later = (2..5).map { executor.run(Part.ToolCall("m$it", "phone_list_elements", "{}"), Origin.Model(it)) as ActionOutcome.Ran }
        assertEquals(emptyList<String>(), later.flatMap { o -> o.verdicts.map { it.code } }.filter { it == "read_loop" })
        assertNull(guards.stop)
    }
}
