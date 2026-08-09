package com.devloom.common;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Supplies the master secret used to encrypt app-stored credentials (SPEC.md §20/§27). It is
 * generated on first use and <b>persisted</b> so keys can be stored without any manual setup —
 * seeded from {@code DEVLOOM_SECRET} when that was provided (so existing setups keep working),
 * otherwise a random value. Once persisted, the DB row is the stable source of truth (changing
 * the env var later won't invalidate stored keys).
 */
@Component
public class MasterKeyProvider {

    private static final String NAME = "master";

    private final AppSecretRepository repo;
    private final String envSecret;
    private volatile String cached;

    public MasterKeyProvider(AppSecretRepository repo, @Value("${devloom.secret:}") String envSecret) {
        this.repo = repo;
        this.envSecret = envSecret;
    }

    /** The master secret — loaded, or seeded/generated and persisted on first call. */
    @Transactional
    public synchronized String secret() {
        if (cached != null) {
            return cached;
        }
        cached = repo.findById(NAME)
                .map(AppSecretEntity::getValue)
                .orElseGet(this::seedAndStore);
        return cached;
    }

    private String seedAndStore() {
        String seed = (envSecret != null && !envSecret.isBlank()) ? envSecret.strip() : random();
        try {
            repo.save(AppSecretEntity.of(NAME, seed));
        } catch (Exception raced) {
            // Another thread created it first — re-read.
            return repo.findById(NAME).map(AppSecretEntity::getValue).orElse(seed);
        }
        return seed;
    }

    private static String random() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }
}
