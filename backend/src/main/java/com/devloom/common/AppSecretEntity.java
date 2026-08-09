package com.devloom.common;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A persisted app secret (e.g. the master encryption key). */
@Entity
@Table(name = "app_secret")
public class AppSecretEntity {

    @Id
    private String name;

    @Column(nullable = false, columnDefinition = "text")
    private String value;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected AppSecretEntity() {
    }

    public static AppSecretEntity of(String name, String value) {
        AppSecretEntity e = new AppSecretEntity();
        e.name = name;
        e.value = value;
        return e;
    }

    public String getName() { return name; }
    public String getValue() { return value; }
}
