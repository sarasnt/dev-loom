package com.devloom.common;

import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Strips secret-looking tokens from source content and logs BEFORE they reach an LLM
 * prompt or a trace (SPEC.md §23/§26). Deterministic and cheap; runs on every log
 * excerpt and every piece of retrieved context.
 *
 * <p>Heuristics only — pattern-based plus a high-entropy long-token catch. Errs toward
 * over-redaction; false positives are safer than a leaked key.
 */
@Component
public class SecretRedactor {

    public static final String MASK = "‹SECRET REDACTED›"; // ‹SECRET REDACTED›

    // Common provider key shapes + generic bearer/env assignments.
    private static final Pattern[] PATTERNS = {
            Pattern.compile("(?i)\\b(sk|pk|rk|ghp|gho|ghs|ghu|github_pat|xox[baprs])[-_][A-Za-z0-9_-]{10,}"),
            Pattern.compile("(?i)\\b(bearer)\\s+[A-Za-z0-9._-]{12,}"),
            Pattern.compile("(?i)(api[_-]?key|secret|token|password|passwd|pwd)\\s*[:=]\\s*\"?[^\\s\"']{6,}\"?"),
            Pattern.compile("(?i)AKIA[0-9A-Z]{16}"),               // AWS access key id
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{6,}"), // JWT
            Pattern.compile("\\b[A-Za-z0-9+/]{40,}={0,2}\\b"),      // long base64-ish blob
    };

    public String redact(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String out = input;
        for (Pattern p : PATTERNS) {
            out = p.matcher(out).replaceAll(MASK);
        }
        return out;
    }

    /** True if redaction changed anything — useful for the "redacted ✓" UI badge. */
    public boolean containsSecret(String input) {
        return input != null && !input.equals(redact(input));
    }
}
