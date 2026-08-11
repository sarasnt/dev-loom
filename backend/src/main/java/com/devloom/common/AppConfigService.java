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
    /** User-desired Ollama models (re-pulled on startup so they survive a fresh volume). */
    public static final String OLLAMA_MODELS = "ollama.models";
    /** Parent directories scanned by "Sync" to auto-add git repos, newline-separated. */
    public static final String REPO_DIRS = "repo.dirs";

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

    /** The user-desired Ollama model set (models they installed via the UI). */
    public java.util.Set<String> ollamaModels() {
        return get(OLLAMA_MODELS)
                .map(v -> new java.util.LinkedHashSet<>(java.util.Arrays.stream(v.split("\n"))
                        .map(String::trim).filter(s -> !s.isBlank()).toList()))
                .map(s -> (java.util.Set<String>) s)
                .orElseGet(java.util.LinkedHashSet::new);
    }

    public void addOllamaModel(String model) {
        if (model == null || model.isBlank()) return;
        java.util.Set<String> s = ollamaModels();
        s.add(model.trim());
        set(OLLAMA_MODELS, String.join("\n", s));
    }

    public void removeOllamaModel(String model) {
        java.util.Set<String> s = ollamaModels();
        if (s.remove(model)) set(OLLAMA_MODELS, s.isEmpty() ? null : String.join("\n", s));
    }

    /** Parent directories the user configured for repo auto-sync (order preserved). */
    public java.util.List<String> repoDirs() {
        return new java.util.ArrayList<>(lines(REPO_DIRS));
    }

    public void addRepoDir(String dir) {
        if (dir == null || dir.isBlank()) return;
        java.util.Set<String> s = lines(REPO_DIRS);
        s.add(dir.trim());
        set(REPO_DIRS, String.join("\n", s));
    }

    public void removeRepoDir(String dir) {
        java.util.Set<String> s = lines(REPO_DIRS);
        if (s.remove(dir)) set(REPO_DIRS, s.isEmpty() ? null : String.join("\n", s));
    }

    private java.util.LinkedHashSet<String> lines(String key) {
        return get(key)
                .map(v -> new java.util.LinkedHashSet<>(java.util.Arrays.stream(v.split("\n"))
                        .map(String::trim).filter(s -> !s.isBlank()).toList()))
                .orElseGet(java.util.LinkedHashSet::new);
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
