package com.jitunicornfx.insightidr.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    private fun start(config: Config): String = runBlocking {
        val realErr = System.err
        val captured = ByteArrayOutputStream()
        System.setErr(PrintStream(captured, true, "UTF-8"))
        val built = try {
            buildHttpRuntime(Rapid7Client(config, MockEngine { respond("{}", HttpStatusCode.OK) }), config, "127.0.0.1", 0)
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
}
