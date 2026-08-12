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
     * Generate, reporting progress as it happens. Adapters that can stream emit text as the model
     * produces it; the rest simply don't call the sink until they're done, so a caller can always
     * use this and a provider that can't stream degrades to arriving all at once.
     */
    default LlmResult generate(LlmRequest request, StreamSink sink) {
        return generate(request);
    }

    /**
     * Where a streaming turn reports to.
     *
     * <p>{@link #status} exists because a tool-using turn is not a stream of answer text: the model
     * thinks aloud, calls a tool, reads the result and only then answers. Any text before a tool
     * call is working-out, not the reply — so it is superseded by a status line rather than left
     * in the answer.
     */
    interface StreamSink {
        /** A chunk of the reply as the model produces it. */
        void delta(String text);

        /** What the run is doing now (e.g. reading a file). Replaces any text streamed so far. */
        void status(String activity);

        StreamSink NONE = new StreamSink() {
            public void delta(String text) {}
            public void status(String activity) {}
        };
    }

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
