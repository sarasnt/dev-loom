package com.devloom.api;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import com.devloom.handoff.HandoffEntity;
import com.devloom.handoff.HandoffRepository;

/**
 * Generates a coding-agent handoff from a build failure (SPEC.md §24). Deterministic
 * assembly — repo/branch/commit, the suspected cause, the log around the failure, ranked
 * hypotheses, and the safety constraints (verify-with-tests; no push/merge/deploy/delete).
 * The artifact is built from the (already redacted) build data, not hand-written.
 *
 * <p>It carries the analysis and the failing log region, not just pointers to them. Whoever picks
 * this up — another agent, or you tomorrow — otherwise has to go and fetch the run to learn
 * anything, and an agent pasted this in a fresh session simply can't. "Failure in job test at step
 * Run tests" is a location, not a cause.
 *
 * <p>Written as real Markdown rather than lines that merely look aligned: it is rendered in the
 * Handoff screen and pasted into tools that render it, and consecutive lines collapse into one
 * paragraph unless the structure is genuine.
 */
@Service
public class HandoffService {

    /** Enough of the log to see the failure, without pasting an entire CI run into the prompt. */
    private static final int MAX_LOG_LINES = 40;

    /** History is a shortlist, not an archive — you reach for this morning's, not March's. */
    private static final int HISTORY = 40;

    private final HandoffRepository store;

    public HandoffService(HandoffRepository store) {
        this.store = store;
    }

    /** Keep an assembled artifact, exactly as it reads now. */
    public Dto.Handoff save(Dto.Handoff h, String buildId) {
        HandoffEntity saved = store.save(HandoffEntity.of(
                buildId, h.title(), h.repo(), h.branch(), h.target(), h.rendered()));
        return withStored(h, saved);
    }

    public List<Dto.HandoffSummary> history() {
        return store.findAllByOrderByCreatedAtDesc(Limit.of(HISTORY)).stream()
                .map(e -> new Dto.HandoffSummary(String.valueOf(e.getId()), e.getTitle(),
                        e.getRepo(), e.getBranch(), e.getBuildId(), e.getCreatedAt().toString()))
                .toList();
    }

    /** A saved artifact by id, or empty if it isn't one of ours. */
    public java.util.Optional<Dto.Handoff> saved(String id) {
        long key;
        try { key = Long.parseLong(id); } catch (NumberFormatException e) { return java.util.Optional.empty(); }
        return store.findById(key).map(e -> new Dto.Handoff(
                String.valueOf(e.getId()), e.getTitle(), e.getTarget(), 1,
                new Dto.Boundary("local", "On your machine"), e.getRendered(),
                defaultSafety(), List.of(), e.getRepo(), e.getBranch(), e.getCreatedAt().toString()));
    }

    public void delete(String id) {
        try { store.deleteById(Long.parseLong(id)); } catch (NumberFormatException ignore) { /* not ours */ }
    }

    private static Dto.Handoff withStored(Dto.Handoff h, HandoffEntity e) {
        return new Dto.Handoff(String.valueOf(e.getId()), h.title(), h.target(), h.version(),
                h.boundary(), h.rendered(), h.safety(), h.sources(), h.repo(), h.branch(),
                e.getCreatedAt().toString());
    }

    private static Dto.Safety defaultSafety() {
        return new Dto.Safety(
                List.of("Verify the fix with tests before claiming done",
                        "Allowed: read repo, run the failing test, edit source/tests"),
                List.of("Do NOT push / merge / deploy / delete without approval",
                        "Forbidden: network calls, deploys, destructive git"));
    }

    public Dto.Handoff fromBuild(Dto.BuildFailure b) {
        String hypotheses = b.causes().stream()
                .map(h -> "%d. (%s) %s%s".formatted(
                        h.rank(), h.confidence(), h.text(),
                        h.uncited() ? " — no supporting evidence" : ""))
                .collect(Collectors.joining("\n"));

        String evidence = b.causes().stream()
                .flatMap(h -> h.evidence().stream())
                .map(Dto.EvidenceRef::id)
                .distinct()
                .map(id -> "- " + id)
                .collect(Collectors.joining("\n"));

        StringBuilder md = new StringBuilder();
        md.append("# Agent Handoff — Fix failing CI on ").append(b.branch()).append("\n\n");

        // A list, not four lines pretending to be a table: this survives being rendered.
        md.append("- **Repo:** ").append(b.repo()).append('\n');
        md.append("- **Branch:** `").append(b.branch()).append('`');
        if (b.pr() != null && !b.pr().isBlank()) md.append("  ·  **PR** #").append(b.pr());
        md.append('\n');
        md.append("- **Run:** ").append(b.run());
        if (b.failedAgo() != null && !b.failedAgo().isBlank()) md.append(" (").append(b.failedAgo()).append(')');
        md.append('\n');
        md.append("- **Failing:** `").append(b.failingJob()).append("` → ").append(b.failingTest()).append("\n\n");

        if (b.summary() != null && !b.summary().isBlank()) {
            md.append("## Suspected cause\n\n").append(b.summary().strip()).append("\n\n");
            String by = b.analyzedBy() == null || b.analyzedBy().isBlank() ? "" : " by " + b.analyzedBy();
            String conf = b.summaryConfidence() == null || b.summaryConfidence().isBlank()
                    ? "" : "confidence " + b.summaryConfidence() + ", ";
            md.append("*(").append(conf).append("analysed").append(by)
              .append(" — treat as a lead, not a finding: confirm it against the log below.)*\n\n");
        }

        String logBlock = logExcerpt(b.log());
        if (!logBlock.isBlank()) {
            md.append("## Point of failure — log\n\n");
            // "Redacted" on its own read like the log had been censored. It hasn't: only secrets
            // are masked, and the point of this section is that the log IS the useful part.
            if (b.redacted()) {
                md.append("*(Secrets — tokens, keys — are masked. Otherwise this is the log as CI produced it.)*\n\n");
            }
            md.append("```\n").append(logBlock).append("\n```\n\n");
        }

        md.append("## Reproduce\n\n1. ").append(b.failingStep()).append("\n\n");

        md.append("## Evidence\n\n").append(evidence.isBlank() ? "- (see linked items)" : evidence).append("\n\n");

        if (!hypotheses.isBlank()) {
            md.append("## Ranked hypotheses\n\n").append(hypotheses).append("\n\n");
        }
        if (b.fixes() != null && !b.fixes().isEmpty()) {
            md.append("## Suggested fixes\n\n");
            for (String f : b.fixes()) md.append("- ").append(f).append('\n');
            md.append('\n');
        }

        md.append("""
                ## Constraints & acceptance

                - Fix must make the failing test pass without weakening assertions.
                - Expected output: a diff + a passing test run.""");

        Dto.Safety safety = new Dto.Safety(
                List.of("Verify the fix with tests before claiming done",
                        "Allowed: read repo, run the failing test, edit source/tests"),
                List.of("Do NOT push / merge / deploy / delete without approval",
                        "Forbidden: network calls, deploys, destructive git"));
        List<Dto.EvidenceRef> sources = b.related();

        return new Dto.Handoff(
                "handoff-" + b.id(), "Fix CI on " + b.branch(), "Claude Code", 1,
                b.boundary(), md.toString(), safety, sources, b.repo(), b.branch());
    }

    /**
     * The tail of the captured log — the failure and what led to it.
     *
     * <p>Taken from the end rather than the start: the analyser already narrows the capture to the
     * first-failure region, and within that the assertion, stack and summary live at the bottom
     * while the top is setup noise. Truncation is announced, so nothing reads as the whole log.
     */
    private static String logExcerpt(List<Dto.LogLine> log) {
        if (log == null || log.isEmpty()) return "";
        List<Dto.LogLine> tail = log.size() <= MAX_LOG_LINES
                ? log : log.subList(log.size() - MAX_LOG_LINES, log.size());
        StringBuilder sb = new StringBuilder();
        if (tail.size() < log.size()) {
            sb.append("[… ").append(log.size() - tail.size()).append(" earlier lines omitted …]\n");
        }
        for (Dto.LogLine l : tail) {
            if (l.text() != null) sb.append(l.text()).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
