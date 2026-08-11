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

    /** Launch a background run. Edit runs in the main checkout require a clean working tree. */
    @Transactional
    public Dto.AgentRun launch(Dto.RunLaunch body) {
        GitRepoEntity repo = repos.findById(parse(body.repoId())).orElseThrow();
        String path = repo.getPath();
        String permission = "edit".equals(body.permission()) ? "edit" : "readonly";

        // Slice 2: no worktree isolation yet — an edit run must not clobber uncommitted work.
        if ("edit".equals(permission)) {
            Map<String, Object> status = agent.status(path);
            if (Boolean.TRUE.equals(status.get("dirty"))) {
                throw new IllegalStateException(
                        "Working tree has uncommitted changes — commit/stash first, or use a read-only run "
                                + "(worktree isolation for edit runs lands in a later update).");
            }
        }

        String title = titleFrom(body.prompt(), repo.getName());
        AgentRunEntity run = AgentRunEntity.background(title, path, body.model(), permission, body.allowTests());
        run = runs.save(run);

        try {
            Map<String, Object> res = agent.startRun(path, body.prompt(), body.model(), permission, body.allowTests());
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
        audit.record("fleet_launch", path, permission + " · " + title);
        return toDto(run);
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
                iso(r.getCreatedAt()), iso(r.getStartedAt()), iso(r.getFinishedAt()));
    }

    private static String iso(Instant t) { return t == null ? null : t.toString(); }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static Long parse(String id) {
        try { return Long.valueOf(id); } catch (Exception e) { return -1L; }
    }
}
