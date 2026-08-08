# DevLoom — Things that need your input

Everything here is **blocked on something only you can provide** (a credential, a consent step, a decision, or a piece of infra). Nothing below blocks the rest of the build — the app runs and the buildable-without-input work continues. When you're ready, we do these together. Each item says exactly what to do and where the value goes.

Secrets always go in **`backend/.env`** (gitignored) — never in code or chat.

---

## 1. Notion — connect the integration to your pages  ⏳ quick
**Why blocked:** the token is valid ("DevLoom" integration), but an internal Notion integration can't see a page until it's explicitly connected. Your page URL alone returns `404 object_not_found`.
**Do this:** open the page in Notion → `•••` (top-right) → **Connections** → search **DevLoom** → add it. Repeat for any pages/databases you want in DevLoom.
**Then:** tell me, or `POST /api/v1/integrations/notion/sync` — the pages appear as `doc` work items. Connector is already built.

## 2. Ollama — confirm it's running with a pulled model  ⏳ quick
**Why partial:** the adapter + router are built and auto-pick whatever model you've pulled, with graceful fallback. To get **real local-model** build-failure/brainstorm analysis (instead of the curated fallback), Ollama must be reachable from the backend *with a model pulled*.
**Do this:**
- If using the bundled container: `docker compose --profile ai up -d ollama` then `docker compose exec ollama ollama pull qwen2.5-coder` (or your model). The backend already points at `ollama:11434`.
- If Ollama runs on your host instead: set `DEVLOOM_OLLAMA_URL=http://host.docker.internal:11434` in `backend/.env`.
**Tell me:** which model you pulled (I'll set `DEVLOOM_AI_MODEL`, though it auto-detects the first available).

## 3. Microsoft / Outlook calendar  (excluded from iteration 1)
When you want it: provide a **private ICS feed URL** (Outlook → Calendar → Share → Publish → ICS) and I'll add it exactly like Google — or we do full Microsoft Graph OAuth (heavier). Google Calendar is already live.

## 4. GitHub — where your real work lives
The connector works but returns 0 for `sarasnt` (no open PRs/issues). If your real GitHub work is under an **org or specific repos**, name them and I'll scope the query there. Otherwise it stays sparse until `sarasnt` has activity (e.g. once `dev-loom` gets PRs).

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
- Data residency/compliance: confirm "data stays on my machine" is sufficient for now (no formal GDPR/region requirement).
- Default local model to ship (currently `Qwen3-Coder-30B-A3B` in config; auto-detect overrides).

---

### For reference — what's proceeding WITHOUT your input
Live already: Jira (25 issues), Google Calendar (real events), GitHub (ready), unified work model, deterministic priority engine, secret redaction, audit trail, retention/delete, generated handoffs, what-changed, cost-budget scaffolding, the AI router with Ollama support + graceful fallback, the full containerized stack. Still buildable without input (queued): wiring more UI actions, onboarding status from real integration state, Bitbucket/GitLab connectors (Next scope), and OIDC *scaffolding* (activates once you supply a client id/secret in item 5).
