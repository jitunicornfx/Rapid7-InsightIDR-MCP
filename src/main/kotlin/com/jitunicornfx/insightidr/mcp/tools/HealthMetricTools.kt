package com.jitunicornfx.insightidr.mcp.tools

import com.jitunicornfx.insightidr.mcp.*
import io.ktor.http.*
import io.modelcontextprotocol.kotlin.sdk.server.Server

/** Registers the InsightIDR API v1 Health Metrics tool. */
fun Server.registerHealthMetricTools(client: Rapid7Client) {

    apiTool(
        name = "get_health_metrics",
        description = "Retrieve InsightIDR health metrics for the organization (e.g. collector/event source health) (API v1).",
        readOnly = true,
        inputSchema = toolSchema {
            integerParam("index", "Zero-based page index.")
            integerParam("size", "Page size.")
            stringParam("resourceTypes", "Comma-separated resource types to filter metrics by.")
            stringParam("orgId", "Optional organization id to scope the metrics to.")
        },
    ) { args ->
        client.request(
            HttpMethod.Get,
            "/idr/v1/health-metrics",
            query = query(
                "index" to args.intOrNull("index"),
                "size" to args.intOrNull("size"),
                "resourceTypes" to args.stringOrNull("resourceTypes"),
                "orgId" to args.stringOrNull("orgId"),
            ),
        ).toToolResult()
    }
}
