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

    public FleetService(AgentRunRepository runs, GitRepoRepository repos,
                        HostAgentClient agent, AuditService audit) {
        this.runs = runs;
        this.repos = repos;
        this.agent = agent;
        this.audit = audit;
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

    /** Apply an isolated run's result: commit its edits onto its branch, drop the worktree. */
    @Transactional
    public Dto.AgentRun apply(String id) {
        AgentRunEntity run = runs.findById(parse(id)).orElseThrow();
        if (run.isIsolated() && run.getBranch() != null) {
            agent.worktreeFinalize(run.getRepoPath(), run.getRunDir(), run.getBranch(), "apply");
            run.setResultSummary("Applied — changes are on branch " + run.getBranch()
                    + " (check it out in Repos to review, commit and push).\n\n" + nz(run.getResultSummary()));
        }
        run.setStatus("done");
        audit.record("fleet_apply", run.getRepoPath(), run.getBranch());
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

    /** Remove a run from the board (does not touch any files it produced). */
    @Transactional
    public void delete(String id) {
        runs.findById(parse(id)).ifPresent(runs::delete);
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
    public void endInteractive(Long brainstormSessionId) {
        for (AgentRunEntity r : runs.findAll()) {
            if ("interactive".equals(r.getKind()) && brainstormSessionId.equals(r.getBrainstormSessionId())
                    && "active".equals(r.getStatus())) {
                r.setStatus("ended");
                r.setFinishedAt(Instant.now());
                runs.save(r);
            }
        }
    }


    /** Poll running background runs and transition them as the host agent's process finishes. */
    @Scheduled(fixedRate = 10_000)
    @Transactional
    public void poll() {
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
                }
                case "failed" -> {
                    run.setStatus("failed");
                    run.setError(str(st.get("error")));
                    run.setFinishedAt(Instant.now());
                }
                case "canceled" -> {
                    run.setStatus("canceled");
                    run.setFinishedAt(Instant.now());
                }
                case "unknown" -> {
                    run.setStatus("failed");
                    run.setError("run lost — the host agent restarted while it was running");
                    run.setFinishedAt(Instant.now());
                }
                default -> { /* still running */ }
            }
            runs.save(run);
        }
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
                r.getBrainstormSessionId() == null ? null : String.valueOf(r.getBrainstormSessionId()));
    }

    private static String iso(Instant t) { return t == null ? null : t.toString(); }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static Long parse(String id) {
        try { return Long.valueOf(id); } catch (Exception e) { return -1L; }
    }
}
