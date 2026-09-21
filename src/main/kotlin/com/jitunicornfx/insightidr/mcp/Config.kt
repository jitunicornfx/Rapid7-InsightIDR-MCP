package com.jitunicornfx.insightidr.mcp

import java.net.URI
import java.net.URISyntaxException

/**
 * Insight platform regional data centers.
 *
 * The IDR APIs (v1 and v2 alike) are served from `https://{region}.api.insight.rapid7.com`; the
 * Log Search API is served from `https://{region}.rest.logs.insight.rapid7.com`. The region code is
 * the prefix of your Insight platform URL (e.g. `us` in `us.idr.insight.rapid7.com`).
 */
enum class Region(val code: String) {
    US("us"),
    US2("us2"),
    US3("us3"),
    EU("eu"),
    CA("ca"),
    AU("au"),
    AP("ap");

    companion object {
        fun fromCode(value: String): Region {
            val normalized = value.trim().lowercase()
            // What was typed is not repeated: a secret pasted into the wrong variable ends up here,
            // and this message goes to stderr, which MCP hosts keep in a log file.
            return entries.firstOrNull { it.code == normalized }
                ?: throw IllegalArgumentException(
                    "INSIGHTIDR_REGION is not a known region. Valid regions: " +
                        entries.joinToString(", ") { it.code },
                )
        }
    }
}

/**
 * Runtime configuration for the MCP server, resolved from environment variables.
 */
data class Config(
    val apiKey: String,
    val region: Region,
    /** Base URL for the v2 API, per the v2 spec servers: `https://{region}.api.insight.rapid7.com`. */
    val baseUrl: String,
    val requestTimeoutMillis: Long,
    /**
     * Ceiling on the characters of API data any one tool result may carry (~4 chars per token, so
     * the 200_000 default is roughly 50k tokens). A Log Search page routinely exceeds a megabyte;
     * results over the budget are rendered compactly, then structurally trimmed, then cut — always
     * with a server-authored notice naming what was dropped. See [ResultBudget].
     *
     * There is deliberately no "unlimited" value: one environment variable must not be able to
     * silently reinstate an unbounded result. Raise the number instead.
     */
    val maxResultChars: Int = DEFAULT_MAX_RESULT_CHARS,
    /**
     * Directory for results spooled by `logsearch_spool_query_to_file`. Null uses the per-user
     * default (`~/.rapid7-insightidr-mcp/spool`). See [SpoolStore].
     */
    val spoolDirectory: String? = null,
    /**
     * Hours a spooled result survives before startup sweeps it. `0` disables sweeping entirely,
     * for analysts who must preserve the files as evidence.
     */
    val spoolRetentionHours: Int = DEFAULT_SPOOL_RETENTION_HOURS,
    /**
     * The one directory `upload_attachment` may read files from. Null — the default — disables that
     * tool: it sends a local file to Rapid7 at the model's request, so it stays off until the
     * operator says where it may look. See [UploadPolicy].
     */
    val uploadDirectory: String? = null,
    /**
     * Base URL for the Log Search REST API. The Log Search spec's servers are the
     * `https://<region>.rest.logs.insight.rapid7.com` hosts; override via
     * [ENV_LOG_SEARCH_BASE_URL] (e.g. to the unified platform route
     * `https://<region>.api.insight.rapid7.com/log_search`).
     */
    val logSearchBaseUrl: String = "https://${region.code}.rest.logs.insight.rapid7.com",
    /**
     * Base URL for the v1 API: `https://<region>.api.insight.rapid7.com`, the same host as v2.
     *
     * The spec and this default agree as of v1.3.1.0. They did not always: every v1 spec up to and
     * including v1.3.0.3 advertised `https://<region>.rest.logs.insight.rapid7.com` in its `servers`
     * block, which was wrong for the `/idr/v1/` paths. Measured against the live API on 2026-08-17,
     * every v1 IDR route returned 404 on that host and 401 (i.e. exists, authentication required) on
     * `api.insight`, while the Log Search routes behaved the other way round — the spec had picked up
     * the Log Search host by mistake. That measurement is why this override exists; Rapid7 corrected
     * the spec in v1.3.1.0. Override via [ENV_V1_BASE_URL] if a tenant ever needs something else.
     */
    val v1BaseUrl: String = "https://${region.code}.api.insight.rapid7.com",
    /**
     * Browser origins permitted to call the server in `--http` mode (CORS). Empty by default, so
     * cross-origin browser requests are denied — the server holds a secret API key and is intended
     * for local/non-browser MCP clients (which don't send an `Origin` header and are unaffected).
     * Set [ENV_HTTP_ALLOWED_ORIGINS] to a comma-separated list (e.g. `https://app.example.com`) only
     * if a trusted browser client must reach it. Never use `*`.
     */
    val httpAllowedOrigins: List<String> = emptyList(),
    /**
     * Shared secret that every `--http` request must present as `Authorization: Bearer <token>`.
     *
     * The HTTP transport fronts a server holding the InsightIDR API key, so without this anything that
     * can reach the port can read and change the tenant. It is REQUIRED when `--host` is not loopback
     * (the server refuses to start otherwise) and optional, with a warning, on loopback. A secret:
     * never in [toString], never in [ServerFacts], never logged.
     */
    val httpAuthToken: String? = null,
    /**
     * Whether the startup check for a newer GitHub release is disabled. The check is best-effort,
     * unauthenticated, and never blocks startup; set [ENV_DISABLE_UPDATE_CHECK] to a truthy value
     * (`1`, `true`, `yes`) in air-gapped or egress-restricted deployments to skip it entirely.
     */
    val updateCheckDisabled: Boolean = false,
    /**
     * Whether automatically downloading and installing a newer release is disabled.
     *
     * Automatic installation replaces the JAR this server runs from, so it is code execution from a
     * remote source: the download is verified against the SHA-256 digest GitHub publishes for the
     * asset, but that digest comes from the same API response, so a compromise of the GitHub release
     * itself would not be caught (see [UpdateInstaller]). Set [ENV_DISABLE_AUTO_UPDATE] to a truthy
     * value — or pass `--no-auto-update` — to keep the notification but never install anything.
     * Disabling the check entirely ([updateCheckDisabled]) also disables installation.
     */
    val autoUpdateDisabled: Boolean = false,
    /**
     * Things [fromEnv] adjusted rather than refused, for [startupWarnings] to report. A value that
     * parses but is out of range is put right and SAID to have been: this server updates itself, so
     * a configuration that ran yesterday must still run today, but it need not run in silence.
     */
    val configWarnings: List<String> = emptyList(),
) {
    /**
     * Things the operator should be told at startup. Separate from [fromEnv] so that stays a pure
     * function of its input; Main prints these to stderr.
     *
     * A base URL outside rapid7.com is legitimate — a proxy, a test double — but it is also where a
     * tampered environment would point the server to collect the API key, which is attached to every
     * request. It is allowed, and said out loud.
     */
    fun startupWarnings(): List<String> = configWarnings + listOf(
        ENV_BASE_URL to baseUrl,
        ENV_V1_BASE_URL to v1BaseUrl,
        ENV_LOG_SEARCH_BASE_URL to logSearchBaseUrl,
    ).mapNotNull { (variable, url) ->
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return@mapNotNull null
        if (host == "rapid7.com" || host.endsWith(".rapid7.com") || isLoopbackHost(host)) return@mapNotNull null
        "WARNING: $variable points at '$host', which is not a rapid7.com host. " +
            "The InsightIDR API key is sent with every request to it."
    }

    /** The API key is a secret; never include it in [toString] output or logs. */
    override fun toString(): String =
        "Config(region=${region.code}, baseUrl=$baseUrl, v1BaseUrl=$v1BaseUrl, logSearchBaseUrl=$logSearchBaseUrl, " +
            "requestTimeoutMillis=$requestTimeoutMillis, apiKey=***, " +
            "httpAuthToken=${if (httpAuthToken == null) "unset" else "***"})"

    companion object {
        const val ENV_API_KEY = "INSIGHTIDR_API_KEY"
        const val ENV_REGION = "INSIGHTIDR_REGION"
        const val ENV_BASE_URL = "INSIGHTIDR_BASE_URL"
        const val ENV_V1_BASE_URL = "INSIGHTIDR_V1_BASE_URL"
        const val ENV_LOG_SEARCH_BASE_URL = "INSIGHTIDR_LOG_SEARCH_BASE_URL"
        const val ENV_TIMEOUT_MS = "INSIGHTIDR_TIMEOUT_MS"
        const val ENV_HTTP_ALLOWED_ORIGINS = "INSIGHTIDR_HTTP_ALLOWED_ORIGINS"
        const val ENV_HTTP_TOKEN = "INSIGHTIDR_HTTP_TOKEN"

        /**
         * Shortest bearer token accepted. Failed attempts are throttled per client address, but a
         * throttle only slows a search; it is the size of the space that defeats one. 16 characters
         * of anything reasonable is far beyond reach at 20 guesses a minute.
         */
        const val MIN_HTTP_TOKEN_CHARS = 16
        const val ENV_DISABLE_UPDATE_CHECK = "INSIGHTIDR_DISABLE_UPDATE_CHECK"
        const val ENV_DISABLE_AUTO_UPDATE = "INSIGHTIDR_DISABLE_AUTO_UPDATE"
        const val ENV_MAX_RESULT_CHARS = "INSIGHTIDR_MAX_RESULT_CHARS"
        const val ENV_SPOOL_DIR = "INSIGHTIDR_SPOOL_DIR"
        const val ENV_SPOOL_RETENTION_HOURS = "INSIGHTIDR_SPOOL_RETENTION_HOURS"
        const val ENV_UPLOAD_DIR = "INSIGHTIDR_UPLOAD_DIR"

        /** Values accepted as "on" for the boolean opt-out variables. */
        private val TRUTHY = setOf("1", "true", "yes", "on")

        /** Values accepted as "off". Anything in neither set is refused; see [switch]. */
        private val FALSY = setOf("0", "false", "no", "off")

        internal fun isTruthy(value: String?): Boolean = value?.trim()?.lowercase() in TRUTHY

        /** Literal loopback names only. No DNS: a hosts-file entry must not be able to widen this. */
        internal fun isLoopbackHost(host: String): Boolean =
            host.lowercase() in setOf("localhost", "127.0.0.1", "::1", "[::1]")

        /**
         * Check a base-URL override and return it without its trailing slash.
         *
         * Every request to a base URL carries the API key, so a malformed one is refused at startup
         * rather than discovered at the first tool call — or not at all: Ktor parses `https://` to
         * the host `localhost`, and would quietly send the key there. The checks run on the raw
         * string for that reason.
         *
         * No message ever repeats the value. It may contain credentials (which is one of the things
         * being rejected), and these messages end up in logs.
         */
        internal fun validateBaseUrl(variable: String, raw: String): String {
            fun bad(why: String): Nothing = throw IllegalStateException("$variable $why")

            val value = raw.trim()
            if ('@' in value) bad("must not contain credentials (an '@').")
            if (value.any { it.isWhitespace() || it.isISOControl() }) bad("must not contain spaces or control characters.")
            val uri = try {
                URI(value)
            } catch (e: URISyntaxException) {
                bad("is not a valid URL. Expected something like https://us.api.insight.rapid7.com.")
            }
            val scheme = uri.scheme?.lowercase() ?: bad("must start with https://.")
            val host = uri.host?.takeIf { it.isNotEmpty() }
                ?: bad("must name a host, e.g. https://us.api.insight.rapid7.com.")
            when (scheme) {
                "https" -> Unit
                "http" -> if (!isLoopbackHost(host)) {
                    bad("must use https://. Plain http:// is accepted only for localhost, for testing.")
                }
                else -> bad("must use https://.")
            }
            if (uri.rawQuery != null || uri.rawFragment != null) bad("must not contain a query string or a fragment.")
            // java.net.URI accepts any run of digits as a port; -1 means "none given".
            if (uri.port != -1 && uri.port !in 1..65_535) bad("has a port that is not between 1 and 65535.")
            // This validated the URL with one parser and Ktor sends the request with another. They
            // have to agree on where it goes, or the check above is about a different host.
            val sent = runCatching { io.ktor.http.Url(value) }.getOrNull()
                ?: bad("is not a URL the HTTP client can use.")
            if (!sent.host.equals(host.removePrefix("[").removeSuffix("]"), ignoreCase = true) &&
                !sent.host.equals(host, ignoreCase = true)
            ) {
                bad("is ambiguous about which host it names.")
            }
            return value.trimEnd('/')
        }

        /**
         * Read a whole-number variable: null when unset or blank, an exception when it is not a whole
         * number at all.
         *
         * A typo used to fall back to the default in silence. For most settings that is merely
         * confusing; for the spool retention it is data loss — `INSIGHTIDR_SPOOL_RETENTION_HOURS=never`
         * meant "24 hours", and an analyst's preserved evidence was swept the next day. The value is
         * never repeated in the message: a secret pasted into the wrong variable should not be echoed
         * into a log.
         *
         * Range is the caller's business, because the right answer differs: see [fromEnv].
         */
        internal fun wholeNumber(env: Map<String, String>, variable: String): Long? {
            val raw = env[variable]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return raw.toLongOrNull() ?: throw IllegalStateException("$variable must be a whole number.")
        }

        /**
         * A safety switch: true, false, or a refusal to start.
         *
         * These turn things OFF (the update check, automatic installation). Read leniently, a value
         * the server does not recognise — `y`, `enabled` — counts as "not set", and the thing the
         * operator asked to be switched off stays on. So an unrecognised value stops the server.
         */
        internal fun switch(env: Map<String, String>, variable: String): Boolean {
            val raw = env[variable]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
            return when (raw) {
                in TRUTHY -> true
                in FALSY -> false
                else -> throw IllegalStateException(
                    "$variable must be one of ${TRUTHY.joinToString("/")} to switch it on, or " +
                        "${FALSY.joinToString("/")} (or unset) to leave it off.",
                )
            }
        }

        const val DEFAULT_REGION = "us"
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val DEFAULT_MAX_RESULT_CHARS = ResultBudget.DEFAULT_MAX_CHARS

        /** Below this a result is too small to be diagnostically useful; a misconfiguration is clamped up. */
        const val MIN_MAX_RESULT_CHARS = 2_000
        const val DEFAULT_SPOOL_RETENTION_HOURS = 24

        /** One hour. Longer than any single InsightIDR request should ever be allowed to hang. */
        const val MAX_TIMEOUT_MS = 3_600_000L

        /** Ten years. Large enough to mean "effectively forever"; small enough not to overflow as millis. */
        const val MAX_SPOOL_RETENTION_HOURS = 87_600L

        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            val warnings = mutableListOf<String>()

            // Trimmed, because a key read from a file ends in a line break. What is left has to be
            // usable as an HTTP header value. If it is not, Ktor refuses the header at the first tool
            // call with an IllegalArgumentException that QUOTES the value - straight into a tool
            // result. So it is refused here instead, and never repeated.
            val apiKey = env[ENV_API_KEY]?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException(
                    "Missing required environment variable $ENV_API_KEY. " +
                            "Create an Insight platform API key and expose it to the server.",
                )
            if (apiKey.any { it.code !in 0x21..0x7E }) {
                throw IllegalStateException(
                    "$ENV_API_KEY contains a space, a line break or a non-ASCII character, so it cannot be " +
                        "sent as an HTTP header. Look for a stray quote, or a character picked up by copy and paste.",
                )
            }

            val region = Region.fromCode(env[ENV_REGION]?.takeIf { it.isNotBlank() } ?: DEFAULT_REGION)

            fun baseUrl(variable: String, default: String): String =
                env[variable]?.takeIf { it.isNotBlank() }?.let { validateBaseUrl(variable, it) } ?: default

            val baseUrl = baseUrl(ENV_BASE_URL, "https://${region.code}.api.insight.rapid7.com")
            val logSearchBaseUrl = baseUrl(ENV_LOG_SEARCH_BASE_URL, "https://${region.code}.rest.logs.insight.rapid7.com")
            val v1BaseUrl = baseUrl(ENV_V1_BASE_URL, "https://${region.code}.api.insight.rapid7.com")

            val timeout = wholeNumber(env, ENV_TIMEOUT_MS).let { asked ->
                when {
                    asked == null -> DEFAULT_TIMEOUT_MS
                    asked <= 0 -> DEFAULT_TIMEOUT_MS.also {
                        warnings += "WARNING: $ENV_TIMEOUT_MS must be greater than 0 (there is no \"no timeout\"); using the default, $it ms."
                    }
                    asked > MAX_TIMEOUT_MS -> MAX_TIMEOUT_MS.also {
                        warnings += "WARNING: $ENV_TIMEOUT_MS is above the maximum; using $it ms."
                    }
                    else -> asked
                }
            }

            // Normalised HERE, once, and an entry that cannot be an origin stops the server. Dropped
            // in silence it left the operator believing a browser client was allowed while every
            // request from it got 403 - and insightidr_server_info still counted it.
            val httpAllowedOrigins = env[ENV_HTTP_ALLOWED_ORIGINS].orEmpty()
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .mapIndexedNotNull { index, entry ->
                    if (entry == "*") {
                        warnings += "WARNING: '*' in $ENV_HTTP_ALLOWED_ORIGINS is ignored. This server holds an " +
                            "API key and never allows every origin; list the ones you mean."
                        return@mapIndexedNotNull null
                    }
                    // A bare host has always meant https.
                    normalizeOrigin(if ("://" in entry) entry else "https://$entry")
                        ?: throw IllegalStateException(
                            "$ENV_HTTP_ALLOWED_ORIGINS entry ${index + 1} is not an origin. Each must be " +
                                "scheme://host[:port] with no path, for example https://app.example.com.",
                        )
                }
                .distinct()

            // Never echoed, not even its length: this message goes to a log.
            val httpAuthToken = env[ENV_HTTP_TOKEN]?.trim()?.takeIf { it.isNotEmpty() }?.also { token ->
                if (token.length < MIN_HTTP_TOKEN_CHARS || token.any { it.isWhitespace() || it.isISOControl() }) {
                    throw IllegalStateException(
                        "$ENV_HTTP_TOKEN must be at least $MIN_HTTP_TOKEN_CHARS characters with no spaces. " +
                            "Generate one with, for example: openssl rand -hex 32",
                    )
                }
            }

            val updateCheckDisabled = switch(env, ENV_DISABLE_UPDATE_CHECK)
            val autoUpdateDisabled = switch(env, ENV_DISABLE_AUTO_UPDATE)

            val maxResultChars = wholeNumber(env, ENV_MAX_RESULT_CHARS).let { asked ->
                when {
                    asked == null -> DEFAULT_MAX_RESULT_CHARS
                    // The "unlimited" attempt. There is no unlimited; it has always meant the default.
                    asked <= 0 -> DEFAULT_MAX_RESULT_CHARS.also {
                        warnings += "WARNING: $ENV_MAX_RESULT_CHARS must be greater than 0 (there is no \"unlimited\"); using the default, $it."
                    }
                    // Too small to be diagnosable: raised to the floor, as documented.
                    asked < MIN_MAX_RESULT_CHARS -> MIN_MAX_RESULT_CHARS.also {
                        warnings += "WARNING: $ENV_MAX_RESULT_CHARS is below the minimum; using $it."
                    }
                    else -> asked.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                }
            }

            val spoolDirectory = env[ENV_SPOOL_DIR]?.trim()?.takeIf { it.isNotBlank() }
            val uploadDirectory = env[ENV_UPLOAD_DIR]?.trim()?.takeIf { it.isNotBlank() }

            val spoolRetentionHours = wholeNumber(env, ENV_SPOOL_RETENTION_HOURS).let { asked ->
                when {
                    asked == null -> DEFAULT_SPOOL_RETENTION_HOURS
                    // REFUSED, not adjusted. Whoever wrote -1 plausibly meant "never sweep", which is
                    // spelled 0 - and guessing 24 hours for them is how preserved evidence gets deleted.
                    asked < 0 -> throw IllegalStateException(
                        "$ENV_SPOOL_RETENTION_HOURS must be 0 or greater. Use 0 to never sweep spooled files.",
                    )
                    asked > MAX_SPOOL_RETENTION_HOURS -> MAX_SPOOL_RETENTION_HOURS.toInt().also {
                        warnings += "WARNING: $ENV_SPOOL_RETENTION_HOURS is above the maximum; using $it hours."
                    }
                    else -> asked.toInt()
                }
            }

            return Config(
                apiKey = apiKey,
                region = region,
                baseUrl = baseUrl,
                requestTimeoutMillis = timeout,
                maxResultChars = maxResultChars,
                spoolDirectory = spoolDirectory,
                spoolRetentionHours = spoolRetentionHours,
                uploadDirectory = uploadDirectory,
                logSearchBaseUrl = logSearchBaseUrl,
                v1BaseUrl = v1BaseUrl,
                httpAllowedOrigins = httpAllowedOrigins,
                httpAuthToken = httpAuthToken,
                updateCheckDisabled = updateCheckDisabled,
                autoUpdateDisabled = autoUpdateDisabled,
                configWarnings = warnings,
            )
        }
    }
}
