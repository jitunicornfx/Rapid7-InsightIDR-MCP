package com.jitunicornfx.insightidr.mcp

/**
 * What this server tells a model about LEQL, the Log Search query language.
 *
 * None of it is in Log Search spec 3.0.2, which leaves the language to docs.rapid7.com and never
 * mentions select() at all. It was measured against the live API on 2026-10-12, on all six query
 * endpoints: every case below on GET /query/logsets (by name), and at least one refused order on each
 * of GET /query/logs/{key}, POST /query/logs, GET /query/logsets/{id}, GET /audit/query/logs/{key} and
 * POST /audit/query/logs, all of which agreed.
 *
 *  - Clauses are accepted in exactly one order, [LEQL_CLAUSE_ORDER], and each is optional. Accepted:
 *    where() through timeslice() as one chain, and select() where() limit(). Refused, every
 *    out-of-order pair tried: where before select, groupby before where, calculate before groupby,
 *    having before calculate, sort before having, limit before where or sort, timeslice before
 *    calculate, sort or limit.
 *  - select() together with calculate() is refused even in that order.
 *  - Keywords are case-insensitive.
 *  - select(k1, k2) returns each event's message reduced to those keys.
 *
 * Every refusal is the same HTTP 400 — errorCode [LEQL_SYNTAX_ERROR_CODE], "Invalid Query Syntax: the
 * query entered is invalid" — and so is an unbalanced parenthesis or quote. Nothing in the response
 * says which rule was broken, so anything a model is to act on has to come from here.
 *
 * NOT measured: a saved query's statement, the download endpoint's query, and the LEQL inside
 * detection rules and metrics. Creating those writes to the tenant, and the download answered 406 to
 * everything. Their descriptions therefore say nothing about clause order.
 */
internal const val LEQL_CLAUSE_ORDER = "select() where() groupby() calculate() having() sort() limit() timeslice()"

/** Closes the description of every parameter that takes a LEQL query for one of those six endpoints. */
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
 * The hint is chosen by the error code, so it appears for a 101011 from ANY tool, including the ones
 * whose endpoints were not measured. There it is still the API saying a query did not parse, which is
 * why the text attributes the order to "the query endpoints" instead of stating it as a law of LEQL.
 *
 * Fixed text. It repeats no part of the request or of the response, so it is safe outside the
 * untrusted envelope.
 */
internal const val LEQL_SYNTAX_ERROR_HINT =
    "The API could not parse the LEQL query (errorCode $LEQL_SYNTAX_ERROR_CODE), so look at the query " +
        "text before changing anything else. Check the clause order first: the query endpoints accept " +
        "only $LEQL_CLAUSE_ORDER, each optional, so select(...) goes BEFORE where(...), and " +
        "groupby(...) before calculate(...). They also refuse select() together with calculate(). " +
        "Then check for unbalanced parentheses or quotes."
