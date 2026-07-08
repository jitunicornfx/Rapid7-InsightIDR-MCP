package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.tools.registerLogSearchAuditTools
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogSearchAuditToolsTest {

    private suspend fun harness(
        body: String = "{}",
        responses: List<Pair<HttpStatusCode, String>>? = null,
    ) = mcpHarness(responseBody = body, responses = responses) { registerLogSearchAuditTools(it) }

    @Test
    fun `audit log management endpoints resolve their paths`() = runBlocking {
        val h = harness(body = "[]")
        h.call("logsearch_list_audit_logs")
        assertEquals("/audit/management/logs", h.lastRequest.url.encodedPath)

        h.call("logsearch_get_audit_log", mapOf("log_id" to "al1"))
        assertEquals("/audit/management/logs/al1", h.lastRequest.url.encodedPath)
    }

    @Test
    fun `audit query endpoints resolve their paths with LEQL params`() = runBlocking {
        val h = harness()
        h.call(
            "logsearch_audit_query_log",
            mapOf("log_key" to "al1", "query" to "where(action=login)", "time_range" to "today"),
        )
        assertEquals(HttpMethod.Get, h.lastRequest.method)
        assertEquals("/audit/query/logs/al1", h.lastRequest.url.encodedPath)
        assertEquals("where(action=login)", h.lastRequest.url.parameters["query"])

        h.call(
            "logsearch_audit_query_logs",
            mapOf("log_keys" to listOf("al1", "al2"), "query" to "where(x)", "time_range" to "today"),
        )
        assertEquals(HttpMethod.Post, h.lastRequest.method)
        assertEquals("/audit/query/logs", h.lastRequest.url.encodedPath)
        val body = h.lastBodyJson()
        assertEquals(2, body["logs"]!!.jsonArray.size)
        assertEquals("where(x)", body["leql"]!!.jsonObject["statement"]!!.jsonPrimitive.content)

        h.call("logsearch_audit_poll_query", mapOf("query_id" to "cont-a"))
        assertEquals("/audit/query/cont-a", h.lastRequest.url.encodedPath)

        h.call("logsearch_audit_list_query_endpoints")
        assertEquals("/audit/query", h.lastRequest.url.encodedPath)
    }

    @Test
    fun `audit query auto-polls 202 continuations`() = runBlocking {
        val poll = "https://us.rest.logs.insight.rapid7.com/audit/query/cont-b"
        val h = harness(
            responses = listOf(
                HttpStatusCode.Accepted to """{"id":"cont-b","links":[{"rel":"Self","href":"$poll"}]}""",
                HttpStatusCode.OK to """{"events":[]}""",
            ),
        )
        val result = h.call("logsearch_audit_query_log", mapOf("log_key" to "al1", "time_range" to "today"))
        assertEquals(2, h.requests.size)
        assertEquals("/audit/query/cont-b", h.lastRequest.url.encodedPath)
        assertTrue(result.isError != true)
    }

    @Test
    fun `audit export endpoints resolve their paths`() = runBlocking {
        val h = harness(body = "[]")
        h.call("logsearch_audit_list_export_jobs")
        assertEquals("/audit/exports", h.lastRequest.url.encodedPath)

        h.call("logsearch_audit_get_export_job", mapOf("export_job_id" to "ex1"))
        assertEquals("/audit/exports/ex1", h.lastRequest.url.encodedPath)
    }
}
