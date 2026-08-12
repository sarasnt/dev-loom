package com.devloom.fleet;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devloom.ai.HostAgentClient;
import com.devloom.api.Dto;
import com.devloom.audit.AuditService;
import com.devloom.briefing.NotificationService;
import com.devloom.repos.GitRepoEntity;
import com.devloom.repos.GitRepoRepository;

/**
 * The Fleet: launches and tracks concurrent AI runs (spec §4–§8). Slice 2 covers headless
 * background runs in the repo's main checkout; a poller transitions them to review/failed as the
 * host agent's `claude -p` process finishes. Worktree isolation + diff review arrive in Slice 4.
 */
@Service
public class FleetService {

    private static final Logger log = LoggerFactory.getLogger(FleetService.class);

    private final AgentRunRepository runs;
    private final GitRepoRepository repos;
    private final HostAgentClient agent;
    private final AuditService audit;
    private final NotificationService notifications;
    private final com.devloom.brainstorm.BrainstormSessionRepository brainstormSessions;
    private final com.devloom.ai.LlmRouter llm;
    private final com.devloom.ai.McpTools mcp;
    /** Local/API model runs execute here so they survive the request that launched them. */
    private final java.util.concurrent.ExecutorService pool =
            java.util.concurrent.Executors.newFixedThreadPool(4);

    public FleetService(AgentRunRepository runs, GitRepoRepository repos,
                        HostAgentClient agent, AuditService audit, NotificationService notifications,
                        com.devloom.brainstorm.BrainstormSessionRepository brainstormSessions,
                        com.devloom.ai.LlmRouter llm, com.devloom.ai.McpTools mcp) {
        this.runs = runs;
        this.repos = repos;
        this.agent = agent;
        this.audit = audit;
        this.notifications = notifications;
        this.brainstormSessions = brainstormSessions;
        this.llm = llm;
        this.mcp = mcp;
    }

    public List<Dto.AgentRun> list() {
        return runs.findAllByOrderByCreatedAtDesc().stream().map(FleetService::toDto).toList();
    }

    public Dto.AgentRun get(String id) {
        return runs.findById(parse(id)).map(FleetService::toDto).orElseThrow();
    }

    /**
     * Launch a background run. An edit run either runs in its own worktree (isolated — safe to run
     * many at once) or, if isolation is off, in the main checkout which must be clean.
     */
    @Transactional
    public Dto.AgentRun launch(Dto.RunLaunch body) {
        GitRepoEntity repo = repos.findById(parse(body.repoId())).orElseThrow();
        String path = repo.getPath();
        String permission = "edit".equals(body.permission()) ? "edit" : "readonly";
        if (!usesClaudeCli(body.model())) {
            return launchLocal(repo, body, permission);
        }
        boolean isolate = body.isolate() && "edit".equals(permission); // isolation only matters for edits

        if ("edit".equals(permission) && !isolate) {
            Map<String, Object> status = agent.status(path);
            if (Boolean.TRUE.equals(status.get("dirty"))) {
                throw new IllegalStateException(
                        "Working tree has uncommitted changes — commit/stash first, turn on worktree "
                                + "isolation, or use a read-only run.");
            }
        }

        String title = titleFrom(body.prompt(), repo.getName());
        AgentRunEntity run = AgentRunEntity.background(title, path, body.model(), permission, body.allowTests());
        run = runs.save(run);

        String cwd = path;
        if (isolate) {
            String branch = "devloom/run-" + run.getId();
            try {
                Map<String, Object> wt = agent.worktreeAdd(path, branch);
                if (!Boolean.TRUE.equals(wt.get("ok")) || wt.get("path") == null) {
                    run.setStatus("failed");
                    run.setError("could not create worktree: " + str(wt.get("error")));
                    run.setFinishedAt(Instant.now());
                    return toDto(runs.save(run));
                }
                cwd = String.valueOf(wt.get("path"));
                run.setIsolated(true);
                run.setBranch(branch);
                run.setRunDir(cwd);
                run = runs.save(run);
            } catch (Exception e) {
                run.setStatus("failed");
                run.setError("could not create worktree: " + e.getMessage());
                run.setFinishedAt(Instant.now());
                return toDto(runs.save(run));
            }
        }

        try {
            Map<String, Object> res = agent.startRun(cwd, body.prompt(), body.model(), permission, body.allowTests());
            Object runId = res.get("runId");
            if (runId == null) {
                run.setStatus("failed");
                run.setError("host agent did not return a run id");
                run.setFinishedAt(Instant.now());
            } else {
                run.setAgentRunId(String.valueOf(runId));
            }
        } catch (Exception e) {
            run.setStatus("failed");
            run.setError("could not start run: " + e.getMessage());
            run.setFinishedAt(Instant.now());
        }
        runs.save(run);
        audit.record("fleet_launch", path, permission + (isolate ? " · isolated" : "") + " · " + title);
        return toDto(run);
    }

    /**
     * Whether a model runs through the Claude Code CLI (`claude -p`, the agentic path that can
     * edit files). Null = the CLI default. Everything else — local Ollama models, OpenAI models —
     * goes through {@link #launchLocal}, which can analyse a repo but not edit it.
     */
    private static boolean usesClaudeCli(String model) {
        if (model == null || model.isBlank()) return true;
        String m = model.toLowerCase();
        return m.startsWith("claude") || m.equals("sonnet") || m.equals("opus") || m.equals("haiku");
    }

    /**
     * Background run on a non-CLI model (local Ollama, or a keyed API model). These have no
     * agentic tool loop, so they <em>analyse</em> the repo and report back — they never edit it.
     * The work runs on a pool thread, so navigating away (or closing the tab) doesn't stop it;
     * the board shows it running and flips it to review when the model answers.
     */
    private Dto.AgentRun launchLocal(GitRepoEntity repo, Dto.RunLaunch body, String permission) {
        if ("edit".equals(permission)) {
            throw new IllegalStateException(
                    "This model can only run read-only analysis — it has no tool loop to edit files. "
                            + "Pick a Claude model for an edit run, or switch this run to read-only.");
        }
        String title = titleFrom(body.prompt(), repo.getName());
        AgentRunEntity run = AgentRunEntity.background(title, repo.getPath(), body.model(), "readonly", false);
        run = runs.save(run);
        final Long id = run.getId();
        final String prompt = body.prompt();
        final String model = body.model();
        final String path = repo.getPath();
        pool.submit(() -> runLocal(id, path, prompt, model));
        audit.record("fleet_launch", path, "local · " + model + " · " + title);
        return toDto(run);
    }

    /** Executes a local/API analysis run off-request and records its outcome. */
    private void runLocal(Long id, String path, String prompt, String model) {
        try {
            // No needs-input marker here on purpose: a one-shot analysis has no channel to answer
            // through, so flagging it would offer the user an action they can't take. If the model
            // lacks context it says so in the result and the user re-runs with more.
            // Every analysis run can read its repository now (RepoTools is always available), so
            // there is one prompt rather than one per tool availability.
            String system = """
                    You are a background analysis agent inspecting a git repository for an engineer.

                    The summary below is only a starting point. You can read the repository: list
                    its files, read any file, and search across them. Read what the question is
                    actually about before answering it — an answer you inferred without reading the
                    file is the failure mode here. Never state a version, name or value you have not
                    seen; if the repository does not contain the answer, say so plainly.

                    Be concrete and technical, and quote the code you rely on.

                    This runs unattended: nobody will read a follow-up question. Never end by
                    offering choices or asking how to proceed — do the work and give the answer.""";
            // The closing instruction sits at the very end of the user turn, not in the system
            // message: small local models weight recency heavily, and from mid-prompt the same
            // sentence loses to their chat-assistant habit of ending on "would you like me to…".
            String full = repoContext(path) + "\n\nTask:\n" + (prompt == null ? "" : prompt)
                    + "\n\nAnswer the task above directly. Do not end with questions or offers of help.";
            com.devloom.ai.LlmPort.LlmResult r =
                    llm.generate(new com.devloom.ai.LlmPort.LlmRequest("fleet", system, full, model, path));
            String text = r.text() == null ? "" : r.text();
            finishLocal(id, text.replace("[DEVLOOM:INPUT]", "").stripTrailing(), null, false,
                    r.telemetry(), com.devloom.ai.RunQuality.score(r.telemetry(), text, true));
        } catch (Exception e) {
            finishLocal(id, null, e.getMessage() == null ? "run failed" : e.getMessage(), false);
        }
    }

    /** Persist a local run's outcome (runs on a pool thread — the repository save opens its own tx). */
    private void finishLocal(Long id, String result, String error, boolean needsInput) {
        finishLocal(id, result, error, needsInput, null, null);
    }

    private void finishLocal(Long id, String result, String error, boolean needsInput,
                             com.devloom.ai.ToolTelemetry tel, com.devloom.ai.RunQuality.Score score) {
        AgentRunEntity run = runs.findById(id).orElse(null);
        if (run == null) return;
        if ("canceled".equals(run.getStatus())) return; // the user let go of it while it ran
        run.setFinishedAt(Instant.now());
        // How it went about the work, kept next to the answer so the board can show that a run
        // answered without opening a file.
        if (tel != null) {
            run.setToolCalls(tel.toolCalls());
            run.setToolRepeats(tel.repeatedCalls());
        }
        if (score != null) {
            run.setQualityScore(java.math.BigDecimal.valueOf(score.value()));
            run.setQualityNotes(score.summary());
        }
        if (error != null) {
            run.setStatus("failed");
            run.setError(error);
            runs.save(run);
            notifications.notify("Run failed · " + repoName(run.getRepoPath()), run.getTitle(), true);
            return;
        }
        run.setResultSummary(result);
        run.setStatus(needsInput ? "input" : "review");
        runs.save(run);
        notifications.notify(needsInput ? "Run needs your input" : "Run finished · review in Fleet",
                run.getTitle(), false);
    }

    /** Read-only snapshot of a repo the analysis model can reason over. */
    private String repoContext(String path) {
        StringBuilder sb = new StringBuilder("Repository: ").append(path).append('\n');
        try {
            Map<String, Object> st = agent.status(path);
            sb.append("Branch: ").append(str(st.get("branch"))).append('\n');
            sb.append("Remote: ").append(str(st.get("slug"))).append('\n');
            sb.append("Working tree: ").append(Boolean.TRUE.equals(st.get("dirty")) ? "has changes" : "clean")
              .append(" (staged ").append(st.get("staged")).append(", unstaged ").append(st.get("unstaged"))
              .append(", untracked ").append(st.get("untracked")).append(")\n");
        } catch (Exception e) {
            sb.append("(repo status unavailable — the host agent may be offline)\n");
        }
        // The file list up front is what keeps a model off directory-listing tools, whose output is
        // mostly .git internals — noise it then describes back instead of the project.
        try {
            Map<String, Object> f = agent.files(path, 200);
            if (f.get("files") instanceof List<?> list && !list.isEmpty()) {
                // Give the count, not just the list — same reason RepoTools does.
                sb.append("\nTracked files (").append(f.get("total") == null ? list.size() : f.get("total"));
                if (Boolean.TRUE.equals(f.get("truncated"))) sb.append(" in total, first ").append(list.size()).append(" shown");
                sb.append("):\n");
                for (Object o : list) sb.append("- ").append(o).append('\n');
            }
        } catch (Exception ignore) { /* the file list is helpful, not required */ }
        try {
            Object commits = agent.log(path, null, false, 15).get("commits");
            if (commits instanceof List<?> list && !list.isEmpty()) {
                sb.append("\nRecent commits:\n");
                for (Object o : list) {
                    if (o instanceof Map<?, ?> c) {
                        sb.append("- ").append(c.get("short")).append(' ').append(c.get("subject")).append('\n');
                    }
                }
            }
        } catch (Exception ignore) { /* history is optional context */ }
        try {
            Map<String, Object> ch = agent.changes(path);
            List<String> files = new java.util.ArrayList<>();
            for (String k : List.of("staged", "unstaged", "untracked")) {
                if (ch.get(k) instanceof List<?> l) {
                    for (Object o : l) {
                        if (o instanceof Map<?, ?> m && m.get("file") != null) files.add(String.valueOf(m.get("file")));
                    }
                }
            }
            if (!files.isEmpty()) sb.append("\nChanged files: ").append(String.join(", ", files)).append('\n');
        } catch (Exception ignore) { /* changes are optional context */ }
        return sb.toString();
    }

    /**
     * Apply an isolated run's result. Mode {@code branch} (default) commits its edits onto its
     * {@code devloom/run-N} branch and drops the worktree; mode {@code patch} applies the diff
     * directly onto the main checkout's current branch (on conflict the worktree is kept and the
     * run stays in review with the error surfaced).
     */
    @Transactional
    public Dto.AgentRun apply(String id, String mode) {
        AgentRunEntity run = runs.findById(parse(id)).orElseThrow();
        boolean patch = "patch".equals(mode);
        if (run.isIsolated() && run.getBranch() != null) {
            Map<String, Object> r = agent.worktreeFinalize(
                    run.getRepoPath(), run.getRunDir(), run.getBranch(), patch ? "patch" : "apply");
            if (patch && !Boolean.TRUE.equals(r.get("ok"))) {
                // Conflict (or failure): keep the run reviewable; the worktree is left intact.
                run.setError("Apply as patch failed: " + str(r.get("error")));
                audit.record("fleet_apply_conflict", run.getRepoPath(), run.getBranch());
                return toDto(runs.save(run));
            }
            run.setResultSummary((patch
                    ? "Applied as a patch onto your current branch — review, commit and push from Repos."
                    : "Applied — changes are on branch " + run.getBranch()
                        + " (check it out in Repos to review, commit and push).")
                    + "\n\n" + nz(run.getResultSummary()));
        }
        run.setStatus("done");
        audit.record("fleet_apply", run.getRepoPath(), (patch ? "patch" : "branch") + " · " + run.getBranch());
        return toDto(runs.save(run));
    }

    /** Discard an isolated run: remove its worktree and branch; nothing is kept. */
    @Transactional
    public Dto.AgentRun discard(String id) {
        AgentRunEntity run = runs.findById(parse(id)).orElseThrow();
        if (run.isIsolated() && run.getBranch() != null) {
            agent.worktreeFinalize(run.getRepoPath(), run.getRunDir(), run.getBranch(), "discard");
            run.setResultSummary("Discarded — worktree and branch removed.\n\n" + nz(run.getResultSummary()));
        }
        run.setStatus("done");
        audit.record("fleet_discard", run.getRepoPath(), run.getBranch());
        return toDto(runs.save(run));
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String repoName(String path) {
        String p = path == null ? "" : path.replace('\\', '/');
        return p.substring(p.lastIndexOf('/') + 1);
    }

    @Transactional
    public Dto.AgentRun cancel(String id) {
        AgentRunEntity run = runs.findById(parse(id)).orElseThrow();
        if (run.getAgentRunId() != null) {
            try { agent.cancelRun(run.getAgentRunId()); } catch (Exception ignore) { /* best effort */ }
        }
        run.setStatus("canceled");
        run.setFinishedAt(Instant.now());
        return toDto(runs.save(run));
    }

    /** The diff a run produced in its working dir (edit runs; empty for read-only). Passthrough of
     *  the host agent's changes shape ({staged,unstaged,untracked} of {file,status}). */
    public Map<String, Object> changes(String id) {
        AgentRunEntity run = runs.findById(parse(id)).orElseThrow();
        String dir = run.getRunDir() == null ? run.getRepoPath() : run.getRunDir();
        try {
            return agent.changes(dir);
        } catch (Exception e) {
            return Map.of("staged", List.of(), "unstaged", List.of(), "untracked", List.of());
        }
    }

    /** Re-run: launch a fresh run with the same parameters. */
    @Transactional
    public Dto.AgentRun rerun(String id) {
        AgentRunEntity r = runs.findById(parse(id)).orElseThrow();
        String repoId = repos.findAll().stream()
                .filter(g -> g.getPath().equalsIgnoreCase(r.getRepoPath()))
                .map(g -> String.valueOf(g.getId())).findFirst().orElseThrow();
        // Re-use the original title as the prompt is not stored; the human can refine after.
        return launch(new Dto.RunLaunch(repoId, r.getTitle(), r.getModel(),
                r.getPermission() == null ? "readonly" : r.getPermission(), r.isAllowTests(), false));
    }

    /** Remove a run from the board, cleaning up an isolated run's leftover worktree/branch. */
    @Transactional
    public void delete(String id) {
        runs.findById(parse(id)).ifPresent(run -> {
            // A dismissed review/failed run that was never applied still owns a worktree — reap it.
            if (!"done".equals(run.getStatus())) cleanupWorktree(run);
            runs.delete(run);
        });
    }

    // ---- interactive runs (claude-cli sessions surface on the board) ----

    /** Record an interactive terminal run so it appears on the Fleet board. */
    @Transactional
    public void recordInteractive(String title, String repoPath, Long brainstormSessionId, String claudeSessionId) {
        AgentRunEntity e = AgentRunEntity.interactive(title, repoPath == null ? "" : repoPath,
                brainstormSessionId, claudeSessionId);
        runs.save(e);
    }

    /** Mark an interactive run ended (e.g. its brainstorm session was deleted). */
    @Transactional
    /**
     * End every run belonging to a brainstorm session that's going away. Previously this only
     * matched kind=interactive in status=active, so a terminal in "running" and any chat run
     * survived their own session — sitting on the board as live work you could click, which
     * then opened an empty new terminal because the session it pointed at was gone.
     */
    public void endInteractive(Long brainstormSessionId) {
        for (AgentRunEntity r : runs.findAll()) {
            if (brainstormSessionId.equals(r.getBrainstormSessionId()) && !isFinished(r.getStatus())) {
                endOrphan(r);
            }
        }
    }

    private static boolean isFinished(String status) {
        return "ended".equals(status) || "failed".equals(status) || "done".equals(status);
    }

    private void endOrphan(AgentRunEntity r) {
        r.setStatus("ended");
        r.setFinishedAt(Instant.now());
        // Drop the dangling link, so nothing tries to route back into a session that isn't there.
        r.setBrainstormSessionId(null);
        runs.save(r);
    }


    /**
     * A run pointing at a brainstorm session that no longer exists has nothing left to show and
     * nowhere to open. Deletion is the one path that creates these, but reaping them here also
     * clears rows orphaned before that path was fixed, rather than leaving the user to clean up.
     */
    private void reapOrphans() {
        for (AgentRunEntity r : runs.findAll()) {
            Long sid = r.getBrainstormSessionId();
            if (sid == null || brainstormSessions.existsById(sid)) continue;
            if (isFinished(r.getStatus())) {
                // Already finished, so nothing to end — but the dead link still has to go, or
                // clicking the row in Recent navigates to a session that isn't there.
                r.setBrainstormSessionId(null);
                runs.save(r);
                continue;
            }
            log.info("Fleet run {} points at deleted brainstorm session {} — ending it", r.getId(), sid);
            endOrphan(r);
        }
    }

    /** Poll running background runs and transition them as the host agent's process finishes. */
    @Scheduled(fixedRate = 10_000)
    @Transactional
    public void poll() {
        reapOrphans();
        for (AgentRunEntity run : runs.findByStatus("running")) {
            if (run.getAgentRunId() == null) continue;
            Map<String, Object> st;
            try {
                st = agent.runStatus(run.getAgentRunId());
            } catch (Exception e) {
                continue; // agent unreachable — try again next tick
            }
            String s = String.valueOf(st.get("status"));
            switch (s) {
                case "done" -> {
                    run.setStatus("review"); // finished — awaiting the user's review
                    run.setResultSummary(str(st.get("result")));
                    if (st.get("sessionId") != null) run.setClaudeSessionId(str(st.get("sessionId")));
                    run.setFinishedAt(Instant.now());
                    notifications.notify("Run finished · review in Fleet", run.getTitle(), false);
                }
                case "failed" -> {
                    run.setStatus("failed");
                    run.setError(str(st.get("error")));
                    run.setFinishedAt(Instant.now());
                    notifications.notify("Run failed · " + repoName(run.getRepoPath()), run.getTitle(), true);
                }
                case "canceled" -> {
                    run.setStatus("canceled");
                    run.setFinishedAt(Instant.now());
                }
                case "unknown" -> {
                    run.setStatus("failed");
                    run.setError("run lost — the host agent restarted while it was running");
                    run.setFinishedAt(Instant.now());
                    cleanupWorktree(run); // nothing meaningful in a lost run's worktree
                }
                default -> { /* still running */ }
            }
            runs.save(run);
        }
        syncInteractive();
        reapStaleChats();
    }

    /**
     * Keep interactive terminal runs in step with the host agent's live PTYs: attached → active,
     * detached-but-alive → running, detached + silent for a while → input ("may need you"),
     * previously-alive PTY now gone → ended.
     */
    private void syncInteractive() {
        Map<String, Map<String, Object>> ptys = new java.util.HashMap<>();
        try {
            Object list = agent.ptySessions().get("sessions");
            if (list instanceof List<?> items) {
                for (Object o : items) {
                    if (o instanceof Map<?, ?> m && m.get("sessionId") != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> mm = (Map<String, Object>) m;
                        ptys.put(String.valueOf(m.get("sessionId")), mm);
                    }
                }
            }
        } catch (Exception e) {
            return; // agent unreachable — try next tick
        }
        adoptLiveTerminals(ptys.keySet());
        List<AgentRunEntity> live = runs.findByStatusIn(List.of("active", "running", "input")).stream()
                .filter(r -> "interactive".equals(r.getKind())).toList();
        if (live.isEmpty()) return;
        for (AgentRunEntity run : live) {
            // The PTY is keyed by the claude session id; pick it up from the brainstorm session
            // once the terminal has actually been opened.
            if (run.getClaudeSessionId() == null && run.getBrainstormSessionId() != null) {
                brainstormSessions.findById(run.getBrainstormSessionId())
                        .map(s -> s.getClaudeSessionId())
                        .ifPresent(run::setClaudeSessionId);
            }
            if (run.getClaudeSessionId() == null) continue;
            Map<String, Object> pty = ptys.get(run.getClaudeSessionId());
            String next;
            if (pty != null && Boolean.TRUE.equals(pty.get("alive"))) {
                boolean attached = Boolean.TRUE.equals(pty.get("attached"));
                // Primary signal: claude explicitly emitted the needs-input marker. Fallback: a
                // detached terminal that has been silent for a while probably wants the user too.
                boolean needsInput = Boolean.TRUE.equals(pty.get("needsInput"));
                long idle = pty.get("idleMs") instanceof Number n ? n.longValue() : 0;
                next = attached ? "active" : (needsInput || idle >= 90_000 ? "input" : "running");
            } else if (!"active".equals(run.getStatus())) {
                // We saw a live detached PTY before and it's gone now → the terminal exited.
                next = "ended";
            } else {
                continue; // 'active' with no PTY yet — terminal simply not opened; leave it be
            }
            if (!next.equals(run.getStatus())) {
                run.setStatus(next);
                if ("ended".equals(next)) run.setFinishedAt(Instant.now());
                if ("input".equals(next)) {
                    notifications.notify("Agent may need you", run.getTitle() + " — terminal is idle", false);
                }
                runs.save(run);
            }
        }
    }

    /**
     * Adopt terminals the board doesn't know about yet: any live PTY whose brainstorm session has
     * no interactive run (sessions started before Fleet existed, or after a DB reset) gets a row
     * here. The live PTY list is the source of truth, so the board reflects what is actually
     * running rather than only what DevLoom happened to record at launch time.
     */
    private void adoptLiveTerminals(java.util.Set<String> liveSessionIds) {
        if (liveSessionIds.isEmpty()) return;
        // Index every interactive run we already have, so a terminal is never duplicated: an
        // ended row whose terminal is alive again is revived rather than re-created.
        Map<String, AgentRunEntity> bySession = new java.util.HashMap<>();
        java.util.Set<Long> liveBrainstormIds = new java.util.HashSet<>();
        for (AgentRunEntity r : runs.findAll()) {
            if (!"interactive".equals(r.getKind())) continue;
            if (r.getClaudeSessionId() != null) bySession.putIfAbsent(r.getClaudeSessionId(), r);
            if (r.getBrainstormSessionId() != null
                    && List.of("active", "running", "input").contains(r.getStatus())) {
                liveBrainstormIds.add(r.getBrainstormSessionId());
            }
        }
        for (com.devloom.brainstorm.BrainstormSessionEntity s : brainstormSessions.findAll()) {
            String sid = s.getClaudeSessionId();
            if (sid == null || !liveSessionIds.contains(sid)) continue; // no live terminal for it
            AgentRunEntity existing = bySession.get(sid);
            if (existing != null) {
                if (!List.of("active", "running", "input").contains(existing.getStatus())) {
                    existing.setStatus("running"); // terminal is alive again — put it back on the board
                    existing.setFinishedAt(null);
                    runs.save(existing);
                }
                continue;
            }
            if (liveBrainstormIds.contains(s.getId())) continue; // row exists, id not yet linked
            AgentRunEntity run = AgentRunEntity.interactive(
                    s.getTitle() == null || s.getTitle().isBlank() ? "Terminal" : s.getTitle(),
                    s.getRepoPath() == null ? "" : s.getRepoPath(), s.getId(), sid);
            run.setStatus("running"); // discovered mid-flight; the status pass refines it
            runs.save(run);
            bySession.put(sid, run);
        }
    }

    /**
     * In-process work (chat turns, local analysis runs) lives on a thread, not in the host agent —
     * so a backend restart orphans it. Anything still "running" long past any plausible finish is
     * marked failed rather than left spinning on the board forever.
     */
    private void reapStaleChats() {
        for (AgentRunEntity run : runs.findByStatus("running")) {
            boolean inProcess = "chat".equals(run.getKind())
                    || ("background".equals(run.getKind()) && run.getAgentRunId() == null);
            if (!inProcess) continue;
            Instant started = run.getStartedAt() == null ? run.getCreatedAt() : run.getStartedAt();
            if (started.isBefore(Instant.now().minusSeconds(30 * 60))) {
                run.setStatus("failed");
                run.setError("lost (the backend restarted while it was generating)");
                run.setFinishedAt(Instant.now());
                runs.save(run);
            }
        }
    }

    /** Best-effort removal of an isolated run's worktree + branch (used for lost runs + dismiss). */
    private void cleanupWorktree(AgentRunEntity run) {
        if (!run.isIsolated() || run.getBranch() == null) return;
        try {
            agent.worktreeFinalize(run.getRepoPath(), run.getRunDir(), run.getBranch(), "discard");
        } catch (Exception ignore) { /* agent offline — a later dismiss retries */ }
    }

    // ---- chat turns (local-model visibility) ----

    /**
     * Record an in-flight chat turn so it shows on the board while a (slow) model generates.
     * Answering also clears a previous "needs input" row for the same session — the user is
     * replying, so the model is no longer blocked on them.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Long chatStarted(String title, String repoPath, String model, Long brainstormSessionId) {
        if (brainstormSessionId != null) {
            for (AgentRunEntity r : runs.findByStatus("input")) {
                if ("chat".equals(r.getKind()) && brainstormSessionId.equals(r.getBrainstormSessionId())) {
                    runs.delete(r);
                }
            }
        }
        return runs.save(AgentRunEntity.chat(title, repoPath, model, brainstormSessionId)).getId();
    }

    /**
     * Close out a chat-turn row. A model that signalled it is blocked on the user keeps its row as
     * "needs input" so the Fleet can route them back; an ordinary success removes it (the reply
     * lives in the brainstorm session — the row's only job was live visibility); a failure stays
     * on the board so the user knows.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void chatFinished(Long id, boolean ok, String error, boolean needsInput) {
        if (id == null) return;
        runs.findById(id).ifPresent(run -> {
            if (ok && needsInput) {
                run.setStatus("input");
                run.setFinishedAt(Instant.now());
                runs.save(run);
                notifications.notify("Agent needs your input", run.getTitle(), false);
                return;
            }
            if (ok) {
                runs.delete(run);
                return;
            }
            run.setStatus("failed");
            run.setError(error);
            run.setFinishedAt(Instant.now());
            runs.save(run);
        });
    }

    // ---- helpers ----

    private static String titleFrom(String prompt, String repoName) {
        String p = prompt == null ? "" : prompt.strip().replaceAll("\\s+", " ");
        if (p.isBlank()) return "Run · " + repoName;
        return p.length() > 80 ? p.substring(0, 80) + "…" : p;
    }

    private static Dto.AgentRun toDto(AgentRunEntity r) {
        return new Dto.AgentRun(
                String.valueOf(r.getId()), r.getTitle(), r.getRepoPath(), r.getRunDir(), r.getBranch(),
                r.getKind(), r.getPermission(), r.isAllowTests(), r.isIsolated(), r.getModel(),
                r.getStatus(), r.getResultSummary(), r.getError(),
                iso(r.getCreatedAt()), iso(r.getStartedAt()), iso(r.getFinishedAt()),
                r.getClaudeSessionId(),
                r.getBrainstormSessionId() == null ? null : String.valueOf(r.getBrainstormSessionId()),
                r.getQualityScore() == null ? null : r.getQualityScore().doubleValue(),
                r.getQualityNotes(), r.getToolCalls(), r.getToolRepeats());
    }

    private static String iso(Instant t) { return t == null ? null : t.toString(); }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static Long parse(String id) {
        try { return Long.valueOf(id); } catch (Exception e) { return -1L; }
    }
}
