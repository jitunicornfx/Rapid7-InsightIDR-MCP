package com.jitunicornfx.insightidr.mcp

import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * What this server tells a model when the API answers HTTP 429.
 *
 * ALL OF IT IS FROM THE SPEC. NONE OF IT HAS BEEN OBSERVED. No 429 has been seen from the live API,
 * and nobody tried to provoke one: that takes 1,500 queries inside fifteen minutes on a tenant other
 * people are using. Nor has any rate-limit header been seen on any response, because until this file
 * the client kept a response's status, body and content type and dropped the rest. An unauthenticated
 * 401 from the Log Search host carries none of them (checked 2026-10-12). If a real 429 ever
 * disagrees with what is below, the 429 is right.
 *
 * Log Search spec 3.0.2 says:
 *
 *  - Limits are per 15-minute window: by default 1,500 queries and 10,000 polls, 1,500 requests to
 *    "/management", 300 downloads, 500 each to "/usage", "/exports" and "/search-stats".
 *  - A response carries X-RateLimit-Limit, X-RateLimit-Remaining and X-RateLimit-Reset, the last being
 *    "the number of seconds until the current rate limit window resets".
 *  - A 429 also carries Retry-After, an integer described in the same words, and a text/plain body,
 *    "API rate limit exceeded".
 *  - "If you are rate limited while making a polling request, then the polling link will likely
 *    expire before the limit is reset, and you will need to make the initial query request again
 *    after the limit is reset."
 *
 * The v1, v2 and alert-triage specs do not mention 429 at all. So X-RateLimit-Reset is read only on a
 * Log Search response, where a spec says what it means; Retry-After is read everywhere, because what
 * it means is HTTP's to say (RFC 9110, section 10.2.3), not any one API's.
 *
 * The hint this replaces was "wait briefly before retrying". With windows of fifteen minutes that
 * sends a model straight back into the same 429, and the number that says how long to wait was in a
 * header nobody read.
 *
 * A header is API-controlled text, and what this server says about it goes OUTSIDE the untrusted
 * envelope, in its own voice. So a header value is never repeated: it is parsed to a number, the
 * number is held to [MAX_RETRY_WAIT_SECONDS], and the server writes the sentence. A value that does
 * not parse is treated exactly as a missing one.
 */
internal const val RATE_LIMIT_HINT =
    "Rate limited — retrying at once is likely to be refused again. Wait for the limit to reset " +
        "first; the notice after the data says how long the API asked for, when it said."

/** `X-RateLimit-Reset`, as the Log Search spec spells it. Ktor has no constant for it. */
internal const val RATE_LIMIT_RESET_HEADER = "X-RateLimit-Reset"

/**
 * The longest wait this server will repeat: a day. The spec's windows are fifteen minutes, so
 * anything near this is already strange; anything past it is not a wait a caller can act on, and is
 * far more likely a timestamp, or nonsense, than a delay. It is dropped, not clamped: clamping would
 * be this server inventing a number the API never sent.
 */
internal const val MAX_RETRY_WAIT_SECONDS = 86_400L

/** The window the Log Search spec gives for every one of its limits. */
private const val LOG_SEARCH_WINDOW_MINUTES = 15

private const val SECONDS_PER_MINUTE = 60L

/** Below two minutes, "90 seconds" is clearer than "about 2 minutes". */
private const val MINUTES_WORTH_SAYING_FROM_SECONDS = 120L

/**
 * A header that is a whole number of seconds: `Retry-After` in its usual form, and `X-RateLimit-Reset`.
 * Null unless [value] is nothing but ASCII digits and no more than [MAX_RETRY_WAIT_SECONDS].
 *
 * The digits are checked by hand because `toLongOrNull` is more generous than HTTP: it takes a sign,
 * and it takes any Unicode digit, so "-5" and Arabic-Indic numerals would both come through as numbers.
 */
internal fun parseDelaySeconds(value: String?): Long? {
    val digits = value?.trim(' ', '\t') ?: return null
    if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
    // Null on overflow, so a hundred digits are simply not a number.
    return digits.toLongOrNull()?.takeIf { it <= MAX_RETRY_WAIT_SECONDS }
}

/**
 * `Retry-After` in either form HTTP allows: a number of seconds, or a date to wait until, which is
 * turned into seconds from [now] and rounded up. A date already past means "now", so zero; one more
 * than [MAX_RETRY_WAIT_SECONDS] ahead is no more believable than the same thing written as a number.
 *
 * The spec only describes the number. The date is here because a gateway in front of the API is free
 * to send it, and reading it costs less than explaining why a wait the response did state was ignored.
 * It is measured against this machine's clock, so it is as wrong as that clock is.
 */
internal fun parseRetryAfter(value: String?, now: Instant): Long? {
    parseDelaySeconds(value)?.let { return it }
    val text = value?.trim(' ', '\t') ?: return null
    val until = try {
        ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
    } catch (_: DateTimeException) {
        return null
    }
    val millis = Duration.between(now, until).toMillis()
    if (millis <= 0) return 0
    return ((millis + 999) / 1000).takeIf { it <= MAX_RETRY_WAIT_SECONDS }
}

/**
 * How long a 429 asks the caller to wait, or null when it does not say in a form this server reads.
 *
 * `Retry-After` first: it is the header whose whole meaning is "wait this long before the next
 * request". [rateLimitReset] is the fallback, and the caller passes it only for a Log Search response.
 */
internal fun retryWaitSeconds(retryAfter: String?, rateLimitReset: String?, now: Instant): Long? =
    parseRetryAfter(retryAfter, now) ?: parseDelaySeconds(rateLimitReset)

private fun describeWait(seconds: Long): String {
    val exact = if (seconds == 1L) "1 second" else "$seconds seconds"
    if (seconds < MINUTES_WORTH_SAYING_FROM_SECONDS) return exact
    // Rounded up: told "about 14 minutes" for 847 seconds, a caller comes back seven seconds early.
    return "$exact (about ${(seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE} minutes)"
}

/**
 * The server's note on a 429, rendered after the untrusted envelope.
 *
 * [waitSeconds] is a number this server parsed and bounded, never header text. For Log Search the
 * note adds what its spec says and stops there: the window length when the response named no wait,
 * and that a query being polled does not outlive a long one.
 */
internal fun rateLimitNote(waitSeconds: Long?, logSearch: Boolean): String = buildString {
    if (waitSeconds != null) {
        append("The API asked for ${describeWait(waitSeconds)} before the next request.")
    } else {
        append("The API did not say how long to wait in a form this server could read.")
        if (logSearch) append(" The Log Search spec describes $LOG_SEARCH_WINDOW_MINUTES-minute rate-limit windows.")
    }
    if (logSearch) {
        append(
            " A running query is discarded once it goes unpolled (the Log Search spec says to poll at " +
                "least every $CONTINUATION_POLL_SECONDS seconds), so if one was being polled and the wait " +
                "is longer than that, run the query again afterwards instead of polling it.",
        )
    }
}
