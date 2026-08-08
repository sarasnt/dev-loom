package com.devloom.api;

import java.util.List;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devloom.audit.AuditService;
import com.devloom.integrations.RetentionService;
import com.devloom.integrations.SyncService;
import com.devloom.workmodel.WorkModelService;

/** All endpoints for the MVP frontend (SPEC.md §34). Shapes match frontend/src/types.ts. */
@RestController
@RequestMapping("/api/v1")
public class ApiController {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneOffset.UTC);

    private final TodayService todayService;
    private final WorkModelService workModel;
    private final FixtureData fixtures;
    private final SyncService syncService;
    private final RetentionService retention;
    private final HandoffService handoffService;
    private final AuditService audit;

    public ApiController(TodayService todayService, WorkModelService workModel,
                         FixtureData fixtures, SyncService syncService,
                         RetentionService retention, HandoffService handoffService,
                         AuditService audit) {
        this.todayService = todayService;
        this.workModel = workModel;
        this.fixtures = fixtures;
        this.syncService = syncService;
        this.retention = retention;
        this.handoffService = handoffService;
        this.audit = audit;
    }

    /** Trigger an on-demand sync of a source (e.g. Jira). */
    @PostMapping("/integrations/{source}/sync")
    public Map<String, Object> sync(@PathVariable String source) {
        int n = syncService.sync(source);
        return Map.<String, Object>of("source", source, "ingested", n);
    }

    /** Disconnect a source: purge its derived work items (SPEC §29). */
    @DeleteMapping("/integrations/{source}")
    public Map<String, Object> disconnect(@PathVariable String source) {
        int removed = retention.purgeSource(source);
        return Map.<String, Object>of("source", source, "removed", removed);
    }

    /** Delete all synced work data (account/workspace reset, SPEC §29). */
    @DeleteMapping("/data")
    public Map<String, Object> purgeAll() {
        long removed = retention.purgeAll();
        return Map.<String, Object>of("removed", removed);
    }

    /** Audit trail — credential use, syncs, deletions (SPEC §26). */
    @GetMapping("/audit")
    public List<Dto.AuditEntry> auditLog() {
        return audit.recent().stream()
                .map(e -> new Dto.AuditEntry(e.getAction(), e.getTarget(), e.getMetadata(),
                        TS.format(e.getCreatedAt())))
                .toList();
    }

    @GetMapping("/today")
    public Dto.Today today() {
        return todayService.today();
    }

    @GetMapping("/work")
    public List<Dto.WorkRow> work() {
        return workModel.allWork();
    }

    @GetMapping({"/builds/{id}", "/builds"})
    public Dto.BuildFailure build(@PathVariable(required = false) String id) {
        return fixtures.buildFailure(id == null ? "1893" : id);
    }

    @GetMapping({"/handoffs/{id}", "/handoffs"})
    public Dto.Handoff handoff(@PathVariable(required = false) String id) {
        // Generate the handoff from the build's (redacted) data — real assembly, not canned.
        String buildId = (id == null || id.startsWith("h")) ? "1893" : id;
        return handoffService.fromBuild(fixtures.buildFailure(buildId));
    }

    @GetMapping("/integrations")
    public List<Dto.Integration> integrations() {
        return fixtures.integrations();
    }

    @GetMapping("/providers")
    public Dto.Providers providers() {
        return fixtures.providers();
    }

    @GetMapping("/privacy")
    public Dto.Privacy privacy() {
        return fixtures.privacy();
    }

    @GetMapping("/brainstorm")
    public Dto.Brainstorm brainstorm() {
        return fixtures.brainstorm();
    }

    @GetMapping("/onboarding")
    public List<Dto.OnboardStep> onboarding() {
        return fixtures.onboarding();
    }
}
