package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.tools.registerCommentTools
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommentToolsTest {

    private suspend fun harness(body: String = "{}") =
        mcpHarness(responseBody = body) { registerCommentTools(it) }

    @Test
    fun `list_comments passes target and sort direction`() = runBlocking {
        val h = harness(body = "[]")
        h.call("list_comments", mapOf("target" to "rrn:inv:1", "size" to 10, "sortDirection" to "DESC"))
        assertEquals(HttpMethod.Get, h.lastRequest.method)
        assertEquals("/idr/v1/comments", h.lastRequest.url.encodedPath)
        assertEquals("rrn:inv:1", h.lastRequest.url.parameters["target"])
        assertEquals("10", h.lastRequest.url.parameters["size"])
        assertEquals("DESC", h.lastRequest.url.parameters["sortDirection"])
    }

    @Test
    fun `get_comment resolves the rrn path`() = runBlocking {
        val h = harness()
        h.call("get_comment", mapOf("rrn" to "cmt1"))
        assertEquals(HttpMethod.Get, h.lastRequest.method)
        assertEquals("/idr/v1/comments/cmt1", h.lastRequest.url.encodedPath)
    }

    @Test
    fun `create_comment posts target, body and attachments`() = runBlocking {
        val h = harness()
        h.call(
            "create_comment",
            mapOf("target" to "rrn:inv:1", "body" to "looks malicious", "attachments" to listOf("att1", "att2")),
        )
        assertEquals(HttpMethod.Post, h.lastRequest.method)
        assertEquals("/idr/v1/comments", h.lastRequest.url.encodedPath)
        val json = h.lastBodyJson()
        assertEquals("rrn:inv:1", json["target"]!!.jsonPrimitive.content)
        assertEquals("looks malicious", json["body"]!!.jsonPrimitive.content)
        assertEquals(2, json["attachments"]!!.jsonArray.size)
    }

    @Test
    fun `delete_comment deletes by rrn`() = runBlocking {
        val h = harness()
        h.call("delete_comment", mapOf("rrn" to "cmt1"))
        assertEquals(HttpMethod.Delete, h.lastRequest.method)
        assertEquals("/idr/v1/comments/cmt1", h.lastRequest.url.encodedPath)
    }

    @Test
    fun `update_comment_visibility puts visibility into the path`() = runBlocking {
        val h = harness()
        h.call("update_comment_visibility", mapOf("rrn" to "cmt1", "visibility" to "PUBLIC"))
        assertEquals(HttpMethod.Put, h.lastRequest.method)
        assertEquals("/idr/v1/comments/cmt1/PUBLIC", h.lastRequest.url.encodedPath)
    }

    @Test
    fun `create_comment without target is a tool error`() = runBlocking {
        val h = harness()
        val result = h.call("create_comment", mapOf("body" to "no target"))
        assertTrue(result.isError == true)
    }
}
