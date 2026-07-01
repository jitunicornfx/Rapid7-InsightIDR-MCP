package com.jitunicornfx.insightidr.mcp

import io.ktor.http.HttpMethod
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * Entry point for the Rapid7 InsightIDR MCP server.
 *
 * Transports:
 *  - `--stdio` (default): standard MCP stdio transport for local desktop clients.
 *  - `--http [port]`: Streamable HTTP / SSE transport for network clients.
 *
 * All diagnostics are written to stderr so stdout stays reserved for the JSON-RPC channel.
 */
fun main(args: Array<String>) {
    val mode = args.firstOrNull() ?: "--stdio"

    if (mode == "--help" || mode == "-h") {
        printUsage()
        return
    }

    // In stdio mode stdout carries the JSON-RPC stream. Some libraries print bootstrap
    // messages directly to stdout during initialization; capture the real stdout for the
    // transport and redirect System.out to stderr so nothing corrupts the protocol channel.
    val protocolOut: PrintStream = System.out
    if (mode == "--stdio") {
        System.setOut(System.err)
    }

    val config = try {
        Config.fromEnv()
    } catch (e: Exception) {
        System.err.println("[insightidr-mcp] Configuration error: ${e.message}")
        exitProcess(1)
    }

    val client = Rapid7Client(config)
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { client.close() } })

    when (mode) {
        "--stdio" -> runStdio(client, config, protocolOut)
        "--http" -> runHttp(client, config, args.getOrNull(1)?.toIntOrNull() ?: DEFAULT_HTTP_PORT)
        else -> {
            System.err.println("[insightidr-mcp] Unknown option: $mode")
            printUsage()
            exitProcess(1)
        }
    }
}

private const val DEFAULT_HTTP_PORT = 3001

private fun printUsage() {
    System.err.println(
        """
        Rapid7 InsightIDR MCP server ($SERVER_VERSION)

        Usage: <launcher> [--stdio | --http [port] | --help]
          --stdio        Run over stdio (default) — the standard MCP transport for local clients.
          --http [port]  Run an HTTP (Streamable HTTP/SSE) server on the given port (default $DEFAULT_HTTP_PORT).
          --help         Show this message.

        Environment:
          ${Config.ENV_API_KEY}   (required)  Insight platform API key.
          ${Config.ENV_REGION}     (optional)  Region code (default ${Config.DEFAULT_REGION}): us, us2, us3, eu, ca, au, ap.
          ${Config.ENV_BASE_URL}   (optional)  Override the full base URL (advanced/testing).
          ${Config.ENV_TIMEOUT_MS} (optional)  Per-request timeout in milliseconds (default ${Config.DEFAULT_TIMEOUT_MS}).
        """.trimIndent(),
    )
}

private fun runStdio(client: Rapid7Client, config: Config, protocolOut: PrintStream) = runBlocking {
    System.err.println("[insightidr-mcp] Starting over stdio — region=${config.region.code}, baseUrl=${config.baseUrl}")
    val server = buildInsightIdrServer(client)
    val transport = StdioServerTransport(
        input = System.`in`.asSource().buffered(),
        output = protocolOut.asSink().buffered(),
    )

    // The transport (not the server) fires onClose when stdin reaches EOF / the client
    // disconnects. Register it before starting the session so the process exits cleanly
    // instead of blocking on done.join() forever.
    val done = Job()
    transport.onClose { done.complete() }
    try {
        server.createSession(transport)
        done.join()
    } finally {
        client.close()
    }
}

private fun runHttp(client: Rapid7Client, config: Config, port: Int) {
    System.err.println("[insightidr-mcp] Starting over HTTP on port $port — region=${config.region.code}, baseUrl=${config.baseUrl}")
    val engine = embeddedServer(CIO, host = "0.0.0.0", port = port) {
        install(CORS) {
            anyHost()
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Delete)
            allowMethod(HttpMethod.Options)
            allowNonSimpleContentTypes = true
        }
        mcp { buildInsightIdrServer(client) }
    }
    try {
        engine.start(wait = true)
    } finally {
        client.close()
    }
}
