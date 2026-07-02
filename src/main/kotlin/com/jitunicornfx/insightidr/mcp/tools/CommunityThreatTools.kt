package com.jitunicornfx.insightidr.mcp.tools

import com.jitunicornfx.insightidr.mcp.*
import io.ktor.http.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.buildJsonObject

private fun contentTypeForFormat(format: String): ContentType = when (format.trim().lowercase()) {
    "json" -> ContentType.Application.Json
    "csv" -> ContentType.Text.CSV
    "stix_xml" -> ContentType.Text.Xml
    else -> throw IllegalArgumentException("Unsupported indicator format '$format'. Use json, csv, or stix_xml.")
}

/** Registers the InsightIDR v1 Community Threats tools (custom threat feeds and their indicators). */
fun Server.registerCommunityThreatTools(client: Rapid7Client) {

    apiTool(
        name = "create_community_threat",
        description = "Create a Community Threat (custom threat) (API v1). Provide the request body as a JSON string " +
                "matching the InsightIDR Community Threat schema, e.g. " +
                "{\"threat\":{\"note\":\"my threat\",\"indicators\":{\"ips\":[\"1.2.3.4\"]}}}.",
        inputSchema = toolSchema("request_body") {
            stringParam("request_body", "The Community Threat definition, as a JSON string.")
        },
    ) { args ->
        val rawBody = args.requireString("request_body")
        client.request(
            HttpMethod.Post,
            "/idr/v1/customthreats",
            rawBody = rawBody,
            rawContentType = ContentType.Application.Json,
        ).toToolResult()
    }

    apiTool(
        name = "add_community_threat_indicators",
        description = "Add indicators to an existing Community Threat, in json, csv, or xml format (API v1).",
        inputSchema = toolSchema("key", "format", "indicators") {
            stringParam("key", "The key of the Community Threat.")
            stringParam("format", "The format of the indicators payload.", enum = listOf("json", "csv", "stix_xml"))
            stringParam("indicators", "The indicators payload, encoded in the chosen format.")
        },
    ) { args ->
        val key = args.requireString("key")
        val format = args.requireString("format")
        val indicators = args.requireString("indicators")
        client.request(
            HttpMethod.Post,
            "/idr/v1/customthreats/key/${seg(key)}/indicators/add",
            query = query("format" to format),
            rawBody = indicators,
            rawContentType = contentTypeForFormat(format),
        ).toToolResult()
    }

    apiTool(
        name = "replace_community_threat_indicators",
        description = "Replace all indicators for a Community Threat, in json, csv, or xml format (API v1).",
        inputSchema = toolSchema("key", "format", "indicators") {
            stringParam("key", "The key of the Community Threat.")
            stringParam("format", "The format of the indicators payload.", enum = listOf("json", "csv", "stix_xml"))
            stringParam("indicators", "The indicators payload, encoded in the chosen format.")
        },
    ) { args ->
        val key = args.requireString("key")
        val format = args.requireString("format")
        val indicators = args.requireString("indicators")
        client.request(
            HttpMethod.Post,
            "/idr/v1/customthreats/key/${seg(key)}/indicators/replace",
            query = query("format" to format),
            rawBody = indicators,
            rawContentType = contentTypeForFormat(format),
        ).toToolResult()
    }

    apiTool(
        name = "delete_community_threat",
        description = "Delete a Community Threat by key (API v1).",
        destructive = true,
        inputSchema = toolSchema("key") {
            stringParam("key", "The key of the Community Threat to delete.")
            stringParam("reason", "Optional reason for deletion.")
        },
    ) { args ->
        val key = args.requireString("key")
        val body = buildJsonObject { putOpt("reason", args.stringOrNull("reason")) }
        client.request(HttpMethod.Post, "/idr/v1/customthreats/key/${seg(key)}/delete", jsonBody = body).toToolResult()
    }
}
