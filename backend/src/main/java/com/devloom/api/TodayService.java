package com.devloom.api;

import java.util.List;

import org.springframework.stereotype.Service;

import com.devloom.priority.PriorityEngine;
import com.devloom.priority.SignalComponent;

/**
 * Builds the Today dashboard. Candidate recommendations carry deterministic signals; the
 * {@link PriorityEngine} ranks them (SPEC.md §22). The "why" text is where an LLM plugs in
 * later (Step 3) — for now it's the fixture prose, always flagged as reasoning.
 */
@Service
public class TodayService {

    private final PriorityEngine priority;

    public TodayService(PriorityEngine priority) {
        this.priority = priority;
    }

    /** A candidate before ranking: its display payload + the signals that score it. */
    private record Candidate(Dto.Recommendation base, List<SignalComponent> signals) {}

    public Dto.Today today() {
        List<Candidate> candidates = List.of(
                new Candidate(
                        rec("482", "pr", "Review PR #482", "acme/billing",
                                "A teammate has been blocked 2 days, CI is green, and it’s tagged for Thursday’s release. Clearing it unblocks them and de-risks the cut.",
                                List.of(chip("blocks:1", "warn"), chip("wait:51h", null), chip("release ⚑", "warn"), chip("ci:green", null)),
                                true,
                                List.of(ev("PR #482", "acme/billing"), ev("TICKET-91", "Checkout 500s")),
                                List.of("Open", "Why", "Snooze", "Override")),
                        List.of(
                                new SignalComponent("blocks teammate", "1 dev", 0.92, 0.9),
                                new SignalComponent("review wait", "51h", 0.70, 0.7),
                                new SignalComponent("release impact", "tagged", 0.80, 0.8),
                                new SignalComponent("freshness", "2m ago", 1.0, 1.0))),
                new Candidate(
                        rec("1893", "build", "Fix CI on feature/pricing", "acme/billing",
                                "First failure on this branch — your last commit d4e5f6 is the only change touching the failing test.",
                                List.of(chip("build #1893 ⚑", "fail"), chip("age:22m", null), chip("1 test", null)),
                                true, List.of(), List.of("Analyze", "Why", "Snooze")),
                        List.of(
                                new SignalComponent("build impact", "1st fail", 0.90, 0.8),
                                new SignalComponent("age", "22m", 0.20, 0.3),
                                new SignalComponent("freshness", "22m ago", 1.0, 0.5))),
                new Candidate(
                        rec("455", "stale", "Stale PR #455", "acme/billing",
                                "going cold — no reviewer activity",
                                List.of(chip("idle 4d", "stale")),
                                false, List.of(), List.of("Nudge")),
                        List.of(
                                new SignalComponent("authored-PR staleness", "idle 4d", 0.60, 0.4),
                                new SignalComponent("age", "4d", 0.50, 0.2))));

        List<PriorityEngine.Scored<Candidate>> ranked = priority.rank(candidates, Candidate::signals);

        List<Dto.Recommendation> next = ranked.stream().map(s -> {
            Dto.Recommendation b = s.item().base();
            boolean lead = s.rank() == 1;
            return new Dto.Recommendation(
                    b.id(), s.rank(), b.type(), b.title(), b.source(), b.why(),
                    b.isHypothesis(), b.chips(), lead ? Boolean.TRUE : null,
                    lead ? toSignalDtos(s.item().signals()) : null,
                    b.evidence().isEmpty() ? null : b.evidence(),
                    s.score(), b.actions());
        }).toList();

        return new Dto.Today(
                "sara's workspace", "sara.santos", "Tue 8 Aug · 09:14",
                new Dto.Changed("3 PRs merged · 1 build broke & recovered · 2 reviews now waiting on you", "since Mon 17:30"),
                new Dto.Sync(List.of(
                        new Dto.SyncSource("gh", "GitHub", "healthy"),
                        new Dto.SyncSource("jira", "Jira", "healthy"),
                        new Dto.SyncSource("gcal", "Google", "syncing"),
                        new Dto.SyncSource("mscal", "Microsoft", "healthy")), "updated 2m ago"),
                new Dto.Model("Qwen3-Coder", true),
                new Dto.Boundary("local", "On your machine"),
                next, 37, 5);
    }

    private List<Dto.SignalComponent> toSignalDtos(List<SignalComponent> signals) {
        return signals.stream()
                .map(s -> new Dto.SignalComponent(s.name(), s.display(), s.normalized(), s.weight()))
                .toList();
    }

    private Dto.Recommendation rec(String id, String type, String title, String source, String why,
                                   List<Dto.SignalChip> chips, boolean hyp,
                                   List<Dto.EvidenceRef> evidence, List<String> actions) {
        return new Dto.Recommendation(id, 0, type, title, source, why, hyp, chips, null, null, evidence, null, actions);
    }

    private Dto.SignalChip chip(String label, String tone) {
        return new Dto.SignalChip(label, tone);
    }

    private Dto.EvidenceRef ev(String id, String title) {
        return new Dto.EvidenceRef(id, title, "local");
    }
}
