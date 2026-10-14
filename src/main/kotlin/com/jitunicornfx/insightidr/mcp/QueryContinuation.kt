package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.tools.LS_MAX_POLL_TIMEOUT_MS

/**
 * What this server tells a model about a Log Search query continuation: the id a still-running query
 * answers with, and the rel="Self" link that carries it.
 *
 * For a continuation it will not serve, spec 3.0.2 gives the poll endpoints a 400 "Invalid
 * Continuation ID" and a 404 "Query Not Found (Continuation ID may have expired)", and no error
 * codes. Measured against the live API on 2026-10-12, on GET /query/{id} and GET /audit/query/{id},
 * there are four cases, and each has a code of its own:
 *
 *  - HTTP 400, errorCode 101010, "Invalid Query Request: continuation id is invalid": a string that is
 *    not a continuation id at all. Both endpoints.
 *  - HTTP 400, errorCode 101053, "Invalid Query Request: unrecognised continuation type": the id the
 *    spec gives as its example.
 *  - HTTP 404, errorCode 101024, "Provided continue id is not valid": a real id with one digit of its
 *    UUID changed, and a real, live id polled on the other endpoint, in both directions. An ordinary
 *    query's id is not valid on /audit/query, nor an audit query's on /query.
 *  - HTTP 404, errorCode 101056, "Cannot find provided continue ID. Please ensure ID is correct and
 *    the search is polled at regular intervals, otherwise it is removed due to inactivity.": a real id
 *    left unpolled, 33 seconds on /query and 40 on /audit/query. Following the same Self link through
 *    logsearch_get_next_page answered the same. So did a FINISHED query's id some minutes after it
 *    had last been read, although seven seconds after that read it had served the result again.
 *
 * How long "unpolled" is, the spec cannot decide: its prose says a link "must be polled at least once
 * every 20 seconds", its code samples that continuations "expire after 10 seconds". One measurement:
 * an id first polled between 13 and 17 seconds after the query was submitted was still good. The
 * exact figure was not measured, and [CONTINUATION_POLL_SECONDS] is the spec's prose, said as such.
 *
 * The hint is chosen by the API's own error code, as for LEQL, and not by which tool was called.
 * That matters for logsearch_get_next_page. The rel="Next" link it is meant for is a new query
 * written out as a URL: the spec says "a new query is launched when fetching the next page", and the
 * one looked at, from GET /query/logs/{key}, was /query/logs/{key}?from=..&to=..&per_page=..
 * &sequence_number=..&next_page=true&query=.. with no continuation id anywhere in it. It has nothing
 * to expire, and the spec's 404 for that endpoint is "Log Not Found". A hint keyed to the tool would
 * have called that an expired query.
 *
 * The generic hints these replace were not wrong so much as useless: a 404 said "the id/RRN may be
 * wrong", to a model holding the very id the API had issued half a minute earlier, and a 400 said to
 * re-check the input schema, where a continuation id is one string of any shape.
 *
 * Fixed text. It repeats nothing from the request or the response, so it is safe outside the
 * untrusted envelope.
 */
internal const val CONTINUATION_POLL_SECONDS = 20

/** The API has no query under this id: see above for what was seen to cause it. */
internal const val CONTINUATION_GONE_CODE = "101056"

internal const val CONTINUATION_GONE_HINT =
    "The API has no query under this continuation id (errorCode $CONTINUATION_GONE_CODE). It discards " +
        "a query that goes unpolled — the Log Search spec says to poll at least every " +
        "$CONTINUATION_POLL_SECONDS seconds — and an id or link it has discarded cannot be revived, so " +
        "do not retry it: run the original query again. Giving the tool longer to wait lets this server " +
        "do the polling inside one call: poll_timeout_ms on a query tool (up to $LS_MAX_POLL_TIMEOUT_MS), " +
        "max_duration_ms on logsearch_spool_query_to_file."

/** [code] is always one of the literals in [CONTINUATION_HINTS], never anything read from a response. */
private fun continuationNotIssuedHint(code: String): String =
    "The API does not recognise this as a continuation id it issued for this endpoint (errorCode $code). " +
        "Pass the \"id\" of a still-running query result exactly as it was returned, to the poll tool of " +
        "the same family: logsearch_audit_poll_query for an audit query, logsearch_poll_query for any " +
        "other. If you no longer have that id, run the query again."

/** Each code under the status it was observed with, and under no other. */
private val CONTINUATION_HINTS: Map<Pair<Int, String>, String> = mapOf(
    (404 to CONTINUATION_GONE_CODE) to CONTINUATION_GONE_HINT,
    (404 to "101024") to continuationNotIssuedHint("101024"),
    (400 to "101010") to continuationNotIssuedHint("101010"),
    (400 to "101053") to continuationNotIssuedHint("101053"),
)

/** The hint for a continuation the API refused, or null when [errorCode] is not one of those four. */
internal fun continuationHint(status: Int, errorCode: String?): String? =
    errorCode?.let { CONTINUATION_HINTS[status to it] }

/**
 * What a query tool says of a result that still carries a rel="Self" link: the query had not finished,
 * and what the result holds is not its answer.
 *
 * Both halves are the spec's. Spec 3.0.2 says a query "is completed and requires no further polling"
 * when its response has no link with rel "Self", and that a poll of one still running returns its
 * progress, and "a partial calculation or some partial search results may be returned". In 0.4.2 a
 * query tool whose wait ran out returned that body as a success with nothing said, and a partial count
 * read like the count.
 *
 * Fixed text, like the hints above: it repeats nothing from the request or the response.
 */
internal const val UNFINISHED_QUERY_NOTE =
    "The query had not finished when this result was returned: it still carries a rel=\"Self\" link, which " +
        "per the Log Search spec a result carries only while its query is running, and until then what it " +
        "holds may be partial search results or a partial calculation. Do not report it as the query's " +
        "answer. To keep waiting, call logsearch_poll_query (logsearch_audit_poll_query for an audit query) " +
        "at once with query_id set to this result's \"id\": the API discards a query that goes unpolled, and " +
        "its spec says to poll at least every $CONTINUATION_POLL_SECONDS seconds. Or run the query again with " +
        "wait_for_completion left on (the default) and a larger poll_timeout_ms (up to $LS_MAX_POLL_TIMEOUT_MS)."

/** Closes the description of both poll tools, so the rule is read before the id is left to go stale. */
internal const val CONTINUATION_POLL_GUIDANCE =
    "The API discards a query that is not polled regularly — the Log Search spec says at least every " +
        "$CONTINUATION_POLL_SECONDS seconds — after which its id answers HTTP 404 and the query has to be " +
        "run again. If you cannot poll that often, run the query again with a larger poll_timeout_ms " +
        "instead of polling by hand."
