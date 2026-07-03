package com.jitunicornfx.insightidr.mcp.tools

import com.jitunicornfx.insightidr.mcp.*
import com.jitunicornfx.insightidr.mcp.Rapid7Client.ApiBase
import io.ktor.http.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Log Search API — logs & log sets management, most-common-keys, raw download,
 * data-usage reporting, and CSV export jobs.
 */
fun Server.registerLogSearchManagementTools(client: Rapid7Client) {

    // ------------------------------------------------------------------
    // Logs & log sets
    // ------------------------------------------------------------------

    apiTool(
        name = "logsearch_list_logs",
        description = "Retrieve all logs in the organization (Log Search API). Returns each log's id, name, " +
            "log set membership and metadata — log ids/keys from here feed the query tools.",
        readOnly = true,
    ) { _ ->
        client.request(HttpMethod.Get, "/management/logs", base = ApiBase.LOG_SEARCH).toToolResult()
    }

    apiTool(
        name = "logsearch_get_log",
        description = "Retrieve a single log by id (Log Search API).",
        readOnly = true,
        inputSchema = toolSchema("log_id") { stringParam("log_id", "The id (UUID) of the log.") },
    ) { args ->
        client.request(
            HttpMethod.Get,
            "/management/logs/${seg(args.requireString("log_id"))}",
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_delete_log",
        description = "Delete a log by id (Log Search API). Deletes the log and its stored data.",
        destructive = true,
        inputSchema = toolSchema("log_id") { stringParam("log_id", "The id (UUID) of the log to delete.") },
    ) { args ->
        client.request(
            HttpMethod.Delete,
            "/management/logs/${seg(args.requireString("log_id"))}",
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_get_log_event_sources",
        description = "Retrieve the event sources feeding a specific log (Log Search API).",
        readOnly = true,
        inputSchema = toolSchema("log_id") { stringParam("log_id", "The id (UUID) of the log.") },
    ) { args ->
        client.request(
            HttpMethod.Get,
            "/management/logs/${seg(args.requireString("log_id"))}/event-sources",
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_list_logsets",
        description = "List all log sets (Log Search API).",
        readOnly = true,
    ) { _ ->
        client.request(HttpMethod.Get, "/management/logsets", base = ApiBase.LOG_SEARCH).toToolResult()
    }

    apiTool(
        name = "logsearch_get_logset",
        description = "Retrieve a log set by id (Log Search API).",
        readOnly = true,
        inputSchema = toolSchema("logset_id") { stringParam("logset_id", "The id (UUID) of the log set.") },
    ) { args ->
        client.request(
            HttpMethod.Get,
            "/management/logsets/${seg(args.requireString("logset_id"))}",
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_replace_logset",
        description = "Replace a log set's definition (PUT, Log Search API). Provide the full logset object " +
            "as returned by logsearch_get_logset (fields: id, name, description, user_data, logs_info, rrn).",
        inputSchema = toolSchema("logset_id", "logset") {
            stringParam("logset_id", "The id (UUID) of the log set to replace.")
            objectParam("logset", "The full replacement log set object (same shape as the API returns).")
        },
    ) { args ->
        val logset = args.objectOrNull("logset")
            ?: throw IllegalArgumentException("Missing required parameter 'logset'")
        // The API expects {"logset": {...}}; tolerate callers who already supplied the wrapper.
        val body = if (logset.keys == setOf("logset")) logset else buildJsonObject { put("logset", logset) }
        client.request(
            HttpMethod.Put,
            "/management/logsets/${seg(args.requireString("logset_id"))}",
            jsonBody = body,
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_delete_logset",
        description = "Delete a log set by id (Log Search API). Logs in the set are not deleted.",
        destructive = true,
        inputSchema = toolSchema("logset_id") { stringParam("logset_id", "The id (UUID) of the log set to delete.") },
    ) { args ->
        client.request(
            HttpMethod.Delete,
            "/management/logsets/${seg(args.requireString("logset_id"))}",
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    // ------------------------------------------------------------------
    // Most common keys
    // ------------------------------------------------------------------

    apiTool(
        name = "logsearch_get_log_top_keys",
        description = "Retrieve the most common keys within a log's recent data (Log Search API). Useful for " +
            "discovering which fields are available to build LEQL queries against.",
        readOnly = true,
        inputSchema = toolSchema("log_id") { stringParam("log_id", "The id (UUID) of the log.") },
    ) { args ->
        client.request(
            HttpMethod.Get,
            "/management/logs/${seg(args.requireString("log_id"))}/topkeys",
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    // ------------------------------------------------------------------
    // Raw download
    // ------------------------------------------------------------------

    apiTool(
        name = "logsearch_download_log_data",
        description = "Download raw log entries (one per line) from up to 10 logs (Log Search API). " +
            "WARNING: without a limit this can return very large amounts of data; prefer a small 'limit' " +
            "or a filtered LEQL 'query'.",
        readOnly = true,
        inputSchema = toolSchema("log_ids") {
            stringParam("log_ids", "The UUIDs of the logs, separated by ':' or ';' (max 10 logs).")
            timeWindowParams()
            stringParam("query", "Optional LEQL query to filter the downloaded entries.")
            integerParam("limit", "Maximum number of log entries to download.")
        },
    ) { args ->
        requireTimeWindow(args)
        client.request(
            HttpMethod.Get,
            "/download/logs/${seg(args.requireString("log_ids"))}",
            query = timeWindowQuery(args) + query(
                "query" to args.stringOrNull("query"),
                "limit" to args.longOrNull("limit"),
            ),
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    // ------------------------------------------------------------------
    // Data usage
    // ------------------------------------------------------------------

    apiTool(
        name = "logsearch_get_usage_total",
        description = "Total log data size uploaded across all logs for a date range (Log Search API). " +
            "Dates use the YYYY-MM-DD format.",
        readOnly = true,
        inputSchema = toolSchema("from", "to") {
            stringParam("from", "Start date of the range, formatted YYYY-MM-DD.")
            stringParam("to", "End date of the range, formatted YYYY-MM-DD.")
        },
    ) { args ->
        client.request(
            HttpMethod.Get,
            "/usage/organizations",
            query = query("from" to args.requireString("from"), "to" to args.requireString("to")),
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_get_usage_per_log",
        description = "Log data usage broken down per log for a date range (Log Search API).",
        readOnly = true,
        inputSchema = toolSchema {
            stringParam("from", "Start date of the range, formatted YYYY-MM-DD.")
            stringParam("to", "End date of the range, formatted YYYY-MM-DD.")
            stringParam("time_range", "Relative alternative to from/to, e.g. 'last 7 days'.")
        },
    ) { args ->
        client.request(
            HttpMethod.Get,
            "/usage/organizations/logs",
            query = query(
                "from" to args.stringOrNull("from"),
                "to" to args.stringOrNull("to"),
                "time_range" to args.stringOrNull("time_range"),
            ),
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_get_log_usage",
        description = "Log data usage for one specific log over a date range (Log Search API).",
        readOnly = true,
        inputSchema = toolSchema("log_key", "from", "to") {
            stringParam("log_key", "The key of the log.")
            stringParam("from", "Start date of the range, formatted YYYY-MM-DD.")
            stringParam("to", "End date of the range, formatted YYYY-MM-DD.")
        },
    ) { args ->
        client.request(
            HttpMethod.Get,
            "/usage/organizations/logs/${seg(args.requireString("log_key"))}",
            query = query("from" to args.requireString("from"), "to" to args.requireString("to")),
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    // ------------------------------------------------------------------
    // CSV export jobs
    // ------------------------------------------------------------------

    apiTool(
        name = "logsearch_list_export_jobs",
        description = "Retrieve all CSV export jobs (Log Search API). Export jobs are created by running a " +
            "query with export_format=csv.",
        readOnly = true,
    ) { _ ->
        client.request(HttpMethod.Get, "/exports", base = ApiBase.LOG_SEARCH).toToolResult()
    }

    apiTool(
        name = "logsearch_get_export_job",
        description = "Retrieve a CSV export job by id (Log Search API).",
        readOnly = true,
        inputSchema = toolSchema("export_job_id") { stringParam("export_job_id", "The UUID of the export job.") },
    ) { args ->
        client.request(
            HttpMethod.Get,
            "/exports/${seg(args.requireString("export_job_id"))}",
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_delete_export_job",
        description = "Delete a CSV export job by id (Log Search API).",
        destructive = true,
        inputSchema = toolSchema("export_job_id") { stringParam("export_job_id", "The UUID of the export job to delete.") },
    ) { args ->
        client.request(
            HttpMethod.Delete,
            "/exports/${seg(args.requireString("export_job_id"))}",
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }
}
