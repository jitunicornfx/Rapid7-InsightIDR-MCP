package com.jitunicornfx.insightidr.mcp.tools

import com.jitunicornfx.insightidr.mcp.*
import io.ktor.http.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val CLOUDHOOK_STATUS = "The RRN of the cloud webhook."

/**
 * The fields of a webhook validation configuration, per the v1 spec's `OAuthConfig*Request` schemas.
 *
 * `type` is the schema's discriminator and `OAUTH` is its only value today — the spec wraps these
 * bodies in a single-member `oneOf`, which reads as a placeholder for further validation types. The
 * create form requires all four non-`scope` fields; the update form requires only `type`, so each
 * call site passes its own `required` list.
 */
private fun JsonObjectBuilder.oauthValidationProps() {
    stringParam("type", "The validation type discriminator.", enum = listOf("OAUTH"))
    stringParam("client_id", "The OAuth client id.")
    stringParam(
        "client_secret_grant_rrn",
        "RRN of the stored client-secret grant, e.g. rrn:credential:local:0123:grant:4567.",
    )
    stringParam("auth_service_url", "Token endpoint URL, e.g. https://auth.example.com/token.")
    stringParam("scope", "Optional OAuth scope, e.g. webhook:write.")
}

/** Registers the InsightIDR API v1 Cloud Webhooks tools. */
fun Server.registerCloudWebhookTools(client: Rapid7Client) {

    apiTool(
        name = "list_cloud_webhooks",
        description = "List configured cloud webhooks (API v1).",
        readOnly = true,
        inputSchema = toolSchema {
            pagingParams("Page size (max 100). Defaults to 10.", maxSize = 100, defaultSize = 10)
        },
    ) { args ->
        client.requestV1(HttpMethod.Get, "/idr/v1/cloud-webhooks", query = pagingQuery(args)).toToolResult()
    }

    apiTool(
        name = "get_cloud_webhook",
        description = "Get a cloud webhook by its RRN (API v1).",
        readOnly = true,
        inputSchema = toolSchema("webhook_rrn") { stringParam("webhook_rrn", CLOUDHOOK_STATUS) },
    ) { args ->
        val rrn = args.requireString("webhook_rrn")
        client.requestV1(HttpMethod.Get, "/idr/v1/cloud-webhooks/${seg(rrn)}").toToolResult()
    }

    apiTool(
        name = "create_cloud_webhook",
        description = "Create a cloud webhook (API v1).",
        inputSchema = toolSchema("name", "url") {
            stringParam("name", "The name of the webhook.")
            stringParam("url", "The URL of the webhook endpoint.")
            objectParam(
                "validation_config",
                "Optional validation configuration for the webhook. OAUTH is currently the only " +
                    "supported type. It can also be added later with add_cloud_webhook_validation.",
                required = listOf("type", "client_id", "client_secret_grant_rrn", "auth_service_url"),
            ) { oauthValidationProps() }
        },
    ) { args ->
        val body = buildJsonObject {
            put("name", args.requireString("name"))
            put("url", args.requireString("url"))
            putOpt("validation_config", args.objectOrNull("validation_config"))
        }
        client.requestV1(HttpMethod.Post, "/idr/v1/cloud-webhooks", jsonBody = body).toToolResult()
    }

    apiTool(
        name = "update_cloud_webhook",
        description = "Update a cloud webhook's name and/or URL (API v1).",
        inputSchema = toolSchema("webhook_rrn") {
            stringParam("webhook_rrn", CLOUDHOOK_STATUS)
            stringParam("name", "New name.")
            stringParam("url", "New endpoint URL.")
        },
    ) { args ->
        val rrn = args.requireString("webhook_rrn")
        val body = buildJsonObject {
            putOpt("name", args.stringOrNull("name"))
            putOpt("url", args.stringOrNull("url"))
        }
        client.requestV1(HttpMethod.Patch, "/idr/v1/cloud-webhooks/${seg(rrn)}", jsonBody = body).toToolResult()
    }

    apiTool(
        name = "delete_cloud_webhook",
        description = "Delete a cloud webhook by its RRN (API v1).",
        destructive = true,
        inputSchema = toolSchema("webhook_rrn") {
            stringParam(
                "webhook_rrn",
                "The RRN of the cloud webhook to delete."
            )
        },
    ) { args ->
        val rrn = args.requireString("webhook_rrn")
        client.requestV1(HttpMethod.Delete, "/idr/v1/cloud-webhooks/${seg(rrn)}").toToolResult()
    }

    apiTool(
        name = "test_cloud_webhook",
        description = "Trigger a test event for a cloud webhook (API v1).",
        inputSchema = toolSchema("webhook_rrn") { stringParam("webhook_rrn", "The RRN of the cloud webhook to test.") },
    ) { args ->
        val rrn = args.requireString("webhook_rrn")
        client.requestV1(HttpMethod.Post, "/idr/v1/cloud-webhooks/${seg(rrn)}/test").toToolResult()
    }

    apiTool(
        name = "replay_cloud_webhook_events",
        description = "Replay events for a cloud webhook, by explicit event ids or a time window " +
            "(API v1). Supply at least one of event_ids, start_time or end_time. Accepted " +
            "asynchronously — a 202 means the replay was queued, not that it finished.",
        inputSchema = toolSchema("webhook_rrn") {
            stringParam("webhook_rrn", CLOUDHOOK_STATUS)
            stringArrayParam("event_ids", "List of event ids to replay. At most 100, and must be unique.", maxItems = 100)
            stringParam(
                "start_time",
                "ISO-8601 UTC timestamp to replay events from (inclusive). Cannot be more than 3 days " +
                    "in the past. On its own it means a 60-second window starting here; omitted with " +
                    "an end_time, it defaults to 60 seconds before it.",
            )
            stringParam(
                "end_time",
                "ISO-8601 UTC timestamp to replay events to (inclusive). Defaults to now. On its own " +
                    "it means a 60-second window ending here.",
            )
        },
    ) { args ->
        val rrn = args.requireString("webhook_rrn")
        val body = buildJsonObject {
            putOpt("event_ids", args.arrayOrNull("event_ids"))
            putOpt("start_time", args.stringOrNull("start_time"))
            putOpt("end_time", args.stringOrNull("end_time"))
        }
        client.requestV1(HttpMethod.Post, "/idr/v1/cloud-webhooks/${seg(rrn)}/replay", jsonBody = body).toToolResult()
    }

    apiTool(
        name = "add_cloud_webhook_validation",
        description = "Add a validation configuration to a cloud webhook (API v1). The config is required.",
        inputSchema = toolSchema("webhook_rrn", "validation_config") {
            stringParam("webhook_rrn", CLOUDHOOK_STATUS)
            objectParam(
                "validation_config",
                "The validation configuration object. OAUTH is currently the only supported type.",
                required = listOf("type", "client_id", "client_secret_grant_rrn", "auth_service_url"),
            ) { oauthValidationProps() }
        },
    ) { args ->
        val rrn = args.requireString("webhook_rrn")
        val body: JsonObject = args.objectOrNull("validation_config")
            ?: throw IllegalArgumentException("Missing required parameter 'validation_config'")
        client.requestV1(HttpMethod.Post, "/idr/v1/cloud-webhooks/${seg(rrn)}/validation", jsonBody = body).toToolResult()
    }

    apiTool(
        name = "update_cloud_webhook_validation",
        description = "Update the validation configuration of a cloud webhook (API v1). The config is required.",
        inputSchema = toolSchema("webhook_rrn", "validation_config") {
            stringParam("webhook_rrn", CLOUDHOOK_STATUS)
            objectParam(
                "validation_config",
                "The updated validation configuration. Only the type discriminator is required on an " +
                    "update; supply just the fields being changed.",
                required = listOf("type"),
            ) { oauthValidationProps() }
        },
    ) { args ->
        val rrn = args.requireString("webhook_rrn")
        val body: JsonObject = args.objectOrNull("validation_config")
            ?: throw IllegalArgumentException("Missing required parameter 'validation_config'")
        client.requestV1(HttpMethod.Patch, "/idr/v1/cloud-webhooks/${seg(rrn)}/validation", jsonBody = body)
            .toToolResult()
    }

    apiTool(
        name = "delete_cloud_webhook_validation",
        description = "Remove the validation configuration from a cloud webhook (API v1).",
        destructive = true,
        inputSchema = toolSchema("webhook_rrn") { stringParam("webhook_rrn", CLOUDHOOK_STATUS) },
    ) { args ->
        val rrn = args.requireString("webhook_rrn")
        client.requestV1(HttpMethod.Delete, "/idr/v1/cloud-webhooks/${seg(rrn)}/validation").toToolResult()
    }
}
