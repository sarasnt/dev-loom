package com.devloom.api;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.devloom.audit.AuditService;
import com.devloom.integrations.IntegrationsService;
import com.devloom.integrations.RetentionService;
import com.devloom.integrations.SyncService;
import com.devloom.workmodel.WorkModelService;

/** All endpoints for the MVP frontend (SPEC.md §34). Shapes match frontend/src/types.ts. */
@RestController
@RequestMapping("/api/v1")
public class ApiController {

    private static final Logger log = LoggerFactory.getLogger(ApiController.class);
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneOffset.UTC);

    // Small pool for SSE analysis streams (single-user local; analyses are infrequent + bounded).
    private final ExecutorService sse = Executors.newCachedThreadPool();

    private final TodayService todayService;
    private final WorkModelService workModel;
    private final SyncService syncService;
    private final RetentionService retention;
    private final HandoffService handoffService;
    private final BuildFailureService buildFailureService;
    private final ChangesService changesService;
    private final IntegrationsService integrationsService;
    private final BrainstormService brainstormService;
    private final ProvidersService providersService;
    private final PrivacyService privacyService;
    private final OnboardingService onboardingService;
    private final AuditService audit;

    public ApiController(TodayService todayService, WorkModelService workModel,
                         SyncService syncService,
                         RetentionService retention, HandoffService handoffService,
                         BuildFailureService buildFailureService, ChangesService changesService,
                         IntegrationsService integrationsService, BrainstormService brainstormService,
                         ProvidersService providersService, PrivacyService privacyService,
                         OnboardingService onboardingService, AuditService audit) {
        this.todayService = todayService;
        this.workModel = workModel;
        this.syncService = syncService;
        this.retention = retention;
        this.handoffService = handoffService;
        this.buildFailureService = buildFailureService;
        this.changesService = changesService;
        this.integrationsService = integrationsService;
        this.brainstormService = brainstormService;
        this.providersService = providersService;
        this.privacyService = privacyService;
        this.onboardingService = onboardingService;
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
        // No id → the service resolves the most recent real failed CI run.
        return buildFailureService.analyze(id);
    }

    /**
     * Stream the analysis: a {@code step} event per real stage (fetch run, read job, redact
     * log, summarize) then a {@code result} event with the full BuildFailure. Lets the UI show
     * genuine progress instead of a time estimate.
     */
    @GetMapping({"/builds/{id}/stream", "/builds/stream"})
    public SseEmitter buildStream(@PathVariable(required = false) String id) {
        SseEmitter emitter = new SseEmitter(180_000L);
        sse.execute(() -> {
            try {
                Dto.BuildFailure result = buildFailureService.analyze(id, step -> {
                    try {
                        emitter.send(SseEmitter.event().name("step").data(step));
                    } catch (Exception ignore) {
                        // client went away mid-stream — the analyze() will still finish
                    }
                });
                emitter.send(SseEmitter.event().name("result").data(result));
                emitter.complete();
            } catch (Exception e) {
                log.warn("Build stream failed for '{}': {}", id, e.getMessage());
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /** "What changed since you last looked" — derived from the audit trail (SPEC §17). */
    @GetMapping("/changes")
    public List<Dto.AuditEntry> changes() {
        return changesService.detail();
    }

    @GetMapping({"/handoffs/{id}", "/handoffs"})
    public Dto.Handoff handoff(@PathVariable(required = false) String id) {
        // Real assembly from a real build's (redacted) data. A handoff id ("h…") or no id →
        // let the build service resolve the most recent real failed run.
        String buildId = (id == null || id.startsWith("h")) ? null : id;
        return handoffService.fromBuild(buildFailureService.analyze(buildId));
    }

    @GetMapping("/integrations")
    public List<Dto.Integration> integrations() {
        return integrationsService.list();
    }

    @GetMapping("/providers")
    public Dto.Providers providers() {
        return providersService.providers();
    }

    /** Switch the active local model (rail dropdown). Returns the refreshed provider state. */
    @PostMapping("/providers/model")
    public Dto.Providers selectModel(@RequestBody Map<String, String> body) {
        providersService.selectModel(body == null ? null : body.get("name"));
        return providersService.providers();
    }

    @GetMapping("/privacy")
    public Dto.Privacy privacy() {
        return privacyService.privacy();
    }

    @GetMapping("/brainstorm")
    public Dto.Brainstorm brainstorm() {
        return brainstormService.initial();
    }

    /** Send a brainstorm message + attached sources → local-model reply (SPEC §Brainstorming). */
    @PostMapping("/brainstorm/messages")
    public Dto.BrainstormMessage brainstormSend(@RequestBody Dto.BrainstormSend body) {
        return brainstormService.reply(body);
    }

    @GetMapping("/onboarding")
    public List<Dto.OnboardStep> onboarding() {
        return onboardingService.steps();
    }
}
