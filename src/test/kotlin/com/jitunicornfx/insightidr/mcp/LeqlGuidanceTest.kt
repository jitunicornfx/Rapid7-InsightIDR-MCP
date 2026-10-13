package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.Rapid7Client.ApiResponse
import com.jitunicornfx.insightidr.mcp.testutil.parseEnvelope
import com.jitunicornfx.insightidr.mcp.tools.registerLogSearchQueryTools
import io.ktor.http.HttpStatusCode
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Log Search API answers every LEQL mistake with the same sentence. These tests hold the server
 * to saying something a model can act on, in its own words, without repeating any of the API's.
 */
class LeqlGuidanceTest {

    /** What the live API returned on 2026-10-12 for `where(...) select(...)`. */
    private val syntaxRejection = """{"errorCode": 101011, "message": "Invalid Query Syntax: the query entered is invalid"}"""

    private fun textOf(status: Int, body: String): String =
        (ApiResponse(status, status in 200..299, body, "application/json").toToolResult().content.first() as TextContent).text

    /** The status line: the first thing the model reads, and the only place a hint appears. */
    private fun statusLine(status: Int, body: String): String = textOf(status, body).lineSequence().first()

    @Test
    fun `the clause order is the one measured against the live API`() {
        // Not derivable from the spec, which never mentions select(). Changing this constant means
        // measuring again, not editing a string: Leql.kt lists what was run.
        assertEquals("select() where() groupby() calculate() having() sort() limit() timeslice()", LEQL_CLAUSE_ORDER)
        assertTrue(LEQL_CLAUSE_ORDER in LEQL_QUERY_GUIDANCE)
        assertTrue("write select(...) where(...), never where(...) select(...)" in LEQL_QUERY_GUIDANCE)
    }

    @Test
    fun `select is only offered with the where clause that makes it return keys`() {
        // Measured 2026-10-12 (Leql.kt): select() on its own returned "{}" for events that hold the
        // keys. The sentence this replaces offered it unconditionally, as the way to shrink a result.
        assertFalse("returns only those keys of each event;" in LEQL_QUERY_GUIDANCE)
        assertTrue("ONLY when the same query has a where() that compares a key" in LEQL_QUERY_GUIDANCE)
        assertTrue("where(key1=/.*/)" in LEQL_QUERY_GUIDANCE, "a way to select without filtering on anything else")
        // The failure is a 200 full of empty events, so the text has to say what it looks like.
        assertTrue("every event comes back as an empty {} although it holds the keys" in LEQL_QUERY_GUIDANCE)
        assertTrue(LEQL_QUERY_GUIDANCE.endsWith("select() cannot be combined with calculate()."))
    }

    @Test
    fun `a select with no where clause reaches the API as written`() = runBlocking {
        // The shape of what the live API returned on 2026-10-12 for select(action) alone.
        val emptied = """{"logs":["lk1"],"events":[{"message":"{}","sequence_number_str":"5","timestamp":1}],"leql":{"statement":"select(action)"}}"""
        val h = mcpHarness(responseBody = emptied) { registerLogSearchQueryTools(it) }
        val result = h.call(
            "logsearch_query_log",
            mapOf("log_key" to "lk1", "query" to "select(action)", "time_range" to "last 1 hours", "per_page" to 1),
        )

        assertEquals(1, h.requests.size)
        // The server adds neither a where() nor kvp_info to make the select work: the query the model
        // wrote is the one the API answers, and the advice is in the parameter's description.
        assertEquals("select(action)", h.lastRequest.url.parameters["query"])
        assertNull(h.lastRequest.url.parameters["kvp_info"])
        assertFalse(result.isError == true)
    }

    @Test
    fun `a LEQL syntax rejection is explained as one`() {
        val envelope = parseEnvelope(textOf(400, syntaxRejection))
        val line = envelope.before.lineSequence().first()

        assertEquals("InsightIDR API returned HTTP 400. $LEQL_SYNTAX_ERROR_HINT", line)
        assertTrue(LEQL_CLAUSE_ORDER in line, "the order is spelled out: the API never says which rule was broken")
        // Attributed to where it was measured. The hint also reaches tools whose endpoints were not.
        assertTrue("the query endpoints accept only $LEQL_CLAUSE_ORDER" in line)
        assertTrue("select(...) goes BEFORE where(...)" in line)
        // The hint this replaces. Every parameter of the failing call was valid against the schema,
        // so it sent the model to look where the mistake was not.
        assertFalse("input schema" in line)
        // The server's voice carries none of the API's text. That stays inside the fence.
        assertTrue("Invalid Query Syntax" in envelope.body)
        assertFalse("Invalid Query Syntax" in envelope.before + envelope.after)
    }

    @Test
    fun `only the API's own error code selects the LEQL hint`() {
        val generic = "InsightIDR API returned HTTP 400. The API rejected the request as malformed"
        val others = listOf(
            "another code, with the digits in its message" to """{"errorCode": 100001, "message": "request 101011 failed"}""",
            "the digits inside a longer number" to """{"errorCode": 1010110, "message": "x"}""",
            "the code below the top level" to """{"error": {"errorCode": 101011}}""",
            "the code inside an array" to """[{"errorCode": 101011}]""",
            "a body that is not JSON" to "errorCode 101011",
            "no body at all" to "",
        )
        for ((why, body) in others) {
            assertTrue(statusLine(400, body).startsWith(generic), "$why: ${statusLine(400, body)}")
        }
        // The same code sent as a string is the same code.
        assertTrue(LEQL_CLAUSE_ORDER in statusLine(400, """{"errorCode": "101011"}"""))
    }

    @Test
    fun `the LEQL hint is for a 400 and nothing else`() {
        // A 5xx that carries the code is still the API being unwell, and retrying is still the advice.
        assertTrue("retrying may succeed" in statusLine(503, syntaxRejection))
        assertFalse(LEQL_CLAUSE_ORDER in statusLine(503, syntaxRejection))
        // Nor is it for any other 4xx: each keeps the hint that belongs to its own status.
        assertTrue("id/RRN" in statusLine(404, syntaxRejection))
        assertTrue("Rate limited" in statusLine(429, syntaxRejection))
        for (status in listOf(401, 403, 404, 406, 422, 429)) {
            assertFalse(LEQL_CLAUSE_ORDER in statusLine(status, syntaxRejection), "HTTP $status")
        }
        // A success is never annotated, whatever its body holds.
        val success = parseEnvelope(textOf(200, syntaxRejection))
        assertFalse("LEQL" in success.before + success.after)
    }

    @Test
    fun `apiErrorCode reads the key the API sends`() {
        assertEquals("101011", apiErrorCode(syntaxRejection))
        assertEquals(
            "101009",
            apiErrorCode("""{"errorCode": 101009, "message": "Invalid Query Request: Pagination is not supported with statistic queries"}"""),
        )
        assertEquals("101011", apiErrorCode("""{"errorCode": "101011"}"""))
        // Framework-level errors from the same API carry the HTTP status under `code` (observed: 406).
        assertEquals("406", apiErrorCode("""{"code": 406, "message": "HTTP 406 Not Acceptable"}"""))
        // errorCode is the API's code and wins; `code` is read only when it is missing or null.
        assertEquals("101011", apiErrorCode("""{"code": 400, "errorCode": 101011}"""))
        assertEquals("400", apiErrorCode("""{"errorCode": null, "code": 400}"""))

        val unreadable = listOf("", "not json", "[1]", "101011", """{"errorCode": {"n": 1}}""", """{"errorCode": [101011]}""", """{"message": "x"}""")
        for (body in unreadable) assertNull(apiErrorCode(body), "<$body>")
    }

    @Test
    fun `a body too large to be an error code is not parsed for one`() {
        val padded = """{"errorCode": 101011, "message": "${"x".repeat(5_000)}"}"""
        assertNull(apiErrorCode(padded))
        assertTrue("input schema" in statusLine(400, padded), "and the result falls back to the generic hint")
    }

    @Test
    fun `the reported query reaches the API unchanged and comes back explained`() = runBlocking {
        val h = mcpHarness(status = HttpStatusCode.BadRequest, responseBody = syntaxRejection) { registerLogSearchQueryTools(it) }
        // The shape of the query that was reported: every clause valid, select() after where().
        val query = """where(logon_type = "REMOTE" AND (destination_asset ICONTAINS "host" OR source_json.computerName ICONTAINS "host")) select(timestamp, result)"""
        val result = h.call(
            "logsearch_query_logsets_by_name",
            mapOf("logset_name" to listOf("Asset Authentication"), "query" to query, "time_range" to "last 24 hours", "per_page" to 20),
        )

        assertEquals(1, h.requests.size, "a syntax error is not the statistic rejection, and is not retried")
        // The server does not reorder or repair LEQL: what the model wrote is what the API judges.
        assertEquals(query, h.lastRequest.url.parameters["query"])
        assertTrue(result.isError == true)
        val line = (result.content.first() as TextContent).text.lineSequence().first()
        assertTrue("select(...) goes BEFORE where(...)" in line, line)
    }
}
