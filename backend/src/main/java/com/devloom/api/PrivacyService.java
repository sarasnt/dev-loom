package com.devloom.api;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.devloom.ai.PrivacyGate;
import com.devloom.audit.AuditEventEntity;
import com.devloom.audit.AuditService;

/**
 * Real privacy / data-boundary state (SPEC.md §26): local-only repos from the {@link PrivacyGate}
 * and an egress log built from the audit trail's {@code llm-egress} events. When everything runs
 * locally the log is empty — an honest "nothing left your machine".
 */
@Service
public class PrivacyService {

    private final PrivacyGate gate;
    private final AuditService audit;

    public PrivacyService(PrivacyGate gate, AuditService audit) {
        this.gate = gate;
        this.audit = audit;
    }

    public Dto.Privacy privacy() {
        List<Dto.EgressEntry> egress = new ArrayList<>();
        for (AuditEventEntity e : audit.recent()) {
            if ("llm-egress".equals(e.getAction())) {
                egress.add(new Dto.EgressEntry(relative(e.getCreatedAt()),
                        e.getMetadata() == null ? "AI request" : e.getMetadata(),
                        e.getTarget(), ""));
            }
        }
        if (egress.isEmpty()) {
            egress.add(new Dto.EgressEntry("—", "Nothing has left your machine", "", ""));
        }
        return new Dto.Privacy(
                "Local-first — nothing leaves unless you add a key and approve it.",
                gate.localOnlyRepos(),
                egress);
    }

    private static String relative(Instant when) {
        long mins = Duration.between(when, Instant.now()).toMinutes();
        if (mins < 1) return "just now";
        if (mins < 60) return mins + "m ago";
        return (mins / 60) + "h ago";
    }
}
