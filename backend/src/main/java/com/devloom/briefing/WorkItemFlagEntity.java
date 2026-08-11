package com.devloom.briefing;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Per-work-item user state that must outlive a sync (spec §6): {@code handled} (I dealt with it)
 * and {@code planned} (in today's plan). Keyed by the source-stable {@code ext_id} so it survives
 * the delete-and-reinsert sync.
 */
@Entity
@Table(name = "work_item_flag")
public class WorkItemFlagEntity {

    @Id
    @Column(name = "ext_id")
    private String extId;

    @Column(name = "handled_at")
    private Instant handledAt;

    @Column(name = "planned_at")
    private Instant plannedAt;

    protected WorkItemFlagEntity() {}

    public static WorkItemFlagEntity of(String extId) {
        WorkItemFlagEntity e = new WorkItemFlagEntity();
        e.extId = extId;
        return e;
    }

    public String getExtId() { return extId; }
    public Instant getHandledAt() { return handledAt; }
    public void setHandledAt(Instant t) { this.handledAt = t; }
    public Instant getPlannedAt() { return plannedAt; }
    public void setPlannedAt(Instant t) { this.plannedAt = t; }
}
