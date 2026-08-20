# Today = execute the day, Work = decide — design

**Date:** 2026-08-20 · **Status:** approved in chat (shape + briefing forks chosen via questions,
then "ok do", with one addition: Work rows must show PR authorship).

## Why

Today and Work grew confusing/redundant: Today's Triage lens ranks and acts, Work browses but
cannot act ("Everything ▸" lands you somewhere you can't plan/snooze/handle), the Briefing and
Triage tabs render nearly the same list on a fresh machine, and nothing anywhere answers "what do
I have *today*" including meetings — calendar start times are baked into display strings, not
stored.

## Decisions

1. **Two pages, two jobs.** Today = the day you're executing; Work = the inventory you triage.
   The Briefing/Triage mode toggle dies (with its `devloom.todayMode` key).
2. **Today, one view, top to bottom:**
   - **Schedule strip** — today's calendar items, chronological by the new `starts_at`; all-day
     items (OOO, holidays) as a chip row above the timed list; ongoing highlighted, past dimmed.
     Compact timeline rows, not cards.
   - **Needs you now** — the existing urgency set (failed builds, review requests). Cards.
   - **Planned** — the `+ Plan` set. Cards, unplan in place.
   - **Assigned to you** — PRs with `prRole` ∈ {mine, review} plus `task` and `review` items
     (Jira's query is already `assignee = currentUser`). Cards; the role chips label them.
   - **Snoozed** fold and the "what changed" bar stay.
   - **Dedup:** an item renders exactly once; highest section wins
     (schedule → needs-you → planned → assigned).
3. **Work gains hands and the relocated jobs:**
   - Every row: **Open · Plan/Unplan · Snooze · Handled/Unhandle** (same endpoints the cards use).
   - **New** filter chip — the since-yesterday set (extIds absent from the last briefing
     snapshot), relocated from the Briefing.
   - **Sort toggle `updated | priority`** — the ranked "what next" survives as a sort order
     (PriorityEngine score exposed on rows), not a page.
   - **PR rows show who created them** — `by <author>` (the field shipped 2026-08-19 but was
     never rendered) — plus their role where it isn't the filter already.
4. **Data:** V25 adds nullable `work_item.starts_at TIMESTAMPTZ`; `CalendarIcsConnector` stores
   the event start it already computes (all-day events at local midnight). `WorkRow` gains
   `isNew`, `score`, `startsAt`; `Recommendation` gains `startsAt`. `Dto.Today` becomes
   `schedule/needsYou/planned/assigned` (+ existing snoozed/changed/sync/counts); the `Briefing`
   payload leaves `/today`. BriefingService keeps snapshots, flags and the urgency rules — Today
   and Work both consume sets it now exposes (`newExtIds`, the needs-you set); the morning
   notification digest is untouched.
5. **Out of scope:** calendar events beyond today on Today (they stay in Work), drag-to-reorder
   the plan, per-item due dates.

## Success criteria

- Compile + vue-tsc clean; V25 applies.
- Live on this machine: the schedule strip shows today's real calendar entries in time order
  (or its honest empty state); planned/assigned sections populate from real flags and roles;
  no item appears in two sections; plan/snooze/handle round-trip from BOTH pages; Work's New
  chip matches the old briefing diff; priority sort reorders; PR rows show `by sarasnt`.
- The notification digest still counts urgent + planned exactly as before.
