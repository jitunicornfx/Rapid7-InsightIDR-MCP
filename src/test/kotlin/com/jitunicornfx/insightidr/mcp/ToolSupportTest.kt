package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.Rapid7Client.ApiResponse
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolSupportTest {

    @Test
    fun `query omits nulls and expands list values`() {
        val q = query("a" to null, "b" to 5, "c" to listOf("x", "y"))
        assertFalse("a" in q)
        assertEquals(listOf("5"), q["b"])
        assertEquals(listOf("x", "y"), q["c"])
    }

    @Test
    fun `seg url-encodes path segments`() {
        assertEquals("a%20b", seg("a b"))
    }

    @Test
    fun `requireString returns value or throws when absent`() {
        val args = buildJsonObject { put("present", "v") }
        assertEquals("v", args.requireString("present"))
        assertFailsWith<IllegalArgumentException> { args.requireString("absent") }
    }

    @Test
    fun `typed accessors read primitives`() {
        val args = buildJsonObject {
            put("n", 7)
            put("b", true)
            put("s", "hi")
        }
        assertEquals(7, args.intOrNull("n"))
        assertEquals(true, args.booleanOrNull("b"))
        assertEquals("hi", args.stringOrNull("s"))
        assertEquals(null, args.intOrNull("missing"))
    }

    @Test
    fun `successful response is not an error`() {
        val result = ApiResponse(200, ok = true, body = """{"a":1}""", contentType = "application/json")
            .toToolResult()
        assertEquals(false, result.isError)
    }

    @Test
    fun `non-2xx response is marked as an error and includes the status`() {
        val result = ApiResponse(404, ok = false, body = "not found", contentType = "text/plain")
            .toToolResult()
        assertEquals(true, result.isError)
        val text = (result.content.first() as TextContent).text
        assertTrue("404" in text)
    }
}
