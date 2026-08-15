# DevLoom

A self-hosted, local-first engineering command center: one dashboard for the day's work —
triage, CI-failure analysis, agent handoffs, local git repositories, brainstorming with a model
of your choice, and a fleet of concurrent AI runs.

It is also a **harness** for the models it runs: prompts, a tool loop, sampling control, scoring
and an evaluation battery all live here, because "this prompt is better" is otherwise a feeling.

Your code, your repositories and your `~/.claude` config never leave the machine unless you add a
remote provider key yourself.

---

## What's in it

| Screen | What it does |
| --- | --- |
| **Today** | The ranked next few things, and a Briefing: what changed since yesterday, what needs you, today's plan |
| **Work** | Every item from every connector in one list — PRs, builds, issues, reviews, calendar events, docs |
| **Builds** | Failure analysis: the failing job/step/test, ranked hypotheses with cited evidence, suggested fixes |
| **Handoffs** | A brief for a coding agent — context, point-of-failure log, safety rails — kept in a history you can revisit |
| **Brainstorm** | Chat with a local or remote model, with repo and work-item context attached; or the real Claude Code TUI in a terminal |
| **Repos** | Local git: status, changes, commit, push, branches, worktrees, source drift, conflict prediction, squash |
| **Fleet** | Concurrent AI runs — background analysis, headless edit runs in their own worktree, live terminals |
| **Settings** | Sources, models, capabilities (skills/MCP/plugins), repos & runs, workspace, monitoring |

Every screen shows real data or an honest empty state. There are no fixtures in the running app.

---

## The three processes

This is the thing to understand first.

| Process | Where it runs | Why |
| --- | --- | --- |
| `edge` | Docker, nginx on **:80 / :443** | The only published ports — routes by hostname, terminates TLS |
| `frontend` | Docker, nginx (internal) | Vue 3 SPA |
| `backend` | Docker, Spring Boot (internal) | API, database, model routing |
| **host agent** | **Your machine**, Node on 127.0.0.1:**8765** | Everything a container cannot reach |

Only the edge publishes anything, so a busy 8080 or 5432 on your machine is no longer a reason
DevLoom won't start. Move the two it does use with `DEVLOOM_HTTP_PORT` / `DEVLOOM_HTTPS_PORT`.

The backend runs in a container, so it *cannot* touch your filesystem, your git repositories, your
`claude` CLI, your `~/.claude` config, or raise a desktop notification. All of that goes through
the host agent (`agent/devloom-agent.mjs`).

**If a repo, terminal, capability or notification feature does nothing, the agent is usually not
running.**

```bash
node agent/devloom-agent.mjs     # foreground; `npm install` in agent/ only for the PTY terminal
```

---

## Quick start

```bash
# a local CA + certificate for the .dev names (once; uses Docker, installs nothing)
sh edge/make-certs.sh

# core stack: database + backend + frontend + edge
docker compose up -d --build

# optionally add local models (Ollama) and observability (Langfuse)
docker compose --profile ai up -d
docker compose --profile obs up -d

# the host agent, on your machine — not in Docker
node agent/devloom-agent.mjs
```

Then open **http://localhost**. The API is on http://localhost/api/v1.

### Using the domain names

Two one-time steps: make the names resolve, and trust the CA. Neither is optional for a `.dev`
name. `.dev` is on the HSTS preload list built into every major browser, so the address is
rewritten to `https://` before the request is sent, and an HSTS domain gives you no "proceed
anyway" button for an untrusted certificate — without both steps the names simply do not load.

`http://localhost` needs neither and always works, so you can skip this entirely.

Generate the certificate first:

```bash
sh edge/make-certs.sh
```

**Windows** — in an **Administrator** PowerShell, from the repo root:

```powershell
Add-Content -Path "$env:WINDIR\System32\drivers\etc\hosts" -Encoding ascii `
  -Value "127.0.0.1 mycompanion-devloom.dev api.mycompanion-devloom.dev portal.mycompanion-devloom.dev"

Import-Certificate -FilePath .\edge\certs\devloom-local-ca.crt -CertStoreLocation Cert:\LocalMachine\Root
```

**Debian / Ubuntu** — the system store covers curl and most tools, but Chrome, Chromium and
Firefox each keep their own (NSS), which is why the third step exists and why skipping it looks
like "curl works, the browser doesn't":

```bash
echo "127.0.0.1 mycompanion-devloom.dev api.mycompanion-devloom.dev portal.mycompanion-devloom.dev"   | sudo tee -a /etc/hosts

# system store — the file must keep a .crt extension or update-ca-certificates ignores it
sudo cp edge/certs/devloom-local-ca.crt /usr/local/share/ca-certificates/devloom-local-ca.crt
sudo update-ca-certificates

# browsers (NSS). Chrome/Chromium:
sudo apt install -y libnss3-tools
certutil -d sql:$HOME/.pki/nssdb -A -t "C,," -n "DevLoom Local CA" -i edge/certs/devloom-local-ca.crt

# Firefox keeps one store per profile:
for prof in ~/.mozilla/firefox/*/; do
  certutil -d sql:"$prof" -A -t "C,," -n "DevLoom Local CA" -i edge/certs/devloom-local-ca.crt
done
```

Restart the browser afterwards — the trust store is read at startup.

| URL | Serves |
| --- | --- |
| `https://mycompanion-devloom.dev` | the app |
| `https://portal.mycompanion-devloom.dev` | the app |
| `https://api.mycompanion-devloom.dev/api/v1/…` | the API on its own name |

The CA is generated on your machine, its key never leaves `edge/certs/` (gitignored), and it can
only vouch for these names. Nothing is registered publicly — they mean something only because your
hosts file says so.

Two things that look like failures and are not:

- **Windows `curl` fails with `CRYPT_E_NO_REVOCATION_CHECK`** while the browser is happy. Schannel
  insists on checking revocation, and a local CA publishes no CRL. Pass `--ssl-no-revoke`.
- **Node ignores the OS trust store.** If you point `eval/` or a script at the https API, set
  `NODE_EXTRA_CA_CERTS=edge/certs/devloom-local-ca.crt`. The default `http://localhost/api/v1`
  needs nothing.

## Configuration

Copy `backend/.env.example` to `backend/.env` (gitignored) and fill in what you use. Nothing is
required to start: with no connectors configured the app runs and shows empty states.

| Setting | For |
| --- | --- |
| `DEVLOOM_SECRET` | Master key for encrypting provider API keys at rest (AES-GCM). Required before you can save keys from the UI; keep it stable, rotating it invalidates stored keys |
| `DEVLOOM_GITHUB_TOKEN` | GitHub connector (a read-only fine-grained PAT is enough) |
| `DEVLOOM_JIRA_*` | Jira **Data Center / on-premises** (PAT + REST v2). Cloud is configured per instance in Settings › Sources |
| `DEVLOOM_NOTION_TOKEN` | Notion connector |
| `DEVLOOM_OLLAMA_URL` | Local models |
| `DEVLOOM_LANGFUSE_*` | Trace and score export |
| `TZ` | So "now" and event times line up |

Secrets are encrypted and stored apart from configuration, and never enter exports, backups or
logs. Non-secret preferences live in the database and are edited in Settings, not in files.

---

## The harness

Product code depends only on `LlmPort`. `LlmRouter` picks the adapter by model name (`claude-*` →
Anthropic, `gpt-*`/`o*` → OpenAI, everything else → Ollama) and falls back to a local model when a
remote one is unavailable or over budget. **Model choice is per screen**, not one global setting.

Two Claude paths exist and are not interchangeable:

- **`claude -p` (headless)** — agentic, can edit files. Used by Fleet background edit runs.
- **PTY terminal** — the real interactive `claude` TUI over a WebSocket. These persist: they
  survive their socket closing, re-attach with replayed scrollback, and are garbage-collected when
  idle and detached.

Around whichever model you pick:

- **Tool loop** (`ai/ToolLoop`) — gives any model, local ones included, real tool calls: read,
  search, list and (on an isolated edit run) write files, plus whatever MCP servers you enabled.
  It resolves aliased tool names, notices a model re-fetching what it already has, and terminates.
- **Sampling** (`ai/Sampling`) — two bands, because one number would have to be wrong for one of
  them: grounded work (repo analysis, build diagnosis, judging) samples near-greedily, brainstorming
  stays warm. An optional **seed** pins grounded sampling for reproducibility (off by default —
  re-running exists partly to harvest variance). Adjustable globally and **per model** in
  Settings › Models › Advanced.
- **Context window** — every Ollama call sends a real `num_ctx` (the configured cap bounded by the
  model's own limit), and the tool loop budgets the conversation against the same window, clipping
  tool results with a note instead of letting Ollama silently truncate the system prompt off the
  front. Remote providers are exempt: their windows are huge and they fail loudly.
- **Prompts** (`ai/PromptLibrary`) — served from Langfuse when configured so a prompt can be edited
  without a rebuild; the compiled-in constant is the fallback and is what ships.
- **Scoring — two axes, deliberately kept apart.** `RunQuality` scores the *process* (repeated
  calls, invented tool names, hitting the step cap, ending on a question). `AnswerJudge` rules on
  whether the run *did the thing it was asked* — its verdict is **schema-constrained JSON** on
  local models (the shape is guaranteed by decoding, so a malformed reply can't silently drop a
  verdict), with the prose parse kept as the fallback for remote models. A run can be a clean 1.00
  and still wrong, which is the point of not merging them. Both are exported to Langfuse.
- **Repair** — when the judge says a read-only run missed the task, it gets one more attempt, kept
  only if it scored better. Edit runs that already wrote a file are excluded: their changes are on
  disk and a second pass would write over work that landed.

### Evaluating it

Local models vary run to run, so a prompt or tool change looks like whatever the last attempt did.
The battery in `eval/` runs questions with known answers through the real `/fleet/runs` path:

```bash
node eval/run.mjs --models qwen2.5-coder:7b --reps 3 --save baseline.json
node eval/run.mjs --models qwen2.5-coder:7b --reps 3 --compare baseline.json
node eval/judge.mjs --models qwen2.5-coder:7b --reps 2    # does the judge agree with the truth?
node eval/features.mjs                                    # a feature ladder, graded on files written
node eval/trend.mjs                                       # pass rates over time; flags regressions
```

Every battery appends a row to `eval/history.jsonl`, so a regression shows up as a `▼` in the
trend instead of depending on someone remembering to compare baselines.

It reports correctness, run quality, penalties by name, and how often the retry fired and rescued
a run. Change anything in `ai/` and re-run it — a claim of improvement without a moved pass rate
is a guess.

---

## Working on it

```bash
cd frontend && npx vue-tsc --noEmit    # the primary frontend gate
cd frontend && npm run dev             # vite dev server
node --check agent/devloom-agent.mjs   # agent syntax gate

# the backend compiles in a container — no local JDK/Maven needed
docker run --rm -v "$PWD/backend:/app" -v devloom_m2:/root/.m2 -w /app \
  maven:3.9-eclipse-temurin-25 mvn -q -o -DskipTests compile
```

There is no unit-test harness. Verification is the typecheck, the containerized compile, then
exercising the running app with `curl` and the browser — and `eval/` for anything that changes how
a model behaves.

---

## Layout

```
backend/     Spring Boot: connectors, priority engine, AI adapters, Fleet, API
frontend/    Vue 3 + TypeScript SPA
agent/       the host agent — repos, terminals, Claude bridge, capabilities, notifications
eval/        the model harness: battery, judge validation, feature ladder
docs/        connector specs, deployment, feature specs and plans
```

`SPEC.md` covers product and technical intent, `UI-SPEC.md` and `DESIGN.md` the design system,
`CLAUDE.md` the conventions that will bite you.

---

## Known limits

- **Local models are not ready to write code.** They are good at reading it — what a file does, why
  a build failed, what changed. Asked to *make* a change they often describe it instead. Measured,
  not assumed: `eval/features.mjs` grades files written rather than prose. Fleet edit runs on a
  local model therefore always get their own git worktree and branch, so a bad one is discarded
  rather than landed.
- A local edit run's work is never executed — there is no write → run tests → fix loop yet.
- Jira **Data Center / on-premises** works (PAT + REST v2). Jira **Cloud** is implemented — a
  separate deployment using Basic `email:apiToken`, REST v3 and ADF flattening — but it calls
  `/rest/api/3/search`, which Atlassian removed; it needs porting to `/rest/api/3/search/jql`.
- Streaming is implemented for local models; remote providers still answer in one piece.
