package com.jitunicornfx.insightidr.mcp.tools

import com.jitunicornfx.insightidr.mcp.Rapid7Client
import com.jitunicornfx.insightidr.mcp.apiTool
import com.jitunicornfx.insightidr.mcp.arrayOrNull
import com.jitunicornfx.insightidr.mcp.booleanOrNull
import com.jitunicornfx.insightidr.mcp.integerParam
import com.jitunicornfx.insightidr.mcp.intOrNull
import com.jitunicornfx.insightidr.mcp.objectArrayParam
import com.jitunicornfx.insightidr.mcp.booleanParam
import com.jitunicornfx.insightidr.mcp.pagingQuery
import com.jitunicornfx.insightidr.mcp.putOpt
import com.jitunicornfx.insightidr.mcp.query
import com.jitunicornfx.insightidr.mcp.requireString
import com.jitunicornfx.insightidr.mcp.seg
import com.jitunicornfx.insightidr.mcp.stringOrNull
import com.jitunicornfx.insightidr.mcp.stringParam
import com.jitunicornfx.insightidr.mcp.toToolResult
import com.jitunicornfx.insightidr.mcp.toolSchema
import io.ktor.http.HttpMethod
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// Status-change endpoints accept WAITING; the create request body schema does not.
private val STATUS_VALUES = listOf("OPEN", "INVESTIGATING", "WAITING", "CLOSED")
private val CREATE_STATUS_VALUES = listOf("OPEN", "INVESTIGATING", "CLOSED")
private val PRIORITY_VALUES = listOf("UNSPECIFIED", "LOW", "MEDIUM", "HIGH", "CRITICAL")
private val DISPOSITION_PATH_VALUES = listOf("BENIGN", "MALICIOUS", "NOT_APPLICABLE")
private val DISPOSITION_BODY_VALUES = listOf("UNDECIDED", "BENIGN", "MALICIOUS", "NOT_APPLICABLE")
private val THREAT_COMMAND_REASONS = listOf(
    "ProblemSolved", "InformationalOnly", "ProblemWeAreAlreadyAwareOf", "NotRelatedToMyCompany",
    "FalsePositive", "LegitimateApplication/Profile", "CompanyOwnedDomain", "Other",
)

/** Build the optional `assignee` object `{ "email": ... }` from an email argument. */
private fun JsonObject.assigneeObject(): JsonObject? =
    stringOrNull("assignee_email")?.let { email -> buildJsonObject { put("email", email) } }

/** Registers the InsightIDR v2 Investigations tools. */
fun Server.registerInvestigationV2Tools(client: Rapid7Client) {

    apiTool(
        name = "list_investigations",
        description = "List InsightIDR investigations (API v2). Supports filtering by status, source, " +
            "priority, assignee, tags, and a time window, with pagination and sorting.",
        readOnly = true,
        inputSchema = toolSchema {
            integerParam("index", "Zero-based page index. Defaults to 0.")
            integerParam("size", "Number of investigations per page (max 1000). Defaults to 20.")
            stringParam("statuses", "Comma-separated statuses to include (OPEN, INVESTIGATING, WAITING, CLOSED).")
            stringParam("sources", "Comma-separated investigation sources to include (e.g. ALERT, MANUAL, HUNT).")
            stringParam("priorities", "Comma-separated priorities to include (UNSPECIFIED, LOW, MEDIUM, HIGH, CRITICAL).")
            stringParam("assignee_email", "Filter to investigations assigned to this user email.")
            stringParam("start_time", "ISO-8601 timestamp; only investigations created at/after this time.")
            stringParam("end_time", "ISO-8601 timestamp; only investigations created at/before this time.")
            stringParam("tags", "Comma-separated tags to filter by.")
            stringParam("sort", "Sort expression, e.g. 'created_time,DESC'.")
            booleanParam("multi_customer", "MSSP only: include investigations across managed organizations.")
        },
    ) { args ->
        client.request(
            HttpMethod.Get,
            "/idr/v2/investigations",
            query = query(
                "index" to args.intOrNull("index"),
                "size" to args.intOrNull("size"),
                "statuses" to args.stringOrNull("statuses"),
                "sources" to args.stringOrNull("sources"),
                "priorities" to args.stringOrNull("priorities"),
                "assignee.email" to args.stringOrNull("assignee_email"),
                "start_time" to args.stringOrNull("start_time"),
                "end_time" to args.stringOrNull("end_time"),
                "tags" to args.stringOrNull("tags"),
                "sort" to args.stringOrNull("sort"),
                "multi-customer" to args.booleanOrNull("multi_customer"),
            ),
        ).toToolResult()
    }

    apiTool(
        name = "get_investigation",
        description = "Get a single InsightIDR investigation by its id or RRN (API v2).",
        readOnly = true,
        inputSchema = toolSchema("id") {
            stringParam("id", "The investigation id or RRN.")
            booleanParam("multi_customer", "MSSP only: resolve across managed organizations.")
        },
    ) { args ->
        val id = args.requireString("id")
        client.request(
            HttpMethod.Get,
            "/idr/v2/investigations/${seg(id)}",
            query = query("multi-customer" to args.booleanOrNull("multi_customer")),
        ).toToolResult()
    }

    apiTool(
        name = "search_investigations",
        description = "Search investigations with structured field criteria and sorting over a time range (API v2).",
        readOnly = true,
        inputSchema = toolSchema {
            objectArrayParam(
                "search",
                "Array of criteria objects: { \"field\": string, \"operator\": \"EQUALS\"|\"CONTAINS\"|\"IN\", \"value\": any }.",
            )
            objectArrayParam("sort", "Array of sort objects: { \"field\": string, \"order\": \"ASC\"|\"DESC\" }.")
            stringParam("start_time", "ISO-8601 start of the time window to search.")
            stringParam("end_time", "ISO-8601 end of the time window to search.")
            integerParam("index", "Zero-based page index.")
            integerParam("size", "Page size.")
            booleanParam("multi_customer", "MSSP only: search across managed organizations.")
        },
    ) { args ->
        val body = buildJsonObject {
            putOpt("search", args.arrayOrNull("search"))
            putOpt("sort", args.arrayOrNull("sort"))
            putOpt("start_time", args.stringOrNull("start_time"))
            putOpt("end_time", args.stringOrNull("end_time"))
        }
        client.request(
            HttpMethod.Post,
            "/idr/v2/investigations/_search",
            query = query(
                "index" to args.intOrNull("index"),
                "size" to args.intOrNull("size"),
                "multi-customer" to args.booleanOrNull("multi_customer"),
            ),
            jsonBody = body,
        ).toToolResult()
    }

    apiTool(
        name = "create_investigation",
        description = "Create a new InsightIDR investigation (API v2).",
        inputSchema = toolSchema("title") {
            stringParam("title", "The name of the investigation.")
            stringParam("status", "Initial status. Defaults to OPEN.", enum = CREATE_STATUS_VALUES)
            stringParam("priority", "Initial priority. Defaults to UNSPECIFIED.", enum = PRIORITY_VALUES)
            stringParam("disposition", "Initial disposition. Defaults to UNDECIDED.", enum = DISPOSITION_BODY_VALUES)
            stringParam("assignee_email", "Email of the user to assign the investigation to.")
        },
    ) { args ->
        val body = buildJsonObject {
            put("title", args.requireString("title"))
            putOpt("status", args.stringOrNull("status"))
            putOpt("priority", args.stringOrNull("priority"))
            putOpt("disposition", args.stringOrNull("disposition"))
            putOpt("assignee", args.assigneeObject())
        }
        client.request(HttpMethod.Post, "/idr/v2/investigations", jsonBody = body).toToolResult()
    }

    apiTool(
        name = "update_investigation",
        description = "Update fields on an existing investigation (API v2). Omitted fields are left unchanged.",
        inputSchema = toolSchema("id") {
            stringParam("id", "The investigation id or RRN.")
            stringParam("title", "New title.")
            stringParam("status", "New status.", enum = STATUS_VALUES)
            stringParam("priority", "New priority.", enum = PRIORITY_VALUES)
            stringParam("disposition", "New disposition.", enum = listOf("BENIGN", "MALICIOUS", "NOT_APPLICABLE"))
            stringParam("assignee_email", "Email of the user to assign; use an empty string to unassign.")
            stringParam(
                "threat_command_close_reason",
                "Threat Command close reason (only for Threat Command investigations being closed).",
                enum = THREAT_COMMAND_REASONS,
            )
            stringParam("threat_command_free_text", "Additional free text when closing a Threat Command investigation.")
            booleanParam("multi_customer", "MSSP only: target a managed organization's investigation.")
        },
    ) { args ->
        val id = args.requireString("id")
        val body = buildJsonObject {
            putOpt("title", args.stringOrNull("title"))
            putOpt("status", args.stringOrNull("status"))
            putOpt("priority", args.stringOrNull("priority"))
            putOpt("disposition", args.stringOrNull("disposition"))
            // Per the spec, unassigning requires assignee.email = null; an empty string maps to that.
            if ("assignee_email" in args) {
                val email = args.stringOrNull("assignee_email")
                putJsonObject("assignee") {
                    if (email.isNullOrBlank()) put("email", JsonNull) else put("email", email)
                }
            }
            putOpt("threat_command_close_reason", args.stringOrNull("threat_command_close_reason"))
            putOpt("threat_command_free_text", args.stringOrNull("threat_command_free_text"))
        }
        client.request(
            HttpMethod.Patch,
            "/idr/v2/investigations/${seg(id)}",
            query = query("multi-customer" to args.booleanOrNull("multi_customer")),
            jsonBody = body,
        ).toToolResult()
    }

    apiTool(
        name = "set_investigation_status",
        description = "Set an investigation's status (API v2). When closing, optional disposition and Threat " +
            "Command close details may be supplied.",
        inputSchema = toolSchema("id", "status") {
            stringParam("id", "The investigation id or RRN.")
            stringParam("status", "The new status.", enum = STATUS_VALUES)
            stringParam("disposition", "Disposition to set (only applied when closing).", enum = DISPOSITION_PATH_VALUES)
            stringParam("threat_command_close_reason", "Threat Command close reason.", enum = THREAT_COMMAND_REASONS)
            stringParam("threat_command_free_text", "Additional free text for a Threat Command close.")
            booleanParam("multi_customer", "MSSP only: target a managed organization's investigation.")
        },
    ) { args ->
        val id = args.requireString("id")
        val status = args.requireString("status")
        val body = buildJsonObject {
            putOpt("disposition", args.stringOrNull("disposition"))
            putOpt("threat_command_close_reason", args.stringOrNull("threat_command_close_reason"))
            putOpt("threat_command_free_text", args.stringOrNull("threat_command_free_text"))
        }
        client.request(
            HttpMethod.Put,
            "/idr/v2/investigations/${seg(id)}/status/${seg(status)}",
            query = query("multi-customer" to args.booleanOrNull("multi_customer")),
            jsonBody = body,
        ).toToolResult()
    }

    apiTool(
        name = "set_investigation_priority",
        description = "Set an investigation's priority (API v2).",
        inputSchema = toolSchema("id", "priority") {
            stringParam("id", "The investigation id or RRN.")
            stringParam("priority", "The new priority.", enum = PRIORITY_VALUES)
            booleanParam("multi_customer", "MSSP only: target a managed organization's investigation.")
        },
    ) { args ->
        val id = args.requireString("id")
        val priority = args.requireString("priority")
        client.request(
            HttpMethod.Put,
            "/idr/v2/investigations/${seg(id)}/priority/${seg(priority)}",
            query = query("multi-customer" to args.booleanOrNull("multi_customer")),
        ).toToolResult()
    }

    apiTool(
        name = "set_investigation_disposition",
        description = "Set an investigation's disposition (API v2).",
        inputSchema = toolSchema("id", "disposition") {
            stringParam("id", "The investigation id or RRN.")
            stringParam("disposition", "The new disposition.", enum = DISPOSITION_PATH_VALUES)
            booleanParam("multi_customer", "MSSP only: target a managed organization's investigation.")
        },
    ) { args ->
        val id = args.requireString("id")
        val disposition = args.requireString("disposition")
        client.request(
            HttpMethod.Put,
            "/idr/v2/investigations/${seg(id)}/disposition/${seg(disposition)}",
            query = query("multi-customer" to args.booleanOrNull("multi_customer")),
        ).toToolResult()
    }

    apiTool(
        name = "assign_investigation",
        description = "Assign a user to an investigation by email (API v2).",
        inputSchema = toolSchema("id", "user_email_address") {
            stringParam("id", "The investigation id or RRN.")
            stringParam("user_email_address", "Email address of the user to assign.")
            booleanParam("multi_customer", "MSSP only: target a managed organization's investigation.")
        },
    ) { args ->
        val id = args.requireString("id")
        val body = buildJsonObject { put("user_email_address", args.requireString("user_email_address")) }
        client.request(
            HttpMethod.Put,
            "/idr/v2/investigations/${seg(id)}/assignee",
            query = query("multi-customer" to args.booleanOrNull("multi_customer")),
            jsonBody = body,
        ).toToolResult()
    }

    apiTool(
        name = "bulk_close_investigations",
        description = "Close multiple investigations in bulk by source and time window (API v2).",
        inputSchema = toolSchema("source", "from", "to") {
            stringParam("source", "Investigation source whose investigations should be closed.", enum = listOf("ALERT", "MANUAL", "HUNT"))
            stringParam("from", "ISO-8601 timestamp; close investigations created at/after this time.")
            stringParam("to", "ISO-8601 timestamp; close investigations created at/before this time.")
            stringParam("alert_type", "Category of alert types to close (required for some sources).")
            stringParam("disposition", "Disposition to set on closed investigations. Defaults to NOT_APPLICABLE.", enum = DISPOSITION_BODY_VALUES)
            stringParam("detection_rule_rrn", "Only close investigations associated with this detection rule RRN.")
            integerParam("max_investigations_to_close", "Optional maximum number of investigations to close.")
        },
    ) { args ->
        val body = buildJsonObject {
            put("source", args.requireString("source"))
            put("from", args.requireString("from"))
            put("to", args.requireString("to"))
            putOpt("alert_type", args.stringOrNull("alert_type"))
            putOpt("disposition", args.stringOrNull("disposition"))
            putOpt("detection_rule_rrn", args.stringOrNull("detection_rule_rrn"))
            putOpt("max_investigations_to_close", args.intOrNull("max_investigations_to_close"))
        }
        // Note: the bulk_close operation does not define a multi-customer query parameter in the spec.
        client.request(HttpMethod.Post, "/idr/v2/investigations/bulk_close", jsonBody = body).toToolResult()
    }

    apiTool(
        name = "list_investigation_alerts",
        description = "List the alerts associated with a specific investigation (API v2).",
        readOnly = true,
        inputSchema = toolSchema("identifier") {
            stringParam("identifier", "The investigation id or RRN.")
            integerParam("index", "Zero-based page index.")
            integerParam("size", "Page size.")
            booleanParam("multi_customer", "MSSP only: target a managed organization's investigation.")
        },
    ) { args ->
        val identifier = args.requireString("identifier")
        client.request(
            HttpMethod.Get,
            "/idr/v2/investigations/${seg(identifier)}/alerts",
            query = pagingQuery(args) + query("multi-customer" to args.booleanOrNull("multi_customer")),
        ).toToolResult()
    }

    apiTool(
        name = "get_investigation_product_alerts",
        description = "Get the list of Rapid7 product alerts associated with a specific investigation (API v2).",
        readOnly = true,
        inputSchema = toolSchema("identifier") {
            stringParam("identifier", "The investigation id or RRN.")
            booleanParam("multi_customer", "MSSP only: target a managed organization's investigation.")
        },
    ) { args ->
        val identifier = args.requireString("identifier")
        client.request(
            HttpMethod.Get,
            "/idr/v2/investigations/${seg(identifier)}/rapid7-product-alerts",
            query = query("multi-customer" to args.booleanOrNull("multi_customer")),
        ).toToolResult()
    }

    apiTool(
        name = "remove_alert_from_investigation",
        description = "Remove (disassociate) an alert from an investigation (API v2).",
        destructive = true,
        inputSchema = toolSchema("identifier", "alert_rrn") {
            stringParam("identifier", "The investigation id or RRN.")
            stringParam("alert_rrn", "The RRN of the alert to remove.")
            booleanParam("multi_customer", "MSSP only: target a managed organization's investigation.")
        },
    ) { args ->
        val identifier = args.requireString("identifier")
        val alertRrn = args.requireString("alert_rrn")
        client.request(
            HttpMethod.Delete,
            "/idr/v2/investigations/${seg(identifier)}/alerts/${seg(alertRrn)}",
            query = query("multi-customer" to args.booleanOrNull("multi_customer")),
        ).toToolResult()
    }
}
