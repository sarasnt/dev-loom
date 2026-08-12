# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What DevLoom is

A self-hosted, **local-first** "calm engineering command center": one dashboard for a developer's
day — triage (Today/Briefing), CI-failure analysis, agent handoffs, brainstorming with a chosen
model, local git repositories, and a Fleet of concurrent AI runs. Design intent and rationale live
in `SPEC.md` (product/technical), `UI-SPEC.md` + `DESIGN.md` (design system), `docs/SPEC-sources.md`
(connectors). Feature specs and plans are written to `docs/superpowers/{specs,plans}/`.

## The three processes (the thing to understand first)

| Process | Where | Why it exists |
| --- | --- | --- |
| `frontend` | Docker (nginx, :8088) | Vue 3 SPA, static build |
| `backend` | Docker (Spring Boot, :8080) | API, DB, model routing |
| **host agent** | **Your machine** (Node, 127.0.0.1:8765) | Everything a container can't reach |

The backend runs in a container, so it **cannot** touch your filesystem, your git repos, your
`claude` CLI, your `~/.claude` config, or pop a desktop notification. All of that goes through
`agent/devloom-agent.mjs`, reached at `host.docker.internal:8765` (`HostAgentClient`). If a repo,
terminal, capability or notification feature "does nothing", the agent is usually not running:

```bash
node agent/devloom-agent.mjs        # foreground; needs `npm install` in agent/ only for the PTY terminal
```

The agent uses Node built-ins for its HTTP endpoints (`ws` + `node-pty` are lazy-loaded, terminal
only). **Keep it dependency-free** for anything non-terminal.

## Commands

```bash
# run everything (core: db + backend + frontend; profiles: `ai` = Ollama, `obs` = Langfuse)
docker compose up -d --build
docker compose --profile ai up -d          # + local models

# frontend
cd frontend && npm run dev                 # vite dev server
cd frontend && npx vue-tsc --noEmit        # typecheck — the primary frontend gate
cd frontend && npm run build               # typecheck + production build

# backend — there is NO local JDK/Maven on the dev machine; compile in a container
docker run --rm -v "$PWD/backend:/app" -v devloom_m2:/root/.m2 -w /app \
  maven:3.9-eclipse-temurin-25 mvn -q -o -DskipTests compile

# agent
node --check agent/devloom-agent.mjs       # syntax gate
```

**Code changes do not appear in the running app until you rebuild.** The frontend is a static
build and the backend is a jar: `docker compose up -d --build backend frontend`. Agent changes
need the agent process restarted (kill the listener on 8765, start it again).

## Verifying work

There is **no unit-test harness** in this project. Verification is: `vue-tsc --noEmit`, the
containerized `mvn compile`, then exercising the real running app with `curl`
(`http://localhost:8080/api/v1/...`) and the browser (`http://localhost:8088`). Prefer proving a
change against live data over asserting it works.

## Architecture

### Unified work model
Connectors (`integrations/`: GitHub, Jira, Notion, calendar) all normalize into one
`WorkItemEntity` (type: `pr|build|task|review|calendar|doc`). `SyncService` does
**delete-then-insert per source instance** — so anything that must survive a sync is keyed by the
source-stable `ext_id`, never the row id (see `work_item_flag`, `briefing_snapshot`).
`PriorityEngine` ranks deterministically from inspectable signals; the LLM never sets rank.

### Model routing (`ai/`)
Product code depends only on `LlmPort`; `LlmRouter` picks the adapter by model name
(`claude-*` → Anthropic, `gpt-*`/`o*` → OpenAI, everything else → Ollama) and falls back to local
when a remote is unavailable or over budget. `ModelMonitor` observes every call (latency/tokens),
optionally exporting to Langfuse. **Model choice is per-screen**, stored client-side
(`stores/dashboard.ts` → `screenModels`), not one global setting.

Two Claude paths exist and are *not* interchangeable:
- **`claude -p` (headless)** — agentic, can edit files. Only Claude models. Used by Fleet
  background edit runs.
- **PTY terminal** (`/pty` WebSocket + xterm.js) — the real interactive `claude` TUI. PTYs are
  **persistent**: they survive their WebSocket closing (you navigate away) and re-attach with
  replayed scrollback; idle+detached ones are GC'd.

### Fleet (`fleet/`)
`agent_run` rows track concurrent AI work: `background` (headless `claude -p`, or a local/API model
doing read-only analysis on a pool thread), `interactive` (a PTY terminal), `chat` (an in-flight
brainstorm turn). A 10s poller reconciles status, **adopts live PTYs the board doesn't know about**,
and routes attention (`review` / `input` / `failed`). Isolated edit runs get their own
`git worktree` + `devloom/run-<id>` branch so several can work one repo at once; Apply keeps the
branch or lands a patch, Discard removes both.

### Host-agent surface
`/repos/*` (status, changes, commit, push, branches, source drift, conflict prediction via
`merge-tree`, worktrees, log, squash), `/pty` (terminal), `/claude` + `/agent/run*` (Claude bridge),
`/caps/*` (skills, MCP servers, plugins — edits the user's own `~/.claude` config), `/backup/*`,
`/notify` (native desktop toast).

## Conventions that will bite you

- **No fixtures in the running app.** Every screen shows real data or an honest empty state. The
  `api/stub.ts` path is only for `VITE_USE_STUB=true` offline frontend work. Every button, tab and
  dropdown must actually work — no placeholder UI.
- **Frontend API facade**: add a function to `api/http.ts` *and* `api/stub.ts`, then re-export it
  from `api/index.ts`. Views import from `../api` only.
- **Jackson**: use `com.fasterxml.jackson` (Jackson 2). Boot 4 also ships `tools.jackson`
  (Jackson 3) — mixing them breaks the build.
- **Migrations**: Flyway, `backend/src/main/resources/db/migration/`. Next is **V18**.
- **Secrets**: encrypted via `SecretCipher`/`DEVLOOM_SECRET`, stored apart from config
  (`source_credential`, `provider_credential`). They must never enter exports, backups or logs.
- **Windows/agent**: run `git` with `shell: false` — going through cmd.exe re-splits arguments
  containing spaces (commit messages, paths). `claude`/`gh` keep `shell: true` for their `.cmd`
  shims. In PowerShell, `Set-Location` does not update `[Environment]::CurrentDirectory`, which is
  what native children inherit — set both when a child must start in a specific directory.
- **User settings** that aren't secret go in `app_config` via `AppConfigService` (key/value,
  `VARCHAR(120)` key).

## Style

Match the surrounding code. Comments explain *why* — the constraint, the trade-off, the bug that
forced the shape — not what the line does. The design system is deliberate: one accent (warp),
mono for verified evidence, grotesque for reasoning; keep status legible without relying on color.
