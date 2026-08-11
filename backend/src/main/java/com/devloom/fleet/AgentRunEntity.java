package com.devloom.fleet;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A Fleet run: an AI task launched from DevLoom, interactive or headless background. */
@Entity
@Table(name = "agent_run")
public class AgentRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(name = "repo_path", nullable = false)
    private String repoPath;

    @Column(name = "run_dir")
    private String runDir;

    private String branch;

    @Column(nullable = false)
    private String kind;              // interactive | background

    private String permission;       // readonly | edit

    @Column(name = "allow_tests", nullable = false)
    private boolean allowTests;

    @Column(nullable = false)
    private boolean isolated;

    private String model;

    @Column(nullable = false)
    private String status;           // running|review|done|failed|canceled|active|ended

    @Column(name = "agent_run_id")
    private String agentRunId;

    @Column(name = "claude_session_id")
    private String claudeSessionId;

    @Column(name = "brainstorm_session_id")
    private Long brainstormSessionId;

    @Column(name = "result_summary", columnDefinition = "text")
    private String resultSummary;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected AgentRunEntity() {}

    public static AgentRunEntity background(String title, String repoPath, String model,
                                            String permission, boolean allowTests) {
        AgentRunEntity e = new AgentRunEntity();
        e.title = title;
        e.repoPath = repoPath;
        e.runDir = repoPath;         // Slice 2 runs in the main checkout; isolation lands in Slice 4
        e.kind = "background";
        e.permission = permission;
        e.allowTests = allowTests;
        e.isolated = false;
        e.model = model;
        e.status = "running";
        e.startedAt = Instant.now();
        return e;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getRepoPath() { return repoPath; }
    public String getRunDir() { return runDir; }
    public void setRunDir(String d) { this.runDir = d; }
    public String getBranch() { return branch; }
    public void setBranch(String b) { this.branch = b; }
    public String getKind() { return kind; }
    public String getPermission() { return permission; }
    public boolean isAllowTests() { return allowTests; }
    public boolean isIsolated() { return isolated; }
    public String getModel() { return model; }
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }
    public String getAgentRunId() { return agentRunId; }
    public void setAgentRunId(String v) { this.agentRunId = v; }
    public String getClaudeSessionId() { return claudeSessionId; }
    public void setClaudeSessionId(String v) { this.claudeSessionId = v; }
    public Long getBrainstormSessionId() { return brainstormSessionId; }
    public String getResultSummary() { return resultSummary; }
    public void setResultSummary(String v) { this.resultSummary = v; }
    public String getError() { return error; }
    public void setError(String v) { this.error = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant t) { this.finishedAt = t; }
}
