package com.jitunicornfx.insightidr.mcp.tools

import com.jitunicornfx.insightidr.mcp.*
import com.jitunicornfx.insightidr.mcp.Rapid7Client.ApiBase
import io.ktor.http.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.put

/** Log Search API — LEQL variables and pre-computed queries (PCQs). */
/**
 * The `metric` body. Spec 3.0.2 contradicts itself on `logs`: the schema block shows a single object,
 * while its own request example, its code sample and the response schema all use an array. The array
 * is what is described here, and said to be so, because three sources outweigh one and the fourth
 * (the API itself) was not asked: creating a pre-computed query is a write to the tenant.
 */
private const val METRIC_SHAPE =
    "{ \"name\", \"description\", \"enabled\": bool, \"logs\": [ { \"id\": \"<log UUID>\" } ], " +
        "\"leql\": { \"statement\": \"where(...)\", \"function\": \"calculate(count)\" }, " +
        "\"resolution\": seconds per data point, \"retention\": seconds kept }. " +
        "'logs' is an ARRAY of {id} objects, as in the API's own request example. To target log sets instead, " +
        "give 'logsets' with {\"id\"} or {\"name\"} entries in the same array form."

fun Server.registerLogSearchVariableTools(client: Rapid7Client) {

    // ------------------------------------------------------------------
    // LEQL variables
    // ------------------------------------------------------------------

    apiTool(
        name = "logsearch_list_variables",
        description = "List all LEQL variables (Log Search API). Variables can be referenced in LEQL queries.",
        readOnly = true,
    ) { _ ->
        client.request(HttpMethod.Get, "/query/variables", base = ApiBase.LOG_SEARCH).toToolResult()
    }

    apiTool(
        name = "logsearch_get_variable",
        description = "Retrieve a LEQL variable by id (Log Search API).",
        readOnly = true,
        inputSchema = toolSchema("variable_id") { stringParam("variable_id", "The id of the LEQL variable.") },
    ) { args ->
        client.request(
            HttpMethod.Get,
            "/query/variables/${seg(args.requireString("variable_id"))}",
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_create_variable",
        description = "Create a LEQL variable (Log Search API).",
        inputSchema = toolSchema("name", "value") {
            stringParam("name", "The name of the variable.")
            stringParam("value", "The value of the variable.")
            stringParam("description", "Optional description of the variable.")
        },
    ) { args ->
        val body = buildJsonObject {
            putJsonObject("variable") {
                put("name", args.requireString("name"))
                put("value", args.requireString("value"))
                putOpt("description", args.stringOrNull("description"))
            }
        }
        client.request(HttpMethod.Post, "/query/variables", jsonBody = body, base = ApiBase.LOG_SEARCH).toToolResult()
    }

    apiTool(
        name = "logsearch_update_variable",
        description = "Update a LEQL variable (PUT, Log Search API). Name and value are required.",
        inputSchema = toolSchema("variable_id", "name", "value") {
            stringParam("variable_id", "The id of the variable to update.")
            stringParam("name", "The name of the variable.")
            stringParam("value", "The value of the variable.")
            stringParam("description", "Optional description of the variable.")
        },
    ) { args ->
        val body = buildJsonObject {
            putJsonObject("variable") {
                put("name", args.requireString("name"))
                put("value", args.requireString("value"))
                putOpt("description", args.stringOrNull("description"))
            }
        }
        client.request(
            HttpMethod.Put,
            "/query/variables/${seg(args.requireString("variable_id"))}",
            jsonBody = body,
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_delete_variable",
        description = "Delete a LEQL variable by id (Log Search API). Fails with 409 if the variable is in use.",
        destructive = true,
        inputSchema = toolSchema("variable_id") { stringParam("variable_id", "The id of the variable to delete.") },
    ) { args ->
        client.request(
            HttpMethod.Delete,
            "/query/variables/${seg(args.requireString("variable_id"))}",
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    // ------------------------------------------------------------------
    // Pre-computed queries (PCQs)
    // ------------------------------------------------------------------

    apiTool(
        name = "logsearch_list_metrics",
        description = "List all pre-computed queries (Log Search API). Pre-computed queries continuously " +
            "calculate a LEQL statement over incoming data at a fixed resolution.",
        readOnly = true,
    ) { _ ->
        client.request(HttpMethod.Get, "/management/metrics", base = ApiBase.LOG_SEARCH).toToolResult()
    }

    apiTool(
        name = "logsearch_get_metric",
        description = "Retrieve a pre-computed query definition by id (Log Search API).",
        readOnly = true,
        inputSchema = toolSchema("metric_id") { stringParam("metric_id", "The UUID of the pre-computed query.") },
    ) { args ->
        client.request(
            HttpMethod.Get,
            "/management/metrics/${seg(args.requireString("metric_id"))}",
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_query_metric",
        description = "Fetch the results of a pre-computed query over a time window (Log Search API).",
        readOnly = true,
        inputSchema = toolSchema("metric_id") {
            stringParam("metric_id", "The UUID of the pre-computed query.")
            timeWindowParams()
        },
    ) { args ->
        requireTimeWindow(args)
        client.request(
            HttpMethod.Get,
            "/query/metrics/${seg(args.requireString("metric_id"))}",
            query = timeWindowQuery(args),
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_create_metric",
        description = "Create a pre-computed query (Log Search API). Provide the 'metric' object: " + METRIC_SHAPE,
        inputSchema = toolSchema("metric") {
            objectParam("metric", "The pre-computed query definition object (see tool description for shape).")
        },
    ) { args ->
        val metric = args.objectOrNull("metric")
            ?: throw IllegalArgumentException("Missing required parameter 'metric'")
        val body = buildJsonObject { put("metric", metric) }
        client.request(HttpMethod.Post, "/management/metrics", jsonBody = body, base = ApiBase.LOG_SEARCH).toToolResult()
    }

    apiTool(
        name = "logsearch_replace_metric",
        description = "Replace a pre-computed query definition (PUT, Log Search API). The 'metric' object: " + METRIC_SHAPE,
        inputSchema = toolSchema("metric_id", "metric") {
            stringParam("metric_id", "The UUID of the pre-computed query to replace.")
            objectParam("metric", "The replacement pre-computed query definition object.")
        },
    ) { args ->
        val metric = args.objectOrNull("metric")
            ?: throw IllegalArgumentException("Missing required parameter 'metric'")
        val body = buildJsonObject { put("metric", metric) }
        client.request(
            HttpMethod.Put,
            "/management/metrics/${seg(args.requireString("metric_id"))}",
            jsonBody = body,
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_delete_metric",
        description = "Delete a pre-computed query by id (Log Search API).",
        destructive = true,
        inputSchema = toolSchema("metric_id") { stringParam("metric_id", "The UUID of the pre-computed query to delete.") },
    ) { args ->
        client.request(
            HttpMethod.Delete,
            "/management/metrics/${seg(args.requireString("metric_id"))}",
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }
}
