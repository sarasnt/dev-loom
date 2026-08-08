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
 * GitHub connector (SPEC.md §12/§13). Pulls the open PRs/issues that involve the
 * authenticated user (author, assignee, reviewer, mentions) via the Search API and
 * normalizes them to {@link WorkItemEntity}. Read-only. Disabled (no-op) without a token,
 * so the fixture GitHub rows remain. Responses parsed as plain {@code Map} (Jackson-version
 * independent — Boot 4 ships Jackson 3).
 */
@Component
public class GitHubConnector implements SourceConnector {

    private static final Logger log = LoggerFactory.getLogger(GitHubConnector.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    private final String token;
    private final int maxIssues;
    private final RestClient http;

    public GitHubConnector(
            @Value("${devloom.github.base-url:https://api.github.com}") String baseUrl,
            @Value("${devloom.github.token:}") String token,
            @Value("${devloom.github.max-issues:25}") int maxIssues) {
        this.token = token;
        this.maxIssues = maxIssues;
        this.http = (token == null || token.isBlank())
                ? null
                : RestClient.builder()
                        .baseUrl(baseUrl)
                        .defaultHeader("Authorization", "Bearer " + token)
                        .defaultHeader("Accept", "application/vnd.github+json")
                        .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                        .build();
    }

    @Override
    public String source() {
        return "GitHub";
    }

    @Override
    public boolean enabled() {
        return http != null;
    }

    @Override
    public List<WorkItemEntity> fetch() {
        if (!enabled()) {
            return List.of();
        }
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

            List<WorkItemEntity> out = new ArrayList<>();
            List<?> items = search == null ? List.of() : asList(search.get("items"));
            int order = 10;
            for (Object o : items) {
                out.add(map(asMap(o), login, order++));
            }
            log.info("GitHub sync: fetched {} items for {}", out.size(), login);
            return out;
        } catch (Exception e) {
            log.warn("GitHub sync failed: {}", e.getMessage());
            return List.of();
        }
    }

    private WorkItemEntity map(Map<String, Object> item, String login, int order) {
        int number = ((Number) item.getOrDefault("number", 0)).intValue();
        String title = str(item, "title");
        String state = str(item, "state"); // open | closed
        boolean isPr = item.containsKey("pull_request");
        String repo = repoShortName(str(item, "repository_url"));
        boolean mine = str(asMap(item.get("user")), "login").equalsIgnoreCase(login);

        String type = isPr ? "pr" : "task";
        String tone = "closed".equals(state) ? "healthy" : "info";
        String status = isPr ? (mine ? "PR · mine" : "PR") : "issue";
        String extId = (repo + "#" + number);
        if (extId.length() > 60) {
            extId = extId.substring(extId.length() - 60);
        }
        String displayTitle = "#" + number + " · " + title;
        String meta = String.join(",", state, repo);
        return WorkItemEntity.create(extId, type, displayTitle, status, tone, meta, source(), order);
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
