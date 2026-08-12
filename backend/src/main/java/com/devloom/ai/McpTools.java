package com.devloom.ai;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.devloom.common.AppConfigService;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

/**
 * MCP tools for models that aren't Claude Code.
 *
 * <p>Claude Code is an MCP client already; a local Ollama model or a raw API call is not. The host
 * agent runs the actual MCP servers (they're usually stdio processes a container can neither spawn
 * nor reach) — this class turns their advertised tools into LangChain4j {@link ToolSpecification}s
 * and executes the calls the model asks for.
 *
 * <p>Opt-in per server: a tool call has side effects, so nothing is exposed until the user enables
 * it for models.
 */
@Service
public class McpTools {

    private static final Logger log = LoggerFactory.getLogger(McpTools.class);

    /** MCP server names exposed to local / API models. */
    public static final String ENABLED = "mcp.enabledForModels";

    /** Tool lists are stable per server; connecting spawns a process, so don't do it per turn. */
    private static final long CACHE_MS = 120_000;

    private final AppConfigService config;
    private final HostAgentClient agent;
    private volatile List<Tool> cached = List.of();
    private volatile String cachedFor = "";
    private volatile long cachedAt;

    public McpTools(AppConfigService config, HostAgentClient agent) {
        this.config = config;
        this.agent = agent;
    }

    /** One MCP tool: which server serves it, plus the spec handed to the model. */
    public record Tool(String server, String name, ToolSpecification spec) {}

    public Set<String> enabledServers() {
        return config.get(ENABLED)
                .map(v -> new LinkedHashSet<>(java.util.Arrays.stream(v.split("\n"))
                        .map(String::trim).filter(s -> !s.isBlank()).toList()))
                .map(s -> (Set<String>) s)
                .orElseGet(LinkedHashSet::new);
    }

    public void setEnabled(Set<String> servers) {
        config.set(ENABLED, servers == null || servers.isEmpty() ? null : String.join("\n", servers));
        cachedAt = 0;
    }

    /** Tools currently available to non-Claude models (empty when none are enabled). */
    public List<Tool> tools() {
        Set<String> servers = enabledServers();
        if (servers.isEmpty()) return List.of();
        String signature = String.join("|", servers);
        long now = System.currentTimeMillis();
        if (signature.equals(cachedFor) && now - cachedAt < CACHE_MS) return cached;
        List<Tool> built = new ArrayList<>();
        try {
            Map<String, Object> res = agent.mcpTools(new ArrayList<>(servers));
            if (res.get("errors") instanceof Map<?, ?> errs && !errs.isEmpty()) {
                log.warn("MCP servers unavailable: {}", errs);
            }
            if (res.get("tools") instanceof List<?> items) {
                for (Object o : items) {
                    if (!(o instanceof Map<?, ?> m)) continue;
                    Tool t = toTool(m);
                    if (t != null) built.add(t);
                }
            }
            log.info("MCP: {} tool(s) available to local models from {}", built.size(), servers);
        } catch (Exception e) {
            log.warn("Could not load MCP tools — is the host agent running? {}", e.toString());
            return cached; // keep the last good set rather than silently disarming the model
        }
        cached = built;
        cachedFor = signature;
        cachedAt = now;
        return built;
    }

    /** Run a tool the model asked for; the returned text is fed back into the conversation. */
    public String call(String server, String tool, String argsJson) {
        try {
            Map<String, Object> r = agent.mcpCall(server, tool, argsJson);
            String text = r.get("text") == null ? "" : String.valueOf(r.get("text"));
            if (!Boolean.TRUE.equals(r.get("ok"))) {
                String err = r.get("error") == null ? text : String.valueOf(r.get("error"));
                return "Tool error: " + (err.isBlank() ? "the tool reported a failure" : err);
            }
            return text.isBlank() ? "(the tool returned no output)" : text;
        } catch (Exception e) {
            return "Tool error: " + e.getMessage();
        }
    }

    /** Which server owns a tool name (models only send the tool name back). */
    public String serverFor(String toolName) {
        for (Tool t : tools()) {
            if (t.name().equals(toolName)) return t.server();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Tool toTool(Map<?, ?> m) {
        String server = String.valueOf(m.get("server"));
        String name = String.valueOf(m.get("name"));
        if (name.isBlank() || "null".equals(name)) return null;
        String desc = m.get("description") == null ? "" : String.valueOf(m.get("description"));
        JsonObjectSchema params = m.get("inputSchema") instanceof Map<?, ?> s
                ? objectSchema((Map<String, Object>) s) : JsonObjectSchema.builder().build();
        return new Tool(server, name, ToolSpecification.builder()
                .name(name)
                .description(desc)
                .parameters(params)
                .build());
    }

    /** Translate a JSON-Schema object (MCP's tool input) into LangChain4j's schema types. */
    @SuppressWarnings("unchecked")
    private JsonObjectSchema objectSchema(Map<String, Object> schema) {
        JsonObjectSchema.Builder b = JsonObjectSchema.builder();
        if (schema.get("description") != null) b.description(String.valueOf(schema.get("description")));
        Object props = schema.get("properties");
        if (props instanceof Map<?, ?> pm) {
            for (Map.Entry<?, ?> e : pm.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> v)) continue;
                JsonSchemaElement el = element((Map<String, Object>) v);
                if (el != null) b.addProperty(String.valueOf(e.getKey()), el);
            }
        }
        if (schema.get("required") instanceof List<?> req) {
            b.required(req.stream().map(String::valueOf).toList());
        }
        return b.build();
    }

    @SuppressWarnings("unchecked")
    private JsonSchemaElement element(Map<String, Object> p) {
        String type = p.get("type") == null ? "string" : String.valueOf(p.get("type"));
        String desc = p.get("description") == null ? null : String.valueOf(p.get("description"));
        return switch (type) {
            case "number" -> JsonNumberSchema.builder().description(desc).build();
            case "integer" -> JsonIntegerSchema.builder().description(desc).build();
            case "boolean" -> JsonBooleanSchema.builder().description(desc).build();
            case "array" -> {
                Object items = p.get("items");
                JsonSchemaElement inner = items instanceof Map<?, ?> im
                        ? element((Map<String, Object>) im) : JsonStringSchema.builder().build();
                yield JsonArraySchema.builder().description(desc).items(inner).build();
            }
            case "object" -> objectSchema(p);
            default -> JsonStringSchema.builder().description(desc).build();
        };
    }
}
