package com.jitunicornfx.insightidr.mcp.tools

import com.jitunicornfx.insightidr.mcp.*
import io.ktor.http.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Registers the legacy InsightIDR API v1 Investigations tools. Prefer the v2 tools where possible;
 * these are provided for parity and for environments that still rely on the v1 endpoints.
 */
fun Server.registerInvestigationV1Tools(client: Rapid7Client) {

    apiTool(
        name = "list_investigations_v1",
        description = "List InsightIDR investigations using the legacy v1 API. Prefer 'list_investigations' (v2).",
        readOnly = true,
        inputSchema = toolSchema {
            integerParam("index", "Zero-based page index.")
            integerParam("size", "Page size (max 1000). Defaults to 20.")
            stringParam("statuses", "Comma-separated statuses (OPEN, INVESTIGATING, CLOSED).")
            stringParam("start_time", "ISO-8601 timestamp; only investigations created at/after this time.")
            stringParam("end_time", "ISO-8601 timestamp; only investigations created at/before this time.")
        },
    ) { args ->
        client.requestV1(
            HttpMethod.Get,
            "/idr/v1/investigations",
            query = query(
                "index" to args.intOrNull("index"),
                "size" to args.intOrNull("size"),
                "statuses" to args.stringOrNull("statuses"),
                "start_time" to args.stringOrNull("start_time"),
                "end_time" to args.stringOrNull("end_time"),
            ),
        ).toToolResult()
    }

    apiTool(
        name = "set_investigation_status_v1",
        description = "Set an investigation's status using the legacy v1 API.",
        inputSchema = toolSchema("id", "status") {
            stringParam("id", "The investigation id or RRN.")
            stringParam("status", "The new status.", enum = listOf("OPEN", "INVESTIGATING", "CLOSED"))
        },
    ) { args ->
        val id = args.requireString("id")
        val status = args.requireString("status")
        client.requestV1(HttpMethod.Put, "/idr/v1/investigations/${seg(id)}/status/${seg(status)}").toToolResult()
    }

    apiTool(
        name = "assign_investigation_v1",
        description = "Assign a user to an investigation by email using the legacy v1 API.",
        inputSchema = toolSchema("id", "user_email_address") {
            stringParam("id", "The investigation id or RRN.")
            stringParam("user_email_address", "Email address of the user to assign.")
        },
    ) { args ->
        val id = args.requireString("id")
        val body = buildJsonObject { put("user_email_address", args.requireString("user_email_address")) }
        client.requestV1(HttpMethod.Put, "/idr/v1/investigations/${seg(id)}/assignee", jsonBody = body).toToolResult()
    }

    apiTool(
        name = "bulk_close_investigations_v1",
        description = "Close investigations in bulk using the legacy v1 API.",
        inputSchema = toolSchema("source", "from", "to") {
            stringParam(
                "source",
                "Investigation source whose investigations should be closed.",
                enum = listOf("ALERT", "MANUAL", "HUNT")
            )
            stringParam("from", "ISO-8601 timestamp; close investigations created at/after this time.")
            stringParam("to", "ISO-8601 timestamp; close investigations created at/before this time.")
            stringParam("alert_type", "Category of alert types to close (required for some sources).")
            stringParam("detection_rule_rrn", "Only close investigations associated with this detection rule RRN.")
            integerParam("max_investigations_to_close", "Optional maximum number of investigations to close.")
        },
    ) { args ->
        val body = buildJsonObject {
            put("source", args.requireString("source"))
            put("from", args.requireString("from"))
            put("to", args.requireString("to"))
            putOpt("alert_type", args.stringOrNull("alert_type"))
            putOpt("detection_rule_rrn", args.stringOrNull("detection_rule_rrn"))
            putOpt("max_investigations_to_close", args.intOrNull("max_investigations_to_close"))
        }
        client.requestV1(HttpMethod.Post, "/idr/v1/investigations/bulk_close", jsonBody = body).toToolResult()
    }
}
