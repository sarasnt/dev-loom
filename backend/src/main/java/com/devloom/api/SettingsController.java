package com.devloom.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devloom.ai.HostAgentClient;
import com.devloom.briefing.NotifyConfig;
import com.devloom.common.AppConfigService;

/** User settings (non-secret): terminal workdir, repo dirs, desktop-notification preferences. */
@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private final AppConfigService config;
    private final HostAgentClient agent;

    public SettingsController(AppConfigService config, HostAgentClient agent) {
        this.config = config;
        this.agent = agent;
    }

    @GetMapping
    public Map<String, Object> get() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("terminalWorkdir", config.get(AppConfigService.TERMINAL_WORKDIR).orElse(""));
        m.put("repoDirs", config.repoDirs());
        m.put("notify", NotifyConfig.from(config).toMap());
        m.put("fleetWorktreesDefault",
                config.get(AppConfigService.FLEET_WORKTREES_DEFAULT).map(Boolean::parseBoolean).orElse(true));
        m.put("gitPushProtection", config.get(AppConfigService.GIT_PUSH_PROTECTION).orElse("protected"));
        m.put("gitProtectedPatterns", config.get(AppConfigService.GIT_PROTECTED_PATTERNS).orElse("main, master, develop, dev"));
        m.put("advanced", advanced());
        return m;
    }

    /**
     * Advanced model settings. Blank values mean "shipped default" — the UI shows the default as a
     * placeholder rather than writing it into config, so raising a default later still reaches
     * anyone who never touched the box.
     */
    private Map<String, Object> advanced() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("groundedTemperature", config.get(AppConfigService.SAMPLING_GROUNDED_TEMP).orElse(""));
        a.put("groundedTopP", config.get(AppConfigService.SAMPLING_GROUNDED_TOP_P).orElse(""));
        a.put("creativeTemperature", config.get(AppConfigService.SAMPLING_CREATIVE_TEMP).orElse(""));
        a.put("maxSteps", config.get(AppConfigService.TOOL_MAX_STEPS).orElse(""));
        a.put("judgeEnabled", !config.get(AppConfigService.JUDGE_ENABLED).map("false"::equalsIgnoreCase).orElse(false));
        a.put("defaults", Map.of(
                "groundedTemperature", com.devloom.ai.Sampling.GROUNDED_TEMPERATURE,
                "groundedTopP", com.devloom.ai.Sampling.GROUNDED_TOP_P,
                "creativeTemperature", com.devloom.ai.Sampling.CREATIVE_TEMPERATURE,
                "maxSteps", com.devloom.ai.ToolLoop.DEFAULT_MAX_STEPS));
        return a;
    }

    public record AdvancedSettings(String groundedTemperature, String groundedTopP,
                                   String creativeTemperature, String maxSteps, Boolean judgeEnabled) {}

    /** Save advanced model settings. An empty string clears the override back to the shipped default. */
    @PutMapping("/advanced")
    public Map<String, Object> setAdvanced(@RequestBody AdvancedSettings body) {
        if (body != null) {
            if (body.groundedTemperature() != null) config.set(AppConfigService.SAMPLING_GROUNDED_TEMP, blankToNull(body.groundedTemperature()));
            if (body.groundedTopP() != null) config.set(AppConfigService.SAMPLING_GROUNDED_TOP_P, blankToNull(body.groundedTopP()));
            if (body.creativeTemperature() != null) config.set(AppConfigService.SAMPLING_CREATIVE_TEMP, blankToNull(body.creativeTemperature()));
            if (body.maxSteps() != null) config.set(AppConfigService.TOOL_MAX_STEPS, blankToNull(body.maxSteps()));
            if (body.judgeEnabled() != null) config.set(AppConfigService.JUDGE_ENABLED, String.valueOf(body.judgeEnabled()));
        }
        return get();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public record GitSettings(String pushProtection, String protectedPatterns) {}

    @PutMapping("/git")
    public Map<String, Object> setGit(@RequestBody GitSettings body) {
        if (body != null) {
            if (body.pushProtection() != null) config.set(AppConfigService.GIT_PUSH_PROTECTION, body.pushProtection());
            if (body.protectedPatterns() != null) config.set(AppConfigService.GIT_PROTECTED_PATTERNS, body.protectedPatterns());
        }
        return get();
    }

    public record FleetSettings(Boolean worktreesDefault) {}

    @PutMapping("/fleet")
    public Map<String, Object> setFleet(@RequestBody FleetSettings body) {
        if (body != null && body.worktreesDefault() != null) {
            config.set(AppConfigService.FLEET_WORKTREES_DEFAULT, String.valueOf(body.worktreesDefault()));
        }
        return get();
    }

    public record TerminalWorkdir(String path) {}
    public record RepoDir(String path) {}

    @PutMapping("/terminal-workdir")
    public Map<String, Object> setTerminalWorkdir(@RequestBody TerminalWorkdir body) {
        config.set(AppConfigService.TERMINAL_WORKDIR, body == null ? null : body.path());
        return get();
    }

    /** Add a parent directory that "Sync" on the Repos page will scan for git repos. */
    @PutMapping("/repo-dirs/add")
    public Map<String, Object> addRepoDir(@RequestBody RepoDir body) {
        if (body != null) config.addRepoDir(body.path());
        return get();
    }

    @PutMapping("/repo-dirs/remove")
    public Map<String, Object> removeRepoDir(@RequestBody RepoDir body) {
        if (body != null) config.removeRepoDir(body.path());
        return get();
    }

    public record NotifySettings(Boolean enabled, String digestTime, String quietStart,
                                 String quietEnd, Boolean urgentCi, Boolean urgentReview, Integer prWaitHours) {}

    /** Save desktop-notification preferences (partial updates allowed). */
    @PutMapping("/notifications")
    public Map<String, Object> setNotifications(@RequestBody NotifySettings b) {
        if (b != null) {
            if (b.enabled() != null) config.set(AppConfigService.NOTIFY_ENABLED, String.valueOf(b.enabled()));
            if (b.digestTime() != null) config.set(AppConfigService.NOTIFY_DIGEST_TIME, b.digestTime());
            if (b.quietStart() != null) config.set(AppConfigService.NOTIFY_QUIET_START, b.quietStart());
            if (b.quietEnd() != null) config.set(AppConfigService.NOTIFY_QUIET_END, b.quietEnd());
            if (b.urgentCi() != null) config.set(AppConfigService.NOTIFY_URGENT_CI, String.valueOf(b.urgentCi()));
            if (b.urgentReview() != null) config.set(AppConfigService.NOTIFY_URGENT_REVIEW, String.valueOf(b.urgentReview()));
            if (b.prWaitHours() != null) config.set(AppConfigService.NOTIFY_PR_WAIT_HOURS, String.valueOf(b.prWaitHours()));
        }
        return get();
    }

    /** Send a test desktop notification through the host agent. */
    @PostMapping("/notifications/test")
    public Map<String, Object> testNotification() {
        return agent.notify("DevLoom", "Test notification — your briefing will look like this.", "normal");
    }
}
