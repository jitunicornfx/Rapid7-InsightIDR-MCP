package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.tools.registerCloudWebhookTools
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudWebhookToolsTest {

    private suspend fun harness(body: String = "{}") =
        mcpHarness(responseBody = body) { registerCloudWebhookTools(it) }

    @Test
    fun `list and get resolve paths`() = runBlocking {
        val h = harness(body = "[]")
        h.call("list_cloud_webhooks", mapOf("size" to 50))
        assertEquals("/idr/v1/cloud-webhooks", h.lastRequest.url.encodedPath)
        assertEquals("50", h.lastRequest.url.parameters["size"])

        h.call("get_cloud_webhook", mapOf("webhook_rrn" to "wh1"))
        assertEquals("/idr/v1/cloud-webhooks/wh1", h.lastRequest.url.encodedPath)
    }

    @Test
    fun `create posts name and url`() = runBlocking {
        val h = harness()
        h.call("create_cloud_webhook", mapOf("name" to "hook", "url" to "https://x.test/hook"))
        assertEquals(HttpMethod.Post, h.lastRequest.method)
        assertEquals("/idr/v1/cloud-webhooks", h.lastRequest.url.encodedPath)
        val body = h.lastBodyJson()
        assertEquals("hook", body["name"]!!.jsonPrimitive.content)
        assertEquals("https://x.test/hook", body["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `update patches, delete deletes, test and replay post`() = runBlocking {
        val h = harness()
        h.call("update_cloud_webhook", mapOf("webhook_rrn" to "wh1", "name" to "renamed"))
        assertEquals(HttpMethod.Patch, h.lastRequest.method)
        assertEquals("/idr/v1/cloud-webhooks/wh1", h.lastRequest.url.encodedPath)

        h.call("delete_cloud_webhook", mapOf("webhook_rrn" to "wh1"))
        assertEquals(HttpMethod.Delete, h.lastRequest.method)

        h.call("test_cloud_webhook", mapOf("webhook_rrn" to "wh1"))
        assertEquals(HttpMethod.Post, h.lastRequest.method)
        assertEquals("/idr/v1/cloud-webhooks/wh1/test", h.lastRequest.url.encodedPath)

        h.call("replay_cloud_webhook_events", mapOf("webhook_rrn" to "wh1", "event_ids" to listOf("e1", "e2")))
        assertEquals("/idr/v1/cloud-webhooks/wh1/replay", h.lastRequest.url.encodedPath)
    }

    @Test
    fun `validation add and update send the config object`() = runBlocking {
        val h = harness()
        val config = mapOf("type" to "OAUTH", "client_id" to "cid")
        h.call("add_cloud_webhook_validation", mapOf("webhook_rrn" to "wh1", "validation_config" to config))
        assertEquals(HttpMethod.Post, h.lastRequest.method)
        assertEquals("/idr/v1/cloud-webhooks/wh1/validation", h.lastRequest.url.encodedPath)
        assertEquals("OAUTH", h.lastBodyJson()["type"]!!.jsonPrimitive.content)

        h.call("update_cloud_webhook_validation", mapOf("webhook_rrn" to "wh1", "validation_config" to config))
        assertEquals(HttpMethod.Patch, h.lastRequest.method)

        h.call("delete_cloud_webhook_validation", mapOf("webhook_rrn" to "wh1"))
        assertEquals(HttpMethod.Delete, h.lastRequest.method)
        assertEquals("/idr/v1/cloud-webhooks/wh1/validation", h.lastRequest.url.encodedPath)
    }

    @Test
    fun `add validation without a config is a tool error`() = runBlocking {
        val h = harness()
        val result = h.call("add_cloud_webhook_validation", mapOf("webhook_rrn" to "wh1"))
        assertTrue(result.isError == true)
    }
}
