package com.devloom.audit;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Audit trail (SPEC.md §26): credential use, external writes, syncs, deletions. */
@Entity
@Table(name = "audit_event")
public class AuditEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String action;

    @Column(nullable = false)
    private String target;

    @Column
    private String metadata;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditEventEntity() {
    }

    public static AuditEventEntity of(String action, String target, String metadata) {
        AuditEventEntity e = new AuditEventEntity();
        e.action = action;
        e.target = target;
        e.metadata = metadata;
        e.createdAt = Instant.now();
        return e;
    }

    public Long getId() { return id; }
    public String getAction() { return action; }
    public String getTarget() { return target; }
    public String getMetadata() { return metadata; }
    public Instant getCreatedAt() { return createdAt; }
}
