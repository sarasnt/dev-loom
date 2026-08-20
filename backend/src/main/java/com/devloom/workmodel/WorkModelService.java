package com.devloom.workmodel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.devloom.api.Dto;
import com.devloom.briefing.BriefingService;
import com.devloom.common.AppConfigService;
import com.devloom.priority.PriorityEngine;
import com.devloom.priority.SignalComponent;

/**
 * Reads the unified WorkItem table and maps to transport DTOs. Also the sole place ranking runs
 * now (design doc "Today = execute the day"): Today used to rank a "next" list itself, but that
 * list is gone and Work's priority sort is the only surviving consumer, so {@link #signalsFor}
 * lives here instead of being duplicated.
 */
@Service
public class WorkModelService {

    private final WorkItemRepository repo;
    private final BriefingService briefing;
    private final PriorityEngine priority;
    private final AppConfigService config;

    public WorkModelService(WorkItemRepository repo, BriefingService briefing, PriorityEngine priority,
                            AppConfigService config) {
        this.repo = repo;
        this.briefing = briefing;
        this.priority = priority;
        this.config = config;
    }

    public List<Dto.WorkRow> allWork() {
        // Snoozed items live in Today's fold, not here — Work was the only /work-shaped consumer
        // that didn't filter them out, so pressing Snooze on a Work row left it sitting in place
        // and looked like the button did nothing (task-4 review, finding 2).
        Set<String> snoozed = config.snoozed();
        List<WorkItemEntity> items = repo.findAllByOrderBySortOrderAsc().stream()
                .filter(w -> !snoozed.contains(w.getExtId()))
                .toList();
        Set<String> newIds = briefing.newExtIds();
        // Computed once for the whole fetch, not per row, same reason as the score map below —
        // Plan/Handled need to render as real toggles ("Plan"/"Unplan") instead of static labels
        // hiding a direction-less backend flip (task-4 review, finding 1).
        Set<String> plannedIds = briefing.plannedExtIds();
        Set<String> handledIds = briefing.handledExtIds();

        // Rank ALL items once per fetch (not per row) so each score reflects this item's place
        // among everything, matching what the Work priority-sort toggle needs to be meaningful.
        Map<String, Double> scoreByExtId = new HashMap<>();
        for (PriorityEngine.Scored<WorkItemEntity> s : priority.rank(items, WorkModelService::signalsFor)) {
            scoreByExtId.put(s.item().getExtId(), s.score());
        }

        return items.stream()
                .map(e -> toRow(e, newIds.contains(e.getExtId()), scoreByExtId.get(e.getExtId()),
                        plannedIds.contains(e.getExtId()), handledIds.contains(e.getExtId())))
                .toList();
    }

    private Dto.WorkRow toRow(WorkItemEntity e, boolean isNew, Double score, boolean planned, boolean handled) {
        List<String> meta = e.getMetaCsv().isBlank() ? List.of() : Arrays.asList(e.getMetaCsv().split(","));
        return new Dto.WorkRow(
                e.getExtId(), e.getType(), glyph(e.getType()), e.getTitle(),
                e.getStatus(), e.getStatusTone(), meta, e.getSource(),
                category(e.getType()), e.getDescription(), e.getParentExtId(),
                // The connector already resolved where this item lives. Work was dropping it and
                // rebuilding a GitHub URL from the id, which meant every other source had no way
                // to be opened at all.
                e.getUrl(), e.getAuthor(), e.getPrRole(),
                isNew, score, e.getStartsAt() == null ? null : e.getStartsAt().toString(),
                planned, handled);
    }

    /**
     * Deterministic priority signals for a real work item (SPEC §22). All normalized to [0,1].
     * Moved from TodayService when its ranked "next" list died — one implementation, not two that
     * could drift, since this is now the only place a score is computed.
     */
    private static List<SignalComponent> signalsFor(WorkItemEntity w) {
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
            // Above your own PR: a review requested of you is blocking someone else, and it is
            // already in the needs-you set by UrgencyRules. Ranking it below the PRs you opened
            // put the two in disagreement about the same item.
            case "review" -> 0.85;
            case "pr" -> 0.8;
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

    private static double clamp(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    /** Presentation glyph derived from domain type (kept out of the DB). */
    private String glyph(String type) {
        return switch (type) {
            case "build" -> "⚡";
            case "task" -> "◆";
            case "review", "calendar" -> "◷";
            case "doc" -> "▤";
            default -> "⎇"; // pr, stale
        };
    }

    /** Human category used for grouping/filtering in the Work view. */
    private String category(String type) {
        return switch (type) {
            case "pr" -> "Pull request";
            case "build" -> "Build";
            case "task" -> "Task";
            case "review", "calendar" -> "Event";
            case "doc" -> "Notes";
            case "stale" -> "Stale";
            default -> "Item";
        };
    }
}
