package com.devloom.ai;

/**
 * Sampling settings per feature.
 *
 * <p>Every adapter used to build its model with no sampling parameters at all, so each ran at its
 * provider's default — 0.8 for Ollama. That is a sensible default for open-ended chat and the
 * wrong one for the work most of this app does: asked the same grounded question about the same
 * repository, a model would read the files and answer correctly on one run and wander off into a
 * directory listing on the next. Measuring a prompt change against that noise is not possible.
 *
 * <p>So: grounded features (repo analysis, build-failure diagnosis) sample near-greedily, because
 * there is one right answer and creativity is a defect. Brainstorming keeps a warm temperature,
 * because there the variety is the point — the same question should not produce the same three
 * ideas every time.
 */
public final class Sampling {

    private Sampling() {}

    /** Temperature for a feature, or null to leave the provider's default alone. */
    public static Double temperature(String feature) {
        if (feature == null) return null;
        return switch (feature) {
            case "fleet", "build-failure" -> 0.1; // one right answer; reproducibility matters
            case "brainstorm" -> 0.7;             // divergence is the feature
            default -> null;
        };
    }

    /**
     * Top-p for a feature. Paired with a low temperature it trims the long tail that produces the
     * occasional wild step — a tool call for something that was never mentioned.
     */
    public static Double topP(String feature) {
        if (feature == null) return null;
        return switch (feature) {
            case "fleet", "build-failure" -> 0.9;
            default -> null;
        };
    }
}
