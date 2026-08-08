# Handover 00 — Baseline & Master Plan

**Date:** 2026-08-08. **Author:** Claude (Opus 4.8). **Purpose:** let any new session resume the DevLoom MVP build with zero context loss.

## Where the project stands (before this build push)

- **[SPEC.md](../../SPEC.md)** — Phase 1 product + technical spec, **approved**. Read §9 (MVP scope), §15 (domain model), §16 (Postgres model), §17–18 (architecture/modules), §20 (LLM), §22 (priority), §23 (build-failure), §24 (handoff), §34 (API).
- **[UI-SPEC.md](../../UI-SPEC.md)** — Phase 2 UI spec, **approved**.
- **[DESIGN.md](../../DESIGN.md)** — high-fidelity design system (tokens, contrast audit, motion). **Components must use `var(--…)` tokens only** — a paper-theme test already caught hardcoded hex; don't reintroduce it.
- **[mockups/](../../mockups/)** — 11 static HTML screens + PNGs. The visual source of truth to port from.
- **[frontend/](../../frontend/)** — runnable Vue 3 + TS + Vite 8 + Pinia app. Shell + **Today** screen against a stub API. `npm install && npm run dev` (port 5173). `npm run build` passes.

## Product-owner decisions (locked)

Self-hosted only · single user (workspace of one) · GitHub + Jira + **both** Google & Microsoft calendars · team features deferred · **local-first AI via Ollama (default `Qwen3-Coder-30B-A3B`), optional BYO Anthropic/OpenAI keys** · **Langfuse required, self-hosted** · Java 25 + **Spring Boot 4** · read-only MVP but write-ready architecture · effort signal = LLM-inferred · social OIDC (GitHub/Google) login.

## Build environment (this machine)

| Tool | Status |
|---|---|
| Node | v24 ✅ |
| Docker + Compose | ✅ (v29 / compose v5) |
| Java / Maven / Gradle | ❌ not installed → **build & run the backend in Docker** |
| Ollama / Postgres / Langfuse | ❌ not installed → run via Docker Compose; AI/tracing stubbed unless containers are up |

**Implication:** backend is authored in `backend/` and compiled/tested/run via Docker (multi-stage Maven image). Frontend is verified live in a browser (Playwright screenshots). External OAuth + live model inference can't be exercised here → **fixtures + stubs**, with wiring docs.

## Master plan (major steps → each ends with a handover doc)

- **Step 1 — Frontend MVP screens.** Router + all screens (Work, Build-failure, Handoff, Brainstorm, Integrations, Providers, Privacy, Onboarding) against an expanded stub API. Browser-verified. → `01-frontend.md`
- **Step 2 — Backend skeleton.** Spring Boot 4 modular monolith (Spring Modulith), Postgres + Flyway, unified domain model, **deterministic priority engine + unit tests**, AI provider port (Ollama adapter + deterministic stub), REST API over fixture data, Actuator health. Docker-built + tested. → `02-backend.md`
- **Step 3 — Backend feature logic.** Build-failure structuring pipeline (collect→structure→sanitize/redact→correlate→hypothesize-stub), handoff generation, "what changed", audit log, retention/delete, PrivacyGate, cost budgets, Langfuse OTLP bridge (feature-flagged). → `03-features.md`
- **Step 4 — Integrations (fixtures) + wiring docs.** GitHub/Jira/Google/Microsoft connectors behind the `SourceConnector` port with recorded fixtures; real-OAuth wiring documented. Ollama adapter guarded behind a health check. → `04-integrations.md`
- **Step 5 — Full stack.** Wire frontend API client to the backend; `docker-compose.yml` with profiles (postgres, backend, frontend, ollama, langfuse). End-to-end smoke test. Final handover. → `05-fullstack.md`

## Honesty ledger (what "done" will and won't mean)

- **Real & verified:** frontend screens; backend domain + priority engine (unit-tested); REST API serving the domain; Postgres schema; build-failure log structuring/redaction; handoff rendering; docker-compose bring-up.
- **Stubbed (needs live creds/services to be real):** GitHub/Jira/calendar OAuth + sync (fixtures instead); actual LLM inference (Ollama adapter present, returns a deterministic canned analysis unless an Ollama container is reachable); Langfuse traces (bridge present, flagged off unless a Langfuse container is configured).
- These stubs are **swap points**, not dead ends — each is an interface with a fixture implementation and a documented real implementation path.

## Conventions

- Monorepo: `frontend/` (Vue), `backend/` (Spring Boot), `mockups/`, `docs/`, root `docker-compose.yml`.
- Design tokens are law (DESIGN.md). API shapes follow SPEC §34. Domain names follow SPEC §15.
- Each step: implement → verify (browser or Docker build/test) → write its handover doc → update this plan's checklist below.

## Progress checklist

- [x] Step 0 — baseline & plan
- [x] Step 1 — frontend MVP screens (`01-frontend.md`) — browser-verified
- [x] Step 2 — backend skeleton (`02-backend.md`) — Docker-verified end-to-end
- [x] **Live Jira integration** (`03-jira-live.md`) — real on-prem issues in the WorkItem model
- [x] **Frontend↔backend wired** — real Jira issues render in the UI (Vue → Vite proxy → Spring Boot → Postgres → jira.critical.pt). Screenshot: `frontend/screenshots/fullstack-work-jira.png`
- [x] **Step 3 (partial)** (`04-fullstack-and-features.md`) — audit log, retention/delete, generated handoff (all real). Remaining: build-failure via LlmPort, what-changed, cost budgets, Langfuse bridge.
- [x] **Full containerized compose** — nginx frontend + backend + db, real Jira at http://localhost:8088.
- [x] **GitHub connector** (live-capable; 0 items for `sarasnt`).
- [x] **Google Calendar (ICS) connector** — real upcoming events (verified: "Reservation at IZAKAYA ONI"). Outlook excluded from iteration 1.
- [x] **Notion connector** — built; needs the page **connected to the DevLoom integration** (URL alone → 404 object_not_found). Works once connected.
- [x] **Step 3 complete (no-input parts):** Ollama `LlmPort` adapter + `LlmRouter` (real-model-when-up, graceful stub fallback) wired into build-failure; **what-changed** from the audit trail (wired into Today + `GET /changes`); **cost-budget** scaffolding; **Langfuse tracer seam** (flagged). Handover `04`.
- [ ] Remaining (no input): onboarding status from real integration state; providers/privacy from config; OIDC login *scaffolding*; Bitbucket/GitLab connectors (Next scope); more UI action wiring.
- [ ] **Blocked-on-user items → see [`docs/NEEDS-INPUT.md`](../NEEDS-INPUT.md)** (Notion page-connect, Ollama model, Outlook, GitHub org, OIDC client creds, Langfuse infra, token rotation).

**Goal (set by user):** complete every spec buildable without input; anything needing input goes to `docs/NEEDS-INPUT.md`.

**Repo:** github.com/sarasnt/dev-loom (private). Latest: `9f95849`. Live sources: **Jira (25), Google Calendar (real), GitHub (ready)**. Secrets in `backend/.env` (gitignored) — **rotate the Jira PAT, GitHub token, Notion token, and reset the Google ICS URL** (all shared in chat).
