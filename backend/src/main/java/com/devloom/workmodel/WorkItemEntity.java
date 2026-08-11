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

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "parent_ext_id")
    private String parentExtId;

    @Column(name = "source_instance_id")
    private Long sourceInstanceId;

    @Column(name = "metadata", columnDefinition = "text")
    private String metadata;

    // Canonical URL to open this item (GitHub PR/issue, Jira browse, Notion page).
    @Column(name = "url", columnDefinition = "text")
    private String url;

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

    /**
     * Optional detail for the expandable Work view: a description preview and a parent
     * work item (its {@code extId} within the same source, for hierarchy). Fluent so
     * connectors can add it without widening {@link #create}.
     */
    public WorkItemEntity withDetail(String description, String parentExtId) {
        this.description = trim(description, 2000);
        this.parentExtId = (parentExtId == null || parentExtId.isBlank()) ? null : parentExtId;
        return this;
    }

    /** Rich, model-facing metadata (e.g. Jira custom fields as "Name: value" lines). */
    public WorkItemEntity withMetadata(String metadata) {
        this.metadata = trim(metadata, 3000);
        return this;
    }

    /** Canonical URL to open the item in its source. */
    public WorkItemEntity withUrl(String url) {
        this.url = (url == null || url.isBlank()) ? null : url.strip();
        return this;
    }

    private static String trim(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) + "…" : t;
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
    public String getDescription() { return description; }
    public String getParentExtId() { return parentExtId; }
    public Long getSourceInstanceId() { return sourceInstanceId; }
    public void setSourceInstanceId(Long id) { this.sourceInstanceId = id; }
    public String getMetadata() { return metadata; }
    public String getUrl() { return url; }
}
