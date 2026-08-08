package com.devloom.ai;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Local model adapter via Ollama (SPEC.md §20). Local-first, free, nothing leaves the
 * machine. Activates only when an Ollama server is reachable — {@link #available()} pings
 * it with a short connect timeout so the app degrades to {@link StubLlm} instantly when
 * Ollama isn't running.
 */
@Component
public class OllamaLlm implements LlmPort {

    private static final Logger log = LoggerFactory.getLogger(OllamaLlm.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    private final String defaultModel;
    private final RestClient http;

    public OllamaLlm(
            @Value("${devloom.ai.ollama-base-url:http://localhost:11434}") String baseUrl,
            @Value("${devloom.ai.default-model:Qwen3-Coder-30B-A3B}") String defaultModel) {
        this.defaultModel = defaultModel;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(1500);    // fail fast when Ollama isn't there
        f.setReadTimeout(120_000);    // generation can take a while
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(f).build();
    }

    @Override
    public String provider() {
        return "ollama";
    }

    @Override
    public boolean available() {
        // Reachable AND has at least one pulled model.
        return !models().isEmpty();
    }

    @Override
    public LlmResult generate(LlmRequest request) {
        String model = resolveModel(request.model());
        String prompt = (request.system() == null ? "" : request.system() + "\n\n") + request.prompt();
        Map<String, Object> resp = http.post()
                .uri("/api/generate")
                .body(Map.of("model", model, "prompt", prompt, "stream", false))
                .retrieve()
                .body(MAP);
        String text = resp == null ? "" : String.valueOf(resp.getOrDefault("response", ""));
        log.info("Ollama generate: model={} chars={}", model, text.length());
        return new LlmResult(text, model, provider(), true);
    }

    /** Names of models pulled into this Ollama instance (empty if unreachable). */
    @SuppressWarnings("unchecked")
    public List<String> models() {
        try {
            Map<String, Object> tags = http.get().uri("/api/tags").retrieve().body(MAP);
            Object models = tags == null ? null : tags.get("models");
            if (models instanceof List<?> list) {
                return list.stream()
                        .map(m -> m instanceof Map ? String.valueOf(((Map<String, Object>) m).get("name")) : "")
                        .filter(n -> n != null && !n.isBlank())
                        .toList();
            }
        } catch (Exception ignore) {
            // unreachable → treated as no models available
        }
        return List.of();
    }

    /** Use the requested/default model if it's actually pulled; otherwise the first available. */
    private String resolveModel(String requested) {
        List<String> models = models();
        String preferred = (requested != null && !requested.isBlank()) ? requested : defaultModel;
        for (String m : models) {
            if (m.equalsIgnoreCase(preferred) || m.startsWith(preferred + ":")) {
                return m;
            }
        }
        return models.isEmpty() ? preferred : models.getFirst();
    }
}
