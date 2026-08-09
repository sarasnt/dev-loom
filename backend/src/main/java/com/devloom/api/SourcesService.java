package com.devloom.api;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devloom.audit.AuditEventEntity;
import com.devloom.audit.AuditService;
import com.devloom.integrations.SetupDescriptor;
import com.devloom.integrations.SourceConnector;
import com.devloom.integrations.SourceCredentialStore;
import com.devloom.integrations.SourceInstanceEntity;
import com.devloom.integrations.SourceInstanceRepository;
import com.devloom.integrations.SourceRegistry;
import com.devloom.integrations.SyncService;
import com.devloom.workmodel.WorkItemRepository;

/**
 * CRUD + test/sync for configured source instances (docs/SPEC-sources.md §8/§10). Secrets are
 * split from config using each connector's descriptor and stored encrypted; a source's name
 * becomes its items' source.
 */
@Service
public class SourcesService {

    private final SourceRegistry registry;
    private final SourceInstanceRepository instances;
    private final SourceCredentialStore credentials;
    private final SyncService sync;
    private final WorkItemRepository work;
    private final AuditService audit;

    public SourcesService(SourceRegistry registry, SourceInstanceRepository instances,
                          SourceCredentialStore credentials, SyncService sync,
                          WorkItemRepository work, AuditService audit) {
        this.registry = registry;
        this.instances = instances;
        this.credentials = credentials;
        this.sync = sync;
        this.work = work;
        this.audit = audit;
    }

    public List<SetupDescriptor.Type> types() {
        return registry.descriptors();
    }

    public List<Dto.SourceView> list() {
        return instances.findAllByOrderByTypeAscNameAsc().stream().map(this::toView).toList();
    }

    @Transactional
    public Dto.SourceView create(Dto.SourceUpsert req) {
        SourceConnector connector = require(req.type());
        Map<String, String> fields = req.fields() == null ? Map.of() : req.fields();
        String baseUrl = blankToNull(fields.get("baseUrl"));
        SourceInstanceEntity inst = instances.save(
                SourceInstanceEntity.of(req.type(), req.deployment(), req.name(), baseUrl, null));
        saveSecrets(inst, connector, req.deployment(), fields);
        audit.record("source_add", inst.getName(), "type=" + inst.getType());
        try {
            sync.syncInstance(inst);
        } catch (Exception ignore) {
            // first sync is best-effort; the source is still created
        }
        return toView(inst);
    }

    @Transactional
    public Dto.SourceView update(String id, Dto.SourceUpsert req) {
        SourceInstanceEntity inst = instances.findById(parse(id)).orElseThrow();
        SourceConnector connector = require(inst.getType());
        if (req.name() != null && !req.name().isBlank()) inst.setName(req.name().strip());
        if (req.enabled() != null) inst.setEnabled(req.enabled());
        Map<String, String> fields = req.fields();
        if (fields != null) {
            if (fields.containsKey("baseUrl")) inst.setBaseUrl(blankToNull(fields.get("baseUrl")));
            // Only (re)write secrets if any secret field was actually provided.
            if (hasAnySecret(connector, inst.getDeployment(), fields)) {
                saveSecrets(inst, connector, inst.getDeployment(), fields);
            }
        }
        inst.touch();
        instances.save(inst);
        audit.record("source_update", inst.getName(), "type=" + inst.getType());
        return toView(inst);
    }

    @Transactional
    public void delete(String id) {
        SourceInstanceEntity inst = instances.findById(parse(id)).orElse(null);
        if (inst == null) return;
        int removed = work.deleteBySourceInstanceId(inst.getId());
        credentials.delete(inst.getId());
        instances.delete(inst);
        audit.record("source_delete", inst.getName(), "removed=" + removed);
    }

    /** Validate a would-be (or existing) source without persisting the result. */
    public Map<String, Object> test(Dto.SourceUpsert req) {
        try {
            SourceConnector connector = require(req.type());
            Map<String, String> fields = req.fields() == null ? Map.of() : req.fields();
            SourceInstanceEntity transientInst = SourceInstanceEntity.of(
                    req.type(), req.deployment(),
                    req.name() == null || req.name().isBlank() ? "test" : req.name(),
                    blankToNull(fields.get("baseUrl")), null);
            connector.test(transientInst, secretsFrom(connector, req.deployment(), fields));
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    @Transactional
    public int sync(String id) {
        return instances.findById(parse(id)).map(sync::syncInstance).orElse(0);
    }

    // ---- helpers ----

    private void saveSecrets(SourceInstanceEntity inst, SourceConnector connector,
                             String deployment, Map<String, String> fields) {
        Map<String, String> secrets = secretsFrom(connector, deployment, fields);
        if (!secrets.isEmpty()) {
            credentials.save(inst.getId(), secrets);
        }
    }

    /** Pull the secret-typed fields (per the descriptor) out of the submitted field map. */
    private Map<String, String> secretsFrom(SourceConnector connector, String deployment,
                                            Map<String, String> fields) {
        Map<String, String> secrets = new LinkedHashMap<>();
        for (SetupDescriptor.Deployment d : connector.describe().deployments()) {
            if (!d.id().equalsIgnoreCase(deployment)) continue;
            for (SetupDescriptor.Field f : d.fields()) {
                if (f.secret()) {
                    String v = fields.get(f.key());
                    if (v != null && !v.isBlank()) secrets.put(f.key(), v.strip());
                }
            }
        }
        return secrets;
    }

    private boolean hasAnySecret(SourceConnector connector, String deployment, Map<String, String> fields) {
        return !secretsFrom(connector, deployment, fields).isEmpty();
    }

    private Dto.SourceView toView(SourceInstanceEntity inst) {
        boolean connected = inst.isEnabled() && credentials.hasCredential(inst);
        long items = work.countBySourceInstanceId(inst.getId());
        String lastSync = lastSyncRelative(inst.getName());
        String typeLabel = registry.forType(inst.getType())
                .map(c -> c.describe().label()).orElse(inst.getType());
        String detail = connected
                ? "connected · " + items + " item" + (items == 1 ? "" : "s") + (lastSync != null ? " · " + lastSync : "")
                : (credentials.hasCredential(inst) ? "disabled" : "no credential");
        List<String> actions = new ArrayList<>(List.of("Re-sync", "Test", "Edit", "Disconnect"));
        String note = "notion".equals(inst.getType()) && connected ? null : null;
        return new Dto.SourceView(
                String.valueOf(inst.getId()), inst.getType(), typeLabel, inst.getDeployment(),
                inst.getName(), inst.getBaseUrl(), inst.isEnabled(),
                connected ? "connected" : "not_connected", detail, items, note, actions);
    }

    private SourceConnector require(String type) {
        return registry.forType(type)
                .orElseThrow(() -> new IllegalArgumentException("unknown source type: " + type));
    }

    private String lastSyncRelative(String name) {
        return audit.recent().stream()
                .filter(e -> "sync".equals(e.getAction()) && name.equals(e.getTarget()))
                .findFirst()
                .map(AuditEventEntity::getCreatedAt)
                .map(SourcesService::relative)
                .orElse(null);
    }

    private static String relative(Instant when) {
        long mins = Duration.between(when, Instant.now()).toMinutes();
        if (mins < 1) return "synced just now";
        if (mins < 60) return "synced " + mins + "m ago";
        return "synced " + (mins / 60) + "h ago";
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }

    private static Long parse(String id) {
        try {
            return Long.valueOf(id);
        } catch (Exception e) {
            return -1L;
        }
    }
}
