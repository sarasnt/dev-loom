package com.devloom.api;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

/**
 * Generates a coding-agent handoff from a build failure (SPEC.md §24). Deterministic
 * assembly — repo/branch/commit, failing step, evidence, ranked hypotheses, and the
 * safety constraints (verify-with-tests; no push/merge/deploy/delete). The artifact is
 * built from the (already redacted) build data, not hand-written.
 */
@Service
public class HandoffService {

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

        String prSegment = (b.pr() == null || b.pr().isBlank()) ? "" : "  PR #" + b.pr();

        String rendered = """
                # Agent Handoff — Fix failing CI on %s
                Repo: %s @ %s%s  Run %s
                Failing: `%s` → %s

                ## Reproduce
                1. %s

                ## Evidence
                %s

                ## Ranked hypotheses
                %s

                ## Constraints & acceptance
                - Fix must make the failing test pass without weakening assertions.
                - Expected output: a diff + a passing test run.""".formatted(
                b.branch(), b.repo(), b.branch(), prSegment, b.run(),
                b.failingJob(), b.failingTest(),
                b.failingStep(),
                evidence.isBlank() ? "- (see linked items)" : evidence,
                hypotheses);

        Dto.Safety safety = new Dto.Safety(
                List.of("Verify the fix with tests before claiming done",
                        "Allowed: read repo, run the failing test, edit source/tests"),
                List.of("Do NOT push / merge / deploy / delete without approval",
                        "Forbidden: network calls, deploys, destructive git"));

        List<Dto.EvidenceRef> sources = b.related();

        return new Dto.Handoff(
                "handoff-" + b.id(), "Fix CI on " + b.branch(), "Claude Code", 1,
                b.boundary(), rendered, safety, sources);
    }
}
