package com.devloom.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devloom.ai.HostAgentClient;
import com.devloom.audit.AuditService;
import com.devloom.repos.GitRepoEntity;
import com.devloom.repos.GitRepoRepository;

/**
 * Local git repositories the user manages through DevLoom (docs/SPEC-sources.md §14). Only a
 * pointer + light metadata is persisted; live status and all git actions run on the host via
 * {@link HostAgentClient}. Without the agent running, repos still list (from the DB) with
 * {@code live=false}.
 */
@Service
public class RepoService {

    private final GitRepoRepository repos;
    private final HostAgentClient agent;
    private final AuditService audit;
    private final com.devloom.common.AppConfigService config;

    public RepoService(GitRepoRepository repos, HostAgentClient agent, AuditService audit,
                       com.devloom.common.AppConfigService config) {
        this.repos = repos;
        this.agent = agent;
        this.audit = audit;
        this.config = config;
    }

    public boolean agentUp() {
        return agent.up();
    }

    public List<Dto.RepoView> list() {
        List<Dto.RepoView> out = new ArrayList<>();
        boolean up = agent.up();
        for (GitRepoEntity r : repos.findAll()) {
            out.add(up ? enrich(r) : stored(r));
        }
        return out;
    }

    @Transactional
    public List<Dto.RepoView> addFolder(String root) {
        List<Dto.RepoView> added = new ArrayList<>();
        for (Map<String, Object> info : agent.scan(root)) {
            GitRepoEntity e = persist(info);
            if (e != null) added.add(view(e, info));
        }
        audit.record("repo_scan", root, "found=" + added.size());
        return list();
    }

    /**
     * Scan every parent directory the user configured in Settings and add any git repos not yet
     * tracked (repo-spec follow-up: "Sync"). Manual add/scan still works alongside this.
     */
    @Transactional
    public Map<String, Object> sync() {
        List<String> dirs = config.repoDirs();
        int before = (int) repos.count();
        for (String root : dirs) {
            try {
                for (Map<String, Object> info : agent.scan(root)) persist(info);
            } catch (Exception e) { /* skip an unreachable dir, keep syncing the rest */ }
        }
        int added = (int) repos.count() - before;
        audit.record("repo_sync", String.join(", ", dirs), "added=" + added);
        Map<String, Object> out = new java.util.HashMap<>();
        out.put("added", added);
        out.put("dirs", dirs);
        out.put("agentUp", agent.up());
        out.put("repos", list());
        return out;
    }

    @Transactional
    public List<Dto.RepoView> addRepo(String path) {
        Map<String, Object> info = agent.status(path); // validates it's a git repo (throws if not)
        persist(info);
        audit.record("repo_add", path, null);
        return list();
    }

    @Transactional
    public void delete(String id) {
        repos.findById(parse(id)).ifPresent(r -> {
            repos.delete(r);
            audit.record("repo_remove", r.getPath(), null);
        });
    }

    public Dto.RepoView setIdentity(String id, String name, String email) {
        GitRepoEntity r = repos.findById(parse(id)).orElseThrow();
        agent.setIdentity(r.getPath(), name, email);
        return enrich(r);
    }

    public Map<String, Object> pull(String id) {
        return agent.pull(pathOf(id));
    }

    public Map<String, Object> push(String id, boolean force) {
        Map<String, Object> r = agent.push(pathOf(id), force);
        audit.record(force ? "repo_push_force" : "repo_push", pathOf(id), null);
        return r;
    }

    /** Abort an in-progress merge/rebase/cherry-pick/revert on the repo. */
    public Map<String, Object> abort(String id) {
        Map<String, Object> r = agent.abort(pathOf(id));
        audit.record("repo_abort", pathOf(id), String.valueOf(r.get("operation")));
        return r;
    }

    public Map<String, Object> pr(String id) {
        return agent.pr(pathOf(id));
    }

    public Map<String, Object> browse(String path) {
        return agent.browse(path);
    }

    public Map<String, Object> changes(String id) {
        return agent.changes(pathOf(id));
    }

    public Map<String, Object> stage(String id, List<String> files) {
        return agent.stage(pathOf(id), files);
    }

    public Map<String, Object> unstage(String id, List<String> files) {
        return agent.unstage(pathOf(id), files);
    }

    public Map<String, Object> commit(String id, String message) {
        Map<String, Object> r = agent.commit(pathOf(id), message);
        audit.record("repo_commit", pathOf(id), null);
        return r;
    }

    public Map<String, Object> branches(String id) {
        return agent.branches(pathOf(id));
    }

    /**
     * Where the current branch forks from, and how far HEAD has drifted from it. Resolution
     * order (spec repos §6): saved override for this branch → repository default → unknown.
     * (PR-base resolution is a later slice; {@code origin="pr"} is reserved for it.)
     */
    public Dto.SourceStatus sourceStatus(String id, String branch) {
        GitRepoEntity r = repos.findById(parse(id)).orElseThrow();
        String cur = (branch == null || branch.isBlank()) ? str(agent.status(r.getPath()), "branch") : branch.trim();
        String override = config.get(srcKey(r.getId(), cur)).orElse(null);
        Map<String, Object> s = agent.sourceStatus(r.getPath(), override);
        String source = s.get("source") == null ? null : String.valueOf(s.get("source"));
        String origin = override != null && !override.isBlank() ? "override"
                : (source != null ? "default" : "unknown");
        return new Dto.SourceStatus(
                source, s.get("defaultBranch") == null ? null : String.valueOf(s.get("defaultBranch")),
                origin, Boolean.TRUE.equals(s.get("hasSource")), Boolean.TRUE.equals(s.get("missing")),
                intOf(s.get("sourceAhead")), intOf(s.get("sourceBehind")));
    }

    /** Save (or clear, when source is blank) the source-branch override for the current branch. */
    public Dto.SourceStatus setSource(String id, String branch, String source) {
        GitRepoEntity r = repos.findById(parse(id)).orElseThrow();
        String cur = (branch == null || branch.isBlank()) ? str(agent.status(r.getPath()), "branch") : branch.trim();
        config.set(srcKey(r.getId(), cur), source == null ? null : source.trim());
        audit.record("repo_source", r.getPath(), cur + " -> " + (source == null ? "(default)" : source));
        return sourceStatus(id, cur);
    }

    /** Per-(repo, current-branch) config key for the source override. Bounded to the 120-char PK. */
    private static String srcKey(Long repoId, String branch) {
        String b = branch == null ? "" : branch;
        if (b.length() > 90) b = Integer.toHexString(b.hashCode());
        return "repo.src." + repoId + "." + b;
    }

    /** Refs older than this are shown as "stale" and conflict results flagged for re-check (§7.4). */
    private static final java.time.Duration FRESH_WINDOW = java.time.Duration.ofMinutes(15);

    /** Fetch remote-tracking refs once, then stamp the last-refresh time (repo-spec §7/FR-06). */
    public Dto.RepoFetch fetch(String id) {
        GitRepoEntity r = repos.findById(parse(id)).orElseThrow();
        Map<String, Object> res = agent.fetch(r.getPath());
        boolean ok = Boolean.TRUE.equals(res.get("ok"));
        String at = null;
        if (ok) {
            at = java.time.Instant.now().toString();
            config.set(fetchKey(r.getId()), at);
            audit.record("repo_fetch", r.getPath(), null);
        }
        return new Dto.RepoFetch(ok, res.get("error") == null ? null : String.valueOf(res.get("error")), at);
    }

    /**
     * Predict conflicts from merging the resolved source branch into HEAD (read-only). Carries the
     * last-refresh time and a staleness flag so the UI can prompt a fetch (repo-spec §7.4/FR-05/06).
     */
    public Dto.ConflictStatus conflict(String id, String branch) {
        GitRepoEntity r = repos.findById(parse(id)).orElseThrow();
        String cur = (branch == null || branch.isBlank()) ? str(agent.status(r.getPath()), "branch") : branch.trim();
        String override = config.get(srcKey(r.getId(), cur)).orElse(null);
        Map<String, Object> c = agent.conflict(r.getPath(), override);
        String last = config.get(fetchKey(r.getId())).orElse(null);
        boolean stale = isStale(last);
        @SuppressWarnings("unchecked")
        List<String> files = c.get("files") instanceof List<?> l ? (List<String>) l : List.of();
        return new Dto.ConflictStatus(
                c.get("state") == null ? "unknown" : String.valueOf(c.get("state")),
                files, c.get("ref") == null ? null : String.valueOf(c.get("ref")),
                c.get("reason") == null ? null : String.valueOf(c.get("reason")), last, stale);
    }

    private static boolean isStale(String iso) {
        if (iso == null) return true;
        try { return java.time.Instant.parse(iso).plus(FRESH_WINDOW).isBefore(java.time.Instant.now()); }
        catch (Exception e) { return true; }
    }

    private static String fetchKey(Long repoId) {
        return "repo.fetch." + repoId;
    }

    public Map<String, Object> checkout(String id, String branch, boolean create) {
        return agent.checkout(pathOf(id), branch, create);
    }

    // ---- helpers ----

    private GitRepoEntity persist(Map<String, Object> info) {
        String path = str(info, "path");
        if (path.isBlank()) return null;
        return repos.findByPathIgnoreCase(path).orElseGet(() ->
                repos.save(GitRepoEntity.of(path, str(info, "name"), str(info, "host"))));
    }

    private Dto.RepoView enrich(GitRepoEntity r) {
        try {
            return view(r, agent.status(r.getPath()));
        } catch (Exception e) {
            return stored(r);
        }
    }

    /** Mark a repo local-only (may only be brainstormed with local models) or clear it. */
    public Dto.RepoView setLocalOnly(String id, boolean value) {
        GitRepoEntity r = repos.findById(parse(id)).orElseThrow();
        r.setLocalOnly(value);
        return enrich(repos.save(r));
    }

    private Dto.RepoView view(GitRepoEntity r, Map<String, Object> info) {
        Map<String, Object> user = asMap(info.get("user"));
        Object op = info.get("operation");
        return new Dto.RepoView(
                String.valueOf(r.getId()), r.getPath(), str(info, "name").isBlank() ? r.getName() : str(info, "name"),
                str(info, "host"), str(info, "slug"), str(info, "branch"), str(info, "remote"),
                Boolean.TRUE.equals(info.get("dirty")), intOf(info.get("ahead")), intOf(info.get("behind")),
                str(user, "name"), str(user, "email"), true, r.isLocalOnly(),
                intOf(info.get("staged")), intOf(info.get("unstaged")), intOf(info.get("untracked")),
                Boolean.TRUE.equals(info.get("hasUpstream")),
                info.get("upstream") == null ? null : String.valueOf(info.get("upstream")),
                op == null ? null : String.valueOf(op),
                info.get("commonDir") == null ? "" : String.valueOf(info.get("commonDir")),
                Boolean.TRUE.equals(info.get("isLinkedWorktree")));
    }

    private Dto.RepoView stored(GitRepoEntity r) {
        return new Dto.RepoView(String.valueOf(r.getId()), r.getPath(), r.getName(),
                r.getHost(), "", "", "", false, 0, 0, "", "", false, r.isLocalOnly(),
                0, 0, 0, false, null, null, "", false);
    }

    /** All worktrees of a repo, flagged with whether DevLoom already tracks each one. */
    public List<Dto.WorktreeInfo> worktrees(String id) {
        String path = pathOf(id);
        Map<String, Object> res = agent.worktrees(path);
        List<GitRepoEntity> all = repos.findAll();
        List<Dto.WorktreeInfo> out = new ArrayList<>();
        Object list = res.get("worktrees");
        if (list instanceof List<?> items) {
            for (Object o : items) {
                if (!(o instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> w = (Map<String, Object>) o;
                String wp = str(w, "path");
                String wpn = normPath(wp);
                GitRepoEntity tracked = all.stream()
                        .filter(r -> normPath(r.getPath()).equals(wpn)).findFirst().orElse(null);
                out.add(new Dto.WorktreeInfo(wp,
                        w.get("branch") == null ? null : String.valueOf(w.get("branch")),
                        w.get("head") == null ? null : String.valueOf(w.get("head")),
                        Boolean.TRUE.equals(w.get("bare")), Boolean.TRUE.equals(w.get("detached")),
                        Boolean.TRUE.equals(w.get("locked")), tracked != null,
                        tracked == null ? null : String.valueOf(tracked.getId())));
            }
        }
        return out;
    }

    private String pathOf(String id) {
        return repos.findById(parse(id)).map(GitRepoEntity::getPath).orElseThrow();
    }

    /** Compare filesystem paths ignoring slash direction and case (Windows-friendly). */
    private static String normPath(String p) {
        return p == null ? "" : p.replace('\\', '/').toLowerCase();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private static int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }

    private static Long parse(String id) {
        try {
            return Long.valueOf(id);
        } catch (Exception e) {
            return -1L;
        }
    }
}
