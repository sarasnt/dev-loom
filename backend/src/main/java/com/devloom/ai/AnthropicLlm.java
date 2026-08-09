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
 * Anthropic Messages API adapter (SPEC.md §20) — a paid, remote provider. Active only when an
 * Anthropic key is configured (app-stored or env). Used for {@code claude-*} models; data
 * leaves the machine, so the {@link LlmRouter} gates egress and records cost.
 */
@Component
public class AnthropicLlm implements LlmPort {

    private static final Logger log = LoggerFactory.getLogger(AnthropicLlm.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};
    private static final String DEFAULT_MODEL = "claude-sonnet-4-6";

    private final CredentialStore credentials;
    private final RestClient http;

    public AnthropicLlm(CredentialStore credentials,
                        @Value("${devloom.ai.anthropic-base-url:https://api.anthropic.com}") String baseUrl) {
        this.credentials = credentials;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5000);
        f.setReadTimeout(120_000);
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(f).build();
    }

    @Override
    public String provider() {
        return "anthropic";
    }

    @Override
    public boolean available() {
        return credentials.hasKey("anthropic");
    }

    @Override
    public String modelLabel() {
        return DEFAULT_MODEL;
    }

    @Override
    public LlmResult generate(LlmRequest request) {
        String key = credentials.key("anthropic").orElseThrow(() -> new IllegalStateException("no anthropic key"));
        String model = (request.model() == null || request.model().isBlank()) ? DEFAULT_MODEL : request.model();
        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", 1024,
                "system", request.system() == null ? "" : request.system(),
                "messages", List.of(Map.of("role", "user", "content", request.prompt())));
        Map<String, Object> resp = http.post()
                .uri("/v1/messages")
                .header("x-api-key", key)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .body(body)
                .retrieve()
                .body(MAP);
        String text = extractText(resp);
        log.info("Anthropic generate: model={} chars={}", model, text.length());
        return new LlmResult(text, model, provider(), true);
    }

    @SuppressWarnings("unchecked")
    private static String extractText(Map<String, Object> resp) {
        if (resp == null) return "";
        Object content = resp.get("content");
        if (content instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> m) {
            Object t = ((Map<String, Object>) m).get("text");
            return t == null ? "" : String.valueOf(t);
        }
        return "";
    }
}
