package com.jitunicornfx.insightidr.mcp.tools

import com.jitunicornfx.insightidr.mcp.*
import io.ktor.http.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.buildJsonObject

/** Input schema shared by all entity `_search` endpoints (accounts, assets, users, local accounts). */
private fun searchSchema() = toolSchema {
    objectArrayParam(
        "search",
        "Array of criteria objects: { \"field\": string, \"operator\": \"EQUALS\"|\"CONTAINS\"|\"IN\", \"value\": any }.",
    )
    objectArrayParam("sort", "Array of sort objects: { \"field\": string, \"order\": \"ASC\"|\"DESC\" }.")
    integerParam("index", "Zero-based page index.")
    integerParam("size", "Page size.")
}

private fun Server.searchTool(client: Rapid7Client, name: String, description: String, path: String) {
    apiTool(name = name, description = description, readOnly = true, inputSchema = searchSchema()) { args ->
        val body = buildJsonObject {
            putOpt("search", args.arrayOrNull("search"))
            putOpt("sort", args.arrayOrNull("sort"))
        }
        client.request(HttpMethod.Post, path, query = pagingQuery(args), jsonBody = body).toToolResult()
    }
}

private fun Server.getByRrnTool(client: Rapid7Client, name: String, description: String, pathPrefix: String) {
    apiTool(
        name = name,
        description = description,
        readOnly = true,
        inputSchema = toolSchema("rrn") { stringParam("rrn", "The Rapid7 Resource Name (RRN) of the entity.") },
    ) { args ->
        val rrn = args.requireString("rrn")
        client.request(HttpMethod.Get, "$pathPrefix/${seg(rrn)}").toToolResult()
    }
}

/**
 * Registers the InsightIDR v1 entity tools: Accounts, Assets, Users, and Local Accounts.
 * Each entity supports a structured `_search` and a get-by-RRN lookup.
 */
fun Server.registerEntityTools(client: Rapid7Client) {
    // Accounts
    searchTool(
        client,
        "search_accounts",
        "Search InsightIDR accounts (directory user accounts) (API v1).",
        "/idr/v1/accounts/_search"
    )
    getByRrnTool(client, "get_account", "Get an InsightIDR account by RRN (API v1).", "/idr/v1/accounts")

    // Assets
    searchTool(client, "search_assets", "Search InsightIDR assets (API v1).", "/idr/v1/assets/_search")
    getByRrnTool(client, "get_asset", "Get an InsightIDR asset by RRN (API v1).", "/idr/v1/assets")

    // Users
    searchTool(client, "search_users", "Search InsightIDR users (API v1).", "/idr/v1/users/_search")
    getByRrnTool(client, "get_user", "Get an InsightIDR user by RRN (API v1).", "/idr/v1/users")

    // Local accounts
    searchTool(
        client,
        "search_local_accounts",
        "Search InsightIDR asset local accounts (API v1).",
        "/idr/v1/assets/local-accounts/_search"
    )
    getByRrnTool(
        client,
        "get_local_account",
        "Get an InsightIDR asset local account by RRN (API v1).",
        "/idr/v1/assets/local-accounts"
    )
}
