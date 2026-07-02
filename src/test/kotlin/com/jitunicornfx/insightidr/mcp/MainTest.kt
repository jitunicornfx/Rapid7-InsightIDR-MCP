package com.jitunicornfx.insightidr.mcp

import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MainTest {

    // Uses Clikt's test() harness, which parses/runs without terminating the JVM.
    // Only --help and parse-error paths are exercised: they short-circuit before run(),
    // so they never start a (blocking) transport or read the environment.

    @Test
    fun `help documents the transport and http options`() {
        val result = Rapid7InsightIdrCommand().test("--help")
        assertEquals(0, result.statusCode)
        assertTrue("--http" in result.output, "help should mention --http")
        assertTrue("--stdio" in result.output, "help should mention --stdio")
        assertTrue("--host" in result.output || "--ip" in result.output, "help should mention the host option")
        assertTrue("--port" in result.output, "help should mention --port")
    }

    @Test
    fun `unknown option is a usage error`() {
        val result = Rapid7InsightIdrCommand().test("--definitely-not-an-option")
        assertTrue(result.statusCode != 0, "unknown option should produce a non-zero status code")
    }
}
