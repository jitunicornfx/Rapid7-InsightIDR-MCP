package com.jitunicornfx.insightidr.mcp.tools

import com.jitunicornfx.insightidr.mcp.*
import com.jitunicornfx.insightidr.mcp.Rapid7Client.ApiBase
import io.ktor.http.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Log Search API — the Audit API variants, which run against the organization's
 * platform audit logs instead of regular event logs.
 */
fun Server.registerLogSearchAuditTools(client: Rapid7Client) {

    apiTool(
        name = "logsearch_list_audit_logs",
        description = "Retrieve all audit logs available to the organization (Log Search Audit API).",
        readOnly = true,
    ) { _ ->
        client.request(HttpMethod.Get, "/audit/management/logs", base = ApiBase.LOG_SEARCH).toToolResult()
    }

    apiTool(
        name = "logsearch_get_audit_log",
        description = "Retrieve a single audit log by id (Log Search Audit API).",
        readOnly = true,
        inputSchema = toolSchema("log_id") { stringParam("log_id", "The id (UUID) of the audit log.") },
    ) { args ->
        client.request(
            HttpMethod.Get,
            "/audit/management/logs/${seg(args.requireString("log_id"))}",
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_audit_query_log",
        description = "Run a LEQL query against one audit log (Log Search Audit API). Polls asynchronous " +
            "results to completion by default.",
        readOnly = true,
        inputSchema = toolSchema("log_key") {
            stringParam("log_key", "The key (UUID) of the audit log to query.")
            stringParam("query", "A valid LEQL query. If omitted, all entries in the time range are returned.")
            timeWindowParams()
            stringParam("label", "Only return entries carrying a label with this UUID.")
            stringParam("labels", "':'-separated label UUIDs to filter by.")
            stringParam("export_format", "If set, export results in this format. Only 'csv' is supported.")
            queryResultParams()
            pollingParams()
        },
    ) { args ->
        val (wait, timeout) = args.pollArgs()
        requireTimeWindow(args)
        val initial = client.request(
            HttpMethod.Get,
            "/audit/query/logs/${seg(args.requireString("log_key"))}",
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
        name = "logsearch_audit_query_logs",
        description = "Run a LEQL query across multiple audit logs (Log Search Audit API, POST /audit/query/logs). " +
            "A time window (time_range, or from+to) is required.",
        readOnly = true,
        inputSchema = toolSchema("log_keys") {
            stringArrayParam("log_keys", "The keys (UUIDs) of the audit logs to query.")
            stringParam("query", "The LEQL statement to run. If omitted, all entries in the time window are returned.")
            timeWindowParams()
            stringParam("labels", "':'-separated label UUIDs; only entries with a matching label are returned.")
            stringParam("export_format", "If set, export results in this format. Only 'csv' is supported.")
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
            "/audit/query/logs",
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
        name = "logsearch_audit_poll_query",
        description = "Poll an in-progress audit-log query by its continuation id (Log Search Audit API).",
        readOnly = true,
        inputSchema = toolSchema("query_id") {
            stringParam("query_id", "The continuation id generated when the audit query started.")
            stringParam("time_range", "Optional relative time range, matching the original query.")
            pollingParams()
        },
    ) { args ->
        val (wait, timeout) = args.pollArgs()
        val initial = client.request(
            HttpMethod.Get,
            "/audit/query/${seg(args.requireString("query_id"))}",
            query = query("time_range" to args.stringOrNull("time_range")),
            base = ApiBase.LOG_SEARCH,
        )
        client.awaitQueryCompletion(initial, wait, timeout).toToolResult()
    }

    apiTool(
        name = "logsearch_audit_list_export_jobs",
        description = "Retrieve all audit-log CSV export jobs (Log Search Audit API).",
        readOnly = true,
    ) { _ ->
        client.request(HttpMethod.Get, "/audit/exports", base = ApiBase.LOG_SEARCH).toToolResult()
    }

    apiTool(
        name = "logsearch_audit_get_export_job",
        description = "Retrieve an audit-log CSV export job by id (Log Search Audit API).",
        readOnly = true,
        inputSchema = toolSchema("export_job_id") { stringParam("export_job_id", "The UUID of the export job.") },
    ) { args ->
        client.request(
            HttpMethod.Get,
            "/audit/exports/${seg(args.requireString("export_job_id"))}",
            base = ApiBase.LOG_SEARCH,
        ).toToolResult()
    }

    apiTool(
        name = "logsearch_audit_list_query_endpoints",
        description = "List the available audit-log query endpoints (Log Search Audit API). Mainly a connectivity check.",
        readOnly = true,
    ) { _ ->
        client.request(HttpMethod.Get, "/audit/query", base = ApiBase.LOG_SEARCH).toToolResult()
    }
}
