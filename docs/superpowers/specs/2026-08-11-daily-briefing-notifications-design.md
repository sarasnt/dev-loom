# Daily Briefing + Desktop Notifications — Design

**Status:** Draft 1
**Date:** 2026-08-11
**Surface:** Today screen (new "Briefing" mode) · Settings › Notifications · host agent

## 1. Summary

DevLoom is a good dashboard but an amnesiac one: every sync it recomputes a to-do list
from scratch, forgets what you handled, and never reaches you unless you open the tab. This
feature adds the two things that make it a *command center* instead of a dashboard:

- **Memory** — a stateful **Briefing** that shows what changed since yesterday, lets you mark
  items handled (persisted), and lets you pick a focused "Today's plan."
- **Proactivity** — **desktop OS notifications** delivered through the host agent: one morning
  digest at a time you choose, plus real-time alerts for urgent events, all under quiet hours.

The Briefing is a **mode of the existing Today screen**, not a new route — "the briefing is just
part of the day." Today keeps its current ranked list (now called **Triage**); a segmented toggle
switches between **Briefing** and **Triage**.

## 2. Goals

1. Make the day's state legible at a glance: new / resolved / still-waiting since yesterday.
2. Let the user act on and remember work: mark handled, build a daily plan — both persisted.
3. Reach the user when the browser is closed, via native desktop notifications.
4. Stay calm: a single morning digest + only genuinely-urgent real-time alerts, with quiet hours.
5. Reuse existing surfaces and data; add no new nav item and no new external dependency.

## 3. Non-goals

- Email or Slack/webhook delivery (deferred; desktop-only for v1).
- Click-through activation on the toast (open the item from the notification) — future.
- Outcome tracking ("did CI go green after I acted") — that's the separate *Threads* bet.
- Multi-user, mobile, or remote delivery.
- Changing the ranking algorithm (Tunable Triage is a separate bet).

## 4. Architecture decision: how notifications are delivered

The backend runs in Docker and cannot pop a host OS notification. The **host agent** already runs
on the user's machine and can. Chosen mechanism:

> **Backend scheduler → `POST /notify` on the host agent → OS-native toast**, shelling out to the
> platform notifier with **no new npm dependency** (matches the agent's "HTTP endpoints run on
> Node built-ins alone" design).

Rejected alternatives:
- **Browser Notification API + service worker** — only fires while a tab/SW is alive; fails the
  "reach me when the browser is closed" requirement.
- **`node-notifier` dependency in the agent** — adds a dependency where a shell-out suffices.

Platform notifier used by the agent (selected by `process.platform`):
- **Windows:** PowerShell toast via `Windows.UI.Notifications.ToastNotificationManager` (built into
  Win10/11, no extra module). Fallback: a `System.Windows.Forms.NotifyIcon` balloon.
- **macOS:** `osascript -e 'display notification "<body>" with title "<title>"'`.
- **Linux:** `notify-send "<title>" "<body>"`.

Delivery requires the agent to be running. When it is not, notifications are skipped (logged), the
in-app Briefing still works, and Settings shows a "notifications unavailable — agent offline" status.

## 5. Today screen: Briefing vs Triage

A segmented control at the top of Today: **Briefing | Triage**.

- **Triage** — the current experience unchanged: the ranked `Next` list + `Everything` / `Snoozed`.
- **Briefing** — three stacked sections built from the same work data:
  1. **Since yesterday** — three groups: **New** (items that appeared), **Resolved** (items present
     at the last briefing now gone or in a done state), **Still waiting** (carried over, with age).
  2. **Needs you now** — the urgent set (see §7), highest-priority framing of the day.
  3. **Today's plan** — items the user picked; a focused checklist that persists across reloads.

Default mode: **Briefing** in the morning window (local time before the digest time + a few hours),
**Triage** otherwise; the last explicit choice is remembered in `localStorage` and wins. (Simple
heuristic, no server state.)

Each Briefing item card (reusing `RecommendationCard`) gains two actions:
- **✓ Handled** — toggles persisted handled state; the item drops from Briefing lists and is counted
  as "Resolved" in tomorrow's since-yesterday. Distinct from Snooze (which only hides temporarily).
- **+ Plan / − Plan** — toggles membership in Today's plan.

## 6. Memory model (data)

One Flyway migration (`V15__briefing.sql`):

```sql
-- A point-in-time snapshot of the work set, for since-yesterday diffs and new-urgent detection.
CREATE TABLE briefing_snapshot (
    id        BIGSERIAL PRIMARY KEY,
    taken_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    kind      VARCHAR(16) NOT NULL,          -- 'digest' | 'sync'
    items_json TEXT NOT NULL                 -- JSON array of {extId,type,source,status,urgencyKey}
);

-- Per-work-item user state that must outlive a sync (keyed by ext id, source-stable).
CREATE TABLE work_item_flag (
    ext_id     VARCHAR(190) PRIMARY KEY,
    handled_at TIMESTAMPTZ,
    planned_at TIMESTAMPTZ
);
```

Notes:
- `work_item_flag` is keyed by `ext_id` (the source-stable id), so flags survive the
  delete-and-reinsert sync. A flag whose item no longer exists is inert and periodically pruned.
- Snapshots: keep the most recent **digest** snapshot (the "yesterday" baseline for the diff) and the
  most recent **sync** snapshot (baseline for new-urgent detection). Older rows pruned (keep ~14).
- `urgencyKey` encodes why an item is urgent (e.g. `ci-fail`, `review-req`, `pr-wait`) so a diff can
  tell a *newly*-urgent item from one that was already urgent.

Notification/config settings live in `app_config` (like the existing `terminal.workdir`):
`notify.enabled`, `notify.digestTime` (HH:mm, local), `notify.quietStart`, `notify.quietEnd`,
`notify.urgent.ci`, `notify.urgent.review`, `notify.urgent.prWaitHours`.

## 7. What counts as "urgent" (v1, deterministic)

An item is urgent when any enabled rule matches:
- **New CI failure** — a `build` item newly in a failed state (`notify.urgent.ci`, default on).
- **Review requested of you** — a PR that needs your review (`notify.urgent.review`, default on),
  from data GitHub already provides.
- **PR waiting on you past threshold** — a PR assigned to you waiting longer than
  `notify.urgent.prWaitHours` (default 24).

"Behind main beyond a threshold" and general staleness are **not** urgent — they belong to the digest.

## 8. Notifications (backend `NotificationService`)

- **Daily digest:** a `@Scheduled(fixedRate = 60s)` tick compares the current local time to
  `notify.digestTime`; when it matches, digest is enabled, it is not quiet hours, and no digest has
  been sent today (tracked via the last `digest` snapshot's date), it composes the digest, sends one
  summary toast through the agent, and writes a `digest` snapshot (the new "yesterday" baseline).
  The minute-tick approach means config changes take effect without a restart.
- **Urgent alerts:** `SyncService` calls `NotificationService.onSync()` after a sync. It builds the
  current work snapshot, diffs against the last `sync` snapshot, and for each **newly**-urgent item
  sends an immediate toast (subject to per-rule toggles). It then stores the new `sync` snapshot.
- **Quiet hours:** during the window, non-urgent is suppressed and urgent alerts are **not** sent
  immediately. No separate pending store is needed — the morning digest recomputes from current
  state and naturally includes anything that arose overnight.
- **Coalescing:** if multiple urgent items arrive in one sync, they are batched into a single toast
  ("3 things need you: CI failed · review requested · …").

## 9. API surface

- `GET /today` — always includes a `briefing` object (it is cheap and lets the frontend switch
  modes without a second request):
  `{ sinceYesterday: { new: Rec[], resolved: Rec[], waiting: Rec[] }, needsYou: Rec[], plan: Rec[] }`.
  `Recommendation` gains `handled: boolean` and `planned: boolean`.
- `POST /today/{id}/handled` — toggle handled; returns updated `TodayData` (mirrors snooze).
- `POST /today/{id}/plan` — toggle plan membership; returns updated `TodayData`.
- `{id}` in both endpoints is the recommendation id as used by Snooze today; the service maps it to
  the work item `ext_id` (the key of `work_item_flag`) using the same resolution Snooze uses.
- `GET /settings` — extended with the `notify.*` config block.
- `PUT /settings/notifications` — save the notification settings block.
- `POST /notifications/test` — send a test toast via the agent; returns `{ ok, error? }`.
- **Agent:** `POST /notify { title, body, urgency: 'normal' | 'urgent' }` → `{ ok, error? }`.

## 10. Frontend surface

- **TodayView** — segmented `Briefing | Triage` control; Briefing renders the three sections via
  `WarpList` / `RecommendationCard`; Triage renders the current list. Mode persisted in `localStorage`.
- **RecommendationCard** — add **Handled** and **Plan** actions (wired to the two new endpoints).
- **Settings › Notifications** (new section/tab): enable toggle, digest time, quiet-hours window,
  per-urgency toggles, a **Test notification** button, and a live "agent connected / unavailable"
  status line.

## 11. Error & edge cases

- **Agent offline:** notifications skipped + logged; Settings shows "unavailable"; in-app Briefing
  unaffected; Test button reports the failure honestly.
- **First run (no prior snapshot):** "Since yesterday" shows "nothing to compare yet"; the first
  digest establishes the baseline.
- **Handled item reappears changed** (e.g. reopened): the flag is cleared when the item's status
  moves back to an active state, so it resurfaces rather than staying hidden forever.
- **Clock/TZ:** all time comparisons use the app's configured timezone (existing `TZ`).
- **Duplicate digests across restarts:** guarded by "already sent today" via the last digest
  snapshot's date, not an in-memory flag.

## 12. Success criteria

- At the configured morning time (outside quiet hours) a desktop toast summarizes since-yesterday +
  needs-you, and the Today › Briefing mode shows the same content.
- A CI failure appearing mid-day triggers an immediate desktop toast (unless quiet hours), and shows
  under "Needs you now."
- Marking an item **Handled** removes it from the Briefing, persists across reloads/syncs, and shows
  as **Resolved** in the next day's "Since yesterday."
- Items added to **Today's plan** persist and render as a focused checklist.
- The **Test notification** button pops a desktop toast when the agent is running.
- No notification fires during quiet hours except that overnight urgent items appear in the digest.

## 13. Rollout slices (map to the implementation plan)

1. **Memory + Briefing mode (in-app only).** `V15` migration, snapshot + diff, `work_item_flag`,
   `handled`/`plan` endpoints, Today `Briefing | Triage` toggle, card actions. No notifications yet.
2. **Delivery + Settings.** Agent `POST /notify` (OS-native), Settings › Notifications with the
   Test button and agent-status line.
3. **Scheduling + urgency.** Digest minute-tick, `onSync` urgent detection, quiet hours, coalescing.

Each slice is independently shippable and verifiable.

## 14. Open decisions (chosen defaults; revisit if wrong)

- Default digest time: **08:30 local**. Default quiet hours: **22:00–08:00**.
- Default PR-wait urgency threshold: **24h**.
- Snapshot retention: **~14** rows per kind.
- Toast click-to-open: **deferred** (platform-specific); v1 toasts are informational.
