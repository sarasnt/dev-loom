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
            // 1) Collect everything shared with the integration (paginated). Search returns
            //    standalone pages, databases, AND database rows (rows are pages).
            List<Map<String, Object>> all = new ArrayList<>();
            String cursor = null;
            int pages = 0;
            do {
                Map<String, Object> body = new HashMap<>();
                body.put("page_size", 100);
                if (cursor != null) body.put("start_cursor", cursor);
                Map<String, Object> resp = http.post().uri("/v1/search").body(body).retrieve().body(MAP);
                if (resp == null) break;
                for (Object o : asList(resp.get("results"))) all.add(asMap(o));
                cursor = Boolean.TRUE.equals(resp.get("has_more")) ? str(resp, "next_cursor") : null;
            } while (cursor != null && !cursor.isBlank() && ++pages < 15 && all.size() < maxItems * 3);

            // 2) Map database id → title so rows can be tagged with their database name.
            Map<String, String> dbTitle = new HashMap<>();
            for (Map<String, Object> r : all) {
                if ("database".equals(str(r, "object"))) {
                    dbTitle.put(clip(str(r, "id").replace("-", "")), extractTitle(r));
                }
            }

            List<WorkItemEntity> out = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>(); // dedup by extId (uq: source, ext_id)
            int[] order = {200};

            // 3) Classify each page: a row of a database (parent = database_id) is a TASK;
            //    any other page is a NOTE. Databases themselves are not work items.
            for (Map<String, Object> r : all) {
                if (out.size() >= maxItems) break;
                if (!"page".equals(str(r, "object"))) continue;
                String extId = clip(str(r, "id").replace("-", ""));
                if (!seen.add(extId)) continue;
                String title = extractTitle(r);
                Map<String, Object> parent = asMap(r.get("parent"));
                if ("database_id".equals(str(parent, "type"))) {
                    String dbId = clip(str(parent, "database_id").replace("-", ""));
                    addTask(out, extId, title, extractStatus(r), dbTitle.getOrDefault(dbId, "Notion"), order);
                } else {
                    out.add(WorkItemEntity.create(extId, "doc",
                            title.isBlank() ? "(untitled page)" : title,
                            "note", "info", "Notion", source(), order[0]++)
                            .withDetail("Notion page shared with DevLoom.", null));
                }
            }

            // 4) Also query each database directly, to catch rows the search index may lag on.
            for (Map<String, Object> r : all) {
                if ("database".equals(str(r, "object"))) {
                    addDatabaseRows(str(r, "id"), extractTitle(r), out, seen, order);
                }
            }

            log.info("Notion sync: {} items ({} databases)", out.size(), dbTitle.size());
            return out;
        } catch (Exception e) {
            log.warn("Notion sync failed: {}", e.getMessage());
            throw new IllegalStateException("Notion fetch failed", e);
        }
    }

    /** A database row → a task work item, tagged with its database name. */
    private void addTask(List<WorkItemEntity> out, String extId, String title, String status,
                         String dbName, int[] order) {
        String tag = dbName.replace(",", " ").strip(); // shown as a detail chip on the task
        String tone = switch (status.toLowerCase()) {
            case "done", "complete", "completed", "closed", "shipped", "archived" -> "healthy";
            case "in progress", "doing", "in review", "started", "wip" -> "warn";
            default -> "info";
        };
        out.add(WorkItemEntity.create(extId, "task",
                title.isBlank() ? "(untitled task)" : title,
                status.isBlank() ? "task" : status, tone,
                tag, source(), order[0]++)
                .withDetail("From Notion database: " + tag, null));
    }

    /** Query a database's rows and surface any not already seen as task work items. */
    private void addDatabaseRows(String rawDbId, String dbName, List<WorkItemEntity> out,
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
                addTask(out, extId, extractTitle(row), extractStatus(row), dbName, order);
                added++;
            }
        } catch (Exception e) {
            log.warn("Notion database query failed for {}: {}", dbName, e.getMessage());
        }
    }

    private static String clip(String id) {
        return id.length() > 60 ? id.substring(0, 60) : id;
    }

    /**
     * The row's status: prefer a {@code status}-typed property, then a select whose name looks
     * like a status/stage/state, then any select — so we don't mistake Priority for status.
     */
    @SuppressWarnings("unchecked")
    private String extractStatus(Map<String, Object> row) {
        Object props = row.get("properties");
        if (!(props instanceof Map<?, ?> pm)) return "";
        String statusTyped = null, namedSelect = null, anySelect = null;
        for (Map.Entry<?, ?> e : pm.entrySet()) {
            String propName = String.valueOf(e.getKey()).toLowerCase();
            Map<String, Object> prop = asMap(e.getValue());
            String type = str(prop, "type");
            if ("status".equals(type)) {
                String v = str(asMap(prop.get("status")), "name");
                if (!v.isBlank()) statusTyped = v;
            } else if ("select".equals(type)) {
                String v = str(asMap(prop.get("select")), "name");
                if (!v.isBlank()) {
                    if (propName.contains("status") || propName.contains("stage") || propName.contains("state")) {
                        namedSelect = v;
                    } else if (anySelect == null) {
                        anySelect = v;
                    }
                }
            }
        }
        return statusTyped != null ? statusTyped : namedSelect != null ? namedSelect
                : anySelect != null ? anySelect : "";
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
