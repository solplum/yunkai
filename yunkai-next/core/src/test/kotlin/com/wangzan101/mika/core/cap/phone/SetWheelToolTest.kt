package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.FAILURE_MESSAGES
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.WheelOutcome
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetWheelToolTest {
    private class WheelScreen : ScreenPort by FakeScreen() {
        var wheel: WheelOutcome = WheelOutcome(true, "40", "00", 20, "actions")
        val wheels = mutableListOf<Triple<String, String, String>>()
        override suspend fun setWheel(ref: String, snapshotId: String, value: String): WheelOutcome { wheels += Triple(ref, snapshotId, value); return wheel }
    }

    private fun call(args: String) = Part.ToolCall("c", "phone_set_wheel", args)
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test fun wheelIsSetInOneCall() = runTest {
        val screen = WheelScreen()
        val d = toolDispatcher(screen)
        val id = d.execute(Part.ToolCall("o", "phone_observe", "{}")).content["snapshotId"]?.jsonPrimitive?.content ?: "snap1"
        val r = d.execute(call("""{"ref":"@e1","snapshotId":"$id","value":" 40 "}""")).content
        assertEquals("true", r.s("ok"))
        assertEquals("40", r.s("value"))
        assertEquals("00", r.s("from"))
        assertEquals("actions", r.s("method"))
        assertEquals("20", r.s("attempts"))
        assertEquals("40", screen.wheels.single().third)

        screen.wheel = WheelOutcome(false, "23", "20", 4, "actions", error = "at_end")
        val end = d.execute(call("""{"ref":"@e1","snapshotId":"$id","value":"30"}""")).content
        assertEquals("false", end.s("ok"))
        assertEquals("wheel_at_end", end.s("error"))
        assertEquals("23", end.s("value"))
        assertEquals(FAILURE_MESSAGES.getValue("wheel_at_end"), end.s("message"))

        for ((port, code) in listOf("not_a_wheel" to "not_a_wheel", "unknown_value" to "wheel_unreadable", "not_moving" to "wheel_not_moving", "overshoot" to "wheel_not_reached")) {
            screen.wheel = WheelOutcome(false, "1", "1", 3, "drag", error = port)
            assertEquals(code, d.execute(call("""{"ref":"@e1","snapshotId":"$id","value":"30"}""")).content.s("error"))
        }

        screen.wheel = WheelOutcome(false, null, null, 0, "none", error = "ref_expired")
        val stale = d.execute(call("""{"ref":"@e1","snapshotId":"$id","value":"30"}""")).content
        assertEquals("ref_expired", stale.s("error"))
        assertFalse(stale.containsKey("method"))
    }

    @Test fun badArgumentsNeverReachThePhone() = runTest {
        val screen = WheelScreen()
        val d = toolDispatcher(screen)
        for (args in listOf("""{"ref":"@e1","snapshotId":"s","value":""}""", """{"value":"9"}""", """{"ref":"e1","snapshotId":"s","value":"9"}""")) {
            val r = d.execute(call(args)).content
            assertEquals(args, "invalid_arguments", r.s("error"))
        }
        assertTrue(screen.wheels.isEmpty())
    }
}
