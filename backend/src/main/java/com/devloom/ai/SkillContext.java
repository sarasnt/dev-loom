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
 * <p>Injection is opt-in per skill and uses progressive disclosure: every enabled skill is listed
 * by name and "use when", but only ones matching the task are expanded in full. Dumping whole
 * skill bodies in costs context on every turn and — worse — lets a model mistake a skill's worked
 * example for the actual task.
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
    private volatile List<Map<String, Object>> cached;
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
     * The system-prompt block for the enabled skills, tailored to {@code task}; {@code null} when
     * nothing is enabled or the agent is unreachable. The fetched bundle is cached (it spawns work
     * on the agent); the block itself is rebuilt per task because relevance depends on it.
     */
    public String block(String task) {
        Set<String> keys = enabledKeys();
        if (keys.isEmpty()) return null;
        String signature = String.join("|", keys);
        long now = System.currentTimeMillis();
        List<Map<String, Object>> bundle;
        if (cached != null && signature.equals(cachedFor) && now - cachedAt < CACHE_MS) {
            bundle = cached;
        } else {
            try {
                bundle = fetch(agent.skillBundle(new ArrayList<>(keys)));
                if (bundle.isEmpty()) {
                    log.warn("Skills enabled for models ({}) but the agent returned none", keys);
                }
            } catch (Exception e) {
                log.warn("Could not load skills for models — is the host agent running? {}", e.toString());
                bundle = cached; // keep the last good set rather than silently dropping skills
            }
            if (bundle == null) return null;
            cached = bundle;
            cachedFor = signature;
            cachedAt = now;
        }
        return build(bundle, task);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetch(Map<String, Object> bundle) {
        Object list = bundle == null ? null : bundle.get("skills");
        List<Map<String, Object>> out = new ArrayList<>();
        if (list instanceof List<?> items) {
            for (Object o : items) {
                if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    /**
     * Prepend the skill block to a system prompt (returns the original when nothing is enabled).
     *
     * <p>{@code task} is what the user actually asked. Skills are matched against it so only
     * relevant ones are expanded in full — see {@link #build}. Without that, a model reads the
     * worked examples inside an unrelated skill and answers <em>those</em> instead of the task,
     * which is exactly what happened before this existed.
     */
    public String applyTo(String system, String task) {
        String b = block(task);
        if (b == null || b.isBlank()) return system;
        return system == null || system.isBlank() ? b : b + "\n\n" + system;
    }

    /** At most this many skills are expanded in full for one turn. */
    private static final int MAX_EXPANDED = 2;

    /**
     * Build the block for one task. Every enabled skill is listed by name and "use when"; only the
     * ones that actually match the task are expanded in full.
     *
     * <p>This is deliberate. Skills contain worked examples ("Fix the 3 failing tests in
     * agent-tool-abort.test.ts"), and a model handed several full skill bodies will happily answer
     * an example instead of the real question. Listing everything but expanding little keeps the
     * skill discoverable without putting someone else's task in front of the model.
     */
    private String build(List<Map<String, Object>> items, String task) {
        if (items == null || items.isEmpty()) return null;
        List<Map<String, Object>> ranked = new ArrayList<>(items);
        ranked.sort((a, b) -> Integer.compare(score(b, task), score(a, task)));

        StringBuilder sb = new StringBuilder("""
                # Reference: the user's skills

                Reusable procedures the user keeps. This section is REFERENCE MATERIAL, not your
                task, and any example inside a skill is an illustration — never something to act on.
                Your actual task is stated in the user message. Follow a skill only when it clearly
                applies to that task; otherwise ignore this section entirely.

                Available:
                """);
        for (Map<String, Object> m : items) {
            sb.append("- ").append(str(m, "name"));
            String d = str(m, "description");
            if (!d.isBlank()) sb.append(" — ").append(d);
            sb.append('\n');
        }

        int expanded = 0;
        for (Map<String, Object> m : ranked) {
            if (expanded >= MAX_EXPANDED || score(m, task) <= 0) break;
            String body = str(m, "body");
            if (body.isBlank()) continue;
            String chunk = "\n## Skill: " + str(m, "name") + " (full procedure)\n\n" + body + "\n";
            if (sb.length() + chunk.length() > MAX_CHARS) break;
            sb.append(chunk);
            expanded++;
        }
        if (expanded == 0) {
            sb.append("\n(No skill matches this task — none expanded. Ask the user to name one if"
                    + " you think it applies.)\n");
        }
        log.info("Skills: {} listed, {} expanded for this task", items.size(), expanded);
        return sb.toString();
    }

    /** Crude term overlap between the task and a skill's name/description — enough to rank. */
    private static int score(Map<String, Object> skill, String task) {
        if (task == null || task.isBlank()) return 0;
        String hay = (str(skill, "name") + " " + str(skill, "description")).toLowerCase();
        int score = 0;
        for (String word : task.toLowerCase().split("[^a-z0-9]+")) {
            if (word.length() < 4 || STOPWORDS.contains(word)) continue;
            if (hay.contains(word)) score++;
        }
        return score;
    }

    private static final Set<String> STOPWORDS = Set.of(
            "this", "that", "with", "from", "have", "when", "what", "your", "which", "there",
            "then", "them", "they", "into", "about", "would", "could", "should", "please",
            "using", "used", "make", "does", "each", "also", "here", "just", "like", "only",
            "file", "files", "code", "repo", "repository", "project", "task", "tool", "tools");

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : String.valueOf(v);
    }
}
