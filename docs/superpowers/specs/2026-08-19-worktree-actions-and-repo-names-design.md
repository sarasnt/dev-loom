# Repos: acting on a collapsed worktree, and telling two same-named repos apart

Two independent problems on the Repositories screen, specified together because they touch the
same file and the same session-title line.

## Problem

**1. A collapsed worktree can't be acted on.** With "Group worktrees" on, `visibleRepos` filters
out linked worktrees and nests them under their primary card. The nested `.wtrow` is display-only
— branch, health chips, path, a `tracked` tag, no controls. The only "✎ Brainstorm here" belongs
to the primary card and passes `r.path`, the main checkout. So starting a brainstorm *in a
worktree* means toggling grouping off first, which defeats the grouping.

**2. Two repos with the same folder name are indistinguishable.** `name` is
`path.basename(dir)` (`agent/devloom-agent.mjs:825`), persisted to `git_repo`. Two repo
directories each containing `implementation` produce two cards both titled `implementation`, two
identical session titles, and two identical Fleet badges. FleetView derives names separately, via
its own `repoName(path)` (`FleetView.vue:62`, used at 248/266/281/297/317), so it has the same
collision independently.

## Part 1 — Worktree actions

A nested worktree row gains the four actions that are meaningfully per-worktree: **branch switch,
changes, health, and Brainstorm here.** It does not gain the card-level actions (Open PR,
local-only, Remove, Sync) — those belong to the repository, not to one checkout of it.

### Why not one `<RepoActions>` component

The obvious extraction fails on layout. On the primary card these four controls are not adjacent:
`branch` and `health` sit in the header row (`.rh`, beside the name and chips), while `changes`
and `brainstorm` sit in the action row below. A single component containing all four would force
them together and redesign the primary card, which is not what this change is for.

### What is extracted, and what is not

Only one component. Measured against the current file, the three panels read very different
amounts of `ReposView`'s state:

| Panel | Lines | Script bindings referenced |
| --- | --- | --- |
| branch (`784-805`) | 22 | 5 |
| changes (`983-1023`) | 41 | 9 |
| health (`707-782`) | 76 | **25** |

A presentational panels component would therefore need a 25-property interface for health alone
(`sourceStatus`, `conflict`, `conflictLoading`, `pushProt`, `editProt`, `saveSource`,
`refreshRepo`, and the rest). That is a worse thing to own than the duplication it would prevent,
so the panels are **not** extracted.

- **`components/RepoBrainstormButton.vue`** — extracted. Its menu is absolutely positioned against
  its own trigger (`.splitwrap { position: relative }`), so it has to render beside each button
  rather than once per card, and its interface is small: the repo, the model list, and a disabled
  flag.
- **The three panels** — not extracted. Instead their `v-if` widens from "this repo" to "this repo
  or any of its tracked worktrees", and they read from whichever row is currently open. One copy
  of each panel then serves the primary card and every worktree row beneath it.

The trigger buttons stay inline in each context. They are one-liners, and their placement
legitimately differs between a full card header and a compact worktree row.

### Where a worktree's panel appears

Because there is one copy of each panel and it lives inside the parent's `<section class="repo">`,
expanding `health` (or `branch`, or `changes`) on a worktree row opens it inside the parent card,
below the worktree list — not inline under that row. This is deliberate: one panel location per
card, and no second copy to keep in sync.

### State stays where it is

`ReposView.vue` keeps owning `repos`, `busy`, `flash`, and the open-state refs (`openHealth`,
`openChanges`, `openBranch`, `bmenu`), and keeps every handler unchanged: `toggleBranches` (597),
`toggleHealth` (87), `switchBranch` (603), `brainstormHere` (205), `startCommit` (438),
`canCommit` (426).

This is the point. Those handlers already take a `RepoView` and key their state by `r.id`, and
`trackedChildren()` already returns full `RepoView` objects with unique ids. The children work
through the existing logic with **no changes to it**.

### Result

`.wtrow` renders `⎇ branch ▾` · `changes` · `health ▾` · `<RepoBrainstormButton>`. The primary
card keeps its own layout and gains nothing but the extracted button in the slot its markup
already has.

## Part 2 — Conflict-qualified names

New `frontend/src/utils/repoNames.ts`, matching the existing `utils/` convention
(`looming.ts`, `markdown.ts`, `models.ts`):

```ts
export function qualifyNames(paths: string[]): Map<string, string>
```

Given the set of known repo paths, it returns path → display label.

**Computed at display time, never stored.** Whether a name collides is a property of the current
set of repos, which changes as they are added and removed. A prefix written into `git_repo.name`
would be wrong the moment the other repo is removed. Nothing in the database changes and there is
no migration.

### Algorithm

1. Normalize each path: backslashes to `/`, drop any trailing slash.
2. Label every path with its last segment.
3. Group labels case-insensitively (Windows paths differ only by case and must still collide).
4. For every group with more than one member, prepend one more parent segment **to that group
   only**, and repeat from step 3.
5. Stop when every label is distinct, or when a path has no more segments to give.

`lccc/implementation` and `personal/implementation` separate after one step. Two repos at
`/a/x/implementation` and `/b/x/implementation` are still tied after one step (`x/implementation`)
and separate on the next (`a/x/…`, `b/x/…`). A repo whose name is already unique is never
prefixed — that is the requirement.

The separator in the label is always `/`, on every platform. The label is a label, not a path.
Segments keep their original casing; only the comparison is case-insensitive.

### Consumers

- **ReposView** — card headings, and the session title in `brainstormHere`, which becomes
  `Terminal · lccc/implementation ⎇ feature/X` for a linked worktree. Without the branch, three
  worktrees of one repo yield three identically-named sessions.
- **FleetView** — the five repo badges, replacing the local `repoName(path)` helper.

Session titles are stamped at creation from the map as it stands then. A title is a historical
label; it is not re-qualified later if the conflict set changes.

### Cost to accept

FleetView derives names from `repoPath` alone today and never loads the repo list. Qualifying its
badges means it gains a repos fetch. Runs whose `repoPath` is untracked or removed fall back to
the plain basename, unprefixed.

## Part 3 — Refresh without reloading, and a toast instead of a banner

### Problem

Every mutating action ends with `await load()`, and `load()` sets `loading = true`. The template
answers that with `<div v-if="loading" class="mono empty">loading…</div>`, which replaces the
**entire repository list** with one line of text before rebuilding it. `await load()` is called
from **11** places — pull, push, PR, commit, commit-and-push, checkout, identity save, and the
rest. Nothing actually reloads the page, but the list vanishing and coming back reads exactly like
it did, and the scroll position goes with it.

Separately, `flash` renders inline above the list (`.flash`, `margin-bottom: 14px`), so a message
appearing pushes the whole list down, and it never clears until the next action starts.

### Design

**Silent refresh.** `load()` takes an options argument:

```ts
async function load({ silent = false }: { silent?: boolean } = {})
```

When `silent`, it fetches and assigns exactly as now but never touches `loading`. The `onMounted`
call stays loud — an empty screen on first paint should say so. The ten post-action refreshes
become silent. Because the list is keyed (`v-for="r in visibleRepos" :key="r.id"`), Vue patches
rows in place instead of unmounting them, so expanded panels and scroll position survive.

**Toast instead of banner.** New `components/AppToast.vue`: fixed-position, overlaying rather than
occupying layout, so showing a message shifts nothing. It carries a tone, because a result that
vanishes is not always wanted:

- `ok` — auto-dismisses after 4 seconds.
- `error` — stays until dismissed. A failed push should not disappear while you are reading it.

`flash` becomes `{ text: string; tone: 'ok' | 'error' }| null`. `act()` already knows the outcome
(`res.ok`) and sets the tone accordingly; the existing `catch` branches set `error`.

## Files touched

| File | Change |
| --- | --- |
| `frontend/src/components/RepoBrainstormButton.vue` | new — split button + model menu |
| `frontend/src/utils/repoNames.ts` | new — `qualifyNames` |
| `frontend/src/views/ReposView.vue` | render the button in the card and in `.wtrow`; widen the three panels' `v-if` to cover worktree children; silent `load()`; toast instead of the inline flash; use qualified names; branch in worktree session titles |
| `frontend/src/views/FleetView.vue` | drop local `repoName`, use `qualifyNames`, load the repo list |
| `frontend/src/components/AppToast.vue` | new — floating, tone-aware, auto-dismissing message |

No backend, agent, or database change.

## Out of scope

- Full parity between a worktree row and a repo card (Open PR, local-only, Remove, Sync).
- Re-qualifying the titles of sessions that already exist.
- The stored `git_repo.name`, which keeps being the plain basename.
- Adopting `AppToast` in the other views that still use an inline flash.

## Verification

No unit-test harness exists in this project, so verification is the project's own: `vue-tsc
--noEmit`, then the running app.

1. `cd frontend && npx vue-tsc --noEmit` passes.
2. With grouping **on**, expand a repo with worktrees: each row offers branch, changes, health and
   Brainstorm here; starting one opens a session whose `cwd` is the **worktree** path, not the
   parent's.
3. The primary card is visually unchanged, and its four controls still work.
4. Two repos named `implementation` under different parents both show a prefix; a repo with a
   unique name shows none; the same labels appear on Fleet badges.
5. Removing one of the two conflicting repos drops the prefix from the survivor without a reload
   of anything but the repo list.
6. Expand a repo's health panel, scroll down, then Pull: the list does **not** blink through
   `loading…`, the panel stays open, and the scroll position holds.
7. A successful pull shows a toast that clears itself after about four seconds and shifts no
   layout; a failing push shows one that stays until dismissed.
