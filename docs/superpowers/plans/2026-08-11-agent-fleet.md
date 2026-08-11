# Agent Fleet + Repos Worktree Grouping — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans or subagent-driven-development to implement task-by-task. Steps use `- [ ]`.

**Goal:** Make concurrent AI work first-class — a Fleet board of interactive + background agent runs, worktree-isolated for safe parallelism, plus a worktree-grouping view on the Repositories page.

**Architecture:** Backend tracks runs in `agent_run` and drives background runs through the host agent's headless `claude -p`; the Today notifications surface finish/fail. Repos becomes worktree-aware by grouping repos that share a `git rev-parse --git-common-dir`.

**Tech Stack:** Java 25 / Spring Boot 4.1 / Postgres+Flyway (JPA), Vue 3 + TS + Pinia, Node host agent.

## Global Constraints

- Agent HTTP endpoints use Node built-ins only — **no new npm deps**.
- Backend Jackson-neutral (`com.fasterxml.jackson`); persisted non-secret settings in `app_config`.
- Verify with `npx vue-tsc --noEmit`, `mvn -q -o -DskipTests compile` (maven container), `curl`, browser.
- Background runs never push/merge/deploy (permission disallow-list + safety prompt).
- Worktree run branches named `devloom/run-<id>`; grouping detection via git common-dir.
- Slices ship independently; this plan details **Slice 1** fully and outlines 2–5 (expanded when reached).

---

## SLICE 1 — Repos worktree grouping (standalone, foundational)

### Task 1: Agent — expose worktree identity + list

**Files:** Modify `agent/devloom-agent.mjs`

**Interfaces:**
- `repoInfo(dir)` return gains `commonDir: string` (absolute) and `isLinkedWorktree: boolean`.
- New route `POST /repos/worktrees { path }` → `{ worktrees: [{ path, branch, head, bare, detached, locked }] }`.

- [ ] **Step 1:** In `repoInfo`, add two git calls to the `Promise.all` and compute fields.
```js
// add to Promise.all: git(dir, ['rev-parse','--git-common-dir'])  → commonRes
const commonRaw = commonRes.code === 0 ? commonRes.out.trim() : '.git'
const commonDir = path.resolve(dir, commonRaw)
let isLinkedWorktree = false
try { isLinkedWorktree = fs.statSync(path.join(dir, '.git')).isFile() } catch { /* dir → main */ }
```
Add `commonDir, isLinkedWorktree` to the returned object.

- [ ] **Step 2:** Add a worktree-list helper + route.
```js
async function worktrees(dir) {
  const r = await git(dir, ['worktree', 'list', '--porcelain'])
  if (r.code !== 0) return { worktrees: [] }
  const out = [], blocks = r.out.split('\n\n')
  for (const b of blocks) {
    const wt = { path: '', branch: null, head: null, bare: false, detached: false, locked: false }
    for (const line of b.split('\n')) {
      if (line.startsWith('worktree ')) wt.path = line.slice(9).trim()
      else if (line.startsWith('branch ')) wt.branch = line.slice(7).trim().replace(/^refs\/heads\//, '')
      else if (line.startsWith('HEAD ')) wt.head = line.slice(5).trim()
      else if (line === 'bare') wt.bare = true
      else if (line === 'detached') wt.detached = true
      else if (line.startsWith('locked')) wt.locked = true
    }
    if (wt.path) out.push(wt)
  }
  return { worktrees: out }
}
```
Route (beside `/repos/source`): `POST /repos/worktrees` → `json(res, 200, await worktrees(p))`.

- [ ] **Step 3:** `node --check agent/devloom-agent.mjs` → agent-ok. Restart the agent.
- [ ] **Step 4:** Verify: `curl -s -X POST 127.0.0.1:8765/repos/worktrees -d '{"path":"C:/Users/saras/Documents/projects/dev-loom"}'` returns the worktree list.
- [ ] **Step 5:** Commit `agent: expose git common-dir + worktree list`.

### Task 2: Backend — RepoView worktree fields + worktrees endpoint

**Files:** Modify `HostAgentClient.java`, `Dto.java`, `RepoService.java`, `ApiController.java`

**Interfaces:**
- `Dto.RepoView` gains `String commonDir, boolean isLinkedWorktree` (trailing fields).
- `Dto.WorktreeInfo(String path, String branch, String head, boolean bare, boolean detached, boolean locked, boolean tracked, String repoId)`.
- `HostAgentClient.worktrees(String path)`; `RepoService.worktrees(String id)` → `List<Dto.WorktreeInfo>` (marks each worktree `tracked` + `repoId` if its path matches a tracked repo, case-insensitive).
- `GET /repos/{id}/worktrees`.

- [ ] **Step 1:** `HostAgentClient.worktrees`:
```java
public Map<String, Object> worktrees(String path) { return post("/repos/worktrees", Map.of("path", path)); }
```
- [ ] **Step 2:** Extend `Dto.RepoView` with `String commonDir, boolean isLinkedWorktree` (append). Add `Dto.WorktreeInfo`. Update both `view(...)` and `stored(...)` construction sites in `RepoService` (stored → `"", false`; view → from the agent map: `str(info,"commonDir")`, `Boolean.TRUE.equals(info.get("isLinkedWorktree"))`).
- [ ] **Step 3:** `RepoService.worktrees(id)`:
```java
public List<Dto.WorktreeInfo> worktrees(String id) {
    String path = pathOf(id);
    Map<String, Object> res = agent.worktrees(path);
    List<GitRepoEntity> all = repos.findAll();
    List<Dto.WorktreeInfo> out = new ArrayList<>();
    for (Object o : (List<?>) res.getOrDefault("worktrees", List.of())) {
        Map<String,Object> w = (Map<String,Object>) o;
        String wp = str(w, "path");
        GitRepoEntity tracked = all.stream()
            .filter(r -> r.getPath().equalsIgnoreCase(wp)).findFirst().orElse(null);
        out.add(new Dto.WorktreeInfo(wp,
            w.get("branch")==null?null:String.valueOf(w.get("branch")),
            w.get("head")==null?null:String.valueOf(w.get("head")),
            Boolean.TRUE.equals(w.get("bare")), Boolean.TRUE.equals(w.get("detached")),
            Boolean.TRUE.equals(w.get("locked")), tracked != null,
            tracked == null ? null : String.valueOf(tracked.getId())));
    }
    return out;
}
```
- [ ] **Step 4:** `ApiController`: `@GetMapping("/repos/{id}/worktrees") → repoService.worktrees(id)`.
- [ ] **Step 5:** Compile backend → exit 0. Commit `repos: worktree identity on RepoView + /repos/{id}/worktrees`.

### Task 3: Frontend — grouping toggle + nested rendering

**Files:** Modify `types.ts`, `api/http.ts|index.ts|stub.ts`, `views/ReposView.vue`

**Interfaces:**
- `RepoView` gains `commonDir: string; isLinkedWorktree: boolean`.
- `WorktreeInfo { path; branch: string|null; head: string|null; bare; detached; locked; tracked; repoId: string|null }`.
- `repoWorktrees(id): Promise<WorktreeInfo[]>`.

- [ ] **Step 1:** Types: add fields to `RepoView`; add `WorktreeInfo`.
- [ ] **Step 2:** API: `export const repoWorktrees = (id) => post<WorktreeInfo[]>(\`/repos/${id}/worktrees\`, {})`; re-export; stub returns `[]`.
- [ ] **Step 3:** ReposView grouping:
  - Add `groupWorktrees = ref(localStorage.getItem('devloom.groupWorktrees') !== 'off')` and a toggle button in the header that persists it.
  - Computed `grouped`: when on, group `repos.value` by `commonDir`; primary = the entry with `isLinkedWorktree===false` (fallback: first). Produce `[{ primary: RepoView, children: RepoView[] }]`. When off, each repo is its own group with no children.
  - Render: primary card as today; when grouped and expanded, a nested list of child worktree rows (branch + reuse health chips). A per-primary "⑂ worktrees (N)" expander that also lazy-loads `repoWorktrees(primary.id)` to include on-disk worktrees not tracked (show branch + an "Add" action reusing `addRepoPath(path)` for untracked ones).
  - A worktree row tagged `devloom/run-*` shows a "run" badge.
- [ ] **Step 4:** `npx vue-tsc --noEmit` → clean.
- [ ] **Step 5:** Commit `repos: Group worktrees toggle + nested worktree view`.
- [ ] **Step 6 (runtime):** Rebuild frontend+backend. In `dummy-project-1` create two worktrees:
  `git -C <projects>/dummy-project-1 worktree add ../dummy-project-1-feature1 -b feature1` and `…-feature2 -b feature2`. Add the primary (and/or worktrees) in DevLoom; verify grouped view nests feature1/feature2 with their branches, and toggling off returns the flat list.

---

## SLICE 2 — Run model + background execution (outline)

- `V17__agent_run.sql` + `AgentRunEntity`/repo (fields per spec §11).
- Agent: run manager + `POST /agent/run`, `GET /agent/run/{id}`, `POST /agent/run/{id}/cancel` — spawn `claude -p --output-format json` detached in `cwd`, track status/output/exit; permission → `--permission-mode plan` (readonly) or `acceptEdits` + disallow push/deploy (edit), optional test allow-list.
- Backend `FleetService` (create/list/get/cancel), `Dto.AgentRun`, endpoints `GET/POST /fleet/runs`, `GET /fleet/runs/{id}`, `POST /fleet/runs/{id}/cancel`; a `@Scheduled(~10s)` poller transitions `running → review/failed`.
- Frontend: launch dialog (repo, prompt, model, permission, tests), a basic Fleet list.
- No worktrees yet: runs in main checkout, require clean tree.

## SLICE 3 — Fleet board UI (outline)

- New route + nav **Fleet**; `FleetView.vue` with Needs-review / Running / Active / Recent groups.
- Run detail: transcript (from `claude -p` result), diff via `/repos/changes` on `run_dir`, actions Cancel/Open-in-terminal.
- Interactive runs: create an `agent_run` (kind=interactive) when a claude-cli brainstorm/handoff run starts; show as `active`, click → its terminal.

## SLICE 4 — Worktree isolation + diff review (outline)

- Agent `POST /agent/worktree/add|remove`; run manager uses a worktree for isolated edit runs.
- `fleet.worktreesDefault` setting (Settings › Fleet) + per-launch checkbox + per-repo permission memory.
- Run detail Apply (`branch`|`patch`) / Discard; startup reconcile of orphaned `devloom/run-*`.

## SLICE 5 — Notifications + attention routing (outline)

- `FleetService` poller fires `NotificationService`: finish → normal toast, fail → urgent toast.
- Fleet "Needs review" headline; optional Today surface of runs needing review.

## Self-Review

- Spec §9 (grouping) → Slice 1 Tasks 1–3. §7/§11/§12 (runs) → Slice 2. §5 (board) → Slice 3.
  §8 (isolation/review) → Slice 4. §10 (notifications) → Slice 5. §16 testing → Task 3 Step 6 + later.
- No placeholders in Slice 1 (full code). Types: `RepoView.commonDir/isLinkedWorktree` and
  `WorktreeInfo` defined once and reused; `repoWorktrees` name consistent across api/index/stub/view.
