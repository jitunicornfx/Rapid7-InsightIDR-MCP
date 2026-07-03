# Rapid7 InsightIDR MCP Server

An [Model Context Protocol](https://modelcontextprotocol.io) (MCP) server, written in Kotlin with the
[MCP Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk), that exposes the
[Rapid7 InsightIDR](https://www.rapid7.com/products/insightidr/) REST API (v1 and v2) as MCP tools.

It lets an MCP-capable assistant triage and manage InsightIDR **investigations**, look up **accounts,
assets, users and local accounts**, work with **comments and attachments**, manage **cloud webhooks**
and **community threats**, register **collectors**, and read **health metrics** — all through your
existing Insight platform API key.

> Coverage is based on the official API references:
> [InsightIDR API v2 (Investigations)](https://help.rapid7.com/insightidr/en-us/api/v2/docs.html) and
> [InsightIDR API v1](https://help.rapid7.com/insightidr/en-us/api/v1/docs.html).

---

## Features

- **~130 tools** spanning the documented InsightIDR v1 and v2 endpoints plus the complete
  Log Search API (queries, saved queries, logs/log sets, usage, CSV exports, LEQL variables,
  pre-computed queries, basic detection rules, and audit logs — everything except S3 archiving).
- Region-aware base URL resolution (`https://<region>.api.insight.rapid7.com`).
- Asynchronous Log Search queries are polled to completion automatically (202 + continuation links).
- `X-Api-Key` authentication from an environment variable — no secrets in code.
- Robust error handling: HTTP and network errors are returned to the model as tool errors
  (with the API's response body) instead of crashing the session.
- Two transports: **stdio** (default, for local desktop clients) and **HTTP** (Streamable HTTP/SSE).
- Self-contained fat JAR via the Shadow plugin.

## Requirements

- **JDK 21+** (developed and tested against a newer JDK; bytecode targets Java 21).
- An **Insight platform API key** with access to InsightIDR
  (Insight Platform Home → *API Keys*). An *Organization* key is recommended.
- Your Insight **region** (the prefix of your Insight URL, e.g. `us` in `us.idr.insight.rapid7.com`).

## Configuration

Configuration is read from environment variables:

| Variable                 | Required | Default                                   | Description                                                    |
|--------------------------|----------|-------------------------------------------|----------------------------------------------------------------|
| `INSIGHTIDR_API_KEY`     | ✅       | —                                         | Insight platform API key.                                      |
| `INSIGHTIDR_REGION`      |          | `us`                                      | Region code: `us`, `us2`, `us3`, `eu`, `ca`, `au`, `ap`.       |
| `INSIGHTIDR_BASE_URL`    |          | `https://<region>.api.insight.rapid7.com` | Full base URL override (advanced / testing).                   |
| `INSIGHTIDR_LOG_SEARCH_BASE_URL` |  | `<base URL>/log_search`                   | Log Search API base override (e.g. `https://<region>.rest.logs.insight.rapid7.com` to hit the direct host). |
| `INSIGHTIDR_TIMEOUT_MS`  |          | `60000`                                   | Per-request timeout in milliseconds.                           |

See [`.env.example`](.env.example).

## Build

```PowerShell
# Windows
.\gradlew.bat shadowJar
```

```bash
# macOS / Linux
./gradlew shadowJar
```

This produces a runnable fat JAR at:

```
build/libs/rapid7-insightidr-mcp-0.1.0-all.jar
```

## Run

### stdio (default)

```PowerShell
# PowerShell 5.1
powershell.exe -Command { $env:INSIGHTIDR_API_KEY="xxxxx"; $env:INSIGHTIDR_REGION="us"; java -jar .\rapid7-insightidr-mcp-0.1.2-all.jar --stdio }

# PowerShell 7
pwsh.exe -Command { $env:INSIGHTIDR_API_KEY="xxxxx"; $env:INSIGHTIDR_REGION="us"; java -jar .\rapid7-insightidr-mcp-0.1.2-all.jar --stdio }

```

```bash
# Linux
INSIGHTIDR_API_KEY=xxxx INSIGHTIDR_REGION=us \
  java -jar build/libs/rapid7-insightidr-mcp-0.1.0-all.jar --stdio
```

### HTTP (Streamable HTTP / SSE)

Binds to `127.0.0.1:3001` by default. Override the listen address with `--host` (alias `--ip`) and the
port with `--port`:

```bash
INSIGHTIDR_API_KEY=xxxx INSIGHTIDR_REGION=us \
  java -jar build/libs/rapid7-insightidr-mcp-0.1.0-all.jar --http --host 0.0.0.0 --port 3001
```

Run `--help` to see all options. You can also run during development with
`./gradlew run --args="--stdio"`.

## Use with an MCP client (e.g. Claude Desktop)

Add to your client's MCP server configuration (adjust the JAR path):

```json
{
  "mcpServers": {
    "insightidr": {
      "command": "java",
      "args": [
        "-jar",
        "C:\\MCP Dev\\Rapid7-InsightIDR-MCP\\build\\libs\\rapid7-insightidr-mcp-0.1.0-all.jar",
        "--stdio"
      ],
      "env": {
        "INSIGHTIDR_API_KEY": "your-insight-platform-api-key",
        "INSIGHTIDR_REGION": "us"
      }
    }
  }
}
```

Then ask the assistant to `validate_connection` first to confirm the key and region.

## Tools

### Diagnostics
- `validate_connection` — validate the API key/region via the platform `/validate` endpoint.

### Investigations (API v2 — recommended)
- `list_investigations`, `get_investigation`, `search_investigations`
- `create_investigation`, `update_investigation`
- `set_investigation_status`, `set_investigation_priority`, `set_investigation_disposition`
- `assign_investigation`, `bulk_close_investigations`
- `list_investigation_alerts`, `get_investigation_product_alerts`, `remove_alert_from_investigation`

### Investigations (API v1 — legacy parity)
- `list_investigations_v1`, `set_investigation_status_v1`, `assign_investigation_v1`, `bulk_close_investigations_v1`

### Entities (API v1)
- Accounts: `search_accounts`, `get_account`
- Assets: `search_assets`, `get_asset`
- Users: `search_users`, `get_user`
- Local accounts: `search_local_accounts`, `get_local_account`

### Comments (API v1)
- `list_comments`, `get_comment`, `create_comment`, `delete_comment`, `update_comment_visibility`

### Attachments (API v1)
- `list_attachments`, `get_attachment_metadata`, `download_attachment`, `delete_attachment`, `upload_attachment`

### Cloud Webhooks (API v1)
- `list_cloud_webhooks`, `get_cloud_webhook`, `create_cloud_webhook`, `update_cloud_webhook`, `delete_cloud_webhook`
- `test_cloud_webhook`, `replay_cloud_webhook_events`
- `add_cloud_webhook_validation`, `update_cloud_webhook_validation`, `delete_cloud_webhook_validation`

### Community Threats (API v1)
- `create_community_threat`, `add_community_threat_indicators`, `replace_community_threat_indicators`, `delete_community_threat`

### Collectors & Health (API v1)
- `add_collector`
- `get_health_metrics`

### Log Search API (`logsearch_*`)

Complete coverage of the [Log Search API](https://docs.rapid7.com/insightidr/log-search-api/)
except S3 archiving. Defaults to the unified route (`<base>/log_search`); see
`INSIGHTIDR_LOG_SEARCH_BASE_URL` to target `rest.logs.insight.rapid7.com` directly.

- **Query log data** (async queries auto-poll to completion; disable with `wait_for_completion=false`):
  `logsearch_query_log`, `logsearch_query_logs`, `logsearch_query_logset`,
  `logsearch_query_logsets_by_name`, `logsearch_poll_query`, `logsearch_get_context_events`,
  `logsearch_get_search_stats`, `logsearch_list_query_endpoints`
- **Saved queries:** `logsearch_list_saved_queries`, `logsearch_get_saved_query`,
  `logsearch_create_saved_query`, `logsearch_replace_saved_query`, `logsearch_update_saved_query`,
  `logsearch_delete_saved_query`, `logsearch_run_saved_query`, `logsearch_run_saved_query_on_logs`
- **Logs & log sets:** `logsearch_list_logs`, `logsearch_get_log`, `logsearch_delete_log`,
  `logsearch_get_log_event_sources`, `logsearch_get_log_top_keys`, `logsearch_list_logsets`,
  `logsearch_get_logset`, `logsearch_replace_logset`, `logsearch_delete_logset`
- **Download & usage:** `logsearch_download_log_data`, `logsearch_get_usage_total`,
  `logsearch_get_usage_per_log`, `logsearch_get_log_usage`
- **CSV export jobs:** `logsearch_list_export_jobs`, `logsearch_get_export_job`, `logsearch_delete_export_job`
- **LEQL variables:** `logsearch_list_variables`, `logsearch_get_variable`, `logsearch_create_variable`,
  `logsearch_update_variable`, `logsearch_delete_variable`
- **Pre-computed queries:** `logsearch_list_metrics`, `logsearch_get_metric`, `logsearch_query_metric`,
  `logsearch_create_metric`, `logsearch_replace_metric`, `logsearch_delete_metric`
- **Basic detection rules:** `logsearch_list_detection_rules`, `logsearch_get_detection_rule`,
  `logsearch_create_detection_rule`, `logsearch_replace_detection_rule`, `logsearch_update_detection_rule`,
  `logsearch_delete_detection_rule`, plus notifications (`logsearch_*_notification`,
  `logsearch_list_notification_targets`, `logsearch_update_notification_targets`),
  targets (`logsearch_*_target`), and labels (`logsearch_*_label`)
- **Audit logs:** `logsearch_list_audit_logs`, `logsearch_get_audit_log`, `logsearch_audit_query_log`,
  `logsearch_audit_query_logs`, `logsearch_audit_poll_query`, `logsearch_audit_list_export_jobs`,
  `logsearch_audit_get_export_job`, `logsearch_audit_list_query_endpoints`

## Testing & coverage

Unit tests live under `src/test/kotlin/` and run on the JUnit Platform. Coverage is measured with JaCoCo:

```bash
./gradlew test jacocoTestReport      # Windows: gradlew.bat test jacocoTestReport
```

Open `build/reports/jacoco/test/html/index.html` for the report. Full details — thresholds, SonarQube
integration, and how to cover the HTTP layer with Ktor `MockEngine` — are in
[docs/CODE_COVERAGE.md](docs/CODE_COVERAGE.md).

## Design notes

- Search endpoints (`search_*`) accept structured `search`/`sort` arrays that pass straight through to the
  API, matching the documented request schema (`{ field, operator, value }` / `{ field, order }`).
- Tools that only read are annotated with `readOnlyHint`; delete/remove operations are annotated as
  destructive so clients can prompt appropriately.
- Results are returned as pretty-printed JSON text. Non-2xx responses are marked as tool errors and
  include the API's response body to help the model self-correct.
- Logging goes to **stderr**; **stdout** is reserved for the MCP JSON-RPC stream in stdio mode.

## Project layout

```
src/main/kotlin/com/jitunicornfx/insightidr/mcp/
  Main.kt                 # entry point + transport selection (stdio / http)
  Config.kt               # env-based configuration + region resolution
  Rapid7Client.kt         # Ktor HTTP client wrapper (auth, timeouts, error passthrough)
  ToolSupport.kt          # schema DSL, argument accessors, result formatting, tool registration
  McpServerFactory.kt     # assembles the Server and registers all tools
  tools/                  # one file per API domain
```

## License

See [LICENSE](LICENSE).
