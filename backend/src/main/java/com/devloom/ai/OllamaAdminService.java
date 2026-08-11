package com.devloom.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.devloom.common.AppConfigService;

/**
 * Manage the local Ollama model set (SPEC.md §20): list installed models with sizes, pull a new
 * one (streaming progress), and remove one. The user's chosen set is persisted in app_config and
 * reconciled on startup — so models installed via the UI survive a fresh Ollama volume without
 * touching docker-compose. (The compose DEVLOOM_OLLAMA_MODELS is just the initial seed.)
 */
@Service
public class OllamaAdminService {

    private static final Logger log = LoggerFactory.getLogger(OllamaAdminService.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    private final String baseUrl;
    private final RestClient http;
    private final HttpClient stream;
    private final AppConfigService config;

    public OllamaAdminService(
            @Value("${devloom.ai.ollama-base-url:http://localhost:11434}") String baseUrl,
            AppConfigService config) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.config = config;
        this.http = RestClient.builder().baseUrl(this.baseUrl).build();
        this.stream = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3)).build();
    }

    /** Installed models with size in bytes: [{name, size}]. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> installed() {
        try {
            Map<String, Object> tags = http.get().uri("/api/tags").retrieve().body(MAP);
            Object models = tags == null ? null : tags.get("models");
            List<Map<String, Object>> out = new ArrayList<>();
            if (models instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        Map<String, Object> e = new LinkedHashMap<>();
                        e.put("name", ((Map<String, Object>) m).get("name"));
                        e.put("size", ((Map<String, Object>) m).get("size"));
                        out.add(e);
                    }
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("Ollama /api/tags failed: {}", e.getMessage());
            return List.of();
        }
    }

    public List<String> installedNames() {
        return installed().stream().map(m -> String.valueOf(m.get("name"))).toList();
    }

    /** Remove a model from Ollama and from the desired set. */
    public boolean delete(String model) {
        try {
            http.method(org.springframework.http.HttpMethod.DELETE).uri("/api/delete")
                    .body(Map.of("name", model)).retrieve().toBodilessEntity();
            config.removeOllamaModel(model);
            return true;
        } catch (Exception e) {
            log.warn("Ollama delete {} failed: {}", model, e.getMessage());
            return false;
        }
    }

    /**
     * Pull a model, relaying each progress line ({status,total,completed}) to {@code onLine}.
     * Records the model in the desired set on success. Blocking — run on a worker thread.
     */
    public void pull(String model, Consumer<Map<String, Object>> onLine) throws Exception {
        String body = "{\"name\":\"" + model.replace("\"", "") + "\",\"stream\":true}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/pull"))
                .timeout(Duration.ofMinutes(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<java.util.stream.Stream<String>> resp =
                stream.send(req, HttpResponse.BodyHandlers.ofLines());
        boolean ok = false;
        try (var lines = resp.body()) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (line == null || line.isBlank()) continue;
                Map<String, Object> parsed = parse(line);
                onLine.accept(parsed);
                if ("success".equals(String.valueOf(parsed.get("status")))) ok = true;
                if (parsed.get("error") != null) throw new IllegalStateException(String.valueOf(parsed.get("error")));
            }
        }
        if (ok) config.addOllamaModel(model);
    }

    /** On boot, pull any user-desired model that isn't present (survives a fresh volume). */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcile() {
        var desired = config.ollamaModels();
        if (desired.isEmpty()) return;
        Thread.ofVirtual().name("ollama-reconcile").start(() -> {
            try {
                var present = new java.util.HashSet<>(installedNames());
                for (String m : desired) {
                    if (present.stream().noneMatch(p -> p.equals(m) || p.startsWith(m + ":"))) {
                        log.info("Reconcile: pulling desired model {}", m);
                        try { pull(m, l -> {}); } catch (Exception e) { log.warn("reconcile pull {} failed: {}", m, e.getMessage()); }
                    }
                }
            } catch (Exception e) {
                log.warn("Ollama reconcile failed: {}", e.getMessage());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        try {
            return (Map<String, Object>) org.springframework.boot.json.JsonParserFactory.getJsonParser().parseMap(json);
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", json);
            return m;
        }
    }
}
