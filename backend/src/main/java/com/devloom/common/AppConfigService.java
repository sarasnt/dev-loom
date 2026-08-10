package com.devloom.common;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persisted, non-secret user settings (key/value). */
@Service
public class AppConfigService {

    /** Default working directory for claude-cli terminal sessions not bound to a repo. */
    public static final String TERMINAL_WORKDIR = "terminal.workdir";

    private final AppConfigRepository repo;

    public AppConfigService(AppConfigRepository repo) {
        this.repo = repo;
    }

    public Optional<String> get(String key) {
        return repo.findById(key)
                .map(AppConfigEntity::getValue)
                .filter(v -> v != null && !v.isBlank());
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
