package com.devloom.repos;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A local git repository DevLoom manages via the host agent. */
@Entity
@Table(name = "git_repo")
public class GitRepoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String path;

    @Column(nullable = false)
    private String name;

    @Column
    private String host;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    // Local-only: this repo may only be brainstormed with local models — never a remote one.
    @Column(name = "local_only", nullable = false)
    private boolean localOnly = false;

    protected GitRepoEntity() {
    }

    public static GitRepoEntity of(String path, String name, String host) {
        GitRepoEntity e = new GitRepoEntity();
        e.path = path;
        e.name = name;
        e.host = host;
        return e;
    }

    public Long getId() { return id; }
    public String getPath() { return path; }
    public String getName() { return name; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public boolean isLocalOnly() { return localOnly; }
    public void setLocalOnly(boolean localOnly) { this.localOnly = localOnly; }
}
