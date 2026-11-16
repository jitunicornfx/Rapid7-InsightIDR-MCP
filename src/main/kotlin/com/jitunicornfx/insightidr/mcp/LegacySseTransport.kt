package com.jitunicornfx.insightidr.mcp

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.MissingApplicationPluginException
import io.ktor.server.application.install
import io.ktor.server.application.plugin
import io.ktor.server.http.HttpRequestLifecycle
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.method
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sse.sse
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The legacy HTTP+SSE transport (GET [path] opens the event stream, POST [path]?sessionId=… carries
 * client messages), mounted so that it works for every client.
 *
 * The SDK's own `Route.mcp(path)` announces a *relative* endpoint (`?sessionId=…`). Browsers and the
 * TypeScript SDK resolve that against the stream URL (→ `/sse?sessionId=…`), but the Kotlin SDK client
 * resolves it against the stream URL's parent (→ `/?sessionId=…`), which 404s. Announcing the absolute
 * path removes the ambiguity. Otherwise this mirrors the SDK helper: one [Server] per connection, a
 * session map keyed by the transport's session id, entries removed when the server closes.
 *
 * The connection is held open by a handler that only waits, and on CIO a handler that only waits is not
 * told that its client has gone: up to 0.4.5 a disconnected client's session, a whole [Server] with
 * every tool registered, stayed until the server restarted - a failed write to the dead stream did not
 * end it either (measured 2026-11-16: 60 answers written to it, each failing, and the session still
 * took messages). As the SDK's own helper does since 0.16.0, the GET installs [HttpRequestLifecycle]
 * with `cancelCallOnClose`, so the engine cancels the call when the client disconnects; the session is
 * then removed and its [Server] closed. Only the GET: [path] may be the routing root, whose other
 * handlers must not be cancelled that way.
 *
 * Requires the Ktor `SSE` plugin to be installed (done by `mcpStreamableHttp`, which is therefore
 * called first).
 *
 * The SDK deprecated [SseServerTransport] in 0.16.0 in favour of Streamable HTTP. It still works, and
 * clients configured for `/sse` and `/` still depend on it, so it stays until those paths are retired.
 *
 * Ported from the InsightVM server. Unlike the SDK's `mcp {}` it applies no Host/Origin check of its
 * own — [installTransportSecurity] has already made those checks, ahead of routing, with knowledge of
 * the allow-list and the bind address that the SDK's localhost-only check does not have.
 */
@Suppress("DEPRECATION")
fun Route.legacySseTransport(
    path: String,
    maxRequestBodySize: Long = HttpSecurity.DEFAULT_MAX_BODY_BYTES,
    newServer: () -> Server,
) {
    val transports = ConcurrentHashMap<String, SseServerTransport>()

    route(path) {
        method(HttpMethod.Get) {
            installCancelCallOnClose()
            sse {
                val transport = SseServerTransport(path, this, maxRequestBodySize)
                transports[transport.sessionId] = transport
                val server = newServer()
                server.onClose { transports.remove(transport.sessionId) }
                try {
                    server.createSession(transport)
                    awaitCancellation()
                } finally {
                    transports.remove(transport.sessionId)
                    // Without its stream the session can no longer answer anything.
                    withContext(NonCancellable) { runCatching { server.close() } }
                }
            }
        }
    }

    post(path) {
        val sessionId = call.request.queryParameters["sessionId"]
        if (sessionId == null) {
            call.respond(HttpStatusCode.BadRequest, "sessionId query parameter is not provided")
            return@post
        }
        val transport = transports[sessionId]
        if (transport == null) {
            call.respond(HttpStatusCode.NotFound, "Session not found")
            return@post
        }
        transport.handlePostMessage(call)
    }
}

/**
 * Has the engine cancel a call once its client disconnects (CIO and Netty support this). One already
 * installed on this route, a parent route or the application is kept as it is. Mirrors the SDK's
 * private helper of the same name.
 */
private fun Route.installCancelCallOnClose() {
    try {
        plugin(HttpRequestLifecycle)
    } catch (_: MissingApplicationPluginException) {
        install(HttpRequestLifecycle) { cancelCallOnClose = true }
    }
}
