package com.jitunicornfx.insightidr.mcp.tools

import com.jitunicornfx.insightidr.mcp.*
import io.ktor.http.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import java.io.File

/** Maximum size of a local file that [upload_attachment] will read into memory (100 MiB). */
internal const val MAX_UPLOAD_BYTES: Long = 100L * 1024 * 1024

/**
 * Reject UNC / remote / device-namespace paths for a local-file upload.
 *
 * `file_path` is model-supplied and, under prompt injection, attacker-influenceable. On Windows,
 * handing `File` a UNC path such as `\\attacker\share\x` triggers an outbound SMB connection during
 * `isFile`/`readBytes`, leaking the host's NetNTLM credentials to the attacker's SMB server — a
 * forced-authentication primitive that needs no Rapid7 access. Any path beginning with two path
 * separators is UNC (`\\host\share`, `//host/share`) or the Windows device namespace (`\\?\`, `\\.\`),
 * none of which is a legitimate local file to attach, so it is refused.
 */
internal fun requireLocalFilePath(path: String) {
    val trimmed = path.trimStart()
    require(!(trimmed.startsWith("\\\\") || trimmed.startsWith("//"))) {
        "Refusing to read a UNC/remote path ('$path'). Provide a path to a local file on the server host."
    }
}

/** Registers the InsightIDR API v1 Attachments tools. */
fun Server.registerAttachmentTools(client: Rapid7Client) {

    apiTool(
        name = "list_attachments",
        description = "List attachments for a target resource (e.g. an investigation RRN) (API v1).",
        readOnly = true,
        inputSchema = toolSchema("target") {
            stringParam("target", "RRN of the resource whose attachments to list.")
            integerParam("index", "Zero-based page index.")
            integerParam("size", "Page size (max 100). Defaults to 20.")
        },
    ) { args ->
        client.requestV1(
            HttpMethod.Get,
            "/idr/v1/attachments",
            query = query(
                "target" to args.requireString("target"),
                "index" to args.intOrNull("index"),
                "size" to args.intOrNull("size"),
            ),
        ).toToolResult()
    }

    apiTool(
        name = "get_attachment_metadata",
        description = "Get metadata (name, size, type, associations) for an attachment by its RRN (API v1).",
        readOnly = true,
        inputSchema = toolSchema("rrn") { stringParam("rrn", "The RRN of the attachment.") },
    ) { args ->
        val rrn = args.requireString("rrn")
        client.requestV1(HttpMethod.Get, "/idr/v1/attachments/${seg(rrn)}/metadata").toToolResult()
    }

    apiTool(
        name = "download_attachment",
        description = "Download the content of an attachment by its RRN (API v1). Returns the raw response body; " +
                "binary attachments may not render as readable text — prefer 'get_attachment_metadata' to inspect them.",
        readOnly = true,
        inputSchema = toolSchema("rrn") { stringParam("rrn", "The RRN of the attachment to download.") },
    ) { args ->
        val rrn = args.requireString("rrn")
        client.requestV1(HttpMethod.Get, "/idr/v1/attachments/${seg(rrn)}").toToolResult()
    }

    apiTool(
        name = "delete_attachment",
        description = "Delete an attachment by its RRN (API v1).",
        destructive = true,
        inputSchema = toolSchema("rrn") { stringParam("rrn", "The RRN of the attachment to delete.") },
    ) { args ->
        val rrn = args.requireString("rrn")
        client.requestV1(HttpMethod.Delete, "/idr/v1/attachments/${seg(rrn)}").toToolResult()
    }

    apiTool(
        name = "upload_attachment",
        description = "Upload a local file as an attachment (API v1). Provide the absolute path to a file on the " +
                "machine running this server; its bytes are read and sent to Rapid7. Only local files are allowed " +
                "(UNC/remote paths are refused) and the file must be under " +
                "${MAX_UPLOAD_BYTES / (1024 * 1024)} MiB. The returned attachment RRN can then be attached to a comment.",
        inputSchema = toolSchema("file_path") {
            stringParam("file_path", "Absolute path to a local file to upload.")
            stringParam("filename", "Optional name to store the attachment under; defaults to the file's name.")
        },
    ) { args ->
        val path = args.requireString("file_path")
        requireLocalFilePath(path)
        val file = File(path)
        require(file.isFile) { "File not found or not a regular file: $path" }
        val size = file.length()
        require(size <= MAX_UPLOAD_BYTES) {
            "File exceeds the ${MAX_UPLOAD_BYTES / (1024 * 1024)} MiB upload limit ($size bytes): $path"
        }
        val fileName = args.stringOrNull("filename")?.takeIf { it.isNotBlank() } ?: file.name
        client.uploadFile(
            "/idr/v1/attachments",
            fileName = fileName,
            bytes = file.readBytes(),
            base = Rapid7Client.ApiBase.IDR_V1,
        ).toToolResult()
    }
}
