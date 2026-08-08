# Handover 03 — Live Jira integration (real data)

**Date:** 2026-08-08. **Status:** ✅ working & verified against `jira.critical.pt`.

## What this delivers
The first **real** integration: the signed-in user's assigned Jira issues flow into the unified WorkItem model and out through the existing `/api/v1/work` endpoint — replacing the Jira fixture with live data.

## Key correction to SPEC §13
`jira.critical.pt` is **on-premises Jira (Data Center/Server)**, not Jira Cloud, and it is **reachable without VPN** (public HTTPS; verified 401→200 with a token). On-prem specifics (now implemented):
- Auth: **Personal Access Token** via `Authorization: Bearer <token>` (not OAuth 3LO).
- API: **REST v2** — `GET /rest/api/2/search?jql=assignee = currentUser() ORDER BY updated DESC`.
- Token owner (validated): Jira user `srsantos`, 284 issues assigned.

## Code added (`backend/`)
- `integrations/SourceConnector.java` — the provider-adapter port (SPEC §12). New sources implement this; nothing downstream changes.
- `integrations/JiraConnector.java` — on-prem Jira adapter. Reads `devloom.jira.{base-url,pat,max-issues}`; parses responses as plain `Map` (Jackson-version-independent — **Boot 4 ships Jackson 3**, package `tools.jackson.*`, so don't import `com.fasterxml.jackson.*`); maps issue→WorkItem (status category → tone: done=healthy, indeterminate=warn, else info); degrades gracefully (returns empty, never throws).
- `integrations/SyncService.java` — replace-on-sync per source (idempotent); leaves other sources' rows untouched.
- `integrations/StartupSync.java` — best-effort sync of enabled connectors at boot (never fails startup).
- `workmodel/WorkItemEntity.create(...)` factory + `WorkItemRepository.deleteBySource/countBySource`.
- `api/ApiController` — `POST /api/v1/integrations/{source}/sync` for on-demand re-sync.
- `application.yml` — `devloom.jira.*` config (all env-driven).

## Config / secrets
- Real values live in **`backend/.env` (gitignored)** — `DEVLOOM_JIRA_BASE_URL`, `DEVLOOM_JIRA_PAT`, `DEVLOOM_NOTION_TOKEN`. `backend/.env.example` documents them with placeholders (committed).
- `docker-compose.yml` backend service loads `./backend/.env` via `env_file` (`required: false`, so the stack still runs without it).
- ⚠️ The Jira PAT and Notion token were shared in chat — **rotate both** and update `backend/.env`.

## Verification
```
docker compose up -d --build db backend
# logs: "Jira sync: fetched 25 issues" → "Synced 25 items from Jira" → "Startup sync complete: 25 items"
curl -s localhost:8080/api/v1/work   # includes 25 Jira rows (real keys LCCCHES-*, LCCCHESDESK-*)
curl -X POST localhost:8080/api/v1/integrations/jira/sync   # {"source":"jira","ingested":25}
```

## Notion status
Token valid (integration "DevLoom" in "Sara Santos's Space") but **0 objects shared** — an internal integration sees nothing until the user shares pages/databases with it (page → ••• → Connections → DevLoom). Connector not built yet; Notion is "Next" scope. Build like JiraConnector once pages are shared.

## Next
- **Step 5 — wire the frontend to the backend** so the dashboard/Work screen shows the real Jira issues (point `frontend/src/api` at `/api/v1`; run full compose incl. the frontend image — already scaffolded).
- **Step 3 — remaining backend feature logic** (build-failure correlation via `LlmPort`, handoff generation, what-changed, audit, retention/delete, cost budgets, Langfuse bridge).
- **GitHub connector** — `gh` is authed as `sarasnt`; add a `GitHubConnector` (fine-grained PAT via `DEVLOOM_GITHUB_TOKEN`) to make PRs/issues live too.
