package com.devloom.ai;

import org.springframework.stereotype.Component;

/**
 * Default offline adapter: deterministic canned reasoning so the whole product works with
 * zero external dependencies and nothing leaving the machine. Swapped for a live Ollama /
 * BYO-key adapter when one is configured and reachable (SPEC.md §20). Marks output as a
 * hypothesis so the UI labels it as reasoning, not fact.
 */
@Component
public class StubLlm implements LlmPort {

    @Override
    public String provider() {
        return "stub";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public LlmResult generate(LlmRequest request) {
        String text = "[offline reasoning] " + request.feature()
                + ": no live model configured; showing a deterministic explanation. "
                + "Configure a local Ollama model or a BYO key to enable real analysis.";
        return new LlmResult(text, "stub-deterministic", provider(), true);
    }
}
