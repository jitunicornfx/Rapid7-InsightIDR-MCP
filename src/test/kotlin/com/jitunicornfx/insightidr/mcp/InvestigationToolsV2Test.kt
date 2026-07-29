package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.tools.registerInvestigationV2Tools
import io.ktor.http.HttpMethod
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InvestigationToolsV2Test {

    private suspend fun harness(status: io.ktor.http.HttpStatusCode = io.ktor.http.HttpStatusCode.OK, body: String = "{}") =
        mcpHarness(status = status, responseBody = body) { registerInvestigationV2Tools(it) }

    private fun textOf(result: CallToolResult): String = (result.content.first() as TextContent).text

    @Test
    fun `list_investigations builds query with dotted and hyphenated keys`() = runBlocking {
        val h = harness(body = "[]")
        val result = h.call(
            "list_investigations",
            mapOf(
                "size" to 10,
                "index" to 2,
                "statuses" to "OPEN",
                "assignee_email" to "a@b.com",
                "multi_customer" to true,
                "sort" to "created_time,DESC",
            ),
        )
        val req = h.lastRequest
        assertEquals(HttpMethod.Get, req.method)
        assertEquals("/idr/v2/investigations", req.url.encodedPath)
        assertEquals("10", req.url.parameters["size"])
        assertEquals("2", req.url.parameters["index"])
        assertEquals("OPEN", req.url.parameters["statuses"])
        assertEquals("a@b.com", req.url.parameters["assignee.email"])
        assertEquals("true", req.url.parameters["multi-customer"])
        assertEquals("test-key", req.headers["X-Api-Key"])
        assertFalse(result.isError == true)
    }

    @Test
    fun `get_investigation uses the id in the path`() = runBlocking {
        val h = harness()
        h.call("get_investigation", mapOf("id" to "abc123"))
        assertEquals(HttpMethod.Get, h.lastRequest.method)
        assertEquals("/idr/v2/investigations/abc123", h.lastRequest.url.encodedPath)
    }

    @Test
    fun `search_investigations posts field-operator-value criteria and sort in the body`() = runBlocking {
        val h = harness(body = "[]")
        h.call(
            "search_investigations",
            mapOf(
                "search" to listOf(
                    mapOf("field" to "status", "operator" to "EQUALS", "value" to "OPEN"),
                    mapOf("field" to "title", "operator" to "CONTAINS", "value" to "phishing"),
                    // IN with an array value matches any of several values.
                    mapOf("field" to "priority", "operator" to "IN", "value" to listOf("HIGH", "CRITICAL")),
                ),
                "sort" to listOf(mapOf("field" to "created_time", "order" to "DESC")),
                "start_time" to "2026-01-01T00:00:00Z",
            ),
        )
        assertEquals(HttpMethod.Post, h.lastRequest.method)
        assertEquals("/idr/v2/investigations/_search", h.lastRequest.url.encodedPath)
        val body = h.lastBodyJson()
        val criteria = body["search"]!!.jsonArray
        assertEquals(3, criteria.size)
        assertEquals("status", criteria[0].jsonObject["field"]!!.jsonPrimitive.content)
        assertEquals("EQUALS", criteria[0].jsonObject["operator"]!!.jsonPrimitive.content)
        assertEquals("OPEN", criteria[0].jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals(2, criteria[2].jsonObject["value"]!!.jsonArray.size)
        assertEquals("DESC", body["sort"]!!.jsonArray[0].jsonObject["order"]!!.jsonPrimitive.content)
        assertEquals("2026-01-01T00:00:00Z", body["start_time"]!!.jsonPrimitive.content)
    }

    @Test
    fun `search_investigations rejects malformed criteria before calling the API`() = runBlocking {
        val h = harness(body = "[]")

        // Unknown operator.
        val badOp = h.call(
            "search_investigations",
            mapOf("search" to listOf(mapOf("field" to "status", "operator" to "LIKE", "value" to "OPEN"))),
        )
        assertTrue(badOp.isError == true)
        assertTrue("operator" in textOf(badOp) && "LIKE" in textOf(badOp))

        // Missing value.
        val noValue = h.call(
            "search_investigations",
            mapOf("search" to listOf(mapOf("field" to "status", "operator" to "EQUALS"))),
        )
        assertTrue(noValue.isError == true)
        assertTrue("value" in textOf(noValue))

        // Missing field.
        val noField = h.call(
            "search_investigations",
            mapOf("search" to listOf(mapOf("operator" to "EQUALS", "value" to "OPEN"))),
        )
        assertTrue(noField.isError == true)
        assertTrue("field" in textOf(noField))

        // Bad sort order.
        val badSort = h.call(
            "search_investigations",
            mapOf("sort" to listOf(mapOf("field" to "created_time", "order" to "UP"))),
        )
        assertTrue(badSort.isError == true)
        assertTrue("order" in textOf(badSort))

        // No request should have been sent for any of the rejected calls.
        assertEquals(0, h.requests.size, "malformed searches must not reach the API")
    }

    @Test
    fun `search_investigations accepts lowercase operators and omitted search`() = runBlocking {
        val h = harness(body = "[]")
        // Operators are case-insensitive per the spec; an all-criteria-omitted search is a valid time-range query.
        val ok = h.call(
            "search_investigations",
            mapOf("search" to listOf(mapOf("field" to "title", "operator" to "contains", "value" to "vpn"))),
        )
        assertFalse(ok.isError == true)
        assertEquals("/idr/v2/investigations/_search", h.lastRequest.url.encodedPath)

        val empty = h.call("search_investigations", mapOf("size" to 5))
        assertFalse(empty.isError == true)
        assertEquals("5", h.lastRequest.url.parameters["size"])
    }

    @Test
    fun `create_investigation builds the assignee object`() = runBlocking {
        val h = harness()
        h.call(
            "create_investigation",
            mapOf("title" to "My Case", "priority" to "HIGH", "assignee_email" to "sec@corp.com"),
        )
        assertEquals(HttpMethod.Post, h.lastRequest.method)
        assertEquals("/idr/v2/investigations", h.lastRequest.url.encodedPath)
        val body = h.lastBodyJson()
        assertEquals("My Case", body["title"]!!.jsonPrimitive.content)
        assertEquals("HIGH", body["priority"]!!.jsonPrimitive.content)
        assertEquals("sec@corp.com", body["assignee"]!!.jsonObject["email"]!!.jsonPrimitive.content)
    }

    @Test
    fun `update_investigation maps empty assignee to a null email (unassign)`() = runBlocking {
        val h = harness()
        h.call("update_investigation", mapOf("id" to "id1", "assignee_email" to ""))
        assertEquals(HttpMethod.Patch, h.lastRequest.method)
        assertEquals("/idr/v2/investigations/id1", h.lastRequest.url.encodedPath)
        assertEquals(JsonNull, h.lastBodyJson()["assignee"]!!.jsonObject["email"])
    }

    @Test
    fun `set_investigation_status puts to the status path`() = runBlocking {
        val h = harness()
        h.call("set_investigation_status", mapOf("id" to "id1", "status" to "CLOSED", "disposition" to "BENIGN"))
        assertEquals(HttpMethod.Put, h.lastRequest.method)
        assertEquals("/idr/v2/investigations/id1/status/CLOSED", h.lastRequest.url.encodedPath)
        assertEquals("BENIGN", h.lastBodyJson()["disposition"]!!.jsonPrimitive.content)
    }

    @Test
    fun `set_investigation_priority and disposition put to their paths`() = runBlocking {
        val h = harness()
        h.call("set_investigation_priority", mapOf("id" to "id1", "priority" to "CRITICAL"))
        assertEquals("/idr/v2/investigations/id1/priority/CRITICAL", h.lastRequest.url.encodedPath)

        h.call("set_investigation_disposition", mapOf("id" to "id1", "disposition" to "MALICIOUS"))
        assertEquals("/idr/v2/investigations/id1/disposition/MALICIOUS", h.lastRequest.url.encodedPath)
    }

    @Test
    fun `assign_investigation posts the user email`() = runBlocking {
        val h = harness()
        h.call("assign_investigation", mapOf("id" to "id1", "user_email_address" to "u@corp.com"))
        assertEquals(HttpMethod.Put, h.lastRequest.method)
        assertEquals("/idr/v2/investigations/id1/assignee", h.lastRequest.url.encodedPath)
        assertEquals("u@corp.com", h.lastBodyJson()["user_email_address"]!!.jsonPrimitive.content)
    }

    @Test
    fun `bulk_close posts body and sends no multi-customer query`() = runBlocking {
        val h = harness()
        h.call(
            "bulk_close_investigations",
            mapOf("source" to "ALERT", "from" to "2026-01-01T00:00:00Z", "to" to "2026-02-01T00:00:00Z"),
        )
        assertEquals(HttpMethod.Post, h.lastRequest.method)
        assertEquals("/idr/v2/investigations/bulk_close", h.lastRequest.url.encodedPath)
        assertNull(h.lastRequest.url.parameters["multi-customer"])
        assertEquals("ALERT", h.lastBodyJson()["source"]!!.jsonPrimitive.content)
    }

    @Test
    fun `alerts endpoints resolve their paths`() = runBlocking {
        val h = harness(body = "[]")
        h.call("list_investigation_alerts", mapOf("identifier" to "id1", "size" to 5))
        assertEquals("/idr/v2/investigations/id1/alerts", h.lastRequest.url.encodedPath)
        assertEquals("5", h.lastRequest.url.parameters["size"])

        h.call("get_investigation_product_alerts", mapOf("identifier" to "id1"))
        assertEquals("/idr/v2/investigations/id1/rapid7-product-alerts", h.lastRequest.url.encodedPath)

        h.call("remove_alert_from_investigation", mapOf("identifier" to "id1", "alert_rrn" to "rrnA"))
        assertEquals(HttpMethod.Delete, h.lastRequest.method)
        assertEquals("/idr/v2/investigations/id1/alerts/rrnA", h.lastRequest.url.encodedPath)
    }

    @Test
    fun `api error is surfaced as a tool error`() = runBlocking {
        val h = harness(status = io.ktor.http.HttpStatusCode.NotFound, body = """{"message":"nope"}""")
        val result = h.call("get_investigation", mapOf("id" to "missing"))
        assertTrue(result.isError == true)
    }
}
