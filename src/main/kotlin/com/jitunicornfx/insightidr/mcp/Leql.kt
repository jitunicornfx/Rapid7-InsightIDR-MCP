package com.jitunicornfx.insightidr.mcp

/**
 * What this server tells a model about LEQL, the Log Search query language.
 *
 * None of it is in Log Search spec 3.0.2, which leaves the language to docs.rapid7.com and never
 * mentions select() at all. It was measured against the live API on 2026-10-12: every case below on
 * GET /query/logsets, and a sample of them on POST /query/logs, GET /audit/query/logs/{key} and
 * POST /audit/query/logs, which agreed.
 *
 *  - Clauses are accepted in exactly one order, [LEQL_CLAUSE_ORDER], and each is optional. Accepted:
 *    where() through timeslice() as one chain, and select() where() limit(). Refused, every
 *    out-of-order pair tried: where before select, groupby before where, calculate before groupby,
 *    having before calculate, sort before having, limit before where or sort, timeslice before
 *    calculate or sort.
 *  - select() together with calculate() is refused even in that order.
 *  - Keywords are case-insensitive.
 *  - select(k1, k2) returns each event's message reduced to those keys.
 *
 * Every refusal is the same HTTP 400 — errorCode [LEQL_SYNTAX_ERROR_CODE], "Invalid Query Syntax: the
 * query entered is invalid" — and so is an unbalanced parenthesis or quote. Nothing in the response
 * says which rule was broken, so anything a model is to act on has to come from here.
 */
internal const val LEQL_CLAUSE_ORDER = "select() where() groupby() calculate() having() sort() limit() timeslice()"

/** Closes the description of every parameter that takes a LEQL query for one of those endpoints. */
internal const val LEQL_QUERY_GUIDANCE =
    "Clauses are optional but their order is fixed: $LEQL_CLAUSE_ORDER. Any other order is rejected " +
        "as a syntax error — write select(...) where(...), never where(...) select(...). " +
        "select(key1, key2) returns only those keys of each event; it cannot be combined with calculate()."

/** The Log Search API's error code for a query it could not parse. Observed; not in the spec. */
internal const val LEQL_SYNTAX_ERROR_CODE = "101011"

/**
 * What an error result says in place of the generic HTTP 400 hint when the API reports
 * [LEQL_SYNTAX_ERROR_CODE].
 *
 * The generic hint sends the model to "this tool's input schema", where a LEQL mistake cannot be
 * found: every parameter is well-formed and the fault is inside one string. A model handed that hint
 * for `where(...) select(...)` had nothing to act on.
 *
 * Fixed text. It repeats no part of the request or of the response, so it is safe outside the
 * untrusted envelope.
 */
internal const val LEQL_SYNTAX_ERROR_HINT =
    "The API could not parse the LEQL query (errorCode $LEQL_SYNTAX_ERROR_CODE). The fault is in the " +
        "LEQL text, not in the other parameters. Check the clause order first — it is fixed: " +
        "$LEQL_CLAUSE_ORDER, each optional — so select(...) goes BEFORE where(...), and groupby(...) " +
        "before calculate(...). select() cannot be combined with calculate(). Then check for " +
        "unbalanced parentheses or quotes."
