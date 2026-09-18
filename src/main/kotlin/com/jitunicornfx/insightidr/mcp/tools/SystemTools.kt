package com.jitunicornfx.insightidr.mcp.tools

import com.jitunicornfx.insightidr.mcp.BuildInfo
import com.jitunicornfx.insightidr.mcp.JsonCodec
import com.jitunicornfx.insightidr.mcp.Rapid7Client
import com.jitunicornfx.insightidr.mcp.SERVER_NAME
import com.jitunicornfx.insightidr.mcp.SERVER_VERSION
import com.jitunicornfx.insightidr.mcp.ServerFacts
import com.jitunicornfx.insightidr.mcp.UpdateChecker
import com.jitunicornfx.insightidr.mcp.UpdateStatus
import com.jitunicornfx.insightidr.mcp.apiTool
import com.jitunicornfx.insightidr.mcp.putOpt
import com.jitunicornfx.insightidr.mcp.textResult
import com.jitunicornfx.insightidr.mcp.toToolResult
import com.jitunicornfx.insightidr.mcp.updateSummary
import io.ktor.http.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Tool name as a constant, so tests and the README refer to a single literal. */
const val SERVER_INFO_TOOL = "insightidr_server_info"

/** Registers connectivity / diagnostics tools for InsightIDR connectivity. */
fun Server.registerSystemTools(
    client: Rapid7Client,
    // Safe to capture at registration time: the facts are immutable per process and Main installs
    // them before any Server exists. UpdateStatus and the tool count are NOT — see the handler.
    facts: ServerFacts = ServerFacts.active,
) {

    apiTool(
        name = "validate_connection",
        description = "Validate connectivity and authentication to the Insight platform. Calls the platform " +
                "/validate endpoint and returns the organization associated with the configured API key. " +
                "Use this first to confirm the API key and region are correct.",
        readOnly = true,
    ) { _ ->
        client.request(HttpMethod.Get, "/validate").toToolResult()
    }

    apiTool(
        name = SERVER_INFO_TOOL,
        description = "Describe this MCP server: the version and build it is running, whether a newer " +
                "release is available or already downloaded and awaiting a restart, the configured " +
                "InsightIDR region and API endpoints, the result-size budget and spool settings, and how " +
                "many tools are registered. Answers from local state — it makes no API or network call.",
        readOnly = true,
    ) {
        // Both of these MUST be read here rather than captured above. The startup update check
        // finishes after the server is built, and registerSystemTools runs FIRST in
        // buildInsightIdrServer — so at registration time the check is always PENDING and only one
        // tool exists.
        textResult(pretty(serverInfo(facts, UpdateStatus.active, tools.size)))
    }
}

private fun pretty(element: JsonElement): String =
    JsonCodec.pretty.encodeToString(JsonElement.serializer(), element)

/**
 * The `insightidr_server_info` payload.
 *
 * Pure — every input is a parameter — so all the update states are exercised as a plain unit test
 * with no MCP round trip and no global state.
 *
 * SECURITY INVARIANT: every string here is server-authored or an allow-listed version token, which
 * is why the result is returned with `textResult` and no untrusted-data envelope. `latestVersion`
 * and `installedVersion` have passed [UpdateChecker]'s strict `VERSION_TAG` allow-list, and
 * `failureReason` is fixed server text from [UpdateInstaller]. If a free-form remote string is ever
 * added here — a release title, a GitHub error body — it must be wrapped with `wrapUntrusted()`.
 *
 * No absolute host path appears in the payload: not the JAR path, not the spool directory. Only
 * booleans say whether they are set.
 */
internal fun serverInfo(
    facts: ServerFacts,
    update: UpdateStatus.Snapshot,
    toolCount: Int,
): JsonElement = buildJsonObject {
    put("server", SERVER_NAME)
    put("version", SERVER_VERSION)

    putJsonObject("build") {
        put("versionSource", if (BuildInfo.fromGeneratedResource) "generated" else "fallback")
        putOpt("gitSha", BuildInfo.gitSha)
        put("gitDirty", BuildInfo.gitDirty)
        putOpt("gitCommitTime", BuildInfo.gitCommitTime)
        putOpt("generatedAt", BuildInfo.generatedAt)
    }

    putJsonObject("update") {
        put("checkState", update.checkState.name.lowercase())
        put("updateAvailable", update.result?.updateAvailable ?: false)
        putOpt("latestVersion", update.result?.latestVersion)
        put("releaseUrl", update.result?.releaseUrl ?: UpdateChecker.RELEASES_PAGE_URL)
        put("installState", update.installState.name.toCamelCase())
        putOpt("installedVersion", update.installedVersion)
        put("restartRequired", update.restartRequired)
        if (update.installState == UpdateStatus.InstallState.STAGED) {
            put("appliesOnExit", update.appliesOnExit)
        }
        putOpt("failureReason", update.failureReason)
        put("runningFromJar", update.runningFromJar)
        put("summary", updateSummary(update))
    }

    putJsonObject("runtime") {
        put("region", facts.region)
        putOpt("baseUrl", facts.baseUrl.takeIf { it.isNotBlank() })
        putOpt("v1BaseUrl", facts.v1BaseUrl.takeIf { it.isNotBlank() })
        putOpt("logSearchBaseUrl", facts.logSearchBaseUrl.takeIf { it.isNotBlank() })
        put("requestTimeoutMillis", facts.requestTimeoutMillis)
        put("maxResultChars", facts.maxResultChars)
        put("spoolDirectoryConfigured", facts.spoolDirectoryConfigured)
        put("spoolRetentionHours", facts.spoolRetentionHours)
        put("uploadDirectoryConfigured", facts.uploadDirectoryConfigured)
        put("httpAllowedOriginCount", facts.httpAllowedOriginCount)
        put("updateCheckDisabled", facts.updateCheckDisabled)
        put("autoUpdateDisabled", facts.autoUpdateDisabled)
    }

    // Derived from the live registry, never hardcoded: a literal count silently goes wrong the
    // moment a tool is added.
    put("toolCount", toolCount)
}

/** `NOT_ATTEMPTED` -> `notAttempted`; single-word names are simply lower-cased. */
private fun String.toCamelCase(): String = lowercase()
    .split('_')
    .mapIndexed { index, part -> if (index == 0) part else part.replaceFirstChar { it.uppercase() } }
    .joinToString("")
