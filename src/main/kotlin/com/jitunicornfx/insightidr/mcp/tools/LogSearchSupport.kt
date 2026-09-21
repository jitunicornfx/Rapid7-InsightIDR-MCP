package com.jitunicornfx.insightidr.mcp.tools

import com.jitunicornfx.insightidr.mcp.*
import com.jitunicornfx.insightidr.mcp.Rapid7Client.ApiBase
import com.jitunicornfx.insightidr.mcp.Rapid7Client.ApiResponse
import io.ktor.http.HttpMethod
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.format.DateTimeParseException

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

/** The maximum number of log entries per page, per the spec's `per_page` parameter. */
internal const val LS_MAX_PER_PAGE = 500

/**
 * The default page size for tools that return events to the caller.
 *
 * Deliberately well below [LS_MAX_PER_PAGE]: a full 500-event page runs to hundreds of kilobytes,
 * so it would breach the response budget and be structurally trimmed on nearly every query — the
 * API would return events that were fetched, paid for in latency, and then dropped. ~100 events fit
 * inside the default budget with headroom. Callers who want a bigger page can still ask for one, and
 * `logsearch_spool_query_to_file` uses the maximum because its events never enter the conversation.
 */
internal const val LS_DEFAULT_PER_PAGE = 100

/**
 * The one-page / aggregate / spool decision rule, worded identically on every tool that returns log
 * events, so the model sees a single policy rather than seven variations of it.
 */
internal const val LS_RESULT_SIZE_GUIDANCE =
    "Returns ONE page — use it to look at a sample. To COUNT or AGGREGATE, put calculate(count) or " +
        "groupby(field) in the LEQL: the API aggregates server-side and answers in a few hundred bytes " +
        "instead of megabytes. To read EVERY matching event, use logsearch_spool_query_to_file, which " +
        "follows all pages on the server and returns only a summary. Large results are truncated to this " +
        "server's response budget, with a notice saying what was dropped."

/** The download endpoint's `limit`: spec 3.0.2 gives 500,000,000 as both its maximum and its default. */
internal const val LS_MAX_DOWNLOAD_ENTRIES = 500_000_000L

/** The only export format the API supports (spec 3.0.2: "Currently only `csv` is supported"). */
internal val LS_EXPORT_FORMATS = listOf("csv")

/** What `export_format` does, per the spec. [pollTool] is the tool that reads the resulting job. */
internal fun exportFormatDescription(pollTool: String): String =
    "If set, the results are EXPORTED instead of returned: the API answers 202 with a link to an export " +
        "job, which you read with $pollTool. Non-statistical queries only (no calculate/groupby). An " +
        "export holds at most the first 1,000,000 entries, and only one export job may run per account " +
        "at a time."

/**
 * [LS_RESULT_SIZE_GUIDANCE] for the AUDIT query tools, which need their own wording: the spool tool
 * posts to `/query/logs` and cannot reach `/audit/query/logs`, so sending a model there for "every
 * event" would send it to a tool that cannot do the job.
 *
 * Spec 3.0.2's prose says the ordinary `/query` endpoints "can be used to search for both audit logs and
 * regular logs". The live API disagrees: checked 2026-09-21 with read-only count queries, it REJECTS an
 * audit log key on `/query/logs`. The observation wins, as it does for error 101009 below.
 */
internal const val LS_AUDIT_RESULT_SIZE_GUIDANCE =
    "Returns ONE page — use it to look at a sample. To COUNT or AGGREGATE, put calculate(count) or " +
        "groupby(field) in the LEQL: the API aggregates server-side and answers in a few hundred bytes. To " +
        "read EVERY matching entry, set export_format=csv and read the job with " +
        "logsearch_audit_get_export_job (logsearch_spool_query_to_file does not cover audit logs). Large " +
        "results are truncated to this server's response budget, with a notice saying what was dropped."

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
} catch (_: StackOverflowError) {
    // Deeply-nested JSON can overflow the recursive parser; treat it as "no link" rather than
    // letting an Error escape and tear down the session.
    null
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

/**
 * Whether [this] response is the Log Search API's rejection of pagination on a statistic
 * (`calculate`/`groupby`) query — error code `101009`, "Pagination is not supported with statistic
 * queries".
 *
 * **This is observed behaviour, not documented behaviour.** Neither the code `101009` nor that
 * message appears anywhere in Log Search spec 3.0.2; both were seen from the live API. The nearest
 * the spec comes is on other parameters: `label`/`labels` "only works with non-statistical queries",
 * and `export_format` is "only for non-statistical search queries". If this stops matching, look at
 * what the API actually returns before looking at the spec.
 *
 * Matched on the raw [ApiResponse.body] (this runs before [toToolResult] pretty-prints and wraps it),
 * on either the numeric code or the message text, and never on a 2xx — a successful result body that
 * happens to contain the string `101009` (e.g. a log line) must not trigger a retry.
 */
internal fun ApiResponse.isStatisticPaginationRejection(): Boolean {
    if (status in 200..299) return false
    if (body.contains("pagination is not supported", ignoreCase = true)) return true
    // The CODE, not the digits. A raw substring match also fires on a 'from' of 1710100900000, a
    // log key or a request id echoed back in some unrelated error, and the request is then retried
    // with its pagination stripped for no reason.
    val code = try {
        (JsonCodec.compact.parseToJsonElement(body) as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull
    } catch (_: StackOverflowError) {
        null
    } catch (_: Exception) {
        null
    }
    return code == STATISTIC_PAGINATION_CODE
}

private const val STATISTIC_PAGINATION_CODE = "101009"

/** Drop only the pagination query keys, leaving the LEQL body, time window, labels, etc. untouched. */
internal fun Map<String, List<String>>.withoutPagination(): Map<String, List<String>> =
    filterKeys { it != "per_page" && it != "sequence_number" }

/**
 * What to tell the model when a statistic query is rejected a second time, with `label`/`labels`
 * still on it.
 *
 * It says what the spec says and stops there. That the labels CAUSED the rejection is a plausible
 * guess, not something anyone has observed, so the note does not claim it.
 */
private const val HTTP_BAD_REQUEST = 400

internal const val STATISTIC_LABELS_NOTE =
    "This is a statistic (calculate/groupby) query. It was retried without per_page and " +
        "sequence_number, which statistic queries do not accept, and was rejected again. It was sent " +
        "with 'label'/'labels', which the Log Search spec says \"only works with non-statistical " +
        "queries\". That filter was NOT removed automatically: dropping it would change which events " +
        "are counted, and return a different answer without saying so. If the count does not need " +
        "the label filter, run the query again without it."

/**
 * Submit a Log Search query and poll it to completion, transparently handling statistic queries.
 *
 * A statistic query (`calculate`/`groupby`) cannot be paginated, but the server sends `per_page` on
 * every query, so the first submit is rejected with [isStatisticPaginationRejection]. That rejection
 * is a request-validation error — no query was created — so the request is safely resubmitted once
 * with the pagination parameters stripped ([withoutPagination]). Event (non-statistic) queries never
 * hit the retry: their 2xx submit flows straight to [awaitQueryCompletion] exactly as before, so
 * pagination for them is unchanged. All Log Search submit tools route through here (base is always
 * [ApiBase.LOG_SEARCH]).
 *
 * Pagination parameters are the ONLY thing the retry removes. They shape how a result is delivered;
 * taking them off cannot change the answer. `label`/`labels` select which events are counted, so
 * they are left alone even though the spec restricts them to non-statistical queries — if the retry
 * still fails with them present, the model is told ([STATISTIC_LABELS_NOTE]) and decides.
 */
internal suspend fun Rapid7Client.submitLogSearchQuery(
    method: HttpMethod,
    path: String,
    query: Map<String, List<String>> = emptyMap(),
    jsonBody: JsonElement? = null,
    wait: Boolean,
    timeout: Long,
): ApiResponse {
    var response = request(method, path, query = query, jsonBody = jsonBody, base = ApiBase.LOG_SEARCH)
    if (response.isStatisticPaginationRejection()) {
        response = request(method, path, query = query.withoutPagination(), jsonBody = jsonBody, base = ApiBase.LOG_SEARCH)
        // Only a 400: the API saying this request is still not valid. A 429 or a 5xx on the retry is
        // the API being busy, and "rejected again, try without the label filter" beside it would send
        // the model off to count a different set of events when retrying was the right answer.
        if (response.status == HTTP_BAD_REQUEST && ("label" in query || "labels" in query)) {
            return response.copy(serverNote = STATISTIC_LABELS_NOTE)
        }
    }
    return awaitQueryCompletion(response, wait, timeout)
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
    integerParam("from", "Start of the time range as a UNIX timestamp in milliseconds. Use with 'to'; mutually exclusive with 'time_range'.", min = 0)
    integerParam("to", "End of the time range as a UNIX timestamp in milliseconds. Use with 'from'; mutually exclusive with 'time_range'.", min = 0)
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
    integerParam("per_page", "Number of log entries per page, up to $LS_MAX_PER_PAGE. Defaults to $LS_DEFAULT_PER_PAGE, which fits the response budget; larger pages are likely to be truncated. Ignored for statistic (calculate/groupby) queries, which cannot be paginated.", min = 1, max = LS_MAX_PER_PAGE.toLong(), default = LS_DEFAULT_PER_PAGE.toLong())
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
        min = 0,
        max = LS_MAX_POLL_TIMEOUT_MS,
        default = LS_DEFAULT_POLL_TIMEOUT_MS,
    )
}

/** Standard from/to/time_range query-parameter map from tool args. */
internal fun timeWindowQuery(args: JsonObject): Map<String, List<String>> = query(
    "from" to args.longOrNull("from"),
    "to" to args.longOrNull("to"),
    "time_range" to args.stringOrNull("time_range"),
)

/**
 * The `per_page` to send: the caller's value, or [default], held to what the API accepts.
 *
 * Every tool goes through this. Only the spool tool used to clamp; the rest passed `per_page=5000`
 * (or `0`, or `-1`) straight to the API, which answers with a 400 the model then has to decode.
 */
internal fun perPage(args: JsonObject, default: Int = LS_DEFAULT_PER_PAGE): Int =
    (args.intOrNull("per_page") ?: default).coerceIn(1, LS_MAX_PER_PAGE)

/**
 * Enforce the usage endpoints' date window, which is NOT the query endpoints' window: the dates are
 * `YYYY-MM-DD` strings rather than epoch milliseconds, so [requireTimeWindow] would reject every
 * valid call.
 *
 * [allowTimeRange] is true only for the per-log endpoint, the one the spec gives a `time_range`
 * alternative, and says of it: "If `time_range` is used, then the `from` and `to` query parameters
 * must not be used."
 */
internal fun requireUsageWindow(args: JsonObject, allowTimeRange: Boolean) {
    val from = args.stringOrNull("from")
    val to = args.stringOrNull("to")
    if (allowTimeRange && args.stringOrNull("time_range") != null) {
        require(from == null && to == null) { "'time_range' cannot be combined with 'from'/'to'. Use one or the other." }
        return
    }
    require(from != null && to != null) {
        if (allowTimeRange) {
            "A date range is required: provide 'time_range' (e.g. 'yesterday' or 'last 7 days'), or both " +
                "'from' and 'to' formatted YYYY-MM-DD."
        } else {
            "Both 'from' and 'to' are required, formatted YYYY-MM-DD."
        }
    }
    require(!usageDate("from", from).isAfter(usageDate("to", to))) { "'from' must not be later than 'to'." }
}

private fun usageDate(name: String, value: String): LocalDate = try {
    LocalDate.parse(value)
} catch (e: DateTimeParseException) {
    throw IllegalArgumentException("'$name' must be a real date formatted YYYY-MM-DD, for example 2026-01-31.")
}

/** Standard result-shaping query-parameter map from tool args; see [LS_DEFAULT_PER_PAGE]. */
internal fun queryResultQuery(args: JsonObject): Map<String, List<String>> = query(
    "per_page" to perPage(args),
    "most_recent_first" to args.booleanOrNull("most_recent_first"),
    "kvp_info" to args.booleanOrNull("kvp_info"),
    "sequence_number" to args.longOrNull("sequence_number"),
)

/** The `during` window built from `from`/`to`/`time_range`, or null when the caller gave none of them. */
private fun duringOrNull(args: JsonObject): JsonObject? {
    val from = args.longOrNull("from")
    val to = args.longOrNull("to")
    val timeRange = args.stringOrNull("time_range")
    if (from == null && to == null && timeRange == null) return null
    return buildJsonObject {
        putOpt("from", from)
        putOpt("to", to)
        putOpt("time_range", timeRange)
    }
}

/** Build the `leql` object (`statement` + optional `during` window) used by query/saved-query bodies. */
internal fun leqlObject(statement: String, args: JsonObject): JsonObject = buildJsonObject {
    put("statement", statement)
    duringOrNull(args)?.let { put("during", it) }
}

/**
 * Like [leqlObject], but for PATCH bodies where both the statement and the window are optional:
 * returns null when neither is supplied, and supports a `during`-only update without a statement.
 */
internal fun leqlObjectForPatch(statement: String?, args: JsonObject): JsonObject? {
    val during = duringOrNull(args)
    if (statement == null && during == null) return null
    return buildJsonObject {
        putOpt("statement", statement)
        during?.let { put("during", it) }
    }
}
