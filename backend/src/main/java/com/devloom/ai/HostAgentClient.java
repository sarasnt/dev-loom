package com.devloom.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Client for the DevLoom host agent (docs/SPEC-sources.md §14) — a local process the user runs
 * on the host, reached via {@code host.docker.internal}. It exposes the logged-in {@code claude}
 * CLI (subscription) and, later, git/repo operations that a Linux container can't do itself.
 * Health is cached briefly so probing is cheap.
 */
@Component
public class HostAgentClient {

    private static final Logger log = LoggerFactory.getLogger(HostAgentClient.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    private final RestClient http;
    private final HttpClient jdk;
    private final String baseUrl;
    private volatile Map<String, Object> cachedHealth;
    private volatile long cachedAt;

    public HostAgentClient(
            @Value("${devloom.host-agent.url:http://host.docker.internal:8765}") String baseUrl) {
        this.baseUrl = baseUrl;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(700);   // fail fast when the agent isn't running
        f.setReadTimeout(240_000);  // a claude generation can take a while
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(f).build();
        this.jdk = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    /**
     * Stream a Claude Code turn: POSTs to the agent's /claude/stream and invokes {@code onEvent}
     * for each ndjson event line as it arrives (parsed to a Map). Blocks until the stream ends.
     */
    public void claudeStream(String system, String prompt, String cwd, String sessionId,
                             String model, Consumer<Map<String, Object>> onEvent) {
        String body = json(Map.of(
                "system", nn(system), "prompt", nn(prompt),
                "cwd", nn(cwd), "sessionId", nn(sessionId), "model", nn(model)));
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/claude/stream"))
                .timeout(Duration.ofMinutes(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<Stream<String>> resp = jdk.send(req, HttpResponse.BodyHandlers.ofLines());
            try (Stream<String> lines = resp.body()) {
                lines.forEach(line -> {
                    if (line == null || line.isBlank()) return;
                    try {
                        onEvent.accept(JsonParserFactory.getJsonParser().parseMap(line));
                    } catch (Exception ignore) {
                        // non-JSON noise line — skip
                    }
                });
            }
        } catch (Exception e) {
            throw new IllegalStateException("host agent stream failed: " + e.getMessage(), e);
        }
    }

    private static String nn(String s) {
        return s == null ? "" : s;
    }

    /** Minimal JSON object encoder for flat string maps (escapes the values). */
    private static String json(Map<String, String> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append("\":\"").append(esc(e.getValue())).append('"');
        }
        return sb.append('}').toString();
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.toString();
    }

    /** Cached (~5s) health probe; empty when the agent is unreachable. */
    public Map<String, Object> health() {
        long now = System.currentTimeMillis();
        if (cachedHealth != null && now - cachedAt < 5_000) {
            return cachedHealth;
        }
        try {
            Map<String, Object> h = http.get().uri("/health").retrieve().body(MAP);
            cachedHealth = h == null ? Map.of() : h;
        } catch (Exception e) {
            cachedHealth = Map.of();
        }
        cachedAt = now;
        return cachedHealth;
    }

    public boolean up() {
        return Boolean.TRUE.equals(health().get("ok"));
    }

    /** Whether a logged-in claude CLI is available through the agent. */
    public boolean claudeAvailable() {
        Object c = health().get("claude");
        return c != null && !String.valueOf(c).isBlank();
    }

    /** Run a Claude Code (subscription) generation via the agent. */
    public Result claude(String system, String prompt) {
        return claude(system, prompt, null, null);
    }

    /**
     * Repo-scoped, resumable generation: {@code cwd} runs claude inside a repo (read/iterate +
     * that repo's skills), {@code sessionId} resumes a prior session. Returns the (new) session id.
     */
    public Result claude(String system, String prompt, String cwd, String sessionId) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("system", system == null ? "" : system);
        body.put("prompt", prompt == null ? "" : prompt);
        if (cwd != null) body.put("cwd", cwd);
        if (sessionId != null) body.put("sessionId", sessionId);
        Map<String, Object> resp = http.post().uri("/claude").body(body).retrieve().body(MAP);
        if (resp == null) {
            throw new IllegalStateException("no response from host agent");
        }
        if (resp.get("error") != null) {
            throw new IllegalStateException(String.valueOf(resp.get("error")));
        }
        String text = String.valueOf(resp.getOrDefault("text", ""));
        String model = String.valueOf(resp.getOrDefault("model", "claude-code"));
        Object sid = resp.get("sessionId");
        log.info("Host agent claude generate: chars={} cwd={}", text.length(), cwd != null);
        return new Result(text, model, sid == null ? null : String.valueOf(sid));
    }

    public record Result(String text, String model, String sessionId) {}

    // ---- repositories (git via the host agent) ----

    @SuppressWarnings("unchecked")
    public java.util.List<Map<String, Object>> scan(String root) {
        Map<String, Object> resp = post("/repos/scan", Map.of("root", root == null ? "" : root));
        Object repos = resp.get("repos");
        return repos instanceof java.util.List ? (java.util.List<Map<String, Object>>) repos : java.util.List.of();
    }

    public Map<String, Object> status(String path) {
        return post("/repos/status", Map.of("path", path));
    }

    /** Compare the current branch against a source branch (override or default). */
    public Map<String, Object> sourceStatus(String path, String source) {
        return post("/repos/source", Map.of("path", path, "source", source == null ? "" : source));
    }

    public Map<String, Object> setIdentity(String path, String name, String email) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("path", path);
        if (name != null) body.put("name", name);
        if (email != null) body.put("email", email);
        return post("/repos/config", body);
    }

    public Map<String, Object> pull(String path) {
        return post("/repos/pull", Map.of("path", path));
    }

    public Map<String, Object> push(String path) {
        return post("/repos/push", Map.of("path", path));
    }

    public Map<String, Object> pr(String path) {
        return post("/repos/pr", Map.of("path", path));
    }

    public Map<String, Object> browse(String path) {
        return post("/fs/list", Map.of("path", path == null ? "" : path));
    }

    public Map<String, Object> changes(String path) {
        return post("/repos/changes", Map.of("path", path));
    }

    public Map<String, Object> stage(String path, java.util.List<String> files) {
        return post("/repos/stage", Map.of("path", path, "files", files == null ? java.util.List.of() : files));
    }

    public Map<String, Object> unstage(String path, java.util.List<String> files) {
        return post("/repos/unstage", Map.of("path", path, "files", files == null ? java.util.List.of() : files));
    }

    public Map<String, Object> commit(String path, String message) {
        return post("/repos/commit", Map.of("path", path, "message", message == null ? "" : message));
    }

    public Map<String, Object> branches(String path) {
        return post("/repos/branches", Map.of("path", path));
    }

    public Map<String, Object> checkout(String path, String branch, boolean create) {
        return post("/repos/checkout", Map.of("path", path, "branch", branch, "create", create));
    }

    private Map<String, Object> post(String path, Map<String, Object> body) {
        Map<String, Object> resp = http.post().uri(path).body(body).retrieve().body(MAP);
        if (resp == null) throw new IllegalStateException("no response from host agent");
        return resp;
    }
}
