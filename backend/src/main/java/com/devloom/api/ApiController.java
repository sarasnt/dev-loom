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
    private final SourcesService sourcesService;
    private final RepoService repoService;
    private final com.devloom.briefing.BriefingService briefingService;
    private final AuditService audit;

    public ApiController(TodayService todayService, WorkModelService workModel,
                         SyncService syncService,
                         RetentionService retention, HandoffService handoffService,
                         BuildFailureService buildFailureService, ChangesService changesService,
                         IntegrationsService integrationsService, BrainstormService brainstormService,
                         ProvidersService providersService, PrivacyService privacyService,
                         OnboardingService onboardingService, SourcesService sourcesService,
                         RepoService repoService, com.devloom.briefing.BriefingService briefingService,
                         AuditService audit) {
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
        this.sourcesService = sourcesService;
        this.repoService = repoService;
        this.briefingService = briefingService;
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

    /** Snooze a Today card (hide it from the list). */
    @PostMapping("/today/snooze/{id}")
    public Dto.Today snooze(@PathVariable String id) {
        todayService.snooze(id);
        return todayService.today();
    }

    /** Toggle "handled" on a Today item (persists; drops it from the Briefing). {@code id} = ext id. */
    @PostMapping("/today/{id}/handled")
    public Dto.Today handled(@PathVariable String id) {
        briefingService.toggleHandled(id);
        return todayService.today();
    }

    /** Toggle membership in "Today's plan". {@code id} = ext id. */
    @PostMapping("/today/{id}/plan")
    public Dto.Today plan(@PathVariable String id) {
        briefingService.togglePlan(id);
        return todayService.today();
    }

    @GetMapping("/work")
    public List<Dto.WorkRow> work() {
        return workModel.allWork();
    }

    @GetMapping({"/builds/{id}", "/builds"})
    public Dto.BuildFailure build(@PathVariable(required = false) String id,
                                  @org.springframework.web.bind.annotation.RequestParam(required = false) String model) {
        // No id → the service resolves the most recent real failed CI run.
        return buildFailureService.analyze(id, s -> {}, model);
    }

    /**
     * Stream the analysis: a {@code step} event per real stage (fetch run, read job, redact
     * log, summarize) then a {@code result} event with the full BuildFailure. Lets the UI show
     * genuine progress instead of a time estimate.
     */
    @GetMapping({"/builds/{id}/stream", "/builds/stream"})
    public SseEmitter buildStream(@PathVariable(required = false) String id,
                                  @org.springframework.web.bind.annotation.RequestParam(required = false) String model) {
        SseEmitter emitter = new SseEmitter(180_000L);
        sse.execute(() -> {
            try {
                Dto.BuildFailure result = buildFailureService.analyze(id, step -> {
                    try {
                        emitter.send(SseEmitter.event().name("step").data(step));
                    } catch (Exception ignore) {
                        // client went away mid-stream — the analyze() will still finish
                    }
                }, model);
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

    // ---- Multi-source configuration (docs/SPEC-sources.md) ----

    /** Setup descriptors for the Add-source form (types → deployments → fields). */
    @GetMapping("/source-types")
    public List<com.devloom.integrations.SetupDescriptor.Type> sourceTypes() {
        return sourcesService.types();
    }

    @GetMapping("/sources")
    public List<Dto.SourceView> sources() {
        return sourcesService.list();
    }

    @PostMapping("/sources")
    public Dto.SourceView addSource(@RequestBody Dto.SourceUpsert body) {
        return sourcesService.create(body);
    }

    @PostMapping("/sources/test")
    public Map<String, Object> testSource(@RequestBody Dto.SourceUpsert body) {
        return sourcesService.test(body);
    }

    @org.springframework.web.bind.annotation.PutMapping("/sources/{id}")
    public Dto.SourceView updateSource(@PathVariable String id, @RequestBody Dto.SourceUpsert body) {
        return sourcesService.update(id, body);
    }

    @DeleteMapping("/sources/{id}")
    public Map<String, Object> deleteSource(@PathVariable String id) {
        sourcesService.delete(id);
        return Map.<String, Object>of("deleted", id);
    }

    @PostMapping("/sources/{id}/sync")
    public Map<String, Object> syncSource(@PathVariable String id) {
        int n = sourcesService.sync(id);
        return Map.<String, Object>of("id", id, "ingested", n);
    }

    // ---- Local repositories (via host agent) ----

    @GetMapping("/repos")
    public Map<String, Object> repos() {
        return Map.of("agentUp", repoService.agentUp(), "repos", repoService.list());
    }

    /** Add all git repos found under a folder. */
    @PostMapping("/repos/scan")
    public List<Dto.RepoView> scanRepos(@RequestBody Dto.RepoAdd body) {
        return repoService.addFolder(body.root());
    }

    /** Sync: scan the parent directories configured in Settings and add any new git repos. */
    @PostMapping("/repos/sync")
    public Map<String, Object> syncRepos() {
        return repoService.sync();
    }

    /** Add a single repo by path. */
    @PostMapping("/repos")
    public List<Dto.RepoView> addRepo(@RequestBody Dto.RepoAdd body) {
        return repoService.addRepo(body.path());
    }

    @DeleteMapping("/repos/{id}")
    public Map<String, Object> removeRepo(@PathVariable String id) {
        repoService.delete(id);
        return Map.<String, Object>of("removed", id);
    }

    @org.springframework.web.bind.annotation.PutMapping("/repos/{id}/identity")
    public Dto.RepoView repoIdentity(@PathVariable String id, @RequestBody Dto.RepoIdentity body) {
        return repoService.setIdentity(id, body.name(), body.email());
    }

    /** Mark a repo local-only (brainstorm it only with local models) or clear it. */
    @org.springframework.web.bind.annotation.PutMapping("/repos/{id}/local-only")
    public Dto.RepoView repoLocalOnly(@PathVariable String id, @RequestBody Dto.RepoLocalOnly body) {
        return repoService.setLocalOnly(id, body != null && body.value());
    }

    @PostMapping("/repos/{id}/pull")
    public Map<String, Object> repoPull(@PathVariable String id) {
        return repoService.pull(id);
    }

    @PostMapping("/repos/{id}/push")
    public Map<String, Object> repoPush(@PathVariable String id, @RequestBody(required = false) Dto.RepoPush body) {
        return repoService.push(id, body != null && body.force());
    }

    @PostMapping("/repos/{id}/abort")
    public Map<String, Object> repoAbort(@PathVariable String id) {
        return repoService.abort(id);
    }

    @PostMapping("/repos/{id}/pr")
    public Map<String, Object> repoPr(@PathVariable String id) {
        return repoService.pr(id);
    }

    /** Browse the host filesystem (folder picker) — cross-platform via the agent. */
    @PostMapping("/fs/browse")
    public Map<String, Object> browse(@RequestBody Dto.FsBrowse body) {
        return repoService.browse(body == null ? null : body.path());
    }

    @PostMapping("/repos/{id}/changes")
    public Map<String, Object> repoChanges(@PathVariable String id) {
        return repoService.changes(id);
    }

    @PostMapping("/repos/{id}/stage")
    public Map<String, Object> repoStage(@PathVariable String id, @RequestBody Dto.RepoFiles body) {
        return repoService.stage(id, body == null ? null : body.files());
    }

    @PostMapping("/repos/{id}/unstage")
    public Map<String, Object> repoUnstage(@PathVariable String id, @RequestBody Dto.RepoFiles body) {
        return repoService.unstage(id, body == null ? null : body.files());
    }

    @PostMapping("/repos/{id}/commit")
    public Map<String, Object> repoCommit(@PathVariable String id, @RequestBody Dto.RepoCommit body) {
        return repoService.commit(id, body == null ? null : body.message());
    }

    @PostMapping("/repos/{id}/branches")
    public Map<String, Object> repoBranches(@PathVariable String id) {
        return repoService.branches(id);
    }

    @PostMapping("/repos/{id}/checkout")
    public Map<String, Object> repoCheckout(@PathVariable String id, @RequestBody Dto.RepoCheckout body) {
        return repoService.checkout(id, body.branch(), body.create());
    }

    @GetMapping("/repos/{id}/worktrees")
    public List<Dto.WorktreeInfo> repoWorktrees(@PathVariable String id) {
        return repoService.worktrees(id);
    }

    @GetMapping("/repos/{id}/source")
    public Dto.SourceStatus repoSource(@PathVariable String id,
                                       @org.springframework.web.bind.annotation.RequestParam(required = false) String branch) {
        return repoService.sourceStatus(id, branch);
    }

    @org.springframework.web.bind.annotation.PutMapping("/repos/{id}/source")
    public Dto.SourceStatus repoSetSource(@PathVariable String id, @RequestBody Dto.RepoSourceSet body) {
        return repoService.setSource(id, body == null ? null : body.branch(), body == null ? null : body.source());
    }

    @PostMapping("/repos/{id}/fetch")
    public Dto.RepoFetch repoFetch(@PathVariable String id) {
        return repoService.fetch(id);
    }

    @GetMapping("/repos/{id}/conflict")
    public Dto.ConflictStatus repoConflict(@PathVariable String id,
                                           @org.springframework.web.bind.annotation.RequestParam(required = false) String branch) {
        return repoService.conflict(id, branch);
    }

    @GetMapping("/providers")
    public Dto.Providers providers() {
        return providersService.providers();
    }

    /** Switch the active model (rail dropdown) — local or a keyed remote model. */
    @PostMapping("/providers/model")
    public Dto.Providers selectModel(@RequestBody Map<String, String> body) {
        providersService.selectModel(body == null ? null : body.get("name"));
        return providersService.providers();
    }

    /** Store a BYO provider API key (encrypted). Returns refreshed provider state. */
    @PostMapping("/providers/keys")
    public Dto.Providers setKey(@RequestBody Dto.SetKey body) {
        providersService.setKey(body.provider(), body.key());
        return providersService.providers();
    }

    /** Remove a stored provider API key. */
    @DeleteMapping("/providers/keys/{provider}")
    public Dto.Providers clearKey(@PathVariable String provider) {
        providersService.clearKey(provider);
        return providersService.providers();
    }

    @GetMapping("/privacy")
    public Dto.Privacy privacy() {
        return privacyService.privacy();
    }

    @GetMapping("/brainstorm")
    public Dto.Brainstorm brainstorm() {
        return brainstormService.overview();
    }

    @GetMapping("/brainstorm/sessions/{id}")
    public Dto.BrainstormSession brainstormSession(@PathVariable String id) {
        return brainstormService.session(id);
    }

    /** Create a new brainstorming session (persisted). */
    @PostMapping("/brainstorm/sessions")
    public Dto.BrainstormSession brainstormNewSession(@RequestBody(required = false) Dto.NewSession body) {
        return brainstormService.createSession(
                body == null ? null : body.title(),
                body == null ? null : body.repoPath(),
                body == null ? null : body.model());
    }

    /** Rename a brainstorming session. */
    @org.springframework.web.bind.annotation.PutMapping("/brainstorm/sessions/{id}")
    public Dto.BrainstormSession brainstormRename(@PathVariable String id, @RequestBody Dto.NewSession body) {
        return brainstormService.renameSession(id, body == null ? null : body.title());
    }

    /** Repo-bound sessions (grouped in the UI under their repo). */
    @GetMapping("/brainstorm/repo-sessions")
    public List<Dto.RepoSession> brainstormRepoSessions() {
        return brainstormService.repoSessions();
    }

    /** Delete a brainstorming session and its messages. */
    @DeleteMapping("/brainstorm/sessions/{id}")
    public Map<String, Object> brainstormDeleteSession(@PathVariable String id) {
        brainstormService.deleteSession(id);
        return Map.<String, Object>of("deleted", id);
    }

    /** Add a context item (work item / file / note) to a session. */
    @PostMapping("/brainstorm/sessions/{id}/context")
    public Dto.BrainstormSession brainstormAddContext(@PathVariable String id, @RequestBody Dto.ContextAdd body) {
        return brainstormService.addContext(id, body);
    }

    @DeleteMapping("/brainstorm/sessions/{id}/context/{ctxId}")
    public Dto.BrainstormSession brainstormRemoveContext(@PathVariable String id, @PathVariable String ctxId) {
        return brainstormService.removeContext(id, ctxId);
    }

    /** Open an interactive Claude Code terminal (claude-cli mode) for a session → cwd + session id. */
    @PostMapping("/brainstorm/sessions/{id}/terminal")
    public Map<String, Object> brainstormTerminal(@PathVariable String id) {
        return brainstormService.terminalInfo(id);
    }

    /** Send a brainstorm message + attached sources → local-model reply (SPEC §Brainstorming). */
    @PostMapping("/brainstorm/messages")
    public Dto.BrainstormMessage brainstormSend(@RequestBody Dto.BrainstormSend body) {
        return brainstormService.reply(body);
    }

    /** Streaming reply: `delta` events with text chunks, then a `done` event with the final. */
    @PostMapping("/brainstorm/messages/stream")
    public SseEmitter brainstormStream(@RequestBody Dto.BrainstormSend body) {
        SseEmitter emitter = new SseEmitter(900_000L);
        sse.execute(() -> {
            try {
                brainstormService.replyStreaming(body,
                        delta -> send(emitter, "delta", Map.of("t", delta)),
                        (text, model) -> send(emitter, "done", Map.of("text", text, "model", model)));
                emitter.complete();
            } catch (Exception e) {
                log.warn("Brainstorm stream failed: {}", e.getMessage());
                send(emitter, "error", Map.of("error", String.valueOf(e.getMessage())));
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (Exception ignore) {
            // client disconnected mid-stream
        }
    }

    @GetMapping("/onboarding")
    public List<Dto.OnboardStep> onboarding() {
        return onboardingService.steps();
    }
}
