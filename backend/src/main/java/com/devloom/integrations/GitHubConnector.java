package com.devloom.integrations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.devloom.workmodel.WorkItemEntity;

/**
 * GitHub connector (docs/SPEC-sources.md). Pulls the open PRs/issues that involve the
 * authenticated user and auto-discovers recent FAILED CI runs as build items. Cloud uses
 * api.github.com; Enterprise uses the instance base URL. Read-only.
 */
@Component
public class GitHubConnector implements SourceConnector {

    private static final Logger log = LoggerFactory.getLogger(GitHubConnector.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    private final int maxIssues;
    private final int maxBuilds;

    public GitHubConnector(
            @Value("${devloom.github.max-issues:25}") int maxIssues,
            @Value("${devloom.github.max-builds:3}") int maxBuilds) {
        this.maxIssues = maxIssues;
        this.maxBuilds = maxBuilds;
    }

    @Override
    public String type() {
        return "github";
    }

    @Override
    public SetupDescriptor.Type describe() {
        return new SetupDescriptor.Type("github", "GitHub", List.of(
                new SetupDescriptor.Deployment("cloud", "Cloud", List.of(
                        SetupDescriptor.Field.secret("token", "Access token", "fine-grained PAT (read)"))),
                new SetupDescriptor.Deployment("onprem", "Enterprise", List.of(
                        SetupDescriptor.Field.url("baseUrl", "API base URL", true, "https://ghe.acme.com/api/v3"),
                        SetupDescriptor.Field.secret("token", "Access token", "PAT")))));
    }

    @Override
    public List<WorkItemEntity> fetch(SourceInstanceEntity inst, Map<String, String> secrets) {
        String baseUrl = inst.getBaseUrl() == null || inst.getBaseUrl().isBlank()
                ? "https://api.github.com" : inst.getBaseUrl();
        String token = secrets.getOrDefault("token", "");
        RestClient http = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
        String source = inst.getName();
        try {
            Map<String, Object> me = http.get().uri("/user").retrieve().body(MAP);
            String login = me == null ? "" : String.valueOf(me.getOrDefault("login", ""));
            if (login.isBlank()) {
                return List.of();
            }

            Map<String, Object> search = http.get()
                    .uri(uri -> uri.path("/search/issues")
                            .queryParam("q", "involves:" + login + " is:open")
                            .queryParam("sort", "updated")
                            .queryParam("order", "desc")
                            .queryParam("per_page", maxIssues)
                            .build())
                    .retrieve()
                    .body(MAP);

            // Reviews waiting on you are a separate GitHub query — `involves:` finds PRs you are
            // merely part of, and says nothing about who is blocking whom. Without this the review
            // features had no data path at all: nothing the connector wrote was ever a review, so
            // the urgency rule that looks for one could never fire.
            Map<String, Object> reviews = http.get()
                    .uri(uri -> uri.path("/search/issues")
                            .queryParam("q", "review-requested:" + login + " is:open is:pr")
                            .queryParam("sort", "updated")
                            .queryParam("order", "desc")
                            .queryParam("per_page", maxIssues)
                            .build())
                    .retrieve()
                    .body(MAP);
            java.util.Set<Integer> awaitingMe = new java.util.HashSet<>();
            for (Object o : (reviews == null ? List.of() : asList(reviews.get("items")))) {
                Object id = asMap(o).get("id");
                if (id instanceof Number n) awaitingMe.add(n.intValue());
            }

            List<WorkItemEntity> out = new ArrayList<>();
            List<?> items = search == null ? List.of() : asList(search.get("items"));
            int order = 10;
            java.util.LinkedHashSet<String> repos = new java.util.LinkedHashSet<>();
            for (Object o : items) {
                Map<String, Object> item = asMap(o);
                out.add(map(item, login, source, order++, awaitingMe));
                String repo = repoShortName(str(item, "repository_url"));
                if (!repo.isBlank()) repos.add(repo);
            }

            int fetchedBuilds = 0;
            for (String repo : repos.stream().limit(3).toList()) {
                fetchedBuilds += addFailedRuns(http, repo, source, out, order);
                order += 10;
            }
            log.info("GitHub sync [{}]: {} items + {} failed CI builds for {}",
                    source, out.size() - fetchedBuilds, fetchedBuilds, login);
            return out;
        } catch (Exception e) {
            log.warn("GitHub sync failed [{}]: {}", source, e.getMessage());
            throw new IllegalStateException("GitHub fetch failed", e);
        }
    }

    /** Recent failed workflow runs for a repo → build WorkItems (repo kept in meta). */
    private int addFailedRuns(RestClient http, String repo, String source, List<WorkItemEntity> out, int baseOrder) {
        try {
            Map<String, Object> runs = http.get()
                    .uri(uri -> uri.path("/repos/" + repo + "/actions/runs")
                            .queryParam("status", "failure")
                            .queryParam("per_page", maxBuilds)
                            .build())
                    .retrieve().body(MAP);
            List<?> list = runs == null ? List.of() : asList(runs.get("workflow_runs"));
            int i = 0;
            for (Object o : list) {
                Map<String, Object> r = asMap(o);
                String runId = str(r, "id");
                String name = str(r, "name");
                String branch = str(r, "head_branch");
                String runNo = str(r, "run_number");
                String title = "CI " + (runNo.isBlank() ? "" : "#" + runNo + " ") + "· " + name + " failed";
                // meta = [branch, repo] — BuildFailureService recovers repo from meta[1].
                out.add(WorkItemEntity.create(runId, "build", title, "failed", "fail",
                        branch + "," + repo, source, baseOrder + i++)
                        .withUrl(str(r, "html_url")));
            }
            return i;
        } catch (Exception e) {
            log.warn("GitHub CI fetch failed for {}: {}", repo, e.getMessage());
            return 0;
        }
    }

    private WorkItemEntity map(Map<String, Object> item, String login, String source, int order,
                               java.util.Set<Integer> awaitingMe) {
        int number = ((Number) item.getOrDefault("number", 0)).intValue();
        String title = str(item, "title");
        String state = str(item, "state"); // open | closed
        boolean isPr = item.containsKey("pull_request");
        String repo = repoShortName(str(item, "repository_url"));
        boolean mine = str(asMap(item.get("user")), "login").equalsIgnoreCase(login);

        Object rawId = item.get("id");
        boolean myReview = rawId instanceof Number n && awaitingMe.contains(n.intValue());

        // A PR waiting on your review is a different job from a PR you happen to be on: it is
        // blocked until you act, so it gets its own type and the urgency rules can find it.
        String type = myReview ? "review" : (isPr ? "pr" : "task");
        String tone = "closed".equals(state) ? "healthy" : (myReview ? "warn" : "info");
        String status = myReview ? "review requested of you" : (isPr ? (mine ? "PR · mine" : "PR") : "issue");
        String extId = (repo + "#" + number);
        if (extId.length() > 60) {
            extId = extId.substring(extId.length() - 60);
        }
        String displayTitle = "#" + number + " · " + title;
        String meta = String.join(",", state, repo);
        return WorkItemEntity.create(extId, type, displayTitle, status, tone, meta, source, order)
                .withUrl(str(item, "html_url"));
    }

    private static String repoShortName(String repositoryUrl) {
        if (repositoryUrl == null) return "";
        int i = repositoryUrl.indexOf("/repos/");
        return i >= 0 ? repositoryUrl.substring(i + "/repos/".length()) : repositoryUrl;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private static List<?> asList(Object o) {
        return o instanceof List ? (List<?>) o : List.of();
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : v.toString();
    }
}
