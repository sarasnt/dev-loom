package com.devloom.backup;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devloom.ai.HostAgentClient;
import com.devloom.audit.AuditService;
import com.devloom.common.AppConfigEntity;
import com.devloom.common.AppConfigRepository;
import com.devloom.common.AppConfigService;
import com.devloom.integrations.SourceInstanceEntity;
import com.devloom.integrations.SourceInstanceRepository;
import com.devloom.repos.GitRepoEntity;
import com.devloom.repos.GitRepoRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Backs DevLoom's configuration up to a git repository the user owns, and restores it.
 *
 * <p><b>Secrets never leave.</b> API keys and source credentials live encrypted in a separate
 * table and are excluded from the payload entirely — a restored install re-asks for them. What is
 * backed up: app settings, source <em>definitions</em> (type/name/base URL/non-secret config),
 * tracked repositories, and — via the host agent — the user's custom skills.
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    /** Config keys for the backup feature itself (not part of a backup payload). */
    public static final String DIR = "backup.dir";
    public static final String REMOTE = "backup.remote";
    public static final String EVERY_HOURS = "backup.everyHours";
    public static final String INCLUDE_SKILLS = "backup.includeSkills";
    public static final String PUSH = "backup.push";
    public static final String LAST_AT = "backup.lastAt";
    public static final String LAST_RESULT = "backup.lastResult";

    private final AppConfigService config;
    private final AppConfigRepository configRepo;
    private final SourceInstanceRepository sources;
    private final GitRepoRepository repos;
    private final HostAgentClient agent;
    private final AuditService audit;
    private final ObjectMapper json = new ObjectMapper();

    public BackupService(AppConfigService config, AppConfigRepository configRepo,
                         SourceInstanceRepository sources, GitRepoRepository repos,
                         HostAgentClient agent, AuditService audit) {
        this.config = config;
        this.configRepo = configRepo;
        this.sources = sources;
        this.repos = repos;
        this.agent = agent;
        this.audit = audit;
    }

    /** Settings prefixed like this are the backup's own plumbing — never part of the payload. */
    private static boolean isBackupOwnKey(String key) {
        return key != null && key.startsWith("backup.");
    }

    /** The exportable, secret-free snapshot of DevLoom's configuration. */
    public Map<String, Object> export() {
        // Deliberately no timestamp in here: git already records when each backup happened, and a
        // changing field would make every run a commit even when the configuration is identical.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", 1);

        Map<String, String> settings = new LinkedHashMap<>();
        for (AppConfigEntity e : configRepo.findAll()) {
            if (isBackupOwnKey(e.getKey())) continue;
            settings.put(e.getKey(), e.getValue());
        }
        out.put("settings", settings);

        List<Map<String, Object>> srcs = sources.findAll().stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", s.getType());
            m.put("deployment", s.getDeployment());
            m.put("name", s.getName());
            m.put("baseUrl", s.getBaseUrl());
            m.put("enabled", s.isEnabled());
            m.put("configJson", s.getConfigJson()); // non-secret fields only; credentials are separate
            return m;
        }).toList();
        out.put("sources", srcs);

        List<Map<String, Object>> rs = repos.findAll().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", r.getPath());
            m.put("name", r.getName());
            m.put("host", r.getHost());
            m.put("localOnly", r.isLocalOnly());
            return m;
        }).toList();
        out.put("repos", rs);

        out.put("note", "Secrets (API keys, source credentials) are deliberately excluded — "
                + "re-enter them after a restore.");
        return out;
    }

    /** Current backup configuration + last-run status, for the settings UI. */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dir", config.get(DIR).orElse(""));
        m.put("remote", config.get(REMOTE).orElse(""));
        m.put("everyHours", config.get(EVERY_HOURS).map(Integer::parseInt).orElse(0));
        m.put("includeSkills", config.get(INCLUDE_SKILLS).map(Boolean::parseBoolean).orElse(true));
        m.put("push", config.get(PUSH).map(Boolean::parseBoolean).orElse(true));
        m.put("lastAt", config.get(LAST_AT).orElse(null));
        m.put("lastResult", config.get(LAST_RESULT).orElse(null));
        return m;
    }

    @Transactional
    public Map<String, Object> configure(String dir, String remote, Integer everyHours,
                                         Boolean includeSkills, Boolean push) {
        if (dir != null) config.set(DIR, dir);
        if (remote != null) config.set(REMOTE, remote);
        if (everyHours != null) config.set(EVERY_HOURS, String.valueOf(everyHours));
        if (includeSkills != null) config.set(INCLUDE_SKILLS, String.valueOf(includeSkills));
        if (push != null) config.set(PUSH, String.valueOf(push));
        return status();
    }

    /** Run a backup now: write the payload into the repo, commit, and push when configured. */
    @Transactional
    public Map<String, Object> backupNow() {
        String dir = config.get(DIR).orElse("");
        if (dir.isBlank()) {
            return Map.of("ok", false, "error", "Set a backup folder first (Settings › General).");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dir", dir);
        body.put("remote", config.get(REMOTE).orElse(""));
        body.put("includeSkills", config.get(INCLUDE_SKILLS).map(Boolean::parseBoolean).orElse(true));
        body.put("push", config.get(PUSH).map(Boolean::parseBoolean).orElse(true));
        body.put("message", "DevLoom backup " + Instant.now().toString());
        Map<String, String> files = new LinkedHashMap<>();
        try {
            files.put("devloom-config.json", json.writerWithDefaultPrettyPrinter().writeValueAsString(export()));
            files.put("README.md", readme());
        } catch (Exception e) {
            return Map.of("ok", false, "error", "could not serialize config: " + e.getMessage());
        }
        body.put("files", files);

        Map<String, Object> r;
        try {
            r = agent.backupSave(body);
        } catch (Exception e) {
            return Map.of("ok", false, "error", "host agent unreachable — start it to run backups");
        }
        boolean ok = Boolean.TRUE.equals(r.get("ok"));
        String summary = !ok ? String.valueOf(r.get("error"))
                : Boolean.TRUE.equals(r.get("unchanged")) ? "no changes since the last backup"
                : "committed " + r.get("commit")
                        + (Boolean.TRUE.equals(r.get("pushed")) ? " · pushed"
                           : r.get("pushError") != null ? " · push failed: " + r.get("pushError")
                           : " · not pushed");
        config.set(LAST_AT, Instant.now().toString());
        config.set(LAST_RESULT, (ok ? "ok: " : "failed: ") + summary);
        audit.record("backup", dir, summary);
        Map<String, Object> out = new LinkedHashMap<>(r);
        out.put("summary", summary);
        return out;
    }

    /**
     * Restore from the backup folder. Settings and repo pointers are merged in; sources are
     * recreated as definitions only, so each still needs its credentials re-entered.
     */
    @Transactional
    public Map<String, Object> restore(boolean restoreSkills) {
        String dir = config.get(DIR).orElse("");
        if (dir.isBlank()) return Map.of("ok", false, "error", "no backup folder configured");
        Map<String, Object> loaded;
        try {
            loaded = agent.backupLoad(dir);
        } catch (Exception e) {
            return Map.of("ok", false, "error", "host agent unreachable");
        }
        if (!Boolean.TRUE.equals(loaded.get("ok"))) {
            return Map.of("ok", false, "error", String.valueOf(loaded.get("error")));
        }
        Object filesObj = loaded.get("files");
        String payload = filesObj instanceof Map<?, ?> m && m.get("devloom-config.json") != null
                ? String.valueOf(m.get("devloom-config.json")) : null;
        if (payload == null) return Map.of("ok", false, "error", "devloom-config.json not found in the backup");

        int settingsCount = 0, repoCount = 0, sourceCount = 0;
        try {
            Map<?, ?> data = json.readValue(payload, Map.class);
            if (data.get("settings") instanceof Map<?, ?> s) {
                for (Map.Entry<?, ?> e : s.entrySet()) {
                    String k = String.valueOf(e.getKey());
                    if (isBackupOwnKey(k)) continue; // don't clobber where the backup lives
                    config.set(k, e.getValue() == null ? null : String.valueOf(e.getValue()));
                    settingsCount++;
                }
            }
            if (data.get("repos") instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> m)) continue;
                    String path = String.valueOf(m.get("path"));
                    if (repos.findByPathIgnoreCase(path).isPresent()) continue;
                    GitRepoEntity e = GitRepoEntity.of(path, String.valueOf(m.get("name")),
                            String.valueOf(m.get("host")));
                    e.setLocalOnly(Boolean.TRUE.equals(m.get("localOnly")));
                    repos.save(e);
                    repoCount++;
                }
            }
            if (data.get("sources") instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> m)) continue;
                    String name = String.valueOf(m.get("name"));
                    if (sources.findByNameIgnoreCase(name).isPresent()) continue;
                    SourceInstanceEntity e = SourceInstanceEntity.of(String.valueOf(m.get("type")),
                            String.valueOf(m.get("deployment")), name,
                            m.get("baseUrl") == null ? null : String.valueOf(m.get("baseUrl")),
                            m.get("configJson") == null ? null : String.valueOf(m.get("configJson")));
                    e.setEnabled(Boolean.TRUE.equals(m.get("enabled")));
                    sources.save(e);
                    sourceCount++;
                }
            }
        } catch (Exception e) {
            return Map.of("ok", false, "error", "backup file is not readable: " + e.getMessage());
        }

        int skills = 0;
        if (restoreSkills) {
            try {
                Map<String, Object> s = agent.backupRestoreSkills(dir);
                skills = s.get("restored") instanceof Number n ? n.intValue() : 0;
            } catch (Exception ignore) { /* skills are best-effort */ }
        }
        audit.record("backup_restore", dir, "settings=" + settingsCount + " repos=" + repoCount
                + " sources=" + sourceCount + " skills=" + skills);
        return Map.of("ok", true, "settings", settingsCount, "repos", repoCount,
                "sources", sourceCount, "skills", skills,
                "note", sourceCount > 0
                        ? "Restored source definitions still need their credentials re-entered."
                        : "Nothing needed re-connecting.");
    }

    /** Scheduled backup: runs at most once per configured interval, and only when set up. */
    @Scheduled(fixedRate = 15 * 60 * 1000)
    public void scheduled() {
        int hours = config.get(EVERY_HOURS).map(h -> {
            try { return Integer.parseInt(h); } catch (Exception e) { return 0; }
        }).orElse(0);
        if (hours <= 0 || config.get(DIR).orElse("").isBlank()) return;
        Instant last = config.get(LAST_AT).map(s -> {
            try { return Instant.parse(s); } catch (Exception e) { return null; }
        }).orElse(null);
        if (last != null && last.plusSeconds(hours * 3600L).isAfter(Instant.now())) return;
        try {
            Map<String, Object> r = backupNow();
            log.info("Scheduled backup: {}", r.get("summary"));
        } catch (Exception e) {
            log.warn("Scheduled backup failed: {}", e.getMessage());
        }
    }

    private String readme() {
        return """
                # DevLoom backup

                Written by DevLoom. `devloom-config.json` holds your settings, source definitions
                and tracked repositories; `skills/` holds your custom Claude skills.

                **No secrets are stored here.** API keys and source credentials are encrypted in
                DevLoom's database and deliberately excluded — after restoring you re-enter them
                once, and everything else comes back.

                Restore from Settings › General › Backup.
                """;
    }
}
