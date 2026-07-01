package com.jitunicornfx.insightidr.mcp.tools

import com.jitunicornfx.insightidr.mcp.Rapid7Client
import com.jitunicornfx.insightidr.mcp.apiTool
import com.jitunicornfx.insightidr.mcp.intOrNull
import com.jitunicornfx.insightidr.mcp.query
import com.jitunicornfx.insightidr.mcp.requireString
import com.jitunicornfx.insightidr.mcp.seg
import com.jitunicornfx.insightidr.mcp.stringOrNull
import com.jitunicornfx.insightidr.mcp.integerParam
import com.jitunicornfx.insightidr.mcp.stringParam
import com.jitunicornfx.insightidr.mcp.toToolResult
import com.jitunicornfx.insightidr.mcp.toolSchema
import io.ktor.http.HttpMethod
import io.modelcontextprotocol.kotlin.sdk.server.Server
import java.io.File

/** Registers the InsightIDR v1 Attachments tools. */
fun Server.registerAttachmentTools(client: Rapid7Client) {

    apiTool(
        name = "list_attachments",
        description = "List attachments for a target resource (e.g. an investigation RRN) (API v1).",
        readOnly = true,
        inputSchema = toolSchema("target") {
            stringParam("target", "RRN of the resource whose attachments to list.")
            integerParam("index", "Zero-based page index.")
            integerParam("size", "Page size.")
        },
    ) { args ->
        client.request(
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
        client.request(HttpMethod.Get, "/idr/v1/attachments/${seg(rrn)}/metadata").toToolResult()
    }

    apiTool(
        name = "download_attachment",
        description = "Download the content of an attachment by its RRN (API v1). Returns the raw response body; " +
            "binary attachments may not render as readable text — prefer 'get_attachment_metadata' to inspect them.",
        readOnly = true,
        inputSchema = toolSchema("rrn") { stringParam("rrn", "The RRN of the attachment to download.") },
    ) { args ->
        val rrn = args.requireString("rrn")
        client.request(HttpMethod.Get, "/idr/v1/attachments/${seg(rrn)}").toToolResult()
    }

    apiTool(
        name = "delete_attachment",
        description = "Delete an attachment by its RRN (API v1).",
        destructive = true,
        inputSchema = toolSchema("rrn") { stringParam("rrn", "The RRN of the attachment to delete.") },
    ) { args ->
        val rrn = args.requireString("rrn")
        client.request(HttpMethod.Delete, "/idr/v1/attachments/${seg(rrn)}").toToolResult()
    }

    apiTool(
        name = "upload_attachment",
        description = "Upload a local file as an attachment (API v1). Provide the absolute path to a file on the " +
            "machine running this server. The returned attachment RRN can then be attached to a comment.",
        inputSchema = toolSchema("file_path") {
            stringParam("file_path", "Absolute path to a local file to upload.")
            stringParam("filename", "Optional name to store the attachment under; defaults to the file's name.")
        },
    ) { args ->
        val path = args.requireString("file_path")
        val file = File(path)
        if (!file.isFile) throw IllegalArgumentException("File not found or not a regular file: $path")
        val fileName = args.stringOrNull("filename")?.takeIf { it.isNotBlank() } ?: file.name
        client.uploadFile("/idr/v1/attachments", fileName = fileName, bytes = file.readBytes()).toToolResult()
    }
}
