# Agent Fleet + Repos Worktree Grouping — Design

**Status:** Draft 1
**Date:** 2026-08-11
**Surface:** new **Fleet** screen · **Repositories** (worktree grouping) · Settings · host agent

## 1. Summary

With AI, an engineer runs several pieces of work at once. DevLoom can *launch* agents (Run a
handoff in the CLI, start a brainstorm) but only ever shows **one** at a time — the terminal you
are watching. This feature makes concurrency first-class:

- **Agent Fleet** — a dedicated board of every AI run launched from DevLoom, interactive and
  background, with attention-routed status ("which agents need me now?") wired to the desktop
  notifications already shipped.
- **Background runs** — headless `claude -p` runs the host agent executes detached, so you fan out
  work and check back instead of babysitting a terminal. Autonomy is **configurable per launch**
  (read-only or edit-in-repo), never pushing/deploying.
- **Worktree isolation** (configurable, default on) — a background *edit* run executes in its own
  `git worktree`, so many agents can safely work the same repo at once without stomping your
  checkout or each other.
- **Repos worktree grouping** — because worktrees are separate directories sharing one repo, the
  Repositories page gains a toggle to group them: one logical repo with its worktrees nested under
  it, each showing its branch and health.

Only DevLoom-launched runs are tracked in v1 (no discovery of sessions from your own terminals).

## 2. Goals

1. See all concurrent agent runs at a glance, grouped by what needs the user.
2. Launch background runs with explicit, per-launch autonomy that never pushes/deploys.
3. Let many agents work one repo in parallel via optional worktree isolation.
4. Make worktrees legible in Repos (grouped under their logical repo, branch-aware).
5. Reuse existing plumbing: `claude -p`, the PTY terminal, `/repos/changes`, NotificationService.

## 3. Non-goals

- Discovering agent sessions started outside DevLoom (own terminals) — later.
- A true security jail for autonomous edits (v1 is strong guardrails, see §7).
- Multi-user fleets, remote runners, or scheduling/cron of runs.
- Auto-merging or auto-pushing a run's result (the user always reviews and pushes via Repos).
- Editing arbitrary run parameters mid-flight; a run is launch-then-observe.

## 4. Concepts

### 4.1 Run kinds
- **Interactive** — the embedded claude-cli terminal (from Brainstorm or a handoff "Run in CLI").
  It appears on the board as `active`; the user clicks to jump into its terminal. DevLoom does not
  synthesize progress for it.
- **Background** — a detached headless `claude -p` run in a repo (or its worktree). Full lifecycle.

### 4.2 Run lifecycle (background)
`running → review → done`, or `failed` / `canceled`.
- **running** — the `claude -p` process is executing.
- **review** — the process finished and produced changes/output the user has not accepted yet.
- **done** — the user applied or dismissed the result (a read-only run with no changes goes here on
  acknowledgement).
- **failed** — non-zero exit or a run error; the error is captured.
- **canceled** — the user stopped it.

Interactive runs use `active` / `ended` only.

## 5. Fleet board (new screen)

A new top-level nav item **Fleet**. Attention-routed columns/groups:

- **Needs review** — background runs in `review` (finished, unaccepted) and `failed`. The queue that
  matters; count is the headline ("2 need review").
- **Running** — background runs in `running`, with elapsed time and repo/model/permission badges.
- **Active** — interactive terminal runs (`active`), each a click-through to its terminal.
- **Recent** — `done` / `canceled` / `ended`, collapsed.

Each card shows: title, repo (and worktree/branch if isolated), model, a kind badge
(`bg` / `⌨ interactive`), a permission badge (`read-only` / `edit`), and status/elapsed.

**Launch:** a "New run" button opens the launch dialog (§6). Runs can also be launched from the
handoff ("Run in background") and from a repo card.

**Run detail** (click a background run): the run's output/transcript (from `claude -p` JSON), its
diff (via the existing `/repos/changes` against the run's dir), and actions — **Apply**, **Discard**,
**Open in terminal** (continue interactively in the run's dir), **Re-run**, **Cancel** (while
running).

## 6. Launching a background run

Launch dialog fields:
- **Repository** (from tracked repos).
- **Prompt / task** (free text; pre-filled when launched from a handoff = the handoff artifact).
- **Model** (local + keyed remote; not the interactive-only agent modes).
- **Permission**: `read-only` or `edit-in-repo` — remembered per repo.
- **Run tests** (checkbox; only meaningful with `edit-in-repo`).
- **Isolate in a worktree** (checkbox; default from the global setting, see §7/§8).

Backend creates an `agent_run` row (`running`) and calls the host agent to spawn the process.

## 7. Background execution + autonomy (host agent)

The agent gains a run manager: it spawns `claude -p --output-format json` detached in the run's
working directory, tracks `{status, output, result, exitCode}` in memory, and exposes status.

Permission mapping (validated against the installed `claude` at build time; adjust flags to match):
- **read-only** → `--permission-mode plan` (reads + proposes, writes nothing).
- **edit-in-repo** → `--permission-mode acceptEdits` plus a **disallow-list** blocking push/deploy/
  destructive git (e.g. disallow `Bash(git push:*)`, `Bash(git commit:*)` unless later allowed,
  deploy commands), and — when **Run tests** is on — an allow-list for the repo's test runner.

Every background run also carries the handoff safety preamble in its system prompt (no push/merge/
deploy/delete, no network beyond what the task needs). This is **strong guardrails, not a jail**:
true isolation from the user's checkout comes from worktrees (§8); network/credential sandboxing is
out of scope for v1 and called out in the UI.

Agent endpoints:
- `POST /agent/run { cwd, prompt, model, permission, allowTests }` → `{ runId }` (spawns detached).
- `GET /agent/run/{runId}` → `{ status, result, error, exitCode }`.
- `POST /agent/run/{runId}/cancel` → kills the process → `{ ok }`.

## 8. Worktree isolation (configurable)

- Global setting `fleet.worktreesDefault` (Settings › Fleet, default **on**); the launch dialog's
  "Isolate in a worktree" checkbox defaults to it and can be overridden per run.
- When **on** for an edit-run: the agent runs `git worktree add <tmp> -b devloom/run-<id> <base>`
  off the repo, executes `claude -p` there, and the run's dir is the worktree. Many runs → many
  worktrees → safe parallelism.
- When **off**: the run executes in the repo's main checkout and **requires a clean working tree**
  (otherwise the launch is rejected with a clear message).
- **Apply** (from run detail): for an isolated run, offer to (a) keep the run's branch
  `devloom/run-<id>` (fast, no merge) or (b) apply its diff as a patch onto the current branch of the
  main checkout; either way the worktree is then removed. **Discard** removes the worktree and its
  branch. Read-only runs write nothing, so Apply/Discard are just "acknowledge".
- Agent endpoints: `POST /agent/worktree/add { repoPath, branch }` → `{ path }`;
  `POST /agent/worktree/remove { path, force }` → `{ ok }`. (Reused by the run manager and, if
  needed, the Repos UI.)

## 9. Repos worktree grouping

Worktrees are separate directories that share one repository. DevLoom groups them.

- **Detection:** each repo's identity is its **common git dir** (`git rev-parse --git-common-dir`,
  normalized). Repos sharing a common dir are worktrees of one logical repo; the **primary** is the
  main worktree (`git worktree list --porcelain` marks it / it is the non-linked one).
- **Agent:** extend the repo status payload with `commonDir` and `isLinkedWorktree`, and add
  `POST /repos/worktrees { path }` → the `git worktree list --porcelain` result (path, branch, HEAD,
  bare/detached, locked) for the repo.
- **UI toggle** on the Repositories page: **"Group worktrees"** (persisted in `localStorage`,
  default **on**).
  - **On:** repos sharing a common dir collapse into one primary card; its worktrees render as
    nested rows, each showing its branch and the same health chips (working tree, upstream, source,
    conflict). The example renders as:
    `lccches-implementation` (main · dev) → `…-feature1` (feature1) · `…-feature2` (feature2).
  - **Off:** today's flat list — every worktree is its own row (unchanged behavior).
- DevLoom-created run worktrees (`devloom/run-*`) appear under their primary while the run is active
  and vanish when the run is applied/discarded; they are visually tagged as run worktrees and are
  not persisted as tracked repos.
- A worktree row's actions are scoped to that worktree (branch/commit/push operate on it).

## 10. Notifications integration

Reuse `NotificationService` + the branded desktop toast:
- A background run entering **review** (finished) → a normal toast: "Run finished · `<title>` ·
  review in Fleet."
- A background run **failed** → an urgent toast (sticky, alarm sound): "Run failed · `<title>`."
- Gated by the same enable/quiet-hours settings. The Fleet "Needs review" count is the in-app
  mirror of these.

## 11. Data model

Migration `V17__agent_run.sql`:
```sql
CREATE TABLE agent_run (
    id                BIGSERIAL PRIMARY KEY,
    title             VARCHAR(300) NOT NULL,
    repo_path         TEXT NOT NULL,
    run_dir           TEXT,                    -- worktree dir, or repo_path when not isolated
    branch            VARCHAR(255),            -- devloom/run-<id> when isolated
    kind              VARCHAR(16) NOT NULL,    -- 'interactive' | 'background'
    permission        VARCHAR(16),             -- 'readonly' | 'edit' (background)
    allow_tests       BOOLEAN NOT NULL DEFAULT false,
    isolated          BOOLEAN NOT NULL DEFAULT false,
    model             VARCHAR(120),
    status            VARCHAR(16) NOT NULL,    -- running|review|done|failed|canceled|active|ended
    agent_run_id      VARCHAR(80),             -- the host agent's in-memory run id
    claude_session_id VARCHAR(80),             -- for interactive / continue-in-terminal
    brainstorm_session_id BIGINT,              -- link when launched as/into a brainstorm
    result_summary    TEXT,
    error             TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at        TIMESTAMPTZ,
    finished_at       TIMESTAMPTZ
);
CREATE INDEX idx_agent_run_status ON agent_run (status, created_at DESC);
```

Config (`app_config`): `fleet.worktreesDefault` (bool). Per-repo permission memory reuses the
existing per-repo config pattern (`fleet.perm.<repoId>`).

## 12. API surface (backend)

- `GET /fleet/runs` → list of runs (grouped client-side by status).
- `POST /fleet/runs` (launch) `{ repoId, prompt, model, permission, allowTests, isolate }` → the run.
- `GET /fleet/runs/{id}` → run + its diff (changed files) when finished.
- `POST /fleet/runs/{id}/cancel` · `POST /fleet/runs/{id}/apply` `{ mode: 'branch'|'patch' }` ·
  `POST /fleet/runs/{id}/discard` · `POST /fleet/runs/{id}/ack` (read-only acknowledge).
- Repos: `GET /repos` gains `commonDir` + `isLinkedWorktree` per repo; `GET /repos/{id}/worktrees`.
- A `@Scheduled(fixedRate=~10s)` poller in `FleetService` refreshes `running` runs from the agent,
  transitions them to `review`/`failed`, sets `finished_at`, and fires the notification once.

## 13. Error & edge cases

- **Agent offline:** launching a background run is disabled with "requires the host agent"; existing
  rows show their last known status.
- **Dirty tree, isolation off:** launch rejected with a clear message (offer to enable isolation).
- **Worktree add fails** (e.g. branch exists, locked): surface the git error; no row is left in
  `running`.
- **claude not installed / plan-mode unsupported flag:** the run fails with the captured stderr; the
  permission-flag mapping is validated at build time.
- **Apply-as-patch conflicts:** report the conflicting files; leave the worktree intact so the user
  can open it in a terminal and resolve.
- **Orphaned worktrees** (backend restarted mid-run): a reconcile on startup marks `running` rows
  with no live agent run as `failed`, and lists `devloom/run-*` worktrees for cleanup.
- **Grouping with no worktrees:** the toggle is a no-op; every repo is its own primary.

## 14. Success criteria

- Launching two background runs on the same repo creates two worktrees; both run concurrently and
  appear under **Running**, then **Needs review**; neither touches the main checkout.
- A read-only run writes nothing to disk; its detail shows the proposed plan; **Acknowledge** moves
  it to Recent.
- An edit run's detail shows its diff; **Apply (branch)** leaves `devloom/run-<id>`; **Discard**
  removes the worktree and branch.
- A finished run pops a desktop toast; a failed run pops an urgent (sticky) one.
- On Repos with **Group worktrees** on, `lccches-implementation` shows one primary with its two
  feature worktrees nested, each labeled with its branch; toggling off returns to the flat list.
- No background run can `git push` (blocked by the disallow-list) — verified by attempting a task
  that tries to push.

## 15. Rollout slices

1. **Repos worktree grouping** (standalone, foundational). Agent `commonDir`/`isLinkedWorktree` +
   `/repos/worktrees`; Repos "Group worktrees" toggle + nested rendering. Ships useful on its own.
2. **Run model + background execution.** `V17`, `FleetService`, agent `/agent/run*`, launch dialog,
   a basic Fleet list (running → review/failed), cancel. No worktrees yet (runs in main checkout,
   clean-tree required).
3. **Fleet board UI.** Attention-routed columns, run detail + transcript, interactive runs on the
   board, "Open in terminal".
4. **Worktree isolation + diff review.** Agent worktree add/remove, isolated runs, Apply
   (branch/patch) / Discard, `fleet.worktreesDefault` setting.
5. **Notifications + attention routing.** Finish/fail toasts via NotificationService; polish the
   "Needs review" headline.

Each slice is independently shippable. Slice 1 may be split into its own plan if preferred.

## 16. Testing

Use the existing dummy repos (`dummy-project-1/2/3` under `Documents/projects`, all on GitHub):
- **Grouping:** in `dummy-project-1`, create two worktrees
  (`git worktree add ../dummy-project-1-feature1 -b feature1`, `…-feature2 -b feature2`), add the
  repo(s) in DevLoom, and verify grouped vs flat views and per-worktree branches/health.
- **Parallel background runs:** launch two edit runs on `dummy-project-2` (which has a real
  conflict) and confirm two worktrees, concurrent execution, and independent diffs.
- **Guardrail:** launch an edit run whose task asks to `git push`; confirm it cannot.
- **Read-only:** a read-only run on `dummy-project-3` proposes changes without writing.

## 17. Open decisions (chosen defaults; revisit if wrong)

- Worktrees default **on**; per-launch override; global toggle in Settings › Fleet.
- Apply default mode: **keep branch** `devloom/run-<id>` (no auto-merge); patch-onto-current is the
  secondary option.
- Background-run poll interval **~10s**; run output capped/truncated for storage.
- Run worktrees named `devloom/run-<id>`; cleaned on apply/discard and on startup reconcile.
