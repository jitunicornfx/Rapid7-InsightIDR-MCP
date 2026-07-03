package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.tools.registerLogSearchVariableTools
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogSearchVariableToolsTest {

    private suspend fun harness(body: String = "{}") =
        mcpHarness(responseBody = body) { registerLogSearchVariableTools(it) }

    @Test
    fun `variable crud maps to query-variables endpoints`() = runBlocking {
        val h = harness(body = "[]")
        h.call("logsearch_list_variables")
        assertEquals("/log_search/query/variables", h.lastRequest.url.encodedPath)

        h.call("logsearch_get_variable", mapOf("variable_id" to "v1"))
        assertEquals("/log_search/query/variables/v1", h.lastRequest.url.encodedPath)

        h.call("logsearch_create_variable", mapOf("name" to "bad_ips", "value" to "1.2.3.4", "description" to "d"))
        assertEquals(HttpMethod.Post, h.lastRequest.method)
        val created = h.lastBodyJson()["variable"]!!.jsonObject
        assertEquals("bad_ips", created["name"]!!.jsonPrimitive.content)
        assertEquals("1.2.3.4", created["value"]!!.jsonPrimitive.content)

        h.call("logsearch_update_variable", mapOf("variable_id" to "v1", "name" to "n", "value" to "x"))
        assertEquals(HttpMethod.Put, h.lastRequest.method)
        assertEquals("/log_search/query/variables/v1", h.lastRequest.url.encodedPath)

        h.call("logsearch_delete_variable", mapOf("variable_id" to "v1"))
        assertEquals(HttpMethod.Delete, h.lastRequest.method)
    }

    @Test
    fun `metric crud maps to management-metrics endpoints`() = runBlocking {
        val h = harness(body = "[]")
        h.call("logsearch_list_metrics")
        assertEquals("/log_search/management/metrics", h.lastRequest.url.encodedPath)

        h.call("logsearch_get_metric", mapOf("metric_id" to "m1"))
        assertEquals("/log_search/management/metrics/m1", h.lastRequest.url.encodedPath)

        val metric = mapOf("name" to "pcq", "leql" to mapOf("statement" to "where(x)", "function" to "calculate(count)"))
        h.call("logsearch_create_metric", mapOf("metric" to metric))
        assertEquals(HttpMethod.Post, h.lastRequest.method)
        assertEquals("pcq", h.lastBodyJson()["metric"]!!.jsonObject["name"]!!.jsonPrimitive.content)

        h.call("logsearch_replace_metric", mapOf("metric_id" to "m1", "metric" to metric))
        assertEquals(HttpMethod.Put, h.lastRequest.method)
        assertEquals("/log_search/management/metrics/m1", h.lastRequest.url.encodedPath)

        h.call("logsearch_delete_metric", mapOf("metric_id" to "m1"))
        assertEquals(HttpMethod.Delete, h.lastRequest.method)
    }

    @Test
    fun `query_metric fetches results with a time window`() = runBlocking {
        val h = harness()
        h.call("logsearch_query_metric", mapOf("metric_id" to "m1", "time_range" to "last 1 day"))
        assertEquals("/log_search/query/metrics/m1", h.lastRequest.url.encodedPath)
        assertEquals("last 1 day", h.lastRequest.url.parameters["time_range"])
    }

    @Test
    fun `create_variable without value is a tool error`() = runBlocking {
        val h = harness()
        val result = h.call("logsearch_create_variable", mapOf("name" to "x"))
        assertTrue(result.isError == true)
    }
}
