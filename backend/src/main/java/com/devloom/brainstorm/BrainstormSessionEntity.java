package com.devloom.brainstorm;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A persisted brainstorming session (SPEC.md §Brainstorming). */
@Entity
@Table(name = "brainstorm_session")
public class BrainstormSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String visibility = "personal";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected BrainstormSessionEntity() {
    }

    public static BrainstormSessionEntity create(String title) {
        BrainstormSessionEntity e = new BrainstormSessionEntity();
        e.title = (title == null || title.isBlank()) ? "New brainstorm" : title.strip();
        return e;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getVisibility() { return visibility; }
    public Instant getUpdatedAt() { return updatedAt; }
}
