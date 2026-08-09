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

    public RepoService(GitRepoRepository repos, HostAgentClient agent, AuditService audit) {
        this.repos = repos;
        this.agent = agent;
        this.audit = audit;
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

    public Map<String, Object> push(String id) {
        return agent.push(pathOf(id));
    }

    public Map<String, Object> pr(String id) {
        return agent.pr(pathOf(id));
    }

    // ---- helpers ----

    private GitRepoEntity persist(Map<String, Object> info) {
        String path = str(info, "path");
        if (path.isBlank()) return null;
        return repos.findByPath(path).orElseGet(() ->
                repos.save(GitRepoEntity.of(path, str(info, "name"), str(info, "host"))));
    }

    private Dto.RepoView enrich(GitRepoEntity r) {
        try {
            return view(r, agent.status(r.getPath()));
        } catch (Exception e) {
            return stored(r);
        }
    }

    private Dto.RepoView view(GitRepoEntity r, Map<String, Object> info) {
        Map<String, Object> user = asMap(info.get("user"));
        return new Dto.RepoView(
                String.valueOf(r.getId()), r.getPath(), str(info, "name").isBlank() ? r.getName() : str(info, "name"),
                str(info, "host"), str(info, "slug"), str(info, "branch"), str(info, "remote"),
                Boolean.TRUE.equals(info.get("dirty")), intOf(info.get("ahead")), intOf(info.get("behind")),
                str(user, "name"), str(user, "email"), true);
    }

    private Dto.RepoView stored(GitRepoEntity r) {
        return new Dto.RepoView(String.valueOf(r.getId()), r.getPath(), r.getName(),
                r.getHost(), "", "", "", false, 0, 0, "", "", false);
    }

    private String pathOf(String id) {
        return repos.findById(parse(id)).map(GitRepoEntity::getPath).orElseThrow();
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
