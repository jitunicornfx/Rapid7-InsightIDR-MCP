package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.testutil.InMemoryTransport
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What src/main/resources/simplelogger.properties lets through to stderr. slf4j-simple writes to
 * whatever System.err is when it logs, so swapping System.err captures it.
 *
 * Measured on 2026-10-13 with the 0.4.2 jar over stdio: 451 stderr lines for a start, a tools/list and
 * one tools/call, 440 of them from the SDK's "FeatureRegistry[Tool]" logger (three INFO lines per tool
 * registered, two per tool called).
 */
class StartupLoggingTest {

    private val config = Config(
        apiKey = "test-key",
        region = Region.US,
        baseUrl = "https://us.api.insight.rapid7.com",
        requestTimeoutMillis = 60_000,
    )

    /** Builds the full server, lists the tools and calls one, and returns what reached stderr. */
    private fun stderrOfAStartAndOneCall(): String = runBlocking {
        val realErr = System.err
        val captured = ByteArrayOutputStream()
        System.setErr(PrintStream(captured, true, "UTF-8"))
        try {
            val server = buildInsightIdrServer(Rapid7Client(config, MockEngine { respond("{}", HttpStatusCode.OK) }))
            val (clientTransport, serverTransport) = InMemoryTransport.createLinkedPair()
            val client = Client(Implementation("test-client", "1.0"))
            coroutineScope {
                launch { client.connect(clientTransport) }
                launch { server.createSession(serverTransport) }
            }
            assertEquals(146, client.listTools().tools.size, "expected the full tool inventory")
            assertNotEquals(true, client.callTool("insightidr_server_info", emptyMap()).isError)
            client.close()
        } finally {
            System.setErr(realErr)
        }
        captured.toString("UTF-8")
    }

    @Test
    fun `the SDK's tool registry does not write a line per tool to stderr`() {
        val stderr = stderrOfAStartAndOneCall()

        val registryLines = stderr.lines().filter { "FeatureRegistry[Tool]" in it }
        assertEquals(emptyList(), registryLines, "registering 146 tools and calling one wrote these lines")
    }

    @Test
    fun `other SDK loggers still log at INFO, so only that one logger was quieted`() {
        // The positive control for the test above: if capturing stderr did not work at all, "no
        // registry lines" would pass for nothing. It also fails if the fix is a raised default level,
        // which would hide INFO lines from every library (Ktor's in --http mode among them).
        val stderr = stderrOfAStartAndOneCall()

        assertTrue(
            stderr.lines().any { " INFO " in it && "FeatureRegistry" !in it },
            "expected at least one INFO line from another SDK logger; stderr was:\n$stderr",
        )
    }
}
