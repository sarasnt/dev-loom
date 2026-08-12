package com.devloom.ai;

/**
 * DevLoom's own LLM abstraction (SPEC.md §20). Product-domain code depends only on this
 * interface — never on Spring AI or a provider SDK. Adapters: {@link StubLlm} (default,
 * deterministic, offline) and a future OllamaLlm / Anthropic / OpenAI adapter.
 */
public interface LlmPort {

    /** @return which provider this adapter represents, e.g. "stub", "ollama", "anthropic". */
    String provider();

    /** Whether this adapter is currently usable (e.g. Ollama reachable). */
    boolean available();

    /** Human label for the model this adapter would use right now (defaults to the provider). */
    default String modelLabel() {
        return provider();
    }

    /** Generate a completion. Callers pass already-redacted, budgeted context. */
    LlmResult generate(LlmRequest request);

    /**
     * @param repoPath the repository this request is about, or null. Present, it turns on the
     *                 built-in repo tools ({@link RepoTools}) for the turn — which is what lets a
     *                 model actually read the code it is being asked about.
     */
    record LlmRequest(String feature, String system, String prompt, String model, String repoPath) {

        /** For features with no repository in play (brainstorming a topic, a build log). */
        public LlmRequest(String feature, String system, String prompt, String model) {
            this(feature, system, prompt, model, null);
        }
    }

    record LlmResult(String text, String model, String provider, boolean hypothesis) {}
}
