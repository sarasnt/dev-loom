package com.devloom.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * System prompts, editable without a rebuild.
 *
 * <p>Every prompt in this app was a compiled-in constant, so tuning one cost a Maven compile and a
 * container rebuild — around two minutes to find out whether a sentence helped. That cost shapes
 * the work: you try the changes you're confident about rather than the ones worth testing.
 *
 * <p>Prompts now come from Langfuse's prompt store when it is configured, and from the constant in
 * the code when it isn't. The constant stays the source of truth for what ships: an unreachable
 * Langfuse, a missing prompt or a bad response all fall back to it silently, because a prompt
 * server being down must never change how the app behaves.
 *
 * <p>What this does NOT give you is a way to try a prompt without running the app — Langfuse's
 * self-hosted playground can't execute against local Ollama models. Measuring a prompt change is
 * still {@code eval/}'s job; this only removes the rebuild between edit and measurement.
 */
@Component
public class PromptLibrary {

    private static final Logger log = LoggerFactory.getLogger(PromptLibrary.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Long enough that a run doesn't re-fetch per call, short enough to see an edit quickly. */
    private static final long CACHE_MS = 30_000;

    private final boolean enabled;
    private final String baseUrl;
    private final String authHeader;
    private final HttpClient http;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(String text, long at) {}

    public PromptLibrary(
            @Value("${devloom.langfuse.enabled:false}") boolean enabled,
            @Value("${devloom.langfuse.host:http://localhost:3000}") String host,
            @Value("${devloom.langfuse.public-key:}") String publicKey,
            @Value("${devloom.langfuse.secret-key:}") String secretKey) {
        boolean haveKeys = publicKey != null && !publicKey.isBlank()
                && secretKey != null && !secretKey.isBlank();
        this.enabled = enabled && haveKeys;
        String h = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
        this.baseUrl = h;
        this.authHeader = haveKeys
                ? "Basic " + Base64.getEncoder().encodeToString(
                        (publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8))
                : null;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)   // same reason as LangfuseTracer
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    /**
     * The prompt registered as {@code name}, or {@code fallback} if there isn't one.
     *
     * @param name     dotted, e.g. {@code devloom/fleet-analysis} — this is what you edit in Langfuse
     * @param fallback the compiled-in text, which is also what gets seeded on first use
     */
    public String get(String name, String fallback) {
        if (!enabled) return fallback;
        Cached c = cache.get(name);
        long now = System.currentTimeMillis();
        if (c != null && now - c.at() < CACHE_MS) {
            return c.text() == null ? fallback : c.text();
        }
        String fetched = fetch(name);
        cache.put(name, new Cached(fetched, now));
        return fetched == null ? fallback : fetched;
    }

    private String fetch(String name) {
        try {
            String url = baseUrl + "/api/public/v2/prompts/"
                    + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8) + "?label=production";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .header("Authorization", authHeader)
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) return null;          // not registered yet — use the code
            if (resp.statusCode() >= 300) {
                log.debug("Prompt fetch {} → HTTP {}", name, resp.statusCode());
                return null;
            }
            JsonNode body = JSON.readTree(resp.body());
            JsonNode prompt = body.path("prompt");
            return prompt.isTextual() && !prompt.asText().isBlank() ? prompt.asText() : null;
        } catch (Exception e) {
            log.debug("Prompt fetch {} unavailable: {}", name, e.toString());
            return null;
        }
    }

    /**
     * Register the compiled-in text under {@code name} if nothing is registered yet, so the prompts
     * appear in Langfuse ready to edit rather than having to be pasted in by hand. Never overwrites
     * an existing one — your edits are the point.
     */
    public void seed(String name, String text) {
        if (!enabled || fetch(name) != null) return;
        try {
            Map<String, Object> payload = Map.of(
                    "name", name, "prompt", text, "type", "text",
                    "labels", java.util.List.of("production"));
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/public/v2/prompts"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", authHeader)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 300) log.info("Registered prompt '{}' in Langfuse", name);
        } catch (Exception e) {
            log.debug("Could not register prompt {}: {}", name, e.toString());
        }
    }
}
