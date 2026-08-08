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
            List<String> actions) {}
    public record Today(
            String workspace, String user, String now, Changed changed, Sync sync,
            Model model, Boundary boundary, List<Recommendation> next,
            int everythingCount, int snoozedCount) {}

    // ---- Work ----
    public record WorkRow(
            String id, String type, String glyph, String title, String status,
            String statusTone, List<String> meta, String source) {}

    // ---- Build failure ----
    public record LogLine(String text, String kind) {}
    public record Hypothesis(int rank, String text, String confidence, List<EvidenceRef> evidence, boolean uncited) {}
    public record BuildFailure(
            String id, String repo, String branch, String pr, String run, String failedAgo,
            Boundary boundary, String summary, String summaryConfidence, String failingJob,
            String failingStep, String failingTest, boolean redacted, List<LogLine> log,
            List<Hypothesis> causes, List<EvidenceRef> related, List<String> diagnostics,
            List<String> fixes) {}

    // ---- Handoff ----
    public record Safety(List<String> allow, List<String> forbid) {}
    public record Handoff(
            String id, String title, String target, int version, Boundary boundary,
            String rendered, Safety safety, List<EvidenceRef> sources) {}

    // ---- Integrations ----
    public record Integration(
            String key, String name, String state, String detail,
            List<String> scopes, String note, List<String> actions) {}

    // ---- Providers ----
    public record LocalProvider(String name, String defaultModel, List<String> models, boolean loaded) {}
    public record KeyProvider(
            String name, String boundaryLabel, boolean hasKey, Boolean valid,
            Integer capCents, Integer usedCents, String note) {}
    public record Providers(LocalProvider local, KeyProvider anthropic, KeyProvider openai, boolean fallbackOn) {}

    // ---- Privacy ----
    public record EgressEntry(String time, String action, String to, String tokens) {}
    public record Privacy(String defaultBoundary, List<String> localOnlyRepos, List<EgressEntry> egress) {}

    // ---- Brainstorm ----
    public record BrainstormMessage(String role, String text, String model, Boolean hypothesis, List<EvidenceRef> sources) {}
    public record SessionRef(String id, String title) {}
    public record BrainstormSession(
            String id, String title, String visibility, String model, Boundary boundary,
            List<EvidenceRef> inContext, List<BrainstormMessage> messages) {}
    public record Brainstorm(List<SessionRef> sessions, BrainstormSession active) {}

    // ---- Onboarding ----
    public record OnboardStep(String n, String title, String detail, String state, String action) {}

    // ---- Audit ----
    public record AuditEntry(String action, String target, String metadata, String at) {}

    // ---- Brainstorm send ----
    public record BrainstormSend(String message, List<String> sourceIds) {}
}
