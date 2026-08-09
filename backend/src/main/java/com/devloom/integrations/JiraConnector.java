package com.devloom.integrations;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
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
 * Jira connector (docs/SPEC-sources.md). One connector for the type; each configured instance
 * supplies its base URL + credential. On-prem (Data Center/Server) uses a PAT via
 * {@code Authorization: Bearer} + REST v2; Cloud uses Basic {@code email:apiToken} + REST v3.
 * Responses parsed as plain {@code Map} (Jackson-version independent).
 */
@Component
public class JiraConnector implements SourceConnector {

    private static final Logger log = LoggerFactory.getLogger(JiraConnector.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    private final int maxIssues;

    public JiraConnector(@Value("${devloom.jira.max-issues:25}") int maxIssues) {
        this.maxIssues = maxIssues;
    }

    @Override
    public String type() {
        return "jira";
    }

    @Override
    public SetupDescriptor.Type describe() {
        return new SetupDescriptor.Type("jira", "Jira", List.of(
                new SetupDescriptor.Deployment("cloud", "Cloud", List.of(
                        SetupDescriptor.Field.url("baseUrl", "Site URL", true, "https://acme.atlassian.net"),
                        SetupDescriptor.Field.text("email", "Account email", true, "you@acme.com"),
                        SetupDescriptor.Field.secret("apiToken", "API token", "Atlassian API token"))),
                new SetupDescriptor.Deployment("onprem", "Server / Data Center", List.of(
                        SetupDescriptor.Field.url("baseUrl", "Base URL", true, "https://jira.acme.com"),
                        SetupDescriptor.Field.secret("pat", "Personal Access Token", "Bearer PAT")))));
    }

    @Override
    public List<WorkItemEntity> fetch(SourceInstanceEntity inst, Map<String, String> secrets) {
        String baseUrl = inst.getBaseUrl();
        boolean cloud = "cloud".equalsIgnoreCase(inst.getDeployment());
        RestClient http = RestClient.builder().baseUrl(baseUrl).build();
        String apiPath = cloud ? "/rest/api/3/search" : "/rest/api/2/search";
        String authHeader = cloud
                ? "Basic " + Base64.getEncoder().encodeToString(
                        (secrets.getOrDefault("email", "") + ":" + secrets.getOrDefault("apiToken", ""))
                                .getBytes(StandardCharsets.UTF_8))
                : "Bearer " + secrets.getOrDefault("pat", "");
        try {
            Map<String, Object> root = http.get()
                    .uri(uri -> uri.path(apiPath)
                            .queryParam("jql", "assignee = currentUser() ORDER BY updated DESC")
                            .queryParam("maxResults", maxIssues)
                            .queryParam("fields", "summary,status,priority,project,parent,description")
                            .build())
                    .header("Authorization", authHeader)
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(MAP);

            List<WorkItemEntity> out = new ArrayList<>();
            List<?> issues = root == null ? List.of() : asList(root.get("issues"));
            int order = 100;
            for (Object issueObj : issues) {
                out.add(map(asMap(issueObj), inst.getName(), order++));
            }
            log.info("Jira sync [{}]: fetched {} issues from {}", inst.getName(), out.size(), baseUrl);
            return out;
        } catch (Exception e) {
            log.warn("Jira sync failed [{}] ({}): {}", inst.getName(), baseUrl, e.getMessage());
            throw new IllegalStateException("Jira fetch failed", e);
        }
    }

    private WorkItemEntity map(Map<String, Object> issue, String source, int order) {
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
        String parentKey = str(asMap(f.get("parent")), "key");
        Object descObj = f.get("description");
        String description = descObj instanceof String s ? s : ""; // v3 ADF is an object → skip

        List<String> metaParts = new ArrayList<>();
        if (!priority.isBlank()) metaParts.add(priority);
        if (!projectKey.isBlank()) metaParts.add(projectKey);
        String title = key + " · " + summary;
        return WorkItemEntity.create(key, "task", title,
                statusName.isBlank() ? "open" : statusName, tone,
                String.join(",", metaParts), source, order)
                .withDetail(description, parentKey);
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
