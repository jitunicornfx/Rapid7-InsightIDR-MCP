package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.tools.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

const val SERVER_NAME = "rapid7-insightidr-mcp"
const val SERVER_VERSION = "0.1.6"

/**
 * Build a fully-configured MCP [Server] with every InsightIDR tool registered.
 * The provided [client] is shared by all tool handlers.
 */
fun buildInsightIdrServer(client: Rapid7Client): Server {
    val server = Server(
        Implementation(name = SERVER_NAME, version = SERVER_VERSION),
        ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
                // Required to push notifications/message to the client (e.g. the update notice).
                logging = buildJsonObject { },
            ),
        ),
    )

    server.registerSystemTools(client)
    server.registerInvestigationV2Tools(client)
    server.registerAlertTools(client)
    server.registerAlertActionTools(client)
    server.registerAlertProcessTreeTools(client)
    server.registerEntityTools(client)
    server.registerCommentTools(client)
    server.registerAttachmentTools(client)
    server.registerCloudWebhookTools(client)
    server.registerCommunityThreatTools(client)
    server.registerCollectorTools(client)
    server.registerHealthMetricTools(client)

    // Log Search API (queries, saved queries, logs/log sets, usage, exports,
    // LEQL variables, pre-computed queries, detection rules, audit logs).
    server.registerLogSearchQueryTools(client)
    server.registerLogSearchManagementTools(client)
    server.registerLogSearchVariableTools(client)
    server.registerLogSearchDetectionRuleTools(client)
    server.registerLogSearchAuditTools(client)

    return server
}

/** Logger name carried on the update notification, so clients can attribute/filter it. */
const val UPDATE_LOGGER_NAME = "$SERVER_NAME.update"

/**
 * Arrange for every session of this server to receive the update notification once initialized.
 *
 * Used by the HTTP transport, where the SDK creates sessions internally and exposes no per-session
 * callback: [Server.onConnect] fires as connections arrive, so each newly-seen session in
 * [Server.sessions] gets its [ServerSession.onInitialized] hook registered exactly once. [notify]
 * performs the actual delivery (injected so tests can observe it without a live client).
 */
fun Server.attachUpdateNotifier(
    scope: kotlinx.coroutines.CoroutineScope,
    notify: suspend (ServerSession) -> Unit,
) {
    val handled = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    onConnect {
        sessions.values.forEach { session ->
            if (handled.add(session.sessionId)) {
                session.onInitialized { scope.launch { runCatching { notify(session) } } }
            }
        }
    }
}

/**
 * Send [result] to [session] as an MCP `notifications/message` once the client has finished
 * initializing, when — and only when — an update is available.
 *
 * The notification is deferred to [ServerSession.onInitialized] because the protocol forbids
 * server-initiated messages before the client's `initialized` handshake. Delivery is best-effort:
 * a client that ignores or rejects logging notifications must not disturb the session, so failures
 * are swallowed (and surfaced on stderr, never stdout, which carries the stdio JSON-RPC stream).
 */
suspend fun Server.notifyUpdateAvailable(session: ServerSession, result: UpdateChecker.Result) {
    if (!result.updateAvailable) return
    runCatching {
        sendLoggingMessage(
            session.sessionId,
            LoggingMessageNotification(
                LoggingMessageNotificationParams(
                    level = LoggingLevel.Info,
                    logger = UPDATE_LOGGER_NAME,
                    data = buildJsonObject {
                        put("message", JsonPrimitive(result.message()))
                        put("current_version", JsonPrimitive(result.currentVersion))
                        result.latestVersion?.let { put("latest_version", JsonPrimitive(it)) }
                        put("release_url", JsonPrimitive(result.releaseUrl))
                    },
                ),
            ),
        )
    }.onFailure { System.err.println("[insightidr-mcp] Update notification not delivered: ${it.message}") }
}
