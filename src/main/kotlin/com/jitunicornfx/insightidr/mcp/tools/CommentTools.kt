package com.jitunicornfx.insightidr.mcp.tools

import com.jitunicornfx.insightidr.mcp.*
import io.ktor.http.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Registers the InsightIDR API v1 Comments tools (comments attach to investigations and other targets). */
fun Server.registerCommentTools(client: Rapid7Client) {

    apiTool(
        name = "list_comments",
        description = "List comments for a target resource (e.g. an investigation RRN) (API v1).",
        readOnly = true,
        inputSchema = toolSchema("target") {
            stringParam("target", "RRN of the resource whose comments to list (e.g. an investigation RRN).")
            pagingParams("Page size (max 100). Defaults to 20.")
            stringParam("sortDirection", "Sort direction by creation time.", enum = listOf("ASC", "DESC"))
        },
    ) { args ->
        client.requestV1(
            HttpMethod.Get,
            "/idr/v1/comments",
            query = pagingQuery(args) + query(
                "target" to args.requireString("target"),
                "sortDirection" to args.stringOrNull("sortDirection"),
            ),
        ).toToolResult()
    }

    apiTool(
        name = "get_comment",
        description = "Get a single comment by its RRN (API v1).",
        readOnly = true,
        inputSchema = toolSchema("rrn") { stringParam("rrn", "The RRN of the comment.") },
    ) { args ->
        val rrn = args.requireString("rrn")
        client.requestV1(HttpMethod.Get, "/idr/v1/comments/${seg(rrn)}").toToolResult()
    }

    apiTool(
        name = "create_comment",
        description = "Create a comment on a target resource (e.g. an investigation) (API v1).",
        inputSchema = toolSchema("target") {
            stringParam("target", "RRN of the resource to comment on (e.g. an investigation RRN).")
            stringParam("body", "The comment text.")
            stringArrayParam("attachments", "Optional array of attachment RRNs to associate with the comment.")
        },
    ) { args ->
        val body = buildJsonObject {
            put("target", args.requireString("target"))
            putOpt("body", args.stringOrNull("body"))
            putOpt("attachments", args.arrayOrNull("attachments"))
        }
        client.requestV1(HttpMethod.Post, "/idr/v1/comments", jsonBody = body).toToolResult()
    }

    apiTool(
        name = "delete_comment",
        description = "Delete a comment by its RRN (API v1).",
        destructive = true,
        inputSchema = toolSchema("rrn") { stringParam("rrn", "The RRN of the comment to delete.") },
    ) { args ->
        val rrn = args.requireString("rrn")
        client.requestV1(HttpMethod.Delete, "/idr/v1/comments/${seg(rrn)}").toToolResult()
    }

    apiTool(
        name = "update_comment_visibility",
        description = "Update the visibility of a comment (API v1).",
        inputSchema = toolSchema("rrn", "visibility") {
            stringParam("rrn", "The RRN of the comment.")
            stringParam(
                "visibility",
                "The new visibility for the comment: INTERNAL or PUBLIC.",
                enum = listOf("INTERNAL", "PUBLIC")
            )
        },
    ) { args ->
        val rrn = args.requireString("rrn")
        val visibility = args.requireString("visibility")
        client.requestV1(HttpMethod.Put, "/idr/v1/comments/${seg(rrn)}/${seg(visibility)}").toToolResult()
    }
}
