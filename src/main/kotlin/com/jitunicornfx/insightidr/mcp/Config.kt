package com.jitunicornfx.insightidr.mcp

/**
 * Insight platform regional data centers.
 *
 * The InsightIDR REST API (both v1 and v2) is served from a region-specific host of the form
 * `https://<region>.api.insight.rapid7.com`. The region code is the prefix of your Insight
 * platform URL (e.g. `us` in `us.idr.insight.rapid7.com`).
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
    val baseUrl: String,
    val requestTimeoutMillis: Long,
) {
    /** The API key is a secret; never include it in [toString] output or logs. */
    override fun toString(): String =
        "Config(region=${region.code}, baseUrl=$baseUrl, requestTimeoutMillis=$requestTimeoutMillis, apiKey=***)"

    companion object {
        const val ENV_API_KEY = "INSIGHTIDR_API_KEY"
        const val ENV_REGION = "INSIGHTIDR_REGION"
        const val ENV_BASE_URL = "INSIGHTIDR_BASE_URL"
        const val ENV_TIMEOUT_MS = "INSIGHTIDR_TIMEOUT_MS"

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

            val timeout = env[ENV_TIMEOUT_MS]?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_TIMEOUT_MS

            return Config(apiKey = apiKey, region = region, baseUrl = baseUrl, requestTimeoutMillis = timeout)
        }
    }
}
