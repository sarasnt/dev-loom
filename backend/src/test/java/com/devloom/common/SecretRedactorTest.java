package com.devloom.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecretRedactorTest {

    private final SecretRedactor redactor = new SecretRedactor();

    @Test
    void redactsGithubTokenInLogLine() {
        String raw = "env: GITHUB_TOKEN=ghp_ABCDEF1234567890abcdefXYZ987654";
        String out = redactor.redact(raw);
        assertThat(out).contains(SecretRedactor.MASK);
        assertThat(out).doesNotContain("ghp_ABCDEF1234567890");
    }

    @Test
    void redactsKeyValueSecretAssignments() {
        assertThat(redactor.redact("api_key: s3cr3tValue123")).contains(SecretRedactor.MASK);
        assertThat(redactor.redact("password=hunter2xyz")).contains(SecretRedactor.MASK);
    }

    @Test
    void leavesOrdinaryLogLinesUntouched() {
        String benign = "expected: 90.00 but was: 100.00 at PricingServiceTest(:211)";
        assertThat(redactor.redact(benign)).isEqualTo(benign);
        assertThat(redactor.containsSecret(benign)).isFalse();
    }

    @Test
    void handlesNullAndEmpty() {
        assertThat(redactor.redact(null)).isNull();
        assertThat(redactor.redact("")).isEmpty();
    }
}
