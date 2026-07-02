package com.jitunicornfx.insightidr.mcp

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.switch
import com.github.ajalt.clikt.parameters.types.int
import io.ktor.http.*
import io.ktor.server.application.install
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.cors.routing.*
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.PrintStream

private const val DEFAULT_HTTP_HOST = "127.0.0.1"
private const val DEFAULT_HTTP_PORT = 3001

internal enum class Transport { STDIO, HTTP }

/**
 * Command-line entry point for the Rapid7 InsightIDR MCP server (built with Clikt).
 *
 * Transports:
 *  - `--stdio` (default): standard MCP stdio transport for local desktop clients.
 *  - `--http`: Streamable HTTP / SSE transport, bound to `--host`/`--port`.
 *
 * All diagnostics are written to stderr so stdout stays reserved for the JSON-RPC channel.
 *
 * The [configProvider] and [serve] seams exist so tests can drive option parsing and the
 * run() dispatch without reading the real environment or starting a (blocking) server.
 */
class Rapid7InsightIdrCommand internal constructor(
    private val configProvider: () -> Config,
    private val serve: (transport: Transport, host: String, port: Int, config: Config) -> Unit,
) : CliktCommand() {

    /** Production entry point: reads config from the environment and starts a real server. */
    constructor() : this({ Config.fromEnv() }, ::runServer)

    override fun help(context: Context): String =
        "Run the Rapid7 InsightIDR MCP server over stdio (default) or HTTP."

    // Blank lines separate paragraphs so each environment variable renders on its own line.
    override fun helpEpilog(context: Context): String =
        """
        |Environment variables:
        |
        |${Config.ENV_API_KEY} (required): Insight platform API key.
        |
        |${Config.ENV_REGION} (optional): region code, default ${Config.DEFAULT_REGION} (us, us2, us3, eu, ca, au, ap).
        |
        |${Config.ENV_BASE_URL} (optional): override the full base URL (advanced/testing).
        |
        |${Config.ENV_TIMEOUT_MS} (optional): per-request timeout in ms, default ${Config.DEFAULT_TIMEOUT_MS}.
        """.trimMargin()

    private val transport: Transport by option()
        .switch("--stdio" to Transport.STDIO, "--http" to Transport.HTTP)
        .default(Transport.STDIO)
        .help("Transport to run. Defaults to --stdio.")

    private val host: String by option("--host", "--ip")
        .default(DEFAULT_HTTP_HOST)
        .help("IP address to listen on in HTTP mode. Defaults to $DEFAULT_HTTP_HOST.")

    private val port: Int by option("--port")
        .int()
        .default(DEFAULT_HTTP_PORT)
        .help("Port to listen on in HTTP mode. Defaults to $DEFAULT_HTTP_PORT.")

    override fun run() {
        val config = try {
            configProvider()
        } catch (e: Exception) {
            echo("Configuration error: ${e.message}", err = true)
            throw ProgramResult(1)
        }
        serve(transport, host, port, config)
    }
}

fun main(args: Array<String>) = Rapid7InsightIdrCommand().main(args)

/** Creates the shared client and dispatches to the selected transport. */
private fun runServer(transport: Transport, host: String, port: Int, config: Config) {
    val client = Rapid7Client(config)
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { client.close() } })

    when (transport) {
        Transport.STDIO -> {
            // In stdio mode stdout carries the JSON-RPC stream. Capture the real stdout for the
            // transport and redirect System.out to stderr so stray writes can't corrupt the channel.
            val protocolOut: PrintStream = System.out
            System.setOut(System.err)
            runStdio(client, config, protocolOut)
        }

        Transport.HTTP -> runHttp(client, config, host, port)
    }
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

private fun runHttp(client: Rapid7Client, config: Config, host: String, port: Int) {
    System.err.println("[insightidr-mcp] Starting over HTTP on $host:$port — region=${config.region.code}, baseUrl=${config.baseUrl}")
    val engine = embeddedServer(CIO, host = host, port = port) {
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
