package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.tools.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

const val SERVER_NAME = "rapid7-insightidr-mcp"
const val SERVER_VERSION = "0.1.5"

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
    server.registerAlertTools(client)
    server.registerAlertActionTools(client)
    server.registerAlertProcessTreeTools(client)
    server.registerEntityTools(client)
    server.registerCommentTools(client)
    server.registerAttachmentTools(client)
    server.registerCloudWebhookTools(client)
    server.registerCommunityThreatTools(client)
    server.registerCollectorTools(client)
    server.registerHealthMetricTools(client)

    // Log Search API (queries, saved queries, logs/log sets, usage, exports,
    // LEQL variables, pre-computed queries, detection rules, audit logs).
    server.registerLogSearchQueryTools(client)
    server.registerLogSearchManagementTools(client)
    server.registerLogSearchVariableTools(client)
    server.registerLogSearchDetectionRuleTools(client)
    server.registerLogSearchAuditTools(client)

    return server
}
