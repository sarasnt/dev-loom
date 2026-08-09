package com.devloom.api;

import java.util.List;

import org.springframework.stereotype.Service;

import com.devloom.ai.CostBudget;
import com.devloom.ai.CredentialStore;
import com.devloom.ai.HostAgentClient;
import com.devloom.ai.ModelPreference;
import com.devloom.ai.OllamaLlm;

/**
 * Real model-provider state for the Settings screen (SPEC.md §20): the local models pulled
 * into Ollama, which BYO keys are configured (encrypted, via {@link CredentialStore}), live
 * cost-budget usage, and the remote models each keyed provider offers. No fixtures.
 */
@Service
public class ProvidersService {

    // Offered remote models when a key is present (the router maps these to the right adapter).
    private static final List<String> ANTHROPIC_MODELS =
            List.of("claude-opus-4-8", "claude-sonnet-4-6", "claude-haiku-4-5-20251001");
    private static final List<String> OPENAI_MODELS = List.of("gpt-4o", "gpt-4o-mini");

    private static final String CLAUDE_CODE = "claude-code";

    private final OllamaLlm ollama;
    private final CostBudget budget;
    private final ModelPreference modelPref;
    private final CredentialStore credentials;
    private final HostAgentClient agent;

    public ProvidersService(OllamaLlm ollama, CostBudget budget, ModelPreference modelPref,
                            CredentialStore credentials, HostAgentClient agent) {
        this.ollama = ollama;
        this.budget = budget;
        this.modelPref = modelPref;
        this.credentials = credentials;
        this.agent = agent;
    }

    /** Set the active model (rail dropdown) — any pulled local model or an available remote one. */
    public void selectModel(String name) {
        if (name != null && !name.isBlank() && allModels().contains(name.strip())) {
            modelPref.set(name);
        }
    }

    public void setKey(String provider, String key) {
        if ("anthropic".equals(provider) || "openai".equals(provider)) {
            credentials.save(provider, key);
        }
    }

    public void clearKey(String provider) {
        if ("anthropic".equals(provider) || "openai".equals(provider)) {
            credentials.clear(provider);
        }
    }

    /** Every model a user could select right now — local + keyed remote + agent (subscription). */
    public List<String> allModels() {
        List<String> all = new java.util.ArrayList<>(ollama.models());
        if (agent.claudeAvailable()) all.add(CLAUDE_CODE);
        if (credentials.hasKey("anthropic")) all.addAll(ANTHROPIC_MODELS);
        if (credentials.hasKey("openai")) all.addAll(OPENAI_MODELS);
        return all;
    }

    public Dto.Providers providers() {
        List<String> models = ollama.models();
        boolean loaded = !models.isEmpty();
        String def = loaded ? models.getFirst() : "local model";
        String active = modelPref.active() != null && allModels().contains(modelPref.active())
                ? modelPref.active() : def;

        Dto.LocalProvider local = new Dto.LocalProvider("Ollama", def, active, models, loaded);

        Dto.KeyProvider anthropic = keyProvider("Anthropic", "anthropic",
                "leaves for Anthropic", ANTHROPIC_MODELS);
        Dto.KeyProvider openai = keyProvider("OpenAI", "openai",
                "leaves for OpenAI", OPENAI_MODELS);

        List<String> agentModels = agent.claudeAvailable() ? List.of(CLAUDE_CODE) : List.of();
        return new Dto.Providers(local, anthropic, openai, false, credentials.canStore(), agentModels);
    }

    private Dto.KeyProvider keyProvider(String name, String id, String boundaryLabel, List<String> models) {
        boolean has = credentials.hasKey(id);
        return new Dto.KeyProvider(
                name, id, boundaryLabel, has, has ? Boolean.TRUE : null,
                budget.capCents(id), budget.usedCents(id),
                credentials.canStore() ? null : "Set DEVLOOM_SECRET to store keys in-app.",
                models, credentials.maskedHint(id));
    }
}
