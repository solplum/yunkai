package com.wangzan101.mika.core.cap.sandbox

import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.SubActions
import com.wangzan101.mika.core.tool.ToolExecution
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SandboxPhoneCommandTest {
    @Test fun commandPhoneCallsUseSubActionAndAllowlist() = runTest {
        val sandbox = FakeSandbox()
        val runner = SandboxTools(sandbox, { "task" }, false)
        val index = knownTools()
        val calls = mutableListOf<Part.ToolCall>()
        val sub = SubActions { call ->
            calls += call
            ToolExecution(buildJsonObject { put("ok", true); put("value", "from-screen") })
        }
        runner.runCommand(Args(RunCommand.def, buildJsonObject { put("command", "mika phone query '{}'" ) }), "task", "parent", sub, index)
        val phone = sandbox.phoneHandler
        assertNotNull(phone)
        val result = phone!!.call("query", buildJsonObject { put("text", "设置") })
        assertEquals("true", result["ok"]!!.jsonPrimitive.content)
        assertEquals("from-screen", result["value"]!!.jsonPrimitive.content)
        assertEquals("phone_query", calls.single().name)
        assertEquals("parent.phone1", calls.single().id)
        assertEquals("设置", (kotlinx.serialization.json.Json.parseToJsonElement(calls.single().argsJson) as JsonObject)["text"]!!.jsonPrimitive.content)

        val denied = phone.call("outside_tool", JsonObject(emptyMap()))
        assertEquals("unknown_phone_command", denied["error"]!!.jsonPrimitive.content)
        assertEquals(1, calls.size)
    }

    @Test fun toolsDeclareWhetherSandboxCanCallThem() = runTest {
        val sandbox = FakeSandbox()
        val runner = SandboxTools(sandbox, { "task" }, false)
        val calls = mutableListOf<String>()
        val sub = SubActions { call -> calls += call.name; ToolExecution(buildJsonObject { put("ok", true) }) }
        runner.runCommand(Args(RunCommand.def, buildJsonObject { put("command", "mika phone find '{}'") }), "task", "parent", sub, knownTools())
        val phone = sandbox.phoneHandler!!
        val opened = listOf("find", "extract", "dialog", "wait", "event", "gesture", "system_command", "query", "tap", "type", "key", "launch", "dump")
        for (op in opened) assertEquals(op, "true", phone.call(op, JsonObject(emptyMap()))["ok"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("phone_find", "phone_extract", "phone_dialog", "phone_wait", "phone_event", "phone_gesture", "phone_system_command",
                "phone_query", "phone_tap", "phone_type_text", "phone_press_key", "phone_launch_app", "phone_list_elements"),
            calls,
        )
        calls.clear()
        for (op in listOf("screenshot", "describe", "find_visually", "web_tap", "run_command", "finish")) {
            assertEquals(op, "unknown_phone_command", phone.call(op, JsonObject(emptyMap()))["error"]!!.jsonPrimitive.content)
        }
        assertEquals(emptyList<String>(), calls)
    }
}
