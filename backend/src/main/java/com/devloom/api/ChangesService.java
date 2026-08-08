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
 */
@Service
public class ChangesService {

    private final AuditService audit;

    public ChangesService(AuditService audit) {
        this.audit = audit;
    }

    public Dto.Changed changed() {
        List<AuditEventEntity> recent = audit.recent();
        if (recent.isEmpty()) {
            return null;
        }
        long synced = recent.stream()
                .filter(e -> "sync".equals(e.getAction()))
                .map(e -> parseCount(e.getMetadata()))
                .reduce(0L, Long::sum);
        long sources = recent.stream()
                .filter(e -> "sync".equals(e.getAction()))
                .map(AuditEventEntity::getTarget)
                .distinct().count();
        long purges = recent.stream().filter(e -> e.getAction().startsWith("purge")).count();

        String text = "%d items synced across %d source%s%s".formatted(
                synced, sources, sources == 1 ? "" : "s",
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
