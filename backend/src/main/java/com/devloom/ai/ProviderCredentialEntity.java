package com.devloom.ai;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** An encrypted BYO provider API key (SPEC.md §20). One row per provider. */
@Entity
@Table(name = "provider_credential")
public class ProviderCredentialEntity {

    @Id
    private String provider; // anthropic | openai

    @Column(name = "enc_key", nullable = false, columnDefinition = "text")
    private String encKey;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ProviderCredentialEntity() {
    }

    public static ProviderCredentialEntity of(String provider, String encKey) {
        ProviderCredentialEntity e = new ProviderCredentialEntity();
        e.provider = provider;
        e.encKey = encKey;
        e.updatedAt = Instant.now();
        return e;
    }

    public String getProvider() { return provider; }
    public String getEncKey() { return encKey; }
    public void setEncKey(String encKey) { this.encKey = encKey; this.updatedAt = Instant.now(); }
}
