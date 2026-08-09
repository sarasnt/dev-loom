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
 * Integration status for the Settings screen, derived from configured source instances
 * (docs/SPEC-sources.md): enabled + credential presence, item count, last sync. No fixtures.
 */
@Service
public class IntegrationsService {

    private record Meta(String key, List<String> scopes, List<String> actions, String hint) {}

    private static final String NOTION_HINT =
            "Sync all pulls every page, database and task connected to DevLoom — Notion only "
            + "exposes what you've connected to the integration. To add more, connect each page "
            + "(open it → ••• → Connections → DevLoom), or add several at once in Notion → "
            + "Settings → Connections → DevLoom → Access. Connecting a whole teamspace includes "
            + "everything inside it. Then Sync all.";

    // Per-type presentation for the (current) Integrations screen.
    private static final Map<String, Meta> META = Map.of(
            "github", new Meta("github",
                    List.of("Contents", "Pull requests", "Issues", "Checks", "Actions"),
                    List.of("Re-sync", "Disconnect"), null),
            "jira", new Meta("jira", null, List.of("Re-sync", "Disconnect"), null),
            "calendar", new Meta("gcal", null, List.of("Re-sync", "Disconnect"), null),
            "notion", new Meta("notion", null, List.of("Sync all", "Disconnect"), NOTION_HINT));

    private final SourceInstanceRepository instances;
    private final SourceCredentialStore credentials;
    private final WorkItemRepository repo;
    private final AuditService audit;

    public IntegrationsService(SourceInstanceRepository instances, SourceCredentialStore credentials,
                               WorkItemRepository repo, AuditService audit) {
        this.instances = instances;
        this.credentials = credentials;
        this.repo = repo;
        this.audit = audit;
    }

    public List<Dto.Integration> list() {
        List<Dto.Integration> out = new ArrayList<>();
        for (SourceInstanceEntity inst : instances.findAllByOrderByTypeAscNameAsc()) {
            out.add(toDto(inst));
        }
        // Microsoft Calendar — not part of iteration 1.
        out.add(new Dto.Integration("mscal", "Microsoft Calendar", "not_connected",
                null, null, "Excluded from iteration 1.", List.of("Connect")));
        return out;
    }

    private Dto.Integration toDto(SourceInstanceEntity inst) {
        Meta m = META.getOrDefault(inst.getType(),
                new Meta(inst.getType(), null, List.of("Re-sync", "Disconnect"), null));
        boolean connected = inst.isEnabled() && credentials.hasCredential(inst);
        long count = repo.countBySourceInstanceId(inst.getId());
        String lastSync = lastSyncRelative(inst.getName());

        String state = connected ? "connected" : "not_connected";
        String detail = null;
        String note = null;
        if (connected) {
            detail = "connected · " + count + " item" + (count == 1 ? "" : "s")
                    + (lastSync != null ? " · " + lastSync : "");
            note = m.hint();
        }
        List<String> actions = connected ? m.actions() : List.of("Connect");
        return new Dto.Integration(m.key(), inst.getName(), state, detail, m.scopes(), note, actions);
    }

    private String lastSyncRelative(String name) {
        return audit.recent().stream()
                .filter(e -> "sync".equals(e.getAction()) && name.equals(e.getTarget()))
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
