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
    ): ApiResponse {
        val response = http.request(config.baseUrl + path) {
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
