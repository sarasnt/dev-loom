package com.devloom.api;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.devloom.priority.PriorityEngine;
import com.devloom.priority.SignalComponent;
import com.devloom.workmodel.WorkItemEntity;
import com.devloom.workmodel.WorkItemRepository;

/**
 * Builds the Today dashboard from the <em>real</em> unified work model (SPEC.md §22). Every
 * card is a live work item (a PR, a failed CI run, a Jira issue, a calendar event, a Notion
 * doc); the {@link PriorityEngine} ranks them with deterministic, inspectable signals. No
 * fixtures — when nothing is synced the list is honestly empty. The "why" text is a
 * deterministic rationale derived from the same signals; the LLM only explains build
 * failures / brainstorms elsewhere, it never sets the rank.
 */
@Service
public class TodayService {

    private static final DateTimeFormatter NOW =
            DateTimeFormatter.ofPattern("EEE d MMM · HH:mm").withZone(ZoneId.systemDefault());
    private static final int MAX_CARDS = 6;

    private final PriorityEngine priority;
    private final ChangesService changes;
    private final WorkItemRepository workItems;
    private final ProvidersService providers;
    private final String workspace;
    private final String user;

    public TodayService(PriorityEngine priority, ChangesService changes,
                        WorkItemRepository workItems, ProvidersService providers,
                        @Value("${devloom.workspace:My workspace}") String workspace,
                        @Value("${devloom.user:you}") String user) {
        this.priority = priority;
        this.changes = changes;
        this.workItems = workItems;
        this.providers = providers;
        this.workspace = workspace;
        this.user = user;
    }

    /** A candidate before ranking: the source item + the signals that score it. */
    private record Candidate(WorkItemEntity item, List<SignalComponent> signals) {}

    public Dto.Today today() {
        List<WorkItemEntity> all = workItems.findAllByOrderBySortOrderAsc();

        List<Candidate> candidates = all.stream()
                .map(w -> new Candidate(w, signalsFor(w)))
                .toList();

        List<PriorityEngine.Scored<Candidate>> ranked = priority.rank(candidates, Candidate::signals);

        List<Dto.Recommendation> next = ranked.stream()
                .limit(MAX_CARDS)
                .map(s -> toRecommendation(s))
                .toList();

        Dto.Changed changed = changes.changed();
        if (changed == null) {
            changed = new Dto.Changed(
                    all.isEmpty()
                            ? "Welcome — connect a source in Settings to start tracking your work."
                            : "You're up to date.",
                    "");
        }

        return new Dto.Today(
                workspace, user, NOW.format(Instant.now()),
                changed, syncState(all), model(),
                new Dto.Boundary("local", "On your machine"),
                next, all.size(), 0);
    }

    // ---- signals ---------------------------------------------------------------

    /** Deterministic priority signals for a real work item (SPEC §22). All normalized to [0,1]. */
    private List<SignalComponent> signalsFor(WorkItemEntity w) {
        List<SignalComponent> signals = new ArrayList<>();

        // urgency — how loudly the item's own status is asking for attention
        double urgency = switch (w.getStatusTone()) {
            case "fail" -> 1.0;
            case "warn" -> 0.8;
            case "info" -> 0.5;
            case "stale" -> 0.3;
            case "healthy" -> 0.2;
            default -> 0.4;
        };
        signals.add(new SignalComponent("status", w.getStatus(), urgency, 1.0));

        // type importance — a broken build or a PR waiting on you outranks a doc
        double kind = switch (w.getType()) {
            case "build" -> 0.95;
            case "pr" -> 0.8;
            case "review" -> 0.75;
            case "task" -> 0.6;
            case "calendar" -> 0.55;
            case "doc" -> 0.4;
            default -> 0.5;
        };
        signals.add(new SignalComponent("type", w.getType(), kind, 0.8));

        // freshness — connectors emit newest-first within a source; earlier == fresher
        int idx = Math.max(0, w.getSortOrder());
        double freshness = 1.0 / (1.0 + (idx / 20.0));
        signals.add(new SignalComponent("freshness", "recent", clamp(freshness), 0.4));

        return signals;
    }

    private Dto.Recommendation toRecommendation(PriorityEngine.Scored<Candidate> s) {
        WorkItemEntity w = s.item().item();
        boolean lead = s.rank() == 1;
        String type = w.getType();

        Dto.Recommendation base = new Dto.Recommendation(
                w.getExtId(), s.rank(), type, w.getTitle(), w.getSource(),
                whyFor(w), false, chipsFor(w),
                lead ? Boolean.TRUE : null,
                lead ? toSignalDtos(s.item().signals()) : null,
                evidenceFor(w), s.score(), actionsFor(type));
        return base;
    }

    /** Honest, deterministic rationale — no invented detail, no model reasoning. */
    private String whyFor(WorkItemEntity w) {
        String meta = w.getMetaCsv();
        return switch (w.getType()) {
            case "build" -> "Failing CI run" + (meta.isBlank() ? "" : " on " + firstMeta(w))
                    + " — open it to analyze the failing job and step on your machine.";
            case "pr" -> "Open pull request that involves you — review or move it forward.";
            case "review" -> "A review is waiting on you.";
            case "task" -> "Open " + w.getSource() + " item assigned to or involving you.";
            case "calendar" -> "Upcoming event — on your calendar.";
            case "doc" -> "Connected " + w.getSource() + " doc in your context.";
            default -> w.getStatus();
        };
    }

    private List<Dto.SignalChip> chipsFor(WorkItemEntity w) {
        List<Dto.SignalChip> chips = new ArrayList<>();
        String tone = switch (w.getStatusTone()) {
            case "fail" -> "fail";
            case "warn", "info" -> "warn";
            case "stale" -> "stale";
            default -> "neutral";
        };
        chips.add(new Dto.SignalChip(w.getStatus(), tone));
        String first = firstMeta(w);
        if (!first.isBlank()) {
            chips.add(new Dto.SignalChip(first, "neutral"));
        }
        return chips;
    }

    private List<Dto.EvidenceRef> evidenceFor(WorkItemEntity w) {
        List<Dto.EvidenceRef> ev = new ArrayList<>();
        String first = firstMeta(w);
        if (!first.isBlank()) {
            ev.add(new Dto.EvidenceRef(first, w.getTitle(), "local"));
        }
        return ev.isEmpty() ? null : ev;
    }

    private List<String> actionsFor(String type) {
        return switch (type) {
            case "build" -> List.of("Analyze", "Why", "Snooze");
            case "pr", "review" -> List.of("Open", "Why", "Snooze");
            case "task" -> List.of("Open", "Why");
            default -> List.of("Open");
        };
    }

    // ---- header state ----------------------------------------------------------

    /** Sync chips derived from what actually synced — one per source with a live count. */
    private Dto.Sync syncState(List<WorkItemEntity> all) {
        Map<String, Long> bySource = new LinkedHashMap<>();
        for (WorkItemEntity w : all) {
            bySource.merge(w.getSource(), 1L, Long::sum);
        }
        List<Dto.SyncSource> sources = bySource.entrySet().stream()
                .map(e -> new Dto.SyncSource(sourceKey(e.getKey()), e.getKey(),
                        e.getValue() > 0 ? "healthy" : "idle"))
                .toList();
        return new Dto.Sync(sources, all.isEmpty() ? "no sources connected" : "synced");
    }

    private Dto.Model model() {
        try {
            Dto.Providers p = providers.providers();
            if (p.local() != null && p.local().loaded()) {
                return new Dto.Model(p.local().defaultModel(), true);
            }
        } catch (Exception ignore) {
            // fall through
        }
        return new Dto.Model("local model", true);
    }

    private static String sourceKey(String source) {
        return switch (source) {
            case "GitHub" -> "gh";
            case "Jira" -> "jira";
            case "Calendar" -> "gcal";
            case "Notion" -> "notion";
            default -> source.toLowerCase();
        };
    }

    private static String firstMeta(WorkItemEntity w) {
        String meta = w.getMetaCsv();
        if (meta == null || meta.isBlank()) return "";
        String[] parts = meta.split(",");
        // build meta is "branch,repo" → show the repo; else the first token
        if ("build".equals(w.getType()) && parts.length >= 2) return parts[1].trim();
        return parts[0].trim();
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    private List<Dto.SignalComponent> toSignalDtos(List<SignalComponent> signals) {
        return signals.stream()
                .map(s -> new Dto.SignalComponent(s.name(), s.display(), s.normalized(), s.weight()))
                .toList();
    }
}
