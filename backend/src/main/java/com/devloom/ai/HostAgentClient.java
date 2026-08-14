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

    /** All worktrees of a repo (path, branch, HEAD, flags). */
    public Map<String, Object> worktrees(String path) {
        return post("/repos/worktrees", Map.of("path", path));
    }

    /** The repo's tracked files (git ls-files), capped at {@code limit}. */
    public Map<String, Object> files(String path, int limit) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("path", path);
        body.put("limit", limit);
        return post("/repos/files", body);
    }

    /** One file's contents, refused by the agent if the path escapes the repo or enters .git. */
    public Map<String, Object> readFile(String path, String file, int maxBytes) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("path", path);
        body.put("file", file);
        body.put("maxBytes", maxBytes);
        return post("/repos/read", body);
    }

    /** Write one file inside a repo (edit runs only — the agent refuses paths outside it). */
    public Map<String, Object> writeFile(String path, String file, String content) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("path", path);
        body.put("file", file);
        body.put("content", content);
        return post("/repos/write", body);
    }

    /**
     * Run the repository's own check (its test script, else its build) and report the outcome.
     *
     * <p>The command comes from the repo's manifest, never from the model, and this only runs when
     * the user allowed tests on an edit run — it executes the repository's code on their machine.
     */
    public Map<String, Object> verify(String path, int timeoutMs) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("path", path);
        body.put("timeoutMs", timeoutMs);
        return post("/repos/verify", body);
    }

    /** A pull request's diff via the logged-in gh CLI. */
    public Map<String, Object> prDiff(String path, String number, int maxBytes) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("path", path);
        body.put("number", number);
        body.put("maxBytes", maxBytes);
        return post("/repos/pr-diff", body);
    }

    /** Literal search across the repo's tracked files (git grep). */
    public Map<String, Object> grep(String path, String query, String glob, int max) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("path", path);
        body.put("query", query);
        if (glob != null && !glob.isBlank()) body.put("glob", glob);
        body.put("max", max);
        return post("/repos/grep", body);
    }

    /** Current-branch commit log; uniqueOnly limits to commits not on the resolved source. */
    public Map<String, Object> log(String path, String source, boolean uniqueOnly, int limit) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("path", path);
        body.put("source", source == null ? "" : source);
        body.put("uniqueOnly", uniqueOnly);
        body.put("limit", limit);
        return post("/repos/log", body);
    }

    /** Full details of one commit (identities, message, changed files). */
    public Map<String, Object> commitInfo(String path, String hash) {
        return post("/repos/commit-info", Map.of("path", path, "hash", hash));
    }

    /** Guarded squash of the newest {@code count} commits (backup ref first; never pushes). */
    public Map<String, Object> squash(String path, int count, String message) {
        return post("/repos/squash", Map.of("path", path, "count", count,
                "message", message == null ? "" : message));
    }

    /** Fetch remote-tracking refs (updates refs/remotes only — no worktree changes). */
    public Map<String, Object> fetch(String path) {
        return post("/repos/fetch", Map.of("path", path));
    }

    /** Pop a native desktop notification on the host (best-effort; requires the agent running). */
    public Map<String, Object> notify(String title, String body, String urgency) {
        return post("/notify", Map.of("title", title == null ? "" : title,
                "body", body == null ? "" : body, "urgency", urgency == null ? "normal" : urgency));
    }

    /** Predict conflicts from integrating the source branch into HEAD (read-only merge-tree). */
    public Map<String, Object> conflict(String path, String source) {
        return post("/repos/conflict", Map.of("path", path, "source", source == null ? "" : source));
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

    public Map<String, Object> push(String path, boolean force) {
        return post("/repos/push", Map.of("path", path, "force", force));
    }

    /** Abort an in-progress merge/rebase/cherry-pick/revert (restores pre-operation state). */
    public Map<String, Object> abort(String path) {
        return post("/repos/abort", Map.of("path", path));
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

    // ---- Fleet: background agent runs ----

    /** Start a detached headless `claude -p` run in {@code cwd}; returns {@code {runId}}. */
    public Map<String, Object> startRun(String cwd, String prompt, String model,
                                        String permission, boolean allowTests) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("cwd", cwd);
        body.put("prompt", prompt == null ? "" : prompt);
        if (model != null) body.put("model", model);
        body.put("permission", permission == null ? "readonly" : permission);
        body.put("allowTests", allowTests);
        return post("/agent/run", body);
    }

    /** Poll a background run's status: {@code {status, result, error, exitCode}}. */
    public Map<String, Object> runStatus(String agentRunId) {
        return get("/agent/run/" + agentRunId);
    }

    /** Cancel (kill) a background run. */
    public Map<String, Object> cancelRun(String agentRunId) {
        return post("/agent/run/" + agentRunId + "/cancel", Map.of());
    }

    /** Create an isolated worktree + branch for an edit run. */
    public Map<String, Object> worktreeAdd(String repoPath, String branch) {
        return post("/agent/worktree/add", Map.of("repoPath", repoPath, "branch", branch));
    }

    /** Finalize a run's worktree: {@code apply} keeps the branch (committing edits), {@code patch}
     *  applies the diff onto the main checkout, {@code discard} removes worktree + branch. */
    public Map<String, Object> worktreeFinalize(String repoPath, String wtPath, String branch, String mode) {
        return post("/agent/worktree/finalize", Map.of("repoPath", repoPath, "wtPath", wtPath,
                "branch", branch == null ? "" : branch, "mode", mode));
    }

    /** Live PTY sessions on the host agent (sessionId, alive, attached, idleMs). */
    public Map<String, Object> ptySessions() {
        return get("/pty/sessions");
    }

    // ---- model capabilities (skills / MCP servers / plugins) ----

    public Map<String, Object> capabilities() {
        return get("/caps");
    }

    public Map<String, Object> addMcpServer(String name, Map<String, Object> spec) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("name", name);
        body.put("spec", spec);
        return post("/caps/mcp", body);
    }

    public Map<String, Object> removeMcpServer(String name) {
        return post("/caps/mcp/remove", Map.of("name", name));
    }

    public Map<String, Object> setPluginEnabled(String id, boolean enabled) {
        return post("/caps/plugin", Map.of("id", id, "enabled", enabled));
    }

    public Map<String, Object> saveSkill(String dir, String name, String description, String body) {
        Map<String, Object> b = new java.util.HashMap<>();
        b.put("dir", dir == null ? "" : dir);
        b.put("name", name == null ? "" : name);
        b.put("description", description == null ? "" : description);
        b.put("body", body == null ? "" : body);
        return post("/caps/skill", b);
    }

    public Map<String, Object> getSkill(String dir) {
        return post("/caps/skill/get", Map.of("dir", dir));
    }

    /** Full text of the named skills, for injecting into models that can't load them natively. */
    public Map<String, Object> skillBundle(java.util.List<String> keys) {
        return post("/caps/skills/bundle", Map.of("keys", keys));
    }

    /** Tools advertised by the named MCP servers (the agent runs the servers). */
    public Map<String, Object> mcpTools(java.util.List<String> servers) {
        return post("/mcp/tools", Map.of("servers", servers));
    }

    /** Invoke an MCP tool. {@code argsJson} is the raw JSON object the model produced. */
    public Map<String, Object> mcpCall(String server, String tool, String argsJson) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("server", server);
        body.put("tool", tool);
        body.put("args", parseArgs(argsJson));
        return post("/mcp/call", body);
    }

    /** Models emit tool arguments as a JSON string; MCP wants a real object. */
    private Object parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return Map.of();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(argsJson, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    public Map<String, Object> removeSkill(String dir) {
        return post("/caps/skill/remove", Map.of("dir", dir));
    }

    public Map<String, Object> installSkillRepo(String repo) {
        return post("/caps/skill/install", Map.of("repo", repo));
    }

    // ---- configuration backup ----

    /** Write the backup payload into the user's backup repo, commit, optionally push. */
    public Map<String, Object> backupSave(Map<String, Object> body) {
        return post("/backup/save", body);
    }

    /** Read a backup tree back (payload files + the names of any stored skills). */
    public Map<String, Object> backupLoad(String dir) {
        return post("/backup/load", Map.of("dir", dir));
    }

    /** Copy backed-up skills back into ~/.claude/skills. */
    public Map<String, Object> backupRestoreSkills(String dir) {
        return post("/backup/restore-skills", Map.of("dir", dir));
    }

    private Map<String, Object> get(String path) {
        Map<String, Object> resp = http.get().uri(path).retrieve().body(MAP);
        if (resp == null) throw new IllegalStateException("no response from host agent");
        return resp;
    }

    private Map<String, Object> post(String path, Map<String, Object> body) {
        Map<String, Object> resp = http.post().uri(path).body(body).retrieve().body(MAP);
        if (resp == null) throw new IllegalStateException("no response from host agent");
        return resp;
    }
}
