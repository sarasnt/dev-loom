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
 * OpenAI Chat Completions adapter (SPEC.md §20) — a paid, remote provider. Active only when an
 * OpenAI key is configured. Used for {@code gpt-*}/{@code o*} models; egress is gated and cost
 * recorded by the {@link LlmRouter}.
 */
@Component
public class OpenAiLlm implements LlmPort {

    private static final Logger log = LoggerFactory.getLogger(OpenAiLlm.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};
    private static final String DEFAULT_MODEL = "gpt-4o-mini";

    private final CredentialStore credentials;
    private final RestClient http;

    public OpenAiLlm(CredentialStore credentials,
                     @Value("${devloom.ai.openai-base-url:https://api.openai.com}") String baseUrl) {
        this.credentials = credentials;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5000);
        f.setReadTimeout(120_000);
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(f).build();
    }

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    public boolean available() {
        return credentials.hasKey("openai");
    }

    @Override
    public String modelLabel() {
        return DEFAULT_MODEL;
    }

    @Override
    public LlmResult generate(LlmRequest request) {
        String key = credentials.key("openai").orElseThrow(() -> new IllegalStateException("no openai key"));
        String model = (request.model() == null || request.model().isBlank()) ? DEFAULT_MODEL : request.model();
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", request.system() == null ? "" : request.system()),
                        Map.of("role", "user", "content", request.prompt())));
        Map<String, Object> resp = http.post()
                .uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + key)
                .header("content-type", "application/json")
                .body(body)
                .retrieve()
                .body(MAP);
        String text = extractText(resp);
        log.info("OpenAI generate: model={} chars={}", model, text.length());
        return new LlmResult(text, model, provider(), true);
    }

    @SuppressWarnings("unchecked")
    private static String extractText(Map<String, Object> resp) {
        if (resp == null) return "";
        Object choices = resp.get("choices");
        if (choices instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> c) {
            Object msg = ((Map<String, Object>) c).get("message");
            if (msg instanceof Map<?, ?> m) {
                Object content = ((Map<String, Object>) m).get("content");
                return content == null ? "" : String.valueOf(content);
            }
        }
        return "";
    }
}
