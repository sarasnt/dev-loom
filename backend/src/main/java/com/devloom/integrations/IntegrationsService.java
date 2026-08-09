package com.devloom.integrations;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.devloom.api.Dto;
import com.devloom.audit.AuditEventEntity;
import com.devloom.audit.AuditService;
import com.devloom.workmodel.WorkItemRepository;

/**
 * Real integration status for the Settings screen — derived from live connector state
 * (enabled?), the item count per source, and the last sync time from the audit trail.
 * No fixtures. Microsoft Calendar is shown as available-but-not-connected (iteration 1).
 */
@Service
public class IntegrationsService {

    private record Meta(String key, String name, List<String> scopes, String noteWhenEmpty,
                        List<String> actions, String hint) {}

    private static final String NOTION_HINT =
            "Sync pulls every page, database and task connected to DevLoom. Notion only exposes "
            + "what you've shared with the integration — to sync more, open a page/database in "
            + "Notion → ••• → Connections → DevLoom (access cascades to child pages), then Re-sync.";

    private static final Map<String, Meta> META = Map.of(
            "GitHub", new Meta("github", "GitHub",
                    List.of("Contents", "Pull requests", "Issues", "Checks", "Actions", "Deployments"),
                    "Connected, but no open items involve you right now.",
                    List.of("Re-sync", "Disconnect"), null),
            "Jira", new Meta("jira", "Jira", null, null, List.of("Re-sync", "Disconnect"), null),
            "Calendar", new Meta("gcal", "Google Calendar", null, null, List.of("Re-sync", "Disconnect"), null),
            "Notion", new Meta("notion", "Notion", null,
                    "No pages shared yet — connect the DevLoom integration to a page.",
                    List.of("Sync all", "Disconnect"), NOTION_HINT));

    private static final List<String> ORDER = List.of("GitHub", "Jira", "Calendar", "Notion");

    private final List<SourceConnector> connectors;
    private final WorkItemRepository repo;
    private final AuditService audit;

    public IntegrationsService(List<SourceConnector> connectors, WorkItemRepository repo, AuditService audit) {
        this.connectors = connectors;
        this.repo = repo;
        this.audit = audit;
    }

    public List<Dto.Integration> list() {
        List<Dto.Integration> out = new ArrayList<>();
        for (String source : ORDER) {
            connectors.stream().filter(c -> c.source().equals(source)).findFirst()
                    .ifPresent(c -> out.add(toDto(c)));
        }
        // Microsoft Calendar — not part of iteration 1.
        out.add(new Dto.Integration("mscal", "Microsoft Calendar", "not_connected",
                null, null, "Excluded from iteration 1.", List.of("Connect")));
        return out;
    }

    private Dto.Integration toDto(SourceConnector c) {
        Meta m = META.getOrDefault(c.source(),
                new Meta(c.source().toLowerCase(), c.source(), null, null, List.of(), null));
        boolean enabled = c.enabled();
        long count = repo.countBySource(c.source());
        String lastSync = lastSyncRelative(c.source());

        String state = enabled ? "connected" : "not_connected";
        String detail = null;
        String note = null;
        if (enabled) {
            detail = "connected · " + count + " item" + (count == 1 ? "" : "s")
                    + (lastSync != null ? " · " + lastSync : "");
            // A persistent hint (e.g. Notion's "how to connect more") wins; else the empty note.
            if (m.hint() != null) {
                note = m.hint();
            } else if (count == 0 && m.noteWhenEmpty() != null) {
                note = m.noteWhenEmpty();
            }
        }
        List<String> actions = enabled ? m.actions() : List.of("Connect");
        return new Dto.Integration(m.key(), m.name(), state, detail, m.scopes(), note, actions);
    }

    private String lastSyncRelative(String source) {
        return audit.recent().stream()
                .filter(e -> "sync".equals(e.getAction()) && source.equals(e.getTarget()))
                .findFirst()
                .map(AuditEventEntity::getCreatedAt)
                .map(IntegrationsService::relative)
                .orElse(null);
    }

    private static String relative(Instant when) {
        long mins = Duration.between(when, Instant.now()).toMinutes();
        if (mins < 1) return "synced just now";
        if (mins < 60) return "synced " + mins + "m ago";
        return "synced " + (mins / 60) + "h ago";
    }
}
