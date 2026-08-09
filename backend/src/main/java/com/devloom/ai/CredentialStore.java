package com.devloom.ai;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devloom.common.SecretCipher;

/**
 * BYO provider API keys (SPEC.md §20). Keys set in the app are stored encrypted at rest (via
 * {@link SecretCipher}); an {@code .env} value is used as a fallback when nothing is stored.
 * A key is never returned to callers — only its presence and a masked hint.
 */
@Service
public class CredentialStore {

    private final ProviderCredentialRepository repo;
    private final SecretCipher cipher;
    private final String anthropicEnv;
    private final String openaiEnv;

    public CredentialStore(ProviderCredentialRepository repo, SecretCipher cipher,
                           @Value("${ANTHROPIC_API_KEY:}") String anthropicEnv,
                           @Value("${OPENAI_API_KEY:}") String openaiEnv) {
        this.repo = repo;
        this.cipher = cipher;
        this.anthropicEnv = anthropicEnv;
        this.openaiEnv = openaiEnv;
    }

    /** Whether keys can be stored from the app (requires DEVLOOM_SECRET). */
    public boolean canStore() {
        return cipher.enabled();
    }

    /** The plaintext key for a provider: stored (decrypted) first, else the .env fallback. */
    public Optional<String> key(String provider) {
        Optional<String> stored = repo.findById(provider)
                .map(e -> {
                    try {
                        return cipher.decrypt(e.getEncKey());
                    } catch (Exception ex) {
                        return null;
                    }
                });
        if (stored.isPresent() && stored.get() != null && !stored.get().isBlank()) {
            return stored;
        }
        String env = env(provider);
        return (env != null && !env.isBlank()) ? Optional.of(env) : Optional.empty();
    }

    public boolean hasKey(String provider) {
        return key(provider).isPresent();
    }

    /** A masked hint for the UI, e.g. "sk-ant-…4f2a" — never the full key. */
    public String maskedHint(String provider) {
        return key(provider).map(CredentialStore::mask).orElse(null);
    }

    @Transactional
    public void save(String provider, String plaintextKey) {
        if (!cipher.enabled()) {
            throw new IllegalStateException("Set DEVLOOM_SECRET to store keys encrypted.");
        }
        if (plaintextKey == null || plaintextKey.isBlank()) {
            throw new IllegalArgumentException("empty key");
        }
        String enc = cipher.encrypt(plaintextKey.strip());
        repo.findById(provider).ifPresentOrElse(
                e -> { e.setEncKey(enc); repo.save(e); },
                () -> repo.save(ProviderCredentialEntity.of(provider, enc)));
    }

    @Transactional
    public void clear(String provider) {
        repo.deleteById(provider);
    }

    private String env(String provider) {
        return switch (provider) {
            case "anthropic" -> anthropicEnv;
            case "openai" -> openaiEnv;
            default -> null;
        };
    }

    private static String mask(String key) {
        if (key.length() <= 8) return "••••";
        return key.substring(0, 6) + "…" + key.substring(key.length() - 4);
    }
}
