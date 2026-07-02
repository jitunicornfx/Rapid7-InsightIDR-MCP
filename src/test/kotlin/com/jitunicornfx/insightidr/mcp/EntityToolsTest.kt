package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.tools.registerEntityTools
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EntityToolsTest {

    private suspend fun harness(body: String = "{}") =
        mcpHarness(responseBody = body) { registerEntityTools(it) }

    @Test
    fun `search posts criteria and paging to the entity search path`() = runBlocking {
        val h = harness(body = "[]")
        h.call(
            "search_accounts",
            mapOf(
                "search" to listOf(mapOf("field" to "name", "operator" to "CONTAINS", "value" to "admin")),
                "sort" to listOf(mapOf("field" to "name", "order" to "ASC")),
                "index" to 0,
                "size" to 50,
            ),
        )
        assertEquals(HttpMethod.Post, h.lastRequest.method)
        assertEquals("/idr/v1/accounts/_search", h.lastRequest.url.encodedPath)
        assertEquals("50", h.lastRequest.url.parameters["size"])
        val json = h.lastBodyJson()
        assertEquals(1, json["search"]!!.jsonArray.size)
        assertEquals(1, json["sort"]!!.jsonArray.size)
    }

    @Test
    fun `get by rrn resolves the entity path`() = runBlocking {
        val h = harness()
        h.call("get_account", mapOf("rrn" to "acc42"))
        assertEquals(HttpMethod.Get, h.lastRequest.method)
        assertEquals("/idr/v1/accounts/acc42", h.lastRequest.url.encodedPath)
    }

    @Test
    fun `all four entity search paths are wired correctly`() = runBlocking {
        val h = harness(body = "[]")
        val expected = mapOf(
            "search_accounts" to "/idr/v1/accounts/_search",
            "search_assets" to "/idr/v1/assets/_search",
            "search_users" to "/idr/v1/users/_search",
            "search_local_accounts" to "/idr/v1/assets/local-accounts/_search",
        )
        for ((tool, path) in expected) {
            h.call(tool, mapOf("size" to 5))
            assertEquals(HttpMethod.Post, h.lastRequest.method, tool)
            assertEquals(path, h.lastRequest.url.encodedPath, tool)
        }
    }

    @Test
    fun `all four get-by-rrn paths are wired correctly`() = runBlocking {
        val h = harness()
        val expected = mapOf(
            "get_account" to "/idr/v1/accounts/",
            "get_asset" to "/idr/v1/assets/",
            "get_user" to "/idr/v1/users/",
            "get_local_account" to "/idr/v1/assets/local-accounts/",
        )
        for ((tool, prefix) in expected) {
            h.call(tool, mapOf("rrn" to "x1"))
            assertEquals(HttpMethod.Get, h.lastRequest.method, tool)
            assertEquals(prefix + "x1", h.lastRequest.url.encodedPath, tool)
        }
    }

    @Test
    fun `get without rrn is a tool error`() = runBlocking {
        val h = harness()
        val result = h.call("get_asset", emptyMap())
        assertTrue(result.isError == true)
    }
}
