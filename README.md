# Rapid7 InsightIDR MCP Server

An [Model Context Protocol](https://modelcontextprotocol.io) (MCP) server, written in Kotlin with the
[MCP Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk), that exposes the
[Rapid7 InsightIDR](https://www.rapid7.com/products/insightidr/) REST API as MCP tools.

It lets an MCP-capable assistant triage and manage InsightIDR **investigations**, look up **accounts,
assets, users and local accounts**, work with **comments and attachments**, manage **cloud webhooks**
and **community threats**, register **collectors**, and read **health metrics** — all through your
existing Insight platform API key.

> Coverage is based on the official API references:
> [InsightIDR Log Search REST API](https://docs.rapid7.com/insightidr/log-search-api/), 
> [InsightIDR API v2 (Investigations)](https://help.rapid7.com/insightidr/en-us/api/v2/docs.html) and
> [InsightIDR API v1](https://help.rapid7.com/insightidr/en-us/api/v1/docs.html).

---
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
| `INSIGHTIDR_BASE_URL`    |          | `https://<region>.api.insight.rapid7.com` | v2 API base URL override (per the v2 spec servers).            |
| `INSIGHTIDR_V1_BASE_URL` |          | `https://<region>.rest.logs.insight.rapid7.com` | v1 API base URL override (default follows the v1 spec servers; set to `https://<region>.api.insight.rapid7.com` if your tenant routes v1 there). |
| `INSIGHTIDR_LOG_SEARCH_BASE_URL` |  | `https://<region>.rest.logs.insight.rapid7.com` | Log Search API base override (default follows the Log Search spec servers; set to `https://<region>.api.insight.rapid7.com/log_search` for the unified platform route). |
| `INSIGHTIDR_TIMEOUT_MS`  |          | `60000`                                   | Per-request timeout in milliseconds.                           |
| `INSIGHTIDR_HTTP_ALLOWED_ORIGINS` |  | *(empty — deny cross-origin)*    | `--http` mode only: comma-separated browser origins allowed via CORS (e.g. `https://app.example.com`). Empty denies all cross-origin browser access; non-browser MCP clients are unaffected. Never use `*`. |

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
# macOS / Linux
INSIGHTIDR_API_KEY=xxxx INSIGHTIDR_REGION=us \
  java -jar build/libs/rapid7-insightidr-mcp-0.1.2-all.jar --stdio
```

### HTTP (Streamable HTTP / SSE)

Binds to `127.0.0.1:3001` by default. Override the listen address with `--host` (alias `--ip`) and the
port with `--port`:

```PowerShell
# PowerShell 5.1
powershell.exe -Command { $env:INSIGHTIDR_API_KEY="xxxxx"; $env:INSIGHTIDR_REGION="us"; java -jar .\rapid7-insightidr-mcp-0.1.2-all.jar --stdio }

# PowerShell 7
pwsh.exe -Command { $env:INSIGHTIDR_API_KEY="xxxxx"; $env:INSIGHTIDR_REGION="us"; java -jar .\rapid7-insightidr-mcp-0.1.2-all.jar --stdio }
```

```bash
# macOS / Linux
INSIGHTIDR_API_KEY=xxxx INSIGHTIDR_REGION=us \
  java -jar build/libs/rapid7-insightidr-mcp-0.1.2-all.jar --http --host 0.0.0.0 --port 3001
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
        "C:\\MCP Dev\\Rapid7-InsightIDR-MCP\\build\\libs\\rapid7-insightidr-mcp-0.1.2-all.jar",
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

### SIEM Alerts (API `/idr/at`)

Alert triage endpoints from the SIEM Alerts API, served from the v2 host
(`https://<region>.api.insight.rapid7.com`) under the `/idr/at` path — no extra configuration needed.
The deprecated `/alerts/fields` endpoint is intentionally omitted in favour of its V2 replacement
(`list_alert_fields`).

- **Alerts:** `search_alerts`, `get_alert`, `get_alerts_by_rrn`, `patch_alert`, `patch_alerts`,
  `investigate_alerts`, `generate_alert_report`
- **Alert context:** `get_alert_evidences`, `get_alert_actors`, `get_alert_assignee_options`,
  `get_assignee_options`
- **Alert fields:** `get_alert_field`, `get_alert_field_values`, `list_alert_fields`
- **Actions (alert jobs):** `list_alert_actions`, `get_alert_action_result`, `get_alert_action_tasks`
- **Process trees:** `get_alert_process_tree`, `get_alert_process_trees`

`search`/`patch`/`sorts`/`aggregates` are passed through as structured JSON matching the API schema;
`patch_alerts` and `investigate_alerts` are asynchronous and return an `action_rrn` you can track with
the Actions tools.

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
except S3 archiving. Defaults to the spec's servers (`https://<region>.rest.logs.insight.rapid7.com`);
see `INSIGHTIDR_LOG_SEARCH_BASE_URL` to target the unified route
(`https://<region>.api.insight.rapid7.com/log_search`) instead.

- **Query log data** (async queries auto-poll to completion; disable with `wait_for_completion=false`;
  `per_page` defaults to the maximum of 500, and paginated results expose a `rel: "Next"` link —
  pass its href to `logsearch_get_next_page` for the next page):
  `logsearch_query_log`, `logsearch_query_logs`, `logsearch_query_logset`,
  `logsearch_query_logsets_by_name`, `logsearch_poll_query`, `logsearch_get_next_page`,
  `logsearch_get_context_events`, `logsearch_get_search_stats`, `logsearch_list_query_endpoints`
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

## Design notes

- Search endpoints (`search_*`) accept structured `search`/`sort` arrays that pass straight through to the
  API, matching the documented request schema (`{ field, operator, value }` / `{ field, order }`).
- Tools that only read are annotated with `readOnlyHint`; delete/remove operations are annotated as
  destructive so clients can prompt appropriately.
- Results are returned as pretty-printed JSON text. Non-2xx responses are marked as tool errors and
  include the API's response body to help the model self-correct.
- Logging goes to **stderr**; **stdout** is reserved for the MCP JSON-RPC stream in stdio mode.

## License

See [LICENSE](LICENSE).
