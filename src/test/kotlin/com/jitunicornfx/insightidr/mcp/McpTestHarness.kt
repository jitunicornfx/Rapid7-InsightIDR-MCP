package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.testutil.InMemoryTransport
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A running in-process MCP client connected to a server whose tools use a MockEngine-backed
 * [Rapid7Client]. Assert on [requests] / [lastRequest] (what the tool sent to the API) and on the
 * [CallToolResult] returned by [call].
 */
class McpTestHarness(
    private val engine: MockEngine,
    private val client: Client,
    private val capturedBodies: List<String?>,
) {
    val requests: List<HttpRequestData> get() = engine.requestHistory
    val lastRequest: HttpRequestData get() = requests.last()
    val lastBody: String? get() = capturedBodies.lastOrNull()

    fun lastBodyJson(): JsonObject = JsonCodec.compact.parseToJsonElement(lastBody!!).jsonObject

    suspend fun call(name: String, args: Map<String, Any?> = emptyMap()): CallToolResult =
        client.callTool(name, args)
}

/**
 * Build a connected harness. [register] registers the tools under test on the server; [status] and
 * [responseBody] control the canned HTTP response the MockEngine returns for every request.
 */
suspend fun mcpHarness(
    status: HttpStatusCode = HttpStatusCode.OK,
    responseBody: String = "{}",
    register: Server.(Rapid7Client) -> Unit,
): McpTestHarness {
    val bodies = mutableListOf<String?>()
    val engine = MockEngine { request ->
        bodies += (request.body as? TextContent)?.text
        respond(
            content = responseBody,
            status = status,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    val config = Config(
        apiKey = "test-key",
        region = Region.US,
        baseUrl = "https://us.api.insight.rapid7.com",
        requestTimeoutMillis = 60_000,
    )

    val server = Server(
        Implementation("test-server", "1.0"),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
    )
    server.register(Rapid7Client(config, engine))

    val (clientTransport, serverTransport) = InMemoryTransport.createLinkedPair()
    val client = Client(Implementation("test-client", "1.0"))
    coroutineScope {
        launch { client.connect(clientTransport) }
        launch { server.createSession(serverTransport) }
    }

    return McpTestHarness(engine, client, bodies)
}
