package com.devloom.api;

import java.util.List;

/**
 * API DTOs — nested records whose JSON shape matches the frontend contract in
 * {@code frontend/src/types.ts} and SPEC.md §34. One file, so the contract is readable
 * in one place.
 */
public final class Dto {
    private Dto() {}

    // ---- shared ----
    public record Boundary(String mode, String label) {}
    public record SyncSource(String key, String label, String state) {}
    public record Sync(List<SyncSource> sources, String updated) {}
    public record Model(String name, boolean local) {}
    public record EvidenceRef(String id, String title, String boundary) {}
    public record SignalChip(String label, String tone) {}
    public record SignalComponent(String name, String value, double normalized, double weight) {}

    // ---- Today ----
    public record Changed(String text, String since) {}
    public record Recommendation(
            String id, int rank, String type, String title, String source, String why,
            boolean isHypothesis, List<SignalChip> chips, Boolean lead,
            List<SignalComponent> signals, List<EvidenceRef> evidence, Double score,
            List<String> actions, String url, boolean handled, boolean planned) {}
    // Today "Briefing" mode (spec §5): since-yesterday diff + the urgent set + today's plan.
    public record Briefing(
            List<Recommendation> newItems, List<Recommendation> resolved,
            List<Recommendation> waiting, List<Recommendation> needsYou,
            List<Recommendation> plan) {}
    public record Today(
            String workspace, String user, String now, Changed changed, Sync sync,
            Model model, Boundary boundary, List<Recommendation> next,
            int everythingCount, int snoozedCount, Briefing briefing) {}

    // ---- Work ----
    public record WorkRow(
            String id, String type, String glyph, String title, String status,
            String statusTone, List<String> meta, String source,
            String category, String description, String parentId) {}

    // ---- Build failure ----
    public record LogLine(String text, String kind) {}
    public record Hypothesis(int rank, String text, String confidence, List<EvidenceRef> evidence, boolean uncited) {}
    public record BuildFailure(
            String id, String repo, String branch, String pr, String run, String failedAgo,
            Boundary boundary, String summary, String summaryConfidence, String failingJob,
            String failingStep, String failingTest, boolean redacted, List<LogLine> log,
            List<Hypothesis> causes, List<EvidenceRef> related, List<String> diagnostics,
            List<String> fixes, String analyzedBy) {}

    // ---- Handoff ----
    public record Safety(List<String> allow, List<String> forbid) {}
    public record Handoff(
            String id, String title, String target, int version, Boundary boundary,
            String rendered, Safety safety, List<EvidenceRef> sources, String repo, String branch) {}

    // ---- Integrations ----
    public record Integration(
            String key, String name, String state, String detail,
            List<String> scopes, String note, List<String> actions) {}

    // ---- Sources (multi-source configuration) ----
    public record SourceView(
            String id, String type, String typeLabel, String deployment, String name,
            String baseUrl, boolean enabled, String state, String detail, long items,
            String note, List<String> actions) {}
    // Create/update payload: name + a flat field map (the connector's descriptor says which
    // fields are secret vs config); enabled used by update only.
    public record SourceUpsert(String type, String deployment, String name,
                               Boolean enabled, java.util.Map<String, String> fields) {}

    // ---- Providers ----
    public record LocalProvider(String name, String defaultModel, String active,
                                List<String> models, boolean loaded) {}
    public record KeyProvider(
            String name, String key, String boundaryLabel, boolean hasKey, Boolean valid,
            Integer capCents, Integer usedCents, String note, List<String> models, String maskedKey) {}
    public record Providers(LocalProvider local, KeyProvider anthropic, KeyProvider openai,
                            boolean fallbackOn, boolean canStoreKeys, List<String> agentModels) {}
    public record SetKey(String provider, String key) {}

    // ---- Privacy ----
    public record EgressEntry(String time, String action, String to, String tokens) {}
    public record Privacy(String defaultBoundary, List<String> localOnlyRepos, List<EgressEntry> egress) {}

    // ---- Brainstorm ----
    public record BrainstormMessage(String role, String text, String model, Boolean hypothesis, List<EvidenceRef> sources) {}
    public record SessionRef(String id, String title, boolean cliMode) {}
    public record ContextItem(String id, String kind, String ref, String label, boolean pinned) {}
    public record RepoSession(String id, String title, String repoPath) {}
    public record ContextAdd(String kind, String ref, String label) {}
    public record BrainstormSession(
            String id, String title, String visibility, String model, Boundary boundary,
            List<ContextItem> inContext, List<BrainstormMessage> messages, String repoPath,
            boolean cliMode, String claudeSessionId, boolean localOnly) {}
    public record Brainstorm(List<SessionRef> sessions, BrainstormSession active) {}

    // ---- Onboarding ----
    public record OnboardStep(String n, String title, String detail, String state, String action) {}

    // ---- Repositories (via host agent) ----
    public record RepoView(
            String id, String path, String name, String host, String slug, String branch,
            String remote, boolean dirty, int ahead, int behind, String userName, String userEmail,
            boolean live, boolean localOnly,
            int staged, int unstaged, int untracked, boolean hasUpstream, String upstream,
            String operation, String commonDir, boolean isLinkedWorktree) {}
    // A git worktree of a repo (repos spec §9). `tracked`/`repoId` = whether DevLoom already
    // tracks this worktree dir as its own repo.
    public record WorktreeInfo(
            String path, String branch, String head, boolean bare, boolean detached,
            boolean locked, boolean tracked, String repoId) {}
    public record RepoLocalOnly(boolean value) {}
    public record RepoAdd(String path, String root) {}
    public record RepoIdentity(String name, String email) {}
    public record FsBrowse(String path) {}
    public record RepoFiles(List<String> files) {}
    public record RepoCommit(String message) {}
    public record RepoPush(boolean force) {}
    public record RepoCheckout(String branch, boolean create) {}
    // Source-branch comparison for the current branch (spec repos §6/§7.3): which branch this
    // work forks from, and how far HEAD has drifted from it. `origin` = "default" | "pr" |
    // "override" | "unknown" tells the UI how `source` was resolved.
    public record SourceStatus(
            String source, String defaultBranch, String origin, boolean hasSource,
            boolean missing, int sourceAhead, int sourceBehind) {}
    public record RepoSourceSet(String branch, String source) {}
    // Read-only conflict prediction for merging the source branch into HEAD (spec repos §7.4).
    // state = "clean" | "conflict" | "unknown" | "unable" | "stale". lastFetch is an ISO instant
    // (null = never); stale flags that refs are older than the freshness window.
    public record ConflictStatus(
            String state, List<String> files, String ref, String reason,
            String lastFetch, boolean stale) {}
    public record RepoFetch(boolean ok, String error, String at) {}

    // ---- Fleet (agent runs) ----
    public record AgentRun(
            String id, String title, String repoPath, String runDir, String branch, String kind,
            String permission, boolean allowTests, boolean isolated, String model, String status,
            String resultSummary, String error, String createdAt, String startedAt, String finishedAt,
            String claudeSessionId, String brainstormSessionId) {}
    public record RunLaunch(String repoId, String prompt, String model, String permission,
                            boolean allowTests, boolean isolate) {}

    // ---- Audit ----
    public record AuditEntry(String action, String target, String metadata, String at) {}

    // ---- Brainstorm send ----
    public record Turn(String role, String text) {}
    public record BrainstormSend(String sessionId, String message, List<String> sourceIds,
                                 List<Turn> history, String model) {}
    public record NewSession(String title, String repoPath, String model) {}
}
