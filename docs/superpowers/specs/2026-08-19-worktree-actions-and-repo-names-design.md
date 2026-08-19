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

### What is extracted

The two pieces that are bulky enough to rot if duplicated:

- **`components/RepoBrainstormButton.vue`** — the split button and its model menu: the
  `repoModels(r)` loop, `modelLabel(m)`, the local-only caption, and the empty
  "no local models pulled" state.
- **`components/RepoPanels.vue`** — the branch list, the changes list, and the health detail.

The four trigger buttons stay inline in each context. They are one-liners, and their placement
legitimately differs between a full card header and a compact worktree row.

### State stays where it is

Both components are presentational. `ReposView.vue` keeps owning `repos`, `busy`, `flash`, and the
open-state refs (`openHealth`, `openChanges`, `openBranches`, `bmenu`), and keeps every handler
unchanged: `toggleBranches` (597), `toggleHealth` (87), `openChanges` (283), `brainstormHere`
(205), `startCommit` (438), `canCommit` (426).

This is the point of the decomposition. Those handlers already take a `RepoView` and key their
state by `r.id`; `trackedChildren()` already returns full `RepoView` objects with unique ids. So
the children work through the existing logic with **no changes to it** — the components receive
the repo plus the relevant open-state and emit clicks back up.

### Result

`.wtrow` renders `⎇ branch ▾` · `changes` · `health ▾` · `<RepoBrainstormButton>`, with
`<RepoPanels>` beneath. The primary card renders the same two components in the slots its markup
already has, and looks identical to today.

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

## Files touched

| File | Change |
| --- | --- |
| `frontend/src/components/RepoBrainstormButton.vue` | new — split button + model menu |
| `frontend/src/components/RepoPanels.vue` | new — branch / changes / health panels |
| `frontend/src/utils/repoNames.ts` | new — `qualifyNames` |
| `frontend/src/views/ReposView.vue` | render both components in the card and in `.wtrow`; use qualified names; branch in worktree session titles |
| `frontend/src/views/FleetView.vue` | drop local `repoName`, use `qualifyNames`, load the repo list |

No backend, agent, or database change.

## Out of scope

- Full parity between a worktree row and a repo card (Open PR, local-only, Remove, Sync).
- Re-qualifying the titles of sessions that already exist.
- The stored `git_repo.name`, which keeps being the plain basename.

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
