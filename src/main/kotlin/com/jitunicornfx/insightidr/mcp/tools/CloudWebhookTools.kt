package com.jitunicornfx.insightidr.mcp.tools

import com.jitunicornfx.insightidr.mcp.Rapid7Client
import com.jitunicornfx.insightidr.mcp.apiTool
import com.jitunicornfx.insightidr.mcp.arrayOrNull
import com.jitunicornfx.insightidr.mcp.integerParam
import com.jitunicornfx.insightidr.mcp.objectOrNull
import com.jitunicornfx.insightidr.mcp.objectParam
import com.jitunicornfx.insightidr.mcp.pagingQuery
import com.jitunicornfx.insightidr.mcp.putOpt
import com.jitunicornfx.insightidr.mcp.requireString
import com.jitunicornfx.insightidr.mcp.seg
import com.jitunicornfx.insightidr.mcp.stringArrayParam
import com.jitunicornfx.insightidr.mcp.stringOrNull
import com.jitunicornfx.insightidr.mcp.stringParam
import com.jitunicornfx.insightidr.mcp.toToolResult
import com.jitunicornfx.insightidr.mcp.toolSchema
import io.ktor.http.HttpMethod
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Registers the InsightIDR v1 Cloud Webhooks tools. */
fun Server.registerCloudWebhookTools(client: Rapid7Client) {

    apiTool(
        name = "list_cloud_webhooks",
        description = "List configured cloud webhooks (API v1).",
        readOnly = true,
        inputSchema = toolSchema {
            integerParam("index", "Zero-based page index.")
            integerParam("size", "Page size.")
        },
    ) { args ->
        client.request(HttpMethod.Get, "/idr/v1/cloud-webhooks", query = pagingQuery(args)).toToolResult()
    }

    apiTool(
        name = "get_cloud_webhook",
        description = "Get a cloud webhook by its RRN (API v1).",
        readOnly = true,
        inputSchema = toolSchema("webhook_rrn") { stringParam("webhook_rrn", "The RRN of the cloud webhook.") },
    ) { args ->
        val rrn = args.requireString("webhook_rrn")
        client.request(HttpMethod.Get, "/idr/v1/cloud-webhooks/${seg(rrn)}").toToolResult()
    }

    apiTool(
        name = "create_cloud_webhook",
        description = "Create a cloud webhook (API v1).",
        inputSchema = toolSchema("name", "url") {
            stringParam("name", "The name of the webhook.")
            stringParam("url", "The URL of the webhook endpoint.")
            objectParam("validation_config", "Optional validation configuration object for the webhook.")
        },
    ) { args ->
        val body = buildJsonObject {
            put("name", args.requireString("name"))
            put("url", args.requireString("url"))
            putOpt("validation_config", args.objectOrNull("validation_config"))
        }
        client.request(HttpMethod.Post, "/idr/v1/cloud-webhooks", jsonBody = body).toToolResult()
    }

    apiTool(
        name = "update_cloud_webhook",
        description = "Update a cloud webhook's name and/or URL (API v1).",
        inputSchema = toolSchema("webhook_rrn") {
            stringParam("webhook_rrn", "The RRN of the cloud webhook.")
            stringParam("name", "New name.")
            stringParam("url", "New endpoint URL.")
        },
    ) { args ->
        val rrn = args.requireString("webhook_rrn")
        val body = buildJsonObject {
            putOpt("name", args.stringOrNull("name"))
            putOpt("url", args.stringOrNull("url"))
        }
        client.request(HttpMethod.Patch, "/idr/v1/cloud-webhooks/${seg(rrn)}", jsonBody = body).toToolResult()
    }

    apiTool(
        name = "delete_cloud_webhook",
        description = "Delete a cloud webhook by its RRN (API v1).",
        destructive = true,
        inputSchema = toolSchema("webhook_rrn") { stringParam("webhook_rrn", "The RRN of the cloud webhook to delete.") },
    ) { args ->
        val rrn = args.requireString("webhook_rrn")
        client.request(HttpMethod.Delete, "/idr/v1/cloud-webhooks/${seg(rrn)}").toToolResult()
    }

    apiTool(
        name = "test_cloud_webhook",
        description = "Trigger a test event for a cloud webhook (API v1).",
        inputSchema = toolSchema("webhook_rrn") { stringParam("webhook_rrn", "The RRN of the cloud webhook to test.") },
    ) { args ->
        val rrn = args.requireString("webhook_rrn")
        client.request(HttpMethod.Post, "/idr/v1/cloud-webhooks/${seg(rrn)}/test").toToolResult()
    }

    apiTool(
        name = "replay_cloud_webhook_events",
        description = "Replay events for a cloud webhook, by explicit event ids or a time window (API v1).",
        inputSchema = toolSchema("webhook_rrn") {
            stringParam("webhook_rrn", "The RRN of the cloud webhook.")
            stringArrayParam("event_ids", "List of event ids to replay.")
            stringParam("start_time", "ISO-8601 UTC timestamp to replay events from (inclusive).")
            stringParam("end_time", "ISO-8601 UTC timestamp to replay events to (inclusive).")
        },
    ) { args ->
        val rrn = args.requireString("webhook_rrn")
        val body = buildJsonObject {
            putOpt("event_ids", args.arrayOrNull("event_ids"))
            putOpt("start_time", args.stringOrNull("start_time"))
            putOpt("end_time", args.stringOrNull("end_time"))
        }
        client.request(HttpMethod.Post, "/idr/v1/cloud-webhooks/${seg(rrn)}/replay", jsonBody = body).toToolResult()
    }

    apiTool(
        name = "add_cloud_webhook_validation",
        description = "Add a validation configuration to a cloud webhook (API v1). The config is required.",
        inputSchema = toolSchema("webhook_rrn", "validation_config") {
            stringParam("webhook_rrn", "The RRN of the cloud webhook.")
            objectParam(
                "validation_config",
                "The validation configuration object. For OAuth, include type=\"OAUTH\", client_id, " +
                    "client_secret_grant_rrn, and auth_service_url.",
            )
        },
    ) { args ->
        val rrn = args.requireString("webhook_rrn")
        val body: JsonObject = args.objectOrNull("validation_config")
            ?: throw IllegalArgumentException("Missing required parameter 'validation_config'")
        client.request(HttpMethod.Post, "/idr/v1/cloud-webhooks/${seg(rrn)}/validation", jsonBody = body).toToolResult()
    }

    apiTool(
        name = "update_cloud_webhook_validation",
        description = "Update the validation configuration of a cloud webhook (API v1). The config is required.",
        inputSchema = toolSchema("webhook_rrn", "validation_config") {
            stringParam("webhook_rrn", "The RRN of the cloud webhook.")
            objectParam(
                "validation_config",
                "The updated validation configuration object. Must include the type discriminator (e.g. type=\"OAUTH\").",
            )
        },
    ) { args ->
        val rrn = args.requireString("webhook_rrn")
        val body: JsonObject = args.objectOrNull("validation_config")
            ?: throw IllegalArgumentException("Missing required parameter 'validation_config'")
        client.request(HttpMethod.Patch, "/idr/v1/cloud-webhooks/${seg(rrn)}/validation", jsonBody = body).toToolResult()
    }

    apiTool(
        name = "delete_cloud_webhook_validation",
        description = "Remove the validation configuration from a cloud webhook (API v1).",
        destructive = true,
        inputSchema = toolSchema("webhook_rrn") { stringParam("webhook_rrn", "The RRN of the cloud webhook.") },
    ) { args ->
        val rrn = args.requireString("webhook_rrn")
        client.request(HttpMethod.Delete, "/idr/v1/cloud-webhooks/${seg(rrn)}/validation").toToolResult()
    }
}
