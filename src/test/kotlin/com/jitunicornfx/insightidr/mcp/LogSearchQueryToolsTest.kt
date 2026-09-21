package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.testutil.parseEnvelope
import com.jitunicornfx.insightidr.mcp.tools.registerLogSearchQueryTools
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogSearchQueryToolsTest {

    private suspend fun harness(
        body: String = "{}",
        responses: List<Pair<HttpStatusCode, String>>? = null,
    ) = mcpHarness(responseBody = body, responses = responses) { registerLogSearchQueryTools(it) }

    @Test
    fun `query_log hits the Log Search base with query params and auth`() = runBlocking {
        val h = harness(body = """{"events":[]}""")
        val result = h.call(
            "logsearch_query_log",
            mapOf(
                "log_key" to "lk1",
                "query" to "where(status=404) calculate(count)",
                "time_range" to "last 1 hour",
                "per_page" to 100,
                "most_recent_first" to true,
            ),
        )
        val req = h.lastRequest
        assertEquals(HttpMethod.Get, req.method)
        assertEquals("/query/logs/lk1", req.url.encodedPath)
        assertEquals("where(status=404) calculate(count)", req.url.parameters["query"])
        assertEquals("last 1 hour", req.url.parameters["time_range"])
        assertEquals("100", req.url.parameters["per_page"])
        assertEquals("true", req.url.parameters["most_recent_first"])
        assertEquals("test-key", req.headers["X-Api-Key"])
        assertFalse(result.isError == true)
    }

    @Test
    fun `query_log auto-polls a 202 continuation to completion`() = runBlocking {
        val poll = "https://us.rest.logs.insight.rapid7.com/query/cont-1"
        val h = harness(
            responses = listOf(
                HttpStatusCode.Accepted to """{"id":"cont-1","progress":10,"links":[{"rel":"Self","href":"$poll"}]}""",
                HttpStatusCode.Accepted to """{"id":"cont-1","progress":60,"links":[{"rel":"Self","href":"$poll"}]}""",
                HttpStatusCode.OK to """{"events":[{"message":"done"}]}""",
            ),
        )
        val result = h.call(
            "logsearch_query_log",
            mapOf("log_key" to "lk1", "query" to "where(x)", "time_range" to "today"),
        )
        assertFalse(result.isError == true)
        assertEquals(3, h.requests.size, "should have polled twice after the initial request")
        assertEquals("/query/cont-1", h.lastRequest.url.encodedPath)
        assertTrue("done" in (result.content.first() as TextContent).text)
    }

    @Test
    fun `polling continues on a 200 that still carries a Self link`() = runBlocking {
        // Per the spec, the poll endpoint returns 200 for ongoing queries; completion is
        // signalled by the absence of a rel=Self link, not by the HTTP status.
        val poll = "https://us.rest.logs.insight.rapid7.com/query/cont-3"
        val h = harness(
            responses = listOf(
                HttpStatusCode.Accepted to """{"id":"cont-3","links":[{"rel":"Self","href":"$poll"}]}""",
                HttpStatusCode.OK to """{"progress":50,"links":[{"rel":"Self","href":"$poll"}]}""",
                HttpStatusCode.OK to """{"events":[{"message":"finished"}],"links":[{"rel":"Next","href":"$poll?page=2"}]}""",
            ),
        )
        val result = h.call("logsearch_query_log", mapOf("log_key" to "lk1", "time_range" to "today"))
        assertEquals(3, h.requests.size, "must poll through the 200-with-Self response and stop at Next-only")
        assertTrue("finished" in (result.content.first() as TextContent).text)
    }

    @Test
    fun `per_page defaults to the sampling default and explicit values are honored`() = runBlocking {
        val h = harness()
        // Deliberately below the API maximum: a full 500-event page would breach the response
        // budget and be trimmed on nearly every query. See LS_DEFAULT_PER_PAGE.
        h.call("logsearch_query_log", mapOf("log_key" to "lk1", "time_range" to "today"))
        assertEquals("100", h.lastRequest.url.parameters["per_page"], "per_page must default to 100")

        h.call("logsearch_query_log", mapOf("log_key" to "lk1", "time_range" to "today", "per_page" to 25))
        assertEquals("25", h.lastRequest.url.parameters["per_page"])

        // A caller who explicitly wants the maximum still gets it.
        h.call("logsearch_query_log", mapOf("log_key" to "lk1", "time_range" to "today", "per_page" to 500))
        assertEquals("500", h.lastRequest.url.parameters["per_page"])

        h.call("logsearch_run_saved_query", mapOf("saved_query_id" to "sq1"))
        assertEquals("100", h.lastRequest.url.parameters["per_page"])
    }

    @Test
    fun `per_page is held to what the API accepts on every query tool`() = runBlocking {
        // Only the spool tool used to clamp. The rest sent 5000 or -1 to the API and got a 400 back.
        val h = harness(body = """{"events":[]}""")
        val calls = listOf(
            "logsearch_query_log" to mapOf("log_key" to "lk1", "time_range" to "today"),
            "logsearch_query_logs" to mapOf("log_keys" to listOf("lk1"), "time_range" to "today"),
            "logsearch_run_saved_query" to mapOf("saved_query_id" to "sq1"),
            "logsearch_get_context_events" to mapOf(
                "sequence_number" to "7", "timestamp" to "1", "log_key" to "lk1", "context_type" to "SURROUND",
            ),
        )
        for ((tool, args) in calls) {
            for ((asked, sent) in listOf(5_000 to "500", 500 to "500", 0 to "1", -3 to "1", 250 to "250")) {
                val result = h.call(tool, args + ("per_page" to asked))
                assertFalse(result.isError == true, "$tool: ${(result.content.first() as TextContent).text}")
                assertEquals(sent, h.lastRequest.url.parameters["per_page"], "$tool with per_page=$asked")
            }
        }
    }

    @Test
    fun `a statistic query still rejected with labels on it is explained, and the labels are not dropped`() = runBlocking {
        val h = harness(
            responses = listOf(
                HttpStatusCode.BadRequest to """{"id":"IDR","code":101009,"message":"Pagination is not supported with statistic queries"}""",
                HttpStatusCode.BadRequest to """{"message":"labels are not supported here"}""",
            ),
        )
        val result = h.call(
            "logsearch_query_log",
            mapOf("log_key" to "lk1", "query" to "calculate(count)", "time_range" to "today", "labels" to "aaa:bbb"),
        )

        assertEquals(2, h.requests.size, "retried once, not in a loop")
        // Removing the filter would count different events and hand back a confident wrong number.
        assertEquals("aaa:bbb", h.requests[1].url.parameters["labels"], "the label filter must survive the retry")
        assertEquals(null, h.requests[1].url.parameters["per_page"])
        assertTrue(result.isError == true)
        val envelope = parseEnvelope((result.content.first() as TextContent).text)
        assertTrue("labels are not supported here" in envelope.body, "the API's own words stay inside the fence")
        assertTrue("NOT removed automatically" in envelope.after, "the explanation is ours, and sits outside it")
        assertTrue("non-statistical" in envelope.after, "and it cites the spec rather than guessing at a cause")
    }

    @Test
    fun `a retry that fails for a transient reason is not blamed on the labels`() = runBlocking {
        // "Rejected again ... run it again without the label filter" next to an HTTP 429 sends the
        // model off to count a different set of events, when simply retrying was the right answer.
        for (transient in listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable, HttpStatusCode.Unauthorized)) {
            val h = harness(
                responses = listOf(
                    HttpStatusCode.BadRequest to """{"id":"IDR","code":101009,"message":"Pagination is not supported with statistic queries"}""",
                    transient to """{"message":"try later"}""",
                ),
            )
            val result = h.call(
                "logsearch_query_log",
                mapOf("log_key" to "lk1", "query" to "calculate(count)", "time_range" to "today", "labels" to "aaa"),
            )
            assertFalse("NOT removed automatically" in (result.content.first() as TextContent).text, "after HTTP ${transient.value}")
        }
    }

    @Test
    fun `an error that merely contains the digits 101009 is not mistaken for the statistic rejection`() = runBlocking {
        // A 'from' of 1710100900000, a log key, a request id: any of them can carry those six digits.
        val h = harness(
            responses = listOf(
                HttpStatusCode.BadRequest to """{"code":100001,"message":"Invalid time range: from=1710100900000"}""",
                HttpStatusCode.OK to """{"events":[]}""",
            ),
        )
        val result = h.call("logsearch_query_log", mapOf("log_key" to "lk1", "query" to "where(x)", "from" to 1710100900000, "to" to 1710100999999))

        assertEquals(1, h.requests.size, "an unrelated 400 must not be retried with its pagination stripped")
        assertTrue(result.isError == true)
    }

    @Test
    fun `the labels note is not attached when it would be beside the point`() = runBlocking {
        // Rejected twice, but no label filter was sent: there is nothing to say about labels.
        val noLabels = harness(
            responses = listOf(
                HttpStatusCode.BadRequest to """{"id":"IDR","code":101009,"message":"Pagination is not supported with statistic queries"}""",
                HttpStatusCode.BadRequest to """{"message":"bad leql"}""",
            ),
        )
        val plain = noLabels.call("logsearch_query_log", mapOf("log_key" to "lk1", "query" to "calculate(count)", "time_range" to "today"))
        assertFalse("NOT removed automatically" in (plain.content.first() as TextContent).text)

        // Labels sent, and the retry worked: nothing went wrong, so nothing to explain.
        val worked = harness(
            responses = listOf(
                HttpStatusCode.BadRequest to """{"id":"IDR","code":101009,"message":"Pagination is not supported with statistic queries"}""",
                HttpStatusCode.OK to """{"statistics":{"count":5}}""",
            ),
        )
        val fine = worked.call(
            "logsearch_query_log",
            mapOf("log_key" to "lk1", "query" to "calculate(count)", "time_range" to "today", "labels" to "aaa"),
        )
        assertFalse(fine.isError == true)
        assertFalse("NOT removed automatically" in (fine.content.first() as TextContent).text)

        // An ordinary failure that was never a statistic rejection.
        val ordinary = harness(responses = listOf(HttpStatusCode.BadRequest to """{"message":"bad leql"}"""))
        val failed = ordinary.call(
            "logsearch_query_log",
            mapOf("log_key" to "lk1", "query" to "where(", "time_range" to "today", "labels" to "aaa"),
        )
        assertEquals(1, ordinary.requests.size)
        assertFalse("NOT removed automatically" in (failed.content.first() as TextContent).text)
    }

    @Test
    fun `a statistic query rejected for pagination is retried without pagination params`() = runBlocking {
        val h = harness(
            responses = listOf(
                HttpStatusCode.BadRequest to
                    """{"id":"IDR","code":101009,"message":"Pagination is not supported with statistic queries"}""",
                HttpStatusCode.OK to """{"statistics":{"count":5}}""",
            ),
        )
        val result = h.call(
            "logsearch_query_log",
            mapOf(
                "log_key" to "lk1",
                "query" to "where(x) calculate(count)",
                "time_range" to "today",
                "sequence_number" to 42,
            ),
        )
        assertEquals(2, h.requests.size, "the rejected statistic query must be retried exactly once")
        // First attempt carries pagination.
        assertEquals("100", h.requests[0].url.parameters["per_page"])
        assertEquals("42", h.requests[0].url.parameters["sequence_number"])
        // The retry — same path and method — drops the pagination params but keeps the LEQL/time window.
        assertEquals(HttpMethod.Get, h.requests[1].method)
        assertEquals("/query/logs/lk1", h.requests[1].url.encodedPath)
        assertEquals(null, h.requests[1].url.parameters["per_page"])
        assertEquals(null, h.requests[1].url.parameters["sequence_number"])
        assertEquals("where(x) calculate(count)", h.requests[1].url.parameters["query"])
        assertEquals("today", h.requests[1].url.parameters["time_range"])
        assertFalse(result.isError == true)
        assertTrue("statistics" in (result.content.first() as TextContent).text)
    }

    @Test
    fun `a successful event query is never retried and keeps pagination`() = runBlocking {
        val h = harness(body = """{"events":[{"message":"ok"}]}""")
        val result = h.call(
            "logsearch_query_log",
            mapOf("log_key" to "lk1", "query" to "where(status=404)", "time_range" to "today"),
        )
        assertEquals(1, h.requests.size, "a 2xx event query must not trigger the pagination retry")
        assertEquals("100", h.lastRequest.url.parameters["per_page"])
        assertFalse(result.isError == true)
    }

    @Test
    fun `a saved-query run rejected for pagination is retried without per_page`() = runBlocking {
        // The saved query's LEQL is stored server-side and cannot be inspected client-side, so the
        // retry is the only mechanism that can rescue a statistic saved query.
        val h = harness(
            responses = listOf(
                HttpStatusCode.BadRequest to """{"code":101009,"message":"Pagination is not supported with statistic queries"}""",
                HttpStatusCode.OK to """{"statistics":{"groups":[]}}""",
            ),
        )
        val result = h.call("logsearch_run_saved_query", mapOf("saved_query_id" to "sq1", "per_page" to 250))
        assertEquals(2, h.requests.size)
        assertEquals("250", h.requests[0].url.parameters["per_page"])
        assertEquals("/query/saved_query/sq1", h.requests[1].url.encodedPath)
        assertEquals(null, h.requests[1].url.parameters["per_page"], "the retry must drop per_page")
        assertFalse(result.isError == true)
    }

    @Test
    fun `get_next_page follows a Next href and polls to completion`() = runBlocking {
        val next = "https://us.rest.logs.insight.rapid7.com/query/logs/lk1?per_page=500&sequence_number=42"
        val poll = "https://us.rest.logs.insight.rapid7.com/query/cont-7"
        val h = harness(
            responses = listOf(
                HttpStatusCode.Accepted to """{"id":"cont-7","links":[{"rel":"Self","href":"$poll"}]}""",
                HttpStatusCode.OK to """{"events":[{"message":"page2"}]}""",
            ),
        )
        val result = h.call("logsearch_get_next_page", mapOf("next_link" to next))
        assertEquals(2, h.requests.size)
        assertEquals("/query/logs/lk1", h.requests.first().url.encodedPath)
        assertEquals("42", h.requests.first().url.parameters["sequence_number"])
        assertTrue("page2" in (result.content.first() as TextContent).text)
    }

    @Test
    fun `get_next_page refuses non-rapid7 URLs`() = runBlocking {
        val h = harness()
        val result = h.call("logsearch_get_next_page", mapOf("next_link" to "https://evil.example.com/steal"))
        assertTrue(result.isError == true)
        assertEquals(0, h.requests.size, "no request may be sent to a disallowed host")
    }

    @Test
    fun `query without a time window is a tool error`() = runBlocking {
        val h = harness()
        val result = h.call("logsearch_query_log", mapOf("log_key" to "lk1", "query" to "where(x)"))
        assertTrue(result.isError == true)
        assertTrue("time window" in (result.content.first() as TextContent).text)
        assertEquals(0, h.requests.size, "no API call may be made without a time window")
    }

    @Test
    fun `query_log with wait_for_completion=false returns the 202 immediately`() = runBlocking {
        val h = harness(
            responses = listOf(
                HttpStatusCode.Accepted to """{"id":"cont-2","links":[{"rel":"Self","href":"https://us.rest.logs.insight.rapid7.com/query/cont-2"}]}""",
            ),
        )
        val result = h.call(
            "logsearch_query_log",
            mapOf("log_key" to "lk1", "time_range" to "today", "wait_for_completion" to false),
        )
        assertEquals(1, h.requests.size)
        assertTrue("cont-2" in (result.content.first() as TextContent).text)
    }

    @Test
    fun `query_logs posts logs and leql with a during window`() = runBlocking {
        val h = harness()
        h.call(
            "logsearch_query_logs",
            mapOf(
                "log_keys" to listOf("lk1", "lk2"),
                "query" to "where(error)",
                "from" to 1700000000000L,
                "to" to 1700000100000L,
                "per_page" to 25,
                "labels" to "uuid1:uuid2",
                "export_format" to "csv",
            ),
        )
        val req = h.lastRequest
        assertEquals(HttpMethod.Post, req.method)
        assertEquals("/query/logs", req.url.encodedPath)
        assertEquals("25", req.url.parameters["per_page"])
        assertEquals("uuid1:uuid2", req.url.parameters["labels"])
        assertEquals("csv", req.url.parameters["export_format"])
        val body = h.lastBodyJson()
        assertEquals(2, body["logs"]!!.jsonArray.size)
        val leql = body["leql"]!!.jsonObject
        assertEquals("where(error)", leql["statement"]!!.jsonPrimitive.content)
        assertEquals("1700000000000", leql["during"]!!.jsonObject["from"]!!.jsonPrimitive.content)
    }

    @Test
    fun `query_logsets_by_name repeats the logset_name parameter`() = runBlocking {
        val h = harness()
        h.call(
            "logsearch_query_logsets_by_name",
            mapOf("logset_name" to listOf("Firewall", "DNS"), "time_range" to "today"),
        )
        assertEquals("/query/logsets", h.lastRequest.url.encodedPath)
        assertEquals(listOf("Firewall", "DNS"), h.lastRequest.url.parameters.getAll("logset_name"))
    }

    @Test
    fun `query_logset queries by id`() = runBlocking {
        val h = harness()
        h.call("logsearch_query_logset", mapOf("logset_id" to "ls1", "query" to "where(x)", "time_range" to "today"))
        assertEquals("/query/logsets/ls1", h.lastRequest.url.encodedPath)
    }

    @Test
    fun `poll_query polls by continuation id`() = runBlocking {
        val h = harness()
        h.call("logsearch_poll_query", mapOf("query_id" to "cont-9"))
        assertEquals("/query/cont-9", h.lastRequest.url.encodedPath)
    }

    @Test
    fun `get_context_events requires and sends context params`() = runBlocking {
        val h = harness()
        h.call(
            "logsearch_get_context_events",
            mapOf(
                "sequence_number" to 12345,
                "timestamp" to "1700000000000",
                "log_key" to "lk1",
                "context_type" to "SURROUND",
            ),
        )
        val req = h.lastRequest
        assertEquals("/query/context/12345", req.url.encodedPath)
        assertEquals("1700000000000", req.url.parameters["timestamp"])
        assertEquals("lk1", req.url.parameters["log_keys"])
        assertEquals("SURROUND", req.url.parameters["context_type"])
    }

    @Test
    fun `search stats and endpoint discovery hit their paths`() = runBlocking {
        val h = harness()
        h.call("logsearch_get_search_stats")
        assertEquals("/search-stats", h.lastRequest.url.encodedPath)
        h.call("logsearch_list_query_endpoints")
        assertEquals("/query", h.lastRequest.url.encodedPath)
    }

    @Test
    fun `saved query crud maps to the saved_queries endpoints`() = runBlocking {
        val h = harness(body = "[]")
        h.call("logsearch_list_saved_queries")
        assertEquals("/query/saved_queries", h.lastRequest.url.encodedPath)

        h.call("logsearch_get_saved_query", mapOf("saved_query_id" to "sq1"))
        assertEquals("/query/saved_queries/sq1", h.lastRequest.url.encodedPath)

        h.call(
            "logsearch_create_saved_query",
            mapOf("name" to "5xx", "statement" to "where(status>=500)", "logs" to listOf("lk1")),
        )
        assertEquals(HttpMethod.Post, h.lastRequest.method)
        val created = h.lastBodyJson()["saved_query"]!!.jsonObject
        assertEquals("5xx", created["name"]!!.jsonPrimitive.content)
        assertEquals("where(status>=500)", created["leql"]!!.jsonObject["statement"]!!.jsonPrimitive.content)
        assertEquals(1, created["logs"]!!.jsonArray.size)

        h.call(
            "logsearch_replace_saved_query",
            mapOf("saved_query_id" to "sq1", "name" to "new", "statement" to "where(x)"),
        )
        assertEquals(HttpMethod.Put, h.lastRequest.method)
        assertEquals("/query/saved_queries/sq1", h.lastRequest.url.encodedPath)

        h.call("logsearch_update_saved_query", mapOf("saved_query_id" to "sq1", "name" to "renamed"))
        assertEquals(HttpMethod.Patch, h.lastRequest.method)

        // A during-only PATCH (no statement) must still emit the leql object with the window.
        h.call("logsearch_update_saved_query", mapOf("saved_query_id" to "sq1", "time_range" to "last 2 days"))
        val patched = h.lastBodyJson()["saved_query"]!!.jsonObject
        assertEquals(
            "last 2 days",
            patched["leql"]!!.jsonObject["during"]!!.jsonObject["time_range"]!!.jsonPrimitive.content,
        )

        h.call("logsearch_delete_saved_query", mapOf("saved_query_id" to "sq1"))
        assertEquals(HttpMethod.Delete, h.lastRequest.method)
    }

    @Test
    fun `run saved query endpoints hit both variants`() = runBlocking {
        val h = harness()
        h.call("logsearch_run_saved_query", mapOf("saved_query_id" to "sq1", "time_range" to "today"))
        assertEquals("/query/saved_query/sq1", h.lastRequest.url.encodedPath)
        assertEquals("today", h.lastRequest.url.parameters["time_range"])

        h.call("logsearch_run_saved_query_on_logs", mapOf("log_keys" to "lk1:lk2", "saved_query_id" to "sq1"))
        // ':' may be percent-encoded in the path; both forms are equivalent to the API.
        // Ktor encodes the ':' separator as %3A. Verified against the live API on 2026-09-18: it accepts
        // the encoded form, so this normalisation hides nothing that matters.
        assertEquals("/query/logs/lk1:lk2/sq1", h.lastRequest.url.encodedPath.replace("%3A", ":"))
    }

    @Test
    fun `missing required log_key is a tool error`() = runBlocking {
        val h = harness()
        val result = h.call("logsearch_query_log", emptyMap())
        assertTrue(result.isError == true)
    }
}
