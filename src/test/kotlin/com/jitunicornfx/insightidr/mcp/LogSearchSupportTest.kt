package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.tools.LS_DEFAULT_POLL_TIMEOUT_MS
import com.jitunicornfx.insightidr.mcp.tools.LS_MAX_POLL_TIMEOUT_MS
import com.jitunicornfx.insightidr.mcp.tools.continuationLink
import com.jitunicornfx.insightidr.mcp.tools.leqlObjectForPatch
import com.jitunicornfx.insightidr.mcp.tools.nextPageLink
import com.jitunicornfx.insightidr.mcp.tools.pollArgs
import com.jitunicornfx.insightidr.mcp.tools.requireTimeWindow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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
