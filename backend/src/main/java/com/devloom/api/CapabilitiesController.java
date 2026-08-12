package com.devloom.api;

import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devloom.ai.HostAgentClient;
import com.devloom.audit.AuditService;

/**
 * What the models can do: skills, MCP servers and plugins. These live in the user's own Claude
 * config (~/.claude.json, ~/.claude/settings.json, ~/.claude/skills), so DevLoom edits those files
 * through the host agent rather than keeping a parallel copy — whatever you configure here is what
 * the CLI, the embedded terminal and background runs all pick up.
 */
@RestController
@RequestMapping("/api/v1/capabilities")
public class CapabilitiesController {

    private final HostAgentClient agent;
    private final AuditService audit;

    public CapabilitiesController(HostAgentClient agent, AuditService audit) {
        this.agent = agent;
        this.audit = audit;
    }

    @GetMapping
    public Map<String, Object> all() {
        try {
            return agent.capabilities();
        } catch (Exception e) {
            // Agent offline — say so honestly instead of pretending the user has nothing installed.
            return Map.of("agentUp", false, "mcp", java.util.List.of(),
                    "skills", java.util.List.of(), "plugins", java.util.List.of(),
                    "error", "host agent unreachable");
        }
    }

    // ---- MCP servers ----
    public record McpUpsert(String name, String transport, String command, java.util.List<String> args,
                            String url, Map<String, String> env) {}

    @PostMapping("/mcp")
    public Map<String, Object> addMcp(@RequestBody McpUpsert body) {
        Map<String, Object> spec = new java.util.LinkedHashMap<>();
        if ("http".equals(body.transport()) || "sse".equals(body.transport())) {
            spec.put("type", body.transport());
            spec.put("url", body.url() == null ? body.command() : body.url());
        } else {
            spec.put("command", body.command());
            if (body.args() != null && !body.args().isEmpty()) spec.put("args", body.args());
        }
        if (body.env() != null && !body.env().isEmpty()) spec.put("env", body.env());
        Map<String, Object> r = agent.addMcpServer(body.name(), spec);
        audit.record("mcp_add", body.name(), body.transport());
        return r;
    }

    @DeleteMapping("/mcp/{name}")
    public Map<String, Object> removeMcp(@PathVariable String name) {
        Map<String, Object> r = agent.removeMcpServer(name);
        audit.record("mcp_remove", name, null);
        return r;
    }

    // ---- plugins ----
    public record PluginToggle(boolean enabled) {}

    @PutMapping("/plugins/{id}")
    public Map<String, Object> togglePlugin(@PathVariable String id, @RequestBody PluginToggle body) {
        Map<String, Object> r = agent.setPluginEnabled(id, body != null && body.enabled());
        audit.record("plugin_toggle", id, String.valueOf(body != null && body.enabled()));
        return r;
    }

    // ---- skills ----
    public record SkillUpsert(String dir, String name, String description, String body) {}
    public record SkillInstall(String repo) {}

    @PostMapping("/skills")
    public Map<String, Object> saveSkill(@RequestBody SkillUpsert body) {
        Map<String, Object> r = agent.saveSkill(body.dir(), body.name(), body.description(), body.body());
        audit.record("skill_save", body.name(), null);
        return r;
    }

    @GetMapping("/skills/{dir}")
    public Map<String, Object> getSkill(@PathVariable String dir) {
        return agent.getSkill(dir);
    }

    @DeleteMapping("/skills/{dir}")
    public Map<String, Object> removeSkill(@PathVariable String dir) {
        Map<String, Object> r = agent.removeSkill(dir);
        audit.record("skill_remove", dir, null);
        return r;
    }

    @PostMapping("/skills/install")
    public Map<String, Object> installSkill(@RequestBody SkillInstall body) {
        Map<String, Object> r = agent.installSkillRepo(body.repo());
        audit.record("skill_install", body.repo(), String.valueOf(r.get("ok")));
        return r;
    }
}
