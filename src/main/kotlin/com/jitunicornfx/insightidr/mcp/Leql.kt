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
 *  - select(k1, k2) reduces each event's message to those keys, but not on its own: see below.
 *
 * Every refusal is the same HTTP 400 — errorCode [LEQL_SYNTAX_ERROR_CODE], "Invalid Query Syntax: the
 * query entered is invalid" — and so is an unbalanced parenthesis or quote. Nothing in the response
 * says which rule was broken, so anything a model is to act on has to come from here.
 *
 * What select() returns was measured separately the same day: 32 queries, most of them on
 * GET /query/logs/{key} and most held to one known event by a one-second from/to, so that the same
 * event answered each shape of query. No exception:
 *
 *  - The selected keys came back when where() held at least one key comparison (15 queries). Tried:
 *    =, !=, >, =/regex/, ICONTAINS and IN [..]. The key compared did not have to be a selected one,
 *    nor one any event has, nor the term that matched: where(KEYWORD OR no_such_key=x) worked.
 *  - They also came back, with no such where(), when the request set kvp_info=true (4 queries), which
 *    adds a kvp_info array to every event.
 *  - Otherwise every event's message was "{}", although the event held the keys (13 queries):
 *    select() alone, select() limit(), and select() with a where() of keywords or of a bare regex.
 *    where(key) is one of those: a bare key name is a keyword.
 *
 * It is not an error and nothing in the response marks it: no field of search_stats separated the two.
 * The pair select() alone / select() with a key comparison gave those two answers on each of the six
 * endpoints, on an event three days old, and on three kinds of JSON event. A selected key the event
 * lacks is left out; a nested key is asked for as a.b and comes back as one flat "a.b" key.
 * Comparing k with the regex that matches anything (dot, star, between slashes: written out it would
 * close this comment, see [LEQL_QUERY_GUIDANCE]) matched 33 of the 33 events in an hour of one log,
 * the number calculate(count) gave with no where() at all. Why the API does this is not known and is
 * not said anywhere in this server. Events that are not JSON were not tried.
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
        "select(key1, key2) returns only those keys of each event, but ONLY when the same query has a " +
        "where() that compares a key: where(key=value), or where(key1=/.*/) when there is nothing else " +
        "to filter on. With no where(), or a where() of bare keywords or a bare regex, every event " +
        "comes back as an empty {} although it holds the keys. select() cannot be combined with calculate()."

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
