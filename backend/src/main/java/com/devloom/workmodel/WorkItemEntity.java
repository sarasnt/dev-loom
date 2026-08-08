package com.devloom.workmodel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The unified WorkItem (SPEC.md §15) — one row per PR / build / task / review / event. */
@Entity
@Table(name = "work_item")
public class WorkItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ext_id", nullable = false)
    private String extId;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String status;

    @Column(name = "status_tone", nullable = false)
    private String statusTone;

    @Column(name = "meta_csv", nullable = false)
    private String metaCsv = "";

    @Column(nullable = false)
    private String source;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected WorkItemEntity() {
    }

    /** Build a WorkItem for ingestion from a connector (id is DB-generated). */
    public static WorkItemEntity create(String extId, String type, String title, String status,
                                        String statusTone, String metaCsv, String source, int sortOrder) {
        WorkItemEntity e = new WorkItemEntity();
        e.extId = extId;
        e.type = type;
        e.title = title;
        e.status = status;
        e.statusTone = statusTone;
        e.metaCsv = metaCsv == null ? "" : metaCsv;
        e.source = source;
        e.sortOrder = sortOrder;
        return e;
    }

    public Long getId() { return id; }
    public String getExtId() { return extId; }
    public String getType() { return type; }
    public String getTitle() { return title; }
    public String getStatus() { return status; }
    public String getStatusTone() { return statusTone; }
    public String getMetaCsv() { return metaCsv; }
    public String getSource() { return source; }
    public int getSortOrder() { return sortOrder; }
}
