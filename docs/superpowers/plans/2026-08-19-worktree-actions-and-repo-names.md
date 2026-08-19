# Repos: worktree actions, qualified names, and non-reloading refresh — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a collapsed worktree be acted on, tell two same-named repos apart, and stop every action blanking the repository list.

**Architecture:** Frontend-only. One new pure helper (`qualifyNames`), one new small component (`RepoBrainstormButton`), one new presentational component (`AppToast`), and surgical edits to `ReposView.vue` and `FleetView.vue`. No backend, agent, or database change; no migration.

**Tech Stack:** Vue 3 (`<script setup>`, Composition API), TypeScript, Vite.

## Global Constraints

- **No unit-test harness exists in this project.** Verification is `npx vue-tsc --noEmit`, then exercising the running app. Do not add vitest or any test dependency.
- **The primary repo card must look and behave exactly as it does today.** Tasks 4 and 5 are refactors with no visible change to it.
- **Node on this machine is v22.0.0** — `--experimental-strip-types` is unavailable, so pure helpers are exercised through the running app's console, not a node script.
- **Frontend API facade rule:** any new API call goes in `api/http.ts` *and* `api/stub.ts`, then is re-exported from `api/index.ts`. Views import from `../api` only.
- **The app must be rebuilt to see changes:** `docker build --build-arg VITE_API_BASE=/api/v1 -t ghcr.io/sarasnt/devloom-frontend:latest ./frontend && docker compose -f docker-compose.prebuilt.yml up -d frontend`. Hard-reload the browser — asset hashes are content-derived but `index.html` caches.
- Comments explain *why*, not what. Match surrounding style.

---

### Task 1: `qualifyNames` helper

**Files:**
- Create: `frontend/src/utils/repoNames.ts`

**Interfaces:**
- Consumes: nothing.
- Produces: `qualifyNames(paths: string[]): Map<string, string>` — maps each **original** path string to its display label. Tasks 2 and 3 call this.

- [ ] **Step 1: Write the helper**

```ts
// Display labels for repositories, disambiguated only when they collide.
//
// Computed from the current set of repos and never stored: whether `implementation` is ambiguous
// depends on what else is tracked right now, so a prefix written into git_repo.name would be
// wrong the moment the other repo is removed.
const norm = (p: string) => (p || '').replace(/\\/g, '/').replace(/\/+$/, '')

// Last `depth` segments of a path, joined with '/'. The label is a label, not a path — it uses
// '/' on every platform, and keeps each segment's original casing.
function tail(path: string, depth: number): string {
  const segs = norm(path).split('/').filter(Boolean)
  return segs.slice(Math.max(0, segs.length - depth)).join('/')
}

function segmentCount(path: string): number {
  return norm(path).split('/').filter(Boolean).length
}

export function qualifyNames(paths: string[]): Map<string, string> {
  // Depth per path, grown independently: only the paths still tied get a longer label, so a repo
  // with a unique name is never prefixed.
  const depth = new Map<string, number>(paths.map((p) => [p, 1]))

  for (;;) {
    const byLabel = new Map<string, string[]>()
    for (const p of paths) {
      // Windows paths can differ only by case and must still collide.
      const key = tail(p, depth.get(p)!).toLowerCase()
      const bucket = byLabel.get(key)
      if (bucket) bucket.push(p)
      else byLabel.set(key, [p])
    }

    let grew = false
    for (const bucket of byLabel.values()) {
      if (bucket.length < 2) continue
      for (const p of bucket) {
        // Stop when a path has no more parents to give, or two repos with identical full paths
        // would loop forever.
        if (depth.get(p)! < segmentCount(p)) { depth.set(p, depth.get(p)! + 1); grew = true }
      }
    }
    if (!grew) break
  }

  return new Map(paths.map((p) => [p, tail(p, depth.get(p)!)]))
}
```

- [ ] **Step 2: Typecheck**

```bash
cd frontend && npx vue-tsc --noEmit
```

Expected: exit 0, no output.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/utils/repoNames.ts
git commit -m "repos: qualify colliding repo names by walking up the path"
```

---

### Task 2: Qualified names in ReposView

**Files:**
- Modify: `frontend/src/views/ReposView.vue`

**Interfaces:**
- Consumes: `qualifyNames` from Task 1.
- Produces: `repoLabel(r: RepoView): string` — used by Task 6's worktree rows.

- [ ] **Step 1: Import and add the computed map**

Add to the imports near the top of `<script setup>`:

```ts
import { qualifyNames } from '../utils/repoNames'
```

Add after the `repos` ref (around line 220):

```ts
// Labels are computed over every tracked repo, not just the visible ones, so a name does not
// change when the worktree-grouping toggle hides a row.
const repoLabels = computed(() => qualifyNames(repos.value.map((r) => r.path)))
function repoLabel(r: RepoView): string {
  return repoLabels.value.get(r.path) ?? r.name
}
```

Confirm `computed` is present in the `vue` import; add it if not.

- [ ] **Step 2: Use the label in the card heading**

In the card header (around line 653), replace:

```html
<h3>{{ r.name }}</h3>
```

with:

```html
<h3>{{ repoLabel(r) }}</h3>
```

- [ ] **Step 3: Use the label, plus the branch, in session titles**

In `brainstormHere` (around line 205), replace:

```ts
const title = model === 'claude-cli' ? `Terminal · ${r.name}` : `Brainstorm · ${r.name}`
```

with:

```ts
// A worktree carries its branch, or three worktrees of one repo yield three identical titles.
const label = r.isLinkedWorktree && r.branch ? `${repoLabel(r)} ⎇ ${r.branch}` : repoLabel(r)
const title = model === 'claude-cli' ? `Terminal · ${label}` : `Brainstorm · ${label}`
```

- [ ] **Step 4: Typecheck**

```bash
cd frontend && npx vue-tsc --noEmit
```

Expected: exit 0.

- [ ] **Step 5: Verify in the running app**

Rebuild and hard-reload (see Global Constraints). On `/repos`:
- a repo with a unique folder name shows **no** prefix;
- if two tracked repos share a folder name, both show one (`lccc/implementation`, `personal/implementation`).

If you have no colliding pair, create one: `mkdir -p /tmp/qa-a/implementation /tmp/qa-b/implementation && git -C /tmp/qa-a/implementation init && git -C /tmp/qa-b/implementation init`, then add both via Browse.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/views/ReposView.vue
git commit -m "repos: show qualified names on cards and in session titles"
```

---

### Task 3: Qualified names in FleetView

**Files:**
- Modify: `frontend/src/views/FleetView.vue:62` (the local `repoName`), and its five call sites (248, 266, 281, 297, 317)

**Interfaces:**
- Consumes: `qualifyNames` from Task 1; `fetchRepos` from `../api`.
- Produces: nothing for later tasks.

- [ ] **Step 1: Load the repo list**

FleetView derives names from `repoPath` alone today and never loads repos. Add to `<script setup>`:

```ts
import { qualifyNames } from '../utils/repoNames'
import { fetchRepos } from '../api'

// Fleet shows a repo badge per run but never needed the repo list before; it does now, because a
// name can only be disambiguated against the others.
const repoPaths = ref<string[]>([])
onMounted(async () => {
  try { repoPaths.value = (await fetchRepos()).repos.map((r) => r.path) } catch { /* badges fall back to the basename */ }
})
```

Confirm `ref` and `onMounted` are already imported from `vue`; add whichever is missing. Confirm `fetchRepos` is exported from `../api` — it is, ReposView uses it.

- [ ] **Step 2: Replace the local helper**

Replace the whole function at line 62:

```ts
function repoName(path: string): string {
  const p = (path || '').replace(/\\/g, '/')
  return p.substring(p.lastIndexOf('/') + 1)
}
```

with:

```ts
const repoLabels = computed(() => qualifyNames(repoPaths.value))
// A run can point at a repo DevLoom no longer tracks; that one keeps its plain basename.
function repoName(path: string): string {
  const p = (path || '').replace(/\\/g, '/').replace(/\/+$/, '')
  return repoLabels.value.get(path) ?? p.substring(p.lastIndexOf('/') + 1)
}
```

Add `computed` to the `vue` import if absent. The five call sites keep calling `repoName(...)` and need no edit.

- [ ] **Step 3: Typecheck**

```bash
cd frontend && npx vue-tsc --noEmit
```

Expected: exit 0.

- [ ] **Step 4: Verify in the running app**

Rebuild, hard-reload, open `/fleet`. Repo badges show the same labels as the Repos screen. A run whose repo was removed still shows a bare basename rather than blank.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/views/FleetView.vue
git commit -m "fleet: badge runs with the same qualified repo names"
```

---

### Task 4: Extract `RepoBrainstormButton`

Pure refactor. The primary card must look and behave identically afterwards.

**Files:**
- Create: `frontend/src/components/RepoBrainstormButton.vue`
- Modify: `frontend/src/views/ReposView.vue:862-878` (the `.splitwrap` holding the brainstorm button)

**Interfaces:**
- Consumes: `RepoView` from `../types`.
- Produces: component `<RepoBrainstormButton>` with props `repo: RepoView`, `models: string[]`, `open: boolean`, `disabled: boolean`; emits `toggle` (no payload) and `pick` (payload `string`, the model id). Task 6 mounts a second instance.

- [ ] **Step 1: Create the component**

```vue
<script setup lang="ts">
// The brainstorm menu is absolutely positioned against its own trigger (.splitwrap is
// position: relative), so it cannot be hoisted to one copy per card — it has to render beside
// each button. Hence a component rather than a shared block.
import type { RepoView } from '../types'

defineProps<{
  repo: RepoView
  models: string[]
  open: boolean
  disabled: boolean
}>()
const emit = defineEmits<{ toggle: []; pick: [model: string] }>()

function label(m: string): string {
  return m === 'claude-cli' ? 'Claude CLI · interactive terminal' : m
}
</script>

<template>
  <div class="splitwrap">
    <button
      class="btn brainstorm"
      :disabled="disabled"
      title="Choose a model to brainstorm this repo"
      @click="emit('toggle')"
    >
      ✎ Brainstorm here ▾
    </button>
    <div v-if="open" class="bmenu" @click.self="emit('toggle')">
      <div class="bmlab mono">{{ repo.localOnly ? 'local models only' : 'choose a model' }}</div>
      <button v-for="m in models" :key="m" class="bmi" @click="emit('pick', m)">
        {{ label(m) }}
      </button>
      <div v-if="!models.length" class="bmi empty mono">no local models pulled</div>
    </div>
  </div>
</template>

<style scoped>
.splitwrap { position: relative; display: inline-flex; }
.btn { font-size: 12px; background: transparent; border: 1px solid var(--line); border-radius: 6px; padding: 4px 10px; color: var(--text); cursor: pointer; }
.btn:disabled { opacity: 0.5; cursor: default; }
.btn.brainstorm { border-color: var(--warp); color: var(--warp-hi); }
.bmenu { position: absolute; top: 100%; right: 0; margin-top: 4px; z-index: 20; min-width: 240px; background: var(--panel); border: 1px solid var(--line); border-radius: 8px; padding: 4px; display: flex; flex-direction: column; }
.bmlab { font-size: 10px; color: var(--faint-text); padding: 4px 8px; }
.bmi { text-align: left; font-size: 12px; background: transparent; border: 0; border-radius: 6px; padding: 6px 8px; color: var(--text); cursor: pointer; }
.bmi:hover { background: var(--warp-weft); }
.bmi.empty { color: var(--faint-text); cursor: default; }
</style>
```

Before finishing this step, open `ReposView.vue`'s `<style scoped>` and copy the **actual** current rules for `.btn`, `.btn.brainstorm`, `.bmenu`, `.bmlab`, `.bmi` over the ones above, so the button is pixel-identical. The values above are the shape to fill, not a guess to keep.

- [ ] **Step 2: Use it in the card**

Add the import in `ReposView.vue`:

```ts
import RepoBrainstormButton from '../components/RepoBrainstormButton.vue'
```

Replace lines 862-878 (the `.splitwrap` block containing `✎ Brainstorm here ▾`) with:

```html
<RepoBrainstormButton
  :repo="r"
  :models="repoModels(r)"
  :open="bmenu === r.id"
  :disabled="busy === r.id || !agentUp"
  @toggle="bmenu = bmenu === r.id ? '' : r.id"
  @pick="(m) => brainstormHere(r, m)"
/>
```

Leave `repoModels`, `modelLabel`, `bmenu` and `brainstormHere` in `ReposView.vue` — `modelLabel` may now be unused there; delete it only if `npx vue-tsc --noEmit` reports it unused, otherwise leave it alone.

- [ ] **Step 3: Typecheck**

```bash
cd frontend && npx vue-tsc --noEmit
```

Expected: exit 0.

- [ ] **Step 4: Verify no visible change**

Rebuild, hard-reload, open `/repos`. The Brainstorm button looks identical, its menu opens in the same place, picking a model still opens a session in that repo. This task changes nothing a user can see.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/RepoBrainstormButton.vue frontend/src/views/ReposView.vue
git commit -m "repos: extract the brainstorm split button, no behaviour change"
```

---

### Task 5: Widen the three panels to cover worktree children

Pure refactor. Still no visible change — the panels keep rendering exactly where they do now for the primary card. This only makes them *addressable* by a child id, which Task 6 then uses.

**Files:**
- Modify: `frontend/src/views/ReposView.vue` — health panel `707-782`, branch panel `784-805`, changes panel `983-1023`

**Interfaces:**
- Consumes: `trackedChildren(primary: RepoView): RepoView[]` (already exists, line ~245).
- Produces: `panelRepo(primary: RepoView, openId: string | null): RepoView | null` — Task 6 relies on this resolving to a worktree child.

- [ ] **Step 1: Add the resolver**

Add near `trackedChildren` (around line 248):

```ts
// Which repo a panel should render for: the card's own repo, or one of its worktrees when that
// row is the open one. One copy of each panel then serves the card and every worktree under it —
// extracting them instead would mean a 25-property interface for the health panel alone.
function panelRepo(primary: RepoView, openId: string | null): RepoView | null {
  if (!openId) return null
  if (openId === primary.id) return primary
  return trackedChildren(primary).find((c) => c.id === openId) ?? null
}
```

- [ ] **Step 2: Rewrite the three `v-if`s to bind the resolved repo**

Health panel, line 707. Replace:

```html
<div v-if="openHealth === r.id" class="healthbox">
```

with:

```html
<template v-for="hr in [panelRepo(r, openHealth)]" :key="hr?.id ?? 'none'">
<div v-if="hr" class="healthbox">
```

and close the added `<template>` immediately after that block's closing `</div>` (line 782).

Then, **inside lines 707-782 only**, replace every `r.id` with `hr.id` and every other `r.` with `hr.`. Do not touch occurrences outside that range.

Repeat exactly the same transformation for:
- the branch panel at `784-805`, using `openBranch` and a loop variable `br`;
- the changes panel at `983-1023`, using `openChanges` and a loop variable `cr`.

The `v-for`-over-a-single-element-array is the idiomatic way to bind a local in a Vue template without a wrapper component; `:key` keeps the panel from being reused across different repos.

- [ ] **Step 3: Typecheck**

```bash
cd frontend && npx vue-tsc --noEmit
```

Expected: exit 0. If it reports `hr` is possibly null inside the block, the `v-if="hr"` is on the wrong element — it must be on the `<div>` directly inside the `<template v-for>`.

- [ ] **Step 4: Verify no visible change**

Rebuild, hard-reload. On `/repos`, expand health, branch and changes on a primary card: each opens, shows the same content as before, and closes again. Switch branches and commit from the panels to confirm the handlers still receive the right repo.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/views/ReposView.vue
git commit -m "repos: let the three panels render for a worktree child too"
```

---

### Task 6: Actions on the worktree row

The feature. Everything before this was groundwork.

**Files:**
- Modify: `frontend/src/views/ReposView.vue` — the `.wtrow` block for tracked children (around lines 674-681)

**Interfaces:**
- Consumes: `RepoBrainstormButton` (Task 4), `panelRepo` (Task 5), `repoLabel` (Task 2), and the existing `toggleBranches`, `toggleHealth`, `openChanges`, `repoModels`, `brainstormHere`, `busy`, `agentUp`.
- Produces: nothing.

- [ ] **Step 1: Add the four controls to the tracked-worktree row**

Replace the tracked-children row (the `v-for="c in trackedChildren(r)"` block, around lines 674-681) with:

```html
<div v-for="c in trackedChildren(r)" :key="c.id" class="wtrow">
  <button class="branchbtn mono" :disabled="!agentUp" title="Switch branch" @click="toggleBranches(c)">
    ⎇ {{ c.branch || '—' }} ▾
  </button>
  <span v-if="isRunWorktree(c.branch)" class="wtrun mono">run</span>
  <span class="hchip mono" :class="workTree(c).tone">{{ workTree(c).label }}</span>
  <span class="hchip mono" :class="upstreamState(c).tone">{{ upstreamState(c).label }}</span>
  <button class="hmore mono" :aria-expanded="openChanges === c.id" @click="openChanges = openChanges === c.id ? null : c.id">
    {{ openChanges === c.id ? 'Hide changes' : 'Changes' }}
  </button>
  <button class="hmore mono" :aria-expanded="openHealth === c.id" @click="toggleHealth(c)">
    health {{ openHealth === c.id ? '▴' : '▾' }}
  </button>
  <RepoBrainstormButton
    :repo="c"
    :models="repoModels(c)"
    :open="bmenu === c.id"
    :disabled="busy === c.id || !agentUp"
    @toggle="bmenu = bmenu === c.id ? '' : c.id"
    @pick="(m) => brainstormHere(c, m)"
  />
  <span class="wtpath mono">{{ c.path }}</span>
  <span class="wttag mono">tracked</span>
</div>
```

The branch is now the switcher button rather than a static `⎇ {{ c.branch }}` label, so no information is lost.

- [ ] **Step 2: Typecheck**

```bash
cd frontend && npx vue-tsc --noEmit
```

Expected: exit 0.

- [ ] **Step 3: Verify the actual bug is fixed**

Rebuild, hard-reload. You need a repo with at least one linked worktree; create one with `git -C <repo> worktree add ../wt-qa -b qa/probe` and Sync.

With **grouping on**, expand the worktree list and confirm:
1. Each worktree row shows branch, changes, health, and Brainstorm here.
2. `Brainstorm here → Claude CLI` on a worktree opens a terminal whose `cwd` is the **worktree** path, not the parent's — check the `cwd` shown in the pane header.
3. The session title reads `Terminal · <label> ⎇ qa/probe`.
4. `health` and `changes` on a worktree row open the panel inside the parent card, showing that worktree's data, not the parent's.
5. The primary card is unchanged.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/views/ReposView.vue
git commit -m "repos: act on a worktree without ungrouping it"
```

---

### Task 7: Silent refresh

**Files:**
- Modify: `frontend/src/views/ReposView.vue` — `load()` (around line 293) and its 11 call sites

**Interfaces:**
- Consumes: nothing.
- Produces: `load({ silent }: { silent?: boolean })`.

- [ ] **Step 1: Give `load` a silent mode**

Replace `load()` (around line 293):

```ts
async function load() {
  loading.value = true
```

with:

```ts
// `loading` blanks the entire list for a "loading…" line, which after an action reads as a page
// refresh and takes the scroll position with it. The first load should still say it is loading;
// every refresh *after* an action should not.
async function load({ silent = false }: { silent?: boolean } = {}) {
  if (!silent) loading.value = true
```

and in its `finally`, replace `loading.value = false` with:

```ts
    if (!silent) loading.value = false
```

- [ ] **Step 2: Make every post-action refresh silent**

Every `await load()` that runs *after* a mutating action becomes `await load({ silent: true })`. Find them with:

```bash
grep -n "load()" frontend/src/views/ReposView.vue
```

Change all of them **except** the one in `onMounted` (around line 291), which stays loud. Expect to change ten call sites — in `act`, `commit`, `commitAndPush`, `switchBranch`, `saveIdentity`, `remove`, and the others the grep reports.

- [ ] **Step 3: Typecheck**

```bash
cd frontend && npx vue-tsc --noEmit
```

Expected: exit 0.

- [ ] **Step 4: Verify the list stops blinking**

Rebuild, hard-reload. Expand a repo's health panel, scroll down, then press Pull. The list must **not** blink through `loading…`, the panel must stay open, and the scroll position must hold. Reload the page fresh and confirm `loading…` still appears on first paint.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/views/ReposView.vue
git commit -m "repos: refresh after an action without blanking the list"
```

---

### Task 8: Toast instead of the inline banner

**Files:**
- Create: `frontend/src/components/AppToast.vue`
- Modify: `frontend/src/views/ReposView.vue` — the `flash` ref (272), its render site (644), its `.flash` style (1100), and every `flash.value = ...` assignment

**Interfaces:**
- Consumes: nothing.
- Produces: component `<AppToast>` with props `text: string`, `tone: 'ok' | 'error'`; emits `close`.

- [ ] **Step 1: Create the component**

```vue
<script setup lang="ts">
// Floating rather than inline: the old banner sat above the list with a margin, so every message
// pushed the whole page down. Errors do not auto-dismiss — a failed push should not disappear
// while you are still reading it.
import { onBeforeUnmount, watch } from 'vue'

const props = defineProps<{ text: string; tone: 'ok' | 'error' }>()
const emit = defineEmits<{ close: [] }>()

let timer: number | undefined
function arm() {
  if (timer) window.clearTimeout(timer)
  timer = props.tone === 'ok' ? window.setTimeout(() => emit('close'), 4000) : undefined
}
watch(() => [props.text, props.tone], arm, { immediate: true })
onBeforeUnmount(() => { if (timer) window.clearTimeout(timer) })
</script>

<template>
  <div class="toast mono" :class="tone" role="status">
    <span class="tmsg">{{ text }}</span>
    <button class="tclose" title="Dismiss" @click="emit('close')">✕</button>
  </div>
</template>

<style scoped>
.toast { position: fixed; right: 18px; bottom: 18px; z-index: 60; max-width: 460px; display: flex; align-items: flex-start; gap: 10px; font-size: 12.5px; border-radius: 8px; padding: 9px 12px; white-space: pre-wrap; border: 1px solid var(--warp); background: var(--panel); color: var(--warp-hi); box-shadow: 0 6px 24px rgba(0,0,0,0.35); }
.toast.error { border-color: var(--bad, #d66); color: var(--bad, #d66); }
.tmsg { flex: 1; }
.tclose { background: transparent; border: 0; color: inherit; cursor: pointer; font-size: 12px; line-height: 1; padding: 0 2px; }
</style>
```

If `--bad` is not defined in `frontend/src/styles/`, grep that folder for the token the design system already uses for failure states and use it instead of the `#d66` fallback.

- [ ] **Step 2: Change the flash ref to carry a tone**

Replace line 272:

```ts
const flash = ref('')
```

with:

```ts
const flash = ref<{ text: string; tone: 'ok' | 'error' } | null>(null)
const say = (text: string, tone: 'ok' | 'error' = 'ok') => { flash.value = { text, tone } }
```

- [ ] **Step 3: Convert every assignment**

Find them:

```bash
grep -n "flash.value" frontend/src/views/ReposView.vue
```

- `flash.value = ''` becomes `flash.value = null`.
- Every message in a `catch` block, and every one whose text carries a failure (the `✗` branches, `Scan failed`, `Not a git repo`, `Could not add worktree`, `Fetch failed for`), becomes `say(<same text>, 'error')`.
- Every other message becomes `say(<same text>)`.

In `act` (around line 369) the outcome is already known, so use it directly:

```ts
const bad = res.ok === false
say(`${repoLabel(r)}: ${label} ${bad ? '✗ ' + (res.error || res.output || '') : '✓'}`, bad ? 'error' : 'ok')
```

Note this also switches the message from `r.name` to `repoLabel(r)` (Task 2), so a toast names the same repo the card does.

- [ ] **Step 4: Swap the render site**

Add the import:

```ts
import AppToast from '../components/AppToast.vue'
```

Replace line 644:

```html
<div v-if="flash" class="flash mono">{{ flash }}</div>
```

with:

```html
<AppToast v-if="flash" :text="flash.text" :tone="flash.tone" @close="flash = null" />
```

Delete the now-unused `.flash` rule at line 1100.

- [ ] **Step 5: Typecheck**

```bash
cd frontend && npx vue-tsc --noEmit
```

Expected: exit 0. It will flag any `flash.value = '...'` assignment missed in Step 3 — fix each one it names.

- [ ] **Step 6: Verify both tones**

Rebuild, hard-reload.
- A successful Pull shows a toast bottom-right that clears itself after about four seconds, and **nothing on the page moves** when it appears.
- A failing action shows a toast that stays until you press ✕. To force one: stop the host agent (`kill` the listener on 8765), press Pull, and confirm the failure toast persists. Restart the agent afterwards with `node agent/devloom-agent.mjs`.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/components/AppToast.vue frontend/src/views/ReposView.vue
git commit -m "repos: float action results as a toast instead of shifting the page"
```
