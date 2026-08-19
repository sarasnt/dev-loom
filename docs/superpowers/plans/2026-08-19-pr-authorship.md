# PR Authorship & Role Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every PR knows who created it and what it wants from you: Work gains Mine / To Review / Others filtering, Today cards show a role chip and "Created by", and the data is computed once at sync time by the connectors.

**Architecture:** Two nullable columns on `work_item` (V24) flow through the entity, both DTOs and all four construction points to the frontend; GitHub persists the `mine`/`myReview` it already computes, Bitbucket DC resolves roles via two id-only calls to the dashboard endpoint's documented `role` parameter with a never-blocks fallback.

**Tech Stack:** Flyway, JPA, Spring `RestClient`, Vue 3 + TypeScript.

**Spec:** `docs/superpowers/specs/2026-08-19-pr-authorship-design.md`

## Recommended executor per task

| Task | Executor | Why |
| --- | --- | --- |
| 1 — model + DTOs + mappers + types | **claude-sonnet** | Positional record churn across four construction points — where silent misalignment lives |
| 2 — the two connectors | **claude-sonnet** | Role semantics + failure isolation |
| 3 — UI + live verification | **claude-sonnet** | Template work plus a live end-to-end check on real GitHub data |

## Global Constraints

- **No local JDK.** Compile exactly with:
  `MSYS_NO_PATHCONV=1 docker run --rm -v "/c/Users/saras/Documents/projects/dev-loom/backend:/w" -v devloom_m2:/root/.m2 -w /w maven:3.9-eclipse-temurin-25 mvn -q -o compile`
  Pass = zero `ERROR] /w` lines. Never trust an `&&`-chained echo.
- Frontend gate: `cd frontend && npx vue-tsc --noEmit` (exit 0). Rebuild to observe:
  `docker compose up -d --build backend frontend`, poll `http://localhost/api/v1/settings` for 200.
- **Migration is V24 and exactly V24** — check `backend/src/main/resources/db/migration/` for the
  current max before creating it.
- Bitbucket is unreachable from this machine (VPN) — its live proof is the checklist addendum;
  GitHub IS live-verifiable here and Task 3 must do it.
- Read/Edit/Write tools only; no BOM; Jackson `com.fasterxml` only; comments explain why.
- **Positional-record discipline:** `Dto.Recommendation` and `Dto.WorkRow` are positional records
  rebuilt in FOUR places (named in Task 1). Every one must be updated in the same commit or the
  compiler catches it — which is the point of appending fields at the END only.
- Execute tasks in order.

---

### Task 1: Columns, DTOs, and every construction point (executor: claude-sonnet)

**Files:**
- Create: `backend/src/main/resources/db/migration/V24__work_item_author_role.sql`
- Modify: `backend/src/main/java/com/devloom/workmodel/WorkItemEntity.java`
- Modify: `backend/src/main/java/com/devloom/api/Dto.java` (WorkRow + Recommendation records)
- Modify: `backend/src/main/java/com/devloom/workmodel/WorkModelService.java` (`toRow`)
- Modify: `backend/src/main/java/com/devloom/api/TodayService.java` (`rec(...)` and `numbered(...)`)
- Modify: `backend/src/main/java/com/devloom/briefing/BriefingService.java` (the Recommendation rebuild, ~line 221)
- Modify: `frontend/src/types.ts` (Recommendation + WorkRow interfaces)

**Interfaces:**
- Produces: `WorkItemEntity.withAuthor(String author, String prRole)`; getters `getAuthor()`/`getPrRole()`.
- Both DTO records gain `String author, String prRole` as the FINAL two components.
- Task 2 calls `withAuthor`; Task 3 reads `author`/`prRole` on both frontend types.

- [ ] **Step 1: The migration** — `V24__work_item_author_role.sql`:

```sql
-- Who created a PR, and what it wants from you ("mine" | "review" | "other"). Computed at sync
-- time by the connector — the only place that knows the authenticated user — never guessed
-- downstream. Null for non-PR items and for connectors that don't resolve it.
ALTER TABLE work_item ADD COLUMN IF NOT EXISTS author VARCHAR(120);
ALTER TABLE work_item ADD COLUMN IF NOT EXISTS pr_role VARCHAR(10);
```

- [ ] **Step 2: Entity.** After the `url` field:

```java
    /** Who created the item (PRs: the author's login or display name); null when unknown. */
    @Column(name = "author", length = 120)
    private String author;

    /** For PRs: what this item wants from you — "mine" | "review" | "other". Null for non-PRs. */
    @Column(name = "pr_role", length = 10)
    private String prRole;
```

Builder next to `withUrl` (match its style):

```java
    public WorkItemEntity withAuthor(String author, String prRole) {
        this.author = author == null || author.isBlank() ? null : author;
        this.prRole = prRole;
        return this;
    }
```

Getters/setters next to the others: `getAuthor/setAuthor/getPrRole/setPrRole`.

- [ ] **Step 3: DTOs.** Append `, String author, String prRole` as the last two components of BOTH
`Dto.WorkRow` and `Dto.Recommendation`.

- [ ] **Step 4: The four construction points** — append the two arguments at each:
  - `WorkModelService.toRow`: `..., e.getUrl(), e.getAuthor(), e.getPrRole());`
  - `TodayService.rec(...)`: `..., briefing.isHandled(w.getExtId()), briefing.isPlanned(w.getExtId()), w.getAuthor(), w.getPrRole());`
  - `TodayService.numbered(...)`: `..., r.actions(), r.url(), r.handled(), r.planned(), r.author(), r.prRole()));`
  - `BriefingService` rebuild (~line 221): `..., r.actions(), r.url(), r.handled(), r.planned(), r.author(), r.prRole()));`

Then grep the whole backend for `new Dto.Recommendation(` and `new Dto.WorkRow(` — every hit must
now pass 18/14-arg forms respectively (the compiler is the net; the grep is the map).

- [ ] **Step 5: Frontend types.** In `types.ts`, `Recommendation` gains (after `planned`):

```ts
  author?: string | null // who created it (PRs) — rendered as "created by …"
  prRole?: 'mine' | 'review' | 'other' | null // what a PR wants from you, resolved at sync time
```

and `WorkRow` gains the same two lines after `url`.

- [ ] **Step 6: Gates.** Backend compile clean; `vue-tsc` exit 0 (the stub returns empty arrays,
so optional fields need no stub change — verify, don't assume).

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "work model: PRs carry their author and their ask (V24)"
```

---

### Task 2: The connectors fill it in (executor: claude-sonnet)

**Files:**
- Modify: `backend/src/main/java/com/devloom/integrations/GitHubConnector.java`
- Modify: `backend/src/main/java/com/devloom/integrations/BitbucketConnector.java` (Server/DC path only)

**Interfaces:** consumes `withAuthor(author, prRole)` from Task 1. Cloud Bitbucket untouched.

- [ ] **Step 1: GitHub.** In the private `map(...)` method (it already computes `mine`, `myReview`,
`isPr`, and reads `item.get("user")`), derive:

```java
        String author = str(asMap(item.get("user")), "login");
        // Role only means something for PRs (review items are PRs too); issues stay null.
        String prRole = (isPr || myReview)
                ? (mine ? "mine" : myReview ? "review" : "other")
                : null;
```

and chain `.withAuthor(author, prRole)` onto the returned `WorkItemEntity` (read the method's
return expression first; keep every existing chained call).

- [ ] **Step 2: Bitbucket DC.** In `fetchServer`, before the PR loop:

```java
            // Who a PR is FOR is resolved here, at sync time: the dashboard endpoint takes a
            // documented role filter, and two id-only calls settle mine-vs-review without
            // guessing from participant lists. Null means the call failed — everything then
            // falls back to "other", because authorship is additive and must never block a sync.
            java.util.Set<String> authored = rolePrKeys(http, "AUTHOR");
            java.util.Set<String> reviewing = rolePrKeys(http, "REVIEWER");
```

Inside the loop (after `repo` and `id` are known):

```java
                String prKey = repo + "#" + id;
                String prRole = authored != null && authored.contains(prKey) ? "mine"
                        : reviewing != null && reviewing.contains(prKey) ? "review" : "other";
                String prAuthor = str(asMap(asMap(pr.get("author")).get("user")), "displayName");
                if (prAuthor.isBlank()) prAuthor = str(asMap(asMap(pr.get("author")).get("user")), "name");
```

Extend `prItem(...)` with two trailing params `String author, String prRole` and chain
`.withAuthor(author, prRole)` in it; update its call site. Add the helper:

```java
    /** PR keys (PROJ/slug#id) for one dashboard role — or null when the call failed. */
    private java.util.Set<String> rolePrKeys(RestClient http, String role) {
        try {
            Map<String, Object> resp = http.get()
                    .uri(uri -> uri.path("/rest/api/1.0/dashboard/pull-requests")
                            .queryParam("state", "OPEN")
                            .queryParam("role", role)
                            .queryParam("limit", 100)
                            .build())
                    .retrieve().body(MAP);
            java.util.Set<String> keys = new java.util.HashSet<>();
            for (Object o : asList(resp == null ? null : resp.get("values"))) {
                Map<String, Object> pr = asMap(o);
                Map<String, Object> repoObj = asMap(asMap(pr.get("toRef")).get("repository"));
                keys.add(str(asMap(repoObj.get("project")), "key") + "/" + str(repoObj, "slug")
                        + "#" + str(pr, "id"));
            }
            return keys;
        } catch (Exception e) {
            log.warn("Bitbucket role filter {} unavailable ({}) — PR roles fall back to \"other\"",
                    role, e.getMessage());
            return null;
        }
    }
```

The build items from `failedBuildItems` stay author-less — a build has no author, and null is
the honest value.

- [ ] **Step 3: Compile** (Global Constraints command) — clean.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "connectors: GitHub persists the roles it already knew; Bitbucket asks the dashboard"
```

---

### Task 3: The UI, and the live proof (executor: claude-sonnet)

**Files:**
- Modify: `frontend/src/views/WorkView.vue`
- Modify: `frontend/src/components/RecommendationCard.vue`
- Modify: `docs/superpowers/plans/2026-08-15-bitbucket-builds-verify.md` (append one section)

- [ ] **Step 1: Work filters.** In `WorkView.vue`:
  - Fix the legacy filter: `case 'mine': return r.prRole === 'mine'` (it string-matched status).
  - Add the role sub-filter (script):

```ts
// PR role sub-filter — only meaningful (and only shown) on the PR/Review lenses.
const roleFilters = ['All', 'Mine', 'To Review', 'Others'] as const
const roleFilter = ref<(typeof roleFilters)[number]>('All')
function matchesRole(r: WorkRow): boolean {
  if (roleFilter.value === 'All') return true
  if (r.type !== 'pr' && r.type !== 'review') return true
  const role = r.prRole ?? 'other'
  return roleFilter.value === 'Mine' ? role === 'mine'
    : roleFilter.value === 'To Review' ? role === 'review'
    : role === 'other'
}
```

  Include `matchesRole(r)` in the `filtered` computed chain, and render the row (template, under
  the existing filter chips, matching their classes) only when `active === 'PRs' || active === 'Reviews'`:

```html
    <div v-if="active === 'PRs' || active === 'Reviews'" class="rolefilters">
      <button v-for="rf in roleFilters" :key="rf" class="chipbtn mono"
              :class="{ on: roleFilter === rf }" @click="roleFilter = rf">{{ rf }}</button>
    </div>
```

  Style `.rolefilters`/`.chipbtn` to match the existing filter chips' look (read them first; reuse
  classes if they fit rather than inventing new ones).

- [ ] **Step 2: Today cards.** In `RecommendationCard.vue`:
  - Under the source chip (top-right block that renders `item.source`), add:

```html
      <span v-if="item.author" class="byline mono">created by {{ item.author }}</span>
```

  with a style like `.byline { display: block; font-size: 10px; color: var(--faint-text); margin-top: 3px; text-align: right; }` — adjust to the actual markup after reading it.
  - In the chips row, after the planned chip:

```html
      <Mono v-if="item.prRole === 'mine'" class="chip plannedchip">yours</Mono>
      <Mono v-else-if="item.prRole === 'review'" class="chip warn">for review</Mono>
```

- [ ] **Step 3: Gates + rebuild.** `vue-tsc` exit 0; rebuild backend+frontend; poll for 200.
(Backend rebuild matters: V24 + connector changes from Tasks 1–2 are still unobserved.)

- [ ] **Step 4: LIVE verification on GitHub data.** Find the GitHub source id
(`GET /api/v1/sources`), trigger `POST /api/v1/sources/{id}/sync`, then:

```bash
curl -s http://localhost/api/v1/work | python3 -c "
import sys,json
rows=[r for r in json.load(sys.stdin) if r['type'] in ('pr','review')]
print([(r['title'][:30], r['author'], r['prRole']) for r in rows])"
```

Expected: every PR row carries `author` + `prRole`; the sarasnt-authored PRs read `mine`.
Then `GET /api/v1/today` — confirm PR recommendations carry the same two fields. Record the
actual output in your report. If any PR row has null author after a fresh sync, that is a FAIL —
report BLOCKED, don't rationalize it.

- [ ] **Step 5: Checklist addendum.** Append to `docs/superpowers/plans/2026-08-15-bitbucket-builds-verify.md`:

```markdown

## PR authorship (added 2026-08-19) — same VPN-side run

8. After re-sync: `curl -s http://localhost/api/v1/work | python3 -c "import sys,json; print([(r['title'][:30], r['author'], r['prRole']) for r in json.load(sys.stdin) if r['type']=='pr'])"`
   → every Bitbucket PR shows its creator's display name; PRs you opened read `mine`, PRs
   awaiting your review read `review`, the rest `other`. If ALL read `other`, the dashboard
   `role` parameter failed on this instance — check backend logs for "role filter … unavailable"
   and report exactly that.
9. Work → PRs: the Mine / To Review / Others chips partition the list; Today: PR cards show
   "created by …" under the source and a `yours`/`for review` chip where it applies.
```

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "work+today: PRs say who made them and what they want from you"
```
