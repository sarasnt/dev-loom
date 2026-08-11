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
                            // *navigable = standard + custom fields; expand=names → field display names.
                            .queryParam("fields", "*navigable")
                            .queryParam("expand", "names")
                            .build())
                    .header("Authorization", authHeader)
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(MAP);

            List<WorkItemEntity> out = new ArrayList<>();
            List<?> issues = root == null ? List.of() : asList(root.get("issues"));
            Map<String, Object> names = asMap(root == null ? null : root.get("names")); // fieldId → label
            int order = 100;
            for (Object issueObj : issues) {
                out.add(map(asMap(issueObj), names, inst.getName(), order++, baseUrl));
            }
            log.info("Jira sync [{}]: fetched {} issues from {}", inst.getName(), out.size(), baseUrl);
            return out;
        } catch (Exception e) {
            log.warn("Jira sync failed [{}] ({}): {}", inst.getName(), baseUrl, e.getMessage());
            throw new IllegalStateException("Jira fetch failed", e);
        }
    }

    private WorkItemEntity map(Map<String, Object> issue, Map<String, Object> names, String source, int order, String baseUrl) {
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
        // Description: v2 (Server/DC) is plain text; v3 (Cloud) is an ADF document → flatten it.
        String description = jiraValueToString(f.get("description"));

        List<String> metaParts = new ArrayList<>();
        if (!priority.isBlank()) metaParts.add(priority);
        if (!projectKey.isBlank()) metaParts.add(projectKey);
        String title = key + " · " + summary;
        String base = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        return WorkItemEntity.create(key, "task", title,
                statusName.isBlank() ? "open" : statusName, tone,
                String.join(",", metaParts), source, order)
                .withDetail(description, parentKey)
                .withMetadata(buildMetadata(f, names))
                .withUrl(base.isBlank() || key.isBlank() ? null : base + "/browse/" + key);
    }

    /**
     * Rich, model-facing metadata: a few useful standard fields plus every non-empty custom
     * field, each labelled with its Jira display name ("Sprint: 24.3", "Story Points: 5", …).
     * Summary/description/status/priority are handled elsewhere, so they're skipped here.
     */
    private static String buildMetadata(Map<String, Object> f, Map<String, Object> names) {
        List<String> lines = new ArrayList<>();
        for (String std : List.of("assignee", "reporter", "labels", "components", "fixVersions", "issuetype")) {
            addLine(lines, names, std, f.get(std));
        }
        for (Map.Entry<String, Object> e : f.entrySet()) {
            if (e.getKey().startsWith("customfield_")) addLine(lines, names, e.getKey(), e.getValue());
        }
        return String.join("\n", lines);
    }

    private static void addLine(List<String> lines, Map<String, Object> names, String fieldId, Object value) {
        String v = jiraValueToString(value);
        if (v.isBlank()) return;
        String label = str(names, fieldId);
        lines.add((label.isBlank() ? fieldId : label) + ": " + (v.length() > 300 ? v.substring(0, 300) + "…" : v));
    }

    /** Best-effort stringify of a Jira field value (string, number, object, list, or ADF). */
    @SuppressWarnings("unchecked")
    private static String jiraValueToString(Object v) {
        if (v == null) return "";
        if (v instanceof String s) return s.strip();
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        if (v instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object o : list) { String p = jiraValueToString(o); if (!p.isBlank()) parts.add(p); }
            return String.join(", ", parts);
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> mm = (Map<String, Object>) m;
            if (mm.containsKey("type") && mm.containsKey("content")) return adfText(mm).strip(); // ADF doc
            for (String k : List.of("displayName", "name", "value", "key")) {
                Object hit = mm.get(k);
                if (hit != null) return String.valueOf(hit);
            }
            return "";
        }
        return v.toString();
    }

    /** Flatten an Atlassian Document Format node tree to plain text. */
    @SuppressWarnings("unchecked")
    private static String adfText(Object node) {
        if (node instanceof Map<?, ?> m) {
            Map<String, Object> mm = (Map<String, Object>) m;
            Object text = mm.get("text");
            StringBuilder sb = new StringBuilder();
            if (text instanceof String s) sb.append(s);
            Object content = mm.get("content");
            if (content instanceof List<?> list) {
                for (Object child : list) sb.append(adfText(child));
                String type = String.valueOf(mm.get("type"));
                if ("paragraph".equals(type) || "heading".equals(type)) sb.append("\n");
            }
            return sb.toString();
        }
        return "";
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
