package com.jitunicornfx.insightidr.mcp.tools

import com.jitunicornfx.insightidr.mcp.*
import com.jitunicornfx.insightidr.mcp.Rapid7Client.ApiBase
import io.ktor.http.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private val TARGET_TYPES = listOf("mailto", "pagerduty", "webhook", "slack", "sqs")

private const val TAG_SHAPE =
    "Shape: { name, type ('Alert' or 'AlertNotify'), sub_type ('InactivityAlert'/'AnomalyAlert' for AlertNotify), " +
        "sources: [{id: logUuid}], leql: {statement}, description?, patterns?, actions?, labels?, priority?, " +
        "and for inactivity/anomaly rules the timeframe/scheduled_query/threshold fields }."

/** Log Search API — Basic Detection Rules (tags), their notifications, targets, and labels. */
fun Server.registerLogSearchDetectionRuleTools(client: Rapid7Client) {

    // ------------------------------------------------------------------
    // Basic detection rules (management/tags)
    // ------------------------------------------------------------------

    apiTool(
        name = "logsearch_list_detection_rules",
        description = "List all basic detection rules (Log Search API, management/tags). These are the " +
            "pattern/inactivity/anomaly detection rules attached to logs.",
        readOnly = true,
    ) { _ ->
        client.request(HttpMethod.Get, "/management/tags", base = ApiBase.LOG_SEARCH).toToolResult()
    }

    apiTool(
        name = "logsearch_get_detection_rule",
        description = "Retrieve a basic detection rule by id (Log Search API).",
        readOnly = true,
        inputSchema = toolSchema("rule_id") { stringParam("rule_id", "The id of the basic detection rule.") },
    ) { args ->
        client.request(
            HttpMethod.Get,
            "/management/tags/${seg(args.requireString("rule_id"))}",
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_create_detection_rule",
        description = "Create a basic detection rule (Log Search API). Provide the full 'tag' object. $TAG_SHAPE",
        inputSchema = toolSchema("tag") {
            objectParam("tag", "The detection rule definition object. $TAG_SHAPE")
        },
    ) { args ->
        val tag = args.objectOrNull("tag")
            ?: throw IllegalArgumentException("Missing required parameter 'tag'")
        val body = buildJsonObject { put("tag", tag) }
        client.request(HttpMethod.Post, "/management/tags", jsonBody = body, base = ApiBase.LOG_SEARCH).toToolResult()
    }

    apiTool(
        name = "logsearch_replace_detection_rule",
        description = "Replace a basic detection rule (PUT, Log Search API). Provide the full 'tag' object. $TAG_SHAPE",
        inputSchema = toolSchema("rule_id", "tag") {
            stringParam("rule_id", "The id of the detection rule to replace.")
            objectParam("tag", "The full replacement detection rule object. $TAG_SHAPE")
        },
    ) { args ->
        val tag = args.objectOrNull("tag")
            ?: throw IllegalArgumentException("Missing required parameter 'tag'")
        val body = buildJsonObject { put("tag", tag) }
        client.request(
            HttpMethod.Put,
            "/management/tags/${seg(args.requireString("rule_id"))}",
            jsonBody = body,
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_update_detection_rule",
        description = "Modify parts of a basic detection rule (PATCH, Log Search API). Only provided fields change.",
        inputSchema = toolSchema("rule_id", "tag") {
            stringParam("rule_id", "The id of the detection rule to modify.")
            objectParam("tag", "Partial detection rule object with only the fields to change.")
        },
    ) { args ->
        val tag = args.objectOrNull("tag")
            ?: throw IllegalArgumentException("Missing required parameter 'tag'")
        val body = buildJsonObject { put("tag", tag) }
        client.request(
            HttpMethod.Patch,
            "/management/tags/${seg(args.requireString("rule_id"))}",
            jsonBody = body,
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_delete_detection_rule",
        description = "Delete a basic detection rule by id (Log Search API).",
        destructive = true,
        inputSchema = toolSchema("rule_id") { stringParam("rule_id", "The id of the detection rule to delete.") },
    ) { args ->
        client.request(
            HttpMethod.Delete,
            "/management/tags/${seg(args.requireString("rule_id"))}",
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    // ------------------------------------------------------------------
    // Notifications (management/actions)
    // ------------------------------------------------------------------

    apiTool(
        name = "logsearch_list_notifications",
        description = "List all detection-rule notifications (Log Search API, management/actions).",
        readOnly = true,
    ) { _ ->
        client.request(HttpMethod.Get, "/management/actions", base = ApiBase.LOG_SEARCH).toToolResult()
    }

    apiTool(
        name = "logsearch_get_notification",
        description = "Retrieve a detection-rule notification by id (Log Search API).",
        readOnly = true,
        inputSchema = toolSchema("notification_id") { stringParam("notification_id", "The id of the notification.") },
    ) { args ->
        client.request(
            HttpMethod.Get,
            "/management/actions/${seg(args.requireString("notification_id"))}",
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_create_notification",
        description = "Create a detection-rule notification (Log Search API). Provide the 'action' object: " +
            "{ enabled, type: 'Alert', min_matches_count, min_matches_period, min_report_count, " +
            "min_report_period (Hour/Day/Minute/5Minute/10Minute/15Minute/30Minute), targets: [...] }.",
        inputSchema = toolSchema("action") {
            objectParam("action", "The notification definition object (see tool description for shape).")
        },
    ) { args ->
        val action = args.objectOrNull("action")
            ?: throw IllegalArgumentException("Missing required parameter 'action'")
        val body = buildJsonObject { put("action", action) }
        client.request(HttpMethod.Post, "/management/actions", jsonBody = body, base = ApiBase.LOG_SEARCH).toToolResult()
    }

    apiTool(
        name = "logsearch_replace_notification",
        description = "Replace a detection-rule notification (PUT, Log Search API).",
        inputSchema = toolSchema("notification_id", "action") {
            stringParam("notification_id", "The id of the notification to replace.")
            objectParam("action", "The full replacement notification object.")
        },
    ) { args ->
        val action = args.objectOrNull("action")
            ?: throw IllegalArgumentException("Missing required parameter 'action'")
        val body = buildJsonObject { put("action", action) }
        client.request(
            HttpMethod.Put,
            "/management/actions/${seg(args.requireString("notification_id"))}",
            jsonBody = body,
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_update_notification",
        description = "Modify parts of a detection-rule notification (PATCH, Log Search API).",
        inputSchema = toolSchema("notification_id", "action") {
            stringParam("notification_id", "The id of the notification to modify.")
            objectParam("action", "Partial notification object with only the fields to change.")
        },
    ) { args ->
        val action = args.objectOrNull("action")
            ?: throw IllegalArgumentException("Missing required parameter 'action'")
        val body = buildJsonObject { put("action", action) }
        client.request(
            HttpMethod.Patch,
            "/management/actions/${seg(args.requireString("notification_id"))}",
            jsonBody = body,
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_delete_notification",
        description = "Delete a detection-rule notification by id (Log Search API).",
        destructive = true,
        inputSchema = toolSchema("notification_id") { stringParam("notification_id", "The id of the notification to delete.") },
    ) { args ->
        client.request(
            HttpMethod.Delete,
            "/management/actions/${seg(args.requireString("notification_id"))}",
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_list_notification_targets",
        description = "List the targets attached to a detection-rule notification (Log Search API).",
        readOnly = true,
        inputSchema = toolSchema("notification_id") { stringParam("notification_id", "The id of the notification.") },
    ) { args ->
        client.request(
            HttpMethod.Get,
            "/management/actions/${seg(args.requireString("notification_id"))}/targets",
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_update_notification_targets",
        description = "Modify the targets attached to a detection-rule notification (PATCH, Log Search API). " +
            "Provide the 'target' object: { name, description, type (${TARGET_TYPES.joinToString("/")}), params_set }.",
        inputSchema = toolSchema("notification_id", "target") {
            stringParam("notification_id", "The id of the notification.")
            objectParam("target", "The target definition to attach (see tool description for shape).")
        },
    ) { args ->
        val target = args.objectOrNull("target")
            ?: throw IllegalArgumentException("Missing required parameter 'target'")
        val body = buildJsonObject { put("target", target) }
        client.request(
            HttpMethod.Patch,
            "/management/actions/${seg(args.requireString("notification_id"))}/targets",
            jsonBody = body,
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    // ------------------------------------------------------------------
    // Targets (management/targets)
    // ------------------------------------------------------------------

    apiTool(
        name = "logsearch_list_targets",
        description = "List all notification targets (Log Search API, management/targets).",
        readOnly = true,
    ) { _ ->
        client.request(HttpMethod.Get, "/management/targets", base = ApiBase.LOG_SEARCH).toToolResult()
    }

    apiTool(
        name = "logsearch_get_target",
        description = "Retrieve a notification target by id (Log Search API).",
        readOnly = true,
        inputSchema = toolSchema("target_id") { stringParam("target_id", "The id of the notification target.") },
    ) { args ->
        client.request(
            HttpMethod.Get,
            "/management/targets/${seg(args.requireString("target_id"))}",
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_create_target",
        description = "Create a notification target (Log Search API). params_set depends on type: " +
            "mailto={direct}, pagerduty={service_key}, slack/webhook={url}, sqs={destination_app, destination_queue}.",
        inputSchema = toolSchema("name", "type", "params_set") {
            stringParam("name", "The name of the target.")
            stringParam("description", "Optional description of the target.")
            stringParam("type", "The type of target.", enum = TARGET_TYPES)
            objectParam("params_set", "Type-specific parameters (see tool description).")
            objectParam("alert_content_set", "Optional content flags, e.g. {\"le_context\": \"true\", \"le_trigger_event\": \"true\"}.")
            objectParam("user_data", "Optional auxiliary key-value data.")
        },
    ) { args ->
        val body = buildJsonObject {
            putJsonObject("target") {
                put("name", args.requireString("name"))
                putOpt("description", args.stringOrNull("description"))
                put("type", args.requireString("type"))
                put("params_set", args.objectOrNull("params_set")
                    ?: throw IllegalArgumentException("Missing required parameter 'params_set'"))
                // The spec marks these required; default to empty objects (as Rapid7's own samples do).
                put("alert_content_set", args.objectOrNull("alert_content_set") ?: buildJsonObject {})
                put("user_data", args.objectOrNull("user_data") ?: buildJsonObject {})
            }
        }
        client.request(HttpMethod.Post, "/management/targets", jsonBody = body, base = ApiBase.LOG_SEARCH).toToolResult()
    }

    apiTool(
        name = "logsearch_replace_target",
        description = "Replace a notification target (PUT, Log Search API).",
        inputSchema = toolSchema("target_id", "name", "type", "params_set") {
            stringParam("target_id", "The id of the target to replace.")
            stringParam("name", "The name of the target.")
            stringParam("description", "Optional description of the target.")
            stringParam("type", "The type of target.", enum = TARGET_TYPES)
            objectParam("params_set", "Type-specific parameters.")
            objectParam("alert_content_set", "Optional content flags.")
            objectParam("user_data", "Optional auxiliary key-value data.")
        },
    ) { args ->
        val body = buildJsonObject {
            putJsonObject("target") {
                put("name", args.requireString("name"))
                putOpt("description", args.stringOrNull("description"))
                put("type", args.requireString("type"))
                put("params_set", args.objectOrNull("params_set")
                    ?: throw IllegalArgumentException("Missing required parameter 'params_set'"))
                // The spec marks these required; default to empty objects (as Rapid7's own samples do).
                put("alert_content_set", args.objectOrNull("alert_content_set") ?: buildJsonObject {})
                put("user_data", args.objectOrNull("user_data") ?: buildJsonObject {})
            }
        }
        client.request(
            HttpMethod.Put,
            "/management/targets/${seg(args.requireString("target_id"))}",
            jsonBody = body,
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_delete_target",
        description = "Delete a notification target by id (Log Search API).",
        destructive = true,
        inputSchema = toolSchema("target_id") { stringParam("target_id", "The id of the target to delete.") },
    ) { args ->
        client.request(
            HttpMethod.Delete,
            "/management/targets/${seg(args.requireString("target_id"))}",
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    // ------------------------------------------------------------------
    // Labels (management/labels)
    // ------------------------------------------------------------------

    apiTool(
        name = "logsearch_list_labels",
        description = "List all labels (Log Search API). Labels mark log entries and can filter queries.",
        readOnly = true,
    ) { _ ->
        client.request(HttpMethod.Get, "/management/labels", base = ApiBase.LOG_SEARCH).toToolResult()
    }

    apiTool(
        name = "logsearch_get_label",
        description = "Retrieve a label by id (Log Search API).",
        readOnly = true,
        inputSchema = toolSchema("label_id") { stringParam("label_id", "The id of the label.") },
    ) { args ->
        client.request(
            HttpMethod.Get,
            "/management/labels/${seg(args.requireString("label_id"))}",
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_create_label",
        description = "Create a label (Log Search API).",
        inputSchema = toolSchema("name", "color") {
            stringParam("name", "The name of the label.")
            stringParam("color", "The color of the label as a HEX code, e.g. 3498db.")
        },
    ) { args ->
        val body = buildJsonObject {
            putJsonObject("label") {
                put("name", args.requireString("name"))
                put("color", args.requireString("color"))
            }
        }
        client.request(HttpMethod.Post, "/management/labels", jsonBody = body, base = ApiBase.LOG_SEARCH).toToolResult()
    }

    apiTool(
        name = "logsearch_replace_label",
        description = "Replace a label (PUT, Log Search API). Both name and color are required.",
        inputSchema = toolSchema("label_id", "name", "color") {
            stringParam("label_id", "The id of the label to replace.")
            stringParam("name", "The new name of the label.")
            stringParam("color", "The new color of the label as a HEX code.")
        },
    ) { args ->
        val body = buildJsonObject {
            putJsonObject("label") {
                put("name", args.requireString("name"))
                put("color", args.requireString("color"))
            }
        }
        client.request(
            HttpMethod.Put,
            "/management/labels/${seg(args.requireString("label_id"))}",
            jsonBody = body,
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_update_label",
        description = "Update a label's name and/or color (PATCH, Log Search API).",
        inputSchema = toolSchema("label_id") {
            stringParam("label_id", "The id of the label to update.")
            stringParam("name", "New name for the label.")
            stringParam("color", "New color for the label as a HEX code.")
        },
    ) { args ->
        val body = buildJsonObject {
            putJsonObject("label") {
                putOpt("name", args.stringOrNull("name"))
                putOpt("color", args.stringOrNull("color"))
            }
        }
        client.request(
            HttpMethod.Patch,
            "/management/labels/${seg(args.requireString("label_id"))}",
            jsonBody = body,
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_delete_label",
        description = "Delete a label by id (Log Search API).",
        destructive = true,
        inputSchema = toolSchema("label_id") { stringParam("label_id", "The id of the label to delete.") },
    ) { args ->
        client.request(
            HttpMethod.Delete,
            "/management/labels/${seg(args.requireString("label_id"))}",
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }
}
