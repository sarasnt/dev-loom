package com.devloom.api;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import com.devloom.integrations.GitHubBuildAnalyzer;
import com.devloom.workmodel.WorkItemEntity;
import com.devloom.workmodel.WorkItemRepository;

/**
 * Assembles the build-failure analysis (SPEC.md §23) from a <em>real</em> GitHub Actions run.
 * The run is resolved either from an explicit run id or from the most recent failed-CI work
 * item in the unified model; {@link GitHubBuildAnalyzer} then fetches the run, its failed
 * job/step and the redacted log tail, and summarizes via the local model. When there is no
 * failing run (or GitHub isn't configured) an honest empty state is returned — never fixtures.
 */
@Service
public class BuildFailureService {

    private final GitHubBuildAnalyzer ghAnalyzer;
    private final WorkItemRepository workItems;

    public BuildFailureService(GitHubBuildAnalyzer ghAnalyzer, WorkItemRepository workItems) {
        this.ghAnalyzer = ghAnalyzer;
        this.workItems = workItems;
    }

    public Dto.BuildFailure analyze(String id) {
        return analyze(id, s -> {});
    }

    /** As {@link #analyze(String)} but streams stage labels to {@code progress} (SSE). */
    public Dto.BuildFailure analyze(String id, Consumer<String> progress) {
        String runId = resolveRunId(id);
        if (runId == null || !ghAnalyzer.enabled()) {
            return emptyState();
        }
        Optional<WorkItemEntity> item = workItems.findFirstByExtId(runId);
        String repo = item.map(BuildFailureService::metaRepo).orElse(null);
        if (repo == null) {
            return emptyState();
        }
        Dto.BuildFailure real = ghAnalyzer.analyze(repo, runId, progress);
        return real != null ? real : emptyState();
    }

    /**
     * Resolve the run to analyze: an explicit numeric run id if given, otherwise the freshest
     * failed-CI work item. Legacy/non-numeric ids (old fixture ids, handoff ids) fall through
     * to "latest", so the view always lands on a real run when one exists.
     */
    private String resolveRunId(String id) {
        if (id != null && id.matches("\\d{6,}")) {
            return id;
        }
        List<WorkItemEntity> builds = workItems.findByTypeOrderBySortOrderAsc("build");
        return builds.isEmpty() ? null : builds.getFirst().getExtId();
    }

    /** Honest "nothing failing" state — keeps the view useful without inventing a failure. */
    private Dto.BuildFailure emptyState() {
        return new Dto.BuildFailure(
                "none", "—", "—", null, "—", "—",
                new Dto.Boundary("local", "On your machine"),
                "No failing CI runs right now. When a connected repo has a failed GitHub Actions "
                        + "run, it appears here with a redacted log tail and a local-model analysis.",
                "n/a", "—", "—", "—", false,
                List.of(new Dto.LogLine("(no build failures)", "omitted")),
                List.of(), List.of(),
                List.of("Connect a GitHub repo with CI, or open a PR that triggers a workflow."),
                List.of(), "deterministic");
    }

    /** Build items store meta as "branch,owner/repo" — recover the repo (2nd token). */
    private static String metaRepo(WorkItemEntity w) {
        String[] parts = w.getMetaCsv().split(",");
        return parts.length >= 2 ? parts[1].trim() : null;
    }
}
