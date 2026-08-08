package com.devloom.audit;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records and reads audit events (SPEC.md §26). Every sync, external write, and deletion goes here. */
@Service
public class AuditService {

    private final AuditRepository repo;

    public AuditService(AuditRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public void record(String action, String target, String metadata) {
        repo.save(AuditEventEntity.of(action, target, metadata));
    }

    @Transactional(readOnly = true)
    public List<AuditEventEntity> recent() {
        return repo.findTop50ByOrderByCreatedAtDesc();
    }
}
