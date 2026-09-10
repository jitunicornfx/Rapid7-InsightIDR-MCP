package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.tools.registerCollectorTools
import com.jitunicornfx.insightidr.mcp.tools.registerHealthMetricTools
import com.jitunicornfx.insightidr.mcp.tools.SERVER_INFO_TOOL
import com.jitunicornfx.insightidr.mcp.tools.registerSystemTools
import io.ktor.http.HttpMethod
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
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
        assertEquals("us.api.insight.rapid7.com", req.url.host)
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

    @Test
    fun `insightidr_server_info makes no HTTP call at all and reports the running version`() = runBlocking {
        UpdateStatus.recordCheck(UpdateChecker.Result(updateAvailable = false, currentVersion = SERVER_VERSION))
        val facts = ServerFacts.from(
            Config(
                apiKey = "test-key",
                region = Region.US,
                baseUrl = "https://us.api.insight.rapid7.com",
                requestTimeoutMillis = 60_000,
            ),
        )
        val h = mcpHarness { registerSystemTools(it, facts) }

        val result = h.call(SERVER_INFO_TOOL)
        assertFalse(result.isError == true)

        // The whole point of the design: the handler touches neither the InsightIDR API nor GitHub.
        // UpdateChecker.check() would build its own OkHttp client and escape the MockEngine, so an
        // empty request history is the assertion that it was never called.
        assertTrue(h.requests.isEmpty(), "insightidr_server_info must make no HTTP request at all")

        val json = JsonCodec.compact
            .parseToJsonElement((result.content.first() as TextContent).text)
            .jsonObject
        assertEquals(SERVER_NAME, json["server"]!!.jsonPrimitive.content)
        assertEquals(SERVER_VERSION, json["version"]!!.jsonPrimitive.content)
        assertEquals("us", json["runtime"]!!.jsonObject["region"]!!.jsonPrimitive.content)
        assertEquals(false, json["update"]!!.jsonObject["updateAvailable"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `insightidr_server_info is read-only and takes no parameters`() = runBlocking {
        val h = mcpHarness { registerSystemTools(it) }
        val tool = h.tools().first { it.name == SERVER_INFO_TOOL }
        assertEquals(true, tool.annotations?.readOnlyHint)
        assertTrue(tool.inputSchema.properties?.isEmpty() ?: true, "the tool must take no arguments")
    }

    @Test
    fun `insightidr_server_info counts every registered tool, not just its own group`() = runBlocking {
        // registerSystemTools runs FIRST in buildInsightIdrServer, so a count captured at
        // registration time would report 2. This proves it is read at call time from the live registry.
        val h = mcpHarness { registerSystemTools(it); registerCollectorTools(it); registerHealthMetricTools(it) }
        val expected = h.tools().size

        val json = JsonCodec.compact
            .parseToJsonElement((h.call(SERVER_INFO_TOOL).content.first() as TextContent).text)
            .jsonObject
        assertEquals(expected, json["toolCount"]!!.jsonPrimitive.int)
        assertTrue(expected > 2, "the fixture should register more than the system group")
    }

    @AfterTest
    fun resetProcessState() {
        // Both holders are process-wide and JUnit runs the module in one JVM.
        UpdateStatus.reset()
        ServerFacts.install(ServerFacts.UNCONFIGURED)
    }
}
