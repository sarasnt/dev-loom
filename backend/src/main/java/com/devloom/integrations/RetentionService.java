package com.devloom.integrations;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devloom.audit.AuditService;
import com.devloom.workmodel.WorkItemRepository;

/**
 * Data retention & deletion (SPEC.md §29). Disconnecting a source purges its derived work
 * items; a full purge clears all synced work. Every deletion is audited.
 */
@Service
public class RetentionService {

    private final WorkItemRepository repo;
    private final AuditService audit;

    public RetentionService(WorkItemRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    /** Disconnect a source: purge its work items. Returns rows removed. */
    @Transactional
    public int purgeSource(String source) {
        int removed = repo.deleteBySource(capitalize(source));
        audit.record("purge_source", source, "removed=" + removed);
        return removed;
    }

    /** Delete all synced work items (account/workspace data reset). */
    @Transactional
    public long purgeAll() {
        long removed = repo.count();
        repo.deleteAllInBatch();
        audit.record("purge_all", "work_item", "removed=" + removed);
        return removed;
    }

    private static String capitalize(String s) {
        if (s == null || s.isBlank()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
