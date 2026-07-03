package com.jitunicornfx.insightidr.mcp

import io.ktor.client.*
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.*
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
        HttpClient(CIO) { configureClient() }
    }

    private fun HttpClientConfig<*>.configureClient() {
        expectSuccess = false
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

    /** Which API family a request targets; each resolves to its own base URL. */
    enum class ApiBase { IDR, LOG_SEARCH }

    private fun baseUrlFor(base: ApiBase): String = when (base) {
        ApiBase.IDR -> config.baseUrl
        ApiBase.LOG_SEARCH -> config.logSearchBaseUrl
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
        base: ApiBase = ApiBase.IDR,
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

    /**
     * GET an absolute URL returned by the API itself — used to follow the `links[].href`
     * continuation URLs that Log Search queries return with HTTP 202.
     *
     * As a safety measure the URL must resolve back to one of the configured API bases or a
     * `*.rapid7.com` host, so a crafted response body can't redirect requests elsewhere.
     */
    suspend fun requestAbsolute(url: String): ApiResponse {
        val allowed = url.startsWith(config.baseUrl) ||
            url.startsWith(config.logSearchBaseUrl) ||
            runCatching { Url(url) }.getOrNull()?.let {
                it.protocol == URLProtocol.HTTPS && it.host.endsWith(".rapid7.com")
            } == true
        require(allowed) { "Refusing to follow non-Rapid7 continuation URL: $url" }

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
    ): ApiResponse {
        val response = http.request(config.baseUrl + path) {
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
        val text = bodyAsText()
        return ApiResponse(
            status = status.value,
            ok = status.value in 200..299,
            body = text,
            contentType = contentType()?.toString(),
        )
    }

    override fun close() = http.close()
}
