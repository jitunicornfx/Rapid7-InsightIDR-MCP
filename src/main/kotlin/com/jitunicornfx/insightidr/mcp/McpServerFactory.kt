package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.tools.registerAttachmentTools
import com.jitunicornfx.insightidr.mcp.tools.registerCloudWebhookTools
import com.jitunicornfx.insightidr.mcp.tools.registerCollectorTools
import com.jitunicornfx.insightidr.mcp.tools.registerCommentTools
import com.jitunicornfx.insightidr.mcp.tools.registerCommunityThreatTools
import com.jitunicornfx.insightidr.mcp.tools.registerEntityTools
import com.jitunicornfx.insightidr.mcp.tools.registerHealthMetricTools
import com.jitunicornfx.insightidr.mcp.tools.registerInvestigationV1Tools
import com.jitunicornfx.insightidr.mcp.tools.registerInvestigationV2Tools
import com.jitunicornfx.insightidr.mcp.tools.registerSystemTools
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

const val SERVER_NAME = "rapid7-insightidr-mcp"
const val SERVER_VERSION = "0.1.0"

/**
 * Build a fully-configured MCP [Server] with every InsightIDR tool registered.
 * The provided [client] is shared by all tool handlers.
 */
fun buildInsightIdrServer(client: Rapid7Client): Server {
    val server = Server(
        Implementation(name = SERVER_NAME, version = SERVER_VERSION),
        ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
            ),
        ),
    )

    server.registerSystemTools(client)
    server.registerInvestigationV2Tools(client)
    server.registerInvestigationV1Tools(client)
    server.registerEntityTools(client)
    server.registerCommentTools(client)
    server.registerAttachmentTools(client)
    server.registerCloudWebhookTools(client)
    server.registerCommunityThreatTools(client)
    server.registerCollectorTools(client)
    server.registerHealthMetricTools(client)

    return server
}
