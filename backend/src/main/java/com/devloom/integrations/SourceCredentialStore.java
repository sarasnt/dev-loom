package com.devloom.integrations;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devloom.common.SecretCipher;

/**
 * Per-instance source secrets (docs/SPEC-sources.md §9). Secrets set in the app are stored
 * encrypted (AES-GCM via {@link SecretCipher}). For the instances seeded from {@code .env}
 * there is no stored credential — this store falls back to the matching {@code .env} value by
 * type, so the app keeps working without {@code DEVLOOM_SECRET} (back-compat). Secrets are a
 * small string→string map serialized as {@code key\tvalue} lines.
 */
@Service
public class SourceCredentialStore {

    private final SourceCredentialRepository repo;
    private final SecretCipher cipher;

    private final String jiraPat;
    private final String githubToken;
    private final String calendarIcs;
    private final String notionToken;

    public SourceCredentialStore(
            SourceCredentialRepository repo, SecretCipher cipher,
            @Value("${devloom.jira.pat:}") String jiraPat,
            @Value("${devloom.github.token:}") String githubToken,
            @Value("${devloom.calendar.ics-url:}") String calendarIcs,
            @Value("${devloom.notion.token:}") String notionToken) {
        this.repo = repo;
        this.cipher = cipher;
        this.jiraPat = jiraPat;
        this.githubToken = githubToken;
        this.calendarIcs = calendarIcs;
        this.notionToken = notionToken;
    }

    public boolean canStore() {
        return cipher.enabled();
    }

    /** The secret map for an instance: stored (decrypted) if present, else the .env fallback. */
    public Map<String, String> secrets(SourceInstanceEntity inst) {
        Optional<SourceCredentialEntity> row = repo.findById(inst.getId());
        if (row.isPresent() && cipher.enabled()) {
            try {
                Map<String, String> m = decode(cipher.decrypt(row.get().getEncSecret()));
                if (!m.isEmpty()) return m;
            } catch (Exception ignore) {
                // fall through to env fallback
            }
        }
        return envFallback(inst.getType());
    }

    public boolean hasCredential(SourceInstanceEntity inst) {
        return !secrets(inst).isEmpty();
    }

    @Transactional
    public void save(Long instanceId, Map<String, String> secrets) {
        if (!cipher.enabled()) {
            throw new IllegalStateException("Set DEVLOOM_SECRET to store source credentials.");
        }
        String enc = cipher.encrypt(encode(secrets));
        repo.findById(instanceId).ifPresentOrElse(
                e -> { e.setEncSecret(enc); repo.save(e); },
                () -> repo.save(SourceCredentialEntity.of(instanceId, enc)));
    }

    @Transactional
    public void delete(Long instanceId) {
        repo.deleteById(instanceId);
    }

    /** Secrets from .env for the seeded instances (no stored credential). */
    private Map<String, String> envFallback(String type) {
        Map<String, String> m = new LinkedHashMap<>();
        switch (type) {
            case "jira" -> put(m, "pat", jiraPat);
            case "github" -> put(m, "token", githubToken);
            case "calendar" -> put(m, "icsUrl", calendarIcs);
            case "notion" -> put(m, "token", notionToken);
            default -> { }
        }
        return m;
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank()) m.put(k, v.strip());
    }

    private static String encode(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.getKey()).append('\t').append(e.getValue());
        }
        return sb.toString();
    }

    private static Map<String, String> decode(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        if (s == null || s.isBlank()) return m;
        for (String line : s.split("\n")) {
            int t = line.indexOf('\t');
            if (t > 0) m.put(line.substring(0, t), line.substring(t + 1));
        }
        return m;
    }
}
