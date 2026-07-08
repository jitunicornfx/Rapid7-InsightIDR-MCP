package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.tools.registerLogSearchDetectionRuleTools
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogSearchDetectionRuleToolsTest {

    private suspend fun harness(body: String = "{}") =
        mcpHarness(responseBody = body) { registerLogSearchDetectionRuleTools(it) }

    @Test
    fun `detection rule crud maps to management-tags endpoints`() = runBlocking {
        val h = harness(body = "[]")
        h.call("logsearch_list_detection_rules")
        assertEquals("/management/tags", h.lastRequest.url.encodedPath)

        h.call("logsearch_get_detection_rule", mapOf("rule_id" to "r1"))
        assertEquals("/management/tags/r1", h.lastRequest.url.encodedPath)

        val tag = mapOf("name" to "fail login", "type" to "Alert", "sources" to listOf(mapOf("id" to "log1")))
        h.call("logsearch_create_detection_rule", mapOf("tag" to tag))
        assertEquals(HttpMethod.Post, h.lastRequest.method)
        assertEquals("fail login", h.lastBodyJson()["tag"]!!.jsonObject["name"]!!.jsonPrimitive.content)

        h.call("logsearch_replace_detection_rule", mapOf("rule_id" to "r1", "tag" to tag))
        assertEquals(HttpMethod.Put, h.lastRequest.method)
        assertEquals("/management/tags/r1", h.lastRequest.url.encodedPath)

        h.call("logsearch_update_detection_rule", mapOf("rule_id" to "r1", "tag" to mapOf("name" to "renamed")))
        assertEquals(HttpMethod.Patch, h.lastRequest.method)

        h.call("logsearch_delete_detection_rule", mapOf("rule_id" to "r1"))
        assertEquals(HttpMethod.Delete, h.lastRequest.method)
    }

    @Test
    fun `notification crud maps to management-actions endpoints`() = runBlocking {
        val h = harness(body = "[]")
        h.call("logsearch_list_notifications")
        assertEquals("/management/actions", h.lastRequest.url.encodedPath)

        h.call("logsearch_get_notification", mapOf("notification_id" to "n1"))
        assertEquals("/management/actions/n1", h.lastRequest.url.encodedPath)

        val action = mapOf("enabled" to true, "type" to "Alert", "min_report_count" to 1)
        h.call("logsearch_create_notification", mapOf("action" to action))
        assertEquals(HttpMethod.Post, h.lastRequest.method)
        assertTrue("enabled" in h.lastBodyJson()["action"]!!.jsonObject)

        h.call("logsearch_replace_notification", mapOf("notification_id" to "n1", "action" to action))
        assertEquals(HttpMethod.Put, h.lastRequest.method)

        h.call("logsearch_update_notification", mapOf("notification_id" to "n1", "action" to mapOf("enabled" to false)))
        assertEquals(HttpMethod.Patch, h.lastRequest.method)

        h.call("logsearch_delete_notification", mapOf("notification_id" to "n1"))
        assertEquals(HttpMethod.Delete, h.lastRequest.method)

        h.call("logsearch_list_notification_targets", mapOf("notification_id" to "n1"))
        assertEquals("/management/actions/n1/targets", h.lastRequest.url.encodedPath)

        h.call(
            "logsearch_update_notification_targets",
            mapOf("notification_id" to "n1", "target" to mapOf("name" to "hook", "type" to "webhook")),
        )
        assertEquals(HttpMethod.Patch, h.lastRequest.method)
        assertEquals("/management/actions/n1/targets", h.lastRequest.url.encodedPath)
    }

    @Test
    fun `target crud maps to management-targets endpoints and builds the body`() = runBlocking {
        val h = harness(body = "[]")
        h.call("logsearch_list_targets")
        assertEquals("/management/targets", h.lastRequest.url.encodedPath)

        h.call("logsearch_get_target", mapOf("target_id" to "t1"))
        assertEquals("/management/targets/t1", h.lastRequest.url.encodedPath)

        h.call(
            "logsearch_create_target",
            mapOf(
                "name" to "Ops Slack",
                "type" to "slack",
                "params_set" to mapOf("url" to "https://hooks.slack.com/services/x"),
            ),
        )
        assertEquals(HttpMethod.Post, h.lastRequest.method)
        val target = h.lastBodyJson()["target"]!!.jsonObject
        assertEquals("Ops Slack", target["name"]!!.jsonPrimitive.content)
        assertEquals("slack", target["type"]!!.jsonPrimitive.content)
        assertTrue("url" in target["params_set"]!!.jsonObject)
        // Spec-required members must always be present, defaulting to {}.
        assertTrue("alert_content_set" in target)
        assertTrue("user_data" in target)

        h.call(
            "logsearch_replace_target",
            mapOf("target_id" to "t1", "name" to "n", "type" to "webhook", "params_set" to mapOf("url" to "https://w")),
        )
        assertEquals(HttpMethod.Put, h.lastRequest.method)
        assertEquals("/management/targets/t1", h.lastRequest.url.encodedPath)

        h.call("logsearch_delete_target", mapOf("target_id" to "t1"))
        assertEquals(HttpMethod.Delete, h.lastRequest.method)
    }

    @Test
    fun `label crud maps to management-labels endpoints`() = runBlocking {
        val h = harness(body = "[]")
        h.call("logsearch_list_labels")
        assertEquals("/management/labels", h.lastRequest.url.encodedPath)

        h.call("logsearch_get_label", mapOf("label_id" to "lb1"))
        assertEquals("/management/labels/lb1", h.lastRequest.url.encodedPath)

        h.call("logsearch_create_label", mapOf("name" to "suspicious", "color" to "ff0000"))
        assertEquals(HttpMethod.Post, h.lastRequest.method)
        val label = h.lastBodyJson()["label"]!!.jsonObject
        assertEquals("suspicious", label["name"]!!.jsonPrimitive.content)
        assertEquals("ff0000", label["color"]!!.jsonPrimitive.content)

        h.call("logsearch_replace_label", mapOf("label_id" to "lb1", "name" to "n", "color" to "00ff00"))
        assertEquals(HttpMethod.Put, h.lastRequest.method)

        h.call("logsearch_update_label", mapOf("label_id" to "lb1", "color" to "0000ff"))
        assertEquals(HttpMethod.Patch, h.lastRequest.method)
        assertEquals("0000ff", h.lastBodyJson()["label"]!!.jsonObject["color"]!!.jsonPrimitive.content)

        h.call("logsearch_delete_label", mapOf("label_id" to "lb1"))
        assertEquals(HttpMethod.Delete, h.lastRequest.method)
    }

    @Test
    fun `create detection rule without tag object is a tool error`() = runBlocking {
        val h = harness()
        val result = h.call("logsearch_create_detection_rule", emptyMap())
        assertTrue(result.isError == true)
    }
}
