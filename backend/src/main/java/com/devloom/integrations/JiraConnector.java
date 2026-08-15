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

    /** Same question on both deployments: what is assigned to me, most recently touched first. */
    private static final String JQL = "assignee = currentUser() ORDER BY updated DESC";

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
        String authHeader = authHeader(inst, secrets, cloud);
        try {
            // The two deployments diverged in 2025: Atlassian removed GET /rest/api/3/search on
            // Cloud — it answers 410 telling you to migrate — in favour of POST search/jql, which
            // takes the same query as a body and pages by cursor instead of offset. Data Center
            // still serves REST v2 the old way, so this is a genuine fork rather than a version
            // bump. Both still accept "*navigable" and expand=names, so the custom-field metadata
            // below survives the move.
            Map<String, Object> root = cloud
                    ? http.post()
                            .uri("/rest/api/3/search/jql")
                            .header("Authorization", authHeader)
                            .header("Accept", "application/json")
                            .header("Content-Type", "application/json")
                            .body(Map.of(
                                    "jql", JQL,
                                    "maxResults", maxIssues,
                                    "fields", List.of("*navigable"),
                                    "expand", "names"))
                            .retrieve()
                            .body(MAP)
                    : http.get()
                            .uri(uri -> uri.path("/rest/api/2/search")
                                    .queryParam("jql", JQL)
                                    .queryParam("maxResults", maxIssues)
                                    // *navigable = standard + custom fields; expand=names → labels.
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

    /**
     * Cloud is Basic {@code email:apiToken}; the email is a setup field, not a secret, so it comes
     * from the instance config. It used to be read from the secret map, where non-secret fields
     * never arrive — which produced Basic ":token", an unauthenticated request, and a cheerful
     * 200 with zero issues. Falls back to the secret map so an instance saved the old way still
     * works.
     */
    private static String authHeader(SourceInstanceEntity inst, Map<String, String> secrets, boolean cloud) {
        if (!cloud) return "Bearer " + secrets.getOrDefault("pat", "");
        String email = inst.config().getOrDefault("email", secrets.getOrDefault("email", ""));
        return "Basic " + Base64.getEncoder().encodeToString(
                (email + ":" + secrets.getOrDefault("apiToken", "")).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * "The request worked" is not evidence of anything here: Jira Cloud answers an unauthenticated
     * search with 200 and an empty list, so the default test (fetch without throwing) passed while
     * the credentials were being ignored. Ask who we are instead — anonymous has no accountId.
     */
    @Override
    public boolean test(SourceInstanceEntity inst, Map<String, String> secrets) {
        if ("cloud".equalsIgnoreCase(inst.getDeployment())) {
            Map<String, Object> me = RestClient.builder().baseUrl(inst.getBaseUrl()).build()
                    .get().uri("/rest/api/3/myself")
                    .header("Authorization", authHeader(inst, secrets, true))
                    .header("Accept", "application/json")
                    .retrieve().body(MAP);
            String accountId = me == null ? "" : str(me, "accountId");
            if (accountId.isBlank()) {
                throw new IllegalStateException("Jira accepted the request but did not recognise the "
                        + "credentials — check the account email and API token.");
            }
        }
        fetch(inst, secrets);
        return true;
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
