package com.jitunicornfx.insightidr.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The HTTP transport as it really runs: a bound socket, the real guard, the real MCP routes. The
 * guard has its own unit tests; what these prove is that it is actually IN FRONT of the routes, which
 * no test of the guard alone can show.
 */
class HttpRuntimeTest {

    private val token = "0123456789abcdef-token"

    // The update check would otherwise call GitHub from a unit test.
    private val config = Config(
        apiKey = "test-key",
        region = Region.US,
        baseUrl = "https://us.api.insight.rapid7.com",
        requestTimeoutMillis = 60_000,
        updateCheckDisabled = true,
    )

    private var runtime: HttpRuntime? = null
    private val http = HttpClient(OkHttp) { expectSuccess = false }
    private var startupLog = ""

    @AfterTest
    fun stop() {
        runtime?.let { it.engine.stop(100, 500); it.shutdown() }
        http.close()
        UpdateStatus.reset()
    }

    /** Start on an ephemeral loopback port and return its base URL. */
    private fun start(
        config: Config,
        notifyGraceMillis: Long = 0,
        update: UpdateChecker.Result? = null,
    ): String = runBlocking {
        val realErr = System.err
        val captured = ByteArrayOutputStream()
        System.setErr(PrintStream(captured, true, "UTF-8"))
        val built = try {
            buildHttpRuntime(
                Rapid7Client(config, MockEngine { respond("{}", HttpStatusCode.OK) }),
                config,
                "127.0.0.1",
                0,
                notifyGraceMillis = notifyGraceMillis,
                startCheck = { update?.let { CompletableDeferred(it) } },
            )
        } finally {
            System.setErr(realErr)
        }
        startupLog = captured.toString("UTF-8")
        runtime = built
        built.engine.start(wait = false)
        val port = withTimeout(10_000) { built.engine.engine.resolvedConnectors().first().port }
        "http://127.0.0.1:$port"
    }

    /** The status of a GET, without reading the body: a successful one is an endless event stream. */
    private suspend fun status(url: String, vararg headers: Pair<String, String>): HttpStatusCode =
        http.prepareGet(url) { headers.forEach { (name, value) -> header(name, value) } }.execute { it.status }

    @Test
    fun `with a token configured, the MCP endpoint is unreachable without it`() = runBlocking {
        val base = start(config.copy(httpAuthToken = token))

        assertEquals(HttpStatusCode.Unauthorized, status(base))
        assertEquals(HttpStatusCode.Unauthorized, status(base, HttpHeaders.Authorization to "Bearer not-the-token"))
        assertEquals(HttpStatusCode.OK, status(base, HttpHeaders.Authorization to "Bearer $token"))
        assertFalse(token in startupLog, "the token must never be logged")
        assertFalse("WARNING" in startupLog, "nothing to warn about when a token is set")
    }

    @Test
    fun `the guard sits in front of the MCP routes, not beside them`() = runBlocking {
        val base = start(config.copy(httpAuthToken = token, httpAllowedOrigins = listOf("https://ui.example")))
        val auth = HttpHeaders.Authorization to "Bearer $token"

        // DNS rebinding: a page on attacker.example that resolves its own name to 127.0.0.1.
        assertEquals(421, status(base, auth, HttpHeaders.Host to "attacker.example").value)
        // A browser page on another origin, even holding a valid token.
        assertEquals(HttpStatusCode.Forbidden, status(base, auth, HttpHeaders.Origin to "https://evil.example"))
        assertEquals(HttpStatusCode.OK, status(base, auth, HttpHeaders.Origin to "https://ui.example"))
    }

    @Test
    fun `loopback without a token still serves, and says loudly that it is open`() = runBlocking {
        val base = start(config)

        assertEquals(HttpStatusCode.OK, status(base))
        assertTrue("WARNING" in startupLog && Config.ENV_HTTP_TOKEN in startupLog, startupLog)
        // Even with no token the rebinding and cross-origin defences are on.
        assertEquals(421, status(base, HttpHeaders.Host to "attacker.example").value)
        assertEquals(HttpStatusCode.Forbidden, status(base, HttpHeaders.Origin to "https://evil.example"))
    }

    @Test
    fun `the runtime itself refuses a network bind without a token`() {
        // The command refuses first. This is the second lock, on the code that actually opens the port.
        val client = Rapid7Client(config, MockEngine { respond("{}", HttpStatusCode.OK) })
        try {
            for (exposed in listOf("0.0.0.0", "10.0.0.5", "::")) {
                assertFailsWith<IllegalArgumentException>(exposed) { buildHttpRuntime(client, config, exposed, 0) }
            }
        } finally {
            client.close()
        }
    }

    // ---------------------------------------------------------------------
    // The transports themselves, driven by the SDK's own clients over a real socket.
    // ---------------------------------------------------------------------

    private val tokenConfig get() = config.copy(httpAuthToken = token)

    private fun authenticatedClient() = HttpClient(OkHttp) {
        install(SSE)
        defaultRequest { header(HttpHeaders.Authorization, "Bearer $token") }
    }

    private val initialize =
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"0"}}}"""

    @Test
    fun `streamable http serves the full tool set on the mcp path`() = runBlocking {
        val base = start(tokenConfig)
        val client = authenticatedClient()
        try {
            val mcp = Client(Implementation("streamable-test", "0"))
            withTimeout(20_000) { mcp.connect(StreamableHttpClientTransport(client, "$base$STREAMABLE_HTTP_PATH")) }
            assertEquals(146, withTimeout(20_000) { mcp.listTools() }.tools.size)
            mcp.close()
        } finally {
            client.close()
        }
    }

    @Test
    fun `legacy sse still works where 0_3 clients expect it, and on its new path`() = runBlocking {
        // This server updates itself. A client configured against the root URL must keep working after
        // an unattended upgrade, or the upgrade is an outage nobody chose.
        val base = start(tokenConfig)
        for (path in listOf(DEPRECATED_ROOT_SSE_PATH, LEGACY_SSE_PATH)) {
            val client = authenticatedClient()
            try {
                val mcp = Client(Implementation("sse-test", "0"))
                withTimeout(20_000) { mcp.connect(SseClientTransport(client, "$base$path")) }
                assertEquals(146, withTimeout(20_000) { mcp.listTools() }.tools.size, "over $path")
                mcp.close()
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `every path is behind the guard, and an unauthenticated initialize builds nothing`() = runBlocking {
        val base = start(tokenConfig)
        for (path in listOf(STREAMABLE_HTTP_PATH, LEGACY_SSE_PATH, DEPRECATED_ROOT_SSE_PATH)) {
            assertEquals(HttpStatusCode.Unauthorized, status("$base$path"), path)
        }
        // Streamable HTTP builds a whole 146-tool server for a sessionless POST. Without the guard in
        // front, that is a free way for anyone to make this process allocate.
        val refused = http.post("$base$STREAMABLE_HTTP_PATH") {
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            contentType(ContentType.Application.Json)
            setBody(initialize)
        }
        assertEquals(HttpStatusCode.Unauthorized, refused.status)

        val accepted = http.post("$base$STREAMABLE_HTTP_PATH") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            contentType(ContentType.Application.Json)
            setBody(initialize)
        }
        assertEquals(HttpStatusCode.OK, accepted.status, accepted.bodyAsText())
        assertTrue(accepted.headers["Mcp-Session-Id"] != null, "streamable http assigns a session id")
        assertTrue(SERVER_NAME in accepted.bodyAsText())
    }

    @Test
    fun `the startup log names the paths`() {
        start(tokenConfig)
        for (path in listOf(STREAMABLE_HTTP_PATH, LEGACY_SSE_PATH)) assertTrue(path in startupLog, startupLog)
        assertTrue("deprecated" in startupLog)
    }

    @Test
    fun `the update notice reaches a streamable http client`() = runBlocking {
        // A server-initiated message travels on a stream the CLIENT opens, just after `initialized`.
        // Sent the instant `initialized` arrives it has nowhere to go; hence the grace period.
        val available = UpdateChecker.Result(updateAvailable = true, currentVersion = "0.3.1", latestVersion = "9.9.9")
        val base = start(tokenConfig.copy(autoUpdateDisabled = true), notifyGraceMillis = 1_500, update = available)
        val client = authenticatedClient()
        try {
            val received = CompletableDeferred<LoggingMessageNotification>()
            val mcp = Client(Implementation("notice-test", "0"))
            mcp.setNotificationHandler<LoggingMessageNotification>(Method.Defined.NotificationsMessage) { n ->
                received.complete(n)
                CompletableDeferred(Unit)
            }
            withTimeout(20_000) { mcp.connect(StreamableHttpClientTransport(client, "$base$STREAMABLE_HTTP_PATH")) }

            val notice = withTimeoutOrNull(15_000) { received.await() }
            assertTrue(notice != null, "the update notice never arrived over streamable http")
            assertEquals("9.9.9", notice.params.data.jsonObject.getValue("latest_version").jsonPrimitive.content)
            mcp.close()
        } finally {
            client.close()
        }
    }

    /**
     * Drive Streamable HTTP by hand so the moment the client opens its event stream is under the
     * test's control: initialize, say `initialized`, wait [openStreamAfterMillis], THEN open the GET
     * stream and report whether the update notice turns up on it.
     */
    private suspend fun noticeArrives(base: String, openStreamAfterMillis: Long): Boolean {
        fun io.ktor.client.request.HttpRequestBuilder.mcpHeaders(session: String? = null) {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            session?.let { header("Mcp-Session-Id", it) }
        }
        val init = http.post("$base$STREAMABLE_HTTP_PATH") {
            mcpHeaders()
            contentType(ContentType.Application.Json)
            setBody(initialize)
        }
        val session = init.headers["Mcp-Session-Id"]!!
        init.bodyAsText()
        http.post("$base$STREAMABLE_HTTP_PATH") {
            mcpHeaders(session)
            contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        }.bodyAsText()

        delay(openStreamAfterMillis)

        return withTimeoutOrNull(4_000) {
            http.prepareGet("$base$STREAMABLE_HTTP_PATH") { mcpHeaders(session) }.execute { response ->
                val channel = response.bodyAsChannel()
                var found = false
                while (!found) {
                    val line = channel.readUTF8Line() ?: break
                    if ("latest_version" in line) found = true
                }
                found
            }
        } ?: false
    }

    private val available = UpdateChecker.Result(updateAvailable = true, currentVersion = "0.3.1", latestVersion = "9.9.9")

    @Test
    fun `a notice sent before the client opens its stream is lost, which is what the grace period is for`() = runBlocking {
        // Measured against SDK 0.15.0, not assumed: a server-initiated message with no open stream to
        // travel on is DROPPED, not queued. The SDK's own Kotlin client happens to open its stream
        // fast enough to win that race; a client that takes 700 ms does not.
        //
        // If this starts failing because the notice ARRIVES, the SDK has begun queueing, and
        // HTTP_NOTIFY_GRACE_MILLIS can be deleted.
        val base = start(tokenConfig.copy(autoUpdateDisabled = true), notifyGraceMillis = 0, update = available)
        assertFalse(noticeArrives(base, openStreamAfterMillis = 700))
    }

    @Test
    fun `within the grace period a client that opens its stream late still gets the notice`() = runBlocking {
        val base = start(tokenConfig.copy(autoUpdateDisabled = true), notifyGraceMillis = 1_500, update = available)
        assertTrue(noticeArrives(base, openStreamAfterMillis = 700))
    }

    @Test
    fun `the production grace period is long enough to matter and short enough not to be noticed`() {
        assertTrue(HTTP_NOTIFY_GRACE_MILLIS in 1_000L..5_000L, "was $HTTP_NOTIFY_GRACE_MILLIS")
    }

    @Test
    fun `the paths are a public contract`() {
        // Client configurations, the README and other people's scripts name these literally. A test
        // that only ever uses the constants would follow a rename without a murmur.
        assertEquals("/mcp", STREAMABLE_HTTP_PATH)
        assertEquals("/sse", LEGACY_SSE_PATH)
        assertEquals("/", DEPRECATED_ROOT_SSE_PATH)
    }

    @Test
    fun `an allow-listed browser origin works on every path, not just past the guard`() = runBlocking {
        // The SDK's transports carry their own Host/Origin check, localhost-only and on by default.
        // Left on, it answers 403 to an origin the operator explicitly allowed — after the guard and
        // CORS have both said yes. That is how INSIGHTIDR_HTTP_ALLOWED_ORIGINS came never to work.
        val base = start(tokenConfig.copy(httpAllowedOrigins = listOf("https://ui.example")))
        val viaBrowser = http.post("$base$STREAMABLE_HTTP_PATH") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Origin, "https://ui.example")
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            contentType(ContentType.Application.Json)
            setBody(initialize)
        }
        assertEquals(HttpStatusCode.OK, viaBrowser.status, viaBrowser.bodyAsText())
        assertEquals("https://ui.example", viaBrowser.headers[HttpHeaders.AccessControlAllowOrigin])

        for (path in listOf(LEGACY_SSE_PATH, DEPRECATED_ROOT_SSE_PATH)) {
            val auth = HttpHeaders.Authorization to "Bearer $token"
            assertEquals(HttpStatusCode.OK, status("$base$path", auth, HttpHeaders.Origin to "https://ui.example"), path)
            assertEquals(HttpStatusCode.Forbidden, status("$base$path", auth, HttpHeaders.Origin to "https://evil.example"), path)
        }
    }
}
