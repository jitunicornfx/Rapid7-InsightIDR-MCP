package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.tools.registerCollectorTools
import com.jitunicornfx.insightidr.mcp.tools.registerHealthMetricTools
import com.jitunicornfx.insightidr.mcp.tools.registerSystemTools
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers the small single-tool registries: Collectors, Health Metrics, and System diagnostics. */
class MiscToolsTest {

    @Test
    fun `add_collector posts name, key and deployment_type to the v1 host`() = runBlocking {
        val h = mcpHarness { registerCollectorTools(it) }
        h.call(
            "add_collector",
            mapOf("name" to "dc1-collector", "key" to "8b0b4b12-aaaa-bbbb-cccc-121212121212", "deployment_type" to "VM"),
        )
        val req = h.lastRequest
        assertEquals(HttpMethod.Post, req.method)
        assertEquals("us.rest.logs.insight.rapid7.com", req.url.host)
        assertEquals("/idr/v1/collectors", req.url.encodedPath)
        val body = h.lastBodyJson()
        assertEquals("dc1-collector", body["name"]!!.jsonPrimitive.content)
        assertEquals("8b0b4b12-aaaa-bbbb-cccc-121212121212", body["key"]!!.jsonPrimitive.content)
        assertEquals("VM", body["deployment_type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `add_collector without a key is a tool error`() = runBlocking {
        val h = mcpHarness { registerCollectorTools(it) }
        val result = h.call("add_collector", mapOf("name" to "dc1-collector"))
        assertTrue(result.isError == true)
    }

    @Test
    fun `get_health_metrics builds the query and omits absent filters`() = runBlocking {
        val h = mcpHarness(responseBody = "[]") { registerHealthMetricTools(it) }
        h.call(
            "get_health_metrics",
            mapOf("index" to 0, "size" to 50, "resourceTypes" to "COLLECTOR", "orgId" to "org-1"),
        )
        val req = h.lastRequest
        assertEquals(HttpMethod.Get, req.method)
        assertEquals("/idr/v1/health-metrics", req.url.encodedPath)
        assertEquals("50", req.url.parameters["size"])
        assertEquals("COLLECTOR", req.url.parameters["resourceTypes"])
        assertEquals("org-1", req.url.parameters["orgId"])

        h.call("get_health_metrics")
        assertNull(h.lastRequest.url.parameters["resourceTypes"])
        assertNull(h.lastRequest.url.parameters["orgId"])
    }

    @Test
    fun `validate_connection calls the platform validate endpoint on the v2 host`() = runBlocking {
        val h = mcpHarness(responseBody = """{"message":"Authorized"}""") { registerSystemTools(it) }
        val result = h.call("validate_connection")
        val req = h.lastRequest
        assertEquals(HttpMethod.Get, req.method)
        // /validate is a platform endpoint: it lives on the api.insight host, not rest.logs.
        assertEquals("us.api.insight.rapid7.com", req.url.host)
        assertEquals("/validate", req.url.encodedPath)
        assertEquals("test-key", req.headers["X-Api-Key"])
        assertFalse(result.isError == true)
    }
}
