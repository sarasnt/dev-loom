package com.devloom.briefing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
     * Active, unhandled items whose extId is absent from the last "digest" snapshot — Work's New
     * chip (design doc Decision 3), extracted from the diff {@code build(...)} used to compute so
     * the chip agrees with what the old briefing called new. Handled items were never "new" in the
     * old list either (the loop that built it skipped them before the diff check).
     */
    @Transactional
    public Set<String> newExtIds() {
        clearHandledOnReopen();
        Map<String, UrgencyRules.SnapItem> base = lastSnapshot("digest");
        Set<String> handled = handledExtIds();
        Set<String> out = new java.util.LinkedHashSet<>();
        for (WorkItemEntity w : active()) {
            if (handled.contains(w.getExtId())) continue;
            if (!base.containsKey(w.getExtId())) out.add(w.getExtId());
        }
        return out;
    }

    /**
     * Active, unhandled items {@link UrgencyRules} flags as urgent — Today's "needs you now"
     * section, extracted from the same selection the old briefing diff made (reuse, don't
     * reimplement, so the two never drift apart).
     */
    @Transactional
    public Set<String> needsYouExtIds() {
        clearHandledOnReopen();
        Set<String> handled = handledExtIds();
        Set<String> out = new java.util.LinkedHashSet<>();
        for (WorkItemEntity w : active()) {
            if (handled.contains(w.getExtId())) continue;
            if (UrgencyRules.urgencyKey(w) != null) out.add(w.getExtId());
        }
        return out;
    }
}
