package com.wangzan101.mika.core.assembly

import com.wangzan101.mika.core.cap.mcp.McpCapability
import com.wangzan101.mika.core.cap.mcp.McpConnection
import com.wangzan101.mika.core.run.RunSpec

internal class McpClientProvider(private val connection: McpConnection) : CapabilityProvider {
    override val id: String = "mcp:${connection.endpoint.serverId}"
    override fun open(spec: RunSpec) = McpCapability(connection, id)
}
