package com.devloom.api;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import com.devloom.integrations.SourceUnavailableException;
import com.devloom.integrations.BitbucketBuildAnalyzer;
import com.devloom.integrations.GitHubBuildAnalyzer;
import com.devloom.integrations.SourceInstanceRepository;
import com.devloom.workmodel.WorkItemEntity;
import com.devloom.workmodel.WorkItemRepository;

/**
 * Assembles the build-failure analysis (SPEC.md §23) from a <em>real</em> build. The item is
 * resolved either from an explicit id or from the most recent failed-CI work item in the unified
 * model, then dispatched by source: {@link GitHubBuildAnalyzer} fetches the run, its failed
 * job/step and the redacted log tail; {@link BitbucketBuildAnalyzer} reasons from build metadata
 * only, since Bitbucket never exposes logs. When there is no failing run (or the source isn't
 * configured) an honest empty state is returned — never fixtures.
 */
@Service
public class BuildFailureService {

    private final GitHubBuildAnalyzer ghAnalyzer;
    private final BitbucketBuildAnalyzer bbAnalyzer;
    private final WorkItemRepository workItems;
    private final SourceInstanceRepository sources;

    public BuildFailureService(GitHubBuildAnalyzer ghAnalyzer, BitbucketBuildAnalyzer bbAnalyzer,
                               WorkItemRepository workItems, SourceInstanceRepository sources) {
        this.ghAnalyzer = ghAnalyzer;
        this.bbAnalyzer = bbAnalyzer;
        this.workItems = workItems;
        this.sources = sources;
    }

    public Dto.BuildFailure analyze(String id) {
        return analyze(id, s -> {}, null);
    }

    /** As {@link #analyze(String)} but streams stage labels to {@code progress} (SSE), and uses
     *  the caller-selected {@code model} (Builds screen) for the analysis. */
    public Dto.BuildFailure analyze(String id, Consumer<String> progress, String model) {
        String runId = resolveRunId(id);
        if (runId == null) {
            return emptyState();
        }
        Optional<WorkItemEntity> item = workItems.findFirstByExtId(runId);
        String repo = item.map(BuildFailureService::metaRepo).orElse(null);
        if (repo == null) {
            return emptyState();
        }
        // Route by the item's source: a Bitbucket failure has no GitHub run to fetch, and vice
        // versa. Anything unrecognized keeps the GitHub path — exactly what it always did.
        // Prefer the stamped source_instance_id (SyncService sets it on every item) over the
        // display-name lookup: a source rename would otherwise silently stop resolving.
        var inst = item.map(WorkItemEntity::getSourceInstanceId)
                .flatMap(sources::findById)
                .or(() -> item.map(WorkItemEntity::getSource).flatMap(sources::findByNameIgnoreCase))
                .orElse(null);
        boolean bitbucket = inst != null && "bitbucket".equalsIgnoreCase(inst.getType());
        // Never fold an unavailable source into emptyState(): "no failing CI runs" and "we could
        // not ask" look identical on screen and mean opposite things.
        if (inst == null || item.isEmpty()) {
            return emptyState();   // nothing resolved to analyze — that really is "nothing to show"
        }
        try {
            Dto.BuildFailure real = bitbucket
                    ? bbAnalyzer.analyze(inst, item.get(), runId, progress, model)
                    : ghAnalyzer.analyze(inst, repo, runId, progress, model);
            return real != null ? real : emptyState();
        } catch (SourceUnavailableException e) {
            return unavailableState(e);
        }
    }

    /**
     * Resolve the run to analyze: an explicit numeric run id if given, otherwise the freshest
     * failed-CI work item. Legacy/non-numeric ids (old fixture ids, handoff ids) fall through
     * to "latest", so the view always lands on a real run when one exists.
     */
    private String resolveRunId(String id) {
        // Explicit ids: a GitHub Actions run id is numeric; a Bitbucket build item is keyed by
        // its head commit SHA. The two shapes cannot collide.
        if (id != null && (id.matches("\\d{6,}") || id.matches("[0-9a-f]{40}"))) {
            return id;
        }
        List<WorkItemEntity> builds = workItems.findByTypeOrderBySortOrderAsc("build");
        return builds.isEmpty() ? null : builds.getFirst().getExtId();
    }

    /**
     * The source could not be reached. Shaped like a build failure so the view needs no new
     * branch, but it says plainly that this is a connectivity problem and not a green build.
     */
    private Dto.BuildFailure unavailableState(SourceUnavailableException e) {
        return new Dto.BuildFailure(
                "unavailable", "—", "—", null, "—", "—",
                new Dto.Boundary("local", "On your machine"),
                e.getMessage() + " Nothing is known about the state of its builds, which is not "
                        + "the same as nothing failing — the failures below are still listed, they "
                        + "just cannot be explained until this source can be consulted.",
                "n/a", "—", "—", "—", false,
                List.of(new Dto.LogLine("(" + e.source() + " unreachable)", "omitted")),
                List.of(), List.of(),
                List.of("A self-hosted server may be reachable only from your machine (VPN or "
                        + "corporate network) while DevLoom's backend runs in a container."),
                List.of("Check this source's credentials in Settings, and that the backend "
                        + "container can reach its base URL."),
                "n/a");
    }

    /** Honest "nothing failing" state — keeps the view useful without inventing a failure. */
    private Dto.BuildFailure emptyState() {
        return new Dto.BuildFailure(
                "none", "—", "—", null, "—", "—",
                new Dto.Boundary("local", "On your machine"),
                "No failing CI runs right now. When a connected repo has a failed GitHub Actions "
                        + "run — or a Bitbucket PR carries a failed build — it appears here with "
                        + "an analysis by the local model.",
                "n/a", "—", "—", "—", false,
                List.of(new Dto.LogLine("(no build failures)", "omitted")),
                List.of(), List.of(),
                List.of("Connect a GitHub repo with CI or a Bitbucket source, or open a PR that triggers a build."),
                List.of(), "deterministic");
    }

    /** Build items store meta as "branch,owner/repo" — recover the repo (2nd token). */
    private static String metaRepo(WorkItemEntity w) {
        String[] parts = w.getMetaCsv().split(",");
        return parts.length >= 2 ? parts[1].trim() : null;
    }
}
