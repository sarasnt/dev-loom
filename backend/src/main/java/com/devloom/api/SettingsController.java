package com.devloom.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devloom.common.AppConfigService;

/** User settings (non-secret). Currently: the default working dir for claude-cli terminals. */
@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private final AppConfigService config;

    public SettingsController(AppConfigService config) {
        this.config = config;
    }

    @GetMapping
    public Map<String, Object> get() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("terminalWorkdir", config.get(AppConfigService.TERMINAL_WORKDIR).orElse(""));
        m.put("repoDirs", config.repoDirs());
        return m;
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
}
