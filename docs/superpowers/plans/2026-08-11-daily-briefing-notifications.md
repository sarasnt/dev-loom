# Daily Briefing + Desktop Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a stateful "Briefing" mode to the Today screen (since-yesterday diff, mark-handled, today's plan) and deliver a morning digest + urgent alerts as native desktop notifications through the host agent.

**Architecture:** Backend computes briefing/urgency from the existing work-item model and persists memory in two new tables; the Today response carries a `briefing` object plus per-item `handled`/`planned` flags. Notifications are sent backend → host agent `POST /notify` → OS-native toast (no new npm deps). A minute-tick scheduler fires the daily digest; `SyncService` triggers urgent alerts; quiet hours gate both.

**Tech Stack:** Java 25 / Spring Boot 4.1 / Postgres 16 + Flyway (JPA), Vue 3 + TypeScript + Pinia + Vite, Node host agent (`agent/devloom-agent.mjs`, built-ins only).

## Global Constraints

- Backend must remain Jackson-version-neutral; use `com.fasterxml.jackson` (Jackson2) `ObjectMapper` as already used in the codebase for JSON (do not import Boot4 `tools.jackson`).
- The host agent's HTTP endpoints run on Node built-ins ONLY — **no new npm dependencies**. Desktop toasts shell out to the OS.
- Running app shows only real data — no fixtures. Every button/toggle must actually work.
- Never POST dummy credentials to the running instance.
- Persisted non-secret settings go in the existing `app_config` key/value table via `AppConfigService`.
- Verification toolchain (no unit-test harness in this repo): frontend `npx vue-tsc --noEmit`; backend compile via `docker run --rm -v "$PWD/backend:/app" -v devloom_m2:/root/.m2 -w /app maven:3.9-eclipse-temurin-25 mvn -q -o -DskipTests compile`; runtime checks via `curl` against `http://localhost:8080/api/v1/...` and the browser at `http://localhost:8088` after `docker compose up -d --build`.
- Config key prefix for this feature: `notify.*`. Flag table keyed by work-item `ext_id`.
- Default digest time `08:30`; default quiet hours `22:00`–`08:00`; default PR-wait urgency `24`h; snapshot retention ~14 rows per kind.

---

## SLICE 1 — In-app Briefing + memory (no notifications)

### Task 1: V15 migration + JPA entities/repositories for memory

**Files:**
- Create: `backend/src/main/resources/db/migration/V15__briefing.sql`
- Create: `backend/src/main/java/com/devloom/briefing/BriefingSnapshotEntity.java`
- Create: `backend/src/main/java/com/devloom/briefing/BriefingSnapshotRepository.java`
- Create: `backend/src/main/java/com/devloom/briefing/WorkItemFlagEntity.java`
- Create: `backend/src/main/java/com/devloom/briefing/WorkItemFlagRepository.java`

**Interfaces:**
- Produces: `BriefingSnapshotEntity` (fields `id`, `takenAt: Instant`, `kind: String`, `itemsJson: String`); `WorkItemFlagEntity` (fields `extId: String`, `handledAt: Instant`, `plannedAt: Instant`).
- Produces: `BriefingSnapshotRepository extends JpaRepository<BriefingSnapshotEntity, Long>` with `Optional<BriefingSnapshotEntity> findFirstByKindOrderByTakenAtDesc(String kind)` and `List<BriefingSnapshotEntity> findByKindOrderByTakenAtDesc(String kind)`.
- Produces: `WorkItemFlagRepository extends JpaRepository<WorkItemFlagEntity, String>` (id type `String` = extId).

- [ ] **Step 1: Write the migration**

`V15__briefing.sql`:
```sql
-- Point-in-time snapshot of the work set, for since-yesterday diffs and new-urgent detection.
CREATE TABLE briefing_snapshot (
    id         BIGSERIAL PRIMARY KEY,
    taken_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    kind       VARCHAR(16) NOT NULL,        -- 'digest' | 'sync'
    items_json TEXT NOT NULL                -- JSON array of {extId,type,source,status,urgencyKey}
);
CREATE INDEX idx_briefing_snapshot_kind_taken ON briefing_snapshot (kind, taken_at DESC);

-- Per-work-item user state that must outlive a sync (keyed by source-stable ext id).
CREATE TABLE work_item_flag (
    ext_id     VARCHAR(190) PRIMARY KEY,
    handled_at TIMESTAMPTZ,
    planned_at TIMESTAMPTZ
);
```

- [ ] **Step 2: Write `BriefingSnapshotEntity`** (mirror the style of `com/devloom/common/AppConfigEntity.java` and `com/devloom/repos/GitRepoEntity.java`)

```java
package com.devloom.briefing;

import java.time.Instant;
import jakarta.persistence.*;

@Entity
@Table(name = "briefing_snapshot")
public class BriefingSnapshotEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "taken_at", nullable = false)
    private Instant takenAt;
    @Column(nullable = false)
    private String kind;
    @Column(name = "items_json", columnDefinition = "text", nullable = false)
    private String itemsJson;

    protected BriefingSnapshotEntity() {}
    public static BriefingSnapshotEntity of(String kind, String itemsJson) {
        BriefingSnapshotEntity e = new BriefingSnapshotEntity();
        e.takenAt = Instant.now();
        e.kind = kind;
        e.itemsJson = itemsJson;
        return e;
    }
    public Long getId() { return id; }
    public Instant getTakenAt() { return takenAt; }
    public String getKind() { return kind; }
    public String getItemsJson() { return itemsJson; }
}
```

- [ ] **Step 3: Write `WorkItemFlagEntity`**

```java
package com.devloom.briefing;

import java.time.Instant;
import jakarta.persistence.*;

@Entity
@Table(name = "work_item_flag")
public class WorkItemFlagEntity {
    @Id
    @Column(name = "ext_id")
    private String extId;
    @Column(name = "handled_at")
    private Instant handledAt;
    @Column(name = "planned_at")
    private Instant plannedAt;

    protected WorkItemFlagEntity() {}
    public static WorkItemFlagEntity of(String extId) {
        WorkItemFlagEntity e = new WorkItemFlagEntity();
        e.extId = extId;
        return e;
    }
    public String getExtId() { return extId; }
    public Instant getHandledAt() { return handledAt; }
    public void setHandledAt(Instant t) { this.handledAt = t; }
    public Instant getPlannedAt() { return plannedAt; }
    public void setPlannedAt(Instant t) { this.plannedAt = t; }
}
```

- [ ] **Step 4: Write both repositories**

```java
package com.devloom.briefing;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface BriefingSnapshotRepository extends JpaRepository<BriefingSnapshotEntity, Long> {
    Optional<BriefingSnapshotEntity> findFirstByKindOrderByTakenAtDesc(String kind);
    List<BriefingSnapshotEntity> findByKindOrderByTakenAtDesc(String kind);
}
```
```java
package com.devloom.briefing;
import org.springframework.data.jpa.repository.JpaRepository;
public interface WorkItemFlagRepository extends JpaRepository<WorkItemFlagEntity, String> {}
```

- [ ] **Step 5: Compile backend**

Run: `docker run --rm -v "$PWD/backend:/app" -v devloom_m2:/root/.m2 -w /app maven:3.9-eclipse-temurin-25 mvn -q -o -DskipTests compile`
Expected: exit 0.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration/V15__briefing.sql backend/src/main/java/com/devloom/briefing/
git commit -m "briefing: V15 migration + snapshot/flag entities"
```

---

### Task 2: Urgency + snapshot item model (shared computation)

**Files:**
- Create: `backend/src/main/java/com/devloom/briefing/UrgencyRules.java`
- Reference (read only): `backend/src/main/java/com/devloom/workmodel/WorkItemEntity.java`, `backend/src/main/java/com/devloom/api/TodayService.java`

**Interfaces:**
- Produces: `record SnapItem(String extId, String type, String source, String status, String urgencyKey)` (nested in `UrgencyRules`).
- Produces: `UrgencyRules.urgencyKey(WorkItemEntity w, NotifyConfig cfg) -> String|null` — returns `"ci-fail"`, `"review-req"`, `"pr-wait"`, or `null`.
- Consumes: `WorkItemEntity` getters (`getExtId`, `getType`, `getSource`, `getStatus`, `getStatusTone`, `getMetaCsv`).
- Note: `NotifyConfig` is defined in Task 8; for Slice 1, `urgencyKey` is called with default thresholds. To avoid a forward dependency, Task 2 hardcodes defaults now and Task 8 adds the config-aware overload.

- [ ] **Step 1: Inspect how build/PR/review items are represented**

Run: `grep -rn "\"build\"\|statusTone\|\"pr\"\|review\|metaCsv\|failed" backend/src/main/java/com/devloom/integrations/GitHubConnector.java backend/src/main/java/com/devloom/api/TodayService.java`
Read the results to confirm: build failures have `type == "build"` and a failed `statusTone` (`"fail"`); PRs have `type == "pr"`; review-requested and PR-wait are derived from `metaCsv` or status. Use what you find; if review data isn't present, `review-req` simply never fires (document that inline).

- [ ] **Step 2: Write `UrgencyRules`**

```java
package com.devloom.briefing;

import com.devloom.workmodel.WorkItemEntity;

/** Deterministic "is this urgent, and why" for the briefing (spec §7). */
public final class UrgencyRules {
    private UrgencyRules() {}

    public record SnapItem(String extId, String type, String source, String status, String urgencyKey) {}

    /** Default thresholds (Slice 1). Task 8 adds a config-aware overload. */
    public static String urgencyKey(WorkItemEntity w) {
        return urgencyKey(w, true, true, 24);
    }

    public static String urgencyKey(WorkItemEntity w, boolean ci, boolean review, int prWaitHours) {
        String type = w.getType() == null ? "" : w.getType();
        String tone = w.getStatusTone() == null ? "" : w.getStatusTone();
        if (ci && "build".equals(type) && "fail".equals(tone)) return "ci-fail";
        if (review && "pr".equals(type) && isReviewRequested(w)) return "review-req";
        if ("pr".equals(type) && waitHours(w) >= prWaitHours) return "pr-wait";
        return null;
    }

    // Review-requested / wait-age are encoded in the item's meta today; adapt to the real shape
    // found in Step 1. If absent, these return false/0 and the rule never fires (documented).
    private static boolean isReviewRequested(WorkItemEntity w) {
        String meta = (w.getMetaCsv() + " " + w.getStatus()).toLowerCase();
        return meta.contains("review");
    }
    private static long waitHours(WorkItemEntity w) {
        return 0; // TODO-at-Step-1: wire to the real wait signal if present; else stays 0.
    }
}
```

> NOTE for the implementer: the `waitHours` stub above is the ONE place you must replace with the real signal discovered in Step 1 (e.g. a `wait:51h` chip or a timestamp in `metaCsv`). If no such signal exists in the model, leave it returning 0 and add a one-line comment saying pr-wait is inert until wait-age is modeled. Do not ship the literal `TODO` text.

- [ ] **Step 3: Compile backend** (command as Task 1 Step 5). Expected: exit 0.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/devloom/briefing/UrgencyRules.java
git commit -m "briefing: deterministic urgency rules"
```

---

### Task 3: BriefingService — diff, flags, compose

**Files:**
- Create: `backend/src/main/java/com/devloom/briefing/BriefingService.java`
- Modify: `backend/src/main/java/com/devloom/api/Dto.java` (add `Briefing` record; extend `Recommendation`)

**Interfaces:**
- Consumes: `WorkItemRepository` (`findAll`, `findByTypeOrderBySortOrderAsc`), `BriefingSnapshotRepository`, `WorkItemFlagRepository`, `com.fasterxml.jackson.databind.ObjectMapper`.
- Produces: `Dto.Briefing(List<Dto.Recommendation> newItems, List<Dto.Recommendation> resolved, List<Dto.Recommendation> waiting, List<Dto.Recommendation> needsYou, List<Dto.Recommendation> plan)`.
- Produces on `BriefingService`:
  - `boolean isHandled(String extId)`, `boolean isPlanned(String extId)`
  - `Set<String> handledExtIds()`, `Set<String> plannedExtIds()`
  - `void toggleHandled(String extId)`, `void togglePlan(String extId)`
  - `List<UrgencyRules.SnapItem> currentSnapshot()` — builds SnapItems for all work items
  - `void clearHandledOnReopen()` — clears `handled_at` for items whose current status is active again
  - `Dto.Briefing build(java.util.function.Function<WorkItemEntity, Dto.Recommendation> toRec)` — assembles the four lists using the last `digest` snapshot as the baseline

- [ ] **Step 1: Extend `Dto.Recommendation` with `handled` + `planned`**

In `Dto.java`, change the `Recommendation` record to add two trailing booleans:
```java
public record Recommendation(
        String id, int rank, String type, String title, String source, String why,
        boolean isHypothesis, List<SignalChip> chips, Boolean lead,
        List<SignalComponent> signals, List<EvidenceRef> evidence, Double score,
        List<String> actions, String url, boolean handled, boolean planned) {}
```
Then fix the single construction site in `TodayService` (Task 4 does this) — for now the compile will break until Task 4; that's expected and Task 4 immediately follows.

Add the `Briefing` record near the Today records:
```java
public record Briefing(
        List<Recommendation> newItems, List<Recommendation> resolved,
        List<Recommendation> waiting, List<Recommendation> needsYou,
        List<Recommendation> plan) {}
```
And add `Briefing briefing` as the last field of the `Today` record:
```java
public record Today(
        String workspace, String user, String now, Changed changed, Sync sync,
        Model model, Boundary boundary, List<Recommendation> next,
        int everythingCount, int snoozedCount, Briefing briefing) {}
```

- [ ] **Step 2: Write `BriefingService`**

```java
package com.devloom.briefing;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devloom.api.Dto;
import com.devloom.workmodel.WorkItemEntity;
import com.devloom.workmodel.WorkItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Memory for the Today "Briefing" mode: since-yesterday diff, handled/plan flags, urgency. */
@Service
public class BriefingService {
    private final WorkItemRepository work;
    private final BriefingSnapshotRepository snapshots;
    private final WorkItemFlagRepository flags;
    private final ObjectMapper json = new ObjectMapper();

    public BriefingService(WorkItemRepository work, BriefingSnapshotRepository snapshots,
                           WorkItemFlagRepository flags) {
        this.work = work; this.snapshots = snapshots; this.flags = flags;
    }

    public boolean isHandled(String extId) {
        return flags.findById(extId).map(f -> f.getHandledAt() != null).orElse(false);
    }
    public boolean isPlanned(String extId) {
        return flags.findById(extId).map(f -> f.getPlannedAt() != null).orElse(false);
    }
    public Set<String> handledExtIds() {
        return flags.findAll().stream().filter(f -> f.getHandledAt() != null)
                .map(WorkItemFlagEntity::getExtId).collect(Collectors.toSet());
    }
    public Set<String> plannedExtIds() {
        return flags.findAll().stream().filter(f -> f.getPlannedAt() != null)
                .map(WorkItemFlagEntity::getExtId).collect(Collectors.toSet());
    }

    @Transactional
    public void toggleHandled(String extId) {
        WorkItemFlagEntity f = flags.findById(extId).orElseGet(() -> WorkItemFlagEntity.of(extId));
        f.setHandledAt(f.getHandledAt() == null ? Instant.now() : null);
        flags.save(f);
    }
    @Transactional
    public void togglePlan(String extId) {
        WorkItemFlagEntity f = flags.findById(extId).orElseGet(() -> WorkItemFlagEntity.of(extId));
        f.setPlannedAt(f.getPlannedAt() == null ? Instant.now() : null);
        flags.save(f);
    }

    /** SnapItems for every current work item (for diffing + urgent detection). */
    public List<UrgencyRules.SnapItem> currentSnapshot() {
        List<UrgencyRules.SnapItem> out = new ArrayList<>();
        for (WorkItemEntity w : work.findAll()) {
            out.add(new UrgencyRules.SnapItem(w.getExtId(), w.getType(), w.getSource(),
                    w.getStatus(), UrgencyRules.urgencyKey(w)));
        }
        return out;
    }

    /** A "done"-ish status means the item is resolved even if still present. */
    public static boolean isDoneStatus(String status) {
        String s = status == null ? "" : status.toLowerCase();
        return s.contains("done") || s.contains("complete") || s.contains("closed")
                || s.contains("merged") || s.contains("shipped") || s.contains("archived")
                || s.contains("passed") || s.contains("success");
    }

    /** Re-open guard: clear a handled flag when its item is present and NOT in a done status. */
    @Transactional
    public void clearHandledOnReopen() {
        Map<String, WorkItemEntity> byId = work.findAll().stream()
                .collect(Collectors.toMap(WorkItemEntity::getExtId, w -> w, (a, b) -> a));
        for (WorkItemFlagEntity f : flags.findAll()) {
            if (f.getHandledAt() == null) continue;
            WorkItemEntity w = byId.get(f.getExtId());
            if (w != null && !isDoneStatus(w.getStatus())) { f.setHandledAt(null); flags.save(f); }
        }
    }

    /** Persist the current work set as a snapshot of the given kind, pruning old rows. */
    @Transactional
    public void writeSnapshot(String kind) {
        try {
            String body = json.writeValueAsString(currentSnapshot());
            snapshots.save(BriefingSnapshotEntity.of(kind, body));
        } catch (Exception e) { /* snapshot is best-effort */ }
        List<BriefingSnapshotEntity> all = snapshots.findByKindOrderByTakenAtDesc(kind);
        for (int i = 14; i < all.size(); i++) snapshots.delete(all.get(i));
    }

    /** The last snapshot of a kind, decoded to a extId->SnapItem map (empty if none). */
    public Map<String, UrgencyRules.SnapItem> lastSnapshot(String kind) {
        return snapshots.findFirstByKindOrderByTakenAtDesc(kind)
                .map(s -> decode(s.getItemsJson())).orElseGet(Map::of);
    }
    private Map<String, UrgencyRules.SnapItem> decode(String body) {
        try {
            UrgencyRules.SnapItem[] arr = json.readValue(body, UrgencyRules.SnapItem[].class);
            Map<String, UrgencyRules.SnapItem> m = new LinkedHashMap<>();
            for (UrgencyRules.SnapItem s : arr) m.put(s.extId(), s);
            return m;
        } catch (Exception e) { return Map.of(); }
    }

    /**
     * Assemble the Briefing. `toRec` converts a work item into a Recommendation (with handled/plan
     * already applied by the caller). Baseline for "since yesterday" is the last digest snapshot.
     */
    public Dto.Briefing build(Function<WorkItemEntity, Dto.Recommendation> toRec) {
        clearHandledOnReopen();
        Map<String, UrgencyRules.SnapItem> base = lastSnapshot("digest");
        Set<String> handled = handledExtIds();
        Set<String> planned = plannedExtIds();

        List<Dto.Recommendation> newItems = new ArrayList<>();
        List<Dto.Recommendation> waiting = new ArrayList<>();
        List<Dto.Recommendation> needsYou = new ArrayList<>();
        List<Dto.Recommendation> plan = new ArrayList<>();
        Set<String> present = new LinkedHashSet<>();

        for (WorkItemEntity w : work.findAll()) {
            present.add(w.getExtId());
            Dto.Recommendation rec = toRec.apply(w);
            if (planned.contains(w.getExtId())) plan.add(rec);
            if (handled.contains(w.getExtId())) continue;         // handled drops from active lists
            boolean known = base.containsKey(w.getExtId());
            if (!known) newItems.add(rec); else waiting.add(rec);
            if (UrgencyRules.urgencyKey(w) != null) needsYou.add(rec);
        }

        // Resolved: in the baseline but now gone, OR present in a done status.
        Map<String, WorkItemEntity> byId = work.findAll().stream()
                .collect(Collectors.toMap(WorkItemEntity::getExtId, w -> w, (a, b) -> a));
        List<Dto.Recommendation> resolved = new ArrayList<>();
        for (String extId : base.keySet()) {
            WorkItemEntity w = byId.get(extId);
            if (w == null) continue; // gone entirely — nothing to render
            if (isDoneStatus(w.getStatus())) resolved.add(toRec.apply(w));
        }
        return new Dto.Briefing(newItems, resolved, waiting, needsYou, plan);
    }
}
```

- [ ] **Step 3: Commit** (compiles after Task 4 wires `toRec`; commit together with Task 4). Skip standalone commit; proceed to Task 4.

---

### Task 4: Wire briefing into TodayService + handled/plan endpoints

**Files:**
- Modify: `backend/src/main/java/com/devloom/api/TodayService.java`
- Modify: `backend/src/main/java/com/devloom/api/ApiController.java`

**Interfaces:**
- Consumes: `BriefingService` (Task 3), the existing `TodayService` recommendation builder.
- Produces: `TodayService` injects `BriefingService`; its recommendation builder sets `handled`/`planned`; `today()` includes `briefing`.
- Produces endpoints: `POST /today/{id}/handled` and `POST /today/{id}/plan` returning `Dto.Today`.

- [ ] **Step 1: Read the current recommendation construction in `TodayService`**

Run: `grep -n "new Dto.Recommendation\|Recommendation\|snoozed\|getUrl\|toRec\|WorkItemEntity" backend/src/main/java/com/devloom/api/TodayService.java`
Identify the method that turns a `WorkItemEntity` into a `Dto.Recommendation` (around line 146, `... s.score(), actionsFor(type), w.getUrl()`).

- [ ] **Step 2: Inject `BriefingService` and thread `handled`/`planned`**

- Add `private final BriefingService briefing;` to `TodayService`, add it to the constructor, assign it.
- Extract (or locate) the single `WorkItemEntity -> Dto.Recommendation` conversion into a method `recFor(WorkItemEntity w)`. Append the two new trailing args to the `new Dto.Recommendation(...)` call:
```java
briefing.isHandled(w.getExtId()), briefing.isPlanned(w.getExtId())
```
- In the method that builds the `Dto.Today`, pass the briefing as the final arg:
```java
briefing.build(this::recFor)
```
Make `recFor` reusable by `briefing.build` (method reference `this::recFor`). If the existing code maps items via a lambda, refactor that lambda body into `recFor` so both the `next` list and the briefing use the identical conversion (DRY).

- [ ] **Step 3: Add the toggle endpoints in `ApiController`**

Find the existing snooze endpoint (`grep -n "snooze\|/today" backend/src/main/java/com/devloom/api/ApiController.java`) and mirror it:
```java
@PostMapping("/today/{id}/handled")
public Dto.Today handled(@PathVariable String id) {
    briefingService.toggleHandled(resolveExtId(id));
    return todayService.today();
}

@PostMapping("/today/{id}/plan")
public Dto.Today plan(@PathVariable String id) {
    briefingService.togglePlan(resolveExtId(id));
    return todayService.today();
}
```
Inject `BriefingService briefingService` into `ApiController` (add to constructor). For `resolveExtId`, use the SAME mapping snooze uses to turn a recommendation id into a work-item ext id — inspect the snooze path and reuse it (often the recommendation `id` already IS the ext id; if so, `resolveExtId` is identity). Add a short private helper documenting which it is.

- [ ] **Step 4: Compile backend.** Expected: exit 0.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/devloom/briefing/BriefingService.java backend/src/main/java/com/devloom/api/Dto.java backend/src/main/java/com/devloom/api/TodayService.java backend/src/main/java/com/devloom/api/ApiController.java
git commit -m "briefing: assemble Briefing in /today + handled/plan toggle endpoints"
```

- [ ] **Step 6: Runtime check**

Rebuild backend: `docker compose up -d --build backend`; wait for `:8080/api/v1/settings` = 200.
Run: `curl -s http://localhost:8080/api/v1/today | python -c "import sys,json;d=json.load(sys.stdin);b=d['briefing'];print({k:len(b[k]) for k in b})"`
Expected: prints counts for `newItems/resolved/waiting/needsYou/plan` (numbers, no error).
Run a toggle: `curl -s -X POST http://localhost:8080/api/v1/today/<some-id>/plan >/dev/null` then re-fetch `/today` and confirm that item now has `planned:true` and appears in `briefing.plan`.

---

### Task 5: Frontend — types, API, store actions

**Files:**
- Modify: `frontend/src/types.ts`
- Modify: `frontend/src/api/http.ts`, `frontend/src/api/index.ts`, `frontend/src/api/stub.ts`
- Modify: `frontend/src/stores/dashboard.ts`

**Interfaces:**
- Produces types: `Recommendation` gains `handled: boolean; planned: boolean`; new `Briefing { newItems; resolved; waiting; needsYou; plan: Recommendation[] }`; `TodayData` gains `briefing: Briefing`.
- Produces API: `toggleHandled(id: string): Promise<TodayData>`, `togglePlan(id: string): Promise<TodayData>`.
- Produces store: `handleItem(id)`, `planItem(id)` (set `today.value` from the response, like `snoozeItem`).

- [ ] **Step 1: Extend `types.ts`**
```ts
// on Recommendation:
  handled: boolean
  planned: boolean
export interface Briefing {
  newItems: Recommendation[]
  resolved: Recommendation[]
  waiting: Recommendation[]
  needsYou: Recommendation[]
  plan: Recommendation[]
}
// on TodayData:
  briefing: Briefing
```

- [ ] **Step 2: Add API functions** (`http.ts`)
```ts
export const toggleHandled = (id: string) => post<TodayData>(`/today/${id}/handled`, {})
export const togglePlan = (id: string) => post<TodayData>(`/today/${id}/plan`, {})
```
Re-export both in `index.ts`. In `stub.ts`, add stubs returning the current stub `TODAY` object (import/derive it) so `VITE_USE_STUB` builds still typecheck:
```ts
export function toggleHandled() { return delay(TODAY) }
export function togglePlan() { return delay(TODAY) }
```
Also extend the stub `TODAY` object with `briefing: { newItems: [], resolved: [], waiting: [], needsYou: [], plan: [] }` and add `handled: false, planned: false` to any stub recommendations so types are satisfied.

- [ ] **Step 3: Add store actions** (`dashboard.ts`, mirror `snoozeItem`)
```ts
async function handleItem(id: string) {
  try { today.value = await toggleHandled(id) } catch { /* leave as-is */ }
}
async function planItem(id: string) {
  try { today.value = await togglePlan(id) } catch { /* leave as-is */ }
}
```
Import `toggleHandled, togglePlan` from `../api`; add `handleItem, planItem` to the store's return object.

- [ ] **Step 4: Typecheck**
Run: `cd frontend && npx vue-tsc --noEmit`
Expected: no output (clean).

- [ ] **Step 5: Commit**
```bash
git add frontend/src/types.ts frontend/src/api/ frontend/src/stores/dashboard.ts
git commit -m "briefing: frontend types, API, store actions for handled/plan + briefing"
```

---

### Task 6: Frontend — Today Briefing|Triage toggle + card actions

**Files:**
- Modify: `frontend/src/views/TodayView.vue`
- Modify: `frontend/src/components/RecommendationCard.vue`

**Interfaces:**
- Consumes: `store.handleItem`, `store.planItem`, `today.briefing`.
- Produces: a segmented `Briefing | Triage` control; Briefing renders `newItems`/`needsYou`/`plan` (+ a compact `resolved`/`waiting`); RecommendationCard shows **Handled** and **Plan** actions.

- [ ] **Step 1: Add the mode toggle + Briefing sections to `TodayView.vue`**

Add a `ref` for the mode, persisted in `localStorage` with the morning-default heuristic:
```ts
import { ref } from 'vue'
const MODE_KEY = 'devloom.todayMode'
function defaultMode(): 'briefing' | 'triage' {
  const saved = localStorage.getItem(MODE_KEY)
  if (saved === 'briefing' || saved === 'triage') return saved
  const h = new Date().getHours()
  return h < 12 ? 'briefing' : 'triage'   // morning → briefing
}
const mode = ref<'briefing' | 'triage'>(defaultMode())
function setMode(m: 'briefing' | 'triage') { mode.value = m; localStorage.setItem(MODE_KEY, m) }
```
In the template, under the `head`, add:
```html
<div class="modes">
  <button class="seg" :class="{ on: mode === 'briefing' }" @click="setMode('briefing')">Briefing</button>
  <button class="seg" :class="{ on: mode === 'triage' }" @click="setMode('triage')">Triage</button>
</div>
```
Wrap the existing `Next`/`WarpList`/`more` block in `<template v-if="mode === 'triage'">`. Add a Briefing block:
```html
<template v-else>
  <template v-if="today.briefing.needsYou.length">
    <div class="sectlab"><span class="eyebrow">Needs you now</span></div>
    <WarpList :items="today.briefing.needsYou" />
  </template>
  <div class="sectlab"><span class="eyebrow">Since yesterday</span></div>
  <template v-if="today.briefing.newItems.length">
    <div class="subhead mono">New</div><WarpList :items="today.briefing.newItems" />
  </template>
  <template v-if="today.briefing.resolved.length">
    <div class="subhead mono">Resolved ({{ today.briefing.resolved.length }})</div>
    <WarpList :items="today.briefing.resolved" />
  </template>
  <template v-if="today.briefing.plan.length">
    <div class="sectlab"><span class="eyebrow">Today's plan</span></div>
    <WarpList :items="today.briefing.plan" />
  </template>
  <div v-if="!today.briefing.newItems.length && !today.briefing.needsYou.length && !today.briefing.plan.length"
       class="mono empty">Nothing needs you yet — enjoy the quiet.</div>
</template>
```
Add minimal CSS for `.modes`, `.seg`, `.seg.on`, `.subhead` consistent with existing tokens (`var(--line)`, `var(--warp)`, `var(--warp-hi)`, `var(--faint-text)`).

- [ ] **Step 2: Add Handled + Plan actions to `RecommendationCard.vue`**

In `runAction` add cases; and render two buttons (near the existing Why/Snooze secondary actions). Since actions are label-driven, add them unconditionally in the secondary row rather than via the `actions[]` array:
```ts
function toggleHandled(item: Recommendation) { store.handleItem(item.id) }
function togglePlan(item: Recommendation) { store.planItem(item.id) }
```
```html
<button class="act" @click="togglePlan(item)">{{ item.planned ? '− Plan' : '+ Plan' }}</button>
<button class="act" @click="toggleHandled(item)">{{ item.handled ? '↩ Unhandle' : '✓ Handled' }}</button>
```
Style `.act` to match the existing secondary action buttons in this component.

- [ ] **Step 3: Typecheck**
Run: `cd frontend && npx vue-tsc --noEmit`
Expected: clean.

- [ ] **Step 4: Commit**
```bash
git add frontend/src/views/TodayView.vue frontend/src/components/RecommendationCard.vue
git commit -m "briefing: Today Briefing|Triage toggle + Handled/Plan card actions"
```

- [ ] **Step 5: Runtime check (browser)**
`docker compose up -d --build frontend`; open `http://localhost:8088`, hard-refresh.
Verify: the Today screen shows a `Briefing | Triage` toggle; Briefing lists sections; clicking **✓ Handled** removes an item from Briefing and it stays gone after refresh; **+ Plan** moves it into Today's plan.

---

## SLICE 2 — Host agent notify + Settings

### Task 7: Host agent `POST /notify` (OS-native toast)

**Files:**
- Modify: `agent/devloom-agent.mjs`

**Interfaces:**
- Produces: HTTP `POST /notify { title, body, urgency }` → `{ ok, error? }`.
- Produces: `async function osNotify(title, body)` returning `{ ok, error? }`, dispatching by `process.platform` and shelling out via the existing `run(cmd, args, opts)` helper (no new deps).

- [ ] **Step 1: Add `osNotify` near the other helpers (after `git`/`run`)**
```js
// Native desktop toast — no npm deps; shells out to the OS notifier. Windows uses a PowerShell
// toast (built into Win10/11), macOS uses osascript, Linux uses notify-send.
async function osNotify(title, body) {
  const t = String(title || 'DevLoom')
  const b = String(body || '')
  try {
    if (IS_WIN) {
      const esc = (s) => s.replace(/`/g, '``').replace(/"/g, '`"').replace(/\$/g, '`$')
      const ps = [
        '[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] > $null;',
        '[Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] > $null;',
        '$xml = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02);',
        `$texts = $xml.GetElementsByTagName("text");`,
        `$texts.Item(0).AppendChild($xml.CreateTextNode("${esc(t)}")) > $null;`,
        `$texts.Item(1).AppendChild($xml.CreateTextNode("${esc(b)}")) > $null;`,
        '$toast = [Windows.UI.Notifications.ToastNotification]::new($xml);',
        '[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier("DevLoom").Show($toast);',
      ].join(' ')
      const r = await run('powershell', ['-NoProfile', '-NonInteractive', '-Command', ps], { timeoutMs: 10000 })
      return r.code === 0 ? { ok: true } : { ok: false, error: (r.err || 'powershell toast failed').trim().slice(0, 300) }
    }
    if (process.platform === 'darwin') {
      const esc = (s) => s.replace(/"/g, '\\"')
      const r = await run('osascript', ['-e', `display notification "${esc(b)}" with title "${esc(t)}"`], { timeoutMs: 8000 })
      return r.code === 0 ? { ok: true } : { ok: false, error: (r.err || 'osascript failed').trim().slice(0, 300) }
    }
    const r = await run('notify-send', [t, b], { timeoutMs: 8000 })
    return r.code === 0 ? { ok: true } : { ok: false, error: (r.err || 'notify-send failed (is libnotify installed?)').trim().slice(0, 300) }
  } catch (e) {
    return { ok: false, error: String(e).slice(0, 300) }
  }
}
```

- [ ] **Step 2: Route `POST /notify`** (beside the other POST routes)
```js
if (req.method === 'POST' && url.pathname === '/notify') {
  const { title, body } = await readBody(req)
  return json(res, 200, await osNotify(title, body))
}
```

- [ ] **Step 3: Syntax check + restart agent**
Run: `node --check agent/devloom-agent.mjs` → prints nothing / exit 0.
Restart the host agent (stop the running `node devloom-agent.mjs`, start it again).

- [ ] **Step 4: Live check**
Run: `curl -s -X POST http://127.0.0.1:8765/notify -H "Content-Type: application/json" -d '{"title":"DevLoom","body":"test toast"}'`
Expected: `{"ok":true}` AND a visible desktop toast.

- [ ] **Step 5: Commit**
```bash
git add agent/devloom-agent.mjs
git commit -m "agent: POST /notify — native desktop toast (no deps)"
```

---

### Task 8: Backend notify config + HostAgentClient.notify + test endpoint

**Files:**
- Modify: `backend/src/main/java/com/devloom/ai/HostAgentClient.java`
- Modify: `backend/src/main/java/com/devloom/common/AppConfigService.java`
- Create: `backend/src/main/java/com/devloom/briefing/NotifyConfig.java`
- Modify: `backend/src/main/java/com/devloom/api/SettingsController.java`
- Modify: `backend/src/main/java/com/devloom/briefing/UrgencyRules.java` (config-aware overload already present from Task 2)

**Interfaces:**
- Produces: `HostAgentClient.notify(String title, String body, String urgency) -> Map<String,Object>` (POST `/notify`).
- Produces: `record NotifyConfig(boolean enabled, String digestTime, String quietStart, String quietEnd, boolean urgentCi, boolean urgentReview, int prWaitHours)` with `static NotifyConfig from(AppConfigService cfg)` reading the `notify.*` keys with the spec defaults.
- Produces: `AppConfigService` constants `NOTIFY_ENABLED`, `NOTIFY_DIGEST_TIME`, `NOTIFY_QUIET_START`, `NOTIFY_QUIET_END`, `NOTIFY_URGENT_CI`, `NOTIFY_URGENT_REVIEW`, `NOTIFY_PR_WAIT_HOURS`.
- Produces endpoints: `GET /settings` includes `notify` block; `PUT /settings/notifications`; `POST /notifications/test`.

- [ ] **Step 1: `HostAgentClient.notify`**
```java
public Map<String, Object> notify(String title, String body, String urgency) {
    return post("/notify", Map.of("title", title == null ? "" : title,
            "body", body == null ? "" : body, "urgency", urgency == null ? "normal" : urgency));
}
```

- [ ] **Step 2: `AppConfigService` constants** (add near existing keys)
```java
public static final String NOTIFY_ENABLED = "notify.enabled";
public static final String NOTIFY_DIGEST_TIME = "notify.digestTime";
public static final String NOTIFY_QUIET_START = "notify.quietStart";
public static final String NOTIFY_QUIET_END = "notify.quietEnd";
public static final String NOTIFY_URGENT_CI = "notify.urgent.ci";
public static final String NOTIFY_URGENT_REVIEW = "notify.urgent.review";
public static final String NOTIFY_PR_WAIT_HOURS = "notify.urgent.prWaitHours";
```

- [ ] **Step 3: `NotifyConfig`**
```java
package com.devloom.briefing;

import com.devloom.common.AppConfigService;

public record NotifyConfig(boolean enabled, String digestTime, String quietStart, String quietEnd,
                           boolean urgentCi, boolean urgentReview, int prWaitHours) {
    public static NotifyConfig from(AppConfigService cfg) {
        return new NotifyConfig(
            cfg.get(AppConfigService.NOTIFY_ENABLED).map(Boolean::parseBoolean).orElse(false),
            cfg.get(AppConfigService.NOTIFY_DIGEST_TIME).orElse("08:30"),
            cfg.get(AppConfigService.NOTIFY_QUIET_START).orElse("22:00"),
            cfg.get(AppConfigService.NOTIFY_QUIET_END).orElse("08:00"),
            cfg.get(AppConfigService.NOTIFY_URGENT_CI).map(Boolean::parseBoolean).orElse(true),
            cfg.get(AppConfigService.NOTIFY_URGENT_REVIEW).map(Boolean::parseBoolean).orElse(true),
            cfg.get(AppConfigService.NOTIFY_PR_WAIT_HOURS).map(Integer::parseInt).orElse(24));
    }
}
```

- [ ] **Step 4: Add config-aware `urgencyKey` overload usage** — confirm Task 2 already provides `urgencyKey(w, ci, review, prWaitHours)`. Add to `BriefingService` a field `NotifyConfig` is NOT needed here; Slice 3 will pass config where it matters. No change required now.

- [ ] **Step 5: Extend `SettingsController`**
- In `get()`, add a `notify` map from `NotifyConfig.from(config)`.
- Add:
```java
public record NotifySettings(Boolean enabled, String digestTime, String quietStart,
                             String quietEnd, Boolean urgentCi, Boolean urgentReview, Integer prWaitHours) {}

@PutMapping("/notifications")
public Map<String, Object> setNotifications(@RequestBody NotifySettings b) {
    if (b != null) {
        if (b.enabled() != null) config.set(AppConfigService.NOTIFY_ENABLED, String.valueOf(b.enabled()));
        if (b.digestTime() != null) config.set(AppConfigService.NOTIFY_DIGEST_TIME, b.digestTime());
        if (b.quietStart() != null) config.set(AppConfigService.NOTIFY_QUIET_START, b.quietStart());
        if (b.quietEnd() != null) config.set(AppConfigService.NOTIFY_QUIET_END, b.quietEnd());
        if (b.urgentCi() != null) config.set(AppConfigService.NOTIFY_URGENT_CI, String.valueOf(b.urgentCi()));
        if (b.urgentReview() != null) config.set(AppConfigService.NOTIFY_URGENT_REVIEW, String.valueOf(b.urgentReview()));
        if (b.prWaitHours() != null) config.set(AppConfigService.NOTIFY_PR_WAIT_HOURS, String.valueOf(b.prWaitHours()));
    }
    return get();
}
```
- Add a test endpoint (inject `HostAgentClient agent` into `SettingsController`):
```java
@PostMapping("/notifications/test")
public Map<String, Object> testNotification() {
    return agent.notify("DevLoom", "Test notification — your briefing will look like this.", "normal");
}
```

- [ ] **Step 6: Compile backend.** Expected: exit 0.

- [ ] **Step 7: Commit**
```bash
git add backend/src/main/java/com/devloom/ai/HostAgentClient.java backend/src/main/java/com/devloom/common/AppConfigService.java backend/src/main/java/com/devloom/briefing/NotifyConfig.java backend/src/main/java/com/devloom/api/SettingsController.java
git commit -m "notify: config + HostAgentClient.notify + settings + test endpoint"
```

- [ ] **Step 8: Runtime check**
`docker compose up -d --build backend`; then `curl -s -X POST http://localhost:8080/api/v1/settings/notifications -H "Content-Type: application/json" -d '{"enabled":true,"digestTime":"08:30"}'` → returns the notify block; `curl -s -X POST http://localhost:8080/api/v1/notifications/test` → `{"ok":true}` + a desktop toast (agent must be running).

---

### Task 9: Frontend — Settings › Notifications

**Files:**
- Modify: `frontend/src/types.ts` (SettingsData `notify` block)
- Modify: `frontend/src/api/http.ts`, `index.ts`, `stub.ts`
- Modify: `frontend/src/views/GeneralView.vue` (add a Notifications section)

**Interfaces:**
- Produces types: `SettingsData.notify: { enabled: boolean; digestTime: string; quietStart: string; quietEnd: string; urgentCi: boolean; urgentReview: boolean; prWaitHours: number }`.
- Produces API: `saveNotificationSettings(partial): Promise<SettingsData>`, `testNotification(): Promise<{ ok: boolean; error?: string }>`.

- [ ] **Step 1: Types** — add the `notify` object to `SettingsData`.
- [ ] **Step 2: API** — `http.ts`:
```ts
export const saveNotificationSettings = (b: Partial<SettingsData['notify']>) =>
  put<SettingsData>('/settings/notifications', b)
export const testNotification = () =>
  post<{ ok: boolean; error?: string }>('/notifications/test', {})
```
Re-export in `index.ts`; stub in `stub.ts` (return the stub settings with a default `notify` block; `testNotification` returns `{ ok: false, error: 'offline' }`). Also add a default `notify` block to the stub `fetchSettings`.
- [ ] **Step 3: `GeneralView.vue`** — add a "Notifications" section: enable checkbox, digest time input (`type="time"`), quiet start/end (`type="time"`), CI/review checkboxes, PR-wait number input, a **Test notification** button (calls `testNotification`, shows the result), and a status line reading the existing agent-up signal if available (else "requires the host agent running"). Load current values from `fetchSettings().notify`; save via `saveNotificationSettings` on change/save.
- [ ] **Step 4: Typecheck** — `npx vue-tsc --noEmit` → clean.
- [ ] **Step 5: Commit**
```bash
git add frontend/src/types.ts frontend/src/api/ frontend/src/views/GeneralView.vue
git commit -m "notify: Settings › Notifications (toggles, times, test button)"
```
- [ ] **Step 6: Runtime check** — rebuild frontend; in Settings › General, the Notifications section saves and **Test notification** pops a desktop toast.

---

## SLICE 3 — Scheduler + urgent-on-sync + quiet hours

### Task 10: NotificationService (digest tick, onSync urgent, quiet hours, coalescing)

**Files:**
- Create: `backend/src/main/java/com/devloom/briefing/NotificationService.java`
- Modify: `backend/src/main/java/com/devloom/integrations/SyncService.java` (call `onSync`)
- Modify: main application class (ensure `@EnableScheduling`; check `grep -rn "@EnableScheduling" backend/src/main/java` and add to the `@SpringBootApplication` class if absent)

**Interfaces:**
- Consumes: `BriefingService` (snapshots, `currentSnapshot`, `lastSnapshot`, `writeSnapshot`), `HostAgentClient.notify`, `AppConfigService`, `NotifyConfig`, `UrgencyRules`.
- Produces: `NotificationService.onSync()` (called by `SyncService`), `@Scheduled` `digestTick()`, private `quiet(...)`, `composeDigest()`, `composeUrgent(list)`.

- [ ] **Step 1: Confirm scheduling is enabled**
Run: `grep -rn "@EnableScheduling\|@SpringBootApplication" backend/src/main/java`
If `@EnableScheduling` is absent, add it to the `@SpringBootApplication` class.

- [ ] **Step 2: Write `NotificationService`**
```java
package com.devloom.briefing;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.devloom.ai.HostAgentClient;
import com.devloom.common.AppConfigService;
import com.devloom.workmodel.WorkItemEntity;
import com.devloom.workmodel.WorkItemRepository;

/** Sends the daily digest (scheduled) and urgent alerts (on sync) via the host agent. */
@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final BriefingService briefing;
    private final WorkItemRepository work;
    private final HostAgentClient agent;
    private final AppConfigService cfg;
    private final ZoneId zone = ZoneId.systemDefault(); // container TZ is set via compose
    private volatile LocalDate lastDigestDate = null;

    public NotificationService(BriefingService briefing, WorkItemRepository work,
                               HostAgentClient agent, AppConfigService cfg) {
        this.briefing = briefing; this.work = work; this.agent = agent; this.cfg = cfg;
    }

    /** Minute tick: fire the digest once, at the configured local time, outside quiet hours. */
    @Scheduled(fixedRate = 60_000)
    public void digestTick() {
        NotifyConfig c = NotifyConfig.from(cfg);
        if (!c.enabled()) return;
        LocalDateTime now = LocalDateTime.now(zone);
        if (!now.toLocalTime().truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
                .equals(parse(c.digestTime()))) return;
        if (now.toLocalDate().equals(lastDigestDate)) return;
        if (quiet(now.toLocalTime(), c)) return;
        lastDigestDate = now.toLocalDate();
        String body = composeDigest(c);
        if (body != null) send("DevLoom · morning briefing", body, "normal");
        briefing.writeSnapshot("digest");
    }

    /** Called after each sync: alert on newly-urgent items (unless quiet hours). */
    public void onSync() {
        NotifyConfig c = NotifyConfig.from(cfg);
        Map<String, UrgencyRules.SnapItem> prev = briefing.lastSnapshot("sync");
        List<WorkItemEntity> items = work.findAll();
        List<WorkItemEntity> newlyUrgent = new ArrayList<>();
        for (WorkItemEntity w : items) {
            String key = UrgencyRules.urgencyKey(w, c.urgentCi(), c.urgentReview(), c.prWaitHours());
            if (key == null) continue;
            UrgencyRules.SnapItem before = prev.get(w.getExtId());
            if (before == null || before.urgencyKey() == null) newlyUrgent.add(w);
        }
        briefing.writeSnapshot("sync");
        if (newlyUrgent.isEmpty() || !c.enabled()) return;
        if (quiet(LocalTime.now(zone), c)) return; // overnight items surface in the morning digest
        send("DevLoom · needs you", composeUrgent(newlyUrgent), "urgent");
    }

    private void send(String title, String body, String urgency) {
        try {
            Map<String, Object> r = agent.notify(title, body, urgency);
            if (!Boolean.TRUE.equals(r.get("ok"))) log.info("notify skipped: {}", r.get("error"));
        } catch (Exception e) { log.info("notify unavailable (agent down?): {}", e.getMessage()); }
    }

    private String composeDigest(NotifyConfig c) {
        long urgent = work.findAll().stream()
                .filter(w -> UrgencyRules.urgencyKey(w, c.urgentCi(), c.urgentReview(), c.prWaitHours()) != null)
                .count();
        long planned = briefing.plannedExtIds().size();
        return "%d need you now · %d in today's plan. Open DevLoom for your briefing.".formatted(urgent, planned);
    }

    private String composeUrgent(List<WorkItemEntity> items) {
        if (items.size() == 1) return items.get(0).getTitle();
        String heads = items.stream().limit(3).map(WorkItemEntity::getTitle).collect(Collectors.joining(" · "));
        return items.size() + " things need you: " + heads + (items.size() > 3 ? " …" : "");
    }

    private boolean quiet(LocalTime now, NotifyConfig c) {
        LocalTime s = parse(c.quietStart()), e = parse(c.quietEnd());
        if (s.equals(e)) return false;
        return s.isBefore(e) ? (!now.isBefore(s) && now.isBefore(e))   // same-day window
                             : (!now.isBefore(s) || now.isBefore(e));   // window crosses midnight
    }
    private static LocalTime parse(String hhmm) {
        try { return LocalTime.parse(hhmm); } catch (Exception ex) { return LocalTime.of(8, 30); }
    }
}
```

- [ ] **Step 3: Hook `SyncService`**
In `syncAll()` and `sync(...)` (or a single common point after items are saved), inject `NotificationService` and call `notificationService.onSync()` after a successful sync. To avoid a circular dependency (NotificationService ← BriefingService ← repositories; SyncService → NotificationService), inject `NotificationService` into `SyncService` via constructor; there is no cycle since NotificationService does not depend on SyncService. After the existing `log.info("Synced ...")`/`syncAll` completion, add `notificationService.onSync();` guarded by try/catch so a notify failure never breaks a sync.

- [ ] **Step 4: Compile backend.** Expected: exit 0.

- [ ] **Step 5: Commit**
```bash
git add backend/src/main/java/com/devloom/briefing/NotificationService.java backend/src/main/java/com/devloom/integrations/SyncService.java backend/src/main/java/com/devloom/DevLoomApplication.java
git commit -m "notify: digest scheduler + urgent-on-sync + quiet hours"
```
(Adjust the application class filename to the real one from Step 1.)

- [ ] **Step 6: Runtime verification**
`docker compose up -d --build backend`.
- Set a digest time one minute ahead and enable: `curl -s -X POST .../settings/notifications -d '{"enabled":true,"digestTime":"<now+1min HH:mm>","quietStart":"00:00","quietEnd":"00:00"}'`; within ~a minute a desktop toast "DevLoom · morning briefing" appears.
- Urgent path: trigger a sync (`curl -s -X POST .../integrations/<source>/sync`) after a new failing build exists; confirm a "needs you" toast fires (outside quiet hours). Re-syncing with no new urgent items fires nothing (idempotent).

---

## Self-Review

**Spec coverage:**
- §5 Briefing/Triage modes → Task 6. §5 handled/plan actions → Tasks 4–6.
- §6 memory tables → Task 1; diff/flags logic → Task 3; snapshot pruning → Task 3.
- §7 urgency rules → Task 2 (+ config-aware in Task 8/10).
- §8 digest schedule, onSync, quiet hours, coalescing → Task 10.
- §9 API (`/today` briefing, handled/plan, settings, test, agent /notify) → Tasks 4, 7, 8, 9.
- §10 frontend (Today toggle, card actions, Settings) → Tasks 6, 9.
- §11 edge cases: agent offline (Task 7/10 send() swallow), first run empty baseline (Task 3 empty map), handled-reopen clear (Task 3 `clearHandledOnReopen`), TZ (Task 10 `zone`), duplicate digest (Task 10 `lastDigestDate`).
- §12 success criteria exercised by the runtime checks in Tasks 6, 9, 10.
- §13 slices = the three sections.

**Placeholder scan:** The only intentional stub is `UrgencyRules.waitHours` (Task 2), flagged with an explicit instruction to wire it to the real signal discovered in Step 1 or document it as inert — not shipped as literal `TODO`.

**Type consistency:** `Dto.Recommendation` gains `handled, planned` (Task 3) and every construction site is updated (Task 4). `Dto.Briefing`/`Dto.Today.briefing` names match frontend `Briefing`/`TodayData.briefing` (Task 5). `BriefingService` method names (`toggleHandled/togglePlan/build/writeSnapshot/lastSnapshot/currentSnapshot/plannedExtIds`) are used consistently in Tasks 4 and 10. `HostAgentClient.notify(title, body, urgency)` matches the agent route and `SettingsController`/`NotificationService` callers.
