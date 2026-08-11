package com.devloom.integrations;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devloom.audit.AuditService;
import com.devloom.workmodel.WorkItemEntity;
import com.devloom.workmodel.WorkItemRepository;

/**
 * Orchestrates connector syncs into the unified WorkItem table (docs/SPEC-sources.md §7).
 * Replace-on-sync <em>per instance</em> (idempotent). A failed fetch throws → existing rows are
 * kept; a successful-but-empty fetch clears the instance's rows so stale items disappear.
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final SourceRegistry registry;
    private final SourceInstanceRepository instances;
    private final SourceCredentialStore credentials;
    private final WorkItemRepository repo;
    private final AuditService audit;
    private final com.devloom.briefing.NotificationService notifications;

    public SyncService(SourceRegistry registry, SourceInstanceRepository instances,
                       SourceCredentialStore credentials, WorkItemRepository repo, AuditService audit,
                       com.devloom.briefing.NotificationService notifications) {
        this.registry = registry;
        this.instances = instances;
        this.credentials = credentials;
        this.repo = repo;
        this.audit = audit;
        this.notifications = notifications;
    }

    /** After a sync completes, fire urgent alerts for newly-urgent items (best-effort). */
    private void afterSync() {
        try { notifications.onSync(); } catch (Exception e) { /* never let notify break a sync */ }
    }

    /** Sync one instance; returns items ingested. */
    @Transactional
    public int syncInstance(SourceInstanceEntity inst) {
        if (!inst.isEnabled()) {
            return 0;
        }
        SourceConnector connector = registry.forType(inst.getType()).orElse(null);
        if (connector == null) {
            log.info("No connector for type '{}' (instance {})", inst.getType(), inst.getName());
            return 0;
        }
        List<WorkItemEntity> items;
        try {
            items = connector.fetch(inst, credentials.secrets(inst));
        } catch (Exception e) {
            log.warn("Sync failed for '{}' — keeping existing rows: {}", inst.getName(), e.getMessage());
            return 0;
        }
        repo.deleteBySourceInstanceId(inst.getId());
        for (WorkItemEntity w : items) {
            w.setSourceInstanceId(inst.getId());
        }
        if (!items.isEmpty()) {
            repo.saveAll(items);
        }
        log.info("Synced {} items from {}", items.size(), inst.getName());
        audit.record("sync", inst.getName(), "ingested=" + items.size());
        return items.size();
    }

    /** Back-compat: sync by instance name or type (used by the current Integrations endpoints). */
    @Transactional
    public int sync(String nameOrType) {
        SourceInstanceEntity byName = instances.findByNameIgnoreCase(nameOrType).orElse(null);
        if (byName != null) {
            int n = syncInstance(byName);
            afterSync();
            return n;
        }
        int total = 0;
        for (SourceInstanceEntity inst : instances.findByType(nameOrType.toLowerCase())) {
            total += syncInstance(inst);
        }
        afterSync();
        return total;
    }

    /** Sync every enabled instance. Returns total items ingested. */
    @Transactional
    public int syncAll() {
        int total = 0;
        for (SourceInstanceEntity inst : instances.findByEnabledTrue()) {
            total += syncInstance(inst);
        }
        afterSync();
        return total;
    }
}
