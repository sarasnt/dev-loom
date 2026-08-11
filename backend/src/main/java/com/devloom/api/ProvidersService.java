package com.devloom.api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.devloom.ai.CostBudget;
import com.devloom.ai.CredentialStore;
import com.devloom.ai.HostAgentClient;
import com.devloom.ai.ModelPreference;
import com.devloom.ai.OllamaLlm;

/**
 * Real model-provider state for the Settings screen (SPEC.md §20): the local models pulled
 * into Ollama, which BYO keys are configured (encrypted, via {@link CredentialStore}), live
 * cost-budget usage, and the remote models each keyed provider offers. When a key is present
 * the offered models are fetched LIVE from the provider's /models API (so you see everything
 * your account can use), cached until the key changes, with a static fallback if the call fails.
 */
@Service
public class ProvidersService {

    private static final Logger log = LoggerFactory.getLogger(ProvidersService.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    // Fallbacks used only if the provider's /models call fails.
    private static final List<String> ANTHROPIC_FALLBACK =
            List.of("claude-opus-4-8", "claude-sonnet-4-6", "claude-haiku-4-5-20251001");
    private static final List<String> OPENAI_FALLBACK = List.of("gpt-4o", "gpt-4o-mini");

    // Interactive Claude Code in an embedded terminal (full TUI via the host agent's PTY).
    // The old programmatic "claude-code" chat is retired — claude-cli supersedes it.
    private static final String CLAUDE_CLI = "claude-cli";

    private final OllamaLlm ollama;
    private final CostBudget budget;
    private final ModelPreference modelPref;
    private final CredentialStore credentials;
    private final HostAgentClient agent;
    private final String anthropicBase;
    private final String openaiBase;
    private final RestClient http;
    // Cached model lists per provider, cleared when the key changes.
    private final Map<String, List<String>> modelCache = new ConcurrentHashMap<>();

    public ProvidersService(OllamaLlm ollama, CostBudget budget, ModelPreference modelPref,
                            CredentialStore credentials, HostAgentClient agent,
                            @Value("${devloom.ai.anthropic-base-url:https://api.anthropic.com}") String anthropicBase,
                            @Value("${devloom.ai.openai-base-url:https://api.openai.com}") String openaiBase) {
        this.ollama = ollama;
        this.budget = budget;
        this.modelPref = modelPref;
        this.credentials = credentials;
        this.agent = agent;
        this.anthropicBase = anthropicBase;
        this.openaiBase = openaiBase;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(4000);
        f.setReadTimeout(8000);
        this.http = RestClient.builder().requestFactory(f).build();
    }

    /** Set the active model — any pulled local model or an available remote one. */
    public void selectModel(String name) {
        if (name != null && !name.isBlank() && allModels().contains(name.strip())) {
            modelPref.set(name);
        }
    }

    public void setKey(String provider, String key) {
        if ("anthropic".equals(provider) || "openai".equals(provider)) {
            credentials.save(provider, key);
            modelCache.remove(provider); // re-fetch with the new key
        }
    }

    public void clearKey(String provider) {
        if ("anthropic".equals(provider) || "openai".equals(provider)) {
            credentials.clear(provider);
            modelCache.remove(provider);
        }
    }

    /** Every model a user could select right now — local + keyed remote + agent (subscription). */
    public List<String> allModels() {
        List<String> all = new java.util.ArrayList<>(ollama.models());
        if (agent.claudeAvailable()) all.add(CLAUDE_CLI);
        if (credentials.hasKey("anthropic")) all.addAll(remoteModels("anthropic"));
        if (credentials.hasKey("openai")) all.addAll(remoteModels("openai"));
        return all;
    }

    public Dto.Providers providers() {
        List<String> models = ollama.models();
        boolean loaded = !models.isEmpty();
        String def = loaded ? models.getFirst() : "local model";
        String active = modelPref.active() != null && allModels().contains(modelPref.active())
                ? modelPref.active() : def;

        Dto.LocalProvider local = new Dto.LocalProvider("Ollama", def, active, models, loaded);

        Dto.KeyProvider anthropic = keyProvider("Anthropic", "anthropic", "leaves for Anthropic");
        Dto.KeyProvider openai = keyProvider("OpenAI", "openai", "leaves for OpenAI");

        List<String> agentModels = agent.claudeAvailable() ? List.of(CLAUDE_CLI) : List.of();
        return new Dto.Providers(local, anthropic, openai, false, credentials.canStore(), agentModels);
    }

    private Dto.KeyProvider keyProvider(String name, String id, String boundaryLabel) {
        boolean has = credentials.hasKey(id);
        List<String> models = has ? remoteModels(id)
                : ("anthropic".equals(id) ? ANTHROPIC_FALLBACK : OPENAI_FALLBACK);
        return new Dto.KeyProvider(
                name, id, boundaryLabel, has, has ? Boolean.TRUE : null,
                budget.capCents(id), budget.usedCents(id),
                credentials.canStore() ? null : "Set DEVLOOM_SECRET to store keys in-app.",
                models, credentials.maskedHint(id));
    }

    /** Models the keyed provider offers, fetched live from its /models API (cached). */
    private List<String> remoteModels(String provider) {
        List<String> cached = modelCache.get(provider);
        if (cached != null) return cached;
        List<String> fetched = fetchRemoteModels(provider);
        if (fetched.isEmpty()) {
            return "anthropic".equals(provider) ? ANTHROPIC_FALLBACK : OPENAI_FALLBACK;
        }
        modelCache.put(provider, fetched);
        return fetched;
    }

    @SuppressWarnings("unchecked")
    private List<String> fetchRemoteModels(String provider) {
        try {
            String key = credentials.key(provider).orElse(null);
            if (key == null || key.isBlank()) return List.of();
            RestClient.RequestHeadersSpec<?> req;
            if ("anthropic".equals(provider)) {
                req = http.get().uri(anthropicBase + "/v1/models?limit=1000")
                        .header("x-api-key", key)
                        .header("anthropic-version", "2023-06-01");
            } else {
                req = http.get().uri(openaiBase + "/v1/models")
                        .header("Authorization", "Bearer " + key);
            }
            Map<String, Object> resp = req.header("Accept", "application/json").retrieve().body(MAP);
            Object data = resp == null ? null : resp.get("data");
            if (!(data instanceof List<?> list)) return List.of();
            List<String> ids = new java.util.ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Object id = ((Map<String, Object>) m).get("id");
                    if (id != null) ids.add(String.valueOf(id));
                }
            }
            List<String> filtered = "openai".equals(provider) ? filterOpenAiChat(ids) : ids;
            filtered.sort(java.util.Comparator.reverseOrder()); // newest-ish first
            log.info("Fetched {} {} models from its API", filtered.size(), provider);
            return filtered;
        } catch (Exception e) {
            log.warn("Could not fetch {} models ({}) — using fallback list", provider, e.getMessage());
            return List.of();
        }
    }

    /** Keep OpenAI chat/reasoning models (gpt-*, o1/o3/o4), drop embeddings/audio/image/etc. */
    private static List<String> filterOpenAiChat(List<String> ids) {
        List<String> out = new java.util.ArrayList<>();
        for (String id : ids) {
            String s = id.toLowerCase();
            boolean chat = s.startsWith("gpt-") || s.startsWith("chatgpt")
                    || s.matches("o[0-9].*");
            boolean junk = s.contains("embedding") || s.contains("whisper") || s.contains("tts")
                    || s.contains("audio") || s.contains("image") || s.contains("dall-e")
                    || s.contains("moderation") || s.contains("realtime") || s.contains("transcribe")
                    || s.contains("search") || s.contains("instruct");
            if (chat && !junk) out.add(id);
        }
        return out;
    }
}
