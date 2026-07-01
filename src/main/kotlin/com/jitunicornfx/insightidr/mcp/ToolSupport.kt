package com.jitunicornfx.insightidr.mcp

import com.jitunicornfx.insightidr.mcp.Rapid7Client.ApiResponse
import io.ktor.http.encodeURLPathPart
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Shared JSON encoder/decoder instances. */
object JsonCodec {
    val pretty: Json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = false
    }
    val compact: Json = Json {
        encodeDefaults = false
    }
}

// ---------------------------------------------------------------------------
// Tool input-schema DSL
// ---------------------------------------------------------------------------

/**
 * Build a JSON-Schema object for a tool's input.
 *
 * @param required names of required properties.
 * @param props builder that declares the object's properties.
 */
fun toolSchema(vararg required: String, props: JsonObjectBuilder.() -> Unit): ToolSchema =
    ToolSchema(
        properties = buildJsonObject(props),
        required = if (required.isEmpty()) null else required.toList(),
    )

/** An empty input schema, for tools that take no arguments. */
fun emptySchema(): ToolSchema = ToolSchema(properties = buildJsonObject {})

fun JsonObjectBuilder.stringParam(name: String, description: String, enum: List<String>? = null) {
    putJsonObject(name) {
        put("type", "string")
        put("description", description)
        if (enum != null) putJsonArray("enum") { enum.forEach { add(it) } }
    }
}

fun JsonObjectBuilder.integerParam(name: String, description: String) {
    putJsonObject(name) {
        put("type", "integer")
        put("description", description)
    }
}

fun JsonObjectBuilder.booleanParam(name: String, description: String) {
    putJsonObject(name) {
        put("type", "boolean")
        put("description", description)
    }
}

fun JsonObjectBuilder.stringArrayParam(name: String, description: String) {
    putJsonObject(name) {
        put("type", "array")
        put("description", description)
        putJsonObject("items") { put("type", "string") }
    }
}

/** An array of free-form JSON objects (e.g. search / sort criteria). */
fun JsonObjectBuilder.objectArrayParam(name: String, description: String) {
    putJsonObject(name) {
        put("type", "array")
        put("description", description)
        putJsonObject("items") { put("type", "object") }
    }
}

/** A free-form JSON object parameter. */
fun JsonObjectBuilder.objectParam(name: String, description: String) {
    putJsonObject(name) {
        put("type", "object")
        put("description", description)
    }
}

// ---------------------------------------------------------------------------
// Argument accessors (over the tool-call arguments JsonObject)
// ---------------------------------------------------------------------------

private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

fun JsonObject.stringOrNull(key: String): String? = primitive(key)?.contentOrNull

fun JsonObject.requireString(key: String): String =
    stringOrNull(key)?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Missing required parameter '$key'")

fun JsonObject.intOrNull(key: String): Int? =
    primitive(key)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

fun JsonObject.longOrNull(key: String): Long? =
    primitive(key)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }

fun JsonObject.booleanOrNull(key: String): Boolean? =
    primitive(key)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

fun JsonObject.arrayOrNull(key: String): JsonArray? = this[key] as? JsonArray

fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.elementOrNull(key: String): JsonElement? = this[key]?.takeUnless { it is JsonNull }

// ---------------------------------------------------------------------------
// JSON body building helpers
// ---------------------------------------------------------------------------

fun JsonObjectBuilder.putOpt(key: String, value: String?) {
    if (value != null) put(key, value)
}

fun JsonObjectBuilder.putOpt(key: String, value: Int?) {
    if (value != null) put(key, value)
}

fun JsonObjectBuilder.putOpt(key: String, value: Long?) {
    if (value != null) put(key, value)
}

fun JsonObjectBuilder.putOpt(key: String, value: Boolean?) {
    if (value != null) put(key, value)
}

fun JsonObjectBuilder.putOpt(key: String, value: JsonElement?) {
    if (value != null) put(key, value)
}

// ---------------------------------------------------------------------------
// Query-parameter building
// ---------------------------------------------------------------------------

/**
 * Build a query-parameter map from name/value pairs. Null values are omitted; list
 * values expand into repeated parameters. Everything else is stringified.
 */
fun query(vararg pairs: Pair<String, Any?>): Map<String, List<String>> {
    val out = LinkedHashMap<String, MutableList<String>>()
    for ((key, value) in pairs) {
        when (value) {
            null -> {}
            is List<*> -> value.filterNotNull().forEach { out.getOrPut(key) { mutableListOf() }.add(it.toString()) }
            else -> out.getOrPut(key) { mutableListOf() }.add(value.toString())
        }
    }
    return out
}

/** Standard `index` / `size` pagination query parameters. */
fun pagingQuery(args: JsonObject): Map<String, List<String>> =
    query("index" to args.intOrNull("index"), "size" to args.intOrNull("size"))

/** URL-encode a value for safe inclusion as a path segment (e.g. an RRN or id). */
fun seg(value: String): String = value.encodeURLPathPart()

// ---------------------------------------------------------------------------
// Result formatting
// ---------------------------------------------------------------------------

/** Pretty-print [raw] if it is valid JSON, otherwise return it unchanged. */
private fun prettyOrRaw(raw: String): String {
    if (raw.isBlank()) return raw
    return try {
        val element = JsonCodec.pretty.parseToJsonElement(raw)
        JsonCodec.pretty.encodeToString(JsonElement.serializer(), element)
    } catch (_: Exception) {
        raw
    }
}

/** Convert an API response into a tool result, marking non-2xx responses as errors. */
fun ApiResponse.toToolResult(): CallToolResult {
    val rendered = prettyOrRaw(body)
    val text = if (ok) {
        rendered.ifBlank { "Success (HTTP $status, empty response body)." }
    } else {
        "InsightIDR API returned HTTP $status.\n${rendered.ifBlank { "(empty response body)" }}"
    }
    return CallToolResult(content = listOf(TextContent(text)), isError = !ok)
}

fun errorResult(message: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(message)), isError = true)

fun textResult(message: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(message)))

// ---------------------------------------------------------------------------
// Tool registration
// ---------------------------------------------------------------------------

/**
 * Register a tool with centralized argument extraction and error handling.
 * The [handler] receives the (possibly empty) arguments object and returns a result;
 * thrown exceptions are converted into error results so a single failing call never
 * tears down the session.
 */
fun Server.apiTool(
    name: String,
    description: String,
    inputSchema: ToolSchema = emptySchema(),
    readOnly: Boolean = false,
    destructive: Boolean = false,
    handler: suspend (args: JsonObject) -> CallToolResult,
) {
    addTool(
        name = name,
        description = description,
        inputSchema = inputSchema,
        toolAnnotations = ToolAnnotations(
            readOnlyHint = readOnly,
            destructiveHint = if (readOnly) null else destructive,
            openWorldHint = true,
        ),
    ) { request ->
        try {
            handler(request.arguments ?: JsonObject(emptyMap()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            errorResult("Invalid arguments for '$name': ${e.message}")
        } catch (e: Exception) {
            errorResult("Tool '$name' failed: ${e.message}")
        }
    }
}
