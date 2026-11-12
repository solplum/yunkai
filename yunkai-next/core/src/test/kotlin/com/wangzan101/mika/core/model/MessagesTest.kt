package com.wangzan101.mika.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagesTest {
    @Test
    fun toolResultReferencesCall() {
        val call = Part.ToolCall("c1", "phone_tap", """{"x":1}""")
        val assistant = Message(Role.Assistant, listOf(call), Author.Mika)
        val result = Message(Role.Tool, listOf(Part.ToolResult("c1", "ok", false)), Author.Mika)
        val sent = assistant.parts.filterIsInstance<Part.ToolCall>().single()
        val got = result.parts.filterIsInstance<Part.ToolResult>().single()
        assertEquals(sent.id, got.callId)
    }

    @Test
    fun errorHierarchy() {
        val e: Throwable = ModelError.RateLimited(1000)
        assertTrue(e is ModelError)
        assertEquals(1000L, (e as ModelError.RateLimited).retryAfterMs)
    }
}
