package com.jitunicornfx.insightidr.mcp.tools

import com.jitunicornfx.insightidr.mcp.*
import io.ktor.http.*
import io.modelcontextprotocol.kotlin.sdk.server.Server

/**
 * Registers the InsightIDR API v1 Attachments tools.
 *
 * [uploads] is defaulted so production picks up the process-wide policy installed at startup, while
 * tests inject a temporary directory.
 */
fun Server.registerAttachmentTools(client: Rapid7Client, uploads: UploadPolicy = UploadPolicy.active) {

    apiTool(
        name = "list_attachments",
        description = "List attachments for a target resource (e.g. an investigation RRN) (API v1).",
        readOnly = true,
        inputSchema = toolSchema("target") {
            stringParam("target", "RRN of the resource whose attachments to list.")
            pagingParams("Page size (max 100). Defaults to 20.", maxSize = 100, defaultSize = 20)
        },
    ) { args ->
        client.requestV1(
            HttpMethod.Get,
            "/idr/v1/attachments",
            query = pagingQuery(args) + query("target" to args.requireString("target")),
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
        // Stays registered when disabled, so the model gets an explanation rather than a missing tool
        // it might try to work around.
        description = "Upload a file from the machine running this server as an attachment (API v1). DISABLED " +
            "unless the operator has set ${Config.ENV_UPLOAD_DIR}; when it is set, only files INSIDE that " +
            "directory can be uploaded, and 'file_path' may be given relative to it. Files must be under " +
            "${UploadPolicy.DEFAULT_MAX_BYTES / (1024 * 1024)} MiB. The returned attachment RRN can then be " +
            "attached to a comment.",
        inputSchema = toolSchema("file_path") {
            stringParam("file_path", "Path of the file to upload, inside the server's upload directory (absolute, or relative to it).")
            stringParam("filename", "Optional name to store the attachment under; defaults to the file's name.")
        },
    ) { args ->
        // Checked first, and answered without touching the filesystem.
        if (!uploads.enabled) return@apiTool errorResult(UploadPolicy.DISABLED_MESSAGE)
        val file = uploads.resolve(args.requireString("file_path"))
        val fileName = args.stringOrNull("filename")?.takeIf { it.isNotBlank() } ?: file.fileName.toString()
        client.uploadFile(
            "/idr/v1/attachments",
            fileName = fileName,
            bytes = uploads.readCapped(file),
            base = Rapid7Client.ApiBase.IDR_V1,
        ).toToolResult()
    }
}
