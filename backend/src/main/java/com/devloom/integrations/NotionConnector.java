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
 * Notion connector (SPEC.md §12 — Next scope, pulled forward). Lists pages/databases the
 * user has shared with the DevLoom integration (via /v1/search) and surfaces them as
 * document work items. An integration only sees content explicitly connected to it.
 * Responses parsed as plain {@code Map} (Jackson-version independent).
 */
@Component
public class NotionConnector implements SourceConnector {

    private static final Logger log = LoggerFactory.getLogger(NotionConnector.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    private final String token;
    private final int maxItems;
    private final RestClient http;

    public NotionConnector(
            @Value("${devloom.notion.base-url:https://api.notion.com}") String baseUrl,
            @Value("${devloom.notion.token:}") String token,
            @Value("${devloom.notion.version:2022-06-28}") String version,
            @Value("${devloom.notion.max-items:15}") int maxItems) {
        this.token = token;
        this.maxItems = maxItems;
        this.http = (token == null || token.isBlank())
                ? null
                : RestClient.builder()
                        .baseUrl(baseUrl)
                        .defaultHeader("Authorization", "Bearer " + token)
                        .defaultHeader("Notion-Version", version)
                        .defaultHeader("Content-Type", "application/json")
                        .build();
    }

    @Override
    public String source() {
        return "Notion";
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
            Map<String, Object> resp = http.post()
                    .uri("/v1/search")
                    .body(Map.of("page_size", maxItems))
                    .retrieve()
                    .body(MAP);

            List<WorkItemEntity> out = new ArrayList<>();
            List<?> results = resp == null ? List.of() : asList(resp.get("results"));
            int order = 200;
            for (Object o : results) {
                Map<String, Object> obj = asMap(o);
                String kind = str(obj, "object"); // page | database
                String title = extractTitle(obj);
                String id = str(obj, "id").replace("-", "");
                String extId = id.length() > 60 ? id.substring(0, 60) : id;
                // A shared Notion page/database is a "note" in DevLoom's model (category Notes).
                String status = "database".equals(kind) ? "note · database" : "note";
                out.add(WorkItemEntity.create(extId, "doc",
                        title.isBlank() ? "(untitled Notion " + kind + ")" : title,
                        status, "info", "Notion", source(), order++)
                        .withDetail("Notion " + (kind.isBlank() ? "page" : kind)
                                + " shared with DevLoom.", null));
            }
            log.info("Notion sync: {} shared objects", out.size());
            return out;
        } catch (Exception e) {
            log.warn("Notion sync failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** Title lives either in a title-typed property (pages) or a top-level `title` array (databases). */
    @SuppressWarnings("unchecked")
    private String extractTitle(Map<String, Object> obj) {
        Object props = obj.get("properties");
        if (props instanceof Map<?, ?> pm) {
            for (Object v : pm.values()) {
                Map<String, Object> prop = asMap(v);
                if ("title".equals(str(prop, "type"))) {
                    String t = joinPlain(prop.get("title"));
                    if (!t.isBlank()) return t;
                }
            }
        }
        String t = joinPlain(obj.get("title"));
        return t;
    }

    private static String joinPlain(Object richTextArray) {
        StringBuilder sb = new StringBuilder();
        for (Object o : asList(richTextArray)) {
            sb.append(str(asMap(o), "plain_text"));
        }
        return sb.toString().trim();
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
