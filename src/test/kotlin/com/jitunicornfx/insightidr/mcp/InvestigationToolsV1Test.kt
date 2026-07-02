package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.tools.registerInvestigationV1Tools
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InvestigationToolsV1Test {

    private suspend fun harness(body: String = "{}") =
        mcpHarness(responseBody = body) { registerInvestigationV1Tools(it) }

    @Test
    fun `list_investigations_v1 builds the query`() = runBlocking {
        val h = harness(body = "[]")
        h.call(
            "list_investigations_v1",
            mapOf("index" to 1, "size" to 20, "statuses" to "OPEN", "start_time" to "2026-01-01T00:00:00Z"),
        )
        assertEquals(HttpMethod.Get, h.lastRequest.method)
        assertEquals("/idr/v1/investigations", h.lastRequest.url.encodedPath)
        assertEquals("20", h.lastRequest.url.parameters["size"])
        assertEquals("OPEN", h.lastRequest.url.parameters["statuses"])
        assertEquals("2026-01-01T00:00:00Z", h.lastRequest.url.parameters["start_time"])
    }

    @Test
    fun `set_investigation_status_v1 puts to the status path`() = runBlocking {
        val h = harness()
        h.call("set_investigation_status_v1", mapOf("id" to "inv1", "status" to "CLOSED"))
        assertEquals(HttpMethod.Put, h.lastRequest.method)
        assertEquals("/idr/v1/investigations/inv1/status/CLOSED", h.lastRequest.url.encodedPath)
    }

    @Test
    fun `assign_investigation_v1 posts the user email`() = runBlocking {
        val h = harness()
        h.call("assign_investigation_v1", mapOf("id" to "inv1", "user_email_address" to "u@corp.com"))
        assertEquals(HttpMethod.Put, h.lastRequest.method)
        assertEquals("/idr/v1/investigations/inv1/assignee", h.lastRequest.url.encodedPath)
        assertEquals("u@corp.com", h.lastBodyJson()["user_email_address"]!!.jsonPrimitive.content)
    }

    @Test
    fun `bulk_close_investigations_v1 posts the required body`() = runBlocking {
        val h = harness()
        h.call(
            "bulk_close_investigations_v1",
            mapOf(
                "source" to "ALERT",
                "from" to "2026-01-01T00:00:00Z",
                "to" to "2026-02-01T00:00:00Z",
                "max_investigations_to_close" to 100,
            ),
        )
        assertEquals(HttpMethod.Post, h.lastRequest.method)
        assertEquals("/idr/v1/investigations/bulk_close", h.lastRequest.url.encodedPath)
        val json = h.lastBodyJson()
        assertEquals("ALERT", json["source"]!!.jsonPrimitive.content)
        assertEquals("100", json["max_investigations_to_close"]!!.jsonPrimitive.content)
    }

    @Test
    fun `set_status_v1 without status is a tool error`() = runBlocking {
        val h = harness()
        val result = h.call("set_investigation_status_v1", mapOf("id" to "inv1"))
        assertTrue(result.isError == true)
    }
}
