package com.jitunicornfx.insightidr.mcp

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.File
import java.io.PrintStream

private const val DEFAULT_HTTP_HOST = "127.0.0.1"
private const val DEFAULT_HTTP_PORT = 3001

/** Guards [autoInstall] so a release is downloaded and installed at most once per process. */
private val installAttempted = java.util.concurrent.atomic.AtomicBoolean(false)

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

    private val noAutoUpdate: Boolean by option("--no-auto-update")
        .flag(default = false)
        .help(
            "Never download or install a new release; only report that one is available. " +
                "Equivalent to ${Config.ENV_DISABLE_AUTO_UPDATE}=1.",
        )

    private val noUpdateCheck: Boolean by option("--no-update-check")
        .flag(default = false)
        .help(
            "Skip the startup check for a newer release entirely (implies --no-auto-update). " +
                "Equivalent to ${Config.ENV_DISABLE_UPDATE_CHECK}=1.",
        )

    override fun run() {
        val fromEnv = try {
            configProvider()
        } catch (e: Exception) {
            echo("Configuration error: ${e.message}", err = true)
            throw ProgramResult(1)
        }
        // A command-line flag can only ever tighten the environment's setting, never re-enable
        // something the environment switched off.
        val config = fromEnv.copy(
            updateCheckDisabled = fromEnv.updateCheckDisabled || noUpdateCheck,
            autoUpdateDisabled = fromEnv.autoUpdateDisabled || noAutoUpdate || noUpdateCheck,
        )
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

/**
 * Start the GitHub update check concurrently with startup, or return null when it is disabled.
 *
 * Kicked off as a [Deferred] so the server begins serving immediately: the result is only awaited
 * inside a session's `onInitialized` callback, by which point the check has almost always finished.
 */
private fun CoroutineScope.startUpdateCheck(config: Config): Deferred<UpdateChecker.Result>? =
    if (config.updateCheckDisabled) {
        System.err.println("[insightidr-mcp] Update check disabled via ${Config.ENV_DISABLE_UPDATE_CHECK}.")
        null
    } else {
        async(Dispatchers.IO) { UpdateChecker.check() }
    }

/** Attach the deferred update result to a session, notifying the client once it is initialized. */
private fun CoroutineScope.notifyWhenInitialized(
    server: io.modelcontextprotocol.kotlin.sdk.server.Server,
    session: io.modelcontextprotocol.kotlin.sdk.server.ServerSession,
    updateCheck: Deferred<UpdateChecker.Result>?,
    config: Config,
) {
    if (updateCheck == null) return
    session.onInitialized {
        launch {
            val result = runCatching { updateCheck.await() }.getOrNull() ?: return@launch
            if (result.updateAvailable) System.err.println("[insightidr-mcp] ${result.message()}")
            server.notifyUpdateAvailable(session, result)
            if (result.updateAvailable) {
                val outcome = autoInstall(config, result)
                if (outcome != null) server.notifyUpdateInstalled(session, outcome)
            }
        }
    }
}

/**
 * Download and install [result]'s release, unless automatic installation is switched off.
 *
 * Returns null when nothing was attempted. A [UpdateInstaller.Outcome.Staged] result registers a
 * shutdown hook that finishes the swap as the JVM exits — on Windows the running JAR cannot be
 * renamed or moved over, and rewriting its bytes is only safe once class loading has settled.
 */
private suspend fun autoInstall(config: Config, result: UpdateChecker.Result): UpdateInstaller.Outcome? {
    // At most one installation per process: in HTTP mode every connecting session runs this path,
    // and concurrent downloads would race over the same staging file and target JAR.
    if (!installAttempted.compareAndSet(false, true)) return null
    if (config.autoUpdateDisabled) {
        System.err.println(
            "[insightidr-mcp] Automatic installation is disabled " +
                "(${Config.ENV_DISABLE_AUTO_UPDATE} / --no-auto-update); update it manually.",
        )
        return null
    }
    val outcome = UpdateInstaller.install(result)
    when (outcome) {
        is UpdateInstaller.Outcome.Installed ->
            System.err.println("[insightidr-mcp] Installed ${outcome.version}; restart to run it.")

        is UpdateInstaller.Outcome.Staged -> {
            System.err.println("[insightidr-mcp] Staged ${outcome.version}; it will be applied as this server exits.")
            val staged = File(outcome.stagedPath)
            val target = UpdateInstaller.runningJar()
            if (target != null) {
                // Registering a hook throws once shutdown has already begun — which is reachable
                // here, since a stdio client can close stdin while the download is still running.
                runCatching {
                    Runtime.getRuntime().addShutdownHook(
                        Thread {
                            runCatching {
                                UpdateInstaller.installStagedAtShutdown(staged, target, outcome.sha256)
                            }
                        },
                    )
                }.onFailure {
                    System.err.println(
                        "[insightidr-mcp] Shutting down already; ${outcome.version} remains staged at " +
                            "${outcome.stagedPath} and will not be applied automatically.",
                    )
                }
            }
        }

        is UpdateInstaller.Outcome.Failed ->
            System.err.println("[insightidr-mcp] Automatic update did not complete: ${outcome.reason}")

        UpdateInstaller.Outcome.Skipped -> Unit
    }
    return outcome
}

private fun runStdio(client: Rapid7Client, config: Config, protocolOut: PrintStream) = runBlocking {
    System.err.println("[insightidr-mcp] Starting over stdio — region=${config.region.code}, baseUrl=${config.baseUrl}")
    val server = buildInsightIdrServer(client)
    // Run the check in its own supervised scope, never as a child of this runBlocking scope: a
    // failure there must not cancel the scope that is serving the MCP session.
    val checkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val updateCheck = checkScope.startUpdateCheck(config)
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
        val session = server.createSession(transport)
        checkScope.notifyWhenInitialized(server, session, updateCheck, config)
        done.join()
    } finally {
        updateCheck?.cancel()
        checkScope.cancel()
        client.close()
    }
}

private fun runHttp(client: Rapid7Client, config: Config, host: String, port: Int) {
    System.err.println("[insightidr-mcp] Starting over HTTP on $host:$port — region=${config.region.code}, baseUrl=${config.baseUrl}")
    // One check for the process, shared by every connecting session (mcp { } builds a server per
    // connection); a supervisor scope keeps a failed check from cancelling anything else.
    val checkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val updateCheck = checkScope.startUpdateCheck(config)
    val engine = embeddedServer(CIO, host = host, port = port) {
        install(CORS) {
            // The server holds a secret API key and has no auth of its own, so arbitrary cross-origin
            // browser access is NOT allowed (no anyHost()). Non-browser MCP clients send no Origin
            // header and are unaffected; a browser origin is permitted only if the operator explicitly
            // allow-lists it via INSIGHTIDR_HTTP_ALLOWED_ORIGINS.
            config.httpAllowedOrigins.forEach { origin ->
                val scheme = origin.substringBefore("://", missingDelimiterValue = "https")
                val hostAndPort = origin.substringAfter("://")
                if (hostAndPort.isNotBlank()) allowHost(hostAndPort, schemes = listOf(scheme))
            }
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Delete)
            allowMethod(HttpMethod.Options)
            allowNonSimpleContentTypes = true
        }
        mcp {
            buildInsightIdrServer(client).also { server ->
                if (updateCheck != null) {
                    server.attachUpdateNotifier(checkScope) { session ->
                        val result = runCatching { updateCheck.await() }.getOrNull()
                        if (result != null) {
                            server.notifyUpdateAvailable(session, result)
                            if (result.updateAvailable) {
                                val outcome = autoInstall(config, result)
                                if (outcome != null) server.notifyUpdateInstalled(session, outcome)
                            }
                        }
                    }
                }
            }
        }
    }
    try {
        engine.start(wait = true)
    } finally {
        updateCheck?.cancel()
        checkScope.cancel()
        client.close()
    }
}
