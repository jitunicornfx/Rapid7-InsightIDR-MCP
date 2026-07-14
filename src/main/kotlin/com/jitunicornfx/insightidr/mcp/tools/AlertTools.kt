package com.jitunicornfx.insightidr.mcp.tools

import com.jitunicornfx.insightidr.mcp.*
import io.ktor.http.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.*

// ---------------------------------------------------------------------------
// InsightIDR SIEM Alerts API (`/idr/at`, served from the v2 `api.insight.rapid7.com` host).
//
// Covers the Alerts, Actions, and Process Tree endpoints used for alert triage. All responses flow
// back through ApiResponse.toToolResult(), which wraps API data in the prompt-injection shield — this
// matters here because alert titles, evidence data, actor display names, process command lines, and
// action request payloads are all attacker-influenceable third-party content.
// ---------------------------------------------------------------------------

/** Common path prefix for the Alerts API (the v2 host carries it under `/idr/at`). */
private const val AT = "/idr/at"

private const val ALERT_RRN_DESC = "The Rapid7 Resource Name (RRN) of the alert."
private const val ACTION_RRN_DESC = "The Rapid7 Resource Name (RRN) of the action (alert job)."
private const val FIELD_ID_DESC = "The unique identifier of the alert field."

// The query key for the free-text SearchOptions filter. Exposed to callers as `search_text` to avoid
// colliding with the structured `search` request-body object on the same endpoints.
private const val SEARCH_OPTIONS_QUERY = "search"
private const val SEARCH_TEXT_ARG = "search_text"
private const val SEARCH_TEXT_DESC = "Case-insensitive free-text filter limiting results to those containing these terms."

private const val SEARCH_DESC =
    "The structured search criteria object (top-level terms are AND'ed): " +
        "{ \"start_time\": ISO-8601 (required), \"end_time\": ISO-8601, \"leql\": \"LEQL WHERE clause\", " +
        "\"terms\": [ { \"field_ids\": [string], " +
        "\"operator\": \"EQUALS\"|\"NOT_EQUALS\"|\"CONTAINS\"|\"GREATER_THAN\"|\"LESS_THAN\", " +
        "\"terms\": [value, ...] } ] (required) }."

private const val PATCH_DESC =
    "The AlertPatch object of changes. Each field wraps its new value under `value`: " +
        "{ \"status\": { \"value\": \"OPEN\"|\"INVESTIGATING\"|\"WAITING\"|\"CLOSED\" }, " +
        "\"disposition\": { \"value\": \"BENIGN\"|\"MALICIOUS\"|\"FALSE_POSITIVE\"|\"SECURITY_TEST\"|\"NOT_APPLICABLE\"|\"UNDECIDED\"|\"UNKNOWN\" }, " +
        "\"priority\": { \"value\": \"INFO\"|\"LOW\"|\"MEDIUM\"|\"HIGH\"|\"CRITICAL\" }, " +
        "\"assignee_id\": { \"value\": \"user-id\" }, " +
        "\"investigation_rrn\": { \"value\": \"rrn\" }, " +
        "\"tags\": { \"value\": [string], \"action\": \"ADD\"|\"REMOVE\" }, " +
        "\"comment\": \"audit reason\" }."

private val ALERT_PRIORITY_VALUES = listOf("UNMAPPED", "INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL")

/** Read a string-array argument as a plain `List<String>` for query expansion (null if absent/empty). */
private fun JsonObject.stringListOrNull(key: String): List<String>? =
    arrayOrNull(key)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotEmpty() }

/** Require a JSON-object argument, mirroring [requireString]'s missing-parameter error. */
private fun JsonObject.requireObject(key: String): JsonObject =
    objectOrNull(key) ?: throw IllegalArgumentException("Missing required parameter '$key'")

/** Require a JSON-array argument, mirroring [requireString]'s missing-parameter error. */
private fun JsonObject.requireArray(key: String): JsonArray =
    arrayOrNull(key) ?: throw IllegalArgumentException("Missing required parameter '$key'")

/** Registers the InsightIDR SIEM Alerts API tools (search, retrieval, triage updates, reports). */
fun Server.registerAlertTools(client: Rapid7Client) {

    apiTool(
        name = "search_alerts",
        description = "Search SIEM alerts that match structured criteria, with sorting, extra fields, and aggregates.",
        readOnly = true,
        inputSchema = toolSchema("search") {
            objectParam("search", SEARCH_DESC)
            objectArrayParam("sorts", "Sort order: array of { \"field_id\": string, \"order\": \"ASCENDING_NULLS_LAST\"|\"ASCENDING_NULLS_FIRST\"|\"DESCENDING_NULLS_LAST\"|\"DESCENDING_NULLS_FIRST\" }.")
            stringArrayParam("field_ids", "Additional field identifiers to include for each alert.")
            objectArrayParam("aggregates", "Aggregations to apply across all matching results.")
            booleanParam("rrns_only", "If true, return only alert RRNs instead of full alert details. Defaults to false.")
            integerParam("index", "Zero-based page index.")
            integerParam("size", "Page size.")
        },
    ) { args ->
        val body = buildJsonObject {
            put("search", args.requireObject("search"))
            putOpt("sorts", args.arrayOrNull("sorts"))
            putOpt("field_ids", args.arrayOrNull("field_ids"))
            putOpt("aggregates", args.arrayOrNull("aggregates"))
        }
        client.request(
            HttpMethod.Post,
            "$AT/alerts/ops/search",
            query = query(
                "rrns_only" to args.booleanOrNull("rrns_only"),
                "index" to args.intOrNull("index"),
                "size" to args.intOrNull("size"),
            ),
            jsonBody = body,
        ).toToolResult()
    }

    apiTool(
        name = "get_alert",
        description = "Retrieve a single SIEM alert by its Rapid7 Resource Name (RRN).",
        readOnly = true,
        inputSchema = toolSchema("alert_rrn") {
            stringParam("alert_rrn", ALERT_RRN_DESC)
        },
    ) { args ->
        val alertRrn = args.requireString("alert_rrn")
        client.request(HttpMethod.Get, "$AT/alerts/${seg(alertRrn)}").toToolResult()
    }

    apiTool(
        name = "get_alerts_by_rrn",
        description = "Retrieve multiple SIEM alerts by their Rapid7 Resource Names (RRNs).",
        readOnly = true,
        inputSchema = toolSchema("rrns") {
            stringArrayParam("rrns", "The RRNs of the alerts to retrieve.")
            stringArrayParam("field_ids", "Additional field identifiers to include for each alert.")
            booleanParam("strict", "If true, return a 404 when no alerts are found.")
        },
    ) { args ->
        val body = buildJsonObject {
            put("rrns", args.requireArray("rrns"))
            putOpt("field_ids", args.arrayOrNull("field_ids"))
        }
        client.request(
            HttpMethod.Post,
            "$AT/alerts/ops/rrns",
            query = query("strict" to args.booleanOrNull("strict")),
            jsonBody = body,
        ).toToolResult()
    }

    apiTool(
        name = "patch_alert",
        description = "Asynchronously update a single SIEM alert (status, disposition, priority, assignee, tags, etc.).",
        inputSchema = toolSchema("alert_rrn", "patch") {
            stringParam("alert_rrn", ALERT_RRN_DESC)
            objectParam("patch", PATCH_DESC)
        },
    ) { args ->
        val alertRrn = args.requireString("alert_rrn")
        client.request(
            HttpMethod.Patch,
            "$AT/alerts/${seg(alertRrn)}",
            jsonBody = args.requireObject("patch"),
        ).toToolResult()
    }

    apiTool(
        name = "patch_alerts",
        description = "Asynchronously update multiple SIEM alerts matching a search. Returns an action RRN to track progress.",
        inputSchema = toolSchema("search", "patch") {
            objectParam("search", SEARCH_DESC)
            objectParam("patch", PATCH_DESC)
        },
    ) { args ->
        val body = buildJsonObject {
            put("search", args.requireObject("search"))
            put("patch", args.requireObject("patch"))
        }
        client.request(HttpMethod.Patch, "$AT/alerts", jsonBody = body).toToolResult()
    }

    apiTool(
        name = "get_alert_evidences",
        description = "Retrieve the evidence associated with a single SIEM alert.",
        readOnly = true,
        inputSchema = toolSchema("alert_rrn") {
            stringParam("alert_rrn", ALERT_RRN_DESC)
            integerParam("index", "Zero-based page index.")
            integerParam("size", "Page size.")
        },
    ) { args ->
        val alertRrn = args.requireString("alert_rrn")
        client.request(
            HttpMethod.Get,
            "$AT/alerts/${seg(alertRrn)}/evidences",
            query = pagingQuery(args),
        ).toToolResult()
    }

    apiTool(
        name = "get_alert_actors",
        description = "Retrieve the actors (assets, accounts, IP addresses) associated with a single SIEM alert.",
        readOnly = true,
        inputSchema = toolSchema("alert_rrn") {
            stringParam("alert_rrn", ALERT_RRN_DESC)
            integerParam("index", "Zero-based page index.")
            integerParam("size", "Page size.")
        },
    ) { args ->
        val alertRrn = args.requireString("alert_rrn")
        client.request(
            HttpMethod.Get,
            "$AT/alerts/${seg(alertRrn)}/actors",
            query = pagingQuery(args),
        ).toToolResult()
    }

    apiTool(
        name = "get_alert_assignee_options",
        description = "Retrieve the users that could be assigned to a single SIEM alert.",
        readOnly = true,
        inputSchema = toolSchema("alert_rrn") {
            stringParam("alert_rrn", ALERT_RRN_DESC)
            stringParam(SEARCH_TEXT_ARG, SEARCH_TEXT_DESC)
            integerParam("index", "Zero-based page index.")
            integerParam("size", "Page size.")
        },
    ) { args ->
        val alertRrn = args.requireString("alert_rrn")
        client.request(
            HttpMethod.Get,
            "$AT/alerts/${seg(alertRrn)}/assigneeOptions",
            query = pagingQuery(args) + query(SEARCH_OPTIONS_QUERY to args.stringOrNull(SEARCH_TEXT_ARG)),
        ).toToolResult()
    }

    apiTool(
        name = "get_assignee_options",
        description = "Retrieve the users that could be assigned to the SIEM alerts matching a search.",
        readOnly = true,
        inputSchema = toolSchema("search") {
            objectParam("search", SEARCH_DESC)
            stringParam(SEARCH_TEXT_ARG, SEARCH_TEXT_DESC)
            integerParam("index", "Zero-based page index.")
            integerParam("size", "Page size.")
        },
    ) { args ->
        val body = buildJsonObject { put("search", args.requireObject("search")) }
        client.request(
            HttpMethod.Post,
            "$AT/alerts/assigneeOptions",
            query = pagingQuery(args) + query(SEARCH_OPTIONS_QUERY to args.stringOrNull(SEARCH_TEXT_ARG)),
            jsonBody = body,
        ).toToolResult()
    }

    apiTool(
        name = "get_alert_field",
        description = "Retrieve a single alert field definition by its identifier.",
        readOnly = true,
        inputSchema = toolSchema("field_id") {
            stringParam("field_id", FIELD_ID_DESC)
        },
    ) { args ->
        val fieldId = args.requireString("field_id")
        client.request(HttpMethod.Get, "$AT/alerts/fields/${seg(fieldId)}").toToolResult()
    }

    apiTool(
        name = "get_alert_field_values",
        description = "Retrieve the enumerated values for a single (enumerable) alert field.",
        readOnly = true,
        inputSchema = toolSchema("field_id") {
            stringParam("field_id", FIELD_ID_DESC)
            stringParam(SEARCH_TEXT_ARG, SEARCH_TEXT_DESC)
            integerParam("index", "Zero-based page index.")
            integerParam("size", "Page size.")
        },
    ) { args ->
        val fieldId = args.requireString("field_id")
        client.request(
            HttpMethod.Get,
            "$AT/alerts/fields/${seg(fieldId)}/values",
            query = pagingQuery(args) + query(SEARCH_OPTIONS_QUERY to args.stringOrNull(SEARCH_TEXT_ARG)),
        ).toToolResult()
    }

    apiTool(
        name = "list_alert_fields",
        description = "Retrieve all supported alert fields (searchable/sortable/aggregatable/enumerable), with optional path filtering.",
        readOnly = true,
        inputSchema = toolSchema {
            stringParam("path", "Limit results to descendants of this node in the field hierarchy (period-separated).")
            integerParam("path_depth", "Depth to round results to after the path filter is applied (minimum 1).")
            stringParam(SEARCH_TEXT_ARG, SEARCH_TEXT_DESC)
            integerParam("index", "Zero-based page index.")
            integerParam("size", "Page size.")
        },
    ) { args ->
        client.request(
            HttpMethod.Get,
            "$AT/alerts/2/fields",
            query = pagingQuery(args) + query(
                "path" to args.stringOrNull("path"),
                "path_depth" to args.intOrNull("path_depth"),
                SEARCH_OPTIONS_QUERY to args.stringOrNull(SEARCH_TEXT_ARG),
            ),
        ).toToolResult()
    }

    apiTool(
        name = "investigate_alerts",
        description = "Create a new investigation from the SIEM alerts matching a search. Alerts are attached asynchronously.",
        inputSchema = toolSchema("organization_id", "title", "disposition", "status", "search") {
            stringParam("organization_id", "The organization that the investigation belongs to.")
            stringParam("title", "The title of the investigation.")
            stringParam("disposition", "The disposition of the investigation.")
            stringParam("status", "The status of the investigation.")
            objectParam("search", SEARCH_DESC)
            stringParam("assignee_id", "Identifier of the user to assign the investigation to.")
            stringParam("priority", "The priority of the investigation.", enum = ALERT_PRIORITY_VALUES)
            stringArrayParam("tags", "Tags to apply to the investigation.")
            stringParam("comment", "Reason for creating the investigation, captured in the audit log.")
        },
    ) { args ->
        val body = buildJsonObject {
            put("organization_id", args.requireString("organization_id"))
            put("title", args.requireString("title"))
            put("disposition", args.requireString("disposition"))
            put("status", args.requireString("status"))
            put("search", args.requireObject("search"))
            putOpt("assignee_id", args.stringOrNull("assignee_id"))
            putOpt("priority", args.stringOrNull("priority"))
            putOpt("tags", args.arrayOrNull("tags"))
            putOpt("comment", args.stringOrNull("comment"))
        }
        client.request(HttpMethod.Post, "$AT/alerts/ops/investigate", jsonBody = body).toToolResult()
    }

    apiTool(
        name = "generate_alert_report",
        description = "Generate a report (counts and aggregations) for the SIEM alerts matching a search.",
        readOnly = true,
        inputSchema = toolSchema("search") {
            objectParam("search", SEARCH_DESC)
            objectArrayParam("aggregates", "Aggregations to apply across all matching results.")
        },
    ) { args ->
        val body = buildJsonObject {
            put("search", args.requireObject("search"))
            putOpt("aggregates", args.arrayOrNull("aggregates"))
        }
        client.request(HttpMethod.Post, "$AT/alerts/ops/generateReport", jsonBody = body).toToolResult()
    }
}

/** Registers the SIEM Alerts "Actions" tools (alert jobs and their tasks/results). */
fun Server.registerAlertActionTools(client: Rapid7Client) {

    apiTool(
        name = "list_alert_actions",
        description = "List the alert actions (alert jobs) created within a time period, with filtering and sorting.",
        readOnly = true,
        inputSchema = toolSchema {
            stringParam("start_time", "ISO-8601 timestamp; only actions created at/after this time.")
            stringArrayParam("types", "Action types to include (PATCH_ALERT, CREATE_INVESTIGATION).")
            stringParam("sort_field", "Field to sort on.", enum = listOf("CREATED_AT", "TASK_COUNT"))
            stringParam("sort_order", "Sort order.", enum = listOf("ASC", "DESC"))
            stringArrayParam("statuses", "Action statuses to include (PENDING, RUNNING, FAILED, COMPLETE_WITH_ISSUES, COMPLETED).")
            booleanParam("has_failed_tasks", "If true, limit results to actions that have failed tasks.")
            integerParam("index", "Zero-based page index.")
            integerParam("size", "Page size.")
        },
    ) { args ->
        client.request(
            HttpMethod.Get,
            "$AT/actions",
            query = pagingQuery(args) + query(
                "start_time" to args.stringOrNull("start_time"),
                "type" to args.stringListOrNull("types"),
                "sort_field" to args.stringOrNull("sort_field"),
                "sort_order" to args.stringOrNull("sort_order"),
                "status" to args.stringListOrNull("statuses"),
                "has_failed_tasks" to args.booleanOrNull("has_failed_tasks"),
            ),
        ).toToolResult()
    }

    apiTool(
        name = "get_alert_action_result",
        description = "Retrieve the result of a single alert action (alert job).",
        readOnly = true,
        inputSchema = toolSchema("action_rrn") {
            stringParam("action_rrn", ACTION_RRN_DESC)
        },
    ) { args ->
        val actionRrn = args.requireString("action_rrn")
        client.request(HttpMethod.Get, "$AT/actions/${seg(actionRrn)}/result").toToolResult()
    }

    apiTool(
        name = "get_alert_action_tasks",
        description = "Retrieve the tasks associated with a single alert action (alert job).",
        readOnly = true,
        inputSchema = toolSchema("action_rrn") {
            stringParam("action_rrn", ACTION_RRN_DESC)
            stringArrayParam("statuses", "Task statuses to include (PENDING, RUNNING, FAILED, COMPLETE_WITH_ISSUES, COMPLETED).")
            integerParam("index", "Zero-based page index.")
            integerParam("size", "Page size.")
        },
    ) { args ->
        val actionRrn = args.requireString("action_rrn")
        client.request(
            HttpMethod.Get,
            "$AT/actions/${seg(actionRrn)}/tasks",
            query = pagingQuery(args) + query("status" to args.stringListOrNull("statuses")),
        ).toToolResult()
    }
}

/** Registers the SIEM Alerts "Process Tree" tools. */
fun Server.registerAlertProcessTreeTools(client: Rapid7Client) {

    apiTool(
        name = "get_alert_process_tree",
        description = "Retrieve a single process tree for a SIEM alert.",
        readOnly = true,
        inputSchema = toolSchema("alert_rrn", "process_tree_rrn") {
            stringParam("alert_rrn", ALERT_RRN_DESC)
            stringParam("process_tree_rrn", "The Rapid7 Resource Name (RRN) of the process tree.")
            booleanParam("force_refresh", "If true, regenerate the tree instead of returning a cached version (expensive).")
            integerParam("branch", "The branch number to generate the process tree with.")
        },
    ) { args ->
        val alertRrn = args.requireString("alert_rrn")
        val processTreeRrn = args.requireString("process_tree_rrn")
        client.request(
            HttpMethod.Post,
            "$AT/alerts/${seg(alertRrn)}/process_trees/${seg(processTreeRrn)}/latest",
            query = query(
                "force_refresh" to args.booleanOrNull("force_refresh"),
                "branch" to args.intOrNull("branch"),
            ),
        ).toToolResult()
    }

    apiTool(
        name = "get_alert_process_trees",
        description = "Retrieve all process trees for a SIEM alert.",
        readOnly = true,
        inputSchema = toolSchema("alert_rrn") {
            stringParam("alert_rrn", ALERT_RRN_DESC)
            booleanParam("force_refresh", "If true, regenerate the trees instead of returning cached versions (expensive).")
            integerParam("branch", "The branch number to generate the process trees with.")
            integerParam("index", "Zero-based page index.")
            integerParam("size", "Page size.")
        },
    ) { args ->
        val alertRrn = args.requireString("alert_rrn")
        client.request(
            HttpMethod.Post,
            "$AT/alerts/${seg(alertRrn)}/process_trees/latest",
            query = pagingQuery(args) + query(
                "force_refresh" to args.booleanOrNull("force_refresh"),
                "branch" to args.intOrNull("branch"),
            ),
        ).toToolResult()
    }
}
