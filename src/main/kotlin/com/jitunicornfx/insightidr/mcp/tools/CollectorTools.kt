package com.jitunicornfx.insightidr.mcp.tools

import com.jitunicornfx.insightidr.mcp.*
import io.ktor.http.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Registers the InsightIDR v1 Collectors tools. */
fun Server.registerCollectorTools(client: Rapid7Client) {

    apiTool(
        name = "add_collector",
        description = "Register a new InsightIDR Collector (API v1).",
        inputSchema = toolSchema("name", "key") {
            stringParam("name", "A unique name for the new collector.")
            stringParam("key", "The unique registration key (a valid UUID) for the collector.")
            stringParam("deployment_type", "Optional indication of how the collector is deployed.")
        },
    ) { args ->
        val body = buildJsonObject {
            put("name", args.requireString("name"))
            put("key", args.requireString("key"))
            putOpt("deployment_type", args.stringOrNull("deployment_type"))
        }
        client.request(HttpMethod.Post, "/idr/v1/collectors", jsonBody = body).toToolResult()
    }
}
