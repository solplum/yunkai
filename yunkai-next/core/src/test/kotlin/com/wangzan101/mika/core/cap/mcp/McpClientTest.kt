package com.wangzan101.mika.core.cap.mcp

import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.ConfirmDecision
import com.wangzan101.mika.core.action.Decision
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.observation.Trust
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpClientTest {
    private class FakeEndpoint : McpEndpoint {
        override val serverId = "demo"
        var offered = listOf(McpToolDescriptor("search", "Search", buildJsonObject { put("type", "object") }, McpAnnotations(readOnlyHint = true)))
        var result = McpResult("found")
        var calls = 0
        override fun tools() = offered
        override suspend fun call(name: String, arguments: JsonObject): McpResult { calls++; return result }
    }

    @Test fun annotationRiskDefaultsToWriteAndEscalatesOpenWorldAndDestructive() {
        assertEquals(ActionRisk.Change, mcpApprovalRisk(McpAnnotations()).level)
        assertEquals(ActionRisk.Read, mcpApprovalRisk(McpAnnotations(readOnlyHint = true)).level)
        assertEquals(ActionRisk.External, mcpApprovalRisk(McpAnnotations(readOnlyHint = true, openWorldHint = true)).level)
        assertEquals(ActionRisk.Irreversible, mcpApprovalRisk(McpAnnotations(destructiveHint = true)).level)
    }

    @Test fun runCatalogIsFrozenAndCapabilitySubsetFiltersMcp() {
        val endpoint = FakeEndpoint()
        val spec = RunSpec(RunId("r"), "task", "thread")
        val tools = toolDispatcher(FakeScreen(), runSpec = spec, mcpServers = listOf(McpConnection(endpoint)))
        val first = tools.definitions().map { it.name }
        assertTrue("mcp_demo__search" in first)
        assertEquals(ActionRisk.Change, tools.approvalRisk("mcp_demo__search")?.level)
        val trusted = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint, trust = McpTrust.TrustAnnotations)))
        assertEquals(ActionRisk.Read, trusted.approvalRisk("mcp_demo__search")?.level)
        endpoint.offered = listOf(McpToolDescriptor("changed", "Changed", buildJsonObject {}))
        assertEquals(first, tools.definitions().map { it.name })
        val subset = toolDispatcher(FakeScreen(), runSpec = spec.copy(capabilities = setOf("phone")), mcpServers = listOf(McpConnection(endpoint)))
        assertFalse(subset.definitions().any { it.name.startsWith("mcp_") })
    }

    @Test fun largeResultUsesReferenceAndUntrustedObservation() = runBlocking {
        val endpoint = FakeEndpoint().apply { result = McpResult("x".repeat(40)) }
        var saved: String? = null
        val store = McpResultStore { _, _, body -> saved = body; "result://one" }
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint, store, previewChars = 10)))
        val result = tools.execute(Part.ToolCall("c", "mcp_demo__search", "{}"), ActionPass())
        assertEquals("x".repeat(40), saved)
        assertEquals("result://one", result.content["fullResult"]?.toString()?.trim('"'))
        assertTrue(result.content["text"].toString().contains("<untrusted>"))
        assertEquals(Trust.Untrusted, result.observations.single().trust)
        assertEquals(10, result.observations.single().text.length)
    }

    @Test fun unattendedExternalMcpCallIsNotAskedAndNotCalledByTheJudge() = runBlocking {
        val endpoint = FakeEndpoint().apply {
            offered = listOf(McpToolDescriptor("send", "Send", buildJsonObject { put("type", "object") },
                McpAnnotations(openWorldHint = true)))
        }
        val tools = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint)))
        // 不花钱的对外调用:无人值守也直接做(只记审批),不在判定时就调服务端
        val decision = ConfirmDecision(tools, null, provenance = RunProvenance(RunOrigin.Trigger, Attendance.Unattended))
            .judge("mcp_demo__send", buildJsonObject {}, Origin.Model(1))
        assertEquals(Decision.Clear, decision)
        assertEquals(0, endpoint.calls)
    }
}
