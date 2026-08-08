# DevLoom — Things that need your input

Everything here is **blocked on something only you can provide** (a credential, a consent step, a decision, or a piece of infra). Nothing below blocks the rest of the build — the app runs and the buildable-without-input work continues. When you're ready, we do these together. Each item says exactly what to do and where the value goes.

Secrets always go in **`backend/.env`** (gitignored) — never in code or chat.

---

## 1. Notion — ✅ DONE (2026-08-08)
Connected the DevLoom integration to the "Messaging & Event-Driven Cheat Sheet" page; it now syncs as a `doc` work item (Integrations shows *Notion · connected · 1 item*). To add more, connect additional pages/databases (page → `•••` → **Connections** → **DevLoom**) and re-sync (`POST /api/v1/integrations/notion/sync`).

## 2. Ollama — ✅ DONE (2026-08-08)
Ollama container running with `qwen2.5-coder` pulled. Verified: build-failure analysis is produced by the local model (`provider=ollama model=qwen2.5-coder:latest`, ~15s on CPU), nothing leaves the machine, redaction intact. The router auto-detects the model and falls back to the offline stub if Ollama is ever down. (Optional: pull `llama3.2:3b` for faster/lower-quality triage; the router will pick whatever's available.)

## 3. Microsoft / Outlook calendar  (excluded from iteration 1)
When you want it: provide a **private ICS feed URL** (Outlook → Calendar → Share → Publish → ICS) and I'll add it exactly like Google — or we do full Microsoft Graph OAuth (heavier). Google Calendar is already live.

## 4. GitHub — ✅ LIVE (2026-08-09)
The connector pulls the PRs/issues that involve `sarasnt` and auto-discovers CI: it surfaces recent **failed workflow runs** as `build` work items and analyzes them for real. Verified end-to-end against `sarasnt/ration-app` run **#144** (`spec/28-first-launch-wizard`): the analyzer follows GitHub's job-log 302→signed-blob redirect, redacts the tail (46 lines), and the local model (`qwen2.5-coder`) correctly diagnosed the drift `AppDatabase` multiple-instantiation failure — evidence separated from hypothesis, nothing left the machine.
*Optional:* if more of your work lives under an **org or specific repos**, name them and I'll scope the search query there.

## 5. Login / authentication  (not yet built — needs an IdP)
The backend currently has **no auth** (single-user, open on localhost — fine for local self-host, not for exposure). The plan is social OIDC (GitHub/Google, SPEC §26). Building it needs an **OAuth client id/secret**:
- GitHub: Settings → Developer settings → OAuth Apps → New (callback `http://localhost:8088/login/oauth2/code/github`).
- or Google Cloud OAuth client.
Give me a client id/secret and I'll wire Spring Security OIDC login.

## 6. Langfuse — self-hosted tracing  (seam ready, export deferred)
The `LangfuseTracer` seam traces every LLM call and is gated by `DEVLOOM_LANGFUSE_ENABLED`. To actually export: stand up self-hosted Langfuse (its Postgres + ClickHouse + Redis + object-store stack — see SPEC §25), then set `DEVLOOM_LANGFUSE_ENABLED=true` + the OTLP endpoint/keys. I can add the Langfuse compose profile; confirm you want the extra ~4 services running locally.

## 7. 🔐 Rotate the secrets shared in chat  (please do soon)
All of these were pasted in chat, so treat them as compromised and rotate, then update `backend/.env`:
- **Jira PAT** — Jira → Profile → Personal Access Tokens → revoke + reissue.
- **GitHub token** (`gho_…`) — revoke in GitHub settings; a fine-grained read-only PAT is ideal for `DEVLOOM_GITHUB_TOKEN`.
- **Notion token** (`ntn_…`) — notion.so/my-integrations → DevLoom → rotate.
- **Google ICS URL** — Calendar settings → *Reset* the private URL, paste the new one into `DEVLOOM_GOOGLE_ICS_URL`.

## 8. Minor confirmations (SPEC §39)
- ✅ **Data residency:** confirmed — everything stays on the local machine for now; no formal GDPR/region requirement. **Deferred:** eventual SaaS/hosting + managed keys (revisit when moving off local-only — reopens tenancy/KMS decisions in SPEC §28/§27/§32).
- ✅ **Default local model:** resolved — `qwen2.5-coder` (auto-detected from Ollama).

---

## 9. Deferred by design (not blocking)
- **OIDC login** — scaffolding intentionally **not** added yet: Spring Security OIDC intercepts all requests and, without a valid client id/secret, would lock you out of the running app you're testing. Add it together with the GitHub/Google client creds (item 5). Until then the app is single-user/open on localhost.
- **Bitbucket / GitLab connectors** — you use GitHub + on-prem Jira, so there's no account to build/test against. The `SourceConnector` port makes these a ~1-file add whenever a real instance exists. Left out to avoid shipping untestable code.

---

### For reference — what's proceeding WITHOUT your input
Live already: Jira (25 issues), Google Calendar (real events), GitHub (ready), unified work model, deterministic priority engine, secret redaction, audit trail, retention/delete, generated handoffs, what-changed, cost-budget scaffolding, the AI router with Ollama support + graceful fallback, the full containerized stack. Still buildable without input (queued): wiring more UI actions, onboarding status from real integration state, Bitbucket/GitLab connectors (Next scope), and OIDC *scaffolding* (activates once you supply a client id/secret in item 5).
