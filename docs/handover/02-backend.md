# Handover 02 — Backend skeleton

**Date:** 2026-08-08. **Step:** 2 of 5. **Status:** ✅ complete & Docker-verified end-to-end.

## What was built

A real **Spring Boot 4.1 modular monolith** in `backend/`, compiled under **JDK 25**, backed by Postgres via Flyway, serving the exact API the frontend consumes.

### Structure (packages = SPEC §18 modules)
```
backend/
  pom.xml                     Spring Boot 4.1.0 parent, Java 25
  Dockerfile                  multi-stage: temurin-25-jdk + Maven 3.9.16 → temurin-25-jre
  src/main/resources/
    application.yml           datasource/JPA/Flyway/CORS/AI/langfuse config (all env-overridable)
    db/migration/V1__init.sql work_item + audit_event tables, seeds 6 work items
  src/main/java/com/devloom/
    DevLoomApplication.java
    config/WebConfig.java     CORS for the Vite dev server
    priority/                 SignalComponent, PriorityEngine (deterministic weighted sum)
    common/SecretRedactor.java secret/token redaction (used by build-failure log)
    workmodel/                WorkItemEntity, WorkItemRepository, WorkModelService
    ai/                       LlmPort, StubLlm (offline default), PrivacyGate  ← ready for Step 3
    api/                      Dto (all DTOs, nested records), TodayService, FixtureData, ApiController
  src/test/java/com/devloom/
    priority/PriorityEngineTest.java   (3 tests)
    common/SecretRedactorTest.java     (4 tests)
```

### Endpoints (all GET, `/api/v1`, shapes == `frontend/src/types.ts`)
`today` (DB work items? no — see note), `work` (DB-backed, Flyway-seeded), `builds/{id}`, `handoffs/{id}`, `integrations`, `providers`, `privacy`, `brainstorm`, `onboarding`. All verified **200**.

### What's genuinely real (not stubbed)
- **PriorityEngine** — deterministic weighted-sum ranking; `TodayService` builds candidates with signals and the engine orders them (PR #482 → rank 1). Unit-tested.
- **SecretRedactor** — real regex/entropy redaction; the `builds/{id}` log runs a real `ghp_…` token through it → response contains `‹SECRET REDACTED›`. Unit-tested.
- **Postgres + Flyway + JPA** — `V1__init.sql` migrates on boot; `/api/v1/work` reads real rows and maps to DTOs (glyph derived from type in the app layer).
- **CORS** for the dev server; **Actuator** health.

### Stubbed (swap points for Step 3/4)
- `builds`, `handoffs`, `providers`, `privacy`, `brainstorm`, `onboarding`, `integrations` return `FixtureData` (canned DTOs). `today`'s recommendation prose is fixture text (the LLM "why" plugs in at Step 3).
- `ai/StubLlm` is the default `LlmPort` (deterministic, offline). `PrivacyGate` has hard-coded local-only repos.

## Verification (all done in Docker — no local Java)
- `docker build` → **BUILD SUCCESS**, `Tests run: 7, Failures: 0, Errors: 0`, jar built.
- `docker compose up db backend` → health `UP`; Flyway `Successfully applied 1 migration`; all 9 endpoints `200`; redaction confirmed present in the build response.

## Gotchas fixed (so the next session doesn't re-hit them)
1. **Spring Boot 4 modularized auto-config.** `flyway-core` alone does NOT auto-configure migrations on Boot 4 — you must depend on **`org.springframework.boot:spring-boot-flyway`** (pulls flyway-core). Same pattern likely applies to other tech auto-configs if you add them.
2. **`spring.flyway.baseline-on-migrate: true` on an empty DB skips V1** (baselines at v1). Removed it. If you re-add it for an existing DB, set `baseline-version: 0`.
3. Reset the DB volume (`docker compose down -v`) after a broken migration attempt — a stale `flyway_schema_history` baseline row will keep skipping V1.

## How to run
```bash
cd dev-loom
docker compose up -d --build db backend         # backend on :8080
curl http://localhost:8080/api/v1/today          # priority-ranked
curl http://localhost:8080/api/v1/work           # DB-backed
docker compose down                              # (add -v to wipe the DB)
```

## Next: Step 3 — backend feature logic
Make the stubbed endpoints real where feasible offline: build-failure structuring pipeline (the log/redaction parts are already real — add correlation + hypothesis via `LlmPort`), handoff generation from a build, "what changed", audit logging on egress/writes, retention/delete endpoints, cost-budget enforcement, and a feature-flagged Langfuse OTLP bridge. Also add the `today`→DB linkage if desired. Then Step 4 (fixture connectors) and Step 5 (wire frontend `src/api` to the backend + full compose incl. frontend image, which is already written: `frontend/Dockerfile`, `frontend/nginx.conf`, root `docker-compose.yml`).
