package com.devloom.api;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.devloom.ai.CostBudget;
import com.devloom.ai.OllamaLlm;

/**
 * Real model-provider state for the Settings screen (SPEC.md §20): the local models actually
 * pulled into Ollama, whether BYO keys are configured, and live cost-budget usage — no fixtures.
 */
@Service
public class ProvidersService {

    private final OllamaLlm ollama;
    private final CostBudget budget;
    private final String defaultModel;
    private final boolean anthropicKey;
    private final boolean openaiKey;

    public ProvidersService(
            OllamaLlm ollama,
            CostBudget budget,
            @Value("${devloom.ai.default-model:}") String defaultModel,
            @Value("${ANTHROPIC_API_KEY:}") String anthropicKey,
            @Value("${OPENAI_API_KEY:}") String openaiKey) {
        this.ollama = ollama;
        this.budget = budget;
        this.defaultModel = defaultModel;
        this.anthropicKey = anthropicKey != null && !anthropicKey.isBlank();
        this.openaiKey = openaiKey != null && !openaiKey.isBlank();
    }

    public Dto.Providers providers() {
        List<String> models = ollama.models();
        boolean loaded = !models.isEmpty();
        String def = loaded ? models.getFirst() : defaultModel;

        Dto.LocalProvider local = new Dto.LocalProvider("Ollama", def, models, loaded);

        Dto.KeyProvider anthropic = new Dto.KeyProvider(
                "Anthropic", "leaves for Anthropic", anthropicKey,
                anthropicKey ? Boolean.TRUE : null,
                budget.capCents("anthropic"), budget.usedCents("anthropic"), null);

        Dto.KeyProvider openai = new Dto.KeyProvider(
                "OpenAI", "leaves for OpenAI", openaiKey,
                openaiKey ? Boolean.TRUE : null,
                openaiKey ? budget.capCents("openai") : null,
                openaiKey ? budget.usedCents("openai") : null,
                "Uses the Responses API · stateless");

        return new Dto.Providers(local, anthropic, openai, false);
    }
}
