package com.devloom.integrations;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A configured, named source integration (docs/SPEC-sources.md). */
@Entity
@Table(name = "source_instance")
public class SourceInstanceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String type;          // jira | github | bitbucket | calendar | notion

    @Column(nullable = false)
    private String deployment = "cloud"; // cloud | onprem | ics

    @Column(nullable = false)
    private String name;          // display name → becomes WorkItem.source

    @Column(name = "base_url")
    private String baseUrl;

    @Column(name = "config_json", columnDefinition = "text")
    private String configJson;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected SourceInstanceEntity() {
    }

    public static SourceInstanceEntity of(String type, String deployment, String name,
                                          String baseUrl, String configJson) {
        SourceInstanceEntity e = new SourceInstanceEntity();
        e.type = type;
        e.deployment = deployment == null || deployment.isBlank() ? "cloud" : deployment;
        e.name = name;
        e.baseUrl = baseUrl;
        e.configJson = configJson;
        return e;
    }

    public void touch() { this.updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public String getType() { return type; }
    public String getDeployment() { return deployment; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
