package com.devloom.brainstorm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One item in a brainstorm session's context (repo / work item / file / note). */
@Entity
@Table(name = "brainstorm_context")
public class BrainstormContextEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(nullable = false)
    private String kind; // repo | workitem | file | note

    @Column
    private String ref;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private boolean pinned;

    protected BrainstormContextEntity() {
    }

    public static BrainstormContextEntity of(Long sessionId, String kind, String ref, String label, boolean pinned) {
        BrainstormContextEntity e = new BrainstormContextEntity();
        e.sessionId = sessionId;
        e.kind = kind;
        e.ref = ref;
        e.label = label;
        e.pinned = pinned;
        return e;
    }

    public Long getId() { return id; }
    public Long getSessionId() { return sessionId; }
    public String getKind() { return kind; }
    public String getRef() { return ref; }
    public String getLabel() { return label; }
    public boolean isPinned() { return pinned; }
}
