package com.devloom.briefing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devloom.api.Dto;
import com.devloom.common.AppConfigService;
import com.devloom.workmodel.WorkItemEntity;
import com.devloom.workmodel.WorkItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Memory for the Today "Briefing" mode (spec §6): the since-yesterday diff, persisted
 * handled/plan flags, urgency, and snapshot bookkeeping. Snoozed items are excluded everywhere
 * so the briefing agrees with the rest of Today.
 */
@Service
public class BriefingService {

    private final WorkItemRepository work;
    private final BriefingSnapshotRepository snapshots;
    private final WorkItemFlagRepository flags;
    private final AppConfigService config;
    private final ObjectMapper json = new ObjectMapper();

    public BriefingService(WorkItemRepository work, BriefingSnapshotRepository snapshots,
                           WorkItemFlagRepository flags, AppConfigService config) {
        this.work = work;
        this.snapshots = snapshots;
        this.flags = flags;
        this.config = config;
    }

    // ---- flags -----------------------------------------------------------------

    public boolean isHandled(String extId) {
        return flags.findById(extId).map(f -> f.getHandledAt() != null).orElse(false);
    }

    public boolean isPlanned(String extId) {
        return flags.findById(extId).map(f -> f.getPlannedAt() != null).orElse(false);
    }

    public Set<String> handledExtIds() {
        return flags.findAll().stream().filter(f -> f.getHandledAt() != null)
                .map(WorkItemFlagEntity::getExtId).collect(Collectors.toSet());
    }

    public Set<String> plannedExtIds() {
        return flags.findAll().stream().filter(f -> f.getPlannedAt() != null)
                .map(WorkItemFlagEntity::getExtId).collect(Collectors.toSet());
    }

    @Transactional
    public void toggleHandled(String extId) {
        WorkItemFlagEntity f = flags.findById(extId).orElseGet(() -> WorkItemFlagEntity.of(extId));
        if (f.getHandledAt() == null) {
            f.setHandledAt(Instant.now());
            // Remember the status at handle-time so the reopen-guard clears only on a real change.
            f.setHandledStatus(work.findFirstByExtId(extId).map(WorkItemEntity::getStatus).orElse(null));
        } else {
            f.setHandledAt(null);
            f.setHandledStatus(null);
        }
        flags.save(f);
    }

    @Transactional
    public void togglePlan(String extId) {
        WorkItemFlagEntity f = flags.findById(extId).orElseGet(() -> WorkItemFlagEntity.of(extId));
        f.setPlannedAt(f.getPlannedAt() == null ? Instant.now() : null);
        flags.save(f);
    }

    // ---- snapshots -------------------------------------------------------------

    /** Active (non-snoozed) work items. */
    private List<WorkItemEntity> active() {
        Set<String> snoozed = config.snoozed();
        return work.findAllByOrderBySortOrderAsc().stream()
                .filter(w -> !snoozed.contains(w.getExtId()))
                .toList();
    }

    /** SnapItems for every active work item (for diffing + urgent detection). */
    public List<UrgencyRules.SnapItem> currentSnapshot() {
        List<UrgencyRules.SnapItem> out = new ArrayList<>();
        for (WorkItemEntity w : active()) {
            out.add(new UrgencyRules.SnapItem(w.getExtId(), w.getType(), w.getSource(),
                    w.getStatus(), UrgencyRules.urgencyKey(w)));
        }
        return out;
    }

    /** Persist the current work set as a snapshot of the given kind, pruning old rows. */
    @Transactional
    public void writeSnapshot(String kind) {
        try {
            snapshots.save(BriefingSnapshotEntity.of(kind, json.writeValueAsString(currentSnapshot())));
        } catch (Exception e) {
            // snapshot is best-effort; a serialization hiccup must never break a request/sync
        }
        List<BriefingSnapshotEntity> all = snapshots.findByKindOrderByTakenAtDesc(kind);
        for (int i = 14; i < all.size(); i++) {
            snapshots.delete(all.get(i));
        }
    }

    /** The last snapshot of a kind as an extId→SnapItem map (empty if none). */
    public Map<String, UrgencyRules.SnapItem> lastSnapshot(String kind) {
        return snapshots.findFirstByKindOrderByTakenAtDesc(kind)
                .map(s -> decode(s.getItemsJson())).orElseGet(Map::of);
    }

    private Map<String, UrgencyRules.SnapItem> decode(String body) {
        try {
            UrgencyRules.SnapItem[] arr = json.readValue(body, UrgencyRules.SnapItem[].class);
            Map<String, UrgencyRules.SnapItem> m = new LinkedHashMap<>();
            for (UrgencyRules.SnapItem s : arr) {
                m.put(s.extId(), s);
            }
            return m;
        } catch (Exception e) {
            return Map.of();
        }
    }

    // ---- assembly --------------------------------------------------------------

    /** A "done"-ish status means the item is resolved even if it is still present. */
    public static boolean isDoneStatus(String status) {
        String s = status == null ? "" : status.toLowerCase();
        return s.contains("done") || s.contains("complete") || s.contains("closed")
                || s.contains("merged") || s.contains("shipped") || s.contains("archived")
                || s.contains("passed") || s.contains("success");
    }

    /**
     * Re-open guard: clear a handled flag only when the item's status has <em>changed</em> since it
     * was handled (e.g. a PR you handled got reopened, or a build failed again). Merely being open
     * is not a reopen — otherwise nothing would ever stay handled.
     */
    @Transactional
    public void clearHandledOnReopen() {
        Map<String, WorkItemEntity> byId = work.findAll().stream()
                .collect(Collectors.toMap(WorkItemEntity::getExtId, w -> w, (a, b) -> a));
        for (WorkItemFlagEntity f : flags.findAll()) {
            if (f.getHandledAt() == null) continue;
            WorkItemEntity w = byId.get(f.getExtId());
            if (w == null) continue; // gone — the flag is inert, leave it for pruning
            String at = f.getHandledStatus();
            if (at != null && !at.equals(w.getStatus())) {
                f.setHandledAt(null);
                f.setHandledStatus(null);
                flags.save(f);
            }
        }
    }

    /**
     * Assemble the Briefing. {@code toRec} converts a work item into a Recommendation (with
     * handled/plan already applied by the caller). Baseline for "since yesterday" is the last
     * digest snapshot.
     */
    @Transactional
    public Dto.Briefing build(Function<WorkItemEntity, Dto.Recommendation> toRec) {
        clearHandledOnReopen();
        Map<String, UrgencyRules.SnapItem> base = lastSnapshot("digest");
        Set<String> handled = handledExtIds();
        Set<String> planned = plannedExtIds();

        List<WorkItemEntity> items = active();
        List<Dto.Recommendation> newItems = new ArrayList<>();
        List<Dto.Recommendation> needsYou = new ArrayList<>();
        List<Dto.Recommendation> plan = new ArrayList<>();

        Map<String, WorkItemEntity> byId = new LinkedHashMap<>();
        for (WorkItemEntity w : items) {
            byId.put(w.getExtId(), w);
            Dto.Recommendation rec = toRec.apply(w);
            if (planned.contains(w.getExtId())) plan.add(rec);
            if (handled.contains(w.getExtId())) continue; // handled drops from the active lists
            // Carried over from the baseline isn't news; only what appeared since is. The full
            // carried-over set was being built and shipped on every Today load — 49 hydrated items,
            // two thirds of the payload — and never rendered. It lives in Work and Triage.
            if (!base.containsKey(w.getExtId())) newItems.add(rec);
            if (UrgencyRules.urgencyKey(w) != null) needsYou.add(rec);
        }

        // Resolved: present in the baseline and now in a done status (fully-gone items just vanish).
        List<Dto.Recommendation> resolved = new ArrayList<>();
        for (String extId : base.keySet()) {
            WorkItemEntity w = byId.get(extId);
            if (w != null && isDoneStatus(w.getStatus())) resolved.add(toRec.apply(w));
        }

        // Each list is numbered on its own. The cards are drawn hanging off the warp spine, which
        // is numbered by rank — and these came through carrying the rank they had in the global
        // ranking, which for everything below the lead item is 0. Every card in the briefing read
        // "0". Within a section, position in that section is what the number means.
        return new Dto.Briefing(numbered(newItems), numbered(resolved),
                numbered(needsYou), numbered(plan));
    }

    /** Re-number a briefing section 1..n, leaving everything else about each card alone. */
    private static List<Dto.Recommendation> numbered(List<Dto.Recommendation> in) {
        List<Dto.Recommendation> out = new ArrayList<>(in.size());
        int i = 1;
        for (Dto.Recommendation r : in) {
            out.add(new Dto.Recommendation(r.id(), i++, r.type(), r.title(), r.source(), r.why(),
                    r.isHypothesis(), r.chips(), r.lead(), r.signals(), r.evidence(), r.score(),
                    r.actions(), r.url(), r.handled(), r.planned(), r.author(), r.prRole()));
        }
        return out;
    }
}
