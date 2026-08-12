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

/**
 * Gives skills to models that can't load them themselves.
 *
 * <p>Claude Code reads {@code ~/.claude/skills} (and plugin-bundled skills) natively. A local
 * Ollama model or a raw API call has no such mechanism — so for those we inject the enabled
 * skills' instructions into the system prompt. That keeps DevLoom local-first: the same
 * capabilities apply whichever model you pick, rather than only the Claude ones.
 *
 * <p>Injection costs context on every turn, so it is opt-in per skill and the block is capped.
 */
@Service
public class SkillContext {

    private static final Logger log = LoggerFactory.getLogger(SkillContext.class);

    /** Skill keys (personal:&lt;dir&gt; / plugin:&lt;id&gt;:&lt;dir&gt;) enabled for non-Claude models. */
    public static final String ENABLED = "skills.enabledForModels";

    /** Rough ceiling for the injected block, so a big skill set can't crowd out the conversation. */
    private static final int MAX_CHARS = 24_000;
    private static final long CACHE_MS = 60_000;

    private final AppConfigService config;
    private final HostAgentClient agent;
    private volatile String cached;
    private volatile String cachedFor = "";
    private volatile long cachedAt;

    public SkillContext(AppConfigService config, HostAgentClient agent) {
        this.config = config;
        this.agent = agent;
    }

    /** The skill keys the user turned on for local / API models. */
    public Set<String> enabledKeys() {
        return config.get(ENABLED)
                .map(v -> new LinkedHashSet<>(java.util.Arrays.stream(v.split("\n"))
                        .map(String::trim).filter(s -> !s.isBlank()).toList()))
                .map(s -> (Set<String>) s)
                .orElseGet(LinkedHashSet::new);
    }

    public void setEnabled(Set<String> keys) {
        config.set(ENABLED, keys == null || keys.isEmpty() ? null : String.join("\n", keys));
        cachedAt = 0; // force a refetch on the next generation
    }

    /**
     * The system-prompt block for the enabled skills, or {@code null} when nothing is enabled or
     * the agent is unreachable. Cached briefly — this runs on every generation.
     */
    public String block() {
        Set<String> keys = enabledKeys();
        if (keys.isEmpty()) return null;
        String signature = String.join("|", keys);
        long now = System.currentTimeMillis();
        if (cached != null && signature.equals(cachedFor) && now - cachedAt < CACHE_MS) {
            return cached;
        }
        String built;
        try {
            built = build(agent.skillBundle(new ArrayList<>(keys)));
        } catch (Exception e) {
            log.debug("skill bundle unavailable (agent down?): {}", e.getMessage());
            return cached; // keep serving the last good block rather than silently dropping skills
        }
        cached = built;
        cachedFor = signature;
        cachedAt = now;
        return built;
    }

    /** Prepend the skill block to a system prompt (returns the original when nothing is enabled). */
    public String applyTo(String system) {
        String b = block();
        if (b == null || b.isBlank()) return system;
        return system == null || system.isBlank() ? b : b + "\n\n" + system;
    }

    private String build(Map<String, Object> bundle) {
        Object list = bundle == null ? null : bundle.get("skills");
        if (!(list instanceof List<?> items) || items.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("""
                # Available skills

                These are the user's own reusable procedures. Follow the relevant one when a task
                matches its description; ignore the rest. They are instructions, not a transcript.
                """);
        for (Object o : items) {
            if (!(o instanceof Map<?, ?> m)) continue;
            String name = String.valueOf(m.get("name"));
            String desc = m.get("description") == null ? "" : String.valueOf(m.get("description"));
            String body = m.get("body") == null ? "" : String.valueOf(m.get("body"));
            String chunk = "\n## Skill: " + name + "\n"
                    + (desc.isBlank() ? "" : "_Use when: " + desc + "_\n")
                    + "\n" + body + "\n";
            if (sb.length() + chunk.length() > MAX_CHARS) {
                sb.append("\n(Further skills omitted — the skill set exceeds the context budget.)\n");
                break;
            }
            sb.append(chunk);
        }
        return sb.toString();
    }
}
