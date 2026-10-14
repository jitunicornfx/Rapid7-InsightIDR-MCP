package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.Rapid7Client.ApiResponse
import com.jitunicornfx.insightidr.mcp.tools.LS_DEFAULT_POLL_TIMEOUT_MS
import com.jitunicornfx.insightidr.mcp.tools.LS_MAX_POLL_TIMEOUT_MS
import com.jitunicornfx.insightidr.mcp.tools.continuationLink
import com.jitunicornfx.insightidr.mcp.tools.eventsPage
import com.jitunicornfx.insightidr.mcp.tools.isStatisticPaginationRejection
import com.jitunicornfx.insightidr.mcp.tools.isStatisticResult
import com.jitunicornfx.insightidr.mcp.tools.leqlObjectForPatch
import com.jitunicornfx.insightidr.mcp.tools.nextPageLink
import com.jitunicornfx.insightidr.mcp.tools.notingUnfinishedQuery
import com.jitunicornfx.insightidr.mcp.tools.pollArgs
import com.jitunicornfx.insightidr.mcp.tools.requireTimeWindow
import com.jitunicornfx.insightidr.mcp.tools.withoutPagination
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogSearchSupportTest {

    private val bothLinks = """
        {"progress":50,"links":[
            {"rel":"Next","href":"https://us.rest.logs.insight.rapid7.com/query/logs/lk1?sequence_number=42"},
            {"rel":"Self","href":"https://us.rest.logs.insight.rapid7.com/query/cont-1"}
        ]}
    """.trimIndent()

    @Test
    fun `continuationLink selects the Self link regardless of order`() {
        assertEquals("https://us.rest.logs.insight.rapid7.com/query/cont-1", continuationLink(bothLinks))
    }

    @Test
    fun `nextPageLink selects the Next link`() {
        assertEquals(
            "https://us.rest.logs.insight.rapid7.com/query/logs/lk1?sequence_number=42",
            nextPageLink(bothLinks),
        )
    }

    @Test
    fun `link extraction tolerates absent links and malformed bodies`() {
        assertNull(continuationLink("""{"events":[]}"""))
        assertNull(nextPageLink("""{"events":[]}"""))
        assertNull(continuationLink("not json at all"))
        assertNull(nextPageLink(""))
        assertNull(continuationLink("""{"links":[{"rel":"Next","href":"https://x"}]}"""))
    }

    private val linksAfterEvents = """],"links":[{"rel":"Next","href":"https://x/next"},{"rel":"Self","href":"https://x/self"}]}"""

    @Test
    fun `a body just under the value ceiling is still read for its links`() {
        // 999,999 values: the document, its two arrays, the two links and the two strings in each.
        val body = """{"events":[""" + "1,".repeat(MAX_PARSED_VALUES - 11) + "1" + linksAfterEvents
        assertFalse(holdsMoreValuesThan(body))

        assertEquals("https://x/next", nextPageLink(body))
        assertEquals("https://x/self", continuationLink(body))
        assertEquals(MAX_PARSED_VALUES - 10, eventsPage(body)!!.events.size)
        assertTrue(isStatisticResult("""{"statistics":{"count":1},"events":[""" + "1,".repeat(MAX_PARSED_VALUES - 13) + "1" + linksAfterEvents))
    }

    @Test
    fun `a body that holds too many values to read into a tree is not read to act on it`() {
        // Valid JSON, with both links and a statistics member in it: 2,000,000 values in 4,000,000
        // characters. Measured 2026-10-12, a tree of 78 bytes for each value, and until the ceiling
        // nothing here limited how many: the same shape at 60,000,000 characters, which the client
        // will hand over, was an OutOfMemoryError in a 2 GB heap, looking for a link.
        val dense = """{"statistics":{},"events":[""" + "1,".repeat(1_999_999) + "1" + linksAfterEvents
        val readers = mapOf<String, (String) -> Any?>(
            "continuationLink" to ::continuationLink,
            "nextPageLink" to ::nextPageLink,
            "eventsPage" to ::eventsPage,
            "isStatisticResult" to { body -> isStatisticResult(body).takeIf { it } },
        )
        for ((name, read) in readers) assertNull(read(dense), name)

        val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue(
            threads != null && threads.isThreadAllocatedMemorySupported && threads.isThreadAllocatedMemoryEnabled,
            "this JVM does not count what a thread allocates",
        )
        for ((name, read) in readers) {
            val before = threads!!.currentThreadAllocatedBytes
            read(dense)
            val allocated = threads.currentThreadAllocatedBytes - before
            assertTrue(allocated < 64 * 1024, "$name allocated $allocated bytes on a body it does not read")
        }
    }

    @Test
    fun `the note on an unfinished query is added to what the client had to say, not put in its place`() {
        val running = ApiResponse(200, true, """{"id":"q","links":[{"rel":"Self","href":"https://x/self"}]}""", "application/json", serverNote = "Earlier.")

        val noted = running.notingUnfinishedQuery()

        assertEquals("Earlier. $UNFINISHED_QUERY_NOTE", noted.serverNote)
        assertEquals(running.copy(serverNote = null), noted.copy(serverNote = null), "and nothing else about it changes")
    }

    @Test
    fun `leqlObjectForPatch returns null when nothing is provided`() {
        assertNull(leqlObjectForPatch(null, buildJsonObject {}))
    }

    @Test
    fun `leqlObjectForPatch supports statement-only and window-only updates`() {
        val statementOnly = leqlObjectForPatch("where(x)", buildJsonObject {})!!
        assertEquals("where(x)", statementOnly["statement"]!!.jsonPrimitive.content)
        assertNull(statementOnly["during"])

        val windowOnly = leqlObjectForPatch(null, buildJsonObject { put("time_range", "last 1 day") })!!
        assertNull(windowOnly["statement"])
        assertEquals("last 1 day", windowOnly["during"]!!.jsonObject["time_range"]!!.jsonPrimitive.content)
    }

    @Test
    fun `requireTimeWindow accepts time_range or a full from-to pair`() {
        requireTimeWindow(buildJsonObject { put("time_range", "today") })
        requireTimeWindow(buildJsonObject { put("from", 1L); put("to", 2L) })
        assertFailsWith<IllegalArgumentException> { requireTimeWindow(buildJsonObject {}) }
        assertFailsWith<IllegalArgumentException> { requireTimeWindow(buildJsonObject { put("from", 1L) }) }
    }

    @Test
    fun `isStatisticPaginationRejection detects 101009 on non-2xx, by code or message, never on 2xx`() {
        // The body the API really sends.
        assertTrue(
            ApiResponse(
                400, false,
                """{"errorCode":101009,"message":"Invalid Query Request: Pagination is not supported with statistic queries"}""",
                "application/json",
            ).isStatisticPaginationRejection(),
        )
        // By message text, mixed case, and a different 4xx status.
        assertTrue(
            ApiResponse(422, false, "Pagination Is Not Supported with statistic queries", null)
                .isStatisticPaginationRejection(),
        )
        // A 2xx result body that merely contains the string 101009 (e.g. a log line) must NOT trigger.
        assertFalse(
            ApiResponse(200, true, """{"events":[{"message":"saw code 101009 here"}]}""", "application/json")
                .isStatisticPaginationRejection(),
        )
        // An unrelated error must NOT trigger.
        assertFalse(
            ApiResponse(404, false, """{"code":404,"message":"HTTP 404 Not Found"}""", null).isStatisticPaginationRejection(),
        )
    }

    @Test
    fun `the statistic rejection is recognised by its code or its message, in whatever shape they arrive`() {
        fun rejected(body: String, status: Int = 400) = ApiResponse(status, false, body, "application/json").isStatisticPaginationRejection()

        assertTrue(rejected("""{"errorCode":101009,"message":"x"}"""), "the code alone, under the key the API uses")
        assertTrue(rejected("""{"errorCode":"101009"}"""), "the same code as a string")
        assertTrue(rejected("""{"code":101009,"message":"x"}"""), "and under the key this server once assumed")
        assertFalse(rejected("""{"errorCode":101011,"message":"Invalid Query Syntax: the query entered is invalid"}"""), "a syntax error")
        assertTrue(rejected("""{"message":"Pagination is not supported with statistic queries"}"""), "message alone")
        assertTrue(rejected("Pagination is not supported with statistic queries"), "a body that is not JSON at all")
        assertFalse(rejected("""{"errorCode":100001,"message":"from=1710100900000 is invalid"}"""), "the digits inside another number")
        assertFalse(rejected("""{"errorCode":1010090}"""))
    }

    @Test
    fun `withoutPagination drops only per_page and sequence_number`() {
        val full = mapOf(
            "per_page" to listOf("500"),
            "sequence_number" to listOf("42"),
            "query" to listOf("where(x) calculate(count)"),
            "time_range" to listOf("today"),
            "most_recent_first" to listOf("true"),
            "kvp_info" to listOf("true"),
        )
        val stripped = full.withoutPagination()
        assertFalse("per_page" in stripped)
        assertFalse("sequence_number" in stripped)
        assertEquals(listOf("where(x) calculate(count)"), stripped["query"])
        assertEquals(listOf("today"), stripped["time_range"])
        assertEquals(listOf("true"), stripped["most_recent_first"])
        assertEquals(listOf("true"), stripped["kvp_info"])
    }

    @Test
    fun `pollArgs defaults and clamps the timeout`() {
        val (waitDefault, timeoutDefault) = buildJsonObject {}.pollArgs()
        assertEquals(true, waitDefault)
        assertEquals(LS_DEFAULT_POLL_TIMEOUT_MS, timeoutDefault)

        val (wait, timeout) = buildJsonObject {
            put("wait_for_completion", false)
            put("poll_timeout_ms", 10_000_000L)
        }.pollArgs()
        assertEquals(false, wait)
        assertEquals(LS_MAX_POLL_TIMEOUT_MS, timeout)
    }
}
