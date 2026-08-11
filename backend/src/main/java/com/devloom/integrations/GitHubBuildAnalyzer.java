package com.devloom.integrations;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.devloom.ai.LlmPort;
import com.devloom.ai.LlmRouter;
import com.devloom.api.Dto;
import com.devloom.common.SecretRedactor;

/**
 * Real build-failure analysis for a GitHub Actions run (SPEC.md §23). Fetches the run, its
 * failed job/step, and the job log; extracts + truncates + redacts the failing region; then
 * summarizes via the local model ({@link LlmRouter}). Returns null when unavailable so the
 * caller can fall back to the sample build.
 */
@Component
public class GitHubBuildAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(GitHubBuildAnalyzer.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};
    private static final String SYSTEM = """
            You are a senior engineer triaging a CI failure. From the failing step and the
            redacted log tail, write 2–3 sentences on the most likely cause. Separate evidence
            from hypothesis. Don't invent details not in the log.""";

    private final boolean enabled;
    private final RestClient http;
    private final SecretRedactor redactor;
    private final LlmRouter llm;

    public GitHubBuildAnalyzer(
            @Value("${devloom.github.base-url:https://api.github.com}") String baseUrl,
            @Value("${devloom.github.token:}") String token,
            SecretRedactor redactor, LlmRouter llm) {
        this.enabled = token != null && !token.isBlank();
        this.redactor = redactor;
        this.llm = llm;
        // The job-log endpoint returns a 302 to a signed blob URL on a different host. The
        // JDK client follows it (NORMAL = follow but drop Authorization on cross-host hops,
        // which is exactly right — the signed URL carries its own auth). RestClient's default
        // client does not follow redirects, which left the log tail empty.
        HttpClient jdk = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.http = enabled
                ? RestClient.builder().baseUrl(baseUrl)
                        .requestFactory(new JdkClientHttpRequestFactory(jdk))
                        .defaultHeader("Authorization", "Bearer " + token)
                        .defaultHeader("Accept", "application/vnd.github+json")
                        .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                        .build()
                : null;
    }

    public boolean enabled() {
        return enabled;
    }

    /** A model summary paired with the model that produced it (for provenance / redo). */
    private record Summary(String text, String model) {}

    /** repo is "owner/name"; runId is the GitHub Actions run id. Returns null on any failure. */
    public Dto.BuildFailure analyze(String repo, String runId) {
        return analyze(repo, runId, s -> {}, null);
    }

    /**
     * As {@link #analyze(String, String)}, but reports each stage to {@code progress} (used to
     * stream real progress over SSE) and summarizes with the caller-selected {@code model}.
     * Runs on the caller's thread.
     */
    public Dto.BuildFailure analyze(String repo, String runId, Consumer<String> progress, String model) {
        if (!enabled) {
            return null;
        }
        try {
            progress.accept("Fetching the failed run from GitHub…");
            // Inline repo/id into the path literal — a "{r}" path var would URL-encode the
            // slash in "owner/name" and 404.
            Map<String, Object> run = http.get().uri("/repos/" + repo + "/actions/runs/" + runId)
                    .retrieve().body(MAP);
            if (run == null) {
                return null;
            }
            String branch = str(run, "head_branch");
            String sha = str(run, "head_sha");
            String runNo = "#" + str(run, "run_number");
            String failedAgo = relative(str(run, "updated_at"));
            String pr = firstPrNumber(run);

            progress.accept("Reading the failing job & step…");
            Map<String, Object> jobsResp = http.get()
                    .uri("/repos/" + repo + "/actions/runs/" + runId + "/jobs")
                    .retrieve().body(MAP);
            List<?> jobs = jobsResp == null ? List.of() : asList(jobsResp.get("jobs"));
            Map<String, Object> failedJob = pickFailedJob(jobs);
            String jobName = str(failedJob, "name");
            String jobId = str(failedJob, "id");
            String failingStep = firstFailedStep(failedJob);

            progress.accept("Redacting the log tail…");
            List<Dto.LogLine> excerpt = logExcerpt(repo, jobId);

            progress.accept("Summarizing with the local model…");
            Summary summary = summarize(jobName, failingStep, excerpt, model);

            List<Dto.EvidenceRef> evidence = List.of(
                    new Dto.EvidenceRef("commit " + (sha.length() >= 8 ? sha.substring(0, 8) : sha), null, "local"),
                    new Dto.EvidenceRef("run " + runNo, null, "local"));
            List<Dto.Hypothesis> causes = List.of(new Dto.Hypothesis(
                    1, "Failure in job \"" + jobName + "\" at step \"" + failingStep + "\"",
                    "med", evidence, false));
            List<Dto.EvidenceRef> related = new ArrayList<>();
            if (!pr.isBlank()) related.add(new Dto.EvidenceRef("PR #" + pr, null, "local"));
            related.add(new Dto.EvidenceRef("commit " + (sha.length() >= 8 ? sha.substring(0, 8) : sha), null, "local"));
            related.add(new Dto.EvidenceRef("run " + runNo, null, "local"));

            return new Dto.BuildFailure(
                    runId, repo, branch, pr.isBlank() ? null : pr, runNo, "failed " + failedAgo,
                    new Dto.Boundary("local", "On your machine"),
                    summary.text(), "med", jobName, failingStep, failingStep, true, excerpt,
                    causes, related,
                    List.of("Re-run the failing step locally: " + failingStep,
                            "Open the run on GitHub to see the full job log."),
                    List.of("Address the failure surfaced in the log tail below."),
                    summary.model());
        } catch (Exception e) {
            log.warn("GitHub build analysis failed for {} run {}: {}", repo, runId, e.getMessage());
            return null;
        }
    }

    private Summary summarize(String jobName, String step, List<Dto.LogLine> excerpt, String model) {
        try {
            String logText = excerpt.stream().map(Dto.LogLine::text).reduce("", (a, b) -> a + "\n" + b);
            String prompt = "Failing job: %s\nFailing step: %s\nRedacted log tail:\n%s"
                    .formatted(jobName, step, logText);
            String m = model == null || model.isBlank() ? null : model;
            LlmPort.LlmResult r = llm.generate(new LlmPort.LlmRequest("build-failure", SYSTEM, prompt, m));
            if (!"stub".equals(r.provider()) && r.text() != null && !r.text().isBlank()) {
                return new Summary(r.text().trim(), r.model());
            }
        } catch (Exception ignore) {
            // fall through to deterministic summary
        }
        return new Summary(
                "CI job \"" + jobName + "\" failed at step \"" + step + "\". See the redacted log tail for the failure.",
                "deterministic");
    }

    /** Job log tail: strip timestamps, keep the last ~45 non-empty lines, redact secrets. */
    private List<Dto.LogLine> logExcerpt(String repo, String jobId) {
        List<Dto.LogLine> out = new ArrayList<>();
        try {
            String raw = http.get().uri("/repos/" + repo + "/actions/jobs/" + jobId + "/logs")
                    .retrieve().body(String.class);
            if (raw == null || raw.isBlank()) {
                out.add(new Dto.LogLine("(log unavailable)", "omitted"));
                return out;
            }
            String[] lines = raw.replace("\r\n", "\n").split("\n");
            List<String> cleaned = new ArrayList<>();
            for (String l : lines) {
                String s = l.replaceFirst("^\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z\\s?", "");
                if (!s.isBlank()) cleaned.add(s);
            }
            int tail = 45;
            int start = Math.max(0, cleaned.size() - tail);
            if (start > 0) {
                out.add(new Dto.LogLine("[… " + start + " earlier log lines omitted …]", "omitted"));
            }
            for (String s : cleaned.subList(start, cleaned.size())) {
                String red = redactor.redact(s);
                String kind = !red.equals(s) ? "redacted"
                        : s.matches("(?i).*(error|fail|exception|✕|✗|not ok|assert).*") ? "fail" : null;
                out.add(new Dto.LogLine(red, kind));
            }
        } catch (Exception e) {
            out.add(new Dto.LogLine("(could not fetch job log: " + e.getMessage() + ")", "omitted"));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> pickFailedJob(List<?> jobs) {
        for (Object o : jobs) {
            Map<String, Object> j = (Map<String, Object>) o;
            if ("failure".equals(j.get("conclusion"))) return j;
        }
        return jobs.isEmpty() ? Map.of() : (Map<String, Object>) jobs.getFirst();
    }

    private String firstFailedStep(Map<String, Object> job) {
        for (Object o : asList(job.get("steps"))) {
            Map<String, Object> s = asMap(o);
            if ("failure".equals(s.get("conclusion"))) return str(s, "name");
        }
        return "(unknown step)";
    }

    private String firstPrNumber(Map<String, Object> run) {
        List<?> prs = asList(run.get("pull_requests"));
        return prs.isEmpty() ? "" : str(asMap(prs.getFirst()), "number");
    }

    private static String relative(String iso) {
        try {
            long mins = Duration.between(Instant.parse(iso), Instant.now()).toMinutes();
            if (mins < 60) return mins + "m ago";
            if (mins < 1440) return (mins / 60) + "h ago";
            return (mins / 1440) + "d ago";
        } catch (Exception e) {
            return "recently";
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private static List<?> asList(Object o) {
        return o instanceof List ? (List<?>) o : List.of();
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : String.valueOf(v);
    }
}
