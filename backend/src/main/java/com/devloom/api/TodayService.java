package com.devloom.api;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.devloom.workmodel.WorkItemEntity;
import com.devloom.workmodel.WorkItemRepository;

/**
 * Builds the Today dashboard from the <em>real</em> unified work model (SPEC.md §22, design doc
 * "Today = execute the day"). Every card is a live work item (a PR, a failed CI run, a Jira
 * issue, a calendar event, a Notion doc). No fixtures — when nothing is synced the sections are
 * honestly empty. The "why" text is a deterministic rationale; the LLM only explains build
 * failures / brainstorms elsewhere, it never ranks anything here. Ranking itself (PriorityEngine)
 * moved to WorkModelService once the old ranked "next" list died — Work's score is the only
 * remaining consumer.
 */
@Service
public class TodayService {

    private static final DateTimeFormatter NOW =
            DateTimeFormatter.ofPattern("EEE d MMM · HH:mm").withZone(ZoneId.systemDefault());
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final ChangesService changes;
    private final WorkItemRepository workItems;
    private final ProvidersService providers;
    private final com.devloom.common.AppConfigService appConfig;
    private final com.devloom.briefing.BriefingService briefing;
    private final String workspace;
    private final String user;

    public TodayService(ChangesService changes,
                        WorkItemRepository workItems, ProvidersService providers,
                        com.devloom.common.AppConfigService appConfig,
                        com.devloom.briefing.BriefingService briefing,
                        @Value("${devloom.workspace:My workspace}") String workspace,
                        @Value("${devloom.user:you}") String user) {
        this.changes = changes;
        this.workItems = workItems;
        this.providers = providers;
        this.appConfig = appConfig;
        this.briefing = briefing;
        this.workspace = workspace;
        this.user = user;
    }

    /** Hide a work item from Today. */
    public void snooze(String extId) {
        appConfig.snooze(extId);
    }

    /** Bring a snoozed item back into Today. */
    public void unsnooze(String extId) {
        appConfig.unsnooze(extId);
    }

    public Dto.Today today() {
        List<WorkItemEntity> everything = workItems.findAllByOrderBySortOrderAsc();
        Set<String> snoozed = appConfig.snoozed();
        List<WorkItemEntity> all = everything.stream()
                .filter(w -> !snoozed.contains(w.getExtId()))
                .toList();

        // Sections, deduped: an item renders once, highest section wins. "Schedule" is only
        // calendar items happening today (or ongoing); everything else falls through.
        LocalDate today = LocalDate.now(ZONE);
        Set<String> placed = new HashSet<>();

        // schedule: type calendar AND (startsAt is today OR status says it is running now)
        List<WorkItemEntity> scheduleItems = all.stream()
                .filter(w -> "calendar".equals(w.getType()) && isTodayOrNow(w, today))
                .sorted(Comparator.comparing(WorkItemEntity::getStartsAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        placed.addAll(scheduleItems.stream().map(WorkItemEntity::getExtId).toList());

        // needsYou: briefing.needsYouExtIds(), minus placed
        Set<String> needsYouIds = briefing.needsYouExtIds();
        List<WorkItemEntity> needsYouItems = all.stream()
                .filter(w -> needsYouIds.contains(w.getExtId()) && !placed.contains(w.getExtId()))
                .toList();
        placed.addAll(needsYouItems.stream().map(WorkItemEntity::getExtId).toList());

        // planned: briefing.plannedExtIds(), minus placed. plannedExtIds() is the raw flag set
        // (the notification digest also reads it) and, unlike needsYouExtIds(), does not already
        // exclude handled items — the dedup contract wants handled out of every section but
        // schedule, so that exclusion happens here.
        Set<String> plannedIds = briefing.plannedExtIds();
        Set<String> handled = briefing.handledExtIds();
        List<WorkItemEntity> plannedItems = all.stream()
                .filter(w -> plannedIds.contains(w.getExtId()) && !placed.contains(w.getExtId())
                        && !handled.contains(w.getExtId()))
                .toList();
        placed.addAll(plannedItems.stream().map(WorkItemEntity::getExtId).toList());

        // assigned: prRole in ("mine","review") OR type in ("task","review"), minus placed
        List<WorkItemEntity> assignedItems = all.stream()
                .filter(w -> !placed.contains(w.getExtId()) && !handled.contains(w.getExtId())
                        && ("mine".equals(w.getPrRole()) || "review".equals(w.getPrRole())
                                || "task".equals(w.getType()) || "review".equals(w.getType())))
                .toList();

        Dto.Changed changed = changes.changed();
        if (changed == null) {
            changed = new Dto.Changed(
                    all.isEmpty()
                            ? "Welcome — connect a source in Settings to start tracking your work."
                            : "You're up to date.",
                    "");
        }

        // The snoozed items themselves, not just their count — a "Snoozed (3)" label that can't
        // show or undo what it counts is a button that does nothing, which is against house rules.
        List<Dto.Recommendation> snoozedRecs = everything.stream()
                .filter(w -> snoozed.contains(w.getExtId()))
                .map(this::recFor)
                .toList();
        return new Dto.Today(
                workspace, user, NOW.format(Instant.now()),
                changed, syncState(all), model(),
                new Dto.Boundary("local", "On your machine"),
                numbered(scheduleItems), numbered(needsYouItems),
                numbered(plannedItems), numbered(assignedItems),
                all.size(), snoozedRecs.size(), snoozedRecs);
    }

    /** True for a calendar item happening today (startsAt on today's local date) or right now
     *  (status begins "now" — set by CalendarIcsConnector for an in-progress event). */
    private static boolean isTodayOrNow(WorkItemEntity w, LocalDate today) {
        boolean startsToday = w.getStartsAt() != null
                && w.getStartsAt().atZone(ZONE).toLocalDate().equals(today);
        boolean runningNow = w.getStatus() != null && w.getStatus().toLowerCase().startsWith("now");
        return startsToday || runningNow;
    }

    /** Section items → Recommendations, numbered 1..n within the section (the warp-spine number
     *  each card is drawn against; every section is numbered on its own, not against the whole
     *  payload, or every card below the first in a section would read "1"). */
    private List<Dto.Recommendation> numbered(List<WorkItemEntity> items) {
        List<Dto.Recommendation> out = new ArrayList<>(items.size());
        int i = 1;
        for (WorkItemEntity w : items) {
            out.add(rec(w, i++, null, null, null));
        }
        return out;
    }

    // ---- recommendation construction --------------------------------------------

    /** A non-ranked recommendation (rank 0, no lead/signals/score) — snoozed list, etc. */
    private Dto.Recommendation recFor(WorkItemEntity w) {
        return rec(w, 0, null, null, null);
    }

    /** Single construction point for a Recommendation, shared by every section and the snoozed list. */
    private Dto.Recommendation rec(WorkItemEntity w, int rank, Boolean lead,
                                   List<Dto.SignalComponent> signals, Double score) {
        String type = w.getType();
        return new Dto.Recommendation(
                w.getExtId(), rank, type, w.getTitle(), w.getSource(),
                whyFor(w), false, chipsFor(w), lead, signals,
                evidenceFor(w), score, actionsFor(type), w.getUrl(),
                briefing.isHandled(w.getExtId()), briefing.isPlanned(w.getExtId()),
                w.getAuthor(), w.getPrRole(),
                w.getStartsAt() == null ? null : w.getStartsAt().toString());
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
}
