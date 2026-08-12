package com.devloom.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devloom.backup.BackupService;

/** Configuration backup to a git repo the user owns (secrets deliberately excluded). */
@RestController
@RequestMapping("/api/v1/backup")
public class BackupController {

    private final BackupService backup;

    public BackupController(BackupService backup) {
        this.backup = backup;
    }

    @GetMapping
    public Map<String, Object> status() {
        return backup.status();
    }

    /** Exactly what would be written — so the user can see there are no secrets in it. */
    @GetMapping("/preview")
    public Map<String, Object> preview() {
        return backup.export();
    }

    public record BackupConfig(String dir, String remote, Integer everyHours,
                               Boolean includeSkills, Boolean push) {}

    @PutMapping
    public Map<String, Object> configure(@RequestBody BackupConfig body) {
        return backup.configure(body.dir(), body.remote(), body.everyHours(),
                body.includeSkills(), body.push());
    }

    @PostMapping("/run")
    public Map<String, Object> run() {
        return backup.backupNow();
    }

    public record RestoreRequest(boolean skills) {}

    @PostMapping("/restore")
    public Map<String, Object> restore(@RequestBody(required = false) RestoreRequest body) {
        return backup.restore(body == null || body.skills());
    }
}
