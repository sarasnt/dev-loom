# Handover 04 — Full containerized stack + Step-3 features

**Date:** 2026-08-08. **Status:** ✅ one-command self-hosted stack running; audit/retention/handoff real.

## One-command self-hosted stack (verified)
```
docker compose up -d --build
#   db (postgres:16)  → :5432
#   backend (Spring Boot 4.1 / JDK 25) → :8080
#   frontend (Vue build → nginx) → http://localhost:8088
```
- Frontend at **http://localhost:8088** (SPA, history-mode fallback). `nginx.conf` proxies `/api/` → `backend:8080`.
- Verified: `:8088/` → 200; `:8088/api/v1/work` → real **25 Jira + 4 GitHub** rows; `:8088/api/v1/today` → 200 (priority engine). Screenshot: `frontend/screenshots/containerized-today.png`.
- `frontend/Dockerfile` bakes `VITE_API_BASE=/api/v1`; the SPA + API are same-origin behind nginx, so no CORS in prod.
- Optional profile: `docker compose --profile ai up -d` adds Ollama on :11434.

## Step-3 features made real (backend)
- **Audit** (`audit/` package + `audit_event` table): every sync and deletion is recorded. `GET /api/v1/audit` returns the recent trail (verified: `sync Jira ingested=25`, `purge_source calendar removed=1`).
- **Retention/delete** (`RetentionService`, SPEC §29): `DELETE /api/v1/integrations/{source}` purges that source's work items; `DELETE /api/v1/data` wipes all synced work. Both audited. (Verified.)
- **Handoff generation** (`HandoffService`, SPEC §24): `GET /api/v1/handoffs/{id}` now **assembles** the agent handoff from the build's (already-redacted) data — repo/branch/PR/run, failing step, evidence, ranked hypotheses, and the locked safety block — instead of returning a canned string. (Verified.)
- Fixed a replace-on-sync **duplicate-key** bug: `WorkItemRepository.deleteBySource` is a bulk `@Modifying` delete so it executes before re-inserts (Hibernate flushes INSERTs before entity DELETEs). Sync is now idempotent.

## Live connectors
- **Jira (on-prem)** — real, 25 assigned issues; PAT bearer + REST v2; startup + `POST /api/v1/integrations/jira/sync`.
- **GitHub** — connector built + enabled (token in `.env`), but returns 0 for `sarasnt` (no open involved PRs/issues); keeps fixture rows. Will populate when there's activity or a different org/repos are targeted.
- Both behind the `SourceConnector` port; adding a new source = one class.

## Endpoint surface (`/api/v1`)
`today · work · builds/{id} · handoffs/{id} · integrations · providers · privacy · brainstorm · onboarding` (GET); `integrations/{source}/sync` (POST); `integrations/{source}` + `data` (DELETE); `audit` (GET). `actuator/health`.

## Still stubbed / not yet real (next)
- `builds/{id}`, `providers`, `privacy`, `brainstorm`, `onboarding`, and `today`'s recommendation prose = fixtures. Build-failure **log redaction is real**; correlation/summary via `LlmPort` is still the deterministic `StubLlm` (no live model).
- **What-changed**, **cost budgets**, **Langfuse OTLP bridge** — not built (Langfuse needs its own container; flag `DEVLOOM_LANGFUSE_ENABLED` exists, no exporter yet).
- **Calendars** (Google/Microsoft) — need a private ICS feed URL or OAuth; not built. **Notion** — token valid but 0 pages shared; connector not built.
- Ollama adapter (`OllamaLlm`) not implemented; `StubLlm` is the only `LlmPort`.
- UI actions (buttons/filters/save-as/override) are presentational; not wired to writes (MVP is read-only by design).

## Secrets
`backend/.env` (gitignored) holds the Jira PAT, GitHub token, Notion token. **Rotate all three** (shared in chat). `backend/.env.example` documents them.

## Repo
github.com/sarasnt/dev-loom (private). HEAD `0a11793`. Handovers `00`–`04` in `docs/handover/`.
