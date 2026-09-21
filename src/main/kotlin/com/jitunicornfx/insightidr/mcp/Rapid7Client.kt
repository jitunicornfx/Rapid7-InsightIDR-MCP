package com.jitunicornfx.insightidr.mcp

import io.ktor.client.*
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.JsonElement
import java.io.ByteArrayOutputStream

/**
 * Thin HTTP wrapper around the InsightIDR REST API.
 *
 * Responsibilities:
 *  - attaches the `X-Api-Key` authentication header to every request,
 *  - resolves relative paths against the configured regional base URL,
 *  - never throws on non-2xx responses (they are surfaced to the caller as [ApiResponse]),
 *    so tool handlers can report API errors back to the model instead of crashing.
 */
class Rapid7Client(
    private val config: Config,
    engine: HttpClientEngine? = null,
    /**
     * Most bytes of any one response that are read. The rest is abandoned, and the result says so.
     *
     * A response used to be buffered whole. `logsearch_download_log_data` with no `limit` asks for up
     * to 500,000,000 entries, it is a read-only tool that many clients approve without asking, and a
     * prompt injection can ask for it: hundreds of megabytes arrive inside the timeout, and the
     * OutOfMemoryError takes down every session of an `--http` server, not just the one that asked.
     * A tool result is cut to the response budget (200,000 characters by default) anyway, so nothing
     * a model could ever be shown is lost by stopping at 64 MiB.
     */
    private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
) : AutoCloseable {

    init {
        // Config.fromEnv already refuses this. Checked again here because a Config can be built
        // directly, and what happens otherwise is that Ktor or OkHttp rejects the header at the first
        // request with an exception that QUOTES the key. Fixed text; the key is never repeated.
        require(config.apiKey.isNotEmpty() && config.apiKey.all { it.code in 0x21..0x7E }) {
            "The configured InsightIDR API key cannot be sent as an HTTP header (it is empty, or contains a " +
                "space, a line break or a non-ASCII character)."
        }
    }

    // Production uses the CIO engine; tests inject a MockEngine to avoid real network calls.
    private val http: HttpClient = if (engine != null) {
        HttpClient(engine) { configureClient() }
    } else {
        HttpClient(OkHttp) { configureClient() }
    }

    private fun HttpClientConfig<*>.configureClient() {
        expectSuccess = false
        // Never auto-follow redirects: every request carries the secret X-Api-Key, and a 3xx from an
        // allow-listed host could otherwise bounce that credential to an arbitrary Location. The API
        // returns data directly; continuations are followed explicitly via the validated allow-list.
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = config.requestTimeoutMillis
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = config.requestTimeoutMillis
        }
    }

    /** Raw HTTP result. [ok] is true for 2xx status codes. */
    data class ApiResponse(
        val status: Int,
        val ok: Boolean,
        val body: String,
        val contentType: String?,
        /**
         * Something THIS SERVER wants to tell the model about the response: never API content. It is
         * rendered after the untrusted envelope, in the server's own voice, so a helper that knows
         * why a request failed can say so without touching [body].
         */
        val serverNote: String? = null,
    )

    /**
     * Which API family a request targets; each resolves to its own base URL
     * ([IDR_V2] and [IDR_V1] -> `api.insight`, which serves both IDR APIs;
     * [LOG_SEARCH] -> the configured Log Search route, `rest.logs.insight` by default).
     */
    enum class ApiBase { IDR_V2, IDR_V1, LOG_SEARCH }

    private fun baseUrlFor(base: ApiBase): String = when (base) {
        ApiBase.IDR_V2 -> config.baseUrl
        ApiBase.IDR_V1 -> config.v1BaseUrl
        ApiBase.LOG_SEARCH -> config.logSearchBaseUrl
    }

    /** Normalize a URL to a `scheme://host:port` origin (lowercased), or null if it can't be parsed. */
    private fun originOf(url: String): String? =
        runCatching { Url(url) }.getOrNull()?.takeIf { it.host.isNotEmpty() }?.let {
            "${it.protocol.name}://${it.host.lowercase()}:${it.port}"
        }

    /** Origins (scheme+host+port) of the configured API bases; continuation links may target these exactly. */
    private val allowedFollowOrigins: Set<String> =
        listOfNotNull(originOf(config.baseUrl), originOf(config.v1BaseUrl), originOf(config.logSearchBaseUrl)).toSet()

    /**
     * The two hosts Rapid7 serves this tenant's region from, per the API specs' `servers` blocks:
     * `<region>.api.insight.rapid7.com` (IDR v1/v2) and `<region>.rest.logs.insight.rapid7.com`
     * (Log Search). Always HTTPS on 443.
     */
    private val canonicalRegionHosts: Set<String> = setOf(
        "${config.region.code}.api.insight.rapid7.com",
        "${config.region.code}.rest.logs.insight.rapid7.com",
    )

    /**
     * Whether an API-provided (or model-provided) URL may be followed with the API key attached.
     *
     * Validation is on the PARSED URL — never a string prefix — so tricks like
     * `https://us.api.insight.rapid7.com.evil.com/...` (prefix match) or
     * `https://us.api.insight.rapid7.com@evil.com/...` (userinfo) resolve to a foreign host and are
     * rejected. A URL is allowed only if:
     *
     *  - its scheme+host+port exactly matches a configured base (whatever scheme the operator chose,
     *    which is what supports a local `http://` test override), or
     *  - it is HTTPS, on port 443, on one of [canonicalRegionHosts].
     *
     * This used to accept any `*.rapid7.com` host on any port. `next_link` and `resume_from_next_link`
     * are model-supplied, so that made every name under rapid7.com — marketing sites, support
     * portals, a forgotten CNAME pointing at a deprovisioned cloud bucket — somewhere a prompt
     * injection could have the API key delivered to. Rapid7 returns continuation links on the host
     * that served the request, so nothing legitimate needs more than the hosts above.
     *
     * A cleartext `http://` link to a real Rapid7 host is refused, so the `X-Api-Key` is never sent
     * over an unencrypted or downgraded connection, and a URL carrying userinfo is refused outright.
     */
    internal fun isAllowedFollowUrl(url: String): Boolean {
        val parsed = runCatching { Url(url) }.getOrNull() ?: return false
        val host = parsed.host.lowercase()
        if (host.isEmpty()) return false
        if (parsed.user != null || parsed.password != null) return false
        if (originOf(url) in allowedFollowOrigins) return true
        return parsed.protocol == URLProtocol.HTTPS && parsed.port == HTTPS_PORT && host in canonicalRegionHosts
    }

    /**
     * Perform a request against the InsightIDR API.
     *
     * @param method HTTP method.
     * @param path path beginning with `/` (e.g. `/idr/v2/investigations`).
     * @param query query parameters; entries with an empty value list are omitted.
     * @param jsonBody optional JSON body (takes precedence over [rawBody]).
     * @param rawBody optional raw string body, sent with [rawContentType].
     * @param rawContentType content type for [rawBody]; defaults to `application/json`.
     */
    suspend fun request(
        method: HttpMethod,
        path: String,
        query: Map<String, List<String>> = emptyMap(),
        jsonBody: JsonElement? = null,
        rawBody: String? = null,
        rawContentType: ContentType = ContentType.Application.Json,
        base: ApiBase = ApiBase.IDR_V2,
    ): ApiResponse {
        return http.prepareRequest(baseUrlFor(base) + path) {
            this.method = method
            header("X-Api-Key", config.apiKey)
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            url {
                query.forEach { (key, values) ->
                    values.forEach { value -> parameters.append(key, value) }
                }
            }
            when {
                jsonBody != null -> {
                    contentType(ContentType.Application.Json)
                    setBody(JsonCodec.compact.encodeToString(JsonElement.serializer(), jsonBody))
                }

                rawBody != null -> {
                    contentType(rawContentType)
                    setBody(rawBody)
                }
            }
        }.execute { it.toApiResponse() }
    }

    /** [request] against the v1 API base (`https://<region>.api.insight.rapid7.com`, same host as v2). */
    suspend fun requestV1(
        method: HttpMethod,
        path: String,
        query: Map<String, List<String>> = emptyMap(),
        jsonBody: JsonElement? = null,
        rawBody: String? = null,
        rawContentType: ContentType = ContentType.Application.Json,
    ): ApiResponse = request(method, path, query, jsonBody, rawBody, rawContentType, base = ApiBase.IDR_V1)

    /**
     * GET an absolute URL returned by the API itself — used to follow the `links[].href`
     * continuation / next-page URLs that Log Search queries return.
     *
     * The URL must be on a configured API base or one of this region's two canonical Rapid7 hosts
     * ([isAllowedFollowUrl]); otherwise the request is refused so the `X-Api-Key` credential
     * can never be sent to an attacker-controlled host embedded in a response body or argument.
     */
    suspend fun requestAbsolute(url: String): ApiResponse {
        // The URL is deliberately NOT in the message. It reaches the model as server-authored error
        // text, outside the untrusted envelope, and the URL is often API-provided.
        require(isAllowedFollowUrl(url)) { REFUSED_FOLLOW_URL }

        return http.prepareRequest(url) {
            method = HttpMethod.Get
            header("X-Api-Key", config.apiKey)
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
        }.execute { it.toApiResponse() }
    }

    /**
     * Upload one file as a multipart/form-data attachment (`filedata` part).
     * Used by the Attachments API, which expects file content rather than JSON.
     */
    suspend fun uploadFile(
        path: String,
        fileName: String,
        bytes: ByteArray,
        query: Map<String, List<String>> = emptyMap(),
        base: ApiBase = ApiBase.IDR_V2,
    ): ApiResponse {
        return http.prepareRequest(baseUrlFor(base) + path) {
            this.method = HttpMethod.Post
            header("X-Api-Key", config.apiKey)
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            url {
                query.forEach { (key, values) -> values.forEach { parameters.append(key, it) } }
            }
            // Strip characters that could break out of the quoted filename or inject headers.
            // Ktor auto-adds `Content-Disposition: form-data; name="filedata"`; we append the filename.
            val safeFileName = fileName.filter { it != '"' && it != '\r' && it != '\n' }.ifBlank { "attachment" }
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "filedata",
                            bytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                                append(HttpHeaders.ContentDisposition, "filename=\"$safeFileName\"")
                            },
                        )
                    },
                ),
            )
        }.execute { it.toApiResponse() }
    }

    /**
     * Read this response, up to [maxResponseBytes] of it. Called inside `execute { }`, so the body is
     * streamed from the socket rather than buffered first - which is what makes the limit a limit.
     */
    private suspend fun HttpResponse.toApiResponse(): ApiResponse {
        val channel = bodyAsChannel()
        val collected = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var cutOff = false
        while (true) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read == -1) break
            if (read == 0) continue
            val room = maxResponseBytes - collected.size()
            if (read > room) {
                collected.write(buffer, 0, room.toInt())
                cutOff = true
                break
            }
            collected.write(buffer, 0, read)
        }
        if (cutOff) channel.cancel()

        // Ktor throws on a Content-Type it cannot parse, quoting the header in the message. A
        // malformed header must not cost the caller the response - nor put API-chosen text into an
        // error message - so both uses of it are guarded, and the fallback is UTF-8.
        val charset = runCatching { charset() }.getOrNull() ?: Charsets.UTF_8
        return ApiResponse(
            status = status.value,
            ok = status.value in 200..299,
            body = String(collected.toByteArray(), charset),
            contentType = runCatching { contentType()?.toString() }.getOrNull(),
            serverNote = if (!cutOff) null else
                "The API's response was larger than ${maxResponseBytes / (1024 * 1024)} MiB and was cut off " +
                    "there, before rendering; what is shown is only its beginning, and is not complete " +
                    "JSON. Ask for less: a shorter time window, a smaller 'limit' or page size, or a filter.",
        )
    }

    override fun close() = http.close()

    companion object {
        private const val HTTPS_PORT = 443

        /** 64 MiB. See [maxResponseBytes]. */
        const val DEFAULT_MAX_RESPONSE_BYTES = 64L * 1024 * 1024

        /** Fixed text: see [requestAbsolute]. */
        const val REFUSED_FOLLOW_URL =
            "Refusing to follow a URL that is not on a configured Rapid7 API host. Links must come " +
                "unmodified from a previous InsightIDR response."
    }
}
