package com.jitunicornfx.insightidr.mcp

/**
 * The parts of [Config] that are safe to describe to a model.
 *
 * A deliberate projection rather than the [Config] itself: Config carries the Insight API key, and
 * nothing in the tools layer should hold a reference to an object that has it. Config is also a
 * `data class`, so `copy()` and destructuring make accidental exposure easy, and its redacted
 * `toString()` guards only the `toString()` path. Narrowing here makes the key unreachable from a
 * tool handler by construction.
 *
 * Process-wide and installed once from Main, matching [ResultBudget] and [SpoolStore]: there is
 * exactly one Config per process — `--http` builds a fresh Server per connection but never a fresh
 * Config — so one set of facts per process is the right granularity.
 */
data class ServerFacts(
    val region: String,
    val baseUrl: String,
    val v1BaseUrl: String,
    val logSearchBaseUrl: String,
    val requestTimeoutMillis: Long,
    val maxResultChars: Int,
    /**
     * Whether `INSIGHTIDR_SPOOL_DIR` was set — deliberately NOT the resolved path. A spool directory
     * is an absolute host path that on both Windows and POSIX contains the OS user name
     * (`C:\Users\<name>\…`, `/home/<name>/…`): recon value and quasi-PII for anything reading the
     * model's context. The model never needs it — the spool tool reports the file it wrote.
     */
    val spoolDirectoryConfigured: Boolean,
    val spoolRetentionHours: Int,
    /** Whether `upload_attachment` is enabled at all. Not the directory, for the same reason as the spool's. */
    val uploadDirectoryConfigured: Boolean,
    /**
     * How many browser origins may call this server in `--http` mode — deliberately NOT the list.
     * Whether cross-origin browser access is permitted at all is the operational fact; the entries
     * themselves name internal hostnames.
     */
    val httpAllowedOriginCount: Int,
    val updateCheckDisabled: Boolean,
    val autoUpdateDisabled: Boolean,
) {
    companion object {
        /** Reported when nothing has been installed. Only reachable in tests; Main always installs. */
        val UNCONFIGURED = ServerFacts(
            region = "unconfigured",
            baseUrl = "",
            v1BaseUrl = "",
            logSearchBaseUrl = "",
            requestTimeoutMillis = 0,
            maxResultChars = 0,
            spoolDirectoryConfigured = false,
            spoolRetentionHours = 0,
            uploadDirectoryConfigured = false,
            httpAllowedOriginCount = 0,
            updateCheckDisabled = false,
            autoUpdateDisabled = false,
        )

        /** The one place Config is narrowed. The API key is deliberately not a field of the result. */
        fun from(config: Config): ServerFacts = ServerFacts(
            region = config.region.code,
            baseUrl = config.baseUrl,
            v1BaseUrl = config.v1BaseUrl,
            logSearchBaseUrl = config.logSearchBaseUrl,
            requestTimeoutMillis = config.requestTimeoutMillis,
            maxResultChars = config.maxResultChars,
            spoolDirectoryConfigured = config.spoolDirectory != null,
            spoolRetentionHours = config.spoolRetentionHours,
            uploadDirectoryConfigured = config.uploadDirectory != null,
            httpAllowedOriginCount = config.httpAllowedOrigins.size,
            updateCheckDisabled = config.updateCheckDisabled,
            autoUpdateDisabled = config.autoUpdateDisabled,
        )

        @Volatile
        private var installed: ServerFacts = UNCONFIGURED

        /** The process-wide facts. */
        val active: ServerFacts get() = installed

        /** Install the configured facts. Called once from Main before anything is served. */
        fun install(facts: ServerFacts) {
            installed = facts
        }
    }
}
