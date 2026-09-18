package com.jitunicornfx.insightidr.mcp

import io.ktor.client.*
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.JsonElement

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
) : AutoCloseable {

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
     * Whether an API-provided (or model-provided) URL may be followed with the API key attached.
     *
     * Validation is on the PARSED origin — never a string prefix of the URL — so tricks like
     * `https://us.api.insight.rapid7.com.evil.com/...` (prefix match) or
     * `https://us.api.insight.rapid7.com@evil.com/...` (userinfo) resolve to a foreign host and are
     * rejected. A URL is allowed only if its scheme+host+port exactly matches a configured base
     * (whatever scheme the operator chose — supporting local http test overrides), or it is HTTPS on
     * a `rapid7.com` host. A cleartext `http://` link to a real Rapid7 host is refused, so the
     * `X-Api-Key` is never sent over an unencrypted or downgraded connection to a discovered host.
     */
    internal fun isAllowedFollowUrl(url: String): Boolean {
        val parsed = runCatching { Url(url) }.getOrNull() ?: return false
        val host = parsed.host.lowercase()
        if (host.isEmpty()) return false
        if (originOf(url) in allowedFollowOrigins) return true
        return parsed.protocol == URLProtocol.HTTPS && (host == "rapid7.com" || host.endsWith(".rapid7.com"))
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
        val response = http.request(baseUrlFor(base) + path) {
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
        }
        return response.toApiResponse()
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
     * The URL's parsed host must be a configured API base host or a `rapid7.com` host
     * ([isAllowedFollowUrl]); otherwise the request is refused so the `X-Api-Key` credential
     * can never be sent to an attacker-controlled host embedded in a response body or argument.
     */
    suspend fun requestAbsolute(url: String): ApiResponse {
        // The URL is deliberately NOT in the message. It reaches the model as server-authored error
        // text, outside the untrusted envelope, and the URL is often API-provided.
        require(isAllowedFollowUrl(url)) { REFUSED_FOLLOW_URL }

        val response = http.request(url) {
            method = HttpMethod.Get
            header("X-Api-Key", config.apiKey)
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
        }
        return response.toApiResponse()
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
        val response = http.request(baseUrlFor(base) + path) {
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
        }
        return response.toApiResponse()
    }

    // 2xx responses are valid
    private suspend fun HttpResponse.toApiResponse(): ApiResponse {
        // bodyAsText() parses the Content-Type to find a charset, and throws on one it cannot parse
        // before reading a byte — so the body is still there to be read as UTF-8.
        val text = try {
            bodyAsText()
        } catch (e: BadContentTypeFormatException) {
            readRawBytes().decodeToString()
        }
        return ApiResponse(
            status = status.value,
            ok = status.value in 200..299,
            body = text,
            // Ktor throws on a Content-Type it cannot parse, quoting the header in the message. A
            // malformed header must not cost the caller the response — nor put API-chosen text into
            // an error message.
            contentType = runCatching { contentType()?.toString() }.getOrNull(),
        )
    }

    override fun close() = http.close()

    companion object {
        /** Fixed text: see [requestAbsolute]. */
        const val REFUSED_FOLLOW_URL =
            "Refusing to follow a URL that is not on a configured Rapid7 API host. Links must come " +
                "unmodified from a previous InsightIDR response."
    }
}
