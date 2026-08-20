# Today/Work Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Today becomes literally your day (schedule + urgent + planned + assigned, deduped); Work becomes the triage home with row actions, a New filter, a priority sort, and visible PR authorship.

**Architecture:** One new column (V25 `starts_at`) feeds a schedule strip; `BriefingService` exposes the sets it already computes (new-since-snapshot, needs-you) so Today and Work both consume them; DTOs grow append-only; the two views are rewritten around the sharpened jobs.

**Tech Stack:** Flyway/JPA, Spring, Vue 3 + TypeScript.

**Spec:** `docs/superpowers/specs/2026-08-20-today-work-split-design.md`

## Recommended executor per task

| Task | Executor | Why |
| --- | --- | --- |
| 1 — column + DTO plumbing | **claude-sonnet** | Positional-record churn (the known hazard class) |
| 2 — backend reshape | **claude-sonnet** | Dedup logic + set exposure; the correctness core |
| 3 — Today rewrite | **claude-sonnet** | Full view rewrite against a changed payload |
| 4 — Work rewrite + live proof | **claude-sonnet** | Actions/filters/sort + end-to-end verification |

## Global Constraints

- Compile: `MSYS_NO_PATHCONV=1 docker run --rm -v "/c/Users/saras/Documents/projects/dev-loom/backend:/w" -v devloom_m2:/root/.m2 -w /w maven:3.9-eclipse-temurin-25 mvn -q -o compile` — pass = zero `ERROR] /w` lines. Frontend: `cd frontend && npx vue-tsc --noEmit` (exit 0).
- **Migration is V25** (verify the dir first). **Do NOT rebuild containers in Tasks 1–2** — the running frontend depends on the old `/today` shape until Task 3 lands; Task 3 rebuilds both, Task 4 re-verifies.
- Read every file before editing; Read/Edit/Write tools only; no BOM; Jackson `com.fasterxml`; comments explain why.
- DTO fields append-only at the END of records; after any record change, grep for every construction site (`new Dto.Recommendation(`, `new Dto.WorkRow(`, `new Dto.Today(`) — there are exactly three Recommendation sites (TodayService.rec, BriefingService.numbered, and after Task 2 only those two remain — verify), one WorkRow site, and Today is built once in TodayService plus stubbed in `frontend/src/api/stub.ts`.
- The morning notification digest (`NotificationService`) must be behaviorally untouched — it reads `briefing.plannedExtIds()` and the urgent count; verify by reading it after Task 2.
- Execute in order.

---

### Task 1: `starts_at` and the DTO plumbing (executor: claude-sonnet)

**Files:** create `backend/src/main/resources/db/migration/V25__work_item_starts_at.sql`; modify `WorkItemEntity.java`, `CalendarIcsConnector.java`, `Dto.java`, `WorkModelService.java`, `TodayService.java`, `BriefingService.java` (construction sites only), `frontend/src/types.ts`.

**Interfaces produced:** `WorkItemEntity.withStartsAt(java.time.Instant)` + getter; `Dto.WorkRow` gains `boolean isNew, Double score, String startsAt` (LAST three); `Dto.Recommendation` gains `String startsAt` (LAST). Task 2 fills real values; this task passes `false, null, null` / entity-derived ISO string where trivially available.

- [ ] **Step 1: Migration V25:**

```sql
-- When a work item happens (calendar events today; anything time-anchored later). The connector
-- always knew this — it was baking the time into a display string, which cannot be sorted into
-- a day's agenda.
ALTER TABLE work_item ADD COLUMN IF NOT EXISTS starts_at TIMESTAMPTZ;
```

- [ ] **Step 2: Entity** — `java.time.Instant startsAt` field (`@Column(name = "starts_at")`), getter/setter, and `withStartsAt(Instant)` builder beside `withUrl`.

- [ ] **Step 3: Calendar connector** — where events become work items (`out.add(WorkItemEntity.create(...))` in `CalendarIcsConnector`), chain `.withStartsAt(...)`: the event's start as an `Instant` (the connector already computes a `ZonedDateTime`/`LocalDateTime` start — read the surrounding code and convert with the ZONE it already uses; all-day events land at local midnight). Every calendar item gets it; other connectors are untouched.

- [ ] **Step 4: DTO churn** — append the new components; update every construction site: `WorkModelService.toRow` passes `false, null, e.getStartsAt() == null ? null : e.getStartsAt().toString()`; `TodayService.rec` passes `w.getStartsAt() == null ? null : w.getStartsAt().toString()`; `BriefingService.numbered` appends `r.startsAt()`. `frontend/src/types.ts`: `WorkRow` gains `isNew?: boolean`, `score?: number | null`, `startsAt?: string | null`; `Recommendation` gains `startsAt?: string | null`.

- [ ] **Step 5: Gates** (compile + vue-tsc; NO rebuild). **Step 6: Commit** `work model: items know when they happen (V25)`.

---

### Task 2: Backend reshape (executor: claude-sonnet)

**Files:** `BriefingService.java`, `TodayService.java`, `WorkModelService.java`, `Dto.java` (the `Today` record), `NotificationService.java` (read-only verification).

**Interfaces produced:**
- `BriefingService.newExtIds()` → `Set<String>`: active items whose extId is absent from `lastSnapshot(...)` — extract from the existing `build(...)` diff logic, same snapshot kind it uses today, so Work's New chip equals the old briefing's New list.
- `BriefingService.needsYouExtIds()` → `Set<String>`: the extIds `build(...)`'s needs-you selection would produce (reuse `UrgencyRules` exactly — extract, don't reimplement).
- `Dto.Today` becomes: `workspace, user, now, changed, sync, model, boundary, List<Recommendation> schedule, List<Recommendation> needsYou, List<Recommendation> planned, List<Recommendation> assigned, int everythingCount, int snoozedCount, List<Recommendation> snoozed` — the `next` list and `Briefing briefing` component are REMOVED (delete `Dto.Briefing` only if nothing else references it; `build(...)` may keep internal use — prefer deleting dead code over keeping it).

- [ ] **Step 1:** Extract/expose the two sets in `BriefingService` (keep `build` working during the edit or delete it once TodayService stops calling it — end state: no dead public methods).
- [ ] **Step 2:** Rewrite `TodayService.today()`:

```java
        // Sections, deduped: an item renders once, highest section wins. "Schedule" is only
        // calendar items happening today (or ongoing); everything else falls through.
        java.time.LocalDate today = java.time.LocalDate.now();
        java.util.Set<String> placed = new java.util.HashSet<>();
        // schedule: type calendar AND (startsAt is today OR status says it is running now)
        // needsYou: briefing.needsYouExtIds(), minus placed
        // planned:  briefing.plannedExtIds(), minus placed
        // assigned: prRole in ("mine","review") OR type in ("task","review"), minus placed
```

Schedule sorts by `startsAt` (nulls last); other sections keep sortOrder order. Handled items are excluded from needsYou/planned/assigned the same way `build(...)` excluded them (read it; keep the reopen-guard semantics by reusing `isHandled`). Snoozed stays excluded everywhere and the snoozed list stays in the payload. Keep `signalsFor`/PriorityEngine imports only if still used — Work's score (Step 3) is the surviving consumer; move/keep accordingly with a why-comment.

- [ ] **Step 3:** `WorkModelService`: real `isNew` (`briefing.newExtIds().contains(extId)`) and `score` (rank all items once with `PriorityEngine` + the same signals Today used — move `signalsFor` to a shared home if needed, e.g. a package-private helper on `WorkModelService`, and have TodayService delegate or delete its copy; one implementation, stated in a comment).
- [ ] **Step 4:** Read `NotificationService` — confirm it still compiles and semantically matches ("N need you now · M in today's plan"); if it consumed `build(...)`, repoint it at the exposed sets with identical counts.
- [ ] **Step 5:** Compile (NO rebuild). Grep confirms no remaining reference to the removed `Today.next`/`briefing` components. **Step 6: Commit** `today: the payload is the day — schedule, urgent, planned, assigned, once each`.

---

### Task 3: Today, rewritten (executor: claude-sonnet)

**Files:** `frontend/src/views/TodayView.vue`, `frontend/src/types.ts` (TodayData reshape), `frontend/src/api/stub.ts` (TODAY literal reshape).

- [ ] **Step 1:** `types.ts`: `TodayData` drops `next` and `briefing`, gains `schedule/needsYou/planned/assigned: Recommendation[]`. Stub literal updated to match (empty arrays).
- [ ] **Step 2:** Rewrite `TodayView.vue`: kill the mode tabs + `devloom.todayMode`; render in order — changed bar; **schedule strip** (all-day chip row = items whose `startsAt` time is local midnight; timed rows `HH:mm · title`, ongoing highlighted via the existing status string starting with "now", past-today dimmed); **Needs you now**, **Planned** (honest empty state: "nothing planned yet — pick items in Work"), **Assigned to you** — all three as `WarpList` cards; snoozed fold unchanged. Empty sections render their label only when the section has content, except Planned which always shows (the empty state is its call to action). Schedule strip is new markup — compact mono rows, match the design system (read the view's existing styles; reuse `.donelist`-ish density).
- [ ] **Step 3:** Gates; **rebuild backend+frontend now** (`docker compose up -d --build backend frontend`, poll `/api/v1/settings` 200). Sanity: `GET /api/v1/today` returns the new shape; the page renders with real data.
- [ ] **Step 4: Commit** `today: one view of the actual day`.

---

### Task 4: Work with hands, and the live proof (executor: claude-sonnet)

**Files:** `frontend/src/views/WorkView.vue`, `frontend/src/stores/dashboard.ts` (reuse its plan/snooze/handle actions — they refresh Today's store; Work must also refresh its own rows after an action), `README.md` (screen table lines for Today/Work).

- [ ] **Step 1:** Row actions: each row gets a compact mono action cluster — `Open` (when `url`), `Plan`/`Unplan`, `Snooze`, `Handled` — calling the same store/API functions the cards use, then re-fetching work rows. Keep rows dense (actions right-aligned; reuse `.btn ghost`-like styling already in the app).
- [ ] **Step 2:** **New** filter chip (`r.isNew`), in the existing chip row after `All`. **Sort toggle** `updated | priority` (priority = `score` desc, nulls last) next to the existing dropdowns. **Authorship on PR rows:** `by {{ r.author }}` in the meta line for `pr`/`review` rows when present.
- [ ] **Step 3:** Gates; rebuild frontend. **Live verification (all on this machine, real data):**
  - `GET /api/v1/today`: schedule strip lists today's real calendar entries in time order (record them); no extId appears in two sections (script-check the JSON).
  - Plan an item from WORK → it appears in Today's Planned; snooze from Work → gone from both; unsnooze from Today's fold → back. Handle → drops from needsYou/assigned.
  - New chip: count equals the old since-yesterday semantics (compare against `newExtIds` via a fresh sync).
  - Priority sort visibly reorders (builds above docs). PR rows show `by sarasnt`.
  Record verbatim outputs; any dead control = BLOCKED.
- [ ] **Step 4:** README: update the Today and Work rows of the screens table to the new jobs. **Step 5: Commit** `work: the triage home — actions, new, priority, authorship`.
