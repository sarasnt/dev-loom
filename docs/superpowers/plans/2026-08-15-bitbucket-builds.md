# Bitbucket DC Builds Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Failed builds on Bitbucket DC pull requests appear as `build` work items (Today/Work) and analyze on the Builds screen from metadata, honestly labeled, with the Jenkins link instead of a fake log.

**Architecture:** `BitbucketConnector` gains one cheap stats call per dashboard PR at sync-time and emits a `build` work item per red head-commit; `BuildFailureService` dispatches by the item's source type; a new `BitbucketBuildAnalyzer` enumerates via the old build-status API, enriches via the per-key endpoint, and summarizes metadata with the local model. No schema change, no frontend change.

**Tech Stack:** Java 25 / Spring Boot 4.1, Spring `RestClient`, existing `LlmRouter`/`PromptLibrary`.

**Spec:** `docs/superpowers/specs/2026-08-15-bitbucket-builds-design.md` (grounded in `docs/superpowers/specs/2026-08-15-bitbucket-dc-probe.md` — every endpoint shape below is a real capture from Bitbucket DC 9.4.22).

## Recommended executor per task

| Task | Executor | Why |
| --- | --- | --- |
| 1 — connector emits build items | **claude-sonnet** | One file, but live-API semantics and failure-isolation judgment |
| 2 — dispatch + analyzer | **claude-sonnet** | New component + a rewrite of a small service; exact code given |
| 3 — docs + VPN checklist | **claude-haiku** | Mechanical doc edits, exact text given |

The orchestrator reviews after every task. If a verification step doesn't produce the stated
output, stop and report — do not improvise.

## Global Constraints

- **No local JDK.** Compile exactly with:
  `MSYS_NO_PATHCONV=1 docker run --rm -v "/c/Users/saras/Documents/projects/dev-loom/backend:/w" -v devloom_m2:/root/.m2 -w /w maven:3.9-eclipse-temurin-25 mvn -q -o compile`
  Pass = zero lines matching `ERROR] /w`. Never trust an `&&`-chained echo.
- **This machine cannot reach the Bitbucket instance (VPN).** Live verification happens from the
  VPN-side session via Task 3's checklist. Local verification = compile + code review + the
  GitHub path staying byte-identical.
- **No Flyway migration** (next is V24 — this plan must not create it). No frontend files.
- **CRLF files; Read/Edit/Write tools only; never a BOM.** Jackson is `com.fasterxml` only.
- **A failed Bitbucket call must never take the PR sync down** — build enrichment is additive.
- Comments explain *why*; match surrounding code. Style reference: `GitHubConnector`'s build-item
  block and `GitHubBuildAnalyzer`.
- Execute tasks in order (Task 2 consumes Task 1's ext-id and meta conventions).

---

### Task 1: Connector emits failed-build work items (executor: claude-sonnet)

**Files:**
- Modify: `backend/src/main/java/com/devloom/integrations/BitbucketConnector.java` (Server/DC path only — Cloud is out of scope per the spec)

**Interfaces:**
- Produces work items with: `extId` = head commit SHA (40 hex), type `build`, tone `fail`,
  `meta = branch + "," + PROJ/slug` (Builds service recovers repo from `meta[1]`), `url` = the
  failed build's Jenkins URL, metadata line `jenkins: <name>`.
- Task 2 relies on exactly these conventions.

- [ ] **Step 1: Add the helper.** After the `prItem(...)` method, insert:

```java
    /**
     * The failed-build work items for one PR head commit — usually none. Two calls by necessity:
     * stats is a fixed five-integer body (the cheapest possible red/green), and only a red pays
     * for the listing call that carries the Jenkins name and url. The probe showed neither lives
     * on the PR object in 9.4.22, so the extra round trip per PR is the price of build coverage.
     */
    private List<WorkItemEntity> failedBuildItems(RestClient http, String sha, String prId,
                                                  String prTitle, String repo, String branch,
                                                  String source, int order) {
        try {
            Map<String, Object> stats = http.get()
                    .uri("/rest/build-status/1.0/commits/stats/" + sha)
                    .retrieve().body(MAP);
            int failed = stats == null ? 0
                    : (stats.get("failed") instanceof Number n ? n.intValue() : 0);
            // An in-progress or absent build is not a failure; only red earns a card.
            if (failed == 0) return List.of();

            Map<String, Object> list = http.get()
                    .uri("/rest/build-status/1.0/commits/" + sha)
                    .retrieve().body(MAP);
            String url = null;
            String jenkinsName = null;
            for (Object o : asList(list == null ? null : list.get("values"))) {
                Map<String, Object> b = asMap(o);
                if ("FAILED".equalsIgnoreCase(str(b, "state"))) {
                    url = str(b, "url");
                    jenkinsName = str(b, "name");
                    break;
                }
            }
            String title = "CI failed · #" + prId + " · " + prTitle;
            // meta = [branch, repo] — the Builds service recovers the repo from meta[1], the
            // same contract the GitHub connector's build items follow.
            return List.of(WorkItemEntity.create(sha, "build", title, "failed", "fail",
                    branch + "," + repo, source, order)
                    .withUrl(url == null || url.isBlank() ? null : url)
                    .withMetadata(jenkinsName == null || jenkinsName.isBlank()
                            ? "" : "jenkins: " + jenkinsName));
        } catch (Exception e) {
            log.debug("Bitbucket build status skipped for {}: {}", sha, e.getMessage());
            return List.of();   // build coverage is additive — never take the PR sync down
        }
    }
```

- [ ] **Step 2: Call it from the Server/DC loop.** In `fetchServer`, declare before the PR loop:

```java
            java.util.Set<String> buildShas = new java.util.HashSet<>();
```

and inside the loop, immediately after the existing `out.add(prItem(id, title, state, repo, source, order++, url));` line:

```java
                // A red build on this PR becomes its own work item, so Bitbucket failures reach
                // Today and the Builds screen the way GitHub ones do. De-duped by head commit:
                // two PRs sharing a head must not produce two build cards.
                String sha = str(asMap(pr.get("fromRef")), "latestCommit");
                String branch = str(asMap(pr.get("fromRef")), "displayId");
                if (!sha.isBlank() && buildShas.add(sha)) {
                    out.addAll(failedBuildItems(http, sha, id, title, repo, branch, source,
                            5 + buildShas.size()));
                }
```

(Verify the exact local variable names against the current loop before pasting — `pr`, `id`,
`title`, `repo`, `source` all exist there today; `url` was added recently for the PR link.)

- [ ] **Step 3: Compile** (Global Constraints command). Expected: clean.

- [ ] **Step 4: Self-review the diff** — confirm: only `fetchServer` + the new helper changed;
the Cloud path untouched; no call can throw out of `failedBuildItems`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/devloom/integrations/BitbucketConnector.java
git commit -m "bitbucket: a red PR head becomes a build work item, one cheap stats call per PR"
```

---

### Task 2: Dispatch by source + the metadata analyzer (executor: claude-sonnet)

**Files:**
- Modify: `backend/src/main/java/com/devloom/api/BuildFailureService.java`
- Create: `backend/src/main/java/com/devloom/integrations/BitbucketBuildAnalyzer.java`

**Interfaces:**
- Consumes Task 1's conventions (SHA ext id, `meta[1]` repo, Jenkins url on the item).
- `BitbucketBuildAnalyzer.analyze(SourceInstanceEntity inst, WorkItemEntity item, String sha, Consumer<String> progress, String model)` → `Dto.BuildFailure` or null.
- The GitHub path must stay byte-identical for GitHub items.

- [ ] **Step 1: Create `BitbucketBuildAnalyzer`** with exactly this shape (imports as needed —
`com.devloom.ai.LlmPort`, `com.devloom.ai.LlmRouter`, `com.devloom.ai.PromptLibrary`,
`com.devloom.api.Dto`, `com.devloom.workmodel.WorkItemEntity`, Spring `RestClient`):

```java
package com.devloom.integrations;

/**
 * Build-failure analysis for a Bitbucket DC build — from metadata, and honest about it.
 *
 * <p>Bitbucket Server exposes a build's state, name, url and (via the per-key endpoint) duration,
 * branch and commit — never logs, and on this instance never testResults (0 of 41 builds sampled;
 * see docs/superpowers/specs/2026-08-15-bitbucket-dc-probe.md). So there is no log tail to redact
 * and summarize: the model reasons from metadata, the log panel carries a pointer to Jenkins, and
 * nothing pretends otherwise. Confidence is capped at "med" — without a log, "high" would be a lie.
 */
@org.springframework.stereotype.Component
public class BitbucketBuildAnalyzer {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(BitbucketBuildAnalyzer.class);
    private static final org.springframework.core.ParameterizedTypeReference<java.util.Map<String, Object>> MAP =
            new org.springframework.core.ParameterizedTypeReference<>() {};

    /** The GitHub analyzer's contract, minus the log it doesn't have. */
    private static final String SYSTEM = """
            You are a senior engineer triaging a CI failure for someone who will fix it without
            seeing the run. You have build METADATA ONLY — there is no log. Write two short
            paragraphs, no headings, no lists.

            Evidence: what the metadata actually shows — the job, branch, commit message,
            duration and ticket. Nothing here may be inferred.

            Hypothesis: given the branch name, commit message and ticket, the most likely area of
            failure and the first thing to check after opening the Jenkins build. Be explicit
            that the log will settle it.

            Never invent an error message, file, symbol or line number — you have not seen the
            log. If the metadata is too thin to say anything useful, say that plainly.""";

    private static final String PROMPT = "devloom/bitbucket-build";

    private final SourceCredentialStore credentials;
    private final com.devloom.ai.LlmRouter llm;
    private final com.devloom.ai.PromptLibrary prompts;

    public BitbucketBuildAnalyzer(SourceCredentialStore credentials,
                                  com.devloom.ai.LlmRouter llm,
                                  com.devloom.ai.PromptLibrary prompts) {
        this.credentials = credentials;
        this.llm = llm;
        this.prompts = prompts;
        prompts.seed(PROMPT, SYSTEM);
    }

    /** Analyze one failed build item. Null when nothing usable — the caller shows its honest state. */
    public com.devloom.api.Dto.BuildFailure analyze(SourceInstanceEntity inst, WorkItemEntity item,
                                                    String sha,
                                                    java.util.function.Consumer<String> progress,
                                                    String model) {
        try {
            String token = credentials.secrets(inst).getOrDefault("pat", "");
            org.springframework.web.client.RestClient http =
                    org.springframework.web.client.RestClient.builder()
                            .baseUrl(inst.getBaseUrl())
                            .defaultHeader("Authorization", "Bearer " + token)
                            .defaultHeader("Accept", "application/json").build();

            progress.accept("Fetching build statuses from Bitbucket…");
            java.util.Map<String, Object> list = http.get()
                    .uri("/rest/build-status/1.0/commits/" + sha)
                    .retrieve().body(MAP);
            java.util.Map<String, Object> build = null;
            for (Object o : asList(list == null ? null : list.get("values"))) {
                java.util.Map<String, Object> b = asMap(o);
                if ("FAILED".equalsIgnoreCase(str(b, "state"))) { build = b; break; }
                if (build == null) build = b;   // fall back to the newest if nothing is red anymore
            }
            if (build == null) return null;
            String key = str(build, "key");
            String jenkinsName = str(build, "name");
            String url = str(build, "url");
            String description = str(build, "description");

            // Enrichment is optional by design: the per-key endpoint adds duration, branch and
            // the commit message (probe §c), but its absence must not sink the analysis.
            progress.accept("Enriching from the per-build endpoint…");
            String repo = metaRepo(item);                      // PROJ/slug, from meta[1]
            String branch = metaBranch(item);
            String buildNumber = "";
            String durationMs = "";
            String commitMessage = "";
            String jiraKey = "";
            try {
                String[] pr = repo.split("/", 2);
                java.util.Map<String, Object> rich = http.get()
                        .uri(uri -> uri.path("/rest/api/latest/projects/" + pr[0]
                                        + "/repos/" + pr[1] + "/commits/" + sha + "/builds")
                                .queryParam("key", key).build())
                        .retrieve().body(MAP);
                if (rich != null) {
                    buildNumber = str(rich, "buildNumber");
                    Object d = rich.get("duration");
                    durationMs = d instanceof Number n && n.longValue() > 0 ? String.valueOf(n.longValue()) : "";
                    String ref = str(rich, "ref");
                    if (!ref.isBlank()) branch = ref.replaceFirst("^refs/heads/", "");
                    java.util.Map<String, Object> commit = asMap(rich.get("commit"));
                    commitMessage = str(commit, "message");
                    Object jk = asMap(commit.get("properties")).get("jira-key");
                    if (jk instanceof java.util.List<?> l && !l.isEmpty()) jiraKey = String.valueOf(l.get(0));
                }
            } catch (Exception e) {
                log.debug("Bitbucket build enrichment skipped for {}: {}", sha, e.getMessage());
            }

            progress.accept("Summarizing from metadata…");
            String user = ("Failed CI build (metadata only — no log available):\n"
                    + "Jenkins job: " + jenkinsName + "\n"
                    + "Repository: " + repo + "\nBranch: " + branch + "\n"
                    + (buildNumber.isBlank() ? "" : "Build number: " + buildNumber + "\n")
                    + (durationMs.isBlank() ? "" : "Duration: " + (Long.parseLong(durationMs) / 1000) + "s\n")
                    + (jiraKey.isBlank() ? "" : "Ticket: " + jiraKey + "\n")
                    + (commitMessage.isBlank() ? "" : "Commit message: " + commitMessage + "\n")
                    + "Status description: " + description);
            String summaryText;
            String summaryModel;
            try {
                com.devloom.ai.LlmPort.LlmResult r = llm.generate(new com.devloom.ai.LlmPort.LlmRequest(
                        "build-failure", prompts.get(PROMPT, SYSTEM), user, model));
                summaryText = r.text() == null || r.text().isBlank()
                        ? "Metadata-only failure — open the Jenkins build for the log." : r.text();
                summaryModel = r.model();
            } catch (Exception e) {
                summaryText = "Metadata-only failure — open the Jenkins build for the log.";
                summaryModel = "deterministic";
            }

            String pr = prNumber(url, item.getTitle());
            String sha8 = sha.length() >= 8 ? sha.substring(0, 8) : sha;
            java.util.List<com.devloom.api.Dto.EvidenceRef> evidence = new java.util.ArrayList<>();
            evidence.add(new com.devloom.api.Dto.EvidenceRef("commit " + sha8, null, "local"));
            if (!jiraKey.isBlank()) evidence.add(new com.devloom.api.Dto.EvidenceRef(jiraKey, null, "local"));
            java.util.List<com.devloom.api.Dto.Hypothesis> causes = java.util.List.of(
                    new com.devloom.api.Dto.Hypothesis(1,
                            "Failed Jenkins build" + (buildNumber.isBlank() ? "" : " #" + buildNumber)
                                    + " on branch " + branch,
                            "med", evidence, false));
            java.util.List<com.devloom.api.Dto.EvidenceRef> related = new java.util.ArrayList<>(evidence);
            if (pr != null) related.add(new com.devloom.api.Dto.EvidenceRef("PR #" + pr, null, "local"));

            return new com.devloom.api.Dto.BuildFailure(
                    sha, repo, branch, pr, buildNumber.isBlank() ? "—" : buildNumber,
                    "failed",
                    new com.devloom.api.Dto.Boundary("local", "On your machine"),
                    summaryText, "med", jenkinsName, "(Bitbucket exposes status only)", "—", false,
                    java.util.List.of(
                            new com.devloom.api.Dto.LogLine(
                                    "Bitbucket carries the build status, not the log — it lives in Jenkins:", "omitted"),
                            new com.devloom.api.Dto.LogLine(url, "normal")),
                    causes, related,
                    java.util.List.of("Open the Jenkins build for the full log: " + url,
                            "The analysis above is from metadata only — the log settles it."),
                    java.util.List.of("Address the failure shown in the Jenkins log."),
                    summaryModel + " · metadata only (no log available)");
        } catch (Exception e) {
            log.warn("Bitbucket build analysis failed for {}: {}", sha, e.getMessage());
            return null;
        }
    }

    /** The PR number, from the Jenkins multibranch url segment (…/job/PR-749/…) or the item title. */
    private static String prNumber(String url, String title) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/PR-(\\d+)/").matcher(url == null ? "" : url);
        if (m.find()) return m.group(1);
        m = java.util.regex.Pattern.compile("#(\\d+)").matcher(title == null ? "" : title);
        return m.find() ? m.group(1) : null;
    }

    private static String metaRepo(WorkItemEntity w) {
        String[] parts = w.getMetaCsv().split(",");
        return parts.length >= 2 ? parts[1].trim() : "";
    }

    private static String metaBranch(WorkItemEntity w) {
        String[] parts = w.getMetaCsv().split(",");
        return parts.length >= 1 ? parts[0].trim() : "";
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> asMap(Object o) {
        return o instanceof java.util.Map ? (java.util.Map<String, Object>) o : java.util.Map.of();
    }

    private static java.util.List<?> asList(Object o) {
        return o instanceof java.util.List ? (java.util.List<?>) o : java.util.List.of();
    }

    private static String str(java.util.Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : String.valueOf(v);
    }
}
```

(House style prefers plain imports over fully-qualified names — normalize the qualified names
above into imports when writing the file.)

- [ ] **Step 2: Dispatch in `BuildFailureService`.** Add fields/ctor params
`BitbucketBuildAnalyzer bbAnalyzer` and
`com.devloom.integrations.SourceInstanceRepository sources`. Replace the body of
`analyze(String id, Consumer<String> progress, String model)` with:

```java
        String runId = resolveRunId(id);
        if (runId == null) {
            return emptyState();
        }
        Optional<WorkItemEntity> item = workItems.findFirstByExtId(runId);
        String repo = item.map(BuildFailureService::metaRepo).orElse(null);
        if (repo == null) {
            return emptyState();
        }
        // Route by the item's source: a Bitbucket failure has no GitHub run to fetch, and vice
        // versa. Anything unrecognized keeps the GitHub path — exactly what it always did.
        boolean bitbucket = item
                .map(WorkItemEntity::getSource)
                .flatMap(sources::findByNameIgnoreCase)
                .map(s -> "bitbucket".equalsIgnoreCase(s.getType()))
                .orElse(false);
        if (bitbucket) {
            var inst = sources.findByNameIgnoreCase(item.get().getSource()).orElse(null);
            Dto.BuildFailure real = inst == null ? null
                    : bbAnalyzer.analyze(inst, item.get(), runId, progress, model);
            return real != null ? real : emptyState();
        }
        if (!ghAnalyzer.enabled()) {
            return emptyState();
        }
        Dto.BuildFailure real = ghAnalyzer.analyze(repo, runId, progress, model);
        return real != null ? real : emptyState();
```

- [ ] **Step 3: Extend `resolveRunId`** — replace its first condition with:

```java
        // Explicit ids: a GitHub Actions run id is numeric; a Bitbucket build item is keyed by
        // its head commit SHA. The two shapes cannot collide.
        if (id != null && (id.matches("\\d{6,}") || id.matches("[0-9a-f]{40}"))) {
            return id;
        }
```

- [ ] **Step 4: Update `emptyState`'s copy** — replace the summary string with:

```java
                "No failing CI runs right now. When a connected repo has a failed GitHub Actions "
                        + "run — or a Bitbucket PR carries a failed build — it appears here with "
                        + "an analysis by the local model.",
```

and the diagnostics line with
`List.of("Connect a GitHub repo with CI or a Bitbucket source, or open a PR that triggers a build.")`.

- [ ] **Step 5: Compile** (Global Constraints command). Expected: clean.

- [ ] **Step 6: Self-review** — confirm the GitHub route is unchanged for GitHub items (same
calls, same order), the class javadoc of `BuildFailureService` no longer claims GitHub-only, and
no `tools.jackson` import crept in.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/devloom/api/BuildFailureService.java backend/src/main/java/com/devloom/integrations/BitbucketBuildAnalyzer.java
git commit -m "builds: dispatch by source; Bitbucket analyzes from metadata and says so"
```

---

### Task 3: Docs + the VPN-side verification checklist (executor: claude-haiku)

**Files:**
- Modify: `README.md` (Known limits), `docs/TEST-ON-ANOTHER-PC.md` (expect table)
- Create: `docs/superpowers/plans/2026-08-15-bitbucket-builds-verify.md`

- [ ] **Step 1: README Known limits** — find the Builds-related line if present; ensure the list
contains exactly one line about Bitbucket builds, reading:

```markdown
- Bitbucket DC builds surface as failed-build cards and analyze **from metadata only** — the log
  lives in Jenkins (linked from the analysis); log fetching is a follow-up scope.
```

- [ ] **Step 2: TEST-ON-ANOTHER-PC expect table** — replace the `Builds screen` row's value with:
`GitHub Actions failures with log analysis; Bitbucket DC failures with metadata analysis + Jenkins link`

- [ ] **Step 3: Create the checklist** `docs/superpowers/plans/2026-08-15-bitbucket-builds-verify.md`:

```markdown
# Verify Bitbucket builds — run from the VPN-side session

Prereqs: images republished by CI after the feature merged (`docker compose pull`), stack up,
the Bitbucket source configured and reachable.

1. `docker compose pull && docker compose up -d` (COMPOSE_FILE is pinned on this machine).
2. Settings → Sources → the Bitbucket source → **Re-sync**. Expect `connected · N items` where
   N now exceeds the PR count when any PR is red.
3. `curl -s http://localhost/api/v1/work | python3 -c "import sys,json;print([r['title'] for r in json.load(sys.stdin) if r['type']=='build'])"`
   → expect `CI failed · #<pr> · <title>` entries for red PRs (none if everything is green —
   check the Bitbucket UI for a red PR first; #749 was red on 2026-08-15).
4. Open **Builds**: the failure renders repo (`PROJ/slug`), branch, PR #, build #; the summary
   is present; `analyzedBy` ends with `metadata only (no log available)`; the log panel shows
   the Jenkins pointer line + URL and **no fabricated log lines**.
5. Click the Jenkins link — it must open the real build.
6. Today/Work: the build card ranks near the top (build weight 0.95) and Open works.
7. Negative path: disconnect VPN, Re-sync → the source chip goes error; Builds still renders
   its honest state. Reconnect, Re-sync → recovers.

Report results (numbers + any deviation) back to Sara / the design session.
```

- [ ] **Step 4: Commit**

```bash
git add README.md docs/TEST-ON-ANOTHER-PC.md docs/superpowers/plans/2026-08-15-bitbucket-builds-verify.md
git commit -m "docs: Bitbucket builds are metadata-analyzed; the proof runs where the VPN is"
```
