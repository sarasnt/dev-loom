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

    /** Generate a completion. Callers pass already-redacted, budgeted context. */
    LlmResult generate(LlmRequest request);

    record LlmRequest(String feature, String system, String prompt, String model) {}

    record LlmResult(String text, String model, String provider, boolean hypothesis) {}
}
