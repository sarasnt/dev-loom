package com.devloom.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Symmetric encryption for secrets at rest (SPEC.md §20/§27). AES-256-GCM with a key derived
 * (SHA-256) from {@code DEVLOOM_SECRET}. Output is base64({@code iv[12] || ciphertext||tag}).
 * When no secret is configured the cipher is disabled — callers should refuse to persist
 * secrets rather than store them in the clear.
 */
@Component
public class SecretCipher {

    private static final int IV_LEN = 12;      // GCM standard nonce
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final boolean enabled;
    private final java.security.SecureRandom rng = new java.security.SecureRandom();

    public SecretCipher(@Value("${devloom.secret:}") String secret) {
        if (secret == null || secret.isBlank()) {
            this.key = null;
            this.enabled = false;
        } else {
            this.key = new SecretKeySpec(sha256(secret), "AES");
            this.enabled = true;
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LEN];
            rng.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("encrypt failed", e);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = c.doFinal(all, IV_LEN, all.length - IV_LEN);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("decrypt failed", e);
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
