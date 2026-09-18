package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.tools.registerLogSearchManagementTools
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogSearchManagementToolsTest {

    private suspend fun harness(body: String = "{}") =
        mcpHarness(responseBody = body) { registerLogSearchManagementTools(it) }

    @Test
    fun `log management endpoints resolve their paths`() = runBlocking {
        val h = harness(body = "[]")
        h.call("logsearch_list_logs")
        assertEquals("/management/logs", h.lastRequest.url.encodedPath)

        h.call("logsearch_get_log", mapOf("log_id" to "log1"))
        assertEquals("/management/logs/log1", h.lastRequest.url.encodedPath)

        h.call("logsearch_delete_log", mapOf("log_id" to "log1"))
        assertEquals(HttpMethod.Delete, h.lastRequest.method)
        assertEquals("/management/logs/log1", h.lastRequest.url.encodedPath)

        h.call("logsearch_get_log_event_sources", mapOf("log_id" to "log1"))
        assertEquals("/management/logs/log1/event-sources", h.lastRequest.url.encodedPath)

        h.call("logsearch_get_log_top_keys", mapOf("log_id" to "log1"))
        assertEquals("/management/logs/log1/topkeys", h.lastRequest.url.encodedPath)
    }

    @Test
    fun `logset management endpoints resolve their paths`() = runBlocking {
        val h = harness(body = "[]")
        h.call("logsearch_list_logsets")
        assertEquals("/management/logsets", h.lastRequest.url.encodedPath)

        h.call("logsearch_get_logset", mapOf("logset_id" to "ls1"))
        assertEquals("/management/logsets/ls1", h.lastRequest.url.encodedPath)

        h.call("logsearch_replace_logset", mapOf("logset_id" to "ls1", "logset" to mapOf("name" to "X")))
        assertEquals(HttpMethod.Put, h.lastRequest.method)
        assertEquals("/management/logsets/ls1", h.lastRequest.url.encodedPath)
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
        assertEquals("/download/logs/a:b", req.url.encodedPath.replace("%3A", ":"))
        assertEquals("last 1 hour", req.url.parameters["time_range"])
        assertEquals("1000", req.url.parameters["limit"])
        assertEquals("where(x)", req.url.parameters["query"])
    }

    @Test
    fun `the per-log usage tool requires a window before it calls the API`() = runBlocking {
        // Its schema marks nothing required (either form is valid), so nothing enforced it at all:
        // an empty call went to the API and came back as a 400.
        val h = harness()
        fun text(r: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult) =
            (r.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text

        val empty = h.call("logsearch_get_usage_per_log", emptyMap())
        assertTrue(empty.isError == true)
        assertTrue("time_range" in text(empty) && "YYYY-MM-DD" in text(empty), "say what is accepted: ${text(empty)}")

        assertTrue(h.call("logsearch_get_usage_per_log", mapOf("from" to "2026-06-01")).isError == true, "half a window")

        // The spec: "If time_range is used, then the from and to query parameters must not be used."
        val both = h.call("logsearch_get_usage_per_log", mapOf("time_range" to "yesterday", "from" to "2026-06-01", "to" to "2026-06-30"))
        assertTrue(both.isError == true)
        assertTrue("cannot be combined" in text(both))

        assertEquals(0, h.requests.size, "none of these may reach the API")
    }

    @Test
    fun `time_range is accepted only by the usage endpoint the spec gives it to`() = runBlocking {
        val h = harness()
        // The spec defines time_range on /usage/organizations/logs alone. The other two would drop it
        // and answer 400 for the missing from/to.
        fun text(r: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult) =
            (r.content.first() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
        for (refused in listOf(
            h.call("logsearch_get_usage_total", mapOf("time_range" to "last 7 days")),
            h.call("logsearch_get_log_usage", mapOf("log_key" to "lk1", "time_range" to "last 7 days")),
        )) {
            assertTrue(refused.isError == true)
            // Naming one missing parameter at a time makes the model fix 'from', retry, and then be
            // told about 'to'. Say both, and do not offer time_range as a way out here.
            assertTrue("'from' and 'to'" in text(refused), "was: ${text(refused)}")
            assertFalse("time_range" in text(refused).substringAfter("':"), "was: ${text(refused)}")
        }
        assertEquals(0, h.requests.size)

        assertFalse(h.call("logsearch_get_usage_per_log", mapOf("time_range" to "last 7 days")).isError == true)
        assertEquals("last 7 days", h.lastRequest.url.parameters["time_range"])
    }

    @Test
    fun `usage dates are checked as dates, not as epoch milliseconds`() = runBlocking {
        val h = harness()
        // The query tools' window check reads from/to as longs and would reject every one of these.
        for (tool in listOf("logsearch_get_usage_total", "logsearch_get_usage_per_log")) {
            assertFalse(h.call(tool, mapOf("from" to "2026-06-01", "to" to "2026-06-30")).isError == true, tool)
        }
        val sent = h.requests.size

        val bad = listOf(
            mapOf("from" to "1767225600000", "to" to "1769817600000"), // epoch millis, as the query tools take
            mapOf("from" to "06/01/2026", "to" to "06/30/2026"),
            mapOf("from" to "2026-02-30", "to" to "2026-03-01"),       // not a real date
            mapOf("from" to "2026-06-30", "to" to "2026-06-01"),       // backwards
        )
        for (args in bad) {
            assertTrue(h.call("logsearch_get_usage_total", args).isError == true, "$args")
            assertTrue(h.call("logsearch_get_log_usage", args + ("log_key" to "lk1")).isError == true, "$args")
        }
        assertEquals(sent, h.requests.size, "a malformed window must be refused before the API is called")
    }

    @Test
    fun `usage endpoints send YYYY-MM-DD from and to`() = runBlocking {
        val h = harness()
        h.call("logsearch_get_usage_total", mapOf("from" to "2026-06-01", "to" to "2026-06-30"))
        assertEquals("/usage/organizations", h.lastRequest.url.encodedPath)
        assertEquals("2026-06-01", h.lastRequest.url.parameters["from"])
        assertEquals("2026-06-30", h.lastRequest.url.parameters["to"])

        h.call("logsearch_get_usage_per_log", mapOf("time_range" to "last 7 days"))
        assertEquals("/usage/organizations/logs", h.lastRequest.url.encodedPath)
        assertEquals("last 7 days", h.lastRequest.url.parameters["time_range"])

        h.call("logsearch_get_log_usage", mapOf("log_key" to "lk1", "from" to "2026-06-01", "to" to "2026-06-30"))
        assertEquals("/usage/organizations/logs/lk1", h.lastRequest.url.encodedPath)
    }

    @Test
    fun `export job endpoints resolve their paths`() = runBlocking {
        val h = harness(body = "[]")
        h.call("logsearch_list_export_jobs")
        assertEquals("/exports", h.lastRequest.url.encodedPath)

        h.call("logsearch_get_export_job", mapOf("export_job_id" to "ex1"))
        assertEquals("/exports/ex1", h.lastRequest.url.encodedPath)

        h.call("logsearch_delete_export_job", mapOf("export_job_id" to "ex1"))
        assertEquals(HttpMethod.Delete, h.lastRequest.method)
        assertEquals("/exports/ex1", h.lastRequest.url.encodedPath)
    }

    @Test
    fun `replace_logset without a body object is a tool error`() = runBlocking {
        val h = harness()
        val result = h.call("logsearch_replace_logset", mapOf("logset_id" to "ls1"))
        assertTrue(result.isError == true)
    }
}
