package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.tools.registerCommunityThreatTools
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommunityThreatToolsTest {

    private suspend fun harness(body: String = "{}") =
        mcpHarness(responseBody = body) { registerCommunityThreatTools(it) }

    @Test
    fun `create_community_threat posts the raw JSON body on the v1 host`() = runBlocking {
        val h = harness()
        val threat = """{"threat":{"note":"my threat","indicators":{"ips":["1.2.3.4"]}}}"""
        h.call("create_community_threat", mapOf("request_body" to threat))
        val req = h.lastRequest
        assertEquals(HttpMethod.Post, req.method)
        assertEquals("us.rest.logs.insight.rapid7.com", req.url.host)
        assertEquals("/idr/v1/customthreats", req.url.encodedPath)
        assertEquals(threat, h.lastBody)
        assertTrue(req.body.contentType.toString().contains("application/json"))
    }

    @Test
    fun `add_indicators maps each format to its content type`() = runBlocking {
        val h = harness()

        h.call(
            "add_community_threat_indicators",
            mapOf("key" to "k1", "format" to "json", "indicators" to """{"ips":["1.2.3.4"]}"""),
        )
        assertEquals("/idr/v1/customthreats/key/k1/indicators/add", h.lastRequest.url.encodedPath)
        assertEquals("json", h.lastRequest.url.parameters["format"])
        assertTrue(h.lastRequest.body.contentType.toString().contains("application/json"))

        h.call(
            "add_community_threat_indicators",
            mapOf("key" to "k1", "format" to "csv", "indicators" to "1.2.3.4\n5.6.7.8"),
        )
        assertEquals("csv", h.lastRequest.url.parameters["format"])
        assertTrue(h.lastRequest.body.contentType.toString().contains("text/csv"))

        h.call(
            "add_community_threat_indicators",
            mapOf("key" to "k1", "format" to "stix_xml", "indicators" to "<stix/>"),
        )
        assertEquals("stix_xml", h.lastRequest.url.parameters["format"])
        assertTrue(h.lastRequest.body.contentType.toString().contains("text/xml"))
    }

    @Test
    fun `replace_indicators posts to the replace path`() = runBlocking {
        val h = harness()
        h.call(
            "replace_community_threat_indicators",
            mapOf("key" to "k1", "format" to "json", "indicators" to """{"ips":[]}"""),
        )
        assertEquals(HttpMethod.Post, h.lastRequest.method)
        assertEquals("/idr/v1/customthreats/key/k1/indicators/replace", h.lastRequest.url.encodedPath)
        assertEquals("json", h.lastRequest.url.parameters["format"])
    }

    @Test
    fun `an unsupported indicator format is a tool error`() = runBlocking {
        val h = harness()
        val result = h.call(
            "add_community_threat_indicators",
            mapOf("key" to "k1", "format" to "xml", "indicators" to "<x/>"),
        )
        assertTrue(result.isError == true)
        assertEquals(0, h.requests.size, "no API call may be made for an invalid format")
    }

    @Test
    fun `delete_community_threat posts the optional reason`() = runBlocking {
        val h = harness()
        h.call("delete_community_threat", mapOf("key" to "k1", "reason" to "no longer needed"))
        assertEquals(HttpMethod.Post, h.lastRequest.method)
        assertEquals("/idr/v1/customthreats/key/k1/delete", h.lastRequest.url.encodedPath)
        assertEquals("no longer needed", h.lastBodyJson()["reason"]!!.jsonPrimitive.content)
    }
}
