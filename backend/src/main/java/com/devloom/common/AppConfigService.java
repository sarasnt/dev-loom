package com.devloom.common;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persisted, non-secret user settings (key/value). */
@Service
public class AppConfigService {

    /** Default working directory for claude-cli terminal sessions not bound to a repo. */
    public static final String TERMINAL_WORKDIR = "terminal.workdir";
    /** Snoozed work-item ext ids (hidden from Today), newline-separated. */
    public static final String TODAY_SNOOZED = "today.snoozed";

    private final AppConfigRepository repo;

    public AppConfigService(AppConfigRepository repo) {
        this.repo = repo;
    }

    public Optional<String> get(String key) {
        return repo.findById(key)
                .map(AppConfigEntity::getValue)
                .filter(v -> v != null && !v.isBlank());
    }

    /** The set of snoozed work-item ext ids. */
    public java.util.Set<String> snoozed() {
        return get(TODAY_SNOOZED)
                .map(v -> new java.util.LinkedHashSet<>(java.util.Arrays.stream(v.split("\n"))
                        .map(String::trim).filter(s -> !s.isBlank()).toList()))
                .map(s -> (java.util.Set<String>) s)
                .orElseGet(java.util.LinkedHashSet::new);
    }

    public void snooze(String id) {
        if (id == null || id.isBlank()) return;
        java.util.Set<String> s = snoozed();
        s.add(id.trim());
        set(TODAY_SNOOZED, String.join("\n", s));
    }

    public void unsnooze(String id) {
        java.util.Set<String> s = snoozed();
        if (s.remove(id)) set(TODAY_SNOOZED, s.isEmpty() ? null : String.join("\n", s));
    }

    @Transactional
    public void set(String key, String value) {
        if (value == null || value.isBlank()) {
            repo.deleteById(key);
            return;
        }
        AppConfigEntity e = repo.findById(key).orElseGet(() -> AppConfigEntity.of(key, value));
        e.setValue(value.strip());
        repo.save(e);
    }
}
