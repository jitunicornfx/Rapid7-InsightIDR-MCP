package com.jitunicornfx.insightidr.mcp

/**
 * Insight platform regional data centers.
 *
 * Per the OpenAPI specifications, the v2 API is served from
 * `https://{region}.api.insight.rapid7.com` and the v1 API from
 * `https://{region}.rest.logs.insight.rapid7.com`. The region code is the prefix of your
 * Insight platform URL (e.g. `us` in `us.idr.insight.rapid7.com`).
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
            return entries.firstOrNull { it.code == normalized }
                ?: throw IllegalArgumentException(
                    "Unknown InsightIDR region '$value'. Valid regions: " +
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
     * Base URL for the Log Search REST API. The Log Search spec's servers are the
     * `https://<region>.rest.logs.insight.rapid7.com` hosts; override via
     * [ENV_LOG_SEARCH_BASE_URL] (e.g. to the unified platform route
     * `https://<region>.api.insight.rapid7.com/log_search`).
     */
    val logSearchBaseUrl: String = "https://${region.code}.rest.logs.insight.rapid7.com",
    /**
     * Base URL for the v1 API. The v1 spec's servers are the
     * `https://<region>.rest.logs.insight.rapid7.com` hosts; override via [ENV_V1_BASE_URL]
     * (e.g. back to `https://<region>.api.insight.rapid7.com` if your tenant routes v1 there).
     */
    val v1BaseUrl: String = "https://${region.code}.rest.logs.insight.rapid7.com",
    /**
     * Browser origins permitted to call the server in `--http` mode (CORS). Empty by default, so
     * cross-origin browser requests are denied — the server holds a secret API key and is intended
     * for local/non-browser MCP clients (which don't send an `Origin` header and are unaffected).
     * Set [ENV_HTTP_ALLOWED_ORIGINS] to a comma-separated list (e.g. `https://app.example.com`) only
     * if a trusted browser client must reach it. Never use `*`.
     */
    val httpAllowedOrigins: List<String> = emptyList(),
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
) {
    /** The API key is a secret; never include it in [toString] output or logs. */
    override fun toString(): String =
        "Config(region=${region.code}, baseUrl=$baseUrl, v1BaseUrl=$v1BaseUrl, logSearchBaseUrl=$logSearchBaseUrl, " +
            "requestTimeoutMillis=$requestTimeoutMillis, apiKey=***)"

    companion object {
        const val ENV_API_KEY = "INSIGHTIDR_API_KEY"
        const val ENV_REGION = "INSIGHTIDR_REGION"
        const val ENV_BASE_URL = "INSIGHTIDR_BASE_URL"
        const val ENV_V1_BASE_URL = "INSIGHTIDR_V1_BASE_URL"
        const val ENV_LOG_SEARCH_BASE_URL = "INSIGHTIDR_LOG_SEARCH_BASE_URL"
        const val ENV_TIMEOUT_MS = "INSIGHTIDR_TIMEOUT_MS"
        const val ENV_HTTP_ALLOWED_ORIGINS = "INSIGHTIDR_HTTP_ALLOWED_ORIGINS"
        const val ENV_DISABLE_UPDATE_CHECK = "INSIGHTIDR_DISABLE_UPDATE_CHECK"
        const val ENV_DISABLE_AUTO_UPDATE = "INSIGHTIDR_DISABLE_AUTO_UPDATE"

        /** Values accepted as "on" for the boolean opt-out variables. */
        private val TRUTHY = setOf("1", "true", "yes", "on")

        internal fun isTruthy(value: String?): Boolean = value?.trim()?.lowercase() in TRUTHY

        const val DEFAULT_REGION = "us"
        const val DEFAULT_TIMEOUT_MS = 60_000L

        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            val apiKey = env[ENV_API_KEY]?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException(
                    "Missing required environment variable $ENV_API_KEY. " +
                            "Create an Insight platform API key and expose it to the server.",
                )

            val region = Region.fromCode(env[ENV_REGION]?.takeIf { it.isNotBlank() } ?: DEFAULT_REGION)

            val baseUrl = (env[ENV_BASE_URL]?.takeIf { it.isNotBlank() }
                ?: "https://${region.code}.api.insight.rapid7.com")
                .trimEnd('/')

            val logSearchBaseUrl = (env[ENV_LOG_SEARCH_BASE_URL]?.takeIf { it.isNotBlank() }
                ?: "https://${region.code}.rest.logs.insight.rapid7.com")
                .trimEnd('/')

            val v1BaseUrl = (env[ENV_V1_BASE_URL]?.takeIf { it.isNotBlank() }
                ?: "https://${region.code}.rest.logs.insight.rapid7.com")
                .trimEnd('/')

            val timeout = env[ENV_TIMEOUT_MS]?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_TIMEOUT_MS

            val httpAllowedOrigins = env[ENV_HTTP_ALLOWED_ORIGINS]
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() && it != "*" }
                ?: emptyList()

            val updateCheckDisabled = isTruthy(env[ENV_DISABLE_UPDATE_CHECK])
            val autoUpdateDisabled = isTruthy(env[ENV_DISABLE_AUTO_UPDATE])

            return Config(
                apiKey = apiKey,
                region = region,
                baseUrl = baseUrl,
                requestTimeoutMillis = timeout,
                logSearchBaseUrl = logSearchBaseUrl,
                v1BaseUrl = v1BaseUrl,
                httpAllowedOrigins = httpAllowedOrigins,
                updateCheckDisabled = updateCheckDisabled,
                autoUpdateDisabled = autoUpdateDisabled,
            )
        }
    }
}
