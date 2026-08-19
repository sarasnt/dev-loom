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
 * Bitbucket connector (docs/SPEC-sources.md). Surfaces the open pull requests that involve the
 * authenticated user.
 *
 * <ul>
 *   <li><b>Cloud</b> (bitbucket.org): REST 2.0, Basic {@code email:apiToken} (Atlassian API
 *       token or app password). Uses {@code GET /2.0/pullrequests/{uuid}} for the user's PRs.</li>
 *   <li><b>Server / Data Center</b>: REST 1.0, Bearer PAT. Uses
 *       {@code GET /rest/api/1.0/dashboard/pull-requests} (PRs where you're author/reviewer).</li>
 * </ul>
 *
 * <p>NOTE: implemented to the documented APIs but not yet exercised against a live instance —
 * verify once a Bitbucket account is reachable. Issues/pipelines are out of this first cut.
 */
@Component
public class BitbucketConnector implements SourceConnector {

    private static final Logger log = LoggerFactory.getLogger(BitbucketConnector.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    private final int maxIssues;

    public BitbucketConnector(@Value("${devloom.bitbucket.max-issues:25}") int maxIssues) {
        this.maxIssues = maxIssues;
    }

    @Override
    public String type() {
        return "bitbucket";
    }

    @Override
    public SetupDescriptor.Type describe() {
        return new SetupDescriptor.Type("bitbucket", "Bitbucket", List.of(
                new SetupDescriptor.Deployment("cloud", "Cloud", List.of(
                        SetupDescriptor.Field.text("email", "Account email", true, "you@acme.com"),
                        SetupDescriptor.Field.secret("apiToken", "API token / app password", "Atlassian API token"))),
                new SetupDescriptor.Deployment("onprem", "Server / Data Center", List.of(
                        SetupDescriptor.Field.url("baseUrl", "Base URL", true, "https://bitbucket.acme.com"),
                        SetupDescriptor.Field.secret("pat", "HTTP access token", "Bearer PAT")))));
    }

    @Override
    public List<WorkItemEntity> fetch(SourceInstanceEntity inst, Map<String, String> secrets) {
        return "cloud".equalsIgnoreCase(inst.getDeployment())
                ? fetchCloud(inst, secrets)
                : fetchServer(inst, secrets);
    }

    // ---- Bitbucket Cloud (REST 2.0) ----
    private List<WorkItemEntity> fetchCloud(SourceInstanceEntity inst, Map<String, String> secrets) {
        String base = inst.getBaseUrl() == null || inst.getBaseUrl().isBlank()
                ? "https://api.bitbucket.org" : inst.getBaseUrl();
        String auth = "Basic " + Base64.getEncoder().encodeToString(
                (secrets.getOrDefault("email", "") + ":" + secrets.getOrDefault("apiToken", ""))
                        .getBytes(StandardCharsets.UTF_8));
        RestClient http = RestClient.builder().baseUrl(base)
                .defaultHeader("Authorization", auth)
                .defaultHeader("Accept", "application/json").build();
        String source = inst.getName();
        try {
            Map<String, Object> me = http.get().uri("/2.0/user").retrieve().body(MAP);
            String uuid = me == null ? "" : str(me, "uuid"); // e.g. "{...}"
            if (uuid.isBlank()) {
                return List.of();
            }
            Map<String, Object> prs = http.get()
                    .uri(uri -> uri.path("/2.0/pullrequests/" + uuid)
                            .queryParam("state", "OPEN")
                            .queryParam("pagelen", maxIssues)
                            .build())
                    .retrieve().body(MAP);
            List<WorkItemEntity> out = new ArrayList<>();
            int order = 10;
            for (Object o : asList(prs == null ? null : prs.get("values"))) {
                Map<String, Object> pr = asMap(o);
                String id = str(pr, "id");
                String title = str(pr, "title");
                String state = str(pr, "state");
                String repo = str(asMap(asMap(asMap(pr.get("destination")).get("repository"))), "full_name");
                if (repo.isBlank()) {
                    repo = str(asMap(asMap(asMap(pr.get("source")).get("repository"))), "full_name");
                }
                // Cloud puts the browser link at links.html.href. Without it "Open" on Today has
                // nothing to open, which reads as a broken button rather than missing data.
                String url = str(asMap(asMap(pr.get("links")).get("html")), "href");
                out.add(prItem(id, title, state, repo, source, order++, url));
            }
            log.info("Bitbucket Cloud sync [{}]: {} PRs", source, out.size());
            return out;
        } catch (Exception e) {
            log.warn("Bitbucket Cloud sync failed [{}]: {}", source, e.getMessage());
            throw new IllegalStateException("Bitbucket fetch failed", e);
        }
    }

    // ---- Bitbucket Server / Data Center (REST 1.0) ----
    private List<WorkItemEntity> fetchServer(SourceInstanceEntity inst, Map<String, String> secrets) {
        RestClient http = RestClient.builder().baseUrl(inst.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + secrets.getOrDefault("pat", ""))
                .defaultHeader("Accept", "application/json").build();
        String source = inst.getName();
        try {
            Map<String, Object> resp = http.get()
                    .uri(uri -> uri.path("/rest/api/1.0/dashboard/pull-requests")
                            .queryParam("state", "OPEN")
                            .queryParam("limit", maxIssues)
                            .build())
                    .retrieve().body(MAP);
            List<WorkItemEntity> out = new ArrayList<>();
            int order = 10;
            for (Object o : asList(resp == null ? null : resp.get("values"))) {
                Map<String, Object> pr = asMap(o);
                String id = str(pr, "id");
                String title = str(pr, "title");
                String state = str(pr, "state");
                Map<String, Object> repoObj = asMap(asMap(pr.get("toRef")).get("repository"));
                String repo = str(asMap(repoObj.get("project")), "key") + "/" + str(repoObj, "slug");
                // Server/DC exposes the browser link as the first links.self entry.
                String url = null;
                for (Object l : asList(asMap(pr.get("links")).get("self"))) {
                    url = str(asMap(l), "href");
                    if (!url.isBlank()) break;
                }
                out.add(prItem(id, title, state, repo, source, order++, url));
            }
            log.info("Bitbucket Server sync [{}]: {} PRs", source, out.size());
            return out;
        } catch (Exception e) {
            log.warn("Bitbucket Server sync failed [{}]: {}", source, e.getMessage());
            throw new IllegalStateException("Bitbucket fetch failed", e);
        }
    }

    private WorkItemEntity prItem(String id, String title, String state, String repo, String source,
                                  int order, String url) {
        String tone = switch (state == null ? "" : state.toUpperCase()) {
            case "MERGED" -> "healthy";
            case "DECLINED", "SUPERSEDED" -> "stale";
            default -> "info";
        };
        String extId = repo + "#" + id;
        if (extId.length() > 60) extId = extId.substring(extId.length() - 60);
        String displayTitle = "#" + id + " · " + title;
        return WorkItemEntity.create(extId, "pr", displayTitle, "PR", tone,
                String.join(",", state == null ? "" : state.toLowerCase(), repo), source, order)
                .withUrl(url == null || url.isBlank() ? null : url);
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
