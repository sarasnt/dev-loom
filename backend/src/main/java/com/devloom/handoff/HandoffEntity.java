package com.devloom.handoff;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A handoff artifact as it was handed over. The rendered markdown is stored rather than
 * re-derived: the analysis is a model call that would come back different, and the log it quotes
 * ages out of the CI provider — a handoff you reopen next week has to still say what it said.
 */
@Entity
@Table(name = "handoff")
public class HandoffEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "build_id")
    private String buildId;

    @Column(nullable = false)
    private String title;

    private String repo;
    private String branch;
    private String target;

    @Column(nullable = false, columnDefinition = "text")
    private String rendered;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected HandoffEntity() {}

    public static HandoffEntity of(String buildId, String title, String repo, String branch,
                                   String target, String rendered) {
        HandoffEntity e = new HandoffEntity();
        e.buildId = buildId;
        e.title = title;
        e.repo = repo;
        e.branch = branch;
        e.target = target;
        e.rendered = rendered;
        return e;
    }

    public Long getId() { return id; }
    public String getBuildId() { return buildId; }
    public String getTitle() { return title; }
    public String getRepo() { return repo; }
    public String getBranch() { return branch; }
    public String getTarget() { return target; }
    public String getRendered() { return rendered; }
    public Instant getCreatedAt() { return createdAt; }
}
