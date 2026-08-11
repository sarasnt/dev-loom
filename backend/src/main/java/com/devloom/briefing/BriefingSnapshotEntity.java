package com.devloom.briefing;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A point-in-time snapshot of the work set (spec §6): baseline for since-yesterday + new-urgent. */
@Entity
@Table(name = "briefing_snapshot")
public class BriefingSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "taken_at", nullable = false)
    private Instant takenAt;

    @Column(nullable = false)
    private String kind;

    @Column(name = "items_json", columnDefinition = "text", nullable = false)
    private String itemsJson;

    protected BriefingSnapshotEntity() {}

    public static BriefingSnapshotEntity of(String kind, String itemsJson) {
        BriefingSnapshotEntity e = new BriefingSnapshotEntity();
        e.takenAt = Instant.now();
        e.kind = kind;
        e.itemsJson = itemsJson;
        return e;
    }

    public Long getId() { return id; }
    public Instant getTakenAt() { return takenAt; }
    public String getKind() { return kind; }
    public String getItemsJson() { return itemsJson; }
}
