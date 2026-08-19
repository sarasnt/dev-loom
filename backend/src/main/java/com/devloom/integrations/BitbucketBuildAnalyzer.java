package com.devloom.integrations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.devloom.ai.LlmPort;
import com.devloom.ai.LlmRouter;
import com.devloom.ai.PromptLibrary;
import com.devloom.api.Dto;
import com.devloom.workmodel.WorkItemEntity;

/**
 * Build-failure analysis for a Bitbucket DC build — from metadata, and honest about it.
 *
 * <p>Bitbucket Server exposes a build's state, name, url and (via the per-key endpoint) duration,
 * branch and commit — never logs, and on this instance never testResults (0 of 41 builds sampled;
 * see docs/superpowers/specs/2026-08-15-bitbucket-dc-probe.md). So there is no log tail to redact
 * and summarize: the model reasons from metadata, the log panel carries a pointer to Jenkins, and
 * nothing pretends otherwise. Confidence is capped at "med" — without a log, "high" would be a lie.
 */
@Component
public class BitbucketBuildAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(BitbucketBuildAnalyzer.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    /** The GitHub analyzer's contract, minus the log it doesn't have. */
    private static final String SYSTEM = """
            You are a senior engineer triaging a CI failure for someone who will fix it without
            seeing the run. You have build METADATA ONLY — there is no log. Write two short
            paragraphs, no headings, no lists.

            Evidence: what the metadata actually shows — the job, branch, commit message,
            duration and ticket. Nothing here may be inferred.

            Hypothesis: given the branch name, commit message and ticket, the most likely area of
            failure and the first thing to check after opening the Jenkins build. Be explicit
            that the log will settle it.

            Never invent an error message, file, symbol or line number — you have not seen the
            log. If the metadata is too thin to say anything useful, say that plainly.""";

    private static final String PROMPT = "devloom/bitbucket-build";

    private final SourceCredentialStore credentials;
    private final LlmRouter llm;
    private final PromptLibrary prompts;

    public BitbucketBuildAnalyzer(SourceCredentialStore credentials,
                                  LlmRouter llm,
                                  PromptLibrary prompts) {
        this.credentials = credentials;
        this.llm = llm;
        this.prompts = prompts;
        prompts.seed(PROMPT, SYSTEM);
    }

    /** Analyze one failed build item. Null when nothing usable — the caller shows its honest state. */
    public Dto.BuildFailure analyze(SourceInstanceEntity inst, WorkItemEntity item,
                                    String sha,
                                    Consumer<String> progress,
                                    String model) {
        try {
            String token = credentials.secrets(inst).getOrDefault("pat", "");
            RestClient http = RestClient.builder()
                    .baseUrl(inst.getBaseUrl())
                    .defaultHeader("Authorization", "Bearer " + token)
                    .defaultHeader("Accept", "application/json").build();

            progress.accept("Fetching build statuses from Bitbucket…");
            Map<String, Object> list = http.get()
                    .uri("/rest/build-status/1.0/commits/" + sha)
                    .retrieve().body(MAP);
            Map<String, Object> build = null;
            for (Object o : asList(list == null ? null : list.get("values"))) {
                Map<String, Object> b = asMap(o);
                if ("FAILED".equalsIgnoreCase(str(b, "state"))) { build = b; break; }
                if (build == null) build = b;   // fall back to the newest if nothing is red anymore
            }
            if (build == null) return null;
            String key = str(build, "key");
            String jenkinsName = str(build, "name");
            String url = str(build, "url");
            String description = str(build, "description");

            // Enrichment is optional by design: the per-key endpoint adds duration, branch and
            // the commit message (probe §c), but its absence must not sink the analysis.
            progress.accept("Enriching from the per-build endpoint…");
            String repo = metaRepo(item);                      // PROJ/slug, from meta[1]
            String branch = metaBranch(item);
            String buildNumber = "";
            String durationMs = "";
            String commitMessage = "";
            String jiraKey = "";
            try {
                String[] pr = repo.split("/", 2);
                Map<String, Object> rich = http.get()
                        .uri(uri -> uri.path("/rest/api/latest/projects/" + pr[0]
                                        + "/repos/" + pr[1] + "/commits/" + sha + "/builds")
                                .queryParam("key", key).build())
                        .retrieve().body(MAP);
                if (rich != null) {
                    buildNumber = str(rich, "buildNumber");
                    Object d = rich.get("duration");
                    durationMs = d instanceof Number n && n.longValue() > 0 ? String.valueOf(n.longValue()) : "";
                    String ref = str(rich, "ref");
                    if (!ref.isBlank()) branch = ref.replaceFirst("^refs/heads/", "");
                    Map<String, Object> commit = asMap(rich.get("commit"));
                    commitMessage = str(commit, "message");
                    Object jk = asMap(commit.get("properties")).get("jira-key");
                    if (jk instanceof List<?> l && !l.isEmpty()) jiraKey = String.valueOf(l.get(0));
                }
            } catch (Exception e) {
                log.debug("Bitbucket build enrichment skipped for {}: {}", sha, e.getMessage());
            }

            progress.accept("Summarizing from metadata…");
            String user = ("Failed CI build (metadata only — no log available):\n"
                    + "Jenkins job: " + jenkinsName + "\n"
                    + "Repository: " + repo + "\nBranch: " + branch + "\n"
                    + (buildNumber.isBlank() ? "" : "Build number: " + buildNumber + "\n")
                    + (durationMs.isBlank() ? "" : "Duration: " + (Long.parseLong(durationMs) / 1000) + "s\n")
                    + (jiraKey.isBlank() ? "" : "Ticket: " + jiraKey + "\n")
                    + (commitMessage.isBlank() ? "" : "Commit message: " + commitMessage + "\n")
                    + "Status description: " + description);
            String summaryText;
            String summaryModel;
            try {
                LlmPort.LlmResult r = llm.generate(new LlmPort.LlmRequest(
                        "build-failure", prompts.get(PROMPT, SYSTEM), user, model));
                summaryText = r.text() == null || r.text().isBlank()
                        ? "Metadata-only failure — open the Jenkins build for the log." : r.text();
                summaryModel = r.model();
            } catch (Exception e) {
                summaryText = "Metadata-only failure — open the Jenkins build for the log.";
                summaryModel = "deterministic";
            }

            String pr = prNumber(url, item.getTitle());
            String sha8 = sha.length() >= 8 ? sha.substring(0, 8) : sha;
            List<Dto.EvidenceRef> evidence = new ArrayList<>();
            evidence.add(new Dto.EvidenceRef("commit " + sha8, null, "local"));
            if (!jiraKey.isBlank()) evidence.add(new Dto.EvidenceRef(jiraKey, null, "local"));
            List<Dto.Hypothesis> causes = List.of(
                    new Dto.Hypothesis(1,
                            "Failed Jenkins build" + (buildNumber.isBlank() ? "" : " #" + buildNumber)
                                    + " on branch " + branch,
                            "med", evidence, false));
            List<Dto.EvidenceRef> related = new ArrayList<>(evidence);
            if (pr != null) related.add(new Dto.EvidenceRef("PR #" + pr, null, "local"));

            return new Dto.BuildFailure(
                    sha, repo, branch, pr, buildNumber.isBlank() ? "—" : buildNumber,
                    "failed",
                    new Dto.Boundary("local", "On your machine"),
                    summaryText, "med", jenkinsName, "(Bitbucket exposes status only)", "—", false,
                    List.of(
                            new Dto.LogLine(
                                    "Bitbucket carries the build status, not the log — it lives in Jenkins:", "omitted"),
                            new Dto.LogLine(url, "normal")),
                    causes, related,
                    List.of("Open the Jenkins build for the full log: " + url,
                            "The analysis above is from metadata only — the log settles it."),
                    List.of("Address the failure shown in the Jenkins log."),
                    summaryModel + " · metadata only (no log available)");
        } catch (Exception e) {
            log.warn("Bitbucket build analysis failed for {}: {}", sha, e.getMessage());
            return null;
        }
    }

    /** The PR number, from the Jenkins multibranch url segment (…/job/PR-749/…) or the item title. */
    private static String prNumber(String url, String title) {
        Matcher m = Pattern.compile("/PR-(\\d+)/").matcher(url == null ? "" : url);
        if (m.find()) return m.group(1);
        m = Pattern.compile("#(\\d+)").matcher(title == null ? "" : title);
        return m.find() ? m.group(1) : null;
    }

    private static String metaRepo(WorkItemEntity w) {
        String[] parts = w.getMetaCsv().split(",");
        return parts.length >= 2 ? parts[1].trim() : "";
    }

    private static String metaBranch(WorkItemEntity w) {
        String[] parts = w.getMetaCsv().split(",");
        return parts.length >= 1 ? parts[0].trim() : "";
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
