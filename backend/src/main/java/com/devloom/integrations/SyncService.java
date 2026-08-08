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
 * Orchestrates connector syncs into the unified WorkItem table. Replace-on-sync per source
 * (idempotent: re-running produces the same rows), so a source's items always reflect the
 * latest fetch without duplicating. Other sources' rows (e.g. fixture GitHub) are untouched.
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final List<SourceConnector> connectors;
    private final WorkItemRepository repo;
    private final AuditService audit;

    public SyncService(List<SourceConnector> connectors, WorkItemRepository repo, AuditService audit) {
        this.connectors = connectors;
        this.repo = repo;
        this.audit = audit;
    }

    /** Sync a single source by label; returns the number of items ingested. */
    @Transactional
    public int sync(String source) {
        SourceConnector connector = connectors.stream()
                .filter(c -> c.source().equalsIgnoreCase(source))
                .findFirst()
                .orElse(null);
        if (connector == null || !connector.enabled()) {
            log.info("Sync skipped for '{}' (no connector or not configured)", source);
            return 0;
        }
        List<WorkItemEntity> items = connector.fetch();
        if (items.isEmpty()) {
            return 0; // leave existing rows in place on a failed/empty fetch
        }
        repo.deleteBySource(connector.source());
        repo.saveAll(items);
        log.info("Synced {} items from {}", items.size(), connector.source());
        audit.record("sync", connector.source(), "ingested=" + items.size());
        return items.size();
    }

    /** Sync every enabled connector. Returns total items ingested. */
    @Transactional
    public int syncAll() {
        int total = 0;
        for (SourceConnector c : connectors) {
            if (c.enabled()) {
                total += sync(c.source());
            }
        }
        return total;
    }
}
