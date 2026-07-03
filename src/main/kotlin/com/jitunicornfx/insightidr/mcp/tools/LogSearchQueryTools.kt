package com.jitunicornfx.insightidr.mcp.tools

import com.jitunicornfx.insightidr.mcp.*
import com.jitunicornfx.insightidr.mcp.Rapid7Client.ApiBase
import io.ktor.http.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Log Search API — querying log data with LEQL, and Saved Queries.
 * Query endpoints are asynchronous (202 + continuation links); tools poll to completion by default.
 */
fun Server.registerLogSearchQueryTools(client: Rapid7Client) {

    apiTool(
        name = "logsearch_query_log",
        description = "Run a LEQL query against one log (Log Search API). Returns matching log entries, or " +
            "statistics for calculate/groupby queries. Polls asynchronous results to completion by default.",
        readOnly = true,
        inputSchema = toolSchema("log_key") {
            stringParam("log_key", "The key (UUID) of the log to query. Multiple ':'-separated keys are accepted but deprecated — prefer logsearch_query_logs.")
            stringParam("query", "A valid LEQL query, e.g. where(status=404) calculate(count). If omitted, all entries in the time range are returned.")
            timeWindowParams()
            stringParam("label", "Only return entries carrying a label with this UUID (non-statistical queries only).")
            stringParam("labels", "':'-separated label UUIDs; only entries with a matching label are returned.")
            stringParam("export_format", "If set, export results in this format. Only 'csv' is supported; non-statistical queries only.")
            queryResultParams()
            pollingParams()
        },
    ) { args ->
        val (wait, timeout) = args.pollArgs()
        requireTimeWindow(args)
        val initial = client.request(
            HttpMethod.Get,
            "/query/logs/${seg(args.requireString("log_key"))}",
            query = query(
                "query" to args.stringOrNull("query"),
                "label" to args.stringOrNull("label"),
                "labels" to args.stringOrNull("labels"),
                "export_format" to args.stringOrNull("export_format"),
            ) + timeWindowQuery(args) + queryResultQuery(args),
            base = ApiBase.LOG_SEARCH,
        )
        client.awaitQueryCompletion(initial, wait, timeout).toToolResult()
    }

    apiTool(
        name = "logsearch_query_logs",
        description = "Run a LEQL query across multiple logs at once (Log Search API, POST /query/logs). " +
            "A time window (time_range, or from+to) is required. Polls asynchronous results to completion by default.",
        readOnly = true,
        inputSchema = toolSchema("log_keys") {
            stringArrayParam("log_keys", "The keys (UUIDs) of the logs to query.")
            stringParam("query", "The LEQL statement to run, e.g. where(status=404) calculate(count). If omitted, all entries in the time window are returned.")
            timeWindowParams()
            stringParam("labels", "':'-separated label UUIDs; only entries with a matching label are returned.")
            stringParam("export_format", "If set, export results in this format. Only 'csv' is supported; non-statistical queries only.")
            queryResultParams()
            pollingParams()
        },
    ) { args ->
        val (wait, timeout) = args.pollArgs()
        requireTimeWindow(args)
        val body = buildJsonObject {
            putOpt("logs", args.arrayOrNull("log_keys"))
            put("leql", leqlObject(args.stringOrNull("query") ?: "", args))
        }
        val initial = client.request(
            HttpMethod.Post,
            "/query/logs",
            query = query(
                "labels" to args.stringOrNull("labels"),
                "export_format" to args.stringOrNull("export_format"),
            ) + queryResultQuery(args),
            jsonBody = body,
            base = ApiBase.LOG_SEARCH,
        )
        client.awaitQueryCompletion(initial, wait, timeout).toToolResult()
    }

    apiTool(
        name = "logsearch_query_logsets_by_name",
        description = "Run a LEQL query against one or more log sets identified by name (Log Search API).",
        readOnly = true,
        inputSchema = toolSchema("logset_name") {
            stringArrayParam("logset_name", "Name(s) of the log set(s) to query.")
            stringParam("query", "A valid LEQL query. If omitted, all entries in the time range are returned.")
            timeWindowParams()
            stringParam("label", "Only return entries carrying a label with this UUID.")
            stringParam("labels", "':'-separated label UUIDs to filter by.")
            queryResultParams()
            pollingParams()
        },
    ) { args ->
        val (wait, timeout) = args.pollArgs()
        requireTimeWindow(args)
        val names = args.arrayOrNull("logset_name")?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?: args.stringOrNull("logset_name")?.let { listOf(it) }
            ?: throw IllegalArgumentException("Missing required parameter 'logset_name'")
        val initial = client.request(
            HttpMethod.Get,
            "/query/logsets",
            query = query(
                "logset_name" to names,
                "query" to args.stringOrNull("query"),
                "label" to args.stringOrNull("label"),
                "labels" to args.stringOrNull("labels"),
            ) + timeWindowQuery(args) + queryResultQuery(args),
            base = ApiBase.LOG_SEARCH,
        )
        client.awaitQueryCompletion(initial, wait, timeout).toToolResult()
    }

    apiTool(
        name = "logsearch_query_logset",
        description = "Run a LEQL query against a single log set by id (Log Search API).",
        readOnly = true,
        inputSchema = toolSchema("logset_id") {
            stringParam("logset_id", "The id of the log set to query.")
            stringParam("query", "A valid LEQL query. If omitted, all entries in the time range are returned.")
            timeWindowParams()
            stringParam("label", "Only return entries carrying a label with this UUID.")
            stringParam("labels", "':'-separated label UUIDs to filter by.")
            queryResultParams()
            pollingParams()
        },
    ) { args ->
        val (wait, timeout) = args.pollArgs()
        requireTimeWindow(args)
        val initial = client.request(
            HttpMethod.Get,
            "/query/logsets/${seg(args.requireString("logset_id"))}",
            query = query(
                "query" to args.stringOrNull("query"),
                "label" to args.stringOrNull("label"),
                "labels" to args.stringOrNull("labels"),
            ) + timeWindowQuery(args) + queryResultQuery(args),
            base = ApiBase.LOG_SEARCH,
        )
        client.awaitQueryCompletion(initial, wait, timeout).toToolResult()
    }

    apiTool(
        name = "logsearch_poll_query",
        description = "Poll an in-progress Log Search query by its continuation id (returned in a 202 response). " +
            "Returns the current progress or the final result.",
        readOnly = true,
        inputSchema = toolSchema("query_id") {
            stringParam("query_id", "The continuation id generated when the query started.")
            stringParam("time_range", "Optional relative time range, matching the original query.")
            pollingParams()
        },
    ) { args ->
        val (wait, timeout) = args.pollArgs()
        val initial = client.request(
            HttpMethod.Get,
            "/query/${seg(args.requireString("query_id"))}",
            query = query("time_range" to args.stringOrNull("time_range")),
            base = ApiBase.LOG_SEARCH,
        )
        client.awaitQueryCompletion(initial, wait, timeout).toToolResult()
    }

    apiTool(
        name = "logsearch_get_context_events",
        description = "Retrieve the log entries immediately before and/or after a specific log entry " +
            "(Log Search API, GET /query/context/{sequence_number}).",
        readOnly = true,
        inputSchema = toolSchema("sequence_number", "timestamp", "log_key", "context_type") {
            integerParam("sequence_number", "The sequence number of the log entry to fetch context for.")
            stringParam("timestamp", "The timestamp of the log entry to fetch contextual events for.")
            stringParam("log_key", "The key of the log containing the log entry.")
            stringParam("context_type", "Which context to return relative to the entry.", enum = listOf("BEFORE", "AFTER", "SURROUND"))
            integerParam("per_page", "Number of log entries per page, up to 500.")
            booleanParam("kvp_info", "When true, include parsed key-value-pair info.")
            booleanParam("most_recent_first", "When true, return most recent events first.")
            pollingParams()
        },
    ) { args ->
        val (wait, timeout) = args.pollArgs()
        val initial = client.request(
            HttpMethod.Get,
            "/query/context/${seg(args.requireString("sequence_number"))}",
            query = query(
                "timestamp" to args.requireString("timestamp"),
                "log_keys" to args.requireString("log_key"),
                "context_type" to args.requireString("context_type"),
                "per_page" to args.intOrNull("per_page"),
                "kvp_info" to args.booleanOrNull("kvp_info"),
                "most_recent_first" to args.booleanOrNull("most_recent_first"),
            ),
            base = ApiBase.LOG_SEARCH,
        )
        client.awaitQueryCompletion(initial, wait, timeout).toToolResult()
    }

    apiTool(
        name = "logsearch_get_search_stats",
        description = "View statistics on past Log Search queries run in your organization (GET /search-stats).",
        readOnly = true,
    ) { _ ->
        client.request(HttpMethod.Get, "/search-stats", base = ApiBase.LOG_SEARCH).toToolResult()
    }

    apiTool(
        name = "logsearch_list_query_endpoints",
        description = "List the available Log Search query endpoints (GET /query). Mainly useful as a connectivity check.",
        readOnly = true,
    ) { _ ->
        client.request(HttpMethod.Get, "/query", base = ApiBase.LOG_SEARCH).toToolResult()
    }

    // ------------------------------------------------------------------
    // Saved queries
    // ------------------------------------------------------------------

    apiTool(
        name = "logsearch_list_saved_queries",
        description = "List all Log Search saved queries.",
        readOnly = true,
    ) { _ ->
        client.request(HttpMethod.Get, "/query/saved_queries", base = ApiBase.LOG_SEARCH).toToolResult()
    }

    apiTool(
        name = "logsearch_get_saved_query",
        description = "Retrieve a Log Search saved query by id.",
        readOnly = true,
        inputSchema = toolSchema("saved_query_id") { stringParam("saved_query_id", "The id of the saved query.") },
    ) { args ->
        client.request(
            HttpMethod.Get,
            "/query/saved_queries/${seg(args.requireString("saved_query_id"))}",
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_create_saved_query",
        description = "Create a Log Search saved query. The LEQL statement is required; logs and a time window are optional.",
        inputSchema = toolSchema("name", "statement") {
            stringParam("name", "The name for the saved query (does not need to be unique).")
            stringParam("statement", "The LEQL statement, e.g. where(status=500) calculate(count).")
            timeWindowParams()
            stringArrayParam("logs", "Optional log keys the saved query applies to.")
        },
    ) { args ->
        val body = buildJsonObject {
            putJsonObject("saved_query") {
                put("name", args.requireString("name"))
                put("leql", leqlObject(args.requireStringAllowEmpty("statement"), args))
                putOpt("logs", args.arrayOrNull("logs"))
            }
        }
        client.request(HttpMethod.Post, "/query/saved_queries", jsonBody = body, base = ApiBase.LOG_SEARCH).toToolResult()
    }

    apiTool(
        name = "logsearch_replace_saved_query",
        description = "Replace a Log Search saved query (PUT). All fields are replaced with the provided values.",
        inputSchema = toolSchema("saved_query_id", "name", "statement") {
            stringParam("saved_query_id", "The id of the saved query to replace.")
            stringParam("name", "The new name for the saved query.")
            stringParam("statement", "The new LEQL statement.")
            timeWindowParams()
            stringArrayParam("logs", "Optional log keys the saved query applies to.")
        },
    ) { args ->
        val body = buildJsonObject {
            putJsonObject("saved_query") {
                put("name", args.requireString("name"))
                put("leql", leqlObject(args.requireStringAllowEmpty("statement"), args))
                putOpt("logs", args.arrayOrNull("logs"))
            }
        }
        client.request(
            HttpMethod.Put,
            "/query/saved_queries/${seg(args.requireString("saved_query_id"))}",
            jsonBody = body,
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_update_saved_query",
        description = "Modify parts of a Log Search saved query (PATCH). Only provided fields are changed.",
        inputSchema = toolSchema("saved_query_id") {
            stringParam("saved_query_id", "The id of the saved query to update.")
            stringParam("name", "New name for the saved query.")
            stringParam("statement", "New LEQL statement.")
            timeWindowParams()
            stringArrayParam("logs", "New set of log keys the saved query applies to.")
        },
    ) { args ->
        val body = buildJsonObject {
            putJsonObject("saved_query") {
                putOpt("name", args.stringOrNull("name"))
                // Supports a during-only update: leql is included whenever a statement OR a window is given.
                leqlObjectForPatch(args.stringOrNull("statement"), args)?.let { put("leql", it) }
                putOpt("logs", args.arrayOrNull("logs"))
            }
        }
        client.request(
            HttpMethod.Patch,
            "/query/saved_queries/${seg(args.requireString("saved_query_id"))}",
            jsonBody = body,
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_delete_saved_query",
        description = "Delete a Log Search saved query by id.",
        destructive = true,
        inputSchema = toolSchema("saved_query_id") { stringParam("saved_query_id", "The id of the saved query to delete.") },
    ) { args ->
        client.request(
            HttpMethod.Delete,
            "/query/saved_queries/${seg(args.requireString("saved_query_id"))}",
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_run_saved_query",
        description = "Run a saved query that already specifies its target logs (GET /query/saved_query/{id}). " +
            "Optionally override the time window.",
        readOnly = true,
        inputSchema = toolSchema("saved_query_id") {
            stringParam("saved_query_id", "The id of the saved query to run.")
            timeWindowParams()
            integerParam("per_page", "Number of log entries per page, up to 500.")
            booleanParam("kvp_info", "When true, include parsed key-value-pair info.")
            booleanParam("most_recent_first", "When true, return most recent events first.")
            pollingParams()
        },
    ) { args ->
        val (wait, timeout) = args.pollArgs()
        val initial = client.request(
            HttpMethod.Get,
            "/query/saved_query/${seg(args.requireString("saved_query_id"))}",
            query = timeWindowQuery(args) + query(
                "per_page" to args.intOrNull("per_page"),
                "kvp_info" to args.booleanOrNull("kvp_info"),
                "most_recent_first" to args.booleanOrNull("most_recent_first"),
            ),
            base = ApiBase.LOG_SEARCH,
        )
        client.awaitQueryCompletion(initial, wait, timeout).toToolResult()
    }

    apiTool(
        name = "logsearch_run_saved_query_on_logs",
        description = "Run a saved query against explicit logs (GET /query/logs/{log_keys}/{saved_query_id}), " +
            "for saved queries that don't specify logs themselves.",
        readOnly = true,
        inputSchema = toolSchema("log_keys", "saved_query_id") {
            stringParam("log_keys", "The keys of the logs to query, separated by ':'.")
            stringParam("saved_query_id", "The id of the saved query to run.")
            timeWindowParams()
            integerParam("per_page", "Number of log entries per page, up to 500.")
            booleanParam("kvp_info", "When true, include parsed key-value-pair info.")
            booleanParam("most_recent_first", "When true, return most recent events first.")
            pollingParams()
        },
    ) { args ->
        val (wait, timeout) = args.pollArgs()
        val initial = client.request(
            HttpMethod.Get,
            "/query/logs/${seg(args.requireString("log_keys"))}/${seg(args.requireString("saved_query_id"))}",
            query = timeWindowQuery(args) + query(
                "per_page" to args.intOrNull("per_page"),
                "kvp_info" to args.booleanOrNull("kvp_info"),
                "most_recent_first" to args.booleanOrNull("most_recent_first"),
            ),
            base = ApiBase.LOG_SEARCH,
        )
        client.awaitQueryCompletion(initial, wait, timeout).toToolResult()
    }
}
