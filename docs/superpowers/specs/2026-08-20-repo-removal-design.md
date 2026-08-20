# Repos: untrack, remove a worktree, delete a branch

Today `Remove` does exactly one thing — drop the repository from DevLoom's tracking — and the
button says so. Everything else about a checkout's lifecycle happens outside the app: you delete a
worktree directory in a terminal, and DevLoom finds out on the next sync.

That gap produced a real failure. A worktree directory was deleted by hand; git kept the
registration, marked it `prunable`, and went on considering its branch checked out. The branch
switcher offered that branch like any other, and checking it out failed with

```
fatal: 'develop' is already used by worktree at '/home/sara/repositories/lccc/impl-LCCCHES-2752-…'
```

pointing at a directory that no longer existed. The app had no way to express "that worktree is
gone", so the only fix was `git worktree prune` in a terminal.

## What Remove becomes

A menu, using the existing `.splitwrap` / `.bmenu` pattern, with each entry enabled only when it
means something for the checkout the card is currently aimed at:

| Entry | When | What it does |
| --- | --- | --- |
| Untrack from DevLoom | always | today's behaviour — the DevLoom record only, files untouched |
| Remove worktree… | the card is aimed at a linked worktree | `git worktree remove <path>` |
| *(stale)* Remove worktree… | that worktree is `prunable` | `git worktree prune` |

The main checkout is never removable as a worktree — git cannot do it, so the entry is disabled
with that as its reason rather than failing on click.

Branch deletion is deliberately absent from this menu. The card is always standing on a branch and
git will not delete the branch you are on, so a card-level "delete branch" is wrong more often than
it is right. It lives in the branch switcher instead (below).

`Untrack` loses its `confirm()`. The menu entry and its subtitle already say what it does, and it
destroys nothing on disk.

## The guard is git, not a dialog

Every destructive action runs the **safe** variant first — `git worktree remove <path>`,
`git branch -d <name>` — with no confirmation dialog. If the safe command succeeds, nothing was
lost; that is the guarantee, and a dialog in front of it would only train the reflex to dismiss
dialogs.

When git refuses, its refusal is the interesting part. The menu shows the reason it gave and grows
one clearly-marked entry that repeats the action forcefully (`worktree remove --force`,
`branch -D`). Two deliberate clicks to lose work, and the first one tells you what you would lose.

```
Remove ▾
  Untrack from DevLoom      files untouched
  Remove worktree…          deletes the directory

── after clicking Remove worktree ──
  ✗ git refused: contains modified or untracked files
  Force remove — discards those files
```

**The Fleet discard path is not reused.** `agent/devloom-agent.mjs:665-691` already removes a
worktree and deletes its branch, but unconditionally with `--force` and `-D`. That is correct for
a throwaway `devloom/run-N` worktree, whose whole purpose is to be discardable, and wrong for a
checkout the user has been working in. Sharing the code would mean sharing the wrong default.

## The branch switcher gains two states

`git branch --format='%(refname:short)|%(worktreepath)'` reports, per branch, the worktree holding
it — empty when free. The switcher currently asks for names only, which is why it offers branches
that cannot be checked out.

- **Held by another worktree** — no longer offered as a checkout, because that checkout cannot
  succeed. The row shows `⎇ in worktree` and clicking it **selects that worktree**: the branch you
  asked for is already checked out somewhere, and switching the card there is what you meant. If
  DevLoom does not track that worktree, the row is disabled and names the path.
- **Free, and not the current branch** — gains a `✕` that deletes it, through the safe-then-force
  flow above. The current branch (`●`) never gets one.

After a worktree is removed its branch becomes free, which is the moment the paired cleanup is
obvious, so the result offers it:

```
✓ worktree removed. Its branch feature/NA-TLM-redesign is now free.
  [Leave it]  [Delete branch too]
```

## Stale worktrees become visible

`git worktree list --porcelain` reports `prunable` with a reason, and the agent already parses that
output (`agent/devloom-agent.mjs:1142`). A worktree whose directory is gone renders as **stale**
rather than as a normal checkout, and its Remove runs `git worktree prune` — `worktree remove`
cannot act on a directory that is not there.

This is the case that motivated the feature: it turns a state that silently held a branch hostage
into one the app names and clears in a click.

## Surface

**Agent** — three new endpoints, plus one extended:

| Endpoint | Body | Returns |
| --- | --- | --- |
| `POST /repos/worktree-remove` | `{ path, worktree, force }` | `{ ok, output }` |
| `POST /repos/branch-delete` | `{ path, branch, force }` | `{ ok, output }` |
| `POST /repos/prune` | `{ path }` | `{ ok, output }` |
| `POST /repos/branches` | `{ path }` | now also `holders: Record<branch, worktreePath>` |

`path` is always the repository; `worktree` is the checkout being removed. Keeping `local` on the
branches response unchanged means the filter, the datalist and `visibleBranches` need no rework —
`holders` is additive.

**Backend** — passthrough only: `HostAgentClient` methods and `ApiController` routes. It already
returns `Map<String, Object>` for branches, so the extra key needs no DTO change.

**Frontend** — new calls added to `api/http.ts` *and* `api/stub.ts`, re-exported from
`api/index.ts`, per the facade rule. Results go through the existing toast: successes auto-dismiss,
failures persist until dismissed.

## Out of scope

- Removing a worktree that belongs to a Fleet run. Those have their own Apply/Discard flow and
  removing one underneath a running agent is a different problem.
- Deleting remote branches. Everything here is local; `git push --delete` is a separate decision
  with separate consequences.
- Bulk actions — removing several worktrees or branches at once.

## Verification

No unit-test harness exists here, so this is `npx vue-tsc --noEmit`, `node --check` on the agent,
then the running app.

1. On a card aimed at the main checkout, `Remove worktree…` is disabled and says why.
2. On a card aimed at a clean worktree, `Remove worktree…` succeeds, the directory is gone, and the
   result offers to delete the freed branch.
3. On a worktree with uncommitted changes, the safe attempt is refused, the menu shows git's reason,
   and only the Force entry completes it.
4. A branch held by another tracked worktree shows `⎇ in worktree` and selects that worktree when
   clicked — it never attempts a checkout that git would reject.
5. Deleting an unmerged branch is refused with git's reason and needs the Force entry.
6. A worktree whose directory was deleted by hand shows as **stale**, and its Remove prunes the
   registration; the branch it held becomes checkout-able immediately afterwards.
