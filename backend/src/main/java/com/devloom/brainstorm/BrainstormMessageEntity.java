package com.devloom.brainstorm;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One turn in a brainstorming session — the user's or the model's. */
@Entity
@Table(name = "brainstorm_message")
public class BrainstormMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(nullable = false)
    private int seq;

    @Column(nullable = false)
    private String role; // you | ai

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column
    private String model;

    @Column(nullable = false)
    private boolean hypothesis;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected BrainstormMessageEntity() {
    }

    public static BrainstormMessageEntity of(Long sessionId, int seq, String role, String body,
                                             String model, boolean hypothesis) {
        BrainstormMessageEntity e = new BrainstormMessageEntity();
        e.sessionId = sessionId;
        e.seq = seq;
        e.role = role;
        e.body = body == null ? "" : body;
        e.model = model;
        e.hypothesis = hypothesis;
        return e;
    }

    public Long getSessionId() { return sessionId; }
    public int getSeq() { return seq; }
    public String getRole() { return role; }
    public String getBody() { return body; }
    public String getModel() { return model; }
    public boolean isHypothesis() { return hypothesis; }
}
