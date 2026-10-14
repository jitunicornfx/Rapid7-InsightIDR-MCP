package com.jitunicornfx.insightidr.mcp

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
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
    /**
     * Bodies that hold more values than this are never parsed either, however short they are. What a
     * tree costs is set by how many values it holds, which [parseCeilingChars] does not limit; see
     * [MAX_PARSED_VALUES] for what was measured, and [holdsMoreValuesThan] for how they are counted.
     */
    val parseCeilingValues: Int = DEFAULT_PARSE_CEILING_VALUES,
    /**
     * Per-event `Context` links are folded into one template only when that saves at least this
     * much; see [foldContextLinks]. Below it the links cost little, and a result that is not worth
     * rewriting is better left exactly as the API sent it.
     */
    val minFoldSavingChars: Int = DEFAULT_MIN_FOLD_SAVING_CHARS,
) {
    /** The limit that actually applies to a response with this [ok] flag. */
    internal fun limitFor(ok: Boolean): Int =
        if (ok) maxChars else maxOf(maxChars, errorFloorChars)

    companion object {
        const val DEFAULT_MAX_CHARS = 200_000
        const val DEFAULT_ERROR_FLOOR_CHARS = 4_000
        const val DEFAULT_SAMPLE_FLOOR_CHARS = 1_000
        const val DEFAULT_PARSE_CEILING_CHARS = 16_000_000
        const val DEFAULT_PARSE_CEILING_VALUES = MAX_PARSED_VALUES
        const val DEFAULT_MIN_FOLD_SAVING_CHARS = 5_000

        /** Key the in-band truncation marker is written under. */
        const val TRUNCATION_KEY = "_mcp_truncated"

        /** Key the in-band template for folded per-event `Context` links is written under. */
        const val FOLDED_LINKS_KEY = "_mcp_folded_links"

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
 * [notice] is null when nothing was compacted, folded or dropped. When present it is emitted OUTSIDE
 * the untrusted envelope, so it can never be confused with — or forged by — API data.
 */
internal data class BudgetedBody(
    val text: String,
    val notice: String?,
    val strategy: BudgetStrategy,
)

/**
 * Render [raw] into at most [ResultBudget.limitFor] characters, degrading through progressively
 * lossier strategies, and never throwing.
 *
 * The body is parsed at most ONCE; every rung re-encodes the same element. Deeply-nested,
 * attacker-controlled JSON can overflow the recursive parser — that is an [Error], not an
 * [Exception], so it would escape `apiTool`'s catch and tear down the session. Every rung swallows
 * it and falls through to the next, and the last rung cannot fail.
 *
 * Before the ladder, and whether or not the body is over budget, the `Context` link repeated on
 * every event of a Log Search result is folded into one template when that saves enough to be worth
 * a notice; see [foldContextLinks]. A fold is kept only while the result is still a JSON document.
 * Its template sits at the END of that document, so a cut would say the template is in the data
 * block and then cut it off: a body that has to be cut is cut as it arrived instead.
 *
 * What is rendered is the tree, and a tree has one member for each key of an object. When the
 * response had more, a notice ahead of any other says how many are missing; see [repeatedKeys].
 *
 * With [rawText] none of that happens. The body is text by design and is shown as it arrived, cut if
 * it does not fit.
 */
internal fun renderWithinBudget(raw: String, budget: ResultBudget, ok: Boolean, rawText: Boolean = false): BudgetedBody {
    if (raw.isBlank()) return BudgetedBody(raw, null, BudgetStrategy.PRETTY)
    val limit = budget.limitFor(ok)

    // A body the tool knows is not an API document is never read as one, whatever it would parse as.
    // The download of log entries is the one there is. Observed 2026-10-13: each entry is one JSON
    // object to a line, so a download of a single entry is a whole JSON document written by whoever
    // wrote the log. Read as one it was re-rendered, and its keys could reach a notice in the
    // server's own voice.
    if (rawText) return renderAsText(raw, limit, budget, CutReason.RAW_TEXT)

    // Too large to hold as a tree, so it is never parsed - and a cut notice may not say what it was.
    if (raw.length > budget.parseCeilingChars) return renderAsText(raw, limit, budget, CutReason.NOT_PARSED)

    // Short enough, and still too large to hold as a tree: a tree is made of values, not characters.
    // They are counted in the text, without parsing it.
    if (holdsMoreValuesThan(raw, budget.parseCeilingValues)) {
        return renderAsText(raw, limit, budget, CutReason.TOO_MANY_VALUES)
    }

    // Not JSON at all.
    val element = parseOrNull(raw) ?: return renderAsText(raw, limit, budget, CutReason.NOT_JSON)

    // A key that an object repeats has one member in the tree and more than one in the text. Every
    // rendering from here on is of the tree, so each of them says what it cannot show.
    val repeated = repeatedKeys(raw, element)?.let(::repeatedKeysNotice)

    // An error body is the diagnostic, and is never rewritten.
    val fold = if (ok) foldContextLinks(element)?.takeIf { it.savedChars >= budget.minFoldSavingChars } else null
    if (fold != null) {
        renderAsDocument(fold.document, raw.length - fold.savedChars, limit, budget)?.let { rendered ->
            return rendered.copy(notice = listOfNotNull(repeated, foldedNotice(fold), rendered.notice).joinToString("\n\n"))
        }
    }

    renderAsDocument(element, raw.length, limit, budget)?.let { return it.saying(repeated) }

    // Rung 4 — cut. The output is a prefix, not a JSON document; the notice says so.
    // When the element cannot be encoded at all (nesting deep enough to overflow the encoder), the
    // fallback is the text as it arrived - escaped first, like every other path, and returned whole
    // if that already fits.
    // The encoding is escaped as far as the cut can keep and the rest of it is counted, as
    // renderAsText does it: the same prefix, and the same length in the notice.
    val compact = encodeUnescapedOrNull(JsonCodec.compact, element) ?: return renderAsText(raw, limit, budget, CutReason.NOT_JSON)
    val head = escapeInvisibleHead(compact, maxOf(limit, budget.sampleFloorChars))
    return lineAwareCut(head, escapedLength(compact), limit, budget, CutReason.NOTHING_TO_TRIM).saying(repeated)
}

/** This body with [first] ahead of whatever notice it has. */
private fun BudgetedBody.saying(first: String?): BudgetedBody =
    if (first == null) this else copy(notice = listOfNotNull(first, notice).joinToString("\n\n"))

/**
 * Why a body is cut rather than trimmed as a document. It is the one thing a cut notice says about
 * what the response was, and the notice is in the server's own voice - so a body that was never
 * parsed has a reason of its own, and is not filed under what a parsed one turned out to be.
 */
private enum class CutReason {
    /** Parsed as JSON, and no rendering of it that is still a document fits. */
    NOTHING_TO_TRIM,

    /** Shown as text because it could not be rendered as JSON. */
    NOT_JSON,

    /** Longer than [ResultBudget.parseCeilingChars], so never parsed. Whether it was JSON is not known. */
    NOT_PARSED,

    /** More values than [ResultBudget.parseCeilingValues], so never parsed. Whether it was JSON is not known. */
    TOO_MANY_VALUES,

    /** Text by design, so never parsed: see `rawText` on [renderWithinBudget]. Whether it was JSON is not known. */
    RAW_TEXT,
}

/**
 * A body that is not rendered as JSON: whole if it fits, otherwise cut at a line boundary.
 *
 * Measured as it will be shown, escaped, for the same reason encodeOrNull does it - but measured by
 * counting, and built only as far as it can be shown: all of a body that fits, and otherwise as much
 * of its start as a cut can keep.
 *
 * Whatever is not JSON, or is too large to parse, arrives here, and that can be all 64 MiB the client
 * reads of one response. Escaped whole, a body of control characters that size was a string six times
 * as long beside the builder it was copied out of, to keep 200,000 characters of it: measured
 * 2026-10-12, an OutOfMemoryError in a 512 MB heap and only just enough in 1 GB. An OutOfMemoryError
 * is an Error, which `apiTool` does not catch.
 */
private fun renderAsText(raw: String, limit: Int, budget: ResultBudget, reason: CutReason): BudgetedBody {
    val length = escapedLength(raw)
    if (length <= limit) return BudgetedBody(escapeInvisible(raw), null, BudgetStrategy.PRETTY)
    val head = escapeInvisibleHead(raw, maxOf(limit, budget.sampleFloorChars))
    return lineAwareCut(head, length, limit, budget, reason)
}

/**
 * Rungs 1 to 3: every rendering of [element] that is still a valid JSON document. Null when none of
 * them fits, or when [element] cannot be encoded at all.
 *
 * [sizeHint] is roughly what [element] takes as compact JSON - the length of the body it was parsed
 * from, less whatever has been folded out of it since.
 */
private fun renderAsDocument(element: JsonElement, sizeHint: Int, limit: Int, budget: ResultBudget): BudgetedBody? {
    // Rung 1 — pretty, the historical behaviour. Skipped when the body already exceeds the limit:
    // pretty >= compact, and for valid JSON compact <= raw (whitespace only adds), so a pretty
    // render at that size is near-certainly wasted allocation.
    if (sizeHint <= limit) {
        encodeUnescapedOrNull(JsonCodec.pretty, element)?.let { escapedWithin(it, limit) }?.let { pretty ->
            return BudgetedBody(pretty, null, BudgetStrategy.PRETTY)
        }
    }

    // Rung 2 — compact. Recovers the whitespace pretty added, which rescues bodies in the
    // [limit, ~2x limit] band. It does not shrink an already-dense API response.
    val compact = encodeUnescapedOrNull(JsonCodec.compact, element) ?: return null
    escapedWithin(compact, limit)?.let { return BudgetedBody(it, compactedNotice(limit), BudgetStrategy.COMPACT) }

    // Rung 3 — structural: drop entries from the largest trimmable top-level array, keeping the
    // document valid, parseable JSON.
    return trimLargestArray(element, limit, budget)
}

/**
 * [raw] as a JSON tree, or null when it is not JSON - which the parser alone does not settle: see
 * [isJson] for what it takes for a value, and [hasValueAfterArrayEnd] for where it ends an array.
 */
private fun parseOrNull(raw: String): JsonElement? = try {
    JsonCodec.compact.parseToJsonElement(raw)
        .takeIf { isJson(it) && !hasValueAfterArrayEnd(raw) }
        ?.let(::withNumbersAsWritten)
} catch (_: StackOverflowError) {
    null
} catch (_: Exception) {
    null
}

/**
 * [root] with every number in it held so that it is written back as the text the API sent.
 *
 * The parser keeps that text and the encoder does not write it. It reads the text as a Long, then a
 * ULong, then a Double, and writes whichever it got. Observed 2026-10-12 through [renderWithinBudget]:
 * `1.50` came back as `1.5`, `1e5` as `100000.0`, `0.00001` as `1.0E-5`, `-0` as `0`, and
 * `123456789012345678901234567890` as `1.2345678901234568E29`, which is not the same number. Nothing
 * said so, and the notice of the compact rung said "No data was dropped".
 *
 * So a number that would not come back as it was is replaced by one the encoder writes as it stands.
 * The only number left alone is an integer that fits a Long: read as one and written as one, it is
 * the text it was, and a document with no other kind of number is returned as it is, with nothing
 * copied. No fixture in this project that stands for an API response has another kind, which does
 * not show that the API sends none.
 *
 * Only for what [isJson] has passed: what is put in the place of a number is written without being
 * read again, so it is put there only when it is a number as RFC 8259 writes one.
 *
 * Walked with a stack of its own, for the reason [isJson] is.
 */
private fun withNumbersAsWritten(root: JsonElement): JsonElement {
    if (root is JsonPrimitive) return root.asWritten()
    val open = ArrayDeque<Rewrite>()
    open.addLast(Rewrite(root))
    while (true) {
        val top = open.last()
        val member = top.next()
        when {
            member is JsonPrimitive -> top.put(member, member.asWritten())
            member != null -> open.addLast(Rewrite(member))
            else -> {
                open.removeLast()
                val parent = open.lastOrNull() ?: return top.result()
                parent.put(top.source, top.result())
            }
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
private fun JsonPrimitive.asWritten(): JsonPrimitive = when {
    isString || this is JsonNull || content == "true" || content == "false" -> this
    content != "-0" && content.toLongOrNull() != null -> this
    isJsonNumber(content) -> JsonUnquotedLiteral(content)
    else -> this
}

/** An object or array part-way through [withNumbersAsWritten]: where the walk is, and a copy once a member has changed. */
private class Rewrite(val source: JsonElement) {
    private val members = (source as? JsonObject)?.entries?.iterator()
    private val items = (source as? JsonArray)?.iterator()
    private var key = ""
    private var index = -1
    private var objectCopy: LinkedHashMap<String, JsonElement>? = null
    private var arrayCopy: ArrayList<JsonElement>? = null

    /** The next member, or null after the last. */
    fun next(): JsonElement? = when {
        members != null -> if (members.hasNext()) members.next().also { key = it.key }.value else null
        items != null && items.hasNext() -> items.next().also { index++ }
        else -> null
    }

    /** Put [now] where [was], the member [next] last returned, stands. Nothing is copied when they are the same. */
    fun put(was: JsonElement, now: JsonElement) {
        if (now === was) return
        when (source) {
            is JsonObject -> (objectCopy ?: LinkedHashMap(source).also { objectCopy = it })[key] = now
            is JsonArray -> (arrayCopy ?: ArrayList(source).also { arrayCopy = it })[index] = now
            is JsonPrimitive -> Unit
        }
    }

    fun result(): JsonElement = objectCopy?.let(::JsonObject) ?: arrayCopy?.let(::JsonArray) ?: source
}

/**
 * Whether every value in [root] is one JSON has, which is less than the parser accepts.
 *
 * Even with `isLenient` off, kotlinx.serialization reads an unquoted word as a value, wherever a
 * value may stand, and the encoder writes it back as whatever it most resembles. Observed 2026-10-12
 * through [renderWithinBudget]: a body of `hello` came back as `"hello"`, `OK` and a line feed as
 * `"OK"`, `0010` as `10`, `1d` as `1.0`, and `[WARN]` as an array holding the string `"WARN"`. A
 * one-word answer, or one short line from `logsearch_download_log_data`, was shown as something the
 * API did not send, and when it had to be cut the notice said it "had no top-level array to trim".
 *
 * So a parse counts only when every value in it is an object, an array, a quoted string, a number as
 * RFC 8259 writes one, `true`, `false` or `null`. One of the last five on its own is a JSON text too,
 * and is still rendered as one: no fixture in this project has an endpoint that answers with a bare
 * scalar, which is not evidence that none does.
 *
 * Walked with a stack of its own, not by recursion: the parser returns objects nested deeper than
 * recursion over them survives. Observed the same day in a test worker, it parsed objects 50,000
 * deep, and the encoder, which does recurse, had given up by 10,000.
 */
internal fun isJson(root: JsonElement): Boolean {
    val open = ArrayDeque<Iterator<JsonElement>>()
    open.addLast(listOf(root).iterator())
    while (open.isNotEmpty()) {
        val values = open.last()
        if (!values.hasNext()) {
            open.removeLast()
            continue
        }
        when (val value = values.next()) {
            is JsonObject -> open.addLast(value.values.iterator())
            is JsonArray -> open.addLast(value.iterator())
            is JsonPrimitive -> if (!value.isJsonScalar()) return false
        }
    }
    return true
}

private fun JsonPrimitive.isJsonScalar(): Boolean =
    isString || this is JsonNull || content == "true" || content == "false" || isJsonNumber(content)

/**
 * Whether [text] is a number as RFC 8259 writes one: `-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?`.
 *
 * Not `toLongOrNull` or `toDoubleOrNull`, which is how the encoder decides what to write: those read
 * `0010`, `+1`, `.5` and `1d` as numbers, and none of them is one in JSON.
 */
private fun isJsonNumber(text: String): Boolean {
    var i = if (text.startsWith('-')) 1 else 0
    // A leading zero stands alone: 0 and 0.5 are numbers, 007 is not.
    val whole = if (text.getOrNull(i) == '0') i + 1 else text.digitsEnd(i)
    if (whole == i) return false
    i = whole
    if (text.getOrNull(i) == '.') {
        val fraction = text.digitsEnd(i + 1)
        if (fraction == i + 1) return false
        i = fraction
    }
    if (text.getOrNull(i) == 'e' || text.getOrNull(i) == 'E') {
        val from = if (text.getOrNull(i + 1) == '+' || text.getOrNull(i + 1) == '-') i + 2 else i + 1
        val exponent = text.digitsEnd(from)
        if (exponent == from) return false
        i = exponent
    }
    return i == text.length
}

/** The index after the run of ASCII digits that starts at [from], which is [from] when there is none. */
private fun String.digitsEnd(from: Int): Int {
    var i = from
    while (i < length && this[i] in '0'..'9') i++
    return i
}

/**
 * Whether [text], which has parsed, has the start of a value after a `]`: something JSON never has,
 * and the parser reads as one more entry of the array that the `]` should have ended.
 *
 * Read from `JsonTreeReader.readArray` in kotlinx.serialization 1.11.0: after an entry it takes a `,`
 * or a `]`, and after either it goes round again for as long as a value can begin, where the object
 * reader beside it stops at a `}`. Observed 2026-10-12 through [renderWithinBudget]: `[1]2]` came back
 * as the array 1, 2, `["a"]"b"]` as "a", "b", and `{"events":[{"id":1}]{"id":2}]}` as a document whose
 * `events` held both objects. The tree is well formed by then, so [isJson] has nothing to find in it,
 * and nothing said the body was not what the API sent.
 *
 * In a text that has parsed, what follows a `]` outside a quoted string is a `,`, another `]`, a `}`,
 * the end of the text, or a value the array went on to. Only the last is looked for, and it is all
 * that has to be: with no such `]`, the arrays and objects the parser built are the ones JSON has.
 * Checked 2026-10-13 through [renderWithinBudget] against a reader written from RFC 8259: every text
 * of seven tokens out of `[ ] { } , :`, a number and two strings, and 1,500,000 documents of up to
 * 120 with a token or two changed, were rendered as a document exactly when they were JSON.
 *
 * A string is read as [holdsMoreValuesThan] reads one, which is as the parser does. One pass over the
 * text, allocating nothing: measured the same day on 15,200,000 characters of events, 12 to 15 ms,
 * where rendering that body took 370 to 500.
 */
private fun hasValueAfterArrayEnd(text: String): Boolean {
    var i = 0
    // What was read last, whitespace aside, was a `]` outside a string.
    var closed = false
    while (i < text.length) {
        val c = text[i++]
        if (c.isJsonSpace()) continue
        if (closed && c != ',' && c != ']' && c != '}') return true
        closed = c == ']'
        if (c == '"') i = afterString(text, i)
    }
    return false
}

/**
 * Encode [element], with invisible characters already escaped: a rendering as it is shown.
 *
 * Every rung of the ladder measures a rendering in this form. Escaping before the measurement rather
 * than in the envelope is what keeps the budget honest: an escape is up to six times longer than the
 * character it replaces, so a body of zero-width characters that "fit" before escaping would leave
 * the server at six times its limit.
 *
 * Only a rung that is going to emit the rendering builds it. One that wants to know how long it is
 * counts instead - [encodedLengthOrNull], [escapedWithin] - and the cut escapes as far as it can keep.
 * Each used to build all of it to read its length. Measured 2026-10-12 at the 16,000,000 characters
 * that are the most parsed, to show 200,000 of them: 263 bytes allocated for each character of a body
 * that is one string of zero-width spaces, and 389 for one of events of 980 of them. Counted, 13 and
 * 21, where the same bodies with nothing to escape take 7 and 13.
 */
private fun encodeOrNull(json: Json, element: JsonElement): String? =
    encodeUnescapedOrNull(json, element)?.let(::escapeInvisible)

/**
 * Encode [element] and no more: what the encoder wrote, with invisible characters still in it. Not
 * yet what is shown, so never emitted and never measured by its own length.
 */
private fun encodeUnescapedOrNull(json: Json, element: JsonElement): String? = try {
    json.encodeToString(JsonElement.serializer(), element)
} catch (_: StackOverflowError) {
    null
} catch (_: Exception) {
    null
}

/** How long [encodeOrNull] would make [element], counted without building it. */
private fun encodedLengthOrNull(json: Json, element: JsonElement): Long? =
    encodeUnescapedOrNull(json, element)?.let(::escapedLength)

/**
 * [encoded] as it is shown, with invisible characters escaped - or null, and nothing built, when that
 * is longer than [limit].
 */
private fun escapedWithin(encoded: String, limit: Int): String? =
    if (escapedLength(encoded) <= limit) escapeInvisible(encoded) else null

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
private fun trimLargestArray(root: JsonElement, limit: Int, budget: ResultBudget): BudgetedBody? = when (root) {
    is JsonArray -> trimArray(root, null, root, limit, budget)

    // "Largest" means largest ENCODED, not most entries. A logset query over 150 logs returns 150
    // small `logs` entries beside 100 large events; chosen by count, `logs` wins, emptying it frees
    // almost nothing, the trim gives up and the body is cut mid-document - invalid JSON, and the
    // Next link at the end of it lost. Candidates are tried biggest first, so a body with two large
    // arrays still trims if either one can make it fit.
    is JsonObject -> root.entries.asSequence()
        .filter { it.key !in ResultBudget.NEVER_TRIM_KEYS }
        .mapNotNull { entry -> (entry.value as? JsonArray)?.let { entry.key to it } }
        .map { (key, array) -> Triple(key, array, encodedLengthOrNull(JsonCodec.compact, array) ?: 0L) }
        .sortedByDescending { it.third }
        .firstNotNullOfOrNull { (key, array, _) -> trimArray(root, key, array, limit, budget) }

    else -> null
}

/** Trim [array] - the root itself when [key] is null, else `root[key]` - so that the document fits. */
private fun trimArray(root: JsonElement, key: String?, array: JsonArray, limit: Int, budget: ResultBudget): BudgetedBody? {
    if (array.size < 2) return null

    // One pass: encode each entry once and accumulate. No binary search, no repeated whole-document
    // encoding — the cost is O(total size), the same as the compact encode already performed.
    val lengths = array.map { encodedLengthOrNull(JsonCodec.compact, it) ?: return null }

    val shell = shellDocument(root, key, array.size)
    val shellLength = encodedLengthOrNull(JsonCodec.compact, shell) ?: return null
    val available = limit - shellLength - SAFETY_MARGIN
    if (available <= 0) return null

    var used = 0L
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
 * Truncate a body to the budget, preferring the last line boundary so a line-oriented body
 * (`logsearch_download_log_data` returns one log entry per line) ends on a complete record.
 * The result is a PREFIX, not valid JSON; the notice says so.
 *
 * [head] is the start of the body as it is shown, escaped, and [totalLength] is the length of all of
 * it in that same form. A caller holding the whole body passes it and its length. One that never
 * built the rest passes at least what a cut can keep - `max(limit, sampleFloorChars)` characters, or
 * the whole body if that is shorter - and the length it counted.
 */
private fun lineAwareCut(head: String, totalLength: Long, limit: Int, budget: ResultBudget, reason: CutReason): BudgetedBody {
    val target = minOf(maxOf(limit, budget.sampleFloorChars).toLong(), totalLength).toInt()
    var slice = head.substring(0, target)
    val lastBreak = slice.lastIndexOf('\n')
    val strategy = if (lastBreak >= budget.sampleFloorChars) {
        slice = slice.substring(0, lastBreak)
        BudgetStrategy.LINE_CUT
    } else {
        BudgetStrategy.HARD_CUT
    }
    // Never emit a dangling half of a surrogate pair — substring can split one.
    if (slice.isNotEmpty() && slice.last().isHighSurrogate()) slice = slice.dropLast(1)
    val notice = cutNotice(totalLength, slice.length, limit, reason, budget.parseCeilingChars, budget.parseCeilingValues)
    return BudgetedBody(slice, notice, strategy)
}

// ---------------------------------------------------------------------------
// Folding the Context link repeated on every event
// ---------------------------------------------------------------------------

/**
 * A Log Search result with its per-event `Context` links folded into one template.
 *
 * [savedChars] is exact for the compact rendering: what was taken out of the [events] events, less
 * the marker that replaced it.
 */
internal data class FoldedLinks(val document: JsonObject, val events: Int, val savedChars: Int)

private const val EVENTS_KEY = "events"
private const val EVENT_LINKS_KEY = "links"
private const val CONTEXT_REL = "Context"
private const val CONTEXT_PATH = "/query/context/"
private const val TIMESTAMP_PARAM = "timestamp="
private const val SEQUENCE_PLACEHOLDER = "{sequence_number_str}"
private const val TIMESTAMP_PLACEHOLDER = "{timestamp}"

/** `"links":` and the comma that separated the member from its neighbour. */
private const val EVENT_LINKS_MEMBER_CHARS = EVENT_LINKS_KEY.length + 4

/** The quotes and colon around the marker's key, and the comma in front of it. */
private const val MARKER_MEMBER_CHARS = ResultBudget.FOLDED_LINKS_KEY.length + 4

private val DIGITS = Regex("^[0-9]{1,32}$")

/**
 * Fold the `Context` link every event of a Log Search result carries into a single template, or
 * return null and leave the result exactly as it arrived.
 *
 * When a query has a LEQL statement the API gives every event `links: [{rel: "Context", href}]`, and
 * the href repeats every log key the query covered. Observed 2026-10-12 on all four query endpoints,
 * on a page reached through a `Next` link and on an audit query: 189 characters for one log, 1,476
 * for a 34-log log set, 3,972 for a 98-log one. At the default page of 100 that was 43% to 89% of
 * the result, and it pushed 44 to 56 of the 100 events out of the response budget. The same log set
 * queried with no statement has no such links and returned all 100.
 *
 * The link says nothing the result does not already say. Its path ends in the event's own
 * `sequence_number_str`, its `timestamp` parameter is the event's `timestamp`, and the rest was the
 * same on every event of a result - checked on 200 of them. `logsearch_get_context_events` needs a
 * sequence number, a timestamp and a log key, and all three stay on the event.
 *
 * So this is a rewrite that can be undone from its own output, and it is made only when that is
 * proved for the result in hand: EVERY event must carry exactly one link, of exactly that shape, and
 * every href must be the same string once its event's sequence number and timestamp are taken out.
 * One event that differs in any way - a second link, another rel, a member this server has never
 * seen - and nothing is folded. Spec 3.0.2 types an event's `links` as a bare array of `{rel, href}`
 * and shows the Context link only in examples, so a link this does not recognise is something to
 * pass on, not something to tidy away.
 */
internal fun foldContextLinks(root: JsonElement): FoldedLinks? {
    val document = root as? JsonObject ?: return null
    // Never write over a member the API sent, however unlikely the name.
    if (ResultBudget.FOLDED_LINKS_KEY in document) return null
    val events = document[EVENTS_KEY] as? JsonArray ?: return null
    if (events.size < 2) return null

    // What a link takes is counted and never built, like every other length the ladder wants. A real
    // link is a few kilobytes, but nothing here limits how long an href is. Measured 2026-10-12 with
    // the rungs already counting: a body of two events whose hrefs held 16,000,000 zero-width
    // characters between them took 268 bytes allocated for each character while this escaped the
    // links to measure them, and 81 once it counted.
    var template: String? = null
    var removed = 0L
    val stripped = ArrayList<JsonElement>(events.size)
    for (entry in events) {
        val event = entry as? JsonObject ?: return null
        val links = event[EVENT_LINKS_KEY] ?: return null
        val own = contextLinkTemplate(event, links) ?: return null
        if (template == null) template = own else if (template != own) return null
        removed += (encodedLengthOrNull(JsonCodec.compact, links) ?: return null) + EVENT_LINKS_MEMBER_CHARS
        stripped += JsonObject(event.filterKeys { it != EVENT_LINKS_KEY })
    }

    val marker = foldedLinksMarker(template ?: return null, events.size)
    val added = (encodedLengthOrNull(JsonCodec.compact, marker) ?: return null) + MARKER_MEMBER_CHARS
    val folded = JsonObject(
        LinkedHashMap(document).apply {
            put(EVENTS_KEY, JsonArray(stripped))
            put(ResultBudget.FOLDED_LINKS_KEY, marker)
        },
    )
    return FoldedLinks(folded, events.size, (removed - added).toInt())
}

/**
 * The href of [event]'s one `Context` link with the event's own sequence number and timestamp
 * replaced by placeholders, or null if [links] is anything other than that one link.
 */
private fun contextLinkTemplate(event: JsonObject, links: JsonElement): String? {
    val link = (links as? JsonArray)?.singleOrNull() as? JsonObject ?: return null
    if (link.size != 2 || link.text("rel") != CONTEXT_REL) return null
    val href = link.text("href") ?: return null

    val sequence = event.text("sequence_number_str")?.takeIf(DIGITS::matches) ?: return null
    val timestamp = (event["timestamp"] as? JsonPrimitive)?.takeUnless { it.isString }?.content
        ?.takeIf(DIGITS::matches) ?: return null
    // What the notice tells the reader to call logsearch_get_context_events with.
    if (event.text("log_id") == null) return null

    // The shape, and only the shape: <anything>/query/context/<one segment>?<parameters>, exactly
    // one of them `timestamp=`. What the segment and that parameter hold is not looked at here.
    val path = href.substringBefore('?', missingDelimiterValue = "")
    val endpoint = path.substringBeforeLast('/', missingDelimiterValue = "") + "/"
    if (!endpoint.endsWith(CONTEXT_PATH)) return null
    val params = href.substring(path.length + 1).split('&')
    if (params.count { it.startsWith(TIMESTAMP_PARAM) } != 1) return null

    val template = endpoint + SEQUENCE_PLACEHOLDER + "?" +
        params.joinToString("&") { if (it.startsWith(TIMESTAMP_PARAM)) TIMESTAMP_PARAM + TIMESTAMP_PLACEHOLDER else it }
    // The proof that folding loses nothing, and the one place an event's own values are compared
    // with its link: filled in again, the template has to be this exact href. It fails for a link
    // to another event, and for an href that itself contains the text of a placeholder.
    return template.takeIf { it.replace(SEQUENCE_PLACEHOLDER, sequence).replace(TIMESTAMP_PLACEHOLDER, timestamp) == href }
}

/** The string value of member [key], or null when it is absent or is not a JSON string. */
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun foldedLinksMarker(template: String, events: Int): JsonObject = buildJsonObject {
    put("rel", CONTEXT_REL)
    put("href_template", template)
    put("events", events)
    put(
        "note",
        "Marker inserted by $SERVER_NAME in place of the per-event links. The marker is not part of the " +
            "InsightIDR API response, but href_template is copied from the links the API sent and is " +
            "untrusted data like the rest of this block. Each of " +
            "the events carried one links entry with this rel, whose href was href_template with " +
            "$SEQUENCE_PLACEHOLDER and $TIMESTAMP_PLACEHOLDER replaced by that event's own sequence_number_str " +
            "and timestamp. Those per-event copies were removed and nothing else was changed.",
    )
}

// ---------------------------------------------------------------------------
// Notices. Server-authored, and emitted OUTSIDE the fence — so the API content that reaches them,
// the name of the array that was trimmed and the keys an object repeated, is allow-listed first
// ([noticeSafeKey]).
// ---------------------------------------------------------------------------

/** A JSON key as it appears in real API responses: a short plain identifier, and nothing else. */
private val NOTICE_SAFE_KEY = Regex("^[A-Za-z0-9_.-]{1,64}$")

/**
 * [key] if it is safe to print in a notice, else null.
 *
 * A JSON object key is chosen by whoever produced the response, and can be any string at all — a
 * sentence, a fake instruction, a megabyte. Naming `events` helps the model; repeating an arbitrary
 * key outside the envelope would hand API data the server's own voice.
 */
internal fun noticeSafeKey(key: String?): String? = key?.takeIf { NOTICE_SAFE_KEY.matches(it) }

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

/**
 * What was folded, where it went, and what to do instead of following a link.
 *
 * Counts and fixed text only. The template is API-provided, so it is shown inside the data block
 * and never here.
 *
 * The last sentence is there because this notice names a tool, and for an audit log that tool cannot
 * work: spec 3.0.2 has no audit counterpart to `/query/context`, and on 2026-10-12 the API answered
 * the Context link it had itself put on an audit-log event with HTTP 404, `errorCode` 101035,
 * "Requested log ... was not found".
 */
private fun foldedNotice(fold: FoldedLinks): String =
    "$NOTICE_TAG ${fold.events} per-event \"Context\" links, identical apart from each event's own sequence " +
        "number and timestamp, were folded into one template (${ResultBudget.FOLDED_LINKS_KEY} in the data " +
        "block), saving ${fold.savedChars} characters of compact JSON. The fold dropped no data: each link is " +
        "that template with the event's own sequence_number_str and timestamp filled in.\n" +
        "To read the events around one, call logsearch_get_context_events with sequence_number = its " +
        "sequence_number_str (as a string, digit for digit), timestamp = its timestamp and log_key = its " +
        "log_id. Audit logs are the " +
        "exception: the API has no context endpoint for them and answers these links with HTTP 404."

/** Most keys a [repeatedKeysNotice] names. Nothing limits how many an object repeats. */
private const val MAX_REPEATED_KEYS_NAMED = 5

/**
 * How many members the data block cannot show, and the keys they were under.
 *
 * A count, fixed text, and the keys that pass [noticeSafeKey], of which at most
 * [MAX_REPEATED_KEYS_NAMED]. The rest are counted and not named.
 */
private fun repeatedKeysNotice(repeated: RepeatedKeys): String {
    val named = repeated.keys.mapNotNull(::noticeSafeKey).take(MAX_REPEATED_KEYS_NAMED)
    val unnamed = repeated.keys.size - named.size
    val keys = named + listOfNotNull("$unnamed not named here".takeIf { unnamed > 0 })
    val members = if (repeated.dropped == 1L) "1 member of this response is" else "${repeated.dropped} members of this response are"
    return "$NOTICE_TAG $members NOT in the data block above. The response repeats a key within an object, " +
        "and only the last member under a key is kept when a response is read as JSON.\n" +
        "Keys the missing members were under: ${keys.joinToString(", ")}."
}

private fun compactedNotice(limit: Int): String =
    "$NOTICE_TAG Large result rendered as COMPACT JSON (not pretty-printed) to fit the " +
        "$limit-character response budget. Compacting dropped no data."

private fun droppedNotice(key: String?, kept: Int, total: Int, limit: Int): String {
    val entries = when {
        key == null -> "top-level entries"
        else -> noticeSafeKey(key)?.let { "$it entries" } ?: "entries of the largest array"
    }
    return "$NOTICE_TAG This result was TRUNCATED to fit the $limit-character response budget " +
        "(INSIGHTIDR_MAX_RESULT_CHARS). The data block above is still valid, parseable JSON.\n" +
        "Returned $kept of $total $entries; ${total - kept} were dropped from the end.\n" +
        "This notice is authoritative; the ${ResultBudget.TRUNCATION_KEY} object inside the data " +
        "block is a convenience copy only.\n" +
        CHEAPER_WAYS
}

/**
 * [originalLength] and [keptLength] are both lengths of the body as it is shown: with invisible
 * characters escaped, and for a JSON body re-encoded compact. That is what the budget is spent on, so
 * the two numbers and the limit compare with each other; it is not the number of characters the API
 * sent whenever anything was escaped.
 *
 * The second line is the only thing said about what the response was, and [reason] is all it may be
 * drawn from. A body over [parseCeiling] is cut without being parsed: observed 2026-10-12 with valid
 * JSON over a lowered ceiling, that line was "The response was not JSON.", of a body nobody had read.
 * One that holds more than [valueCeiling] values is not parsed either, and has a line of its own: it
 * can be a fraction of [parseCeiling] long, so "too large" would name a limit it never reached.
 */
private fun cutNotice(
    originalLength: Long,
    keptLength: Int,
    limit: Int,
    reason: CutReason,
    parseCeiling: Int,
    valueCeiling: Int,
): String =
    "$NOTICE_TAG This response was $originalLength characters and was CUT to $keptLength to fit " +
        "the $limit-character response budget (INSIGHTIDR_MAX_RESULT_CHARS). The block above is a " +
        "PREFIX of the response and is NOT valid JSON — do not try to parse it as a whole document.\n" +
        when (reason) {
            CutReason.NOTHING_TO_TRIM -> "The response had no top-level array to trim.\n"
            CutReason.NOT_JSON -> "The response was not JSON.\n"
            CutReason.NOT_PARSED ->
                "The response was too large to parse (over $parseCeiling characters), so it was not checked for JSON.\n"
            CutReason.TOO_MANY_VALUES ->
                "The response was not parsed: as JSON it holds more than $valueCeiling values, more than this " +
                    "server reads into memory at once, so it was not checked for JSON.\n"
            CutReason.RAW_TEXT ->
                "The response is a download of log entries, one to a line, shown as text; it was not read as JSON.\n"
        } +
        CHEAPER_WAYS
