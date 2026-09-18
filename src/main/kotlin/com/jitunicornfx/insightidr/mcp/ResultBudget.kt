package com.jitunicornfx.insightidr.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * How many characters of InsightIDR API data a single tool result may carry.
 *
 * A Log Search page routinely exceeds a megabyte, and pretty-printing inflates it a further
 * 30-100%. Handing that to the model is expensive and rarely useful. Every API response funnels
 * through [ApiResponse.toToolResult], so this one type bounds all of them.
 *
 * Immutable. The process-wide value is installed once at startup from [Config.maxResultChars];
 * tests pass an explicit instance, so the ladder is testable without touching global state.
 */
data class ResultBudget(
    /** Ceiling on rendered API data, in characters. ~4 chars per token, so 200_000 is ~50k tokens. */
    val maxChars: Int = DEFAULT_MAX_CHARS,
    /**
     * Error bodies are never trimmed below this. A non-2xx body is the diagnostic the model needs
     * to correct itself; a small [maxChars] must not be able to erase it.
     */
    val errorFloorChars: Int = DEFAULT_ERROR_FLOOR_CHARS,
    /** A cut always emits at least this much, so the response's shape is still visible. */
    val sampleFloorChars: Int = DEFAULT_SAMPLE_FLOOR_CHARS,
    /**
     * Bodies larger than this are never parsed into a JSON tree — the tree costs several times the
     * string it came from. Above it the ladder degrades straight to a line-aware cut. The spool
     * tool, not a bigger heap, is the answer for results this size.
     */
    val parseCeilingChars: Int = DEFAULT_PARSE_CEILING_CHARS,
) {
    /** The limit that actually applies to a response with this [ok] flag. */
    internal fun limitFor(ok: Boolean): Int =
        if (ok) maxChars else maxOf(maxChars, errorFloorChars)

    companion object {
        const val DEFAULT_MAX_CHARS = 200_000
        const val DEFAULT_ERROR_FLOOR_CHARS = 4_000
        const val DEFAULT_SAMPLE_FLOOR_CHARS = 1_000
        const val DEFAULT_PARSE_CEILING_CHARS = 16_000_000

        /** Key the in-band truncation marker is written under. */
        const val TRUNCATION_KEY = "_mcp_truncated"

        /**
         * Arrays that are never candidates for structural trimming. Dropping `links` would delete
         * the `rel="Next"` href and silently destroy the caller's ability to paginate.
         */
        internal val NEVER_TRIM_KEYS = setOf("links")

        @Volatile
        private var installed: ResultBudget = ResultBudget()

        /** The process-wide budget. One [Config] per process, so one budget per process. */
        val active: ResultBudget get() = installed

        /** Install the configured budget. Called once from Main before anything is served. */
        fun install(budget: ResultBudget) {
            installed = budget
        }
    }
}

/** Headroom for the truncation marker's final digits and the encoder's separators. */
private const val SAFETY_MARGIN = 512

/** Which rung of the ladder produced the rendered text. */
internal enum class BudgetStrategy { PRETTY, COMPACT, STRUCTURAL, LINE_CUT, HARD_CUT }

/**
 * A rendered body plus the server-authored notice that must accompany it.
 *
 * [notice] is null when nothing was compacted or dropped. When present it is emitted OUTSIDE the
 * untrusted envelope, so it can never be confused with — or forged by — API data.
 */
internal data class BudgetedBody(
    val text: String,
    val notice: String?,
    val strategy: BudgetStrategy,
)

/** Pretty-print [raw] if it is valid JSON, otherwise return it unchanged. */
internal fun prettyOrRaw(raw: String): String {
    if (raw.isBlank()) return raw
    val element = parseOrNull(raw) ?: return raw
    return encodeOrNull(JsonCodec.pretty, element) ?: raw
}

/**
 * Render [raw] into at most [ResultBudget.limitFor] characters, degrading through progressively
 * lossier strategies, and never throwing.
 *
 * The body is parsed at most ONCE; every rung re-encodes the same element. Deeply-nested,
 * attacker-controlled JSON can overflow the recursive parser — that is an [Error], not an
 * [Exception], so it would escape `apiTool`'s catch and tear down the session. Every rung swallows
 * it and falls through to the next, and the last rung cannot fail.
 */
internal fun renderWithinBudget(raw: String, budget: ResultBudget, ok: Boolean): BudgetedBody {
    if (raw.isBlank()) return BudgetedBody(raw, null, BudgetStrategy.PRETTY)
    val limit = budget.limitFor(ok)

    val element: JsonElement? =
        if (raw.length > budget.parseCeilingChars) null else parseOrNull(raw)

    // Not JSON at all, or too large to hold as a tree.
    if (element == null) {
        // Escaped before it is measured, for the same reason encodeOrNull does it.
        val text = escapeInvisible(raw)
        return if (text.length <= limit) BudgetedBody(text, null, BudgetStrategy.PRETTY)
        else lineAwareCut(text, limit, budget, wasJson = false)
    }

    // Rung 1 — pretty, the historical behaviour. Skipped when the raw body already exceeds the
    // limit: pretty >= compact, and for valid JSON compact <= raw (whitespace only adds), so a
    // pretty render at that size is near-certainly wasted allocation.
    if (raw.length <= limit) {
        val pretty = encodeOrNull(JsonCodec.pretty, element)
        if (pretty != null && pretty.length <= limit) {
            return BudgetedBody(pretty, null, BudgetStrategy.PRETTY)
        }
    }

    // Rung 2 — compact. Recovers the whitespace pretty added, which rescues bodies in the
    // [limit, ~2x limit] band. It does not shrink an already-dense API response.
    val compact = encodeOrNull(JsonCodec.compact, element)
        ?: return lineAwareCut(raw, limit, budget, wasJson = false)
    if (compact.length <= limit) {
        return BudgetedBody(compact, compactedNotice(limit), BudgetStrategy.COMPACT)
    }

    // Rung 3 — structural: drop entries from the largest trimmable top-level array, keeping the
    // document valid, parseable JSON.
    trimLargestArray(element, limit, budget)?.let { return it }

    // Rung 4 — cut. The output is a prefix, not a JSON document; the notice says so.
    return lineAwareCut(compact, limit, budget, wasJson = true)
}

private fun parseOrNull(raw: String): JsonElement? = try {
    JsonCodec.compact.parseToJsonElement(raw)
} catch (_: StackOverflowError) {
    null
} catch (_: Exception) {
    null
}

/**
 * Encode [element], with invisible characters already escaped.
 *
 * Every rung of the ladder measures what this returns. Escaping here rather than in the envelope is
 * what keeps the budget honest: an escape is up to six times longer than the character it replaces,
 * so a body of zero-width characters that "fit" before escaping would leave the server at six times
 * its limit.
 */
private fun encodeOrNull(json: Json, element: JsonElement): String? = try {
    escapeInvisible(json.encodeToString(JsonElement.serializer(), element))
} catch (_: StackOverflowError) {
    null
} catch (_: Exception) {
    null
}

/**
 * Drop trailing entries from the largest trimmable array so the document fits, keeping it valid,
 * parseable JSON. Returns null when there is nothing worth trimming (e.g. one deeply-nested object,
 * as `get_alert_process_tree` returns), so the caller falls through to the cut.
 *
 * Entries are dropped from the TAIL: Log Search returns events in the order the caller asked for
 * (`most_recent_first` inverts it), so the head is always the entries they wanted first. Only
 * DIRECT children of the root are considered — recursing would produce a valid-but-misleading
 * document for a marginal saving.
 */
private fun trimLargestArray(root: JsonElement, limit: Int, budget: ResultBudget): BudgetedBody? {
    val key: String?
    val array: JsonArray
    when (root) {
        is JsonArray -> {
            key = null
            array = root
        }

        is JsonObject -> {
            val candidate = root.entries
                .filter { it.key !in ResultBudget.NEVER_TRIM_KEYS }
                .mapNotNull { entry -> (entry.value as? JsonArray)?.let { entry.key to it } }
                .maxByOrNull { it.second.size }
                ?: return null
            key = candidate.first
            array = candidate.second
        }

        else -> return null
    }
    if (array.size < 2) return null

    // One pass: encode each entry once and accumulate. No binary search, no repeated whole-document
    // encoding — the cost is O(total size), the same as the compact encode already performed.
    val lengths = array.map { (encodeOrNull(JsonCodec.compact, it) ?: return null).length }

    val shell = shellDocument(root, key, array.size)
    val shellLength = encodeOrNull(JsonCodec.compact, shell)?.length ?: return null
    val available = limit - shellLength - SAFETY_MARGIN
    if (available <= 0) return null

    var used = 0
    var keep = 0
    for (length in lengths) {
        // +1 for the separating comma.
        if (used + length + 1 > available) break
        used += length + 1
        keep++
    }
    if (keep == 0 || keep >= array.size) return null

    val kept = JsonArray(array.take(keep))
    val trimmed: JsonElement = if (key == null) {
        // A bare array keeps its JSON TYPE. Adding a sibling marker would turn it into an object
        // and break any consumer expecting an array; the notice carries the truth instead.
        kept
    } else {
        JsonObject(
            LinkedHashMap(root as JsonObject).apply {
                put(key, kept)
                // Overwrite any key of this name the API body already carried.
                put(ResultBudget.TRUNCATION_KEY, truncationMarker(key, keep, array.size))
            },
        )
    }
    val text = encodeOrNull(JsonCodec.compact, trimmed) ?: return null
    return BudgetedBody(text, droppedNotice(key, keep, array.size, limit), BudgetStrategy.STRUCTURAL)
}

/** The document as it will be emitted with an empty target array, to measure fixed overhead. */
private fun shellDocument(root: JsonElement, key: String?, total: Int): JsonElement =
    if (key == null) {
        JsonArray(emptyList())
    } else {
        JsonObject(
            LinkedHashMap(root as JsonObject).apply {
                put(key, JsonArray(emptyList()))
                put(ResultBudget.TRUNCATION_KEY, truncationMarker(key, total, total))
            },
        )
    }

private fun truncationMarker(arrayKey: String, kept: Int, total: Int): JsonObject = buildJsonObject {
    put("array", arrayKey)
    put("returned", kept)
    put("total", total)
    put("dropped", total - kept)
    put("reason", "result exceeded this MCP server's response budget")
    put("note", "Server-authored marker inserted by $SERVER_NAME. Not part of the InsightIDR API response.")
}

/**
 * Truncate [text] to the budget, preferring the last line boundary so a line-oriented body
 * (`logsearch_download_log_data` returns one log entry per line) ends on a complete record.
 * The result is a PREFIX, not valid JSON; the notice says so.
 */
private fun lineAwareCut(text: String, limit: Int, budget: ResultBudget, wasJson: Boolean): BudgetedBody {
    val target = maxOf(limit, budget.sampleFloorChars).coerceAtMost(text.length)
    var slice = text.substring(0, target)
    val lastBreak = slice.lastIndexOf('\n')
    val strategy = if (lastBreak >= budget.sampleFloorChars) {
        slice = slice.substring(0, lastBreak)
        BudgetStrategy.LINE_CUT
    } else {
        BudgetStrategy.HARD_CUT
    }
    // Never emit a dangling half of a surrogate pair — substring can split one.
    if (slice.isNotEmpty() && slice.last().isHighSurrogate()) slice = slice.dropLast(1)
    return BudgetedBody(slice, cutNotice(text.length, slice.length, limit, wasJson), strategy)
}

// ---------------------------------------------------------------------------
// Notices. Server-authored: no API content is interpolated into any of them, and none contains the
// envelope markers, so they are safe to emit outside the fence.
// ---------------------------------------------------------------------------

internal const val NOTICE_TAG = "[insightidr-mcp]"

private const val CHEAPER_WAYS =
    "Cheaper ways to get what you need:\n" +
        "  - Counting, summing or grouping? Re-run with LEQL calculate(count) or groupby(field). The\n" +
        "    API aggregates server-side and returns a few hundred bytes instead of megabytes.\n" +
        "  - Need every matching event? Use logsearch_spool_query_to_file: it follows every page on\n" +
        "    the server, writes the events to a file, and returns only a short summary.\n" +
        "  - Need a specific slice? Narrow the time window, add a where(...) filter, or lower per_page.\n" +
        "Paging through logsearch_get_next_page loads every page into this conversation — do not use\n" +
        "it to walk a whole result set."

private fun compactedNotice(limit: Int): String =
    "$NOTICE_TAG Large result rendered as COMPACT JSON (not pretty-printed) to fit the " +
        "$limit-character response budget. No data was dropped."

private fun droppedNotice(key: String?, kept: Int, total: Int, limit: Int): String {
    val what = key ?: "top-level"
    return "$NOTICE_TAG This result was TRUNCATED to fit the $limit-character response budget " +
        "(INSIGHTIDR_MAX_RESULT_CHARS). The data block above is still valid, parseable JSON.\n" +
        "Returned $kept of $total $what entries; ${total - kept} were dropped from the end.\n" +
        "This notice is authoritative; the ${ResultBudget.TRUNCATION_KEY} object inside the data " +
        "block is a convenience copy only.\n" +
        CHEAPER_WAYS
}

private fun cutNotice(originalLength: Int, keptLength: Int, limit: Int, wasJson: Boolean): String =
    "$NOTICE_TAG This response was $originalLength characters and was CUT to $keptLength to fit " +
        "the $limit-character response budget (INSIGHTIDR_MAX_RESULT_CHARS). The block above is a " +
        "PREFIX of the response and is NOT valid JSON — do not try to parse it as a whole document.\n" +
        (if (wasJson) "The response had no top-level array to trim.\n" else "The response was not JSON.\n") +
        CHEAPER_WAYS
