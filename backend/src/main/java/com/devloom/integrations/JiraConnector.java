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
 * On-premises Jira (Data Center/Server) connector — SPEC.md §12/§13, corrected for on-prem:
 * Personal Access Token via {@code Authorization: Bearer}, REST API v2. Fetches the user's
 * assigned issues and normalizes them to {@link WorkItemEntity}. Disabled (no-op) when no PAT
 * is configured, so the app runs on fixtures without it.
 *
 * <p>Responses are parsed as plain {@code Map} (not a bound Jackson type) so the connector is
 * independent of the Jackson major version on the classpath (Boot 4 ships Jackson 3).
 */
@Component
public class JiraConnector implements SourceConnector {

    private static final Logger log = LoggerFactory.getLogger(JiraConnector.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    private final String baseUrl;
    private final String pat;
    private final int maxIssues;
    private final RestClient http;

    public JiraConnector(
            @Value("${devloom.jira.base-url:}") String baseUrl,
            @Value("${devloom.jira.pat:}") String pat,
            @Value("${devloom.jira.max-issues:25}") int maxIssues) {
        this.baseUrl = baseUrl;
        this.pat = pat;
        this.maxIssues = maxIssues;
        this.http = baseUrl.isBlank() ? null : RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public String source() {
        return "Jira";
    }

    @Override
    public boolean enabled() {
        return http != null && pat != null && !pat.isBlank();
    }

    @Override
    public List<WorkItemEntity> fetch() {
        if (!enabled()) {
            return List.of();
        }
        try {
            Map<String, Object> root = http.get()
                    .uri(uri -> uri.path("/rest/api/2/search")
                            .queryParam("jql", "assignee = currentUser() ORDER BY updated DESC")
                            .queryParam("maxResults", maxIssues)
                            .queryParam("fields", "summary,status,priority,project")
                            .build())
                    .header("Authorization", "Bearer " + pat)
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(MAP);

            List<WorkItemEntity> out = new ArrayList<>();
            List<?> issues = root == null ? List.of() : asList(root.get("issues"));
            int order = 100;
            for (Object issueObj : issues) {
                out.add(map(asMap(issueObj), order++));
            }
            log.info("Jira sync: fetched {} issues from {}", out.size(), baseUrl);
            return out;
        } catch (Exception e) {
            // Degrade gracefully — a down/unreachable Jira must not break the app.
            log.warn("Jira sync failed ({}): {}", baseUrl, e.getMessage());
            return List.of();
        }
    }

    private WorkItemEntity map(Map<String, Object> issue, int order) {
        String key = str(issue, "key");
        Map<String, Object> f = asMap(issue.get("fields"));
        String summary = str(f, "summary");
        Map<String, Object> status = asMap(f.get("status"));
        String statusName = str(status, "name");
        String statusCat = str(asMap(status.get("statusCategory")), "key"); // new|indeterminate|done
        String priority = str(asMap(f.get("priority")), "name");
        String projectKey = str(asMap(f.get("project")), "key");

        String tone = switch (statusCat) {
            case "done" -> "healthy";
            case "indeterminate" -> "warn";
            default -> "info";
        };
        List<String> metaParts = new ArrayList<>();
        if (!priority.isBlank()) metaParts.add(priority);
        if (!projectKey.isBlank()) metaParts.add(projectKey);
        String title = key + " · " + summary;
        return WorkItemEntity.create(key, "task", title,
                statusName.isBlank() ? "open" : statusName, tone,
                String.join(",", metaParts), source(), order);
    }

    // ---- tiny, null-safe JSON-map helpers ----
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
