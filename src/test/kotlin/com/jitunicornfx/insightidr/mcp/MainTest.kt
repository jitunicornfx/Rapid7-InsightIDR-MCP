package com.jitunicornfx.insightidr.mcp

import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainTest {

    private val fakeConfig = Config(
        apiKey = "test-key",
        region = Region.US,
        baseUrl = "https://us.api.insight.rapid7.com",
        requestTimeoutMillis = 60_000,
    )

    private class Captured {
        var transport: Transport? = null
        var host: String? = null
        var port: Int? = null
        var config: Config? = null
    }

    /** Build a command whose config is fixed and whose server launch is captured (never blocks). */
    private fun commandCapturing(captured: Captured, config: Config = fakeConfig) =
        Rapid7InsightIdrCommand(
            configProvider = { config },
            serve = { transport, host, port, effectiveConfig ->
                captured.transport = transport
                captured.host = host
                captured.port = port
                captured.config = effectiveConfig
            },
        )

    @Test
    fun `help documents transport and http options`() {
        val result = commandCapturing(Captured()).test("--help")
        assertEquals(0, result.statusCode)
        assertTrue("--http" in result.output && "--stdio" in result.output)
        assertTrue("--host" in result.output || "--ip" in result.output)
        assertTrue("--port" in result.output)
    }

    @Test
    fun `unknown option is a usage error`() {
        val result = commandCapturing(Captured()).test("--definitely-not-an-option")
        assertTrue(result.statusCode != 0)
    }

    @Test
    fun `default run selects stdio with default host and port`() {
        val captured = Captured()
        val result = commandCapturing(captured).test("")
        assertEquals(0, result.statusCode)
        assertEquals(Transport.STDIO, captured.transport)
        assertEquals("127.0.0.1", captured.host)
        assertEquals(3001, captured.port)
    }

    @Test
    fun `explicit stdio flag selects stdio`() {
        val captured = Captured()
        commandCapturing(captured).test("--stdio")
        assertEquals(Transport.STDIO, captured.transport)
    }

    @Test
    fun `http with host and port is parsed and dispatched`() {
        val captured = Captured()
        val result = commandCapturing(captured).test("--http --host 1.2.3.4 --port 9999")
        assertEquals(0, result.statusCode)
        assertEquals(Transport.HTTP, captured.transport)
        assertEquals("1.2.3.4", captured.host)
        assertEquals(9999, captured.port)
    }

    @Test
    fun `ip is an alias for host`() {
        val captured = Captured()
        commandCapturing(captured).test("--http --ip 10.0.0.5")
        assertEquals("10.0.0.5", captured.host)
        assertEquals(3001, captured.port)
    }

    @Test
    fun `configuration error exits non-zero without launching a server`() {
        val captured = Captured()
        val command = Rapid7InsightIdrCommand(
            configProvider = { throw IllegalStateException("missing INSIGHTIDR_API_KEY") },
            serve = { transport, host, port, _ ->
                captured.transport = transport
                captured.host = host
                captured.port = port
            },
        )
        val result = command.test("--http")
        assertEquals(1, result.statusCode)
        assertTrue("Configuration error" in result.output)
        assertTrue("missing INSIGHTIDR_API_KEY" in result.output)
        assertNull(captured.transport, "server must not launch when config fails")
    }

    @Test
    fun `help documents the update opt-out options`() {
        val output = commandCapturing(Captured()).test("--help").output
        assertTrue("--no-auto-update" in output)
        assertTrue("--no-update-check" in output)
    }

    @Test
    fun `automatic installation runs unless it is switched off`() {
        val on = Captured()
        commandCapturing(on).test("--stdio")
        assertEquals(false, on.config?.autoUpdateDisabled, "enabled by default")

        val off = Captured()
        commandCapturing(off).test("--stdio --no-auto-update")
        assertEquals(true, off.config?.autoUpdateDisabled)
        assertEquals(false, off.config?.updateCheckDisabled, "the check itself still runs")
    }

    @Test
    fun `--no-update-check also stops anything being installed`() {
        val captured = Captured()
        commandCapturing(captured).test("--stdio --no-update-check")
        assertEquals(true, captured.config?.updateCheckDisabled)
        assertEquals(true, captured.config?.autoUpdateDisabled, "no check means nothing to install")
    }

    @Test
    fun `a command-line flag can only tighten what the environment already disabled`() {
        // The environment switched installation off; omitting the flag must NOT turn it back on.
        val envDisabled = fakeConfig.copy(autoUpdateDisabled = true, updateCheckDisabled = true)
        val captured = Captured()
        commandCapturing(captured, envDisabled).test("--stdio")
        assertEquals(true, captured.config?.autoUpdateDisabled)
        assertEquals(true, captured.config?.updateCheckDisabled)
    }
}
