package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.testutil.InMemoryTransport
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Limits belong in the schema, not only in prose. These tests read the schemas the way a client
 * does — from the full, live tool list — so a tool added later without its bounds fails here.
 */
class ToolSchemaBoundsTest {

    private fun allTools(): List<Tool> = runBlocking {
        val config = Config("k", Region.US, "https://us.api.insight.rapid7.com", 60_000)
        val server = buildInsightIdrServer(Rapid7Client(config, MockEngine { respond("{}", HttpStatusCode.OK) }))
        val (clientTransport, serverTransport) = InMemoryTransport.createLinkedPair()
        val client = Client(Implementation("test-client", "1.0"))
        coroutineScope {
            launch { client.connect(clientTransport) }
            launch { server.createSession(serverTransport) }
        }
        client.listTools().tools
    }

    private val tools by lazy { allTools() }

    /** Every (tool, property schema) pair declaring a property called [name]. */
    private fun declaring(name: String): List<Pair<String, JsonObject>> =
        tools.mapNotNull { tool -> tool.inputSchema.properties?.get(name)?.jsonObject?.let { tool.name to it } }

    private fun JsonObject.bound(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

    @Test
    fun `every per_page is bounded to what the Log Search API accepts`() {
        val found = declaring("per_page")
        assertTrue(found.size >= 5, "expected every Log Search query tool, found ${found.map { it.first }}")
        for ((tool, schema) in found) {
            assertEquals(1L, schema.bound("minimum"), "$tool per_page minimum")
            assertEquals(500L, schema.bound("maximum"), "$tool per_page maximum")
            assertNotNull(schema.bound("default"), "$tool per_page default")
        }
    }

    @Test
    fun `every page index starts at zero and every page size has a floor`() {
        val indexes = declaring("index")
        assertTrue(indexes.size >= 15, "found ${indexes.size}")
        for ((tool, schema) in indexes) assertEquals(0L, schema.bound("minimum"), "$tool index minimum")

        val sizes = declaring("size")
        assertEquals(indexes.map { it.first }, sizes.map { it.first }, "index and size are declared together")
        for ((tool, schema) in sizes) assertNotNull(schema.bound("minimum"), "$tool size minimum")
    }

    @Test
    fun `page size limits follow each API's own spec`() {
        val sizes = declaring("size").toMap()
        // v2 and most of v1: 1..100.
        for (tool in listOf("list_investigations", "list_comments", "list_attachments", "list_cloud_webhooks")) {
            assertEquals(1L to 100L, sizes.getValue(tool).let { it.bound("minimum") to it.bound("maximum") }, tool)
        }
        // The v1 entity searches alone go to 1000.
        assertEquals(1_000L, sizes.getValue("search_assets").bound("maximum"))
        // Alert triage: minimum 0, and the spec gives NO maximum, so none is invented.
        assertEquals(0L, sizes.getValue("search_alerts").bound("minimum"))
        assertNull(sizes.getValue("search_alerts").bound("maximum"), "a made-up ceiling would refuse valid requests")
        // Cloud webhooks default to 10 where everything else defaults to 20.
        assertEquals(10L, sizes.getValue("list_cloud_webhooks").bound("default"))
    }

    @Test
    fun `a default always lies inside its own bounds`() {
        for (tool in tools) {
            for ((name, schema) in tool.inputSchema.properties.orEmpty()) {
                val property = schema.jsonObject
                val default = property.bound("default") ?: continue
                property.bound("minimum")?.let { assertTrue(default >= it, "${tool.name}.$name default $default < minimum $it") }
                property.bound("maximum")?.let { assertTrue(default <= it, "${tool.name}.$name default $default > maximum $it") }
            }
        }
    }

    @Test
    fun `spool caps are declared with the ceilings the handler enforces`() {
        val spool = tools.single { it.name == "logsearch_spool_query_to_file" }.inputSchema.properties!!
        fun max(name: String) = spool.getValue(name).jsonObject.bound("maximum")
        assertEquals(2_000L, max("max_pages"))
        assertEquals(5_000_000L, max("max_events"))
        assertEquals(4L * 1024 * 1024 * 1024, max("max_bytes"), "larger than an Int, which is why bounds are Long")
        assertEquals(600_000L, max("max_duration_ms"))
        assertEquals(10L, max("sample_events"))
    }

    @Test
    fun `alert action filters are enums, not prose`() {
        val actions = tools.single { it.name == "list_alert_actions" }.inputSchema.properties!!
        fun items(name: String) = actions.getValue(name).jsonObject.getValue("items").jsonObject
            .getValue("enum").jsonArray.map { it.jsonPrimitive.content }

        assertEquals(listOf("PATCH_ALERT", "CREATE_INVESTIGATION"), items("types"))
        assertEquals(listOf("PENDING", "RUNNING", "FAILED", "COMPLETE_WITH_ISSUES", "COMPLETED"), items("statuses"))

        val tasks = tools.single { it.name == "get_alert_action_tasks" }.inputSchema.properties!!
        assertEquals(5, tasks.getValue("statuses").jsonObject.getValue("items").jsonObject.getValue("enum").jsonArray.size)
    }

    @Test
    fun `other spec limits are declared`() {
        fun property(tool: String, name: String) =
            tools.single { it.name == tool }.inputSchema.properties!!.getValue(name).jsonObject

        assertEquals(1L, property("list_alert_fields", "path_depth").bound("minimum"))
        // The v2 spec: "The minimum ... is 0".
        assertEquals(0L, property("bulk_close_investigations", "max_investigations_to_close").bound("minimum"))
        assertEquals(100L, property("replay_cloud_webhook_events", "event_ids").bound("maxItems"))
    }

    @Test
    fun `export_format is an enum of what the API supports, and points at the right poll tool`() {
        val exporting = tools.filter { it.inputSchema.properties?.containsKey("export_format") == true }
        assertEquals(4, exporting.size, "found ${exporting.map { it.name }}")
        for (tool in exporting) {
            val property = tool.inputSchema.properties!!.getValue("export_format").jsonObject
            assertEquals(listOf("csv"), property.getValue("enum").jsonArray.map { it.jsonPrimitive.content }, tool.name)
            val description = property.getValue("description").jsonPrimitive.content
            // Audit export jobs live on a different endpoint, with a different tool.
            val expected = if ("audit" in tool.name) "logsearch_audit_get_export_job" else "logsearch_get_export_job"
            assertTrue(expected in description, "${tool.name} must point at $expected")
            if ("audit" !in tool.name) assertTrue("logsearch_audit_get_export_job" !in description, tool.name)
        }
    }

    @Test
    fun `the audit query tools never send the model to a tool that cannot reach audit logs`() {
        val audit = tools.filter { it.name.startsWith("logsearch_audit_query") }
        assertEquals(2, audit.size)
        for (tool in audit) {
            val description = tool.description.orEmpty()
            assertTrue("ONE page" in description, "${tool.name} must carry result-size guidance")
            assertTrue("logsearch_audit_get_export_job" in description, tool.name)
            // The spool tool posts to /query/logs. The ordinary guidance recommends it; copied here
            // verbatim it would be advice that cannot work.
            assertTrue("does not cover audit logs" in description, tool.name)
        }
    }

    @Test
    fun `the download limit says what omitting it means`() {
        val limit = tools.single { it.name == "logsearch_download_log_data" }.inputSchema.properties!!
            .getValue("limit").jsonObject
        assertEquals(500_000_000L, limit.bound("maximum"))
        assertTrue("500,000,000" in limit.getValue("description").jsonPrimitive.content)
    }

    @Test
    fun `investigate_alerts hints at values without constraining them`() {
        val properties = tools.single { it.name == "investigate_alerts" }.inputSchema.properties!!
        for (name in listOf("status", "disposition")) {
            val property = properties.getValue(name).jsonObject
            // Spec 39.10.0 gives no enum. One borrowed from another API could refuse a valid value.
            assertNull(property["enum"], "$name must not be a hard enum")
            assertTrue("spec lists no values" in property.getValue("description").jsonPrimitive.content)
        }
        assertTrue("OPEN" in properties.getValue("status").jsonObject.getValue("description").jsonPrimitive.content)
    }

    @Test
    fun `the DSL refuses bounds that contradict themselves`() {
        fun schema(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(block)

        assertFailsWith<IllegalArgumentException> { schema { integerParam("n", "d", min = 10, max = 1) } }
        assertFailsWith<IllegalArgumentException> { schema { integerParam("n", "d", min = 1, max = 10, default = 11) } }
        assertFailsWith<IllegalArgumentException> { schema { integerParam("n", "d", min = 1, default = 0) } }

        // Nothing is emitted that was not asked for: an unbounded integer stays unbounded.
        val plain = schema { integerParam("n", "d") }.getValue("n").jsonObject
        assertEquals(setOf("type", "description"), plain.keys)
        val array = schema { stringArrayParam("a", "d") }.getValue("a").jsonObject
        assertEquals(setOf("type", "description", "items"), array.keys)
        assertEquals(setOf("type"), array.getValue("items").jsonObject.keys)

        val bounded = schema { integerParam("n", "d", min = 0, max = 4L * 1024 * 1024 * 1024) }.getValue("n").jsonObject
        assertEquals(4_294_967_296L, bounded.getValue("maximum").jsonPrimitive.long)
    }
}
