package com.devloom.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * Symmetric encryption for secrets at rest (SPEC.md §20/§27). AES-256-GCM with a key derived
 * (SHA-256) from the master secret ({@link MasterKeyProvider}) — which is auto-generated and
 * persisted on first use, so encryption is always available (no manual setup needed). Output is
 * base64({@code iv[12] || ciphertext||tag}).
 */
@Component
public class SecretCipher {

    private static final int IV_LEN = 12;      // GCM standard nonce
    private static final int TAG_BITS = 128;

    private final MasterKeyProvider master;
    private volatile SecretKeySpec key;
    private final java.security.SecureRandom rng = new java.security.SecureRandom();

    public SecretCipher(MasterKeyProvider master) {
        this.master = master;
    }

    /** Always available — the master secret is generated + persisted on demand. */
    public boolean enabled() {
        return true;
    }

    private SecretKeySpec key() {
        SecretKeySpec k = key;
        if (k == null) {
            synchronized (this) {
                if (key == null) {
                    key = new SecretKeySpec(sha256(master.secret()), "AES");
                }
                k = key;
            }
        }
        return k;
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LEN];
            rng.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
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
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
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
