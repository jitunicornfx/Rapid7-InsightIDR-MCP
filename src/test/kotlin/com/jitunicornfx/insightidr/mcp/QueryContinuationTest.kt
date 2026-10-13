package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.Rapid7Client.ApiResponse
import com.jitunicornfx.insightidr.mcp.testutil.parseEnvelope
import com.jitunicornfx.insightidr.mcp.tools.registerLogSearchAuditTools
import com.jitunicornfx.insightidr.mcp.tools.registerLogSearchQueryTools
import io.ktor.http.HttpStatusCode
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Log Search API refuses a query continuation in four ways, each with its own error code. These
 * tests hold the server to telling a model which one it met and what to do about it, in its own
 * words, and to leaving every other 400 and 404 alone.
 */
class QueryContinuationTest {

    // What the live API returned on 2026-10-12. QueryContinuation.kt says what was polled to get each.
    private val gone = """{"errorCode": 101056, "message": "Cannot find provided continue ID. Please ensure ID is correct and the search is polled at regular intervals, otherwise it is removed due to inactivity."}"""
    private val notValid = """{"errorCode": 101024, "message": "Provided continue id is not valid"}"""
    private val malformed = """{"errorCode": 101010, "message": "Invalid Query Request: continuation id is invalid"}"""
    private val unknownType = """{"errorCode": 101053, "message": "Invalid Query Request: unrecognised continuation type"}"""

    private val notIssued = "The API does not recognise this as a continuation id it issued for this endpoint"
    private val generic400 = "InsightIDR API returned HTTP 400. The API rejected the request as malformed"
    private val generic404 = "InsightIDR API returned HTTP 404. Not found — the id/RRN may be wrong"

    private fun textOf(status: Int, body: String): String =
        (ApiResponse(status, status in 200..299, body, "application/json").toToolResult().content.first() as TextContent).text

    private fun statusLine(status: Int, body: String): String = textOf(status, body).lineSequence().first()

    @Test
    fun `the polling interval is the one the spec's prose gives`() {
        // "Links must be polled at least once every 20 seconds to avoid expiration." The spec's code
        // samples say 10; an id first polled 13 to 17 seconds after its query was submitted was
        // still good. Changing this number means reading the spec or measuring again.
        assertEquals(20, CONTINUATION_POLL_SECONDS)
        for (text in listOf(CONTINUATION_GONE_HINT, CONTINUATION_POLL_GUIDANCE)) {
            assertTrue("the Log Search spec says" in text && "every 20 seconds" in text, "attributed, not asserted: $text")
        }
    }

    @Test
    fun `a continuation the API has discarded is explained as one`() {
        val envelope = parseEnvelope(textOf(404, gone))
        val line = envelope.before.lineSequence().first()

        assertEquals("InsightIDR API returned HTTP 404. $CONTINUATION_GONE_HINT", line)
        assertTrue("(errorCode 101056)" in line)
        assertTrue("run the original query again" in line && "do not retry it" in line)
        // What makes running it again work where polling it by hand did not.
        assertTrue("A larger poll_timeout_ms on the query tool (up to 120000)" in line, line)
        // The hint this replaces, said to a model holding the id the API had issued a moment before.
        assertFalse("id/RRN" in line)
        // The server's voice carries none of the API's text. That stays inside the fence.
        assertTrue("removed due to inactivity" in envelope.body)
        assertFalse("removed due to inactivity" in envelope.before + envelope.after)
    }

    @Test
    fun `an id the endpoint did not issue is explained as one`() {
        for ((status, body, code) in listOf(Triple(404, notValid, "101024"), Triple(400, malformed, "101010"), Triple(400, unknownType, "101053"))) {
            val envelope = parseEnvelope(textOf(status, body))
            val line = envelope.before.lineSequence().first()

            assertTrue(line.startsWith("InsightIDR API returned HTTP $status. $notIssued (errorCode $code)."), line)
            // Measured in both directions: an id is only good on the endpoint family that issued it.
            assertTrue("logsearch_audit_poll_query for an audit query, logsearch_poll_query for any other" in line, line)
            assertTrue("run the query again" in line)
            assertFalse("input schema" in line || "id/RRN" in line, line)
            assertFalse("discards" in line, "nothing was seen to expire here, so nothing says it did: $line")
            val apiMessage = body.substringAfter("\"message\": \"").substringBefore("\"}")
            assertTrue(apiMessage in envelope.body && apiMessage !in envelope.before + envelope.after, apiMessage)
        }
    }

    @Test
    fun `each code selects its hint only under the status it was observed with`() {
        // The same four bodies under the other of the two statuses: no hint of theirs applies.
        for (body in listOf(malformed, unknownType)) assertTrue(statusLine(404, body).startsWith(generic404), statusLine(404, body))
        for (body in listOf(gone, notValid)) assertTrue(statusLine(400, body).startsWith(generic400), statusLine(400, body))

        for (body in listOf(gone, notValid, malformed, unknownType)) {
            // A 5xx that carries a code is still the API being unwell, and retrying is still the advice.
            assertTrue("retrying may succeed" in statusLine(503, body))
            for (status in listOf(401, 403, 406, 422, 429, 500, 503)) {
                assertFalse("continuation" in statusLine(status, body), "HTTP $status: ${statusLine(status, body)}")
            }
            // A success is never annotated, whatever its body holds.
            val success = parseEnvelope(textOf(200, body))
            assertFalse("continuation" in success.before + success.after)
        }
    }

    @Test
    fun `only the API's own error code selects a continuation hint`() {
        val others404 = listOf(
            // Observed: what a Context link on an audit-log event is answered with.
            "a log that is not there" to """{"errorCode":101035,"message":"Requested log with id 43c4b67e was not found"}""",
            "the v1 API's shape" to """{"message":"Attachment Not Found","correlation_id":"abc"}""",
            "another code, with the digits in its message" to """{"errorCode": 100001, "message": "query 101056 was not found"}""",
            "the digits inside a longer number" to """{"errorCode": 1010560, "message": "x"}""",
            "the code below the top level" to """{"error": {"errorCode": 101056}}""",
            "a body that is not JSON" to "errorCode 101056",
            "no body at all" to "",
        )
        for ((why, body) in others404) assertTrue(statusLine(404, body).startsWith(generic404), "$why: ${statusLine(404, body)}")

        val others400 = listOf(
            "another code, with the digits in its message" to """{"errorCode": 100001, "message": "continuation 101010 is invalid"}""",
            "the code below the top level" to """{"error": {"errorCode": 101053}}""",
            "no body at all" to "",
        )
        for ((why, body) in others400) assertTrue(statusLine(400, body).startsWith(generic400), "$why: ${statusLine(400, body)}")

        // The same code sent as a string is the same code.
        assertTrue(CONTINUATION_GONE_HINT in statusLine(404, """{"errorCode": "101056"}"""))
        // And the LEQL hint, which shares the 400, is still chosen by its own.
        assertTrue(LEQL_CLAUSE_ORDER in statusLine(400, """{"errorCode": 101011}"""))
    }

    @Test
    fun `both poll tools explain a discarded continuation, and do not ask for it twice`() = runBlocking {
        val h = mcpHarness(status = HttpStatusCode.NotFound, responseBody = gone) {
            registerLogSearchQueryTools(it)
            registerLogSearchAuditTools(it)
        }
        for ((tool, path) in listOf("logsearch_poll_query" to "/query/cont-1", "logsearch_audit_poll_query" to "/audit/query/cont-1")) {
            val before = h.requests.size
            val result = h.call(tool, mapOf("query_id" to "cont-1"))

            assertEquals(1, h.requests.size - before, "$tool: a discarded continuation is not polled again")
            assertEquals(path, h.lastRequest.url.encodedPath)
            assertTrue(result.isError == true)
            val line = (result.content.first() as TextContent).text.lineSequence().first()
            assertEquals("InsightIDR API returned HTTP 404. $CONTINUATION_GONE_HINT", line, tool)
        }
    }

    @Test
    fun `a made-up continuation id is not blamed on the input schema`() = runBlocking {
        // What was actually polled to measure this: a 400, where the review had expected a 404.
        val h = mcpHarness(status = HttpStatusCode.BadRequest, responseBody = malformed) { registerLogSearchQueryTools(it) }

        val result = h.call("logsearch_poll_query", mapOf("query_id" to "zz-not-a-real-continuation-id"))

        assertEquals("/query/zz-not-a-real-continuation-id", h.lastRequest.url.encodedPath)
        val line = (result.content.first() as TextContent).text.lineSequence().first()
        assertTrue(line.startsWith("InsightIDR API returned HTTP 400. $notIssued (errorCode 101010)."), line)
    }

    @Test
    fun `the next-page tool calls a 404 an expired query only when the API does`() = runBlocking {
        // Handed a Self link, it is polling, and the API answers as it does any poll.
        val self = "https://us.rest.logs.insight.rapid7.com/log_search/query/cont-1:?time_range=last+14+days"
        val polling = mcpHarness(status = HttpStatusCode.NotFound, responseBody = gone) { registerLogSearchQueryTools(it) }
        val discarded = (polling.call("logsearch_get_next_page", mapOf("next_link" to self)).content.first() as TextContent).text
        assertEquals("InsightIDR API returned HTTP 404. $CONTINUATION_GONE_HINT", discarded.lineSequence().first())

        // The Next link it is meant for is a new query spelled out as a URL. There is no continuation
        // in it to expire, so a 404 there is a log that is not there, and is not called anything else.
        val next = "https://us.rest.logs.insight.rapid7.com/log_search/query/logs/lk1?from=1&to=2&per_page=1&sequence_number=3&next_page=true"
        val paging = mcpHarness(
            status = HttpStatusCode.NotFound,
            responseBody = """{"errorCode":101035,"message":"Requested log with id lk1 was not found"}""",
        ) { registerLogSearchQueryTools(it) }
        val missing = (paging.call("logsearch_get_next_page", mapOf("next_link" to next)).content.first() as TextContent).text
        assertTrue(missing.lineSequence().first().startsWith(generic404), missing.lineSequence().first())
        assertFalse("continuation" in parseEnvelope(missing).before)
    }

    @Test
    fun `both poll tools state the polling rule before it is broken`() = runBlocking {
        val h = mcpHarness {
            registerLogSearchQueryTools(it)
            registerLogSearchAuditTools(it)
        }
        val tools = h.tools().associateBy { it.name }

        val ordinary = tools.getValue("logsearch_poll_query").description.orEmpty()
        val audit = tools.getValue("logsearch_audit_poll_query").description.orEmpty()
        for (description in listOf(ordinary, audit)) {
            assertTrue(description.endsWith(CONTINUATION_POLL_GUIDANCE), description)
        }
        assertTrue("run the query again with a larger poll_timeout_ms" in CONTINUATION_POLL_GUIDANCE)
        // Each names the other as where the other family's ids belong.
        assertTrue("Not for audit queries: their ids are only valid on logsearch_audit_poll_query." in ordinary, ordinary)
        assertTrue("an id from any other query is only valid on logsearch_poll_query." in audit, audit)
    }
}
