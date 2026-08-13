package com.devloom.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.devloom.audit.AuditEventEntity;
import com.devloom.audit.AuditService;

/**
 * "What changed since you last looked" (SPEC.md FR-17). Derived deterministically from the
 * audit trail — recent syncs and deletions summarized into one line, with a relative "since".
 * Real (not fixture): reflects actual recorded activity.
 *
 * <p>It reports how many items you have and how many sources they came from, not the sum of what
 * every sync ingested. Syncing is replace-on-sync, so each run re-ingests the whole source: adding
 * those up counted the same 54 items once per sync and told a workspace of 54 that 500 things had
 * happened. What changed is a property of the items, not of how often we fetched them.
 */
@Service
public class ChangesService {

    private final AuditService audit;
    private final com.devloom.workmodel.WorkItemRepository work;

    public ChangesService(AuditService audit, com.devloom.workmodel.WorkItemRepository work) {
        this.audit = audit;
        this.work = work;
    }

    public Dto.Changed changed() {
        List<AuditEventEntity> recent = audit.recent();
        if (recent.isEmpty()) {
            return null;
        }
        long sources = recent.stream()
                .filter(e -> "sync".equals(e.getAction()))
                .map(AuditEventEntity::getTarget)
                .distinct().count();
        long purges = recent.stream().filter(e -> e.getAction().startsWith("purge")).count();
        long tracked = work.count();

        String text = "%d item%s tracked across %d source%s%s".formatted(
                tracked, tracked == 1 ? "" : "s", sources, sources == 1 ? "" : "s",
                purges > 0 ? " · " + purges + " deletion" + (purges == 1 ? "" : "s") : "");

        return new Dto.Changed(text, since(recent.getLast().getCreatedAt()));
    }

    private static long parseCount(String metadata) {
        if (metadata == null) return 0;
        int i = metadata.indexOf('=');
        try {
            return i >= 0 ? Long.parseLong(metadata.substring(i + 1).trim()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String since(Instant earliest) {
        long mins = Duration.between(earliest, Instant.now()).toMinutes();
        if (mins < 1) return "just now";
        if (mins < 60) return "since " + mins + "m ago";
        long h = mins / 60;
        return "since " + h + "h ago";
    }

    /** Detailed recent changes as audit entries (for a future "changes" drawer). */
    public List<Dto.AuditEntry> detail() {
        return audit.recent().stream()
                .map(e -> new Dto.AuditEntry(e.getAction(), e.getTarget(), e.getMetadata(),
                        e.getCreatedAt().toString()))
                .collect(Collectors.toList());
    }
}
