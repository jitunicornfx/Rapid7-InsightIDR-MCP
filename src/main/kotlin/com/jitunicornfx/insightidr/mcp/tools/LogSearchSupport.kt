package com.jitunicornfx.insightidr.mcp.tools

import com.jitunicornfx.insightidr.mcp.*
import com.jitunicornfx.insightidr.mcp.Rapid7Client.ApiResponse
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Shared helpers for the Log Search API tools.
 *
 * Log Search queries are asynchronous: the API may reply `202 Accepted` with a JSON body that
 * carries a continuation URL in `links[0].href`. Clients poll that URL until they receive a
 * `200` with the final result. The helpers here implement that loop so individual tools can
 * simply opt in via a `wait_for_completion` argument.
 */

internal const val LS_POLL_INTERVAL_MS = 500L
internal const val LS_DEFAULT_POLL_TIMEOUT_MS = 25_000L
internal const val LS_MAX_POLL_TIMEOUT_MS = 120_000L

/** The maximum (and our default) number of log entries per page, per the spec's `per_page` parameter. */
internal const val LS_MAX_PER_PAGE = 500

/**
 * Extract the in-progress continuation URL (the `rel="Self"` link) from a Log Search response
 * body. Per the spec, a query is still running exactly while its body carries a Self link —
 * the poll endpoint returns HTTP 200 for both ongoing and finished queries, so the link (not
 * the status code) is the completion signal. A finished, paginated result may carry only a
 * `rel="Next"` link, which must NOT be followed here.
 */
internal fun continuationLink(body: String): String? = linkWithRel(body, "Self")

/**
 * Extract the next-page URL (the `rel="Next"` link) from a completed, paginated Log Search
 * result. Present when more pages of events are available.
 */
internal fun nextPageLink(body: String): String? = linkWithRel(body, "Next")

private fun linkWithRel(body: String, rel: String): String? = try {
    JsonCodec.compact.parseToJsonElement(body).jsonObject["links"]
        ?.jsonArray
        ?.firstOrNull { it.jsonObject["rel"]?.jsonPrimitive?.contentOrNull.equals(rel, ignoreCase = true) }
        ?.jsonObject?.get("href")?.jsonPrimitive?.contentOrNull
} catch (_: Exception) {
    null
}

/**
 * Follow query continuations until the query completes, the poll budget is exhausted, or no
 * `Self` continuation link is present. On timeout the last (in-progress) response is returned;
 * its body still contains the query `id`/`links` so the caller can resume via the poll tool.
 */
internal suspend fun Rapid7Client.awaitQueryCompletion(
    initial: ApiResponse,
    waitForCompletion: Boolean,
    maxWaitMillis: Long,
): ApiResponse {
    if (!waitForCompletion) return initial
    var current = initial
    var waited = 0L
    while ((current.status == 202 || current.status == 200) && waited < maxWaitMillis) {
        val next = continuationLink(current.body) ?: return current
        delay(LS_POLL_INTERVAL_MS)
        waited += LS_POLL_INTERVAL_MS
        current = requestAbsolute(next)
    }
    return current
}

/** Read the polling controls shared by all query tools. */
internal fun JsonObject.pollArgs(): Pair<Boolean, Long> {
    val wait = booleanOrNull("wait_for_completion") ?: true
    val timeout = (longOrNull("poll_timeout_ms") ?: LS_DEFAULT_POLL_TIMEOUT_MS)
        .coerceIn(0, LS_MAX_POLL_TIMEOUT_MS)
    return wait to timeout
}

// ---------------------------------------------------------------------------
// Shared input-schema fragments
// ---------------------------------------------------------------------------

/** `from`/`to` (epoch millis) and `time_range` (relative) window parameters. */
internal fun JsonObjectBuilder.timeWindowParams() {
    integerParam("from", "Start of the time range as a UNIX timestamp in milliseconds. Use with 'to'; mutually exclusive with 'time_range'.")
    integerParam("to", "End of the time range as a UNIX timestamp in milliseconds. Use with 'from'; mutually exclusive with 'time_range'.")
    stringParam(
        "time_range",
        "Relative time range instead of from/to, e.g. 'today', 'yesterday', or 'last x mins/hours/days/weeks/months/years'.",
    )
}

/**
 * Enforce the API's time-window contract for query executions: either `time_range` or both
 * `from` and `to` must be supplied. Fails fast with a clear message instead of an API 400.
 */
internal fun requireTimeWindow(args: JsonObject) {
    val hasRange = args.stringOrNull("time_range") != null
    val hasFromTo = args.longOrNull("from") != null && args.longOrNull("to") != null
    require(hasRange || hasFromTo) {
        "A time window is required: provide 'time_range' (e.g. 'last 1 hour') or both 'from' and 'to'."
    }
}

/** Pagination / result-shaping parameters shared by the query endpoints. */
internal fun JsonObjectBuilder.queryResultParams() {
    integerParam("per_page", "Number of log entries per page, up to $LS_MAX_PER_PAGE. Defaults to the maximum ($LS_MAX_PER_PAGE).")
    booleanParam("most_recent_first", "When true, return the most recent events first. Defaults to false.")
    booleanParam("kvp_info", "When true, include parsed key-value-pair info for each returned log entry.")
    integerParam("sequence_number", "Include entries in the 'from' millisecond with sequence numbers at/after this value.")
}

/** Polling controls shared by all asynchronous query tools. */
internal fun JsonObjectBuilder.pollingParams() {
    booleanParam(
        "wait_for_completion",
        "When true (default), automatically poll 202 continuations until the query finishes or the poll budget runs out.",
    )
    integerParam(
        "poll_timeout_ms",
        "Maximum time to spend polling for completion, in ms (default $LS_DEFAULT_POLL_TIMEOUT_MS, max $LS_MAX_POLL_TIMEOUT_MS).",
    )
}

/** Standard from/to/time_range query-parameter map from tool args. */
internal fun timeWindowQuery(args: JsonObject): Map<String, List<String>> = query(
    "from" to args.longOrNull("from"),
    "to" to args.longOrNull("to"),
    "time_range" to args.stringOrNull("time_range"),
)

/** Standard result-shaping query-parameter map from tool args; `per_page` defaults to the maximum. */
internal fun queryResultQuery(args: JsonObject): Map<String, List<String>> = query(
    "per_page" to (args.intOrNull("per_page") ?: LS_MAX_PER_PAGE),
    "most_recent_first" to args.booleanOrNull("most_recent_first"),
    "kvp_info" to args.booleanOrNull("kvp_info"),
    "sequence_number" to args.longOrNull("sequence_number"),
)

/** Build the `leql` object (`statement` + optional `during` window) used by query/saved-query bodies. */
internal fun leqlObject(statement: String, args: JsonObject): JsonObject = buildJsonObject {
    put("statement", statement)
    val from = args.longOrNull("from")
    val to = args.longOrNull("to")
    val timeRange = args.stringOrNull("time_range")
    if (from != null || to != null || timeRange != null) {
        put(
            "during",
            buildJsonObject {
                putOpt("from", from)
                putOpt("to", to)
                putOpt("time_range", timeRange)
            },
        )
    }
}

/**
 * Like [leqlObject], but for PATCH bodies where both the statement and the window are optional:
 * returns null when neither is supplied, and supports a `during`-only update without a statement.
 */
internal fun leqlObjectForPatch(statement: String?, args: JsonObject): JsonObject? {
    val from = args.longOrNull("from")
    val to = args.longOrNull("to")
    val timeRange = args.stringOrNull("time_range")
    if (statement == null && from == null && to == null && timeRange == null) return null
    return buildJsonObject {
        putOpt("statement", statement)
        if (from != null || to != null || timeRange != null) {
            put(
                "during",
                buildJsonObject {
                    putOpt("from", from)
                    putOpt("to", to)
                    putOpt("time_range", timeRange)
                },
            )
        }
    }
}
