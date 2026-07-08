package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.testutil.InMemoryTransport
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpServerFactoryTest {

    @Test
    fun `buildInsightIdrServer registers the complete tool inventory`() = runBlocking {
        val engine = MockEngine { respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val config = Config(
            apiKey = "test-key",
            region = Region.US,
            baseUrl = "https://us.api.insight.rapid7.com",
            requestTimeoutMillis = 60_000,
        )
        val server = buildInsightIdrServer(Rapid7Client(config, engine))

        val (clientTransport, serverTransport) = InMemoryTransport.createLinkedPair()
        val client = Client(Implementation("test-client", "1.0"))
        coroutineScope {
            launch { client.connect(clientTransport) }
            launch { server.createSession(serverTransport) }
        }

        val tools = client.listTools().tools
        val names = tools.map { it.name }.toSet()

        assertEquals(129, tools.size, "expected the full tool inventory")
        assertEquals(tools.size, names.size, "tool names must be unique")

        // One representative per registry group.
        for (probe in listOf(
            "validate_connection",           // system
            "list_investigations",           // v2 investigations
            "list_investigations_v1",        // v1 investigations
            "search_accounts",               // entities
            "list_comments",                 // comments
            "list_attachments",              // attachments
            "list_cloud_webhooks",           // cloud webhooks
            "create_community_threat",       // community threats
            "add_collector",                 // collectors
            "get_health_metrics",            // health metrics
            "logsearch_query_log",           // log search: query
            "logsearch_get_next_page",       // log search: pagination
            "logsearch_list_logs",           // log search: management
            "logsearch_list_variables",      // log search: variables/metrics
            "logsearch_list_detection_rules", // log search: detection rules
            "logsearch_list_audit_logs",     // log search: audit
        )) {
            assertTrue(probe in names, "missing tool: $probe")
        }
    }
}
