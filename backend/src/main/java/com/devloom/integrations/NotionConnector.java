package com.devloom.integrations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final int maxPerDb;
    private final RestClient http;

    public NotionConnector(
            @Value("${devloom.notion.base-url:https://api.notion.com}") String baseUrl,
            @Value("${devloom.notion.token:}") String token,
            @Value("${devloom.notion.version:2022-06-28}") String version,
            @Value("${devloom.notion.max-items:300}") int maxItems,
            @Value("${devloom.notion.max-per-db:100}") int maxPerDb) {
        this.token = token;
        this.maxItems = maxItems;
        this.maxPerDb = maxPerDb;
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
            List<WorkItemEntity> out = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>(); // dedup by extId (uq: source, ext_id)
            int[] order = {200};
            String cursor = null;
            int pages = 0;

            // 1) Every page/database shared with the integration (paginated).
            do {
                Map<String, Object> body = new HashMap<>();
                body.put("page_size", 100);
                if (cursor != null) body.put("start_cursor", cursor);
                Map<String, Object> resp = http.post().uri("/v1/search").body(body).retrieve().body(MAP);
                if (resp == null) break;

                for (Object o : asList(resp.get("results"))) {
                    Map<String, Object> obj = asMap(o);
                    String kind = str(obj, "object"); // page | database
                    String rawId = str(obj, "id");
                    String extId = clip(rawId.replace("-", ""));
                    if (!seen.add(extId) || out.size() >= maxItems) continue;
                    String title = extractTitle(obj);

                    if ("database".equals(kind)) {
                        out.add(WorkItemEntity.create(extId, "doc",
                                title.isBlank() ? "(untitled database)" : title,
                                "note · database", "info", "Notion", source(), order[0]++)
                                .withDetail("Notion database shared with DevLoom.", null));
                        // 2) Its rows are tasks — nested under the database in the Work tree.
                        addDatabaseRows(rawId, extId, out, seen, order);
                    } else {
                        out.add(WorkItemEntity.create(extId, "doc",
                                title.isBlank() ? "(untitled page)" : title,
                                "note", "info", "Notion", source(), order[0]++)
                                .withDetail("Notion page shared with DevLoom.", null));
                    }
                }
                cursor = Boolean.TRUE.equals(resp.get("has_more")) ? str(resp, "next_cursor") : null;
            } while (cursor != null && !cursor.isBlank() && ++pages < 10 && out.size() < maxItems);

            log.info("Notion sync: {} objects (pages, databases, tasks)", out.size());
            return out;
        } catch (Exception e) {
            log.warn("Notion sync failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** Query a database's rows and surface them as task work items under the database. */
    private void addDatabaseRows(String rawDbId, String dbExtId, List<WorkItemEntity> out,
                                 Set<String> seen, int[] order) {
        try {
            Map<String, Object> resp = http.post()
                    .uri("/v1/databases/" + rawDbId + "/query")
                    .body(Map.of("page_size", Math.min(maxPerDb, 100)))
                    .retrieve().body(MAP);
            int added = 0;
            for (Object o : asList(resp == null ? null : resp.get("results"))) {
                if (added >= maxPerDb || out.size() >= maxItems) break;
                Map<String, Object> row = asMap(o);
                String extId = clip(str(row, "id").replace("-", ""));
                if (!seen.add(extId)) continue;
                String title = extractTitle(row);
                String status = extractStatus(row);
                String tone = switch (status.toLowerCase()) {
                    case "done", "complete", "completed", "closed" -> "healthy";
                    case "in progress", "doing", "in review" -> "warn";
                    default -> "info";
                };
                out.add(WorkItemEntity.create(extId, "task",
                        title.isBlank() ? "(untitled task)" : title,
                        status.isBlank() ? "task" : status, tone,
                        "Notion", source(), order[0]++)
                        .withDetail(null, dbExtId));
                added++;
            }
        } catch (Exception e) {
            log.warn("Notion database query failed for {}: {}", dbExtId, e.getMessage());
        }
    }

    private static String clip(String id) {
        return id.length() > 60 ? id.substring(0, 60) : id;
    }

    /** First status/select property value on a row, if any. */
    @SuppressWarnings("unchecked")
    private String extractStatus(Map<String, Object> row) {
        Object props = row.get("properties");
        if (props instanceof Map<?, ?> pm) {
            for (Object v : pm.values()) {
                Map<String, Object> prop = asMap(v);
                String type = str(prop, "type");
                if ("status".equals(type) || "select".equals(type)) {
                    Map<String, Object> val = asMap(prop.get(type));
                    String name = str(val, "name");
                    if (!name.isBlank()) return name;
                }
            }
        }
        return "";
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
