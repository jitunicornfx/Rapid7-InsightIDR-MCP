package com.jitunicornfx.insightidr.mcp.tools

import com.jitunicornfx.insightidr.mcp.Rapid7Client
import com.jitunicornfx.insightidr.mcp.apiTool
import com.jitunicornfx.insightidr.mcp.toToolResult
import io.ktor.http.*
import io.modelcontextprotocol.kotlin.sdk.server.Server

/** Registers connectivity / diagnostics tools for InsightIDR connectivity. */
fun Server.registerSystemTools(client: Rapid7Client) {

    apiTool(
        name = "validate_connection",
        description = "Validate connectivity and authentication to the Insight platform. Calls the platform " +
                "/validate endpoint and returns the organization associated with the configured API key. " +
                "Use this first to confirm the API key and region are correct.",
        readOnly = true,
    ) { _ ->
        client.request(HttpMethod.Get, "/validate").toToolResult()
    }
}
