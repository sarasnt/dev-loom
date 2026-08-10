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

    @Column(name = "repo_path")
    private String repoPath;

    @Column(name = "claude_session_id")
    private String claudeSessionId;

    // True once this session has been opened in claude-cli mode: its conversation lives in the
    // terminal (Claude Code's transcript), not brainstorm_message, so the UI keeps it a terminal.
    @Column(name = "cli_mode", nullable = false)
    private boolean cliMode = false;

    protected BrainstormSessionEntity() {
    }

    public static BrainstormSessionEntity create(String title) {
        return create(title, null);
    }

    public static BrainstormSessionEntity create(String title, String repoPath) {
        BrainstormSessionEntity e = new BrainstormSessionEntity();
        e.title = (title == null || title.isBlank()) ? "New brainstorm" : title.strip();
        e.repoPath = (repoPath == null || repoPath.isBlank()) ? null : repoPath.strip();
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
    public String getRepoPath() { return repoPath; }
    public String getClaudeSessionId() { return claudeSessionId; }
    public void setClaudeSessionId(String id) { this.claudeSessionId = id; }
    public boolean isCliMode() { return cliMode; }
    public void setCliMode(boolean cliMode) { this.cliMode = cliMode; }
}
