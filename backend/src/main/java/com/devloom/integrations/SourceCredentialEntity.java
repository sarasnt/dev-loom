package com.devloom.integrations;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The encrypted secret map for a source instance (docs/SPEC-sources.md). */
@Entity
@Table(name = "source_credential")
public class SourceCredentialEntity {

    @Id
    @Column(name = "instance_id")
    private Long instanceId;

    @Column(name = "enc_secret", nullable = false, columnDefinition = "text")
    private String encSecret;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected SourceCredentialEntity() {
    }

    public static SourceCredentialEntity of(Long instanceId, String encSecret) {
        SourceCredentialEntity e = new SourceCredentialEntity();
        e.instanceId = instanceId;
        e.encSecret = encSecret;
        return e;
    }

    public Long getInstanceId() { return instanceId; }
    public String getEncSecret() { return encSecret; }
    public void setEncSecret(String encSecret) { this.encSecret = encSecret; this.updatedAt = Instant.now(); }
}
