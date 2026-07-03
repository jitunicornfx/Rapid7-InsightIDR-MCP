package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.tools.registerLogSearchManagementTools
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogSearchManagementToolsTest {

    private suspend fun harness(body: String = "{}") =
        mcpHarness(responseBody = body) { registerLogSearchManagementTools(it) }

    @Test
    fun `log management endpoints resolve their paths`() = runBlocking {
        val h = harness(body = "[]")
        h.call("logsearch_list_logs")
        assertEquals("/log_search/management/logs", h.lastRequest.url.encodedPath)

        h.call("logsearch_get_log", mapOf("log_id" to "log1"))
        assertEquals("/log_search/management/logs/log1", h.lastRequest.url.encodedPath)

        h.call("logsearch_delete_log", mapOf("log_id" to "log1"))
        assertEquals(HttpMethod.Delete, h.lastRequest.method)
        assertEquals("/log_search/management/logs/log1", h.lastRequest.url.encodedPath)

        h.call("logsearch_get_log_event_sources", mapOf("log_id" to "log1"))
        assertEquals("/log_search/management/logs/log1/event-sources", h.lastRequest.url.encodedPath)

        h.call("logsearch_get_log_top_keys", mapOf("log_id" to "log1"))
        assertEquals("/log_search/management/logs/log1/topkeys", h.lastRequest.url.encodedPath)
    }

    @Test
    fun `logset management endpoints resolve their paths`() = runBlocking {
        val h = harness(body = "[]")
        h.call("logsearch_list_logsets")
        assertEquals("/log_search/management/logsets", h.lastRequest.url.encodedPath)

        h.call("logsearch_get_logset", mapOf("logset_id" to "ls1"))
        assertEquals("/log_search/management/logsets/ls1", h.lastRequest.url.encodedPath)

        h.call("logsearch_replace_logset", mapOf("logset_id" to "ls1", "logset" to mapOf("name" to "X")))
        assertEquals(HttpMethod.Put, h.lastRequest.method)
        assertEquals("/log_search/management/logsets/ls1", h.lastRequest.url.encodedPath)
        // The API expects the {"logset": {...}} wrapper to be added automatically.
        assertTrue("logset" in h.lastBodyJson())

        h.call("logsearch_delete_logset", mapOf("logset_id" to "ls1"))
        assertEquals(HttpMethod.Delete, h.lastRequest.method)
    }

    @Test
    fun `download_log_data puts ids in the path with window and limit`() = runBlocking {
        val h = harness()
        h.call(
            "logsearch_download_log_data",
            mapOf("log_ids" to "a:b", "time_range" to "last 1 hour", "limit" to 1000, "query" to "where(x)"),
        )
        val req = h.lastRequest
        assertEquals("/log_search/download/logs/a:b", req.url.encodedPath.replace("%3A", ":"))
        assertEquals("last 1 hour", req.url.parameters["time_range"])
        assertEquals("1000", req.url.parameters["limit"])
        assertEquals("where(x)", req.url.parameters["query"])
    }

    @Test
    fun `usage endpoints send YYYY-MM-DD from and to`() = runBlocking {
        val h = harness()
        h.call("logsearch_get_usage_total", mapOf("from" to "2026-06-01", "to" to "2026-06-30"))
        assertEquals("/log_search/usage/organizations", h.lastRequest.url.encodedPath)
        assertEquals("2026-06-01", h.lastRequest.url.parameters["from"])
        assertEquals("2026-06-30", h.lastRequest.url.parameters["to"])

        h.call("logsearch_get_usage_per_log", mapOf("time_range" to "last 7 days"))
        assertEquals("/log_search/usage/organizations/logs", h.lastRequest.url.encodedPath)
        assertEquals("last 7 days", h.lastRequest.url.parameters["time_range"])

        h.call("logsearch_get_log_usage", mapOf("log_key" to "lk1", "from" to "2026-06-01", "to" to "2026-06-30"))
        assertEquals("/log_search/usage/organizations/logs/lk1", h.lastRequest.url.encodedPath)
    }

    @Test
    fun `export job endpoints resolve their paths`() = runBlocking {
        val h = harness(body = "[]")
        h.call("logsearch_list_export_jobs")
        assertEquals("/log_search/exports", h.lastRequest.url.encodedPath)

        h.call("logsearch_get_export_job", mapOf("export_job_id" to "ex1"))
        assertEquals("/log_search/exports/ex1", h.lastRequest.url.encodedPath)

        h.call("logsearch_delete_export_job", mapOf("export_job_id" to "ex1"))
        assertEquals(HttpMethod.Delete, h.lastRequest.method)
        assertEquals("/log_search/exports/ex1", h.lastRequest.url.encodedPath)
    }

    @Test
    fun `replace_logset without a body object is a tool error`() = runBlocking {
        val h = harness()
        val result = h.call("logsearch_replace_logset", mapOf("logset_id" to "ls1"))
        assertTrue(result.isError == true)
    }
}
